package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.LeadConversionTargets;

/**
 * Whether an order is the one a lead became (ADR 0111 §4): this tenant's, and this brand's.
 *
 * <p>The question is declared in {@code customers.api} and answered here for the reason {@code
 * CustomerOrderActivityPort} gives: {@code ordering} already imports {@code customers.api}. An order
 * is only ever a conversion target for a lead of its own brand; a reservation is {@code dinein}'s.
 */
@Component
public class OrderLeadConversionTargets implements LeadConversionTargets {

    private final JdbcClient jdbc;

    public OrderLeadConversionTargets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean orderExists(UUID tenantId, UUID brandId, UUID orderId) {
        return jdbc.sql("""
                SELECT count(*) FROM ordering.orders
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :orderId
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("orderId", orderId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public boolean reservationExists(UUID tenantId, UUID brandId, UUID reservationId) {
        return false;
    }
}
