package uz.qoida.platform.fulfillment.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.fulfillment.domain.BranchOrigin;
import uz.qoida.platform.fulfillment.domain.VersionStatus;
import uz.qoida.platform.fulfillment.domain.zone.ZoneCandidate;
import uz.qoida.platform.fulfillment.domain.zone.ZoneRole;
import uz.qoida.platform.tenancy.api.GeoPoint;

/**
 * Zones, their versions, and the containment question (ADR 0037).
 *
 * <p>Every read carries the tenant predicate, and the containment read carries a
 * location predicate as well. That second one is not an optimisation: ADR 0037
 * refuses to re-home an address to a location that does cover it, and a query
 * that asked "which of this brand's zones contain the point" would be the first
 * step towards doing exactly that by accident.
 *
 * <p>Longitude comes before latitude in every PostGIS constructor here. That
 * ordering is the single most common way a working zone system starts returning
 * polygons in the wrong hemisphere, and it is silent — the geometry is valid, it
 * is just elsewhere — so it is spelled out at each call site rather than trusted
 * to memory.
 */
@Repository
public class JdbcServiceZoneStore {

    private final JdbcClient jdbc;

    public JdbcServiceZoneStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------- reads

    /**
     * Where the branch is, and its timezone, which every time rule is evaluated
     * against.
     *
     * <p>Empty means no such location for this tenant. A located branch comes back
     * as a {@link BranchOrigin}, which is the type that refuses an absent
     * coordinate and the null island — so an unlocated branch throws here rather
     * than travelling on as a point that happens to be in the Gulf of Guinea.
     */
    public Optional<Branch> findBranch(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT id, latitude, longitude, coordinate_source, timezone
                FROM tenant.locations
                WHERE tenant_id = :tenantId AND id = :locationId
                """)
                .param("tenantId", tenantId).param("locationId", locationId)
                .query((row, number) -> new Branch(
                        row.getObject("id", UUID.class),
                        // getDouble answers 0 for SQL NULL, and 0 is a real
                        // coordinate. Reading these through getObject is what
                        // keeps "no coordinate" from arriving as (0, 0).
                        row.getObject("latitude", Double.class),
                        row.getObject("longitude", Double.class),
                        row.getString("coordinate_source"),
                        ZoneId.of(row.getString("timezone"))))
                .optional();
    }

    /**
     * Step 2 and step 3's input: every live {@code DELIVERY} zone bound to this
     * location whose polygon covers the point.
     *
     * <p>Ordered by the ADR's total order — priority descending, then smaller area,
     * then zone id — so the winner is the first row on any machine, on any planner
     * version, and after a {@code VACUUM FULL}. The same order is applied again in
     * Java by {@link ZoneCandidate#RANKING}; the duplication is deliberate, because
     * a resolver that depends on the database having sorted correctly cannot be
     * unit-tested and a database that returns rows in an order nobody asserted is
     * how a fee starts differing between two identical quotes.
     *
     * <p>{@code ST_Covers} rather than {@code ST_Contains}: an address exactly on a
     * shared border belongs to the zone, and a boundary that excludes its own edge
     * leaves a hairline of unserviceable addresses nobody can find.
     */
    public List<ZoneCandidate> containingDeliveryZones(UUID tenantId, UUID locationId,
            GeoPoint point, Instant at) {
        return jdbc.sql("""
                SELECT v.zone_id, v.version, v.priority, v.area_sq_meters, v.currency,
                       v.delivery_tariff_id, v.free_delivery_from_minor, v.min_basket_minor
                FROM fulfillment.service_zone_versions v
                JOIN fulfillment.zone_location_bindings b
                  ON b.tenant_id = v.tenant_id AND b.zone_id = v.zone_id
                WHERE v.tenant_id = :tenantId
                  AND v.status = 'ACTIVE'
                  AND v.zone_role = 'DELIVERY'
                  AND b.location_id = :locationId
                  AND b.valid_from <= :at
                  AND (b.valid_until IS NULL OR b.valid_until > :at)
                  AND ST_Covers(v.area,
                        ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)
                ORDER BY v.priority DESC, v.area_sq_meters, v.zone_id
                """)
                .param("tenantId", tenantId).param("locationId", locationId)
                .param("longitude", point.longitude()).param("latitude", point.latitude())
                .param("at", timestamp(at))
                .query((row, number) -> new ZoneCandidate(
                        row.getObject("zone_id", UUID.class),
                        row.getInt("version"),
                        row.getInt("priority"),
                        row.getDouble("area_sq_meters"),
                        row.getString("currency"),
                        row.getObject("delivery_tariff_id", UUID.class),
                        row.getObject("free_delivery_from_minor", Long.class),
                        row.getObject("min_basket_minor", Long.class)))
                .list();
    }

    /**
     * The catchment guard, which is Delever's <em>не принимать заказы из других зон
     * доставки</em>.
     *
     * <p>Returns how many catchment zones are bound to this branch and how many of
     * them cover the point, in one round trip, because the two numbers only mean
     * anything together. No binding at all means no guard: a brand that has not
     * drawn catchments has not asked for one, and inventing a guard from an empty
     * table would refuse every address at every branch on the day the feature
     * ships.
     */
    public CatchmentCheck catchmentCheck(UUID tenantId, UUID locationId, GeoPoint point, Instant at) {
        return jdbc.sql("""
                SELECT count(*) AS bound,
                       count(*) FILTER (
                           WHERE ST_Covers(v.area,
                               ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)
                       ) AS covering
                FROM fulfillment.service_zone_versions v
                JOIN fulfillment.zone_location_bindings b
                  ON b.tenant_id = v.tenant_id AND b.zone_id = v.zone_id
                WHERE v.tenant_id = :tenantId
                  AND v.status = 'ACTIVE'
                  AND v.zone_role = 'CATCHMENT'
                  AND b.location_id = :locationId
                  AND b.valid_from <= :at
                  AND (b.valid_until IS NULL OR b.valid_until > :at)
                """)
                .param("tenantId", tenantId).param("locationId", locationId)
                .param("longitude", point.longitude()).param("latitude", point.latitude())
                .param("at", timestamp(at))
                .query((row, number) -> new CatchmentCheck(
                        row.getLong("bound"), row.getLong("covering")))
                .single();
    }

    /** What activation has to be satisfied about, read from the stored geometry itself. */
    public Optional<GeometryFacts> geometryFacts(UUID tenantId, UUID zoneId, int version) {
        return jdbc.sql("""
                SELECT ST_IsValid(v.area::geometry) AS valid_rings,
                       ST_IsValidReason(v.area::geometry) AS invalid_reason,
                       v.area_sq_meters,
                       r.id IS NOT NULL AS has_region,
                       coalesce(
                           r.id IS NULL
                           OR ST_Covers(
                               ST_MakeEnvelope(r.bbox_sw_lon, r.bbox_sw_lat,
                                               r.bbox_ne_lon, r.bbox_ne_lat, 4326),
                               v.area::geometry),
                           true) AS within_region
                FROM fulfillment.service_zone_versions v
                LEFT JOIN fulfillment.regions r ON r.id = v.region_id
                WHERE v.tenant_id = :tenantId AND v.zone_id = :zoneId AND v.version = :version
                """)
                .param("tenantId", tenantId).param("zoneId", zoneId).param("version", version)
                .query((row, number) -> new GeometryFacts(
                        row.getBoolean("valid_rings"),
                        row.getString("invalid_reason"),
                        row.getDouble("area_sq_meters"),
                        row.getBoolean("has_region"),
                        row.getBoolean("within_region")))
                .optional();
    }

    /**
     * Whether this tenant may name that region, and which of the two it is.
     *
     * <p>{@code fulfillment.regions.tenant_id} is nullable, and V0025's column
     * comment says why: "Null means a platform region every tenant may reference.
     * Tashkent is not one tenant's fact." A tenant may also define its own, so a
     * region id on its own answers nothing about ownership and the query has to
     * constrain on the tenant it was authorised against — {@code tenant_id IS
     * NULL} for the platform's, {@code tenant_id = :tenantId} for this tenant's,
     * and no third branch.
     *
     * <p>Empty covers both "no such region" and "another tenant's", deliberately
     * undistinguished: an authoring endpoint that told them apart would answer
     * "is this region id real anywhere on Qoida" for any uuid a caller submits.
     *
     * @return true if the region is a platform region, false if it is this
     *         tenant's own, empty if the tenant may not name it
     */
    public Optional<Boolean> regionIsPlatform(UUID tenantId, UUID regionId) {
        if (regionId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT tenant_id IS NULL AS platform_owned
                  FROM fulfillment.regions
                 WHERE id = :regionId
                   AND (tenant_id IS NULL OR tenant_id = :tenantId)
                   AND status = 'ACTIVE'
                """)
                .param("regionId", regionId).param("tenantId", tenantId)
                .query(Boolean.class)
                .optional();
    }

    public Optional<ZoneRole> zoneRole(UUID tenantId, UUID brandId, UUID zoneId) {
        return jdbc.sql("""
                SELECT zone_role FROM fulfillment.service_zones
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :zoneId
                """)
                .param("tenantId", tenantId).param("brandId", brandId).param("zoneId", zoneId)
                .query(String.class)
                .optional()
                .map(ZoneRole::valueOf);
    }

    public int nextVersion(UUID tenantId, UUID zoneId) {
        return jdbc.sql("""
                SELECT coalesce(max(version), 0) + 1 FROM fulfillment.service_zone_versions
                WHERE tenant_id = :tenantId AND zone_id = :zoneId
                """)
                .param("tenantId", tenantId).param("zoneId", zoneId)
                .query(Integer.class).single();
    }

    public Optional<VersionStatus> versionStatus(UUID tenantId, UUID zoneId, int version) {
        return jdbc.sql("""
                SELECT status FROM fulfillment.service_zone_versions
                WHERE tenant_id = :tenantId AND zone_id = :zoneId AND version = :version
                """)
                .param("tenantId", tenantId).param("zoneId", zoneId).param("version", version)
                .query(String.class)
                .optional()
                .map(VersionStatus::valueOf);
    }

    // ------------------------------------------------------------------ writes

    public void insertZone(UUID id, UUID tenantId, UUID brandId, ZoneRole role, String code,
            String nameRu, String nameUz, String nameEn, Instant now) {
        jdbc.sql("""
                INSERT INTO fulfillment.service_zones (
                    id, tenant_id, brand_id, zone_role, code,
                    display_name_ru, display_name_uz, display_name_en, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :role, :code,
                    :nameRu, :nameUz, :nameEn, :now, :now)
                """)
                .param("id", id).param("tenantId", tenantId).param("brandId", brandId)
                .param("role", role.name()).param("code", code)
                .param("nameRu", nameRu).param("nameUz", nameUz).param("nameEn", nameEn)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * A circle, buffered into a polygon by PostGIS at authoring time.
     *
     * <p>The buffer is geodesic, computed on the spheroid, which is the reason it
     * happens here and not in Java: a hand-rolled circle in degrees is an ellipse
     * on the ground, wrong by the cosine of the latitude, and at Tashkent's
     * latitude that is a fifth of the radius in the east–west direction. The
     * centre and radius are kept in {@code authoring_shape} so the editor still
     * round-trips a circle.
     *
     * <p>{@code area_sq_meters} is computed by the same statement rather than
     * supplied, so the ranking key can never disagree with the geometry it ranks.
     */
    public void insertCircleVersion(DraftVersion draft, GeoPoint centre, int radiusMeters,
            String authoringShapeJson) {
        Map<String, Object> params = commonDraftParams(draft);
        params.put("longitude", centre.longitude());
        params.put("latitude", centre.latitude());
        params.put("radius", radiusMeters);
        params.put("authoringShape", authoringShapeJson);

        jdbc.sql("""
                WITH shape AS (
                    SELECT ST_Multi(ST_Buffer(
                        ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                        :radius)::geometry)::geography AS area
                )
                INSERT INTO fulfillment.service_zone_versions (
                    id, tenant_id, zone_id, zone_role, version, status, area, authoring_shape,
                    origin_location_id, region_id, region_is_platform, priority,
                    area_sq_meters, currency,
                    delivery_tariff_id, free_delivery_from_minor, min_basket_minor,
                    created_by, created_at)
                SELECT :id, :tenantId, :zoneId, :role, :version, 'DRAFT', shape.area,
                    CAST(:authoringShape AS jsonb), :originLocationId, :regionId,
                    :regionIsPlatform, :priority,
                    ST_Area(shape.area), :currency, :tariffId, :freeFrom, :minBasket,
                    :createdBy, :now
                FROM shape
                """)
                .params(params)
                .update();
    }

    /** A hand-drawn polygon, supplied as GeoJSON exactly as the map editor emits it. */
    public void insertPolygonVersion(DraftVersion draft, String geoJson, String authoringShapeJson) {
        Map<String, Object> params = commonDraftParams(draft);
        params.put("geoJson", geoJson);
        params.put("authoringShape", authoringShapeJson);

        jdbc.sql("""
                WITH shape AS (
                    SELECT ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(:geoJson), 4326))::geography AS area
                )
                INSERT INTO fulfillment.service_zone_versions (
                    id, tenant_id, zone_id, zone_role, version, status, area, authoring_shape,
                    origin_location_id, region_id, region_is_platform, priority,
                    area_sq_meters, currency,
                    delivery_tariff_id, free_delivery_from_minor, min_basket_minor,
                    created_by, created_at)
                SELECT :id, :tenantId, :zoneId, :role, :version, 'DRAFT', shape.area,
                    CAST(:authoringShape AS jsonb), :originLocationId, :regionId,
                    :regionIsPlatform, :priority,
                    ST_Area(shape.area), :currency, :tariffId, :freeFrom, :minBasket,
                    :createdBy, :now
                FROM shape
                """)
                .params(params)
                .update();
    }

    /**
     * Activates one version and retires whichever was live.
     *
     * <p>Retiring first, in the same transaction, is what makes the partial unique
     * index a guard rather than an obstacle: two operators activating two versions
     * of one zone at the same moment cannot both succeed, and the loser sees a
     * constraint violation instead of both polygons being live at once.
     */
    public int activateVersion(UUID tenantId, UUID zoneId, int version, UUID actorId, Instant now) {
        jdbc.sql("""
                UPDATE fulfillment.service_zone_versions
                SET status = 'RETIRED', retired_at = :now
                WHERE tenant_id = :tenantId AND zone_id = :zoneId AND status = 'ACTIVE'
                  AND version <> :version
                """)
                .param("tenantId", tenantId).param("zoneId", zoneId)
                .param("version", version).param("now", timestamp(now))
                .update();

        return jdbc.sql("""
                UPDATE fulfillment.service_zone_versions
                SET status = 'ACTIVE', activated_by = :actorId, activated_at = :now
                WHERE tenant_id = :tenantId AND zone_id = :zoneId AND version = :version
                  AND status = 'DRAFT'
                """)
                .param("tenantId", tenantId).param("zoneId", zoneId).param("version", version)
                .param("actorId", actorId).param("now", timestamp(now))
                .update();
    }

    public void bindLocation(UUID tenantId, UUID brandId, UUID zoneId, UUID locationId, Instant from) {
        jdbc.sql("""
                INSERT INTO fulfillment.zone_location_bindings (
                    tenant_id, brand_id, zone_id, location_id, valid_from)
                VALUES (:tenantId, :brandId, :zoneId, :locationId, :from)
                ON CONFLICT (zone_id, location_id, valid_from) DO NOTHING
                """)
                .param("tenantId", tenantId).param("brandId", brandId).param("zoneId", zoneId)
                .param("locationId", locationId).param("from", timestamp(from))
                .update();
    }

    // --------------------------------------------------------------- row types

    /**
     * A location as this module reads it.
     *
     * <p>The coordinate is left nullable here and refused by {@link #origin()},
     * rather than being refused at the query, so the caller can tell "no such
     * branch" from "a branch with no pin". Those two send an operator to two
     * different screens.
     */
    public record Branch(UUID locationId, Double latitude, Double longitude,
            String coordinateSource, ZoneId timezone) {

        public BranchOrigin origin() {
            return BranchOrigin.of(locationId, latitude, longitude, coordinateSource);
        }
    }

    /** @param bound zero means the brand has drawn no catchment, so there is no guard */
    public record CatchmentCheck(long bound, long covering) {

        public boolean guardApplies() {
            return bound > 0;
        }

        public boolean covered() {
            return covering > 0;
        }
    }

    public record GeometryFacts(boolean validRings, String invalidReason, double areaSquareMeters,
            boolean hasRegion, boolean withinRegion) { }

    /**
     * The fields both draft shapes share, so neither insert can quietly omit one.
     *
     * @param regionIsPlatform whether the named region is a platform region every
     *                         tenant may reference, rather than one this tenant
     *                         defined. Null exactly when no region is named.
     *                         {@code fulfillment.regions.tenant_id} is nullable —
     *                         V0025: "Tashkent is not one tenant's fact" — so a
     *                         region id alone does not say whose region it is, and
     *                         V0088 keys {@code fk_zone_version_region} on this
     *                         declaration
     */
    public record DraftVersion(
            UUID id, UUID tenantId, UUID zoneId, ZoneRole role, int version,
            UUID originLocationId, UUID regionId, Boolean regionIsPlatform, int priority,
            String currency, UUID deliveryTariffId, Long freeDeliveryFromMinor,
            Long minBasketMinor, UUID createdBy, Instant createdAt) {

        public DraftVersion {
            if ((regionId == null) != (regionIsPlatform == null)) {
                throw new IllegalArgumentException(
                        "A zone version either names a region and says whose it is, or names "
                                + "none. Leaving the ownership undeclared is what takes "
                                + "fk_zone_version_region out of the check entirely.");
            }
        }
    }

    /**
     * A {@code HashMap} and not {@code Map.of}, because half these values are
     * legitimately null — a hand-drawn zone has no origin location, a catchment
     * carries no tariff — and {@code Map.of} rejects a null value outright.
     */
    private static Map<String, Object> commonDraftParams(DraftVersion draft) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", draft.id());
        params.put("tenantId", draft.tenantId());
        params.put("zoneId", draft.zoneId());
        params.put("role", draft.role().name());
        params.put("version", draft.version());
        params.put("originLocationId", draft.originLocationId());
        params.put("regionId", draft.regionId());
        params.put("regionIsPlatform", draft.regionIsPlatform());
        params.put("priority", draft.priority());
        params.put("currency", draft.currency());
        params.put("tariffId", draft.deliveryTariffId());
        params.put("freeFrom", draft.freeDeliveryFromMinor());
        params.put("minBasket", draft.minBasketMinor());
        params.put("createdBy", draft.createdBy());
        params.put("now", timestamp(draft.createdAt()));
        return params;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
