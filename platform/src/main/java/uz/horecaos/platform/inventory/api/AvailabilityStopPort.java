package uz.horecaos.platform.inventory.api;

import java.util.UUID;

/**
 * The stops a source other than the console writes, for a consumer outside
 * {@code inventory} (ADR 0141 Decision 4).
 *
 * <p>Narrow by design, matching {@link StockAvailabilityPort}: the POS poll's two
 * verbs and nothing else. A POS "out of stock" reading writes a {@code POS} stop at
 * its location and a "back in stock" reading ends <em>only</em> that binding's {@code
 * POS} stop, so it can no longer un-86 a dish an operator stopped by hand — the
 * defect this port exists to close — and an operator lifting their own stop no
 * longer erases the POS's opinion.
 *
 * <p>Unlike {@link StockAvailabilityPort#toggle}, neither verb needs the item to be
 * {@code BINARY}-tracked: a stop covers an {@code UNTRACKED} or {@code QUANTITY}
 * dish as well.
 */
public interface AvailabilityStopPort {

    /**
     * Records that the POS binding reports this variant out of stock at this
     * location. Idempotent: a stop this binding already holds is left as it is.
     */
    void placePosStop(UUID tenantId, UUID brandId, UUID locationId, UUID variantId, UUID bindingId);

    /**
     * Ends this binding's own {@code POS} stop on this variant at this location,
     * if it has one. Never touches an {@code OPERATOR} or {@code BOT} stop, nor
     * another binding's.
     *
     * @return whether a stop was ended
     */
    boolean liftPosStop(UUID tenantId, UUID locationId, UUID variantId, UUID bindingId);
}
