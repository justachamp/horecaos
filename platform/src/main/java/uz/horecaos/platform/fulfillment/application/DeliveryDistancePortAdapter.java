package uz.horecaos.platform.fulfillment.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.DeliveryDistancePort;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;

/** {@link DeliveryDistancePort} over {@link JdbcDeliveryPlanStore#distanceMetersByOrders} (gap map row 1.1). */
@Component
public class DeliveryDistancePortAdapter implements DeliveryDistancePort {

    private final JdbcDeliveryPlanStore plans;

    public DeliveryDistancePortAdapter(JdbcDeliveryPlanStore plans) {
        this.plans = plans;
    }

    @Override
    public Map<UUID, Integer> distanceMetersByOrders(UUID tenantId, Collection<UUID> orderIds) {
        return plans.distanceMetersByOrders(tenantId, orderIds);
    }
}
