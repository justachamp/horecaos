package uz.horecaos.platform.media.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import uz.horecaos.platform.iam.api.EntitlementGate;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.application.MediaAssetService;
import uz.horecaos.platform.media.domain.DerivativeVariant;
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

    /**
     * The commercial entitlement that admits video (ADR 0157). Named as a string, as {@code content}
     * does, so media takes no edge to {@code commercial}.
     */
    private static final String VIDEO_ENTITLEMENT = "content.video.enabled";

    private final MediaAssetService media;
    private final CurrentActor currentActor;
    private final List<EntitlementGate> entitlements;

    public MediaController(MediaAssetService media, CurrentActor currentActor, List<EntitlementGate> entitlements) {
        this.media = media;
        this.currentActor = currentActor;
        this.entitlements = List.copyOf(entitlements);
    }

    @PostMapping("/upload-requests")
    @RequiresCapability(value = Capability.MEDIA_UPLOAD, mutating = true)
    @Operation(
            summary = "Allocate an asset and return a constrained upload URL",
            description = "The returned URL is signed for exactly this key, content type, and size, "
                    + "and expires shortly. Upload to it directly, then call finalize.")
    public ResponseEntity<UploadTicketResponse> requestUpload(
            @PathVariable UUID tenantId, @Valid @RequestBody UploadRequest request) {
        MediaOwner owner = new MediaOwner(request.ownerScope(), request.ownerId());
        if (MediaAssetService.VIDEO_MP4.equalsIgnoreCase(request.contentType().strip())) {
            requireVideoEntitlement(tenantId);
        }

        MediaAssetService.UploadTicket ticket;
        try {
            ticket = media.requestUpload(
                    tenantId,
                    owner,
                    request.visibility(),
                    request.contentType(),
                    request.sizeBytes(),
                    request.filename(),
                    actorId(),
                    request.renditionProfile());
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }

        return ResponseEntity.ok(new UploadTicketResponse(
                ticket.assetId().toString(), ticket.uploadUrl(),
                ticket.requiredHeaders(), ticket.expiresAt()));
    }

    @PostMapping("/{assetId}/finalize")
    @RequiresCapability(value = Capability.MEDIA_UPLOAD, mutating = true)
    @Operation(
            summary = "Record that the upload is complete and queue verification",
            description = "Never touches the object store. Records the client's claim that the upload "
                    + "finished and queues asynchronous verification; the returned status is UPLOADED, "
                    + "not AVAILABLE. A client's claim is not evidence, so nothing becomes displayable "
                    + "until a separate verification worker has read the object store's own metadata.")
    public ResponseEntity<AssetResponse> finalizeUpload(@PathVariable UUID tenantId, @PathVariable UUID assetId) {
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
    public ResponseEntity<AssetResponse> get(@PathVariable UUID tenantId, @PathVariable UUID assetId) {
        return media.find(tenantId, new MediaAssetId(assetId))
                .map(asset -> ResponseEntity.ok(new AssetResponse(assetId.toString(), asset.status())))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such media asset"));
    }

    @GetMapping("/{assetId}/download-url")
    @RequiresCapability(Capability.MEDIA_READ)
    @Operation(
            summary = "Return a short-lived signed URL for a private asset",
            description = "Only an AVAILABLE asset yields a URL; an unverified object is never "
                    + "served. Pass `variant` (THUMBNAIL/CARD/DETAIL) for a rendition rather than "
                    + "the original — a grid rendering dozens of images has no business fetching "
                    + "full-size originals for every one of them, and until this parameter existed "
                    + "there was no way to ask for anything else: every derivative ADR 0010's "
                    + "pipeline renders was stored and never served.")
    public ResponseEntity<DownloadResponse> downloadUrl(
            @PathVariable UUID tenantId,
            @PathVariable UUID assetId,
            @RequestParam(required = false) @Nullable DerivativeVariant variant) {
        return media.downloadUrl(tenantId, new MediaAssetId(assetId), variant)
                .map(url -> ResponseEntity.ok(new DownloadResponse(url)))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        variant == null
                                ? "No such media asset, or it is not available"
                                : "No such media asset, it is not available, or that rendition has not rendered yet"));
    }

    /**
     * Video is off until the platform owner grants it to the tenant (ADR 0157). Asked of
     * {@code checkGranted}: the plain feature check answers yes for a feature nobody granted under the
     * meter-only default, which is the wrong answer for a switch that is off by default. An unknown key
     * or no gate at all reads as not entitled.
     */
    private void requireVideoEntitlement(UUID tenantId) {
        for (EntitlementGate gate : entitlements) {
            Optional<EntitlementGate.Answer> answer = gate.checkGranted(tenantId, VIDEO_ENTITLEMENT);
            if (answer.isPresent()) {
                if (answer.get().entitled()) {
                    return;
                }
                break;
            }
        }
        throw ApiException.entitlementRequired(VIDEO_ENTITLEMENT);
    }

    /**
     * The Keycloak subject as a UUID, or null if this realm ever issues a
     * non-UUID subject. Recorded for attribution only, so an unparseable one is
     * not worth failing an upload over.
     */
    private @Nullable UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * What a client asks to upload.
     *
     * @param sizeBytes the exact byte count. The presigned URL is signed for it,
     *                  so an upload of a different size will not be accepted. An image is
     *                  at most 10 MiB and an MP4 video at most 12 MiB; the service applies
     *                  the cap that fits the type
     * @param renditionProfile omitted for the standard renditions; {@code CREATIVE} for an
     *                  image the console uploads for the storefront's home content, which
     *                  is also rendered at {@code HERO} (ADR 0157)
     */
    public record UploadRequest(
            @NotNull MediaOwner.Scope ownerScope,
            @NotNull UUID ownerId,
            @NotNull MediaVisibility visibility,
            @NotBlank String contentType,
            @Positive @Max(12 * 1024 * 1024) long sizeBytes,
            String filename,
            @Nullable @Pattern(regexp = "CREATIVE") String renditionProfile) {}

    public record UploadTicketResponse(
            String assetId, URI uploadUrl, Map<String, String> requiredHeaders, Instant expiresAt) {}

    public record AssetResponse(String assetId, MediaAssetStatus status) {}

    public record DownloadResponse(URI url) {}
}
