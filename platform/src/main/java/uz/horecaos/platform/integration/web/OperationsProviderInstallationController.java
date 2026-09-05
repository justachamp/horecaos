package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.integration.api.provider.ConnectFieldCatalog;
import uz.horecaos.platform.integration.provider.ProviderCapabilityReconciliationService;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Provider installations and bindings, on the surface their screen actually lives on.
 *
 * <p>The owner decided integrations are tenant self-service (ADR 0065) and the operations
 * app's Settings section was built against that decision. The endpoints behind it, however,
 * shipped in wave 25 under {@code /api/v1/control-plane/tenants/{tenantId}/integrations} — a
 * path named for the platform's admin console, never revisited once the screen moved to
 * operations in wave 26. This class re-publishes the same nine operations under
 * {@code /api/v1/operations/tenants/{tenantId}/integrations}, the
 * {@link uz.horecaos.platform.configuration.OpenApiSurface#OPERATIONS} prefix the operations
 * Angular app is a first-party consumer of, so {@code make openapi-baseline}'s
 * {@code operations} group finally describes the endpoints its own app depends on.
 *
 * <p><strong>Why this is a delegate rather than a move.</strong>
 * {@code OpenApiContractTests#everyPublishedPathBelongsToExactlyOneSurfaceGroup} and its
 * sibling compatibility check forbid dropping a published path — the original
 * control-plane-prefixed path is kept exactly as it was (nothing calls it today; the
 * control-plane app's own provider reads go through the unrelated, cross-tenant
 * {@code PlatformIntegrationAdminController}), because a currently-unused published path is
 * still a published path. Rather than duplicating the tenant-scoped, secret-adjacent logic —
 * installation creation, binding activation, and both secret-rotation flows — into a second
 * copy that could silently drift from the original, every method here forwards, unmodified,
 * to the one class that owns it. There is exactly one implementation of
 * "how a provider secret rotates" in this codebase; this controller only changes which URL
 * reaches it.
 *
 * <p>Capabilities are unchanged: {@link Capability#INTEGRATION_INSTALLATION_MANAGE} and
 * {@link Capability#INTEGRATION_BINDING_ACTIVATE}, already held by the {@code TENANT_OWNER}
 * and {@code TENANT_ADMIN} bundles (ADR 0025's closed input: "tenant owner and admin manage
 * integrations") — so this move needed no new capability and no bundle change, unlike the
 * referral-capability gap {@code PlatformRoleTests} caught in an earlier wave.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/integrations")
@Tag(name = "Provider integrations", description = "POS, payment, delivery, and notification accounts")
public class OperationsProviderInstallationController {

    private final ProviderInstallationController delegate;

    public OperationsProviderInstallationController(ProviderInstallationController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/connect-fields")
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(
            summary = "Per-adapter connect field declarations (ADR 0065)",
            description = "Identical to the control-plane-prefixed path's own operation; see "
                    + "ProviderInstallationController.connectFields for the full description. Published "
                    + "here too so the operations app's generated client covers what it actually calls.")
    List<ConnectFieldCatalog.ProviderConnectDeclaration> connectFields(@PathVariable UUID tenantId) {
        return delegate.connectFields(tenantId);
    }

    @GetMapping
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(summary = "List installations, without credentials")
    Page<ProviderInstallationController.InstallationView> list(@PathVariable UUID tenantId) {
        return delegate.list(tenantId);
    }

    @PostMapping
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Install a provider",
            description = "The environment is chosen from an approved catalogue; a tenant never "
                    + "supplies a URL, which closes the request-forgery path at the model.")
    ResponseEntity<Map<String, Object>> install(
            @PathVariable UUID tenantId, @Valid @RequestBody ProviderInstallationController.InstallRequest request) {
        return delegate.install(tenantId, request);
    }

    @PostMapping("/{installationId}/bindings")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Bind an installation to a brand or location",
            description = "Created suspended. Activation is separate, so a binding cannot go live "
                    + "before someone has confirmed it points at the intended restaurant.")
    ResponseEntity<Map<String, Object>> bind(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @Valid @RequestBody ProviderInstallationController.BindRequest request) {
        return delegate.bind(tenantId, installationId, request);
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
        return delegate.reconcileCapabilities(tenantId, installationId);
    }

    @PostMapping("/{installationId}/secret-rotations")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Point an installation's secret reference at a rotated credential",
            description = "ADR 0028: the database stores a reference, never a value, so this only "
                    + "ever changes which reference is on file. Verified before it is written: the new "
                    + "reference must resolve through the ADR 0028 secret manager, and (Telegram bot "
                    + "installations only, today's one caller) the resolved token must pass a live getMe. "
                    + "Either failure changes nothing.")
    ResponseEntity<ProviderInstallationController.RotateSecretResponse> rotateSecret(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @Valid @RequestBody ProviderInstallationController.RotateSecretRequest request) {
        return delegate.rotateSecret(tenantId, installationId, request);
    }

    @PostMapping("/{installationId}/secret-rotations/value")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Rotate an installation's credential through the write-only door",
            description = "ADR 0065: accepts the new VALUE directly, verifies it (Telegram bot "
                    + "installations only) before it is ever written anywhere, then writes it through "
                    + "the door under a freshly-minted reference and swaps the installation onto it.")
    ResponseEntity<ProviderInstallationController.RotateSecretResponse> rotateSecretByValue(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @Valid @RequestBody ProviderInstallationController.RotateSecretValueRequest request) {
        return delegate.rotateSecretByValue(tenantId, installationId, request);
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
            @Valid @RequestBody ProviderInstallationController.ReasonRequest request) {
        return delegate.activateBinding(tenantId, installationId, bindingId, request);
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
            @Valid @RequestBody ProviderInstallationController.ReasonRequest request) {
        return delegate.suspendBinding(tenantId, installationId, bindingId, request);
    }
}
