package uz.horecaos.platform.dinein.infrastructure.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.LeadConversionTargets;

/**
 * Whether a reservation is the one a lead became (ADR 0111 §4): this tenant's, and this brand's.
 *
 * <p>Declared in {@code customers.api} and answered here, the direction {@code
 * CustomerOrderActivityPort} established: {@code dinein} already imports {@code customers.api}. An
 * order is {@code ordering}'s to answer.
 */
@Component
public class ReservationLeadConversionTargets implements LeadConversionTargets {

    private final JdbcClient jdbc;

    public ReservationLeadConversionTargets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean orderExists(UUID tenantId, UUID brandId, UUID orderId) {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean reservationExists(UUID tenantId, UUID brandId, UUID reservationId) {
        return jdbc.sql("""
                SELECT count(*) FROM dinein.reservations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :reservationId
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("reservationId", reservationId)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
