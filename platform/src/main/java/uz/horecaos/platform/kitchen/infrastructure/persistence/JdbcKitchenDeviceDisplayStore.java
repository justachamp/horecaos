package uz.horecaos.platform.kitchen.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code kitchen.device_displays} (ADR 0151): the station a wall display shows, held by the
 * kitchen and not by the page, and when the wall last read.
 *
 * <p>The wall's own read stamps {@code last_read_at} <em>without</em> moving {@code version}: the
 * version is a manager's concurrency check on the station they are editing, and a screen polling every
 * ten seconds must not make every edit stale.
 */
@Repository
public class JdbcKitchenDeviceDisplayStore {

    private static final String SELECT = """
            SELECT tenant_id, location_id, device_id, station_id, last_read_at, version
              FROM kitchen.device_displays
            """;

    private final JdbcClient jdbc;

    public JdbcKitchenDeviceDisplayStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per wall; a second call for the same device is a no-op. */
    public void insert(UUID tenantId, UUID locationId, UUID deviceId, Instant now) {
        jdbc.sql("""
                INSERT INTO kitchen.device_displays (tenant_id, location_id, device_id, created_at, updated_at)
                VALUES (:tenantId, :locationId, :deviceId, :now, :now)
                ON CONFLICT (tenant_id, device_id) DO NOTHING
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("deviceId", deviceId)
                .param("now", utc(now))
                .update();
    }

    public Optional<DisplayRow> find(UUID tenantId, UUID deviceId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND device_id = :deviceId")
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .query(JdbcKitchenDeviceDisplayStore::map)
                .optional();
    }

    /** Every display configured at a branch, by device. */
    public Map<UUID, DisplayRow> forLocation(UUID tenantId, UUID locationId) {
        Map<UUID, DisplayRow> byDevice = new HashMap<>();
        jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND location_id = :locationId")
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcKitchenDeviceDisplayStore::map)
                .list()
                .forEach(row -> byDevice.put(row.deviceId(), row));
        return byDevice;
    }

    /**
     * Points a wall at a station, or at the whole branch when {@code stationId} is null, if the row is
     * still at {@code expectedVersion}.
     *
     * @return the new version, or empty when the row moved on (or does not exist)
     */
    public Optional<Integer> setStation(
            UUID tenantId, UUID deviceId, @Nullable UUID stationId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE kitchen.device_displays
                   SET station_id = :stationId, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND device_id = :deviceId AND version = :expectedVersion
                RETURNING version
                """)
                .param("stationId", stationId)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .param("expectedVersion", expectedVersion)
                .query(Integer.class)
                .optional();
    }

    /**
     * Stamps the wall's read, at most once per {@code minimumGapSeconds}, and never moves the version.
     *
     * @return whether this call wrote (false when the last stamp is recent enough)
     */
    public boolean touchRead(UUID tenantId, UUID deviceId, Instant now, int minimumGapSeconds) {
        return jdbc.sql("""
                UPDATE kitchen.device_displays
                   SET last_read_at = :now
                 WHERE tenant_id = :tenantId AND device_id = :deviceId
                   AND (last_read_at IS NULL OR last_read_at <= :now - make_interval(secs => :gap))
                """)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("deviceId", deviceId)
                        .param("gap", minimumGapSeconds)
                        .update()
                > 0;
    }

    private static DisplayRow map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        OffsetDateTime lastRead = rs.getObject("last_read_at", OffsetDateTime.class);
        return new DisplayRow(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getObject("station_id", UUID.class),
                lastRead == null ? null : lastRead.toInstant(),
                rs.getInt("version"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * @param stationId  the station the wall shows, null for the whole branch
     * @param lastReadAt when the wall last read its projection, null before the first read
     */
    public record DisplayRow(
            UUID tenantId,
            UUID locationId,
            UUID deviceId,
            @Nullable UUID stationId,
            @Nullable Instant lastReadAt,
            int version) {}
}
