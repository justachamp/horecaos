package uz.horecaos.platform.media.api;

import java.util.UUID;

/**
 * Whether an asset may be shown as one tenant-owned public image of a given shape -- a feedback tag's
 * icon (ADR 0159, Decision 2, ADR 0010).
 *
 * <p>A second, narrower question than {@link MediaAvailability#allDisplayable}, which would accept a
 * brand-owned or private asset: a tag icon is a public image the tenant itself owns, served to anonymous
 * customers through the storefront media route, so the check names each condition and reports the one
 * that failed. Pure read, no authorisation; the caller (a capability-gated endpoint) has decided who may
 * ask.
 *
 * <p>Every reason except {@link Verdict#ACCEPTED} is one the console can show a person, and none says
 * more than the person already knows: an asset of another tenant answers {@link Verdict#UNKNOWN_ASSET},
 * the same as one that does not exist, so an id cannot be probed.
 */
public interface TenantImageCheck {

    Verdict check(UUID tenantId, UUID assetId, int ratioWidth, int ratioHeight, long maximumBytes);

    enum Verdict {
        ACCEPTED,
        /** No such asset, or not this tenant's -- one answer. */
        UNKNOWN_ASSET,
        /** Owned by a brand or a location rather than by the tenant. */
        NOT_TENANT_OWNED,
        /** Visibility is {@code PRIVATE}: it would never be served to a customer. */
        NOT_PUBLIC,
        /** Not yet verified, rejected or deleted. */
        NOT_AVAILABLE,
        /** Not a raster image whose dimensions the pipeline read. */
        NOT_A_RASTER_IMAGE,
        WRONG_ASPECT_RATIO,
        TOO_LARGE
    }
}
