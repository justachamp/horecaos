package uz.horecaos.platform.fulfillment.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The courier ETA fulfilment captured for an order's live delivery plan
 * (ADR 0014, gap map row 2.1a).
 *
 * <p>Declared here, in the same {@code api} named interface {@link
 * OrderProgressPort} already crosses the kitchen/fulfilment boundary through,
 * because the kitchen board's ticket join (gap map row 2.1a: "courier ETA on
 * the kitchen ticket") is the one caller outside this module that needs it.
 * The kitchen holds an order id, never a delivery plan id of its own, so the
 * read is keyed the same way {@code OrderProgressPort} is.
 */
public interface CourierEtaPort {

    /**
     * The captured ETA for every order in the set that has one, batched for a
     * whole kitchen board page in one round trip rather than one query per
     * ticket. Absent for an order with no live delivery plan, a plan an
     * in-house courier carries, or a plan whose winning quote named no ETA —
     * never fabricated.
     */
    Map<UUID, Instant> etaByOrders(UUID tenantId, Collection<UUID> orderIds);
}
