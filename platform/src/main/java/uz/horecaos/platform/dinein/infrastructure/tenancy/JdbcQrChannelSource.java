package uz.horecaos.platform.dinein.infrastructure.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.dinein.application.port.QrChannelSource;

/**
 * Reads the tenant's {@code QR_TABLE} channel code straight off {@code
 * tenant.sales_channels} (ADR 0036).
 *
 * <p>Mirrors {@code JdbcSalesChannelStore.hallChannelId}'s own query shape and
 * its own rule -- exactly one active row of the system type answers; zero or
 * more than one is "no unambiguous channel" rather than a guess -- without
 * importing {@code tenancy}'s service layer, the same read-only boundary
 * {@code JdbcSessionOrderSource} already keeps toward {@code ordering}.
 */
@Component
public class JdbcQrChannelSource implements QrChannelSource {

    private final JdbcClient jdbc;

    public JdbcQrChannelSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> qrTableChannelCode(UUID tenantId) {
        List<String> codes =
                jdbc.sql("""
                SELECT code FROM tenant.sales_channels
                WHERE tenant_id = :tenantId AND system_type = 'QR_TABLE' AND status = 'ACTIVE'
                ORDER BY id
                LIMIT 2
                """).param("tenantId", tenantId).query(String.class).list();
        return codes.size() == 1 ? Optional.of(codes.getFirst()) : Optional.empty();
    }
}
