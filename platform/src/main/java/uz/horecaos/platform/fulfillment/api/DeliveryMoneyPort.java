package uz.horecaos.platform.fulfillment.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a delivery costs and what its courier is to collect, asked of {@code courier} by the deliveries list
 * (ADR 0164 slice two, ADR 0042).
 *
 * <p>Declared here, implemented by {@code courier}, for the reason {@link InternalFleetPort} is: {@code courier}
 * already depends on {@code fulfillment.api}, and a {@code fulfillment -> courier} edge on top of it would close a
 * module cycle. Money lives where it is interpreted -- the cost lines and the settlement's cash figure are not
 * fulfillment's to read -- so the list asks, in one batch for a page, and takes the answer as it comes.
 *
 * <p><strong>A cost is always stated at its basis and never summed across bases</strong> (ADR 0042): a row that
 * has an accrual and later an invoice shows the newest live line, with its basis named, not the two added.
 */
public interface DeliveryMoneyPort {

    /** The answers for the shipments of one page, keyed by shipment id. A shipment with nothing to say is absent. */
    Map<UUID, DeliveryMoney> forShipments(UUID tenantId, List<ShipmentRef> shipments);

    /**
     * @param prepaid        HorecaOS already holds the money, so nothing is collected at the door
     * @param orderTotalMinor the fallback when the order has no settlement, as the courier app uses it
     */
    record ShipmentRef(UUID shipmentId, UUID orderId, boolean prepaid, long orderTotalMinor) {}

    /**
     * @param cashToCollectMinor what the courier is to collect at the door: zero for a prepaid order
     * @param cost               the newest live cost line, or null when none has been recognised yet
     */
    record DeliveryMoney(long cashToCollectMinor, @Nullable Cost cost) {}

    /**
     * @param basis {@code ACCRUED}, {@code INVOICED} or {@code SETTLED}
     * @param path  {@code INTERNAL} (what the courier accrued) or {@code PARTNER} (what a partner charged)
     */
    record Cost(String basis, String path, long amountMinor, String currency) {}
}
