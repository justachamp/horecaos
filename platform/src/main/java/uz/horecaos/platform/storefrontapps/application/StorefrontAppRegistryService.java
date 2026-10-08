package uz.horecaos.platform.storefrontapps.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppClientType;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppAuthorisationView;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppConformance;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppDetailView;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppSummaryView;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppView;
import uz.horecaos.platform.storefrontapps.domain.AppOrigin;
import uz.horecaos.platform.storefrontapps.domain.ConformanceStatus;
import uz.horecaos.platform.storefrontapps.domain.StorefrontAppStatus;
import uz.horecaos.platform.storefrontapps.infrastructure.persistence.JdbcStorefrontAppStore;
import uz.horecaos.platform.storefrontapps.infrastructure.persistence.JdbcStorefrontAppStore.AppRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The platform's registry of storefront apps (ADR 0070): registering one, changing
 * it, suspending or retiring it, rotating a confidential client's secret, and
 * recording what the conformance suite said.
 *
 * <p>Every method here is platform staff's, and each writes its audit fact in the
 * same transaction as the change it describes (ADR 0027).
 *
 * <p><strong>The secret manager is called before a transaction opens, never inside
 * one.</strong> The same ordering {@code PartnerApiClientService} keeps for the same
 * reason: a database rollback cannot take back a write the manager already made, so
 * the write goes first and the row commits only once it has succeeded. A failed
 * insert leaves a value behind a reference nothing points at, which authorises
 * nothing.
 *
 * <p>The plaintext secret exists in exactly one place outside the manager: the return
 * value of {@link #register} or {@link #rotateSecret}, handed to the caller once. Nothing
 * here retains it.
 */
@Service
public class StorefrontAppRegistryService {

    /**
     * The owner scope of every app secret's reference: platform-derived and constant, never a
     * tenant's. An app is registered once and authorised by many tenants, so no one tenant owns
     * its secret.
     */
    static final String SECRET_OWNER_SCOPE = "platform-storefront-apps";

    /** The prefix a confidential client's secret carries, so a leaked one is recognisable on sight. */
    static final String SECRET_PREFIX = "sfs_";

    private static final int SECRET_BYTES = 32;
    private static final int MAX_ORIGINS = 20;
    private static final Pattern CONTRACT_VERSION = Pattern.compile("v[0-9]{1,3}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcStorefrontAppStore store;
    private final SecretIngressGateway door;
    private final AuditRecorder audit;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;
    private final String contractVersion;

    public StorefrontAppRegistryService(
            JdbcStorefrontAppStore store,
            SecretIngressGateway door,
            AuditRecorder audit,
            TransactionTemplate unitOfWork,
            Clock clock,
            @Value("${horecaos.api.version}") String contractVersion) {
        this.store = store;
        this.door = door;
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.contractVersion = contractVersion;
    }

    // ------------------------------------------------------------------- reads

    public List<StorefrontAppSummaryView> list() {
        return store.listApps().stream()
                .map(summary -> new StorefrontAppSummaryView(
                        view(summary.app()), summary.activeAuthorisations(), summary.activeTenants()))
                .toList();
    }

    public StorefrontAppDetailView detail(UUID appId) {
        AppRow app = require(appId);
        List<StorefrontAppAuthorisationView> authorisations = store.authorisationsForApp(appId).stream()
                .map(row -> new StorefrontAppAuthorisationView(
                        row.id(),
                        row.tenantId(),
                        row.tenantName(),
                        row.brandId(),
                        row.brandName(),
                        row.status(),
                        row.grantedBy(),
                        row.grantedAt(),
                        row.revokedBy(),
                        row.revokedAt(),
                        row.version()))
                .toList();
        return new StorefrontAppDetailView(view(app), authorisations);
    }

    // ------------------------------------------------------------------ writes

    public record RegisterCommand(
            String name, String vendor, StorefrontAppClientType clientType, boolean firstParty, List<String> origins) {}

    /** The app, and the secret it was just given — present for a confidential client, once. */
    public record Registered(
            StorefrontAppView app, @Nullable String secretValue) {

        /** Redacted: Spring's message converters log a deserialised body at TRACE (ADR 0028). */
        @Override
        public String toString() {
            return "Registered[app=" + app.id() + ", secretValue=" + (secretValue == null ? "none" : "REDACTED") + "]";
        }
    }

    public Registered register(RegisterCommand command, ActorRef actor, String reason) {
        String name = requireText(command.name(), "name", 120);
        String vendor = requireText(command.vendor(), "vendor", 160);
        List<String> origins = normaliseOrigins(command.origins());
        if (command.clientType() == StorefrontAppClientType.PUBLIC && origins.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A public client is held to its origin allowlist and nothing else; register at least one origin");
        }

        Instant now = clock.instant();
        String secretValue = null;
        String reference = null;
        Instant rotatedAt = null;
        if (command.clientType() == StorefrontAppClientType.CONFIDENTIAL) {
            secretValue = mintSecret();
            reference = door.write(
                            SecretCategory.PROVIDER_STOREFRONT_APP, SECRET_OWNER_SCOPE, SecretValue.of(secretValue))
                    .toString();
            rotatedAt = now;
        }

        UUID id = Ids.newId();
        AppRow row = new AppRow(
                id,
                name,
                vendor,
                command.clientType(),
                command.firstParty(),
                origins,
                reference,
                rotatedAt,
                StorefrontAppStatus.ACTIVE,
                "NOT_RUN",
                null,
                null,
                null,
                actor.subject(),
                0,
                now,
                now);
        try {
            unitOfWork.executeWithoutResult(status -> {
                store.insertApp(row);
                record("storefront_app.registered", id, actor, reason, now, new LinkedHashMap<>(), snapshot(row));
            });
        } catch (DuplicateKeyException sameName) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This vendor already has a storefront app with that name");
        }
        return new Registered(view(row), secretValue);
    }

    public StorefrontAppView update(
            UUID appId,
            long expectedVersion,
            String name,
            String vendor,
            List<String> origins,
            ActorRef actor,
            String reason) {
        AppRow before = require(appId);
        requireNotRetired(before);
        String newName = requireText(name, "name", 120);
        String newVendor = requireText(vendor, "vendor", 160);
        List<String> newOrigins = normaliseOrigins(origins);
        if (before.clientType() == StorefrontAppClientType.PUBLIC && newOrigins.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A public client keeps at least one origin; it has no other protection");
        }
        Instant now = clock.instant();
        try {
            unitOfWork.executeWithoutResult(status -> {
                if (!store.updateDetails(appId, expectedVersion, newName, newVendor, newOrigins, now)) {
                    throw stale(appId, expectedVersion);
                }
                AppRow after = require(appId);
                record("storefront_app.updated", appId, actor, reason, now, snapshot(before), snapshot(after));
            });
        } catch (DuplicateKeyException sameName) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This vendor already has a storefront app with that name");
        }
        return view(require(appId));
    }

    /**
     * Moves an app between ACTIVE, SUSPENDED and RETIRED.
     *
     * <p>Takes effect on the next storefront request of every tenant, because the identity check
     * reads the app's row each time. RETIRED is final: the row stays so an order that names the
     * app still resolves, but nothing can bring it back.
     */
    public StorefrontAppView changeStatus(
            UUID appId, long expectedVersion, StorefrontAppStatus target, ActorRef actor, String reason) {
        AppRow before = require(appId);
        if (before.status() == StorefrontAppStatus.RETIRED) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "A retired app can never serve again");
        }
        if (before.status() == target) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This app is already " + target.name().toLowerCase());
        }
        Instant now = clock.instant();
        unitOfWork.executeWithoutResult(status -> {
            if (!store.updateStatus(appId, expectedVersion, target, now)) {
                throw stale(appId, expectedVersion);
            }
            record(
                    "storefront_app." + target.name().toLowerCase(Locale.ROOT) + "_set",
                    appId,
                    actor,
                    reason,
                    now,
                    fields("status", before.status().name()),
                    fields("status", target.name()));
        });
        return view(require(appId));
    }

    /**
     * Mints a fresh secret for a confidential client and returns it once.
     *
     * <p>The reference changes with every rotation (the door always mints a new one), and the row
     * points at the new one in the same transaction as the audit fact, so a request in flight
     * either finds the old value behind the old reference or the new one behind the new — never a
     * reference with nothing behind it. The previous value stays in the manager, untouched, until
     * the platform's own retention clears it; it authorises nothing because nothing refers to it.
     */
    public Registered rotateSecret(UUID appId, long expectedVersion, ActorRef actor, String reason) {
        AppRow before = require(appId);
        requireNotRetired(before);
        if (before.clientType() != StorefrontAppClientType.CONFIDENTIAL) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A public client holds no secret; a browser keeps none, so there is nothing to rotate");
        }
        String secretValue = mintSecret();
        String reference = door.write(
                        SecretCategory.PROVIDER_STOREFRONT_APP, SECRET_OWNER_SCOPE, SecretValue.of(secretValue))
                .toString();
        Instant now = clock.instant();
        unitOfWork.executeWithoutResult(status -> {
            if (!store.replaceSecretReference(appId, expectedVersion, reference, now)) {
                throw stale(appId, expectedVersion);
            }
            record(
                    "storefront_app.secret_rotated",
                    appId,
                    actor,
                    reason,
                    now,
                    fields("secretRotatedAt", before.secretRotatedAt()),
                    fields("secretRotatedAt", now));
        });
        return new Registered(view(require(appId)), secretValue);
    }

    /**
     * Records the conformance suite's verdict against the contract this platform serves.
     *
     * <p>Refused for any other contract version: a result recorded against a contract that is not
     * the one being served is not evidence about it.
     */
    public StorefrontAppView recordConformance(
            UUID appId,
            long expectedVersion,
            ConformanceStatus result,
            String reportedContractVersion,
            @Nullable String note,
            ActorRef actor,
            String reason) {
        if (result != ConformanceStatus.PASSED && result != ConformanceStatus.FAILED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A conformance result is PASSED or FAILED");
        }
        if (!CONTRACT_VERSION.matcher(reportedContractVersion).matches()
                || !reportedContractVersion.equals(contractVersion)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A conformance result is recorded against the contract being served, " + contractVersion);
        }
        AppRow before = require(appId);
        requireNotRetired(before);
        Instant now = clock.instant();
        String trimmedNote = note == null || note.isBlank() ? null : requireText(note, "note", 1000);
        unitOfWork.executeWithoutResult(status -> {
            if (!store.recordConformance(
                    appId, expectedVersion, result.name(), reportedContractVersion, trimmedNote, now)) {
                throw stale(appId, expectedVersion);
            }
            record(
                    "storefront_app.conformance_recorded",
                    appId,
                    actor,
                    reason,
                    now,
                    fields("conformanceStatus", before.conformanceStatus()),
                    fields("conformanceStatus", result.name(), "contractVersion", reportedContractVersion));
        });
        return view(require(appId));
    }

    // ----------------------------------------------------------------- helpers

    private AppRow require(UUID appId) {
        return store.findApp(appId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such storefront app"));
    }

    private static void requireNotRetired(AppRow app) {
        if (app.status() == StorefrontAppStatus.RETIRED) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "A retired app can no longer be changed");
        }
    }

    private ApiException stale(UUID appId, long expected) {
        Optional<AppRow> current = store.findApp(appId);
        return current.map(row -> ApiException.staleVersion(expected, row.version()))
                .orElseGet(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such storefront app"));
    }

    /** The app as the registry shows it, with a stale PASSED turned into EXPIRED at read time. */
    StorefrontAppView view(AppRow row) {
        return new StorefrontAppView(
                row.id(),
                row.name(),
                row.vendor(),
                row.clientType(),
                row.firstParty(),
                row.originAllowlist(),
                row.secretReference() != null,
                row.secretRotatedAt(),
                row.status(),
                conformance(row),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    StorefrontAppConformance conformance(AppRow row) {
        return conformanceOf(row, contractVersion);
    }

    /**
     * What an app's recorded result is worth against the contract being served. A PASSED result
     * recorded against another major version has expired; one recorded against this one has not.
     */
    public static StorefrontAppConformance conformanceOf(AppRow row, String currentContract) {
        ConformanceStatus recorded = ConformanceStatus.valueOf(row.conformanceStatus());
        ConformanceStatus effective =
                recorded == ConformanceStatus.PASSED && !currentContract.equals(row.conformanceContractVersion())
                        ? ConformanceStatus.EXPIRED
                        : recorded;
        return new StorefrontAppConformance(
                effective, row.conformanceContractVersion(), row.conformanceRecordedAt(), row.conformanceNote());
    }

    /**
     * What the audit fact records of an app: every field that says what the app is, and the
     * secret never — {@code secretRotatedAt} is the only trace, and {@link ChangeDocuments}
     * redacts its value on the strength of the word in its name.
     */
    private static Map<String, Object> snapshot(AppRow row) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", row.name());
        fields.put("vendor", row.vendor());
        fields.put("clientType", row.clientType().name());
        fields.put("firstParty", row.firstParty());
        fields.put("origins", String.join(",", row.originAllowlist()));
        fields.put("status", row.status().name());
        return fields;
    }

    private void record(
            String actionCode,
            UUID appId,
            ActorRef actor,
            String reason,
            Instant now,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(AuditFact.of(actionCode, AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.platform())
                .target("StorefrontApp", appId)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(appId.toString())
                .occurredAt(now)
                .build());
    }

    /** One field as the mutable, null-tolerant map a change document is built from. */
    private static Map<String, Object> fields(String key, @Nullable Object value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(key, value);
        return fields;
    }

    private static Map<String, Object> fields(
            String firstKey, @Nullable Object firstValue, String secondKey, @Nullable Object secondValue) {
        Map<String, Object> fields = fields(firstKey, firstValue);
        fields.put(secondKey, secondValue);
        return fields;
    }

    private static List<String> normaliseOrigins(@Nullable List<String> origins) {
        if (origins == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String origin : origins) {
            try {
                unique.add(AppOrigin.parseAllowlistEntry(origin));
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
            }
        }
        if (unique.size() > MAX_ORIGINS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An app may list at most " + MAX_ORIGINS + " origins");
        }
        return new ArrayList<>(unique);
    }

    private static String requireText(@Nullable String value, String field, int max) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > max) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The " + field + " must be between 1 and " + max + " characters");
        }
        return text;
    }

    private static String mintSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
