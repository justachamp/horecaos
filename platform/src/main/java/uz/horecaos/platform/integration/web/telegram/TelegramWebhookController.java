package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.provider.telegram.TelegramUpdateHandler;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;

/**
 * The Bot API webhook (ADR 0058).
 *
 * <p>Hard rule, in the exact order the ADR states it: verify the
 * {@code X-Telegram-Bot-Api-Secret-Token} header against the installation's own
 * webhook secret reference <em>before</em> touching the body at all. The header
 * is compared with {@link MessageDigest#isEqual}, not {@code String.equals}, for
 * the same reason every credential comparison in this codebase is: a
 * variable-time comparison leaks how many leading bytes matched to anyone who
 * can measure response latency, and a forged token guess only has to be right
 * once.
 *
 * <p><strong>The "no oracle" invariant covers cost, not just content.</strong>
 * Status and body alone are not enough: a not-found/inactive installation id
 * used to answer after one indexed lookup, while a real, {@code ACTIVE},
 * webhook-registered id paid one extra {@link SecretResolver#resolve} call
 * before the same 403 -- observable as latency the instant this endpoint
 * became reachable without authentication. Installation ids are v7 (guessable
 * by creation-time window), so {@link #webhook} always resolves something --
 * the real reference when one exists, {@link #decoyWebhookSecretReference} otherwise
 * -- before any branch decides to refuse.
 *
 * <p>{@code local}-profile development has no public URL for Telegram to reach,
 * so this controller is registered in every profile but exercised only through
 * staging/production webhook registration or a test that POSTs to it directly;
 * {@code TelegramLongPollingConsumer} is the local-dev path.
 *
 * <p>Hidden from the published OpenAPI document for the same reason
 * {@code ClickShopApiController} is: this is Telegram's contract, not
 * HorecaOS's.
 */
@RestController
@RequestMapping("/providers/telegram")
@Hidden
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);
    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TelegramWebhookInstallationLookup installations;
    private final SecretResolver secrets;
    private final SecretIngressGateway door;
    private final TelegramUpdateHandler handler;
    private final ObjectMapper objectMapper;

    /**
     * Minted once, lazily, and reused for every not-found/inactive call from
     * then on -- so it warms into {@code SecretResolver}'s own cache exactly
     * like a frequently-hit real installation's reference would, rather than
     * paying a full uncached resolve (a slower, and so newly distinguishable,
     * shape of work) on every probe.
     */
    private volatile @Nullable SecretReference decoyWebhookSecretReference;

    public TelegramWebhookController(
            TelegramWebhookInstallationLookup installations,
            SecretResolver secrets,
            SecretIngressGateway door,
            TelegramUpdateHandler handler,
            ObjectMapper objectMapper) {
        this.installations = installations;
        this.secrets = secrets;
        this.door = door;
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{installationId}/webhook")
    public ResponseEntity<Void> webhook(
            @PathVariable UUID installationId,
            @RequestHeader(value = SECRET_HEADER, required = false) String presentedToken,
            @RequestBody byte[] rawBody) {

        WebhookInstallation installation = installations.find(installationId).orElse(null);

        // Resolved unconditionally -- the real reference when the installation
        // is genuinely there, a fixed decoy otherwise -- so this call costs
        // the same either way. See the class doc.
        String expected;
        if (installation != null
                && installation.webhookSecretReference() != null
                && "ACTIVE".equals(installation.status())) {
            expected = secrets.resolve(SecretReference.parse(installation.webhookSecretReference()))
                    .reveal();
        } else {
            // Normalized to null: an inactive or webhook-less installation is
            // refused exactly like no installation at all, below.
            installation = null;
            expected = secrets.resolve(decoyWebhookSecretReference()).reveal();
        }

        if (installation == null) {
            // Not found and not authenticated read identically: an installation
            // id is guessable by design (Click's own binding-in-path comment
            // makes the same point), so this response must not distinguish "no
            // such installation" from "wrong token".
            return ResponseEntity.status(403).build();
        }

        if (presentedToken == null || !constantTimeEquals(presentedToken, expected)) {
            log.warn("Telegram webhook for installation {} presented an invalid secret token", installationId);
            return ResponseEntity.status(403).build();
        }

        Map<String, Object> update;
        try {
            update = rawBody.length == 0 ? Map.of() : objectMapper.readValue(rawBody, MAP_TYPE);
        } catch (RuntimeException malformed) {
            // Authenticated but unparseable. Answered 200 anyway: Telegram
            // retries a non-2xx delivery, and retrying a body that will never
            // parse just burns the platform's own webhook queue.
            log.warn("Telegram webhook for installation {} carried a body that could not be parsed", installationId);
            return ResponseEntity.ok().build();
        }

        try {
            handler.handle(installation, update);
        } catch (RuntimeException failure) {
            log.error("Telegram update handling failed for installation {}", installationId, failure);
        }
        return ResponseEntity.ok().build();
    }

    private SecretReference decoyWebhookSecretReference() {
        SecretReference existing = decoyWebhookSecretReference;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (decoyWebhookSecretReference == null) {
                // A real, resolvable secret -- never revealed to any caller,
                // never checked against anything -- purely so resolving it
                // costs the same shape of work (and, once warm, the same
                // cache-hit speed) as resolving a real installation's own
                // webhook secret reference would.
                decoyWebhookSecretReference = door.write(
                        SecretCategory.PROVIDER_NOTIFICATION,
                        "telegram-webhook-timing-decoy",
                        SecretValue.of(UUID.randomUUID().toString()));
            }
            return decoyWebhookSecretReference;
        }
    }

    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
