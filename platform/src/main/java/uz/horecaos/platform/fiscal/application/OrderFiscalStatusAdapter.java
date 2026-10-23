package uz.horecaos.platform.fiscal.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.ordering.api.OrderFiscalStatusPort;

/** {@link OrderFiscalStatusPort} over {@link JdbcFiscalLifecycleStore#statusByOrders} (gap map row 1.1). */
@Component
public class OrderFiscalStatusAdapter implements OrderFiscalStatusPort {

    private final JdbcFiscalLifecycleStore documents;

    public OrderFiscalStatusAdapter(JdbcFiscalLifecycleStore documents) {
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> fiscalStatusByOrders(UUID tenantId, Collection<UUID> orderIds) {
        return documents.statusByOrders(tenantId, orderIds);
    }
}
