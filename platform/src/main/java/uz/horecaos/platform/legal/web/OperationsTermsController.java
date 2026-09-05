package uz.horecaos.platform.legal.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.legal.application.TermsPublishingService;
import uz.horecaos.platform.legal.domain.TermsVersion;
import uz.horecaos.platform.legal.domain.TermsVersionSummary;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant authoring its own terms of service, from the operations console
 * (ADR 0068).
 *
 * <p>A thin adapter over {@link TermsPublishingService}; every rule —
 * versions are append-only, at least one language is required, an unknown
 * locale or an oversized body is refused — is the service's, not this
 * controller's.
 *
 * <p>{@code BRAND} scope everywhere, matching the path: {@code
 * EndpointCapabilityDeclarationTests.aDeclaredScopeIsNoWiderThanTheEndpointsPath}
 * refuses a {@code TENANT}-scoped declaration under a path naming {@code
 * {brandId}}. {@link Capability#TERMS_MANAGE} and {@link Capability#TERMS_READ}
 * are both held by {@code tenant-owner} alone among the tenant bundles, and a
 * {@code TENANT}-scoped grant satisfies a {@code BRAND}-scoped check (ADR 0025
 * scopes cover downwards) — which is also why the operations frontend resolves
 * this screen's tenant through {@code CurrentTenant} and lets the operator
 * pick a brand from {@code OperationsBrandController.list}, rather than
 * through {@code CurrentBrand}: an owner's only grant is {@code TENANT}-scoped
 * and carries no brand id for {@code CurrentBrand} to read.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/terms-documents")
@Tag(
        name = "Terms of service authoring",
        description = "Publishing and reading a brand's own terms of service (ADR 0068)")
public class OperationsTermsController {

    private final TermsPublishingService publishing;
    private final CurrentActor currentActor;

    public OperationsTermsController(TermsPublishingService publishing, CurrentActor currentActor) {
        this.publishing = publishing;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.TERMS_READ, scope = ScopeType.BRAND)
    @Operation(summary = "This brand's publishing history, newest first")
    List<TermsVersionSummaryView> history(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return publishing.history(tenantId, brandId).stream()
                .map(TermsVersionSummaryView::of)
                .toList();
    }

    @GetMapping("/current")
    @RequiresCapability(value = Capability.TERMS_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The version currently in force",
            description = "published: false with empty contents when this brand has never published — "
                    + "the storefront is serving the platform default, and this is what the authoring "
                    + "screen starts an operator's first draft from.")
    TermsVersionView current(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return publishing.current(tenantId, brandId).map(TermsVersionView::of).orElseGet(TermsVersionView::unpublished);
    }

    @GetMapping("/{version}")
    @RequiresCapability(value = Capability.TERMS_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One historical version, exactly as it was published")
    TermsVersionView get(@PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable int version) {
        return publishing
                .version(tenantId, brandId, version)
                .map(TermsVersionView::of)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such terms version"));
    }

    @PostMapping
    @RequiresCapability(value = Capability.TERMS_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish the next version",
            description = "Creates a new version; never edits a prior one. At least one language is "
                    + "required. A language the tenant does not write in this version keeps whatever "
                    + "it last published, or falls back to the platform default if it never published "
                    + "that language at all.")
    ResponseEntity<TermsVersionView> publish(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody PublishRequest request) {

        TermsVersion published = publishing.publish(
                tenantId,
                brandId,
                request.contentsByLocale(),
                ActorRef.user(currentActor.get().subject(), null),
                request.note());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/terms-documents/{version}")
                .buildAndExpand(tenantId, brandId, published.version())
                .toUri();
        return ResponseEntity.created(location).body(TermsVersionView.of(published));
    }

    record PublishRequest(
            @NotEmpty Map<String, String> contentsByLocale,
            @Nullable @Size(max = 500) String note) {}

    record TermsVersionSummaryView(UUID id, int version, Set<String> locales, String publishedBy, Instant publishedAt) {
        static TermsVersionSummaryView of(TermsVersionSummary summary) {
            return new TermsVersionSummaryView(
                    summary.id(), summary.version(), summary.locales(), summary.publishedBy(), summary.publishedAt());
        }
    }

    record TermsVersionView(
            boolean published,
            @Nullable UUID id,
            @Nullable Integer version,
            Map<String, String> contentsByLocale,
            @Nullable String publishedBy,
            @Nullable Instant publishedAt) {

        static TermsVersionView of(TermsVersion version) {
            return new TermsVersionView(
                    true,
                    version.id(),
                    version.version(),
                    version.contentsByLocale(),
                    version.publishedBy(),
                    version.publishedAt());
        }

        static TermsVersionView unpublished() {
            return new TermsVersionView(false, null, null, Map.of(), null, null);
        }
    }
}
