package uz.horecaos.platform.ordering.infrastructure.pos;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.application.PosExportStatus;

/**
 * Answers {@link PosExportStatus} from {@code integration.pos_order_exports}.
 *
 * <p>Reads the POS module's table rather than calling into it, which is the same
 * shape {@code JdbcOrderCatalogSnapshot} uses for the catalogue: the SQL crosses
 * the boundary, the Java does not, so Spring Modulith's dependency rules hold and
 * ordering keeps owning the question it asks.
 */
@Component
public class JdbcPosExportStatus implements PosExportStatus {

    private final JdbcClient jdbc;

    public JdbcPosExportStatus(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> stateOf(UUID tenantId, UUID orderId) {
        // Newest first. An order re-exported after an abandonment has more than
        // one row, and it is the current attempt that decides whether the kitchen
        // is holding this ticket.
        return jdbc.sql("""
                SELECT state FROM integration.pos_order_exports
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                 ORDER BY requested_at DESC
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(String.class)
                .optional();
    }
}
