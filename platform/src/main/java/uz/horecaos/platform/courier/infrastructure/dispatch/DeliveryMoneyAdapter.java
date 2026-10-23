package uz.horecaos.platform.courier.infrastructure.dispatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.CostLineRow;
import uz.horecaos.platform.fulfillment.api.DeliveryMoneyPort;
import uz.horecaos.platform.payments.api.CashDueLookupPort;

/**
 * Answers {@link DeliveryMoneyPort} from the cost lines and the settlement (ADR 0164, ADR 0042).
 *
 * <p>The cash figure is the one the courier app shows: the settlement's own cash leg, falling back to the order
 * total when the order has no settlement, and zero for a prepaid order. It is read as an answer
 * ({@link CashDueLookupPort#cashDueMinorIfSettled}), never caught from a throw, because this runs inside the
 * list's read transaction.
 */
@Component
public class DeliveryMoneyAdapter implements DeliveryMoneyPort {

    private final JdbcDeliveryCostStore costs;
    private final CashDueLookupPort cashDue;

    public DeliveryMoneyAdapter(JdbcDeliveryCostStore costs, CashDueLookupPort cashDue) {
        this.costs = costs;
        this.cashDue = cashDue;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, DeliveryMoney> forShipments(UUID tenantId, List<ShipmentRef> shipments) {
        if (shipments.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CostLineRow> newest = costs.newestLiveLinePerShipment(
                tenantId, shipments.stream().map(ShipmentRef::shipmentId).toList());
        Map<UUID, DeliveryMoney> answer = new HashMap<>();
        for (ShipmentRef ref : shipments) {
            long cash = ref.prepaid()
                    ? 0L
                    : Math.max(
                            0L,
                            cashDue.cashDueMinorIfSettled(tenantId, ref.orderId())
                                    .orElse(ref.orderTotalMinor()));
            CostLineRow line = newest.get(ref.shipmentId());
            answer.put(
                    ref.shipmentId(),
                    new DeliveryMoney(
                            cash,
                            line == null
                                    ? null
                                    : new Cost(
                                            line.costBasis().name(),
                                            line.costPath().name(),
                                            line.amountMinor(),
                                            line.currency())));
        }
        return answer;
    }
}
