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

    @Override
    public Optional<String> storefrontHostname(UUID tenantId) {
        Optional<String> own = verifiedHostnameOf(tenantId, "QR_TABLE");
        return own.isPresent() ? own : verifiedHostnameOf(tenantId, "WEB");
    }

    /**
     * The one verified hostname among the tenant's active channels of this type,
     * or empty for none or for several -- the same "exactly one answers" rule
     * {@link #qrTableChannelCode} applies, for the same reason: with two
     * candidates there is no telling which is the storefront the guest should
     * reach.
     */
    private Optional<String> verifiedHostnameOf(UUID tenantId, String systemType) {
        List<String> hostnames = jdbc.sql("""
                SELECT h.hostname
                FROM tenant.channel_hostnames h
                JOIN tenant.sales_channels c ON c.tenant_id = h.tenant_id AND c.id = h.channel_id
                WHERE h.tenant_id = :tenantId AND c.system_type = :systemType
                  AND c.status = 'ACTIVE' AND h.verified
                ORDER BY h.hostname
                LIMIT 2
                """)
                .param("tenantId", tenantId)
                .param("systemType", systemType)
                .query(String.class)
                .list();
        return hostnames.size() == 1 ? Optional.of(hostnames.getFirst()) : Optional.empty();
    }
}
