package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code fulfillment.regions} (V0025, tightened by V0088), read and written.
 *
 * <p>Until this class existed the table had exactly one writer in the
 * repository — test SQL — so a production database had no region at all and
 * the bounding-box check {@code ServiceZoneService.activate} runs against a
 * region was inert on every zone anyone activated. V0025's own comment says
 * what the box is for: "an unconstrained geocoder asked for a Tashkent street
 * name will return a plausible street of the same name in another country".
 *
 * <p>Every read here answers the tenant's own regions <em>and</em> the
 * platform's, which is V0025's nullable {@code tenant_id}: "Null means a
 * platform region every tenant may reference. Tashkent is not one tenant's
 * fact." Every write is scoped to {@code tenant_id = :tenantId} and therefore
 * cannot touch a platform row — the {@code update} and {@code archive}
 * statements return 0 for one, which the service turns into a not-found rather
 * than a forbidden so the endpoint cannot be used to enumerate the platform's
 * rows.
 */
@Repository
public class JdbcRegionStore {

    private final JdbcClient jdbc;

    public JdbcRegionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, code, display_name_ru, display_name_uz, display_name_en,
                   centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon,
                   status, version
            """;

    /** The tenant's own regions and the platform's, newest-code-last so the list is stable. */
    public List<RegionRow> list(UUID tenantId) {
        return jdbc.sql(SELECT_COLUMNS + """
                        FROM fulfillment.regions
                        WHERE tenant_id = :tenantId OR tenant_id IS NULL
                        ORDER BY (tenant_id IS NULL) DESC, code
                        """)
                .param("tenantId", tenantId)
                .query(JdbcRegionStore::mapRegion)
                .list();
    }

    /** One region this tenant may see — its own or the platform's. */
    public Optional<RegionRow> find(UUID tenantId, UUID regionId) {
        return jdbc.sql(SELECT_COLUMNS + """
                        FROM fulfillment.regions
                        WHERE id = :regionId AND (tenant_id = :tenantId OR tenant_id IS NULL)
                        """)
                .param("regionId", regionId)
                .param("tenantId", tenantId)
                .query(JdbcRegionStore::mapRegion)
                .optional();
    }

    public void insert(UUID id, UUID tenantId, RegionGeography geography, Instant now) {
        jdbc.sql("""
                INSERT INTO fulfillment.regions (
                    id, tenant_id, code, display_name_ru, display_name_uz, display_name_en,
                    centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon,
                    status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :code, :nameRu, :nameUz, :nameEn,
                    :centreLat, :centreLon, :swLat, :swLon, :neLat, :neLon,
                    'ACTIVE', 1, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", geography.code())
                .param("nameRu", geography.displayNameRu())
                .param("nameUz", geography.displayNameUz())
                .param("nameEn", geography.displayNameEn())
                .param("centreLat", geography.centreLat())
                .param("centreLon", geography.centreLon())
                .param("swLat", geography.bboxSwLat())
                .param("swLon", geography.bboxSwLon())
                .param("neLat", geography.bboxNeLat())
                .param("neLon", geography.bboxNeLon())
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Rewrites the tenant's own region under its expected version, bumping
     * {@code version}.
     *
     * <p>{@code tenant_id = :tenantId} rather than {@code IS NOT DISTINCT FROM}
     * is deliberate: a platform region matches no row here and the update
     * returns 0. {@code version = :expectedVersion} is the optimistic-locking
     * predicate {@code platform/.claude/skills/http-api-conventions/SKILL.md}
     * requires and {@code JdbcLegalEntityStore.update}/{@code
     * JdbcSalesChannelStore} already carry for their own aggregates — without
     * it two operators editing the same region concurrently silently clobber
     * each other, the later write winning with no conflict surfaced to either.
     *
     * @return 1 when a row of this tenant's was rewritten at exactly {@code
     *         expectedVersion}, 0 otherwise (not found, not owned, not active,
     *         or the version has moved on — the caller distinguishes those)
     */
    public int update(UUID tenantId, UUID regionId, RegionGeography geography, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.regions
                SET code = :code,
                    display_name_ru = :nameRu,
                    display_name_uz = :nameUz,
                    display_name_en = :nameEn,
                    centre_lat = :centreLat,
                    centre_lon = :centreLon,
                    bbox_sw_lat = :swLat,
                    bbox_sw_lon = :swLon,
                    bbox_ne_lat = :neLat,
                    bbox_ne_lon = :neLon,
                    version = version + 1,
                    updated_at = :now
                WHERE id = :regionId AND tenant_id = :tenantId AND status = 'ACTIVE'
                      AND version = :expectedVersion
                """)
                .param("regionId", regionId)
                .param("tenantId", tenantId)
                .param("code", geography.code())
                .param("nameRu", geography.displayNameRu())
                .param("nameUz", geography.displayNameUz())
                .param("nameEn", geography.displayNameEn())
                .param("centreLat", geography.centreLat())
                .param("centreLon", geography.centreLon())
                .param("swLat", geography.bboxSwLat())
                .param("swLon", geography.bboxSwLon())
                .param("neLat", geography.bboxNeLat())
                .param("neLon", geography.bboxNeLon())
                .param("expectedVersion", expectedVersion)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Archives the tenant's own region. Never deletes one: {@code
     * service_zone_versions.region_id} points here and a months-old resolution's
     * evidence must not dangle — the same argument V0025 makes for the zone.
     *
     * @return 1 when an {@code ACTIVE} row of this tenant's was archived, 0 otherwise
     */
    public int archive(UUID tenantId, UUID regionId, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.regions
                SET status = 'ARCHIVED', version = version + 1, updated_at = :now
                WHERE id = :regionId AND tenant_id = :tenantId AND status = 'ACTIVE'
                """)
                .param("regionId", regionId)
                .param("tenantId", tenantId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** How many zone versions still name this region, archived or not. */
    public long zoneVersionsNaming(UUID tenantId, UUID regionId) {
        Long count = jdbc.sql("""
                SELECT count(*) FROM fulfillment.service_zone_versions
                WHERE tenant_id = :tenantId AND region_id = :regionId
                """)
                .param("tenantId", tenantId)
                .param("regionId", regionId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    private static RegionRow mapRegion(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        UUID owner = rs.getObject("tenant_id", UUID.class);
        return new RegionRow(
                rs.getObject("id", UUID.class),
                owner == null,
                rs.getString("code"),
                rs.getString("display_name_ru"),
                rs.getString("display_name_uz"),
                rs.getString("display_name_en"),
                rs.getDouble("centre_lat"),
                rs.getDouble("centre_lon"),
                rs.getDouble("bbox_sw_lat"),
                rs.getDouble("bbox_sw_lon"),
                rs.getDouble("bbox_ne_lat"),
                rs.getDouble("bbox_ne_lon"),
                rs.getString("status"),
                rs.getInt("version"));
    }

    /**
     * One region as the console reads it.
     *
     * @param platform whether the row is the platform's ({@code tenant_id IS
     *                 NULL}) rather than this tenant's. Rendered, because a
     *                 tenant may reference a platform region and may not edit
     *                 one, and a screen that does not show the difference offers
     *                 an edit button that always fails
     */
    public record RegionRow(
            UUID regionId,
            boolean platform,
            String code,
            String displayNameRu,
            String displayNameUz,
            String displayNameEn,
            double centreLat,
            double centreLon,
            double bboxSwLat,
            double bboxSwLon,
            double bboxNeLat,
            double bboxNeLon,
            String status,
            int version) {}

    /**
     * A region's geography as an operator types it.
     *
     * <p>Nothing is validated in this record. The database's own {@code
     * ck_region_coordinates}, {@code ck_region_bbox_oriented} and {@code
     * ck_region_centre_within_bbox} are the authority, and {@code RegionService}
     * reproduces them as readable refusals so an operator sees every problem at
     * once instead of a driver error.
     */
    public record RegionGeography(
            String code,
            String displayNameRu,
            String displayNameUz,
            String displayNameEn,
            double centreLat,
            double centreLon,
            double bboxSwLat,
            double bboxSwLon,
            double bboxNeLat,
            double bboxNeLon) {}
}
