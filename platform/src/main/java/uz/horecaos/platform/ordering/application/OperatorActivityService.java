package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OperatorActivity;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;

/**
 * {@link OperatorActivity} over the order attribution columns (ADR 0156, ADR 0139): the subjects
 * that created or accepted an order at a branch in a window. Nothing else about an order leaves
 * this class.
 */
@Service
public class OperatorActivityService implements OperatorActivity {

    private final JdbcOrderStore orders;

    public OperatorActivityService(JdbcOrderStore orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> operatorsAt(UUID tenantId, UUID locationId, Instant fromInclusive, Instant toExclusive) {
        return orders.operatorSubjectsAt(tenantId, locationId, fromInclusive, toExclusive);
    }
}
