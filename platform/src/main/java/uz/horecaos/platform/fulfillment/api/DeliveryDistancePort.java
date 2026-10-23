package uz.horecaos.platform.fulfillment.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The distance fulfilment measured for an order's live delivery plan, for the order board's optional
 * «Дистанция» column (gap map row {@code 1.1}, orders.md §2.5 «behind the picker», ADR 0014).
 *
 * <p>Declared beside {@link CourierEtaPort} for the same reason and keyed the same way: the caller holds
 * an order id, never a delivery plan id, and asks once for a whole board page.
 */
public interface DeliveryDistancePort {

    /**
     * The plan's route distance in metres for every order in the set that has one. Absent for an order
     * with no live delivery plan (a pickup, a dine-in order, one cancelled) and for a plan the tariff
     * priced without measuring -- never fabricated, never zero standing in for «unknown».
     */
    Map<UUID, Integer> distanceMetersByOrders(UUID tenantId, Collection<UUID> orderIds);
}
