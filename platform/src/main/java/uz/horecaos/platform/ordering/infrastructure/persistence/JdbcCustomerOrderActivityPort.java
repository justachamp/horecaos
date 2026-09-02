package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerOrderActivityPort;

/**
 * {@link CustomerOrderActivityPort}'s implementation — see that interface's
 * own doc for why {@code ordering} implements a port declared in {@code
 * customers.api} rather than the customary "the data owner declares the
 * port" shape {@code OrderDirectory} otherwise uses.
 */
@Service
public class JdbcCustomerOrderActivityPort implements CustomerOrderActivityPort {

    private final JdbcOrderStore orders;

    public JdbcCustomerOrderActivityPort(JdbcOrderStore orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public long customersOrderedBetween(UUID tenantId, Instant from, Instant to) {
        return orders.countDistinctCustomersBetween(tenantId, from, to);
    }
}
