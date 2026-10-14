package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.api.PurposedImages;
import uz.horecaos.platform.tenancy.application.KioskIdleMediaService;
import uz.horecaos.platform.tenancy.application.KioskIdleMediaService.IdleMediaList;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A KIOSK channel's idle media: list, upload, attach, order and remove (ADR 0187, D25).
 *
 * <p>Every endpoint sits behind {@code kiosk.device.manage} at {@code LOCATION} scope, the capability a branch
 * manager holds (she holds neither {@code channel.manage} nor {@code media.upload}), and works for a channel that
 * sells at the branch in the path. The list is one aggregate with one version: every change is sent against the
 * {@code If-Match} the screen read, and a second manager's change in between is {@code STALE_VERSION}.
 */
@RestController
@RequestMapping(
        "/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kiosk/channels/{channelId}/idle-media")
@Tag(name = "Kiosk idle media", description = "ADR 0187: the pictures a KIOSK channel plays while nobody is ordering")
public class KioskIdleMediaController {

    private final KioskIdleMediaService idleMedia;
    private final CurrentActor currentActor;

    public KioskIdleMediaController(KioskIdleMediaService idleMedia, CurrentActor currentActor) {
        this.idleMedia = idleMedia;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "A KIOSK channel's idle media, in the order it plays",
            description = "At most eight pictures. The version is the If-Match of the next change; the limits "
                    + "(count, bytes, ratio) are the ones the platform enforces, so a screen never hard-codes them.")
    public ResponseEntity<IdleMediaList> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID channelId) {
        IdleMediaList list = idleMedia.list(tenantId, locationId, channelId);
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(list.version())).body(list);
    }

    @PostMapping("/upload-requests")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Allocate an idle-media picture and return a constrained upload URL",
            description = "A public, tenant-owned still image (JPEG, PNG or WebP) of at most 1 MB, signed for exactly "
                    + "this key, type and size. Upload to it directly, then call finalize. The 9:16 shape is checked "
                    + "when the pipeline has read the file's own header: a wrong one ends REJECTED.")
    public ResponseEntity<UploadTicketResponse> requestUpload(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID channelId,
            @Valid @RequestBody UploadRequest body) {
        PurposedImages.UploadTicket ticket = idleMedia.requestUpload(
                tenantId, locationId, channelId, body.contentType(), body.sizeBytes(), body.filename(), actorId());
        return ResponseEntity.ok(new UploadTicketResponse(
                ticket.assetId(), ticket.uploadUrl(), ticket.requiredHeaders(), ticket.expiresAt()));
    }

    @GetMapping("/uploads/{assetId}")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Where an idle-media upload's verification stands",
            description = "UPLOADED while the pipeline has not read the file, then AVAILABLE or REJECTED; a rejected "
                    + "picture carries the code it was refused with (PURPOSE_ASPECT_RATIO for a picture that is not "
                    + "9:16). A 404 for any asset that is not an idle-media picture of this tenant.")
    public ResponseEntity<UploadStateResponse> uploadState(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID channelId,
            @PathVariable UUID assetId) {
        PurposedImages.UploadState state = idleMedia.uploadState(tenantId, locationId, channelId, assetId);
        return ResponseEntity.ok(new UploadStateResponse(state.assetId(), state.status(), state.rejectionCode()));
    }

    @PostMapping("/uploads/{assetId}/finalize")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Record that an idle-media upload is complete and queue verification",
            description = "As the media route, for a picture allocated here. The status is UPLOADED; AVAILABLE or "
                    + "REJECTED follows, and only AVAILABLE can be attached. A 404 for any asset that is not an "
                    + "idle-media picture of this tenant.")
    public ResponseEntity<FinalizeResponse> finalizeUpload(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID channelId,
            @PathVariable UUID assetId) {
        MediaAssetStatus status = idleMedia.finalizeUpload(tenantId, locationId, channelId, assetId);
        return ResponseEntity.ok(new FinalizeResponse(assetId, status));
    }

    @PostMapping
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Put a verified picture at the end of the idle loop",
            description = "The picture is checked again here -- an idle-media picture of this tenant, 9:16, at most "
                    + "1 MB, verified -- and refused with IDLE_MEDIA_REFUSED and a reason otherwise, a ninth "
                    + "included. Writes an ADR 0027 fact naming the branch.")
    public ResponseEntity<IdleMediaList> attach(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID channelId,
            @Valid @RequestBody AttachRequest body,
            HttpServletRequest request) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(request));
        IdleMediaList list = idleMedia.attach(
                tenantId,
                brandId,
                locationId,
                channelId,
                body.assetId(),
                expected,
                currentActor.get().subject());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(list.version())).body(list);
    }

    @PutMapping("/order")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Put the idle loop in another order",
            description = "itemIds must list every picture on the loop exactly once; anything else is refused and "
                    + "nothing moves.")
    public ResponseEntity<IdleMediaList> reorder(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID channelId,
            @Valid @RequestBody OrderRequest body,
            HttpServletRequest request) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(request));
        IdleMediaList list = idleMedia.reorder(
                tenantId,
                brandId,
                locationId,
                channelId,
                body.itemIds(),
                expected,
                currentActor.get().subject());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(list.version())).body(list);
    }

    @DeleteMapping("/{itemId}")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Take a picture off the idle loop",
            description = "The pictures behind it move up. The asset itself is not deleted: the media pipeline's "
                    + "own retention decides that once nothing refers to it.")
    public ResponseEntity<IdleMediaList> remove(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID channelId,
            @PathVariable UUID itemId,
            HttpServletRequest request) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(request));
        IdleMediaList list = idleMedia.remove(
                tenantId,
                brandId,
                locationId,
                channelId,
                itemId,
                expected,
                currentActor.get().subject());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(list.version())).body(list);
    }

    /** The Keycloak subject as a UUID, or null: attribution only, never a reason to refuse an upload. */
    private @Nullable UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    // -------------------------------------------------------------------------- bodies

    record UploadRequest(
            @NotBlank @Size(max = 128) String contentType,
            @NotNull @Positive Long sizeBytes,
            @Nullable @Size(max = 255) String filename) {}

    record UploadTicketResponse(UUID assetId, URI uploadUrl, Map<String, String> requiredHeaders, Instant expiresAt) {}

    record UploadStateResponse(
            UUID assetId, MediaAssetStatus status, @Nullable String rejectionCode) {}

    record FinalizeResponse(UUID assetId, MediaAssetStatus status) {}

    record AttachRequest(@NotNull UUID assetId) {}

    record OrderRequest(@NotNull @Size(max = 8) List<@NotNull UUID> itemIds) {}
}
