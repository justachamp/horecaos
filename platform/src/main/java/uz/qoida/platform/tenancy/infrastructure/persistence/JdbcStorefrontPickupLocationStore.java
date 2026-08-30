package uz.qoida.platform.tenancy.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.tenancy.api.GeoPoint;

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
     * The nearest catalogue-capable branches, ordered deterministically.
     *
     * <p>The database owns geodesic distance here. Latitude and longitude are
     * WGS 84 degrees, so planar arithmetic in the client would produce a
     * different order as a search moves away from the equator.
     */
    public List<PickupLocationCandidate> nearestTo(GeoPoint point, int limit) {
        return jdbc.sql("""
                SELECT l.tenant_id,
                       l.brand_id,
                       l.id AS location_id,
                       c.id AS channel_id,
                       b.display_name AS brand_name,
                       l.display_name AS location_name,
                       l.address_line,
                       l.district,
                       l.city,
                       ST_Distance(
                           ST_SetSRID(ST_MakePoint(l.longitude, l.latitude), 4326)::geography,
                           ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                       ) AS distance_meters
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
                  AND l.latitude IS NOT NULL
                  AND l.longitude IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM catalog.publications p
                      WHERE p.tenant_id = l.tenant_id
                        AND p.brand_id = l.brand_id
                        AND p.channel = 'STOREFRONT'
                        AND p.status = 'PUBLISHED'
                  )
                ORDER BY distance_meters, l.id
                LIMIT :limit
                """)
                .param("latitude", point.latitude())
                .param("longitude", point.longitude())
                .param("limit", limit)
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
                        row.getDouble("distance_meters")))
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
            double distanceMeters) { }
}
