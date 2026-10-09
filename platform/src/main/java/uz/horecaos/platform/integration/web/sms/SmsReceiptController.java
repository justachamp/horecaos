package uz.horecaos.platform.integration.web.sms;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import uz.horecaos.platform.integration.camel.notification.NotificationChannelAdapter;
import uz.horecaos.platform.integration.camel.notification.NotificationChannelAdapter.ReceiptAuthentication;
import uz.horecaos.platform.integration.camel.notification.ReceiptEvent;
import uz.horecaos.platform.integration.provider.sms.SmsReceiptInbox;
import uz.horecaos.platform.integration.provider.sms.SmsReceiptInstallationLookup;
import uz.horecaos.platform.integration.provider.sms.SmsReceiptInstallationLookup.ReceiptInstallation;
import uz.horecaos.platform.notifications.api.DeliveryReceiptPort.ReceiptCommand;
import uz.horecaos.platform.notifications.api.DeliveryReceiptPort.ReceiptDisposition;
import uz.horecaos.platform.notifications.api.ReceiptEnablement;

/**
 * An SMS gateway's delivery receipts (ADR 0146 Decision 4).
 *
 * <p>{@code permitAll} on the filter chain and authenticated here, as the Click and
 * Telegram endpoints are: a provider holds no session. What it can prove depends on
 * the gateway. Where the provider can carry a secret, a per-installation one held as
 * an ADR 0028 reference is compared in constant time. Where it cannot (VAS), the
 * provider's published source addresses are allowed at the edge
 * ({@code deploy/infra/caddy/Caddyfile}) and the request itself proves nothing, so
 * the controls that remain are the ones that limit what a forgery could do: the id
 * must name an attempt made under this installation, a receipt can only advance it,
 * and an unknown id creates nothing.
 *
 * <p><strong>Every refusal is the same 404.</strong> An unknown installation, one
 * that is not active, a provider type with no receipt source, and a wrong or missing
 * secret answer identically, in the same time: installation ids are v7 and so
 * guessable by creation window, and an endpoint that told "no such installation"
 * from "wrong secret" would be an oracle for which ones exist. A type with no
 * receipt source answers 404 by design: with nothing listening, no receipt is read
 * for it by any means.
 *
 * <p>Authenticated but unusable (unparseable, unrecognised status, unknown message)
 * answers 200 and is counted: the provider retries a non-2xx delivery, and retrying
 * a body that will never apply only fills the queue.
 *
 * <p>Hidden from the published OpenAPI document, like the other provider callbacks:
 * the contract is the gateway's, not HorecaOS's. The request body is never logged,
 * and a metric label is a bounded enum, never a number, a text or a message id.
 */
@RestController
@RequestMapping("/providers/sms")
@Hidden
public class SmsReceiptController {

    static final String SECRET_HEADER = "X-HorecaOS-Receipt-Secret";

    private static final Logger log = LoggerFactory.getLogger(SmsReceiptController.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SmsReceiptInstallationLookup installations;
    private final List<NotificationChannelAdapter> adapters;
    private final ReceiptEnablement enablement;
    private final SecretResolver secrets;
    private final SecretIngressGateway door;
    private final SmsReceiptInbox inbox;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    /** Minted once and reused, so a probe of a missing installation costs what a real one does. */
    private volatile @Nullable SecretReference decoySecretReference;

    public SmsReceiptController(
            SmsReceiptInstallationLookup installations,
            List<NotificationChannelAdapter> adapters,
            ReceiptEnablement enablement,
            SecretResolver secrets,
            SecretIngressGateway door,
            SmsReceiptInbox inbox,
            ObjectMapper objectMapper,
            MeterRegistry meters) {
        this.installations = installations;
        this.adapters = adapters;
        this.enablement = enablement;
        this.secrets = secrets;
        this.door = door;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    @PostMapping("/{installationId}/receipts")
    public ResponseEntity<Void> receipt(
            @PathVariable UUID installationId,
            @RequestHeader(value = SECRET_HEADER, required = false) @Nullable String presentedSecret,
            @RequestBody byte[] rawBody) {

        ReceiptInstallation found = installations.find(installationId).orElse(null);
        NotificationChannelAdapter adapterFound = found == null ? null : adapterFor(found.providerType());

        // Present only when everything that makes the endpoint answer at all is.
        ReceiptInstallation installation = found != null
                        && adapterFound != null
                        && "ACTIVE".equals(found.status())
                        && enablement.isEnabled(found.providerType())
                ? found
                : null;
        NotificationChannelAdapter adapter = installation == null ? null : adapterFound;
        boolean needsSecret = adapter != null && adapter.receiptAuthentication() == ReceiptAuthentication.SECRET_HEADER;

        // Resolved whatever the answer will be, so the cost does not say which case
        // this was. The decoy is a real secret nothing ever reveals.
        String expected = null;
        if (installation != null && needsSecret && installation.webhookSecretReference() != null) {
            expected = secrets.resolve(SecretReference.parse(installation.webhookSecretReference()))
                    .reveal();
        } else if (installation == null || needsSecret) {
            expected = secrets.resolve(decoy()).reveal();
        }

        if (installation == null || adapter == null) {
            count(found == null ? "unknown" : found.providerType(), "unauthenticated");
            return ResponseEntity.notFound().build();
        }
        if (needsSecret) {
            boolean authenticated = installation.webhookSecretReference() != null
                    && presentedSecret != null
                    && expected != null
                    && constantTimeEquals(presentedSecret, expected);
            if (!authenticated) {
                log.warn("An SMS receipt for installation {} was not authenticated", installationId);
                count(installation.providerType(), "unauthenticated");
                return ResponseEntity.notFound().build();
            }
        }

        Optional<ReceiptEvent> event = read(adapter, rawBody);
        if (event.isEmpty()) {
            count(installation.providerType(), "unreadable");
            return ResponseEntity.ok().build();
        }

        ReceiptEvent receipt = event.get();
        ReceiptDisposition disposition = inbox.accept(
                installation.providerType(),
                new ReceiptCommand(
                        installation.tenantId(),
                        installationId,
                        installations.bindingIds(installation.tenantId(), installationId),
                        receipt.providerMessageId(),
                        receipt.normalizedStatus(),
                        receipt.providerStatus(),
                        receipt.occurredAt(),
                        receipt.hardBounce()));
        count(installation.providerType(), disposition.tag());
        return ResponseEntity.ok().build();
    }

    private Optional<ReceiptEvent> read(NotificationChannelAdapter adapter, byte[] rawBody) {
        try {
            Map<String, Object> parsed = rawBody.length == 0 ? Map.of() : objectMapper.readValue(rawBody, MAP_TYPE);
            return adapter.normalise(parsed);
        } catch (RuntimeException unreadable) {
            // The class only: a parser message can quote the body.
            log.warn(
                    "An SMS receipt body could not be read: {}",
                    unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private @Nullable NotificationChannelAdapter adapterFor(String providerType) {
        return adapters.stream()
                .filter(adapter -> "SMS".equals(adapter.channel())
                        && adapter.providerType().equals(providerType))
                .findFirst()
                .orElse(null);
    }

    private void count(String providerType, String outcome) {
        meters.counter("horecaos.sms.receipts", "provider", providerType, "outcome", outcome)
                .increment();
    }

    private SecretReference decoy() {
        SecretReference existing = decoySecretReference;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (decoySecretReference == null) {
                decoySecretReference = door.write(
                        SecretCategory.PROVIDER_NOTIFICATION,
                        "sms-receipt-timing-decoy",
                        SecretValue.of(UUID.randomUUID().toString()));
            }
            return decoySecretReference;
        }
    }

    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
