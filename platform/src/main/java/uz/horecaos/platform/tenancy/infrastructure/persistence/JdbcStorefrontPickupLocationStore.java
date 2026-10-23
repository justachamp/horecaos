package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * The small, public projection a customer needs before choosing a branch.
 *
 * <p>This is intentionally a discovery query rather than a general location
 * listing. A location appears only when its tenant, brand and storefront
 * binding are active, it supports pickup, has a coordinate, and its brand has
 * a published storefront menu. Returning every location and asking the mobile
 * app to join those facts would expose dormant branches and repeatedly send a
 * customer to menus that cannot exist.
 */
@Repository
public class JdbcStorefrontPickupLocationStore {

    private final JdbcClient jdbc;

    public JdbcStorefrontPickupLocationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The catalogue-capable branches, ordered deterministically.
     *
     * <p>With a customer point the database owns geodesic distance: latitude and longitude are WGS 84 degrees, so planar
     * arithmetic in the client would produce a different order as a search moves away from the equator. Nearest first, then
     * the brand's own storefront order, then the id (ADR 0157 Release 4: the manual order is the tie-break after distance).
     *
     * <p>With no point (ADR 0183 D17) there is no distance to sort by, so the order is the one the brand set
     * ({@code storefront_sort_order}, unset last), then the branch's name, then its id. {@code distanceMeters} is then null:
     * the answer says it does not know, and does not invent a zero.
     */
    public List<PickupLocationCandidate> nearestTo(@Nullable GeoPoint point, int limit) {
        boolean located = point != null;
        String distance = located ? """
                  ST_Distance(
                      ST_SetSRID(ST_MakePoint(l.longitude, l.latitude), 4326)::geography,
                      ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                  )""" : "CAST(NULL AS double precision)";
        String order = located
                ? "ORDER BY distance_meters, l.storefront_sort_order NULLS LAST, l.id"
                : "ORDER BY l.storefront_sort_order NULLS LAST, l.display_name, l.id";
        var statement = jdbc.sql("""
                SELECT l.tenant_id,
                       l.brand_id,
                       l.id AS location_id,
                       c.id AS channel_id,
                       b.display_name AS brand_name,
                       l.display_name AS location_name,
                       l.address_line,
                       l.district,
                       l.city,
                       """ + distance + """
                        AS distance_meters
                FROM tenant.locations l
                JOIN tenant.brands b
                  ON b.tenant_id = l.tenant_id AND b.id = l.brand_id
                JOIN tenant.tenants t
                  ON t.id = l.tenant_id
                JOIN tenant.sales_channel_locations cl
                  ON cl.tenant_id = l.tenant_id AND cl.location_id = l.id
                 AND cl.status = 'ACTIVE'
                JOIN tenant.sales_channels c
                  ON c.tenant_id = cl.tenant_id AND c.id = cl.channel_id
                 AND c.code = 'STOREFRONT' AND c.status = 'ACTIVE'
                JOIN tenant.channel_fulfillment_modes fm
                  ON fm.tenant_id = c.tenant_id AND fm.channel_id = c.id
                 AND fm.fulfillment_mode = 'PICKUP' AND fm.enabled
                WHERE t.status = 'ACTIVE'
                  AND b.status = 'ACTIVE'
                  AND l.status = 'ACTIVE'
                  AND (l.latitude IS NOT NULL AND l.longitude IS NOT NULL OR NOT CAST(:located AS boolean))
                  AND EXISTS (
                      SELECT 1
                      FROM catalog.publications p
                      WHERE p.tenant_id = l.tenant_id
                        AND p.brand_id = l.brand_id
                        AND p.channel = 'STOREFRONT'
                        AND p.status = 'PUBLISHED'
                  )
                """ + order + """

                LIMIT :limit
                """)
                .param("located", located)
                .param("limit", limit);
        if (point != null) {
            statement = statement.param("latitude", point.latitude()).param("longitude", point.longitude());
        }
        return statement
                .query((row, number) -> new PickupLocationCandidate(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getObject("channel_id", UUID.class),
                        row.getString("brand_name"),
                        row.getString("location_name"),
                        row.getString("address_line"),
                        row.getString("district"),
                        row.getString("city"),
                        row.getObject("distance_meters") == null ? null : row.getDouble("distance_meters")))
                .list();
    }

    /** Internal row identity, including the channel needed by the resolver. */
    public record PickupLocationCandidate(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            String brandName,
            String locationName,
            String addressLine,
            String district,
            String city,
            @Nullable Double distanceMeters) {}

    // A named branch's public profile, by id rather than by distance
    // (2026-09-21 audit follow-up (d)), is served by
    // `JdbcStorefrontLocationProfileStore` — see
    // `StorefrontPickupLocationController`'s own note on why the
    // from-scratch duplicate of that read was removed here during batch 8
    // integration.
}
