package uz.horecaos.platform.storefrontapps.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppAuthorisationService;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppBrandAuthorisationView;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppCatalogueEntry;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant choosing its storefront (ADR 0070): which registered apps may serve one of its
 * brands, authorised and withdrawn from the operations console.
 *
 * <p>Brand scope, so the owner and the administrator hold it and a branch manager does not.
 * Revoking needs no second step and no deployment: the next storefront request that names the
 * app for this brand is refused by name.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/storefront-apps")
@Tag(
        name = "Brand storefront apps",
        description = "ADR 0070: which registered storefront apps this brand has authorised")
public class OperationsStorefrontAppController {

    private final StorefrontAppAuthorisationService authorisations;
    private final CurrentActor currentActor;

    public OperationsStorefrontAppController(
            StorefrontAppAuthorisationService authorisations, CurrentActor currentActor) {
        this.authorisations = authorisations;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.STOREFRONT_APP_AUTHORISE, scope = ScopeType.BRAND)
    @Operation(
            summary = "The storefront apps this brand can choose, and what it has decided about each",
            description = "Every registered app that is not retired, with the brand's standing: "
                    + "NOT_AUTHORISED, AUTHORISED or REVOKED. The authorisationVersion is the If-Match "
                    + "value for revoking.")
    List<StorefrontAppCatalogueEntry> catalogue(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return authorisations.catalogue(tenantId, brandId);
    }

    @PostMapping("/{appId}/authorisations")
    @RequiresCapability(value = Capability.STOREFRONT_APP_AUTHORISE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Authorise a storefront app for this brand",
            description = "Also authorises again an app the brand revoked. Refused for an app that is "
                    + "suspended or retired, and with a conflict when the brand already authorises it.")
    ResponseEntity<StorefrontAppBrandAuthorisationView> authorise(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID appId,
            @Valid @RequestBody ReasonRequest request) {
        StorefrontAppBrandAuthorisationView granted =
                authorisations.authorise(tenantId, brandId, appId, actor(), request.reason());
        return ResponseEntity.status(201)
                .eTag(AggregateVersion.toETag(granted.version()))
                .body(granted);
    }

    @PostMapping("/{appId}/revocations")
    @RequiresCapability(value = Capability.STOREFRONT_APP_AUTHORISE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Revoke this brand's authorisation of a storefront app",
            description = "Needs If-Match with the authorisation version read. Takes effect on the next "
                    + "storefront request the app makes for this brand, which is refused with APP_REVOKED.")
    ResponseEntity<StorefrontAppBrandAuthorisationView> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID appId,
            HttpServletRequest http,
            @Valid @RequestBody ReasonRequest request) {
        StorefrontAppBrandAuthorisationView revoked = authorisations.revoke(
                tenantId, brandId, appId, AggregateVersion.requireIfMatch(http), actor(), request.reason());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(revoked.version()))
                .body(revoked);
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
}
