package uz.horecaos.platform.storefrontapps.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppClientType;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppRegistryService;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppRegistryService.RegisterCommand;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppRegistryService.Registered;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppDetailView;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppSummaryView;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppView;
import uz.horecaos.platform.storefrontapps.domain.ConformanceStatus;
import uz.horecaos.platform.storefrontapps.domain.StorefrontAppStatus;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The platform's registry of storefront apps (ADR 0070): the control plane's own screen over
 * who may build a storefront against the published contract.
 *
 * <p>Platform staff only ({@link Capability#STOREFRONT_APP_REGISTRY_MANAGE}). Every change
 * takes an {@code If-Match} version and carries a reason, and answers the new version in
 * {@code ETag}. The plaintext secret of a confidential client is in exactly two responses,
 * registration and rotation, and never again: {@code list} and {@code get} say only that one is
 * configured and when it last changed.
 */
@RestController
@RequestMapping("/api/v1/control-plane/storefront-apps")
@Tag(
        name = "Storefront apps",
        description = "ADR 0070: the platform's registry of storefront apps, public and confidential clients")
public class StorefrontAppRegistryController {

    private final StorefrontAppRegistryService registry;
    private final CurrentActor currentActor;

    public StorefrontAppRegistryController(StorefrontAppRegistryService registry, CurrentActor currentActor) {
        this.registry = registry;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.STOREFRONT_APP_REGISTRY_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every registered storefront app, with how many brands authorise it",
            description = "Never a secret or the reference to one — only whether a confidential client has "
                    + "one configured and when it last changed. The conformance result a row carries is "
                    + "read against the contract being served: a PASSED result recorded against another "
                    + "major version reads EXPIRED.")
    List<StorefrontAppSummaryView> list() {
        return registry.list();
    }

    @GetMapping("/{appId}")
    @RequiresCapability(value = Capability.STOREFRONT_APP_REGISTRY_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "One storefront app and every brand's standing with it",
            description = "The ETag is the version to send as If-Match on a change.")
    ResponseEntity<StorefrontAppDetailView> get(@PathVariable UUID appId) {
        StorefrontAppDetailView detail = registry.detail(appId);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(detail.app().version()))
                .body(detail);
    }

    @PostMapping
    @RequiresCapability(value = Capability.STOREFRONT_APP_REGISTRY_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Register a storefront app",
            description = "A PUBLIC client is a browser-only app: no secret, an app id, and the origin "
                    + "allowlist the platform enforces — attributable and revocable, not authenticated. A "
                    + "CONFIDENTIAL client is server-backed: the platform mints its secret, keeps only an "
                    + "ADR 0028 reference, and returns the value exactly once, in this response.")
    ResponseEntity<RegisteredAppResponse> register(@Valid @RequestBody RegisterAppRequest request) {
        Registered registered = registry.register(
                new RegisterCommand(
                        request.name(),
                        request.vendor(),
                        request.clientType(),
                        Boolean.TRUE.equals(request.firstParty()),
                        request.originAllowlist() == null ? List.of() : request.originAllowlist()),
                actor(),
                request.reason());
        return ResponseEntity.created(URI.create("/api/v1/control-plane/storefront-apps/"
                        + registered.app().id()))
                .eTag(AggregateVersion.toETag(registered.app().version()))
                .body(new RegisteredAppResponse(registered.app(), registered.secretValue()));
    }

    @PutMapping("/{appId}")
    @RequiresCapability(value = Capability.STOREFRONT_APP_REGISTRY_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Change an app's name, vendor and origin allowlist",
            description = "Needs If-Match with the version read. The client type never changes: a public "
                    + "client cannot become confidential by an edit, because that is a different "
                    + "registration with a different secret.")
    ResponseEntity<StorefrontAppView> update(
            @PathVariable UUID appId, HttpServletRequest http, @Valid @RequestBody UpdateAppRequest request) {
        StorefrontAppView updated = registry.update(
                appId,
                AggregateVersion.requireIfMatch(http),
                request.name(),
                request.vendor(),
                request.originAllowlist() == null ? List.of() : request.originAllowlist(),
                actor(),
                request.reason());
        return versioned(updated);
    }

    @PutMapping("/{appId}/status")
    @RequiresCapability(value = Capability.STOREFRONT_APP_REGISTRY_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Suspend, reinstate or retire an app",
            description = "SUSPENDED refuses the app for every tenant on its next request and can be lifted "
                    + "with ACTIVE. RETIRED is final.")
    ResponseEntity<StorefrontAppView> changeStatus(
            @PathVariable UUID appId, HttpServletRequest http, @Valid @RequestBody ChangeStatusRequest request) {
        return versioned(registry.changeStatus(
                appId, AggregateVersion.requireIfMatch(http), request.status(), actor(), request.reason()));
    }

    @PostMapping("/{appId}/secret-rotations")
    @RequiresCapability(value = Capability.STOREFRONT_APP_REGISTRY_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Rotate a confidential client's secret",
            description = "Mints a fresh secret and returns it exactly once. The previous one stops "
                    + "working with this response; a public client has none to rotate.")
    ResponseEntity<RegisteredAppResponse> rotateSecret(
            @PathVariable UUID appId, HttpServletRequest http, @Valid @RequestBody ReasonRequest request) {
        Registered rotated =
                registry.rotateSecret(appId, AggregateVersion.requireIfMatch(http), actor(), request.reason());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(rotated.app().version()))
                .body(new RegisteredAppResponse(rotated.app(), rotated.secretValue()));
    }

    @PostMapping("/{appId}/conformance-results")
    @RequiresCapability(value = Capability.STOREFRONT_APP_REGISTRY_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Record the conformance suite's result for an app",
            description = "Recorded against the contract version being served; a PASSED result expires when "
                    + "the contract's major version moves.")
    ResponseEntity<StorefrontAppView> recordConformance(
            @PathVariable UUID appId, HttpServletRequest http, @Valid @RequestBody ConformanceRequest request) {
        return versioned(registry.recordConformance(
                appId,
                AggregateVersion.requireIfMatch(http),
                request.result(),
                request.contractVersion(),
                request.note(),
                actor(),
                request.reason()));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static ResponseEntity<StorefrontAppView> versioned(StorefrontAppView view) {
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(view.version())).body(view);
    }

    public record RegisterAppRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 160) String vendor,
            @NotNull StorefrontAppClientType clientType,
            @Nullable Boolean firstParty,
            @Nullable List<@NotBlank @Size(max = 300) String> originAllowlist,
            @NotBlank @Size(max = 1000) String reason) {}

    public record UpdateAppRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 160) String vendor,
            @Nullable List<@NotBlank @Size(max = 300) String> originAllowlist,
            @NotBlank @Size(max = 1000) String reason) {}

    public record ChangeStatusRequest(
            @NotNull StorefrontAppStatus status,
            @NotBlank @Size(max = 1000) String reason) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record ConformanceRequest(
            @NotNull ConformanceStatus result,
            @NotBlank @Size(max = 16) String contractVersion,
            @Nullable @Size(max = 1000) String note,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * The app, and the secret it was just given — present for a confidential client, once.
     *
     * <p>Redacted on purpose (ADR 0028): a record's generated {@code toString} prints every
     * component, and Spring's message converters log the deserialised body at TRACE.
     */
    public record RegisteredAppResponse(
            StorefrontAppView app, @Nullable String secretValue) {

        @Override
        public String toString() {
            return "RegisteredAppResponse[app=" + app.id() + ", secretValue=REDACTED]";
        }
    }
}
