package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ConnectFieldCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup.InstallationSnapshot;
import uz.horecaos.platform.integration.provider.ProviderCapabilityReconciliationService;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramCallResult;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookRegistrationService;
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
 *
 * <p><strong>This path is kept for the published contract, not for a caller.</strong>
 * The operations app's own Integrations screen (ADR 0065) calls
 * {@link OperationsProviderInstallationController} at the operations-prefixed
 * mirror of every endpoint below; the control-plane app never called this class
 * (its own provider reads go through the unrelated, cross-tenant
 * {@code PlatformIntegrationAdminController}). {@code OpenApiContractTests}
 * forbids dropping a published path even when its only caller has moved on, so
 * this class stays exactly as it was rather than being deleted or rewritten —
 * every method here is this codebase's one implementation of installation and
 * secret-rotation logic, and {@link OperationsProviderInstallationController}
 * forwards to it unchanged.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/integrations")
@Tag(name = "Provider integrations", description = "POS, payment, delivery, and notification accounts")
public class ProviderInstallationController {

    /** The one provider type wave 13's rotate-secret verification speaks. */
    private static final String TELEGRAM_BOT_API = "TELEGRAM_BOT_API";

    /**
     * Matches {@code CloposAdapter.PROVIDER_TYPE}, duplicated rather than
     * imported: {@code pos.infrastructure.clopos} is outside this module's
     * named-interface boundary, the same reason {@link #TELEGRAM_BOT_API} is a
     * local literal above rather than a shared constant.
     */
    private static final String CLOPOS_PROVIDER_TYPE = "clopos";

    /**
     * Matches {@code CloposConfig.REQUIRE_CLERK_APPROVAL}'s key string, for the
     * same reason {@link #CLOPOS_PROVIDER_TYPE} is a literal rather than an
     * import.
     */
    private static final String CLOPOS_REQUIRE_CLERK_APPROVAL_KEY = "clopos.requireClerkApproval";

    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final java.time.Clock clock;
    private final ProviderCapabilityReconciliationService reconciliation;
    private final ProviderInstallationLookup installations;
    private final SecretResolver secrets;
    private final TelegramBotApiClient telegramBotApi;
    private final SecretIngressGateway door;
    private final TelegramWebhookRegistrationService webhookRegistration;

    public ProviderInstallationController(
            JdbcClient jdbc,
            AuditRecorder audit,
            CurrentActor currentActor,
            java.time.Clock clock,
            ProviderCapabilityReconciliationService reconciliation,
            ProviderInstallationLookup installations,
            SecretResolver secrets,
            TelegramBotApiClient telegramBotApi,
            SecretIngressGateway door,
            TelegramWebhookRegistrationService webhookRegistration) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
        this.reconciliation = reconciliation;
        this.installations = installations;
        this.secrets = secrets;
        this.telegramBotApi = telegramBotApi;
        this.door = door;
        this.webhookRegistration = webhookRegistration;
    }

    @GetMapping("/connect-fields")
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(
            summary = "Per-adapter connect field declarations (ADR 0065)",
            description = "Static, code-owned catalogue: which fields a Click, Payme, or Telegram "
                    + "connect flow needs, and which of those must travel through the write-only secret "
                    + "door rather than as plain configuration. The control-plane app renders its connect "
                    + "form from this, so a new provider adapter costs a catalogue entry, not a new screen. "
                    + "Each declaration also carries the environments a tenant may actually choose for it "
                    + "(integration.provider_environments, platform-owned): the approved-catalogue join a "
                    + "connect form needs to offer a picker instead of a free-text field an operator has no "
                    + "way to guess.")
    List<ConnectFieldDeclarationView> connectFields(@PathVariable UUID tenantId) {
        Map<String, List<ConnectFieldEnvironment>> environmentsByDeclaration = approvedEnvironmentsByDeclaration();
        return ConnectFieldCatalog.all().stream()
                .map(declaration -> new ConnectFieldDeclarationView(
                        declaration.providerType(),
                        declaration.category(),
                        declaration.fields(),
                        environmentsByDeclaration.getOrDefault(
                                declarationKey(declaration.category().name(), declaration.providerType()), List.of())))
                .toList();
    }

    /**
     * Every approved environment, grouped by (category, upper(provider_type)).
     *
     * <p>{@link ConnectFieldCatalog} is a static declaration (ADR 0065's own
     * doc comment) and stays that way — this join belongs in the web layer
     * that already owns the tenant-facing {@code install()} check below, not
     * inside the catalogue class. Grouped in Java from one query rather than
     * one query per declaration: {@link ConnectFieldCatalog#all()} is small,
     * but a per-declaration query would still be N+1 for no reason.
     *
     * <p>The provider type comparison is case-insensitive on purpose: {@code
     * integration.provider_environments} rows are seeded independently across
     * a dozen migrations (V0036 seeds Clopos's row as lowercase {@code
     * 'clopos'} while every {@link ConnectFieldCatalog} declaration is upper
     * snake case) and a byte-for-byte join would silently show an approved
     * provider as having no environments the moment a seed's casing diverged
     * from the catalogue's, which is exactly the defect this endpoint exists
     * to close for Telegram.
     */
    private Map<String, List<ConnectFieldEnvironment>> approvedEnvironmentsByDeclaration() {
        record Row(String key, ConnectFieldEnvironment environment) {}

        return jdbc
                .sql("""
                        SELECT provider_category, provider_type, code, is_production
                          FROM integration.provider_environments
                         ORDER BY is_production DESC, code
                        """)
                .query((rs, rowNumber) -> new Row(
                        declarationKey(rs.getString("provider_category"), rs.getString("provider_type")),
                        new ConnectFieldEnvironment(rs.getString("code"), rs.getBoolean("is_production"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        Row::key, LinkedHashMap::new, Collectors.mapping(Row::environment, Collectors.toList())));
    }

    /** Case-sensitive on category (a closed, code-defined enum), case-insensitive on provider type. */
    private static String declarationKey(String category, String providerType) {
        return category + "|" + providerType.toUpperCase(Locale.ROOT);
    }

    @GetMapping
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(summary = "List installations, without credentials")
    Page<InstallationView> list(@PathVariable UUID tenantId) {
        return Page.last(jdbc.sql("""
                SELECT i.id, i.provider_category, i.provider_type, i.environment_code,
                       i.display_name, i.status, i.secret_reference, i.last_connection_status,
                       i.adapter_version, i.last_secret_rotated_at, i.secret_last_used_at,
                       i.non_sensitive_config, i.webhook_secret_reference IS NOT NULL AS webhook_registered,
                       i.non_sensitive_config ->> 'webhookRegisteredAt' AS webhook_registered_at
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
                        rs.getString("adapter_version"),
                        rs.getObject("last_secret_rotated_at", OffsetDateTime.class),
                        rs.getObject("secret_last_used_at", OffsetDateTime.class),
                        rs.getString("non_sensitive_config"),
                        rs.getBoolean("webhook_registered"),
                        parseInstantText(rs.getString("webhook_registered_at"))))
                .list());
    }

    /** {@code non_sensitive_config->>'webhookRegisteredAt'} is an {@link OffsetDateTime#toString()}-shaped value, or absent. */
    private static @Nullable OffsetDateTime parseInstantText(@Nullable String value) {
        return value == null ? null : OffsetDateTime.parse(value);
    }

    @PostMapping
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Install a provider",
            description = "The environment is chosen from an approved catalogue; a tenant never "
                    + "supplies a URL, which closes the request-forgery path at the model.")
    ResponseEntity<Map<String, Object>> install(
            @PathVariable UUID tenantId, @Valid @RequestBody InstallRequest request) {

        // Category alone used to be the whole check, so a request could name any
        // environment approved for the category regardless of which provider it
        // actually belonged to — a Telegram install naming the SMS gateway's own
        // NOTIFICATION-category row would have gotten that row's base_url. The
        // provider type comparison is case-insensitive for the same reason
        // approvedEnvironmentsByDeclaration() above is: a seeded row's casing
        // (Clopos's is lowercase 'clopos') is not guaranteed to match the
        // request's exactly even when it names the same provider.
        //
        // The match yields the row's own provider_type — its canonical casing
        // — and everything below persists and looks up by that, never by
        // request.providerType() directly. ConnectFieldCatalog.forProviderType()
        // and the TELEGRAM_BOT_API/CLOPOS_PROVIDER_TYPE literals elsewhere in
        // this class compare case-sensitively, so a request that named the
        // right provider in the wrong case would otherwise install with a
        // non_sensitive_config silently dropped to "{}" and a provider_type
        // that never again matches those exact-case gates (rotateSecret,
        // rotateSecretByValue, installationOf).
        Optional<String> canonicalProviderType = jdbc.sql("""
                SELECT provider_type FROM integration.provider_environments
                 WHERE code = :code AND provider_category = :category
                   AND upper(provider_type) = upper(:type)
                 LIMIT 1
                """)
                .param("code", request.environmentCode())
                .param("category", request.category().name())
                .param("type", request.providerType())
                .query(String.class)
                .optional();

        if (canonicalProviderType.isEmpty()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Unknown provider environment for this category: " + request.environmentCode());
        }
        String providerType = canonicalProviderType.get();

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference, external_account_reference,
                     non_sensitive_config)
                VALUES (:id, :tenantId, :category, :type, :environment,
                        :name, 'DRAFT', :secret, :account, cast(:config AS jsonb))
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("category", request.category().name())
                .param("type", providerType)
                .param("environment", request.environmentCode())
                .param("name", request.displayName())
                .param("secret", request.secretReference())
                .param("account", request.externalAccountReference())
                .param("config", nonSensitiveConfigOf(providerType, request.externalAccountReference()))
                .update();

        record(
                tenantId,
                "integration.installation_created",
                id,
                "Provider installed",
                Map.of(
                        "category", request.category().name(),
                        "providerType", providerType,
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
                .param("priority", request.priority() == null || request.priority() == 0 ? 100 : request.priority())
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

    @GetMapping("/{installationId}/capability-catalogue")
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(
            summary = "The vendor ceiling this installation's own provider declares (gap-map row 10.8a)",
            description = "Never a per-credential fact -- the same declaration ceiling "
                    + "ProviderCapabilityReconciliationService#reconcile caps live evidence at, read "
                    + "here before any reconciliation has run. Backs the branch-binding dialog's own "
                    + "capability-assignment picker: a POS or DELIVERY installation returns its real "
                    + "capability codes; a category with no wired catalogue (or no adapter for this "
                    + "installation's own provider type) answers an empty list rather than a guess.")
    ResponseEntity<CapabilityCatalogueView> capabilityCatalogue(
            @PathVariable UUID tenantId, @PathVariable UUID installationId) {
        InstallationSnapshot installation = installations
                .installation(tenantId, installationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Installation is not available"));
        List<String> capabilities =
                reconciliation.declaredCapabilities(installation.category(), installation.providerType()).stream()
                        .sorted()
                        .toList();
        return ResponseEntity.ok(new CapabilityCatalogueView(
                installationId, installation.category(), installation.providerType(), capabilities));
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
                   SET secret_reference = :newReference, last_secret_rotated_at = :now,
                       version = version + 1, updated_at = :now
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

    @PostMapping("/{installationId}/secret-rotations/value")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Rotate an installation's credential through the write-only door",
            description = "ADR 0065: generalizes the endpoint above to a tenant that has no way to "
                    + "write a reference by hand. Accepts the new VALUE directly, verifies it "
                    + "(Telegram bot installations only, the same scoping rotateSecret already "
                    + "documents) before it is ever written anywhere, then writes it through the door "
                    + "under a freshly-minted reference and swaps the installation onto it. For every "
                    + "other provider type there is no harmless authenticated call to verify against, so "
                    + "the write proceeds and the installation is left last_connection_status = "
                    + "UNVERIFIED — connected, not silently claimed as confirmed, mirroring the "
                    + "Telegram-only scoping rationale rather than pretending a stronger guarantee.")
    public ResponseEntity<RotateSecretResponse> rotateSecretByValue(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @Valid @RequestBody RotateSecretValueRequest request) {

        InstallationSnapshot installation = installations
                .installation(tenantId, installationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Installation is not available"));

        SecretValue newCredential = SecretValue.of(request.value());
        String botUsername = null;
        boolean verified = false;

        if (TELEGRAM_BOT_API.equals(installation.providerType())) {
            // Verified BEFORE it ever touches the secrets manager: a rejected
            // credential must never even become a resolvable, if orphaned,
            // secret. Only a value Telegram accepts is written at all.
            TelegramCallResult result = telegramBotApi.getMe(
                    new ProviderCall(installation.baseUrl(), newCredential.reveal(), null, Duration.ofSeconds(15)));
            if (!(result instanceof TelegramCallResult.Success success)) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE, "Telegram rejected the new token: " + describe(result));
            }
            Object usernameValue = success.result().get("username");
            botUsername = usernameValue == null ? null : String.valueOf(usernameValue);
            verified = true;
        }

        SecretReference reference =
                door.write(secretCategoryFor(installation.category()), ownerScopeFor(tenantId), newCredential);

        String oldReference = installation.secretReference();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int changed = jdbc.sql("""
                UPDATE integration.installations
                   SET secret_reference = :newReference,
                       last_connection_status = :connectionStatus,
                       last_secret_rotated_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId AND secret_reference = :oldReference
                """)
                .param("newReference", reference.toString())
                .param("connectionStatus", verified ? "SUCCEEDED" : "UNVERIFIED")
                .param("id", installationId)
                .param("tenantId", tenantId)
                .param("oldReference", oldReference)
                .param("now", now)
                .update();

        if (changed == 0) {
            // A value was already written through the door above; the row
            // simply never gets pointed at it. It stays orphaned in the
            // secrets manager, unreachable by any reference in a database
            // row -- ADR 0028's accepted posture (rollback "never returns
            // secret values to the database") rather than a leak.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This installation's secret reference changed while the new value was being verified");
        }

        record(
                tenantId,
                "integration.installation_secret_rotated",
                installationId,
                request.reason(),
                // Reference names and the verification outcome only -- never
                // the value, which never reaches this method beyond the one
                // door.write() and (Telegram only) getMe() calls above.
                Map.of(
                        "oldReference",
                        oldReference,
                        "newReference",
                        reference.toString(),
                        "verified",
                        verified,
                        "botUsername",
                        botUsername == null ? "" : botUsername),
                Capability.INTEGRATION_INSTALLATION_MANAGE);

        return ResponseEntity.ok(
                new RotateSecretResponse(installationId, oldReference, reference.toString(), botUsername));
    }

    @PostMapping("/{installationId}/webhook-registration")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Register (or re-register) this installation's Telegram webhook",
            description = "ADR 0058: mints a fresh webhook secret token, writes it through the ADR 0065 "
                    + "door, calls Telegram's setWebhook, and only on that success points the installation "
                    + "at the new reference — nothing in the database changes on a Telegram refusal. "
                    + "TELEGRAM_BOT_API installations only, ACTIVE only. Re-running rotates the webhook "
                    + "secret: the previous token stops working the instant Telegram accepts the new one, "
                    + "which is the supported recovery from a mismatched or leaked secret.")
    public ResponseEntity<WebhookRegistrationResponse> registerWebhook(
            @PathVariable UUID tenantId, @PathVariable UUID installationId) {

        TelegramWebhookRegistrationService.Registration registration = webhookRegistration.register(
                tenantId, installationId, ActorRef.user(currentActor.get().subject(), null));

        return ResponseEntity.ok(new WebhookRegistrationResponse(
                registration.installationId(),
                registration.webhookUrl(),
                registration.registeredAt().atOffset(ZoneOffset.UTC),
                registration.botUsername()));
    }

    /** ownerScope is platform-derived, never a caller-supplied string. */
    private static String ownerScopeFor(UUID tenantId) {
        return "tenant-" + tenantId;
    }

    /**
     * Turns the connect form's single positionally-joined {@code
     * externalAccountReference} string back into a {@code non_sensitive_config}
     * jsonb document keyed by {@link ConnectFieldCatalog}'s own declared
     * non-secret field names, in the order it declares them — the same order
     * {@code connect-provider-panel.ts}'s {@code submit()} joins them in.
     *
     * <p>ADR 0106: a blank field keeps its position (an empty string in the
     * split, silently dropped from the emitted document) rather than shifting
     * every later field left. That correction matters starting with this
     * wave: {@code CLICK} normally has both of its two non-secret fields
     * filled, so the bug was latent there, but an analytics installation
     * commonly has exactly one of its declared fields set, and the old
     * behaviour would have written it under the wrong key.
     */
    private static String nonSensitiveConfigOf(String providerType, @Nullable String joinedReference) {
        List<ConnectFieldCatalog.ConnectField> nonSecretFields = ConnectFieldCatalog.forProviderType(providerType)
                .map(declaration -> declaration.fields().stream()
                        .filter(field -> !field.secret())
                        .toList())
                .orElse(List.of());
        if (nonSecretFields.isEmpty()) {
            return "{}";
        }
        String[] parts =
                joinedReference == null || joinedReference.isEmpty() ? new String[0] : joinedReference.split("/", -1);

        StringBuilder json = new StringBuilder("{");
        boolean wroteOne = false;
        for (int i = 0; i < nonSecretFields.size(); i++) {
            String value = i < parts.length ? parts[i] : "";
            if (value.isBlank()) {
                continue;
            }
            if (wroteOne) {
                json.append(',');
            }
            json.append('"')
                    .append(escapeJsonString(nonSecretFields.get(i).key()))
                    .append("\":\"")
                    .append(escapeJsonString(value))
                    .append('"');
            wroteOne = true;
        }
        return json.append('}').toString();
    }

    private static String escapeJsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static SecretCategory secretCategoryFor(ProviderCategory category) {
        return switch (category) {
            case POS -> SecretCategory.PROVIDER_POS;
            case PAYMENT -> SecretCategory.PROVIDER_PAYMENT;
            case DELIVERY -> SecretCategory.PROVIDER_DELIVERY;
            case NOTIFICATION -> SecretCategory.PROVIDER_NOTIFICATION;
            case VOICE -> SecretCategory.PROVIDER_VOICE;
            case MARKETPLACE, GEOCODING, OTHER, ANALYTICS ->
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "The secret door has no category for " + category + " installations yet");
        };
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

    /**
     * An installation's bindings, newest first, every status.
     *
     * <p>Activation and suspension take a binding's id, and nothing returned
     * one except the call that created it, so a binding made yesterday could
     * not be found to be suspended today. Scoped by tenant and installation
     * both: a binding id from another tenant is simply absent.
     */
    @GetMapping("/{installationId}/bindings")
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(summary = "List an installation's bindings")
    List<BindingView> bindings(@PathVariable UUID tenantId, @PathVariable UUID installationId) {
        return jdbc.sql("""
                SELECT b.id, b.brand_id, b.location_id, b.status, b.priority, b.effective_from, b.effective_until
                  FROM integration.bindings b
                 WHERE b.tenant_id = :tenantId AND b.installation_id = :installationId
                 ORDER BY b.created_at DESC
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query((rs, n) -> new BindingView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("brand_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("priority"),
                        rs.getObject("effective_from", OffsetDateTime.class),
                        rs.getObject("effective_until", OffsetDateTime.class)))
                .list();
    }

    /**
     * The per-branch install model row {@code 10.8a} asks for, surfaced: for
     * every branch of this brand, and every capability bound there or
     * inherited from the brand, which installation actually handles it.
     *
     * <p>The IA (10.8, 3.3) and ADR 0030 both frame this as tenant-default
     * overridden per branch; {@code ProviderInstallationLookup.primaryBinding}
     * already resolves exactly that precedence, one scope and capability at a
     * time (proven by {@code JdbcProviderInstallationLookupTests.
     * aLocationBindingWinsOverItsBrand}). This is the same specificity rule —
     * a location binding outranks its brand's, narrower priority breaks a tie
     * — run once across every branch and capability at once, because the hub
     * has to show a whole brand's worth of branches in one screen rather than
     * ask once per branch per capability.
     *
     * <p>{@code DISTINCT ON} plus the {@code ORDER BY} is the whole
     * precedence rule, the same shape {@link #bindings} and {@code
     * JdbcProviderInstallationLookup}'s own {@code candidates()} query take:
     * one statement decides the winner rather than a read followed by
     * in-memory comparison.
     */
    @GetMapping("/branches/effective-bindings")
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(
            summary = "The effective installation per branch, per capability (gap-map row 10.8a)",
            description = "For every location of the given brand: the installation that would "
                    + "actually handle each capability bound there, whether bound directly to the "
                    + "location or inherited from the brand's own default. A branch with no row for "
                    + "a capability has nothing bound at either scope.")
    List<EffectiveBindingView> effectiveBindings(@PathVariable UUID tenantId, @RequestParam UUID brandId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (loc.id, bc.capability_code)
                       loc.id AS location_id, bc.capability_code,
                       i.provider_category, i.provider_type,
                       i.id AS installation_id, i.display_name AS installation_display_name,
                       b.id AS binding_id, (b.location_id IS NOT NULL) AS location_scoped
                  FROM tenant.locations loc
                  JOIN integration.bindings b
                    ON b.tenant_id = loc.tenant_id
                   AND (b.location_id = loc.id OR (b.location_id IS NULL AND b.brand_id = loc.brand_id))
                  JOIN integration.installations i
                    ON i.id = b.installation_id AND i.tenant_id = b.tenant_id
                  JOIN integration.binding_capabilities bc
                    ON bc.binding_id = b.id AND bc.tenant_id = b.tenant_id
                 WHERE loc.tenant_id = :tenantId AND loc.brand_id = :brandId
                   AND b.status = 'ACTIVE' AND i.status = 'ACTIVE'
                   AND bc.enabled AND bc.is_primary
                   AND b.effective_from <= :now AND (b.effective_until IS NULL OR b.effective_until > :now)
                 ORDER BY loc.id, bc.capability_code, (b.location_id IS NOT NULL) DESC, b.priority ASC
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .query((rs, n) -> new EffectiveBindingView(
                        rs.getObject("location_id", UUID.class),
                        rs.getString("capability_code"),
                        rs.getString("provider_category"),
                        rs.getString("provider_type"),
                        rs.getObject("installation_id", UUID.class),
                        rs.getString("installation_display_name"),
                        rs.getObject("binding_id", UUID.class),
                        rs.getBoolean("location_scoped")))
                .list();
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

    @GetMapping("/{installationId}/settings")
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(
            summary = "Provider-specific settings for one installation",
            description = "Today, Clopos's order-acceptance mode only: whether an exported order still "
                    + "needs a clerk to accept it at the till (docs/providers/clopos-api.md Q7). Refused for "
                    + "any other provider type, because the setting has no meaning there.")
    ResponseEntity<CloposSettingsView> settings(@PathVariable UUID tenantId, @PathVariable UUID installationId) {
        return ResponseEntity.ok(
                new CloposSettingsView(installationOf(tenantId, installationId).requireClerkApproval()));
    }

    @PostMapping("/{installationId}/settings")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Set whether Clopos still needs a clerk's acceptance",
            description = "Per-tenant, on the installation rather than a per-venue binding: the owner's "
                    + "own framing of the question was per tenant, and one Clopos installation is one "
                    + "brand. Defaults to true (the clerk decides) until set — an order sitting in "
                    + "PENDING awaiting a clerk is recoverable and visible, an auto-accepted one is "
                    + "already food, and CloposAdapter#exportOrder reads exactly this key.")
    ResponseEntity<CloposSettingsView> updateSettings(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @Valid @RequestBody UpdateCloposSettingsRequest request) {

        InstallationConfigRow row = installationOf(tenantId, installationId);

        int updated = jdbc.sql("""
                UPDATE integration.installations
                   SET non_sensitive_config = jsonb_set(
                           coalesce(non_sensitive_config, '{}'::jsonb),
                           ARRAY[:key]::text[], to_jsonb(:value), true),
                       version = version + 1, updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId
                """)
                .param("key", CLOPOS_REQUIRE_CLERK_APPROVAL_KEY)
                .param("value", request.requireClerkApproval())
                .param("id", installationId)
                .param("tenantId", tenantId)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();

        if (updated == 0) {
            // The row this method's own read above just found is gone by the time
            // of the write — a retirement racing this call. Reported rather than
            // silently treated as success, the same posture rotateSecret takes on
            // its own conflicting-write race.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Installation is not available");
        }

        record(
                tenantId,
                "integration.installation_settings_updated",
                installationId,
                "Clopos order-acceptance setting changed",
                Map.of(
                        "requireClerkApproval",
                        request.requireClerkApproval(),
                        "previousValue",
                        row.requireClerkApproval()),
                Capability.INTEGRATION_INSTALLATION_MANAGE);

        return ResponseEntity.ok(new CloposSettingsView(request.requireClerkApproval()));
    }

    /**
     * Reads this installation's provider type and its current
     * {@code requireClerkApproval} setting together, and refuses anything that
     * is not a Clopos installation — the settings surface only has one field
     * today and it belongs to no other provider.
     */
    private InstallationConfigRow installationOf(UUID tenantId, UUID installationId) {
        InstallationConfigRow row = jdbc.sql("""
                SELECT provider_type,
                       non_sensitive_config ->> :key AS require_clerk_approval
                  FROM integration.installations
                 WHERE id = :id AND tenant_id = :tenantId
                """)
                .param("key", CLOPOS_REQUIRE_CLERK_APPROVAL_KEY)
                .param("id", installationId)
                .param("tenantId", tenantId)
                .query((row1, number) -> new InstallationConfigRow(
                        row1.getString("provider_type"), row1.getString("require_clerk_approval")))
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Installation is not available"));

        if (!CLOPOS_PROVIDER_TYPE.equals(row.providerType())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Settings are defined for clopos installations only, not " + row.providerType());
        }
        return row;
    }

    /** @param rawRequireClerkApproval the stored text value, or null when nothing has been set yet */
    private record InstallationConfigRow(
            String providerType, @Nullable String rawRequireClerkApproval) {

        /**
         * Absent means the value {@code CloposAdapter}'s own default falls back
         * to ({@code CloposConfig.REQUIRE_CLERK_APPROVAL}'s {@code "true"}
         * default) — the safe, slower posture, never guessed as the convenient
         * one.
         */
        boolean requireClerkApproval() {
            return rawRequireClerkApproval == null || Boolean.parseBoolean(rawRequireClerkApproval);
        }
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
            // ADR 0106: ANALYTICS declares no secret field at all
            // (ConnectFieldCatalog), so this is legitimately absent for it —
            // not only a value the write-only door has not run yet for.
            @Nullable @Size(max = 512) String secretReference,
            @Nullable @Size(max = 255) String externalAccountReference) {}

    /**
     * @param priority optional; absent or {@code 0} means the column's own default
     *                 of 100. Boxed on purpose: the operations console has always
     *                 omitted it, and this JSON stack refuses a missing value for a
     *                 primitive ({@code FAIL_ON_NULL_FOR_PRIMITIVES} is on by
     *                 default in Jackson 3), so as {@code int} every console bind
     *                 answered 400 MALFORMED_BODY — pre-production, 2026-09-21
     */
    public record BindRequest(
            UUID brandId,
            UUID locationId,
            @Nullable @Min(0) Integer priority,
            @NotNull List<String> capabilities,
            @NotNull List<String> primaryCapabilities) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    /** Whether an exported Clopos order still needs a clerk's acceptance at the till (Q7). */
    public record CloposSettingsView(boolean requireClerkApproval) {}

    public record UpdateCloposSettingsRequest(@NotNull Boolean requireClerkApproval) {}

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

    /**
     * @param value the new credential, ADR 0065's door: exists only in this
     *              request body and the one write/verify call this endpoint
     *              makes with it. Never returned, logged, or placed in an
     *              error message
     */
    public record RotateSecretValueRequest(
            @NotBlank @Size(max = 4096) String value,
            @NotBlank @Size(max = 1000) String reason) {

        /**
         * Redacted on purpose (ADR 0028). A record's generated {@code toString}
         * prints every component, and Spring's message converters log the
         * deserialized body at TRACE — so the default would put a live
         * credential into a log line the moment someone turned tracing on.
         */
        @Override
        public String toString() {
            return "RotateSecretValueRequest[value=REDACTED, reason=" + reason + "]";
        }
    }

    /** Reference strings only, per ADR 0028 — never a secret value. */
    public record RotateSecretResponse(
            UUID installationId,
            String oldSecretReference,
            String newSecretReference,
            @Nullable String botUsername) {}

    private record InstallationActivationGate(
            String status, String connectionStatus, boolean hasUnverifiedCapability) {}

    /**
     * One {@code integration.provider_environments} row a tenant may actually
     * choose for a declaration's provider — never the host: choosing an
     * environment needs its name and whether it is live, not where it points
     * (the same posture {@code PlatformIntegrationAdminController.ProviderEnvironmentView}
     * already takes for the platform-scope read of the same table).
     */
    public record ConnectFieldEnvironment(String code, boolean production) {}

    /**
     * {@link ConnectFieldCatalog.ProviderConnectDeclaration} plus the
     * environments approved for it — additive over the static declaration,
     * assembled here rather than on the catalogue itself (see {@link
     * #approvedEnvironmentsByDeclaration()}). An empty {@code environments}
     * list is a real, renderable state: the platform has not approved any
     * environment for this provider yet, so a connect form must refuse to
     * submit rather than let an operator type a code that can only ever be
     * rejected.
     */
    public record ConnectFieldDeclarationView(
            String providerType,
            ProviderCategory category,
            List<ConnectFieldCatalog.ConnectField> fields,
            List<ConnectFieldEnvironment> environments) {}

    /**
     * The vendor ceiling one installation's own provider declares (gap-map
     * row 10.8a) — {@code capabilities} is empty, never null, when this build
     * has no catalogue for the category or no adapter for the provider type,
     * so the branch-binding dialog can render "nothing to assign" rather than
     * treat a missing list as a loading state that never resolves.
     */
    public record CapabilityCatalogueView(
            UUID installationId, ProviderCategory category, String providerType, List<String> capabilities) {}

    /** Where an installation applies: a brand, or one location of it. */
    public record BindingView(
            UUID id,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String status,
            int priority,
            OffsetDateTime effectiveFrom,
            @Nullable OffsetDateTime effectiveUntil) {}

    /**
     * One branch, one capability, one resolved winner (gap-map row 10.8a).
     *
     * @param locationScoped true when {@code bindingId} is bound directly to
     *                       this branch, false when it is inherited from the
     *                       brand's own default — the "tenant-default
     *                       overridden per branch" distinction the hub's own
     *                       screen has to make visible rather than leave an
     *                       operator to infer from two separate reads
     */
    public record EffectiveBindingView(
            UUID locationId,
            String capabilityCode,
            String providerCategory,
            String providerType,
            UUID installationId,
            String installationDisplayName,
            UUID bindingId,
            boolean locationScoped) {}

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
            String adapterVersion,
            @Nullable OffsetDateTime lastSecretRotatedAt,
            /**
             * ADR 0106, gap-map row X.14: when the secret reference last resolved
             * successfully during a capability-reconciliation preflight — evidence
             * the secret still resolves, not evidence of a live provider call.
             */
            @Nullable OffsetDateTime secretLastUsedAt,
            /**
             * Raw jsonb text, e.g. {@code {"gtmContainerId":"GTM-ABC1234"}} for an
             * ADR 0106 analytics installation, or Clopos's
             * {@code {"clopos.requireClerkApproval":true}}. Never a secret — every
             * field this column carries was declared {@code secret: false} in
             * {@link ConnectFieldCatalog}.
             */
            String nonSensitiveConfig,
            /**
             * {@code webhook_secret_reference IS NOT NULL} — whether {@link
             * #registerWebhook} has ever succeeded for this installation. Additive:
             * every other provider category simply reads false, since only a
             * two-directional NOTIFICATION installation ever has one.
             */
            boolean webhookRegistered,
            /** When {@link #registerWebhook} last succeeded, or null if it never has. */
            @Nullable OffsetDateTime webhookRegisteredAt) {}

    /**
     * Never a secret. {@code botUsername} is null when this installation has
     * never resolved one (see {@code TelegramBotIdentityResolver}) — a webhook
     * can register successfully before anything has ever called {@code getMe}.
     */
    public record WebhookRegistrationResponse(
            UUID installationId,
            String webhookUrl,
            OffsetDateTime registeredAt,
            @Nullable String botUsername) {}
}
