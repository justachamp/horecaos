package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Row 4.2g: whether a variant is inside its own per-item sale schedule right
 * now (ADR 0019). {@code CatalogAuthoringService#isOnSaleNow} already answers
 * this question for the authoring side; this is the read the cart enforces —
 * live, not published, because a schedule change ("stop selling breakfast at
 * 11:00") has to take effect at once rather than survive until the next
 * republish, the same reasoning {@code StorefrontCatalogQuery}'s own class
 * doc gives for reading location offerings live rather than from the
 * publication.
 *
 * <p>A port rather than a dependency on the catalog module, matching {@link
 * CartMenuRules}: ordering needs one yes/no answer about one variant, not a
 * schedule-authoring model. {@code catalog.domain.ItemSaleSchedule}'s own
 * class doc explains why a module boundary means this is a second, narrower
 * copy of the weekly-window matching logic rather than a reuse — the same
 * choice this port's own implementation makes.
 */
public interface CartSaleWindowRules {

    /**
     * @param zone the branch's own IANA zone, so the window is read in the
     *             wall-clock time it was authored in, never UTC
     * @param at   the instant to check, converted to local time inside the zone
     * @return true when the variant has no windows at all (unrestricted, the
     *         default for every item nobody has scoped), or the moment falls
     *         inside one of its windows
     */
    boolean isOnSaleAt(UUID tenantId, UUID locationId, UUID variantId, ZoneId zone, Instant at);
}
