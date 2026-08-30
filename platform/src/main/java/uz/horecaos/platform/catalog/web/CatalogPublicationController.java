package uz.horecaos.platform.catalog.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Publishing a menu (ADR 0016).
 *
 * <p>Validation is exposed separately from publishing so an operator can see
 * every problem before committing to anything, rather than discovering them one
 * failed publish at a time.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog")
@Tag(name = "Catalog publication", description = "Validation, publication, and rollback")
public class CatalogPublicationController {

    private final CatalogPublicationService publication;
    private final CurrentActor currentActor;

    public CatalogPublicationController(CatalogPublicationService publication, CurrentActor currentActor) {
        this.publication = publication;
        this.currentActor = currentActor;
    }

    // ADR 0016 listed this as a POST, but validation has no effect, and ADR 0031's
    // gate is right that a POST must. GET is both honest and cacheable.
    @GetMapping("/catalogs/{catalogId}/validation")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Report every problem without publishing",
            description = "Returns stable codes and the entity each one is about, because "
                    + "\"a product has no variant\" is unactionable without knowing which product.")
    public ResponseEntity<ValidationResponse> validate(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID catalogId) {
        try {
            ValidationFinding.Report report = publication.validate(tenantId, brandId, catalogId);
            return ResponseEntity.ok(ValidationResponse.of(report));
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @PostMapping("/catalogs/{catalogId}/publications")
    @RequiresCapability(value = Capability.CATALOG_PUBLISH, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Snapshot, validate, and make live",
            description = "A rejected publication is still recorded with its report, so "
                    + "\"why did publishing fail an hour ago\" has an answer.")
    public ResponseEntity<PublicationResponse> publish(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID catalogId,
            @RequestParam(defaultValue = "STOREFRONT") String channel) {

        try {
            var result = publication.publish(tenantId, brandId, catalogId, channel, actorId());
            PublicationResponse body = new PublicationResponse(result.publicationId(),
                    result.status(), result.contentHash(), ValidationResponse.of(result.report()));

            // A rejection is a completed request that produced a considered "no",
            // not a server fault: 200 with the report is more useful to an
            // operator UI than an error status with a message string.
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @PostMapping("/publications/{publicationId}/activate")
    @RequiresCapability(value = Capability.CATALOG_PUBLISH, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Roll back to a previous publication",
            description = "Republishes an existing snapshot; it never edits history. The channel "
                    + "comes from the publication itself, so a rollback cannot retire one "
                    + "channel's menu and activate another channel's snapshot.")
    public ResponseEntity<PublicationResponse> rollback(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID publicationId) {

        try {
            var result = publication.rollbackTo(tenantId, brandId, publicationId);
            return ResponseEntity.ok(new PublicationResponse(result.publicationId(),
                    result.status(), result.contentHash(), ValidationResponse.of(result.report())));
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        } catch (IllegalStateException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage());
        }
    }

    private UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    public record ValidationResponse(boolean publishable, List<FindingView> findings) {

        static ValidationResponse of(ValidationFinding.Report report) {
            return new ValidationResponse(report.publishable(),
                    report.findings().stream().map(FindingView::of).toList());
        }
    }

    public record FindingView(String severity, String code, String entityType, UUID entityId,
            String entityCode, String detail) {

        static FindingView of(ValidationFinding finding) {
            return new FindingView(finding.severity().name(), finding.code(),
                    finding.entityType() == null ? null : finding.entityType().name(),
                    finding.entityId(), finding.entityCode(), finding.detail());
        }
    }

    public record PublicationResponse(UUID publicationId, PublicationStatus status,
            String contentHash, ValidationResponse validation) { }
}
