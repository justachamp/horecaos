package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup.InstallationSnapshot;
import uz.horecaos.platform.integration.provider.ProviderCapabilityReconciliationService;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramCallResult;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Provider installations and bindings (ADR 0026).
 *
 * <p>Secret values are write-only and never returned. A response carries the
 * reference, so an operator can see that a credential is configured and rotated
 * without ever being able to read it back.
 *
 * <p>A binding is created suspended. Activation is a separate call that a
 * connection check must precede, because binding a POS to the wrong restaurant
 * is only discovered when an order exports to the wrong kitchen.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/integrations")
@Tag(name = "Provider integrations", description = "POS, payment, delivery, and notification accounts")
public class ProviderInstallationController {

    /** The one provider type wave 13's rotate-secret verification speaks. */
    private static final String TELEGRAM_BOT_API = "TELEGRAM_BOT_API";

    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final java.time.Clock clock;
    private final ProviderCapabilityReconciliationService reconciliation;
    private final ProviderInstallationLookup installations;
    private final SecretResolver secrets;
    private final TelegramBotApiClient telegramBotApi;

    public ProviderInstallationController(
            JdbcClient jdbc,
            AuditRecorder audit,
            CurrentActor currentActor,
            java.time.Clock clock,
            ProviderCapabilityReconciliationService reconciliation,
            ProviderInstallationLookup installations,
            SecretResolver secrets,
            TelegramBotApiClient telegramBotApi) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
        this.reconciliation = reconciliation;
        this.installations = installations;
        this.secrets = secrets;
        this.telegramBotApi = telegramBotApi;
    }

    @GetMapping
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(summary = "List installations, without credentials")
    Page<InstallationView> list(@PathVariable UUID tenantId) {
        return Page.last(jdbc.sql("""
                SELECT i.id, i.provider_category, i.provider_type, i.environment_code,
                       i.display_name, i.status, i.secret_reference, i.last_connection_status,
                       i.adapter_version
                  FROM integration.installations i
                 WHERE i.tenant_id = :tenantId
                 ORDER BY i.created_at DESC
                """)
                .param("tenantId", tenantId)
                .query((rs, n) -> new InstallationView(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider_category"),
                        rs.getString("provider_type"),
                        rs.getString("environment_code"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("secret_reference"),
                        rs.getString("last_connection_status"),
                        rs.getString("adapter_version")))
                .list());
    }

    @PostMapping
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Install a provider",
            description = "The environment is chosen from an approved catalogue; a tenant never "
                    + "supplies a URL, which closes the request-forgery path at the model.")
    ResponseEntity<Map<String, Object>> install(
            @PathVariable UUID tenantId, @Valid @RequestBody InstallRequest request) {

        boolean environmentExists = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM integration.provider_environments
                                WHERE code = :code AND provider_category = :category)
                """)
                .param("code", request.environmentCode())
                .param("category", request.category().name())
                .query(Boolean.class)
                .single();

        if (!environmentExists) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Unknown provider environment for this category: " + request.environmentCode());
        }

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference, external_account_reference)
                VALUES (:id, :tenantId, :category, :type, :environment,
                        :name, 'DRAFT', :secret, :account)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("category", request.category().name())
                .param("type", request.providerType())
                .param("environment", request.environmentCode())
                .param("name", request.displayName())
                .param("secret", request.secretReference())
                .param("account", request.externalAccountReference())
                .update();

        record(
                tenantId,
                "integration.installation_created",
                id,
                "Provider installed",
                Map.of(
                        "category", request.category().name(),
                        "providerType", request.providerType(),
                        "environment", request.environmentCode()),
                Capability.INTEGRATION_INSTALLATION_MANAGE);

        return ResponseEntity.ok(Map.of("installationId", id, "status", "DRAFT"));
    }

    @PostMapping("/{installationId}/bindings")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Bind an installation to a brand or location",
            description = "Created suspended. Activation is separate, so a binding cannot go live "
                    + "before someone has confirmed it points at the intended restaurant.")
    ResponseEntity<Map<String, Object>> bind(
            @PathVariable UUID tenantId, @PathVariable UUID installationId, @Valid @RequestBody BindRequest request) {

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, location_id, status, priority)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'SUSPENDED', :priority)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", request.brandId())
                .param("locationId", request.locationId())
                .param("priority", request.priority() == 0 ? 100 : request.priority())
                .update();

        for (String capability : request.capabilities()) {
            jdbc.sql("""
                    INSERT INTO integration.binding_capabilities
                        (binding_id, tenant_id, capability_code, enabled, is_primary)
                    VALUES (:bindingId, :tenantId, :capability, true, :primary)
                    """)
                    .param("bindingId", id)
                    .param("tenantId", tenantId)
                    .param("capability", capability)
                    .param("primary", request.primaryCapabilities().contains(capability))
                    .update();
        }

        record(
                tenantId,
                "integration.binding_created",
                id,
                "Provider bound",
                Map.of(
                        "installationId", installationId.toString(),
                        "capabilities", request.capabilities()),
                Capability.INTEGRATION_INSTALLATION_MANAGE);

        return ResponseEntity.ok(Map.of("bindingId", id, "status", "SUSPENDED"));
    }

    @PostMapping("/{installationId}/capability-reconciliation")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Reconcile an installation's declared capabilities",
            description = "Records append-only preflight evidence: the secret reference must resolve "
                    + "and the wired adapter must declare each capability. POS uses its specialised "
                    + "live discovery endpoint instead.")
    ResponseEntity<ProviderCapabilityReconciliationService.Reconciliation> reconcileCapabilities(
            @PathVariable UUID tenantId, @PathVariable UUID installationId) {
        ProviderCapabilityReconciliationService.Reconciliation result =
                reconciliation.reconcile(tenantId, installationId);
        record(
                tenantId,
                "integration.capabilities_reconciled",
                installationId,
                "Provider capability preflight completed",
                Map.of(
                        "connectionStatus", result.connectionStatus(),
                        "adapterVersion", result.adapterVersion(),
                        "capabilities", result.capabilities()),
                Capability.INTEGRATION_INSTALLATION_MANAGE);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{installationId}/secret-rotations")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Point an installation's secret reference at a rotated credential",
            description = "ADR 0028: the database stores a reference, never a value, so this only "
                    + "ever changes which reference is on file — the docs/runbooks/sendpulse-cutover.md "
                    + "step 9 gap. Verified before it is written: the new reference must resolve "
                    + "through the ADR 0028 secret manager, and (Telegram bot installations only, "
                    + "today's one caller) the resolved token must pass a live getMe. Either failure "
                    + "changes nothing.")
    public ResponseEntity<RotateSecretResponse> rotateSecret(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @Valid @RequestBody RotateSecretRequest request) {

        InstallationSnapshot installation = installations
                .installation(tenantId, installationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Installation is not available"));

        if (!TELEGRAM_BOT_API.equals(installation.providerType())) {
            // Every other provider type is a real gap, named rather than
            // guessed at: nothing today gives this endpoint a harmless
            // authenticated call for SMS or payment providers, the same
            // absence ProviderCapabilityReconciliationService's own doc
            // comment records for its non-POS preflight.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Secret rotation verification is wired for TELEGRAM_BOT_API installations only, not "
                            + installation.providerType());
        }

        SecretReference reference;
        try {
            reference = SecretReference.parse(request.newSecretReference());
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Malformed secret reference: " + malformed.getMessage());
        }

        SecretValue credential;
        try {
            // Fresh, not cached: this reference was likely never resolved
            // before (the whole reason a rotation is in flight), and even
            // when it was, only a fresh read proves the manager holds the
            // rotated value right now.
            credential = secrets.resolveFresh(reference);
        } catch (RuntimeException unresolved) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The new secret reference does not resolve: " + unresolved.getMessage());
        }

        TelegramCallResult result = telegramBotApi.getMe(
                new ProviderCall(installation.baseUrl(), credential.reveal(), null, Duration.ofSeconds(15)));

        if (!(result instanceof TelegramCallResult.Success success)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "Telegram rejected the new token: " + describe(result));
        }

        String oldReference = installation.secretReference();
        int changed = jdbc.sql("""
                UPDATE integration.installations
                   SET secret_reference = :newReference, version = version + 1, updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId AND secret_reference = :oldReference
                """)
                .param("newReference", reference.toString())
                .param("id", installationId)
                .param("tenantId", tenantId)
                .param("oldReference", oldReference)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();

        if (changed == 0) {
            // Another caller rotated (or otherwise touched) this installation's
            // reference between the read above and this write — the getMe
            // verification is now stale evidence for a row that has moved on.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This installation's secret reference changed while the new one was being verified");
        }

        Object usernameValue = success.result().get("username");
        String botUsername = usernameValue == null ? null : String.valueOf(usernameValue);

        record(
                tenantId,
                "integration.installation_secret_rotated",
                installationId,
                request.reason(),
                // Reference NAMES only, per ADR 0028 discipline — never the
                // token they point at, which never reaches this class at all
                // beyond the one getMe call above. Named "reference", not
                // "secretReference": ChangeDocuments#isProtected redacts any
                // changed()-map key containing "secret" by name regardless of
                // what the value actually is, and the whole point here is that
                // an ADR 0028 reference is exactly the kind of value that is
                // safe to keep visible in the audit trail.
                Map.of(
                        "oldReference",
                        oldReference,
                        "newReference",
                        reference.toString(),
                        "botUsername",
                        botUsername == null ? "" : botUsername),
                Capability.INTEGRATION_INSTALLATION_MANAGE);

        return ResponseEntity.ok(
                new RotateSecretResponse(installationId, oldReference, reference.toString(), botUsername));
    }

    private static String describe(TelegramCallResult result) {
        return switch (result) {
            case TelegramCallResult.Success ignored -> "unreachable";
            case TelegramCallResult.Retryable retryable -> retryable.errorCode() + ": " + retryable.detail();
            case TelegramCallResult.Uncertain uncertain -> uncertain.errorCode() + ": " + uncertain.detail();
            case TelegramCallResult.BusinessRejected rejected -> rejected.errorCode() + ": " + rejected.detail();
            case TelegramCallResult.BindingRetirement retirement -> retirement.reason() + ": " + retirement.detail();
            case TelegramCallResult.ChatMigrated ignored -> "unexpected chat migration answer from getMe";
        };
    }

    @PostMapping("/{installationId}/bindings/{bindingId}/activate")
    @RequiresCapability(value = Capability.INTEGRATION_BINDING_ACTIVATE, mutating = true)
    @Operation(
            summary = "Activate a binding",
            description = "Refused until the installation has passed a connection check.")
    ResponseEntity<Map<String, Object>> activateBinding(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @PathVariable UUID bindingId,
            @Valid @RequestBody ReasonRequest request) {

        InstallationActivationGate gate = jdbc.sql("""
                SELECT i.status, i.last_connection_status,
                       EXISTS (
                           SELECT 1
                             FROM integration.binding_capabilities bc
                            WHERE bc.binding_id = :bindingId
                              AND bc.tenant_id = i.tenant_id
                              AND bc.enabled
                              AND coalesce(i.capability_snapshot -> bc.capability_code ->> 'support',
                                           'UNSUPPORTED') <> 'SUPPORTED'
                       ) AS has_unverified_capability
                  FROM integration.installations i
                 WHERE i.id = :id AND i.tenant_id = :tenantId
                   AND EXISTS (
                       SELECT 1 FROM integration.bindings b
                        WHERE b.id = :bindingId AND b.tenant_id = i.tenant_id
                          AND b.installation_id = i.id
                   )
                """)
                .param("id", installationId)
                .param("bindingId", bindingId)
                .param("tenantId", tenantId)
                .query((row, number) -> new InstallationActivationGate(
                        row.getString("status"),
                        row.getString("last_connection_status"),
                        row.getBoolean("has_unverified_capability")))
                .optional()
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Installation or binding is not available"));

        if (!"SUCCEEDED".equals(gate.connectionStatus())) {
            // ADR 0026: a capability the provider has not demonstrated must not
            // become the sole business path for a live restaurant.
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "This installation has no successful connection check, so its binding cannot activate");
        }
        if (!"DRAFT".equals(gate.status()) && !"ACTIVE".equals(gate.status())) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST, "A suspended or retired installation cannot activate a binding");
        }
        if (gate.hasUnverifiedCapability()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST, "Every enabled binding capability must be verified before activation");
        }

        int activated = jdbc.sql("""
                UPDATE integration.bindings
                   SET status = 'ACTIVE', version = version + 1, updated_at = :now
                 WHERE id = :id AND installation_id = :installationId
                   AND tenant_id = :tenantId AND status = 'SUSPENDED'
                """)
                .param("id", bindingId)
                .param("installationId", installationId)
                .param("tenantId", tenantId)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();

        if (activated == 1) {
            jdbc.sql("""
                    UPDATE integration.installations
                       SET status = 'ACTIVE', version = version + 1, updated_at = :now
                     WHERE id = :installationId AND tenant_id = :tenantId AND status = 'DRAFT'
                    """)
                    .param("installationId", installationId)
                    .param("tenantId", tenantId)
                    .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
            record(
                    tenantId,
                    "integration.binding_activated",
                    bindingId,
                    request.reason(),
                    Map.of("installationId", installationId.toString()),
                    Capability.INTEGRATION_BINDING_ACTIVATE);
        }
        return ResponseEntity.ok(
                Map.of("changed", activated == 1, "outcome", activated == 1 ? "activated" : "no_change"));
    }

    @PostMapping("/{installationId}/bindings/{bindingId}/suspend")
    @RequiresCapability(value = Capability.INTEGRATION_BINDING_ACTIVATE, mutating = true)
    @Operation(
            summary = "Suspend a binding",
            description = "The rollback path: operations return to a manual process while mappings "
                    + "and evidence are retained for reconciliation.")
    ResponseEntity<Map<String, Object>> suspendBinding(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @PathVariable UUID bindingId,
            @Valid @RequestBody ReasonRequest request) {

        int suspended = jdbc.sql("""
                UPDATE integration.bindings
                   SET status = 'SUSPENDED', version = version + 1, updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId AND status = 'ACTIVE'
                """)
                .param("id", bindingId)
                .param("tenantId", tenantId)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();

        if (suspended == 1) {
            record(
                    tenantId,
                    "integration.binding_suspended",
                    bindingId,
                    request.reason(),
                    Map.of(),
                    Capability.INTEGRATION_BINDING_ACTIVATE);
        }
        return ResponseEntity.ok(
                Map.of("changed", suspended == 1, "outcome", suspended == 1 ? "suspended" : "no_change"));
    }

    private void record(
            UUID tenantId,
            String actionCode,
            UUID targetId,
            String reason,
            Map<String, Object> changes,
            Capability capability) {
        audit.record(AuditFact.of(actionCode, AuditClass.SECURITY)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("Integration", targetId)
                .because(reason)
                .changed(changes)
                .usingCapability(capability.code())
                .correlatedBy(targetId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * A request to register a new ADR 0026 provider installation.
     *
     * @param secretReference an ADR 0028 reference. The value itself is written
     *                        straight to the secrets manager and never passes
     *                        through this API.
     */
    public record InstallRequest(
            @NotNull ProviderCategory category,
            @NotBlank @Size(max = 64) String providerType,
            @NotBlank @Size(max = 64) String environmentCode,
            @NotBlank @Size(max = 255) String displayName,
            @Size(max = 512) String secretReference,
            @Size(max = 255) String externalAccountReference) {}

    public record BindRequest(
            UUID brandId,
            UUID locationId,
            int priority,
            @NotNull List<String> capabilities,
            @NotNull List<String> primaryCapabilities) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * @param newSecretReference an ADR 0028 reference — never a value. Usually
     *                           the installation's existing reference string
     *                           unchanged (only the value behind it rotated,
     *                           out of band, in the secrets manager); a
     *                           different string here is the re-provisioned-bot
     *                           case the runbook's own step 9 names
     */
    public record RotateSecretRequest(
            @NotBlank @Size(max = 512) String newSecretReference,
            @NotBlank @Size(max = 1000) String reason) {}

    /** Reference strings only, per ADR 0028 — never a secret value. */
    public record RotateSecretResponse(
            UUID installationId,
            String oldSecretReference,
            String newSecretReference,
            @Nullable String botUsername) {}

    private record InstallationActivationGate(
            String status, String connectionStatus, boolean hasUnverifiedCapability) {}

    /** Never carries a secret value, only its reference. */
    public record InstallationView(
            UUID id,
            String category,
            String providerType,
            String environmentCode,
            String displayName,
            String status,
            String secretReference,
            String lastConnectionStatus,
            String adapterVersion) {}
}
