package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL for {@code tenant.kiosk_devices} (ADR 0162) and the few channel and branch facts a kiosk's
 * screens read beside it.
 *
 * <p>Every statement names the tenant. The composite foreign keys of V0526 are what stop a row naming a channel
 * of another type, another tenant, a location the channel has no binding at, or a location other than its
 * principal's; this class does not re-implement them, it lets the database refuse and the service translate.
 */
@Repository
public class JdbcKioskDeviceStore {

    private final JdbcClient jdbc;

    public JdbcKioskDeviceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A kiosk's binding with the channel facts every screen shows beside it. */
    public record BindingRow(
            UUID deviceId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            String channelCode,
            String channelName,
            String channelStatus,
            boolean bindingActive,
            int version) {}

    /** A KIOSK channel that is active and sells at one location: what a manager may pair a kiosk to. */
    public record ChannelChoice(UUID channelId, String code, String displayName) {}

    public record BranchSummary(String name, String timezone) {}

    private static final String BINDING_SELECT = """
            SELECT k.device_id, k.tenant_id, k.brand_id, k.location_id, k.channel_id, k.version,
                   c.code AS channel_code, c.display_name AS channel_name, c.status AS channel_status,
                   (scl.status = 'ACTIVE') AS binding_active
              FROM tenant.kiosk_devices k
              JOIN tenant.sales_channels c
                ON c.tenant_id = k.tenant_id AND c.id = k.channel_id
              JOIN tenant.sales_channel_locations scl
                ON scl.tenant_id = k.tenant_id AND scl.channel_id = k.channel_id AND scl.location_id = k.location_id
            """;

    public void insert(UUID tenantId, UUID brandId, UUID locationId, UUID deviceId, UUID channelId, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.kiosk_devices
                    (tenant_id, device_id, brand_id, location_id, channel_id, channel_type, created_at, updated_at)
                VALUES (:tenantId, :deviceId, :brandId, :locationId, :channelId, 'KIOSK', :now, :now)
                """)
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .param("now", at(now))
                .update();
    }

    public Optional<BindingRow> find(UUID tenantId, UUID deviceId) {
        return jdbc.sql(BINDING_SELECT + " WHERE k.tenant_id = :tenantId AND k.device_id = :deviceId")
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .query(JdbcKioskDeviceStore::toBinding)
                .optional();
    }

    public List<BindingRow> forLocation(UUID tenantId, UUID locationId) {
        return jdbc.sql(BINDING_SELECT + " WHERE k.tenant_id = :tenantId AND k.location_id = :locationId")
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcKioskDeviceStore::toBinding)
                .list();
    }

    /**
     * Moves a kiosk to another channel, against the version the manager read. The composite keys re-check, at the
     * database, that the new channel is a KIOSK channel of this tenant with a binding row at the kiosk's location.
     */
    public boolean rebind(UUID tenantId, UUID deviceId, UUID channelId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.kiosk_devices
                           SET channel_id = :channelId, version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND device_id = :deviceId AND version = :expected
                        """)
                        .param("channelId", channelId)
                        .param("now", at(now))
                        .param("tenantId", tenantId)
                        .param("deviceId", deviceId)
                        .param("expected", expectedVersion)
                        .update()
                == 1;
    }

    /** Touches the kiosk's version when only its principal's name changed, so a stale form is still refused. */
    public boolean touch(UUID tenantId, UUID deviceId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.kiosk_devices
                           SET version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND device_id = :deviceId AND version = :expected
                        """)
                        .param("now", at(now))
                        .param("tenantId", tenantId)
                        .param("deviceId", deviceId)
                        .param("expected", expectedVersion)
                        .update()
                == 1;
    }

    /** Whether the channel has an {@code ACTIVE} binding row at the location. */
    public boolean channelSellsAt(UUID tenantId, UUID channelId, UUID locationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM tenant.sales_channel_locations
                         WHERE tenant_id = :tenantId AND channel_id = :channelId AND location_id = :locationId
                           AND status = 'ACTIVE'
                        """)
                        .param("tenantId", tenantId)
                        .param("channelId", channelId)
                        .param("locationId", locationId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** The KIOSK channels that are {@code ACTIVE} and sell at this location, by name. */
    public List<ChannelChoice> kioskChannelsAt(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT c.id, c.code, c.display_name
                  FROM tenant.sales_channels c
                  JOIN tenant.sales_channel_locations scl
                    ON scl.tenant_id = c.tenant_id AND scl.channel_id = c.id
                 WHERE c.tenant_id = :tenantId AND c.system_type = 'KIOSK' AND c.status = 'ACTIVE'
                   AND scl.location_id = :locationId AND scl.status = 'ACTIVE'
                 ORDER BY c.display_name, c.code
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((rs, n) -> new ChannelChoice(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("display_name")))
                .list();
    }

    public Optional<BranchSummary> branch(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                SELECT display_name, timezone FROM tenant.locations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query((rs, n) -> new BranchSummary(rs.getString("display_name"), rs.getString("timezone")))
                .optional();
    }

    private static BindingRow toBinding(ResultSet rs, int rowNumber) throws SQLException {
        return new BindingRow(
                rs.getObject("device_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("channel_id", UUID.class),
                rs.getString("channel_code"),
                rs.getString("channel_name"),
                rs.getString("channel_status"),
                rs.getBoolean("binding_active"),
                rs.getInt("version"));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
