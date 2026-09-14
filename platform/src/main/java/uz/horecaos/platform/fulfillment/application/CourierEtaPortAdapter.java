package uz.horecaos.platform.fulfillment.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.CourierEtaPort;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;

/** {@link CourierEtaPort} over {@link JdbcDeliveryPlanStore#courierEtaByOrders} (gap map row 2.1a). */
@Component
public class CourierEtaPortAdapter implements CourierEtaPort {

    private final JdbcDeliveryPlanStore plans;

    public CourierEtaPortAdapter(JdbcDeliveryPlanStore plans) {
        this.plans = plans;
    }

    @Override
    public Map<UUID, Instant> etaByOrders(UUID tenantId, Collection<UUID> orderIds) {
        return plans.courierEtaByOrders(tenantId, orderIds);
    }
}
