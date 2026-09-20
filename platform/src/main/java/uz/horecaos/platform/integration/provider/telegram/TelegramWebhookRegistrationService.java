package uz.horecaos.platform.integration.provider.telegram;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Closes the gap {@code TelegramWebhookController}'s own class doc names: the
 * header it checks on every inbound call is written by nothing (ADR 0058).
 * Without this, a connected, {@code ACTIVE} Telegram installation can never
 * receive an update on a deployed host, and ADR 0063's storefront
 * "Continue with Telegram" sign-in can never complete — the only documented
 * path before this class was raw SQL plus a manual {@code curl} carrying the
 * bot token (see {@code docs/runbooks/sendpulse-cutover.md}), which is exactly
 * the ADR 0065 door this class closes for webhook registration too.
 *
 * <p><strong>Sequence, deliberately not one transaction.</strong> {@code
 * setWebhook} is an external HTTP call to Telegram, and this codebase's own
 * rule is that an external call never runs inside a database transaction —
 * see {@code ProviderInstallationController.rotateSecret}, which resolves the
 * new secret and calls {@code getMe} before it ever opens a write. Here the
 * order is: mint and write a fresh webhook secret through the ADR 0065 door
 * (its own, independent write — the same "write, then verify, then use"
 * shape {@code ProviderInstallationController.rotateSecretByValue} already
 * follows), call Telegram, and only on success run one short transaction that
 * updates {@code integration.installations} and records the ADR 0027 audit
 * fact together. A Telegram failure leaves the database exactly as it was;
 * only Telegram's own answer decides whether the new reference is ever
 * pointed at.
 *
 * <p><strong>Re-running is rotation.</strong> There is no separate "first
 * registration" vs. "re-registration" branch: every call mints a new secret
 * token, asks Telegram to replace whatever webhook it has on file (Telegram's
 * own {@code setWebhook} is unconditionally idempotent — "atomic per bot and
 * reversible by the same call", ADR 0059), and only then swaps the column.
 * The old token stops working the instant Telegram accepts the new one; there
 * is no window where both are valid.
 */
@Service
public class TelegramWebhookRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookRegistrationService.class);

    /** Duplicated rather than imported, the same reason {@code ProviderInstallationController} does. */
    private static final String TELEGRAM_BOT_API = "TELEGRAM_BOT_API";

    private static final String ACTIVE_STATUS = "ACTIVE";

    /** Telegram's own allowed alphabet for a webhook {@code secret_token}. */
    private static final char[] SECRET_TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toCharArray();

    private static final int SECRET_TOKEN_LENGTH = 64;

    /**
     * Verified against {@code TelegramUpdateHandler#handle}, not assumed:
     * that method branches on exactly {@code callback_query} and {@code
     * message} (a share-contact message is a {@code message} carrying a
     * {@code contact} field, not a separate update type) — nothing in this
     * codebase reads {@code my_chat_member}. Naming an update type Telegram
     * would then deliver into a handler that silently drops it is worse than
     * a shorter, honest list: {@code TelegramRightsVerifier} already answers
     * "is the bot still an admin here" with a live {@code getChatMember} call
     * instead, so no group-membership webhook update is needed for that
     * either.
     */
    private static final List<String> ALLOWED_UPDATES = List.of("message", "callback_query");

    /** Matches {@code SecretsProfileGuard}'s and {@code PresetVerificationCodeGuard}'s own set. */
    private static final Set<String> LOCAL_PROFILES = Set.of("local", "test", "default");

    private final JdbcClient jdbc;
    private final SecretIngressGateway door;
    private final SecretResolver secrets;
    private final TelegramBotApiClient bots;
    private final AuditRecorder audit;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;
    private final Environment environment;
    private final String publicApiOrigin;
    private final SecureRandom secureRandom = new SecureRandom();

    public TelegramWebhookRegistrationService(
            JdbcClient jdbc,
            SecretIngressGateway door,
            SecretResolver secrets,
            TelegramBotApiClient bots,
            AuditRecorder audit,
            TransactionTemplate unitOfWork,
            Clock clock,
            Environment environment,
            @Value("${horecaos.public-api-origin:http://localhost:8080}") String publicApiOrigin) {
        this.jdbc = jdbc;
        this.door = door;
        this.secrets = secrets;
        this.bots = bots;
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.environment = environment;
        this.publicApiOrigin = publicApiOrigin;
    }

    /**
     * Registers, or re-registers (rotates), this installation's webhook.
     *
     * @param actor the authenticated caller, recorded on the ADR 0027 audit
     *              fact this method writes on success
     * @throws ApiException RESOURCE_NOT_FOUND for an unknown installation or
     *                       one belonging to another tenant (tenant isolation:
     *                       the two answer identically); UNPROCESSABLE_STATE
     *                       for a non-Telegram or non-ACTIVE installation, an
     *                       unresolvable bot token, a non-https public origin
     *                       outside local/test, or a Telegram refusal;
     *                       RESOURCE_CONFLICT when the row this call read has
     *                       moved on by the time of the write -- retired, or
     *                       raced by another overlapping registration call --
     *                       so retry rather than silently overwrite it
     */
    public Registration register(UUID tenantId, UUID installationId, ActorRef actor) {
        requireHttpsOriginUnlessLocal();

        Installation installation = findInstallation(tenantId, installationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Installation is not available"));

        if (!TELEGRAM_BOT_API.equals(installation.providerType())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Webhook registration is defined for TELEGRAM_BOT_API installations only, not "
                            + installation.providerType());
        }
        if (!ACTIVE_STATUS.equals(installation.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Webhook registration requires an ACTIVE installation, not " + installation.status());
        }

        SecretValue botToken;
        try {
            // Fresh, not cached: the whole point of this call is to prove the
            // bot token this installation is on file with actually resolves
            // right now, the same reasoning rotateSecret's own resolveFresh
            // call documents.
            botToken = secrets.resolveFresh(SecretReference.parse(installation.secretReference()));
        } catch (RuntimeException unresolved) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The installation's bot token does not resolve: " + unresolved.getMessage());
        }

        String secretToken = generateSecretToken();
        // Written before Telegram is ever called, mirroring
        // rotateSecretByValue's own "write, then verify" shape: a value that
        // never becomes reachable from a database row is an accepted,
        // unreferenced orphan in the secrets manager (ADR 0028's rollback
        // posture), never a leak.
        SecretReference webhookReference =
                door.write(SecretCategory.PROVIDER_NOTIFICATION, "tenant-" + tenantId, SecretValue.of(secretToken));

        String webhookUrl = publicApiOrigin + "/providers/telegram/" + installationId + "/webhook";
        TelegramCallResult result = bots.setWebhook(
                new ProviderCall(installation.baseUrl(), botToken.reveal(), null, Duration.ofSeconds(15)),
                webhookUrl,
                secretToken,
                ALLOWED_UPDATES);

        if (result instanceof TelegramCallResult.Uncertain uncertain) {
            // Not a confirmed refusal: Telegram's own answer could not be
            // read (per TelegramCallResult.Uncertain's own doc), so Telegram
            // may already have applied the new secret and URL even though
            // this call never saw an "ok" answer. The UPDATE below never
            // runs either way, so the database stays exactly as it was --
            // but saying "rejected" here would send an operator chasing the
            // wrong cause (a bad bot token or URL) instead of the right one.
            // setWebhook is unconditionally idempotent per bot (ADR 0059),
            // so retrying resolves the ambiguity regardless of what Telegram
            // actually did with this call.
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Telegram's answer to setWebhook could not be confirmed (" + describe(uncertain) + "). "
                            + "Telegram may have already applied the new webhook secret even though this call "
                            + "did not confirm it -- retry registration to resynchronize; this is not a "
                            + "rejection of the bot token or URL.");
        }

        if (!(result instanceof TelegramCallResult.Success)) {
            // Nothing in the database changes: the UPDATE below never runs.
            // The freshly-written secret above stays an orphaned, unreferenced
            // value in the secrets manager -- never a leak, never pointed at
            // by any row.
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "Telegram rejected the webhook registration: " + describe(result));
        }

        OffsetDateTime registeredAt = OffsetDateTime.now(clock);
        try {
            unitOfWork.executeWithoutResult(ignored -> {
                int changed = jdbc.sql("""
                        UPDATE integration.installations
                           SET webhook_secret_reference = :reference,
                               version = version + 1,
                               updated_at = :now,
                               non_sensitive_config = jsonb_set(
                                   jsonb_set(non_sensitive_config, '{webhookRegisteredAt}',
                                             to_jsonb(:registeredAt::text), true),
                                   '{webhookUrl}', to_jsonb(:webhookUrl::text), true)
                         WHERE id = :id AND tenant_id = :tenantId
                           AND webhook_secret_reference IS NOT DISTINCT FROM :oldReference
                        """)
                        .param("reference", webhookReference.toString())
                        .param("now", registeredAt)
                        .param("registeredAt", registeredAt.toString())
                        .param("webhookUrl", webhookUrl)
                        .param("id", installationId)
                        .param("tenantId", tenantId)
                        .param("oldReference", installation.webhookSecretReference())
                        .update();

                if (changed == 0) {
                    // Mirrors ProviderInstallationController#rotateSecretByValue's
                    // own CAS: with the predicate above, "no rows changed" now
                    // means either the row this method's own read found is gone
                    // (a retirement racing this call) or another registration
                    // call already moved webhook_secret_reference off the value
                    // this call verified against -- two overlapping registrations
                    // whose Telegram calls and database commits interleaved.
                    // Either way there is nothing safe to overwrite with a
                    // reference verified against a row that has moved on, so
                    // this is answered the same way that sibling method answers
                    // its own identical race: a conflict, not a silent
                    // overwrite that would leave the database pointing at a
                    // secret Telegram no longer honors.
                    throw new ApiException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "This installation's webhook registration changed while this call was in flight; "
                                    + "retry registration");
                }

                audit.record(AuditFact.of("integration.webhook_registered", AuditClass.SECURITY)
                        .by(actor)
                        .at(ResourceScope.tenant(tenantId))
                        .target("Integration", installationId)
                        .because("Telegram webhook registered")
                        // Reference NAME only, per ADR 0028 -- the token itself
                        // never reaches this class beyond the one door.write()
                        // and setWebhook() calls above.
                        .changed(
                                Map.of("webhookSecretReference", webhookReference.toString(), "webhookUrl", webhookUrl))
                        .usingCapability(Capability.INTEGRATION_INSTALLATION_MANAGE.code())
                        .correlatedBy(installationId.toString())
                        .occurredAt(registeredAt.toInstant())
                        .build());
            });
        } catch (DataAccessException persistFailure) {
            // Telegram has already accepted webhookReference by this point
            // (result is a Success): only the local write failed. Silence
            // here is exactly the split-brain this class exists to avoid --
            // the installation now expects a secret the platform never
            // recorded, and TelegramWebhookController will 403 every real
            // delivery until someone notices. Logged with the installation
            // id and the reference NAME only (ADR 0028: never the token
            // itself), and re-thrown as a clear, ADR-0031-shaped failure
            // that tells the operator to retry, instead of falling through
            // to GlobalApiErrorHandler's unhandled-exception default.
            log.error(
                    "Telegram accepted the webhook registration for installation {} (reference {}) but "
                            + "recording it failed; the stored secret is now out of sync with Telegram until "
                            + "this is retried",
                    installationId,
                    webhookReference,
                    persistFailure);
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Telegram accepted the new webhook but the platform failed to record it. Retry "
                            + "registration to resynchronize the stored secret with Telegram's.");
        }

        return new Registration(installationId, webhookUrl, registeredAt.toInstant(), installation.botUsername());
    }

    /**
     * Telegram requires https for a webhook URL. Refused outside a local/test
     * profile so a misconfigured {@code horecaos.public-api-origin} fails
     * loudly at call time rather than registering a webhook Telegram will
     * itself reject with a less legible error — or, worse, an origin that
     * happens to also answer on http and silently carries the secret token in
     * clear text.
     */
    private void requireHttpsOriginUnlessLocal() {
        if (publicApiOrigin.regionMatches(true, 0, "https://", 0, "https://".length())) {
            return;
        }
        List<String> active = List.of(environment.getActiveProfiles());
        boolean localOnly = active.isEmpty() || active.stream().allMatch(LOCAL_PROFILES::contains);
        if (localOnly) {
            return;
        }
        throw new ApiException(
                ErrorCode.UNPROCESSABLE_STATE,
                "Telegram requires an https webhook origin; horecaos.public-api-origin is not https and "
                        + "the active profile (" + active + ") is not local/test");
    }

    private String generateSecretToken() {
        StringBuilder token = new StringBuilder(SECRET_TOKEN_LENGTH);
        for (int i = 0; i < SECRET_TOKEN_LENGTH; i++) {
            token.append(SECRET_TOKEN_ALPHABET[secureRandom.nextInt(SECRET_TOKEN_ALPHABET.length)]);
        }
        return token.toString();
    }

    private Optional<Installation> findInstallation(UUID tenantId, UUID installationId) {
        return jdbc.sql("""
                SELECT i.id, i.provider_type, i.status, i.secret_reference,
                       i.webhook_secret_reference, e.base_url,
                       i.non_sensitive_config ->> 'botUsername' AS bot_username
                  FROM integration.installations i
                  JOIN integration.provider_environments e ON e.code = i.environment_code
                 WHERE i.id = :installationId AND i.tenant_id = :tenantId
                """)
                .param("installationId", installationId)
                .param("tenantId", tenantId)
                .query((row, number) -> new Installation(
                        row.getObject("id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("status"),
                        row.getString("secret_reference"),
                        row.getString("webhook_secret_reference"),
                        row.getString("base_url"),
                        row.getString("bot_username")))
                .optional();
    }

    private static String describe(TelegramCallResult result) {
        return switch (result) {
            case TelegramCallResult.Success ignored -> "unreachable";
            case TelegramCallResult.Retryable retryable -> retryable.errorCode() + ": " + retryable.detail();
            case TelegramCallResult.Uncertain uncertain -> uncertain.errorCode() + ": " + uncertain.detail();
            case TelegramCallResult.BusinessRejected rejected -> rejected.errorCode() + ": " + rejected.detail();
            case TelegramCallResult.BindingRetirement retirement -> retirement.reason() + ": " + retirement.detail();
            case TelegramCallResult.ChatMigrated ignored -> "unexpected chat migration answer from setWebhook";
        };
    }

    private record Installation(
            UUID id,
            String providerType,
            String status,
            String secretReference,
            @Nullable String webhookSecretReference,
            String baseUrl,
            @Nullable String botUsername) {}

    /** @param botUsername null when this installation has never resolved one (see {@code TelegramBotIdentityResolver}) */
    public record Registration(
            UUID installationId,
            String webhookUrl,
            Instant registeredAt,
            @Nullable String botUsername) {}
}
