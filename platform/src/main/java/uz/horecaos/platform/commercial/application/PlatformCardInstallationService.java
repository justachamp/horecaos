package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.commercial.domain.PlatformCardInstallation;
import uz.horecaos.platform.commercial.infrastructure.PlatformCardGateway;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardChargeAttemptStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardTopUpStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlatformCardInstallationStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * HorecaOS's own card merchant account, as an installation the owner completes later (ADR 0095, ADR
 * 0026).
 *
 * <p>No Click or Payme merchant account exists, so nothing here connects to one. What exists is the
 * place the connection will be made and every rule that will govern it: a provider type that must have
 * an adapter wired into this build, an environment that must come from the approved catalogue and never
 * from a typed URL, a credential that is only ever a secret reference, a single ACTIVE account at a
 * time, and a test double that cannot be activated outside a local or test run. Completing the
 * installation is then creating one of a real provider type, activating it, and nothing else — and that
 * waits on the account and on the adapter that speaks to it.
 *
 * <p>Replacing the active account strands the cards bound under the old one: a token is only meaningful
 * to the merchant account that minted it. The gateway refuses such a card by name, so the tenant is asked
 * to add its card again instead of being told its bank declined it.
 *
 * <p>It strands the charges still waiting for an answer too, and those are worse: an idempotency key is
 * meaningful only to the account it was sent to. A top-up or a statement charge that was asked and never
 * answered may have moved the money, and the account that replaces this one cannot say. So an account is
 * not suspended while charges asked through it are unresolved, unless the person suspending it says, in so
 * many words, that they know. Even then the charges are not settled as declined: they stay {@code PENDING}
 * and resolve when this account is active again.
 */
@Service
public class PlatformCardInstallationService {

    private static final int MAX_CONFIG_ENTRIES = 32;

    private final JdbcPlatformCardInstallationStore installations;
    private final PlatformCardGateway gateway;
    private final JdbcCardTopUpStore topUps;
    private final JdbcCardChargeAttemptStore attempts;
    private final AuditRecorder audit;
    private final Clock clock;

    public PlatformCardInstallationService(
            JdbcPlatformCardInstallationStore installations,
            PlatformCardGateway gateway,
            JdbcCardTopUpStore topUps,
            JdbcCardChargeAttemptStore attempts,
            AuditRecorder audit,
            Clock clock) {
        this.installations = installations;
        this.gateway = gateway;
        this.topUps = topUps;
        this.attempts = attempts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlatformCardInstallation> list() {
        return installations.list();
    }

    @Transactional(readOnly = true)
    public PlatformCardInstallation find(UUID id) {
        return installations.find(id).orElseThrow(() -> notFound());
    }

    /** Declares an account in DRAFT. Nothing is charged through it until it is activated. */
    @Transactional
    public UUID create(
            String providerType,
            @Nullable String environmentCode,
            String displayName,
            @Nullable String secretReference,
            @Nullable String externalAccountReference,
            Map<String, Object> configuration,
            ActorRef actor,
            String reason,
            String correlationId) {
        CardProviderAdapter adapter = gateway.adapterFor(providerType)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "This build has no adapter for provider type %s".formatted(providerType),
                        Map.of("reason", "NO_ADAPTER_FOR_PROVIDER_TYPE")));
        if (configuration.size() > MAX_CONFIG_ENTRIES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Configuration holds at most %d entries".formatted(MAX_CONFIG_ENTRIES));
        }
        String environment = blankToNull(environmentCode);
        String secret = blankToNull(secretReference);
        if (adapter.requiresSecret()) {
            if (environment == null || !installations.approvedEnvironmentExists(environment, providerType)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "A payment installation names an environment from the approved catalogue; none is approved "
                                + "for %s as typed".formatted(providerType),
                        Map.of("field", "environmentCode"));
            }
            requirePaymentSecretReference(secret);
        } else if (environment != null || secret != null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s makes no network call and holds no credential, so it names neither an environment nor a secret"
                            .formatted(providerType));
        }
        Instant now = clock.instant();
        UUID id = Ids.newId();
        installations.insert(new PlatformCardInstallation(
                id,
                providerType,
                environment,
                displayName,
                PlatformCardInstallation.DRAFT,
                secret,
                blankToNull(externalAccountReference),
                configuration,
                null,
                null,
                subject(actor),
                null,
                0,
                now,
                now));
        audit.record(AuditFact.of("commercial.card_installation.created", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target("commercial.platform_card_installation", id)
                .because(reason)
                .changed(ChangeDocuments.created(Map.of(
                        "providerType", providerType,
                        "status", PlatformCardInstallation.DRAFT,
                        "displayName", displayName)))
                .usingCapability(Capability.INTEGRATION_INSTALLATION_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return id;
    }

    /** Makes this the account cards are charged through. At most one is active; the previous one is suspended first. */
    @Transactional
    public PlatformCardInstallation activate(
            UUID id, long expectedVersion, ActorRef actor, String reason, String correlationId) {
        PlatformCardInstallation installation = find(id);
        if (!PlatformCardInstallation.DRAFT.equals(installation.status())
                && !PlatformCardInstallation.SUSPENDED.equals(installation.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "An installation that is %s cannot be activated".formatted(installation.status()),
                    Map.of("reason", "NOT_ACTIVATABLE"));
        }
        if (!gateway.mayUse(installation.providerType())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A test double is not activated outside a local or test run",
                    Map.of("reason", "TEST_DOUBLE_NOT_ALLOWED"));
        }
        if (installations.findActive().filter(active -> !active.id().equals(id)).isPresent()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Another installation is active; suspend it first. Cards bound under it will have to be added again.",
                    Map.of("reason", "ANOTHER_INSTALLATION_ACTIVE"));
        }
        return move(
                installation,
                expectedVersion,
                PlatformCardInstallation.ACTIVE,
                subject(actor),
                Map.of(),
                actor,
                reason,
                correlationId);
    }

    /** Stops charging through this account; refused while charges asked through it are unresolved. */
    @Transactional
    public PlatformCardInstallation suspend(
            UUID id, long expectedVersion, ActorRef actor, String reason, String correlationId) {
        return suspend(id, expectedVersion, false, actor, reason, correlationId);
    }

    /**
     * @param acknowledgeUnresolvedCharges the caller knows top-ups or statement charges asked through this
     *     account have no answer yet, and suspends it anyway. They stay {@code PENDING}, the audit fact says
     *     how many, and they resolve when this account is active again.
     */
    @Transactional
    public PlatformCardInstallation suspend(
            UUID id,
            long expectedVersion,
            boolean acknowledgeUnresolvedCharges,
            ActorRef actor,
            String reason,
            String correlationId) {
        PlatformCardInstallation installation = find(id);
        if (!PlatformCardInstallation.ACTIVE.equals(installation.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Only an active installation can be suspended",
                    Map.of("reason", "NOT_ACTIVE"));
        }
        long unresolvedTopUps = topUps.countPendingUnder(id);
        long unresolvedCharges = attempts.countPendingUnder(id);
        Map<String, Object> unresolved = new LinkedHashMap<>();
        if (unresolvedTopUps + unresolvedCharges > 0) {
            if (!acknowledgeUnresolvedCharges) {
                throw new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Card charges asked through this account are still waiting for its answer, and the account "
                                + "that replaces it cannot tell whether they took the money. Wait for the settlement "
                                + "sweep to resolve them, or suspend with acknowledgeUnresolvedCharges to leave them "
                                + "pending until this account is active again.",
                        Map.of(
                                "reason",
                                "UNRESOLVED_CARD_CHARGES",
                                "unresolvedTopUps",
                                unresolvedTopUps,
                                "unresolvedStatementCharges",
                                unresolvedCharges));
            }
            unresolved.put("unresolvedTopUps", unresolvedTopUps);
            unresolved.put("unresolvedStatementCharges", unresolvedCharges);
        }
        return move(
                installation,
                expectedVersion,
                PlatformCardInstallation.SUSPENDED,
                null,
                unresolved,
                actor,
                reason,
                correlationId);
    }

    private PlatformCardInstallation move(
            PlatformCardInstallation installation,
            long expectedVersion,
            String status,
            @Nullable String activatedBy,
            Map<String, Object> alsoRecorded,
            ActorRef actor,
            String reason,
            String correlationId) {
        Instant now = clock.instant();
        if (!installations.transition(installation.id(), expectedVersion, status, activatedBy, now)) {
            throw new ApiException(
                    ErrorCode.STALE_VERSION, "The installation changed since it was read; read it again");
        }
        Map<String, Object> after = withStatus(status, alsoRecorded);
        Map<String, Object> change = ChangeDocuments.diff(Map.of("status", installation.status()), after);
        audit.record(AuditFact.of(
                        "commercial.card_installation." + status.toLowerCase(java.util.Locale.ROOT),
                        AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target("commercial.platform_card_installation", installation.id())
                .because(reason)
                .changed(change)
                .usingCapability(Capability.INTEGRATION_INSTALLATION_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return find(installation.id());
    }

    private static Map<String, Object> withStatus(String status, Map<String, Object> alsoRecorded) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.putAll(alsoRecorded);
        return after;
    }

    private static void requirePaymentSecretReference(@Nullable String reference) {
        if (reference == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A payment installation names the secret holding its credential",
                    Map.of("field", "secretReference"));
        }
        SecretReference parsed;
        try {
            parsed = SecretReference.parse(reference);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Malformed secret reference", Map.of("field", "secretReference"));
        }
        if (parsed.category() != SecretCategory.PROVIDER_PAYMENT) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The secret reference must be in the provider_payment category",
                    Map.of("field", "secretReference"));
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such card installation");
    }
}
