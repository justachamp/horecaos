package uz.horecaos.platform.media.api;

/**
 * The lifecycle of an asset (ADR 0010).
 *
 * <p>Only {@link #AVAILABLE} may be shown to a customer. The states before it
 * exist because "the client says it uploaded a JPEG" and "the object store holds
 * a verified JPEG of the expected size" are different claims, and treating the
 * first as the second is how a storefront ends up serving an executable.
 */
public enum MediaAssetStatus {

    /** Allocated with a presigned URL. No bytes are known to exist yet. */
    PENDING_UPLOAD,

    /** Bytes exist at the key. Not yet verified, so not yet displayable. */
    UPLOADED,

    /** Verified against the store's own metadata. Safe to serve. */
    AVAILABLE,

    /** Verification failed. The reason is recorded; the object is not served. */
    REJECTED,

    DELETION_REQUESTED,
    DELETED;

    public boolean isDisplayable() {
        return this == AVAILABLE;
    }
}
