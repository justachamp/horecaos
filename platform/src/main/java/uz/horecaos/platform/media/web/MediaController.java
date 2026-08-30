package uz.horecaos.platform.media.web;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.application.MediaAssetService;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Media upload and retrieval (ADR 0010).
 *
 * <p>Bytes never pass through these endpoints. The client is handed a
 * constrained, short-lived URL and uploads to the object store directly, which
 * keeps a ten-megabyte photo from occupying a request thread and keeps our
 * bandwidth out of the path.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/media/assets")
@Tag(name = "Media", description = "Presigned upload lifecycle for catalog and brand imagery")
public class MediaController {

    private final MediaAssetService media;
    private final CurrentActor currentActor;

    public MediaController(MediaAssetService media, CurrentActor currentActor) {
        this.media = media;
        this.currentActor = currentActor;
    }

    @PostMapping("/upload-requests")
    @RequiresCapability(value = Capability.MEDIA_UPLOAD, mutating = true)
    @Operation(summary = "Allocate an asset and return a constrained upload URL",
            description = "The returned URL is signed for exactly this key, content type, and size, "
                    + "and expires shortly. Upload to it directly, then call finalize.")
    public ResponseEntity<UploadTicketResponse> requestUpload(
            @PathVariable UUID tenantId, @Valid @RequestBody UploadRequest request) {
        MediaOwner owner = new MediaOwner(request.ownerScope(), request.ownerId());

        MediaAssetService.UploadTicket ticket;
        try {
            ticket = media.requestUpload(tenantId, owner, request.visibility(),
                    request.contentType(), request.sizeBytes(), request.filename(),
                    actorId());
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }

        return ResponseEntity.ok(new UploadTicketResponse(
                ticket.assetId().toString(), ticket.uploadUrl(),
                ticket.requiredHeaders(), ticket.expiresAt()));
    }

    @PostMapping("/{assetId}/finalize")
    @RequiresCapability(value = Capability.MEDIA_UPLOAD, mutating = true)
    @Operation(summary = "Verify the uploaded object and make it displayable",
            description = "Reads the object store's own metadata. The request body carries no claims "
                    + "about the upload, because a client's claim is not evidence.")
    public ResponseEntity<AssetResponse> finalizeUpload(
            @PathVariable UUID tenantId, @PathVariable UUID assetId) {
        MediaAssetStatus status;
        try {
            status = media.finalizeUpload(tenantId, new MediaAssetId(assetId));
        } catch (IllegalArgumentException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such media asset");
        }
        return ResponseEntity.ok(new AssetResponse(assetId.toString(), status));
    }

    @GetMapping("/{assetId}")
    @RequiresCapability(Capability.MEDIA_READ)
    @Operation(summary = "Read an asset's status")
    public ResponseEntity<AssetResponse> get(
            @PathVariable UUID tenantId, @PathVariable UUID assetId) {
        return media.find(tenantId, new MediaAssetId(assetId))
                .map(asset -> ResponseEntity.ok(new AssetResponse(assetId.toString(), asset.status())))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such media asset"));
    }

    @GetMapping("/{assetId}/download-url")
    @RequiresCapability(Capability.MEDIA_READ)
    @Operation(summary = "Return a short-lived signed URL for a private asset",
            description = "Only an AVAILABLE asset yields a URL; an unverified object is never served.")
    public ResponseEntity<DownloadResponse> downloadUrl(
            @PathVariable UUID tenantId, @PathVariable UUID assetId) {
        return media.downloadUrl(tenantId, new MediaAssetId(assetId))
                .map(url -> ResponseEntity.ok(new DownloadResponse(url)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such media asset, or it is not available"));
    }

    /**
     * The Keycloak subject as a UUID, or null if this realm ever issues a
     * non-UUID subject. Recorded for attribution only, so an unparseable one is
     * not worth failing an upload over.
     */
    private UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * @param sizeBytes the exact byte count. The presigned URL is signed for it,
     *                  so an upload of a different size will not be accepted
     */
    public record UploadRequest(
            @NotNull MediaOwner.Scope ownerScope,
            @NotNull UUID ownerId,
            @NotNull MediaVisibility visibility,
            @NotBlank String contentType,
            @Positive @Max(10 * 1024 * 1024) long sizeBytes,
            String filename) { }

    public record UploadTicketResponse(String assetId, URI uploadUrl,
            Map<String, String> requiredHeaders, Instant expiresAt) { }

    public record AssetResponse(String assetId, MediaAssetStatus status) { }

    public record DownloadResponse(URI url) { }
}
