package uz.horecaos.platform.media.api;

import java.util.UUID;

/**
 * Asks the media module to let go of a brand-owned public asset (ADR 0157, Media and video).
 *
 * <p>The only way a module other than media retires an asset it created for a brand. It does not
 * delete anything itself: it moves the asset to {@code DELETION_REQUESTED}, from which it is no longer
 * displayable, and the existing deletion worker removes the object and every rendition. An asset any
 * {@link MediaUsageProbe} reports in use is left exactly as it is.
 *
 * <p>Deliberately narrow. Only a {@code PUBLIC} asset owned at {@code BRAND} scope can be released this
 * way -- the kind a creative or a poster is. A private asset (a staff photo, a courier's evidence) has
 * its own deletion path and its own rules (ADR 0029), and this port cannot reach it whatever id it is
 * handed.
 */
public interface MediaRelease {

    /** What a release request came to. */
    enum Outcome {
        /** The asset is no longer displayable and its objects are queued for removal. */
        RELEASED,
        /** Some module still uses it. Nothing changed. */
        STILL_IN_USE,
        /** Not a brand-owned public asset, not found, or already on its way out. Nothing changed. */
        NOT_RELEASABLE
    }

    /**
     * @param reason a fixed phrase for the audit record; never customer or tenant text
     */
    Outcome release(UUID tenantId, UUID assetId, String reason);
}
