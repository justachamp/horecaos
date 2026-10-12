package uz.horecaos.platform.media.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.application.MediaAssetService;
import uz.horecaos.platform.media.domain.DerivativeVariant;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The brand's own door to media (ADR 0010, ADR 0025, ADR 0157): the same upload lifecycle as
 * {@link MediaController}, declared at {@code BRAND} scope and owned by the brand in the path.
 *
 * <p>{@link MediaController} is a tenant route, and a grant covers downward only: a brand manager
 * holds {@code content.author} and {@code content.publish} at her brand and so could write a banner
 * but not upload its picture, because her {@code media.upload} (a brand grant) never covers a tenant
 * route. A tenant owner or administrator is covered here too, by the same downward rule, so the
 * console has one path to call for a brand's creative whoever is signed in.
 *
 * <p>Two properties keep this from widening anything. The owner is not in the body: every asset
 * allocated here is {@code BRAND}-owned by the brand in the path, so a brand manager cannot allocate
 * an asset owned by a tenant or another brand. And the other three routes answer for the brand's own
 * assets only, with the same 404 a missing asset gets -- an asset of another brand of the same tenant
 * is not distinguishable from one that does not exist.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/media/assets")
@Tag(name = "Media", description = "Presigned upload lifecycle for a brand's own imagery and video")
public class BrandMediaController {

    private final MediaAssetService media;
    private final CurrentActor currentActor;
    private final VideoUploadEntitlement videoEntitlement;

    BrandMediaController(MediaAssetService media, CurrentActor currentActor, VideoUploadEntitlement videoEntitlement) {
        this.media = media;
        this.currentActor = currentActor;
        this.videoEntitlement = videoEntitlement;
    }

    @PostMapping("/upload-requests")
    @RequiresCapability(value = Capability.MEDIA_UPLOAD, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Allocate a brand-owned asset and return a constrained upload URL",
            description = "As the tenant route, for an asset owned by the brand in the path. The returned URL "
                    + "is signed for exactly this key, content type, and size, and expires shortly. Upload "
                    + "to it directly, then call finalize.")
    public ResponseEntity<MediaController.UploadTicketResponse> requestBrandUpload(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody BrandUploadRequest request) {
        if (MediaAssetService.VIDEO_MP4.equalsIgnoreCase(request.contentType().strip())) {
            videoEntitlement.require(tenantId);
        }
        MediaAssetService.UploadTicket ticket;
        try {
            ticket = media.requestUpload(
                    tenantId,
                    MediaOwner.brand(brandId),
                    request.visibility(),
                    request.contentType(),
                    request.sizeBytes(),
                    request.filename(),
                    actorId(),
                    request.renditionProfile());
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
        return ResponseEntity.ok(new MediaController.UploadTicketResponse(
                ticket.assetId().toString(), ticket.uploadUrl(), ticket.requiredHeaders(), ticket.expiresAt()));
    }

    @PostMapping("/{assetId}/finalize")
    @RequiresCapability(value = Capability.MEDIA_UPLOAD, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Record that a brand asset's upload is complete and queue verification",
            description = "As the tenant route; a 404 for an asset the brand does not own.")
    public ResponseEntity<MediaController.AssetResponse> finalizeBrandUpload(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID assetId) {
        owned(tenantId, brandId, assetId);
        MediaAssetStatus status;
        try {
            status = media.finalizeUpload(tenantId, new MediaAssetId(assetId));
        } catch (IllegalArgumentException missing) {
            throw notFound();
        }
        return ResponseEntity.ok(new MediaController.AssetResponse(assetId.toString(), status));
    }

    @GetMapping("/{assetId}")
    @RequiresCapability(value = Capability.MEDIA_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Read a brand asset's status")
    public ResponseEntity<MediaController.AssetResponse> getBrandAsset(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID assetId) {
        MediaAsset asset = owned(tenantId, brandId, assetId);
        return ResponseEntity.ok(new MediaController.AssetResponse(assetId.toString(), asset.status()));
    }

    @GetMapping("/{assetId}/download-url")
    @RequiresCapability(value = Capability.MEDIA_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Return a short-lived signed URL for a brand asset",
            description = "As the tenant route: only an AVAILABLE asset yields a URL, `variant` asks for a "
                    + "rendition, and a 404 covers an asset the brand does not own.")
    public ResponseEntity<MediaController.DownloadResponse> brandDownloadUrl(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID assetId,
            @RequestParam(required = false) @Nullable DerivativeVariant variant) {
        owned(tenantId, brandId, assetId);
        return media.downloadUrl(tenantId, new MediaAssetId(assetId), variant)
                .map(url -> ResponseEntity.ok(new MediaController.DownloadResponse(url)))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        variant == null
                                ? "No such media asset, or it is not available"
                                : "No such media asset, it is not available, or that rendition has not rendered yet"));
    }

    /** The asset when this brand owns it; otherwise the answer a missing asset gets. */
    private MediaAsset owned(UUID tenantId, UUID brandId, UUID assetId) {
        return media.find(tenantId, new MediaAssetId(assetId))
                .filter(asset -> asset.owner().equals(MediaOwner.brand(brandId)))
                .orElseThrow(BrandMediaController::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such media asset");
    }

    private @Nullable UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * What a client asks to upload for the brand in the path. There is no owner: the brand is the owner.
     *
     * @param sizeBytes the exact byte count the presigned URL is signed for; an image is at most 10 MiB and
     *                  an MP4 video at most 12 MiB, and the service applies the cap that fits the type
     * @param renditionProfile omitted for the standard renditions; {@code CREATIVE} for an image the console
     *                  uploads for the storefront's home content, which is also rendered at {@code HERO}
     */
    public record BrandUploadRequest(
            @NotNull MediaVisibility visibility,
            @NotBlank String contentType,
            @Positive @Max(12 * 1024 * 1024) long sizeBytes,
            String filename,
            @Nullable @Pattern(regexp = "CREATIVE") String renditionProfile) {}
}
