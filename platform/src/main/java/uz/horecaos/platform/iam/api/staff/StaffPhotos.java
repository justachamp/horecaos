package uz.horecaos.platform.iam.api.staff;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a staff member's photo needs from the media pipeline (ADR 0010, ADR 0139),
 * as a port another module provides.
 *
 * <p>Inverted for the reason {@code iam.api.audit} and {@code iam.api.mail} are:
 * {@code media} already depends on {@code iam} (its assets are scoped by {@link
 * uz.horecaos.platform.iam.api.ResourceScope} and its facts are audited through
 * the recorder), so {@code iam} importing a media type would close a cycle Spring
 * Modulith refuses. {@code iam} says what it needs and {@code media} implements
 * it, which is the direction the two modules already run in.
 *
 * <p>The ADR pictured a narrow sibling method on {@code MediaAvailability}. That
 * port answers only "displayable in this tenant", which is the wrong question
 * for a person's picture -- a {@code PUBLIC} catalogue image is displayable and
 * must still be refused as somebody's profile photo -- and it lives in the module
 * {@code iam} may not import. The three methods below are that sibling, on the
 * side of the boundary that can reach it.
 *
 * <p>Every method answers the same thing for an asset of another tenant and for
 * one that does not exist: nothing. A port that distinguished the two would turn
 * the photo endpoint into a platform-wide existence oracle for asset ids (V0069).
 */
public interface StaffPhotos {

    /**
     * Ingests an image the caller already holds through the ADR 0010 pipeline:
     * the real content type, dimensions and size are read from the bytes and
     * weighed against the same budget as any upload, and the asset is created
     * {@code PRIVATE} and owned by the tenant itself.
     */
    Ingested ingest(UUID tenantId, byte[] content, @Nullable String originalFilename, @Nullable UUID actorId);

    /**
     * True only when the asset exists <em>in this tenant</em>, is verified,
     * has {@code PRIVATE} visibility and is owned at {@code TENANT} scope by
     * this very tenant.
     */
    boolean isPrivateTenantAsset(UUID tenantId, UUID assetId);

    /** A short-lived signed read URL for such an asset, never a public one; empty otherwise. */
    Optional<URI> signedReadUrl(UUID tenantId, UUID assetId);

    /**
     * @param rejectionCode null when {@code accepted}; otherwise one of the
     *                      codes the media pipeline already uses ({@code
     *                      SIZE_EXCEEDED}, {@code CONTENT_NOT_AN_IMAGE}, {@code
     *                      TYPE_NOT_ALLOWED}, {@code DIMENSIONS_EXCEEDED})
     */
    record Ingested(
            boolean accepted,
            @Nullable UUID assetId,
            @Nullable String rejectionCode) {}
}
