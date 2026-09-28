package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort.DeliveryBranchMatch;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort.PickupBranchCandidate;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * The cross-branch half of ADR 0037's containment question (gap map row
 * {@code 1.3}), read for {@link
 * uz.horecaos.platform.fulfillment.application.BranchResolutionService}.
 *
 * <p>{@link #deliveryCandidates} is {@code JdbcServiceZoneStore
 * #containingDeliveryZones}'s own query with its {@code b.location_id
 * = :locationId} predicate widened to {@code l.brand_id = :brandId} — see that
 * method's own doc for why the narrower query exists at all and stays the one
 * {@link uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver}
 * runs. {@code DISTINCT ON} picks each location's own winning zone with the
 * identical {@code ORDER BY} that query already uses, so a branch that has
 * three overlapping zones over one address reports the zone that would
 * actually price it, not an arbitrary one of the three.
 */
@Repository
public class JdbcBranchResolutionStore {

    private final JdbcClient jdbc;

    public JdbcBranchResolutionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** See {@link uz.horecaos.platform.fulfillment.api.BranchResolutionPort#deliveryCandidates}. */
    public List<DeliveryBranchMatch> deliveryCandidates(UUID tenantId, UUID brandId, GeoPoint point, Instant at) {
        return jdbc.sql("""
                SELECT DISTINCT ON (b.location_id)
                       b.location_id, l.display_name,
                       v.zone_id, v.version, v.priority, v.area_sq_meters
                  FROM fulfillment.service_zone_versions v
                  JOIN fulfillment.zone_location_bindings b
                    ON b.tenant_id = v.tenant_id AND b.zone_id = v.zone_id
                  JOIN tenant.locations l
                    ON l.tenant_id = b.tenant_id AND l.id = b.location_id
                 WHERE v.tenant_id = :tenantId
                   AND l.brand_id = :brandId
                   AND l.status = 'ACTIVE'
                   AND v.status = 'ACTIVE'
                   AND v.zone_role = 'DELIVERY'
                   AND b.valid_from <= :at
                   AND (b.valid_until IS NULL OR b.valid_until > :at)
                   AND ST_Covers(v.area,
                         ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)
                 ORDER BY b.location_id, v.priority DESC, v.area_sq_meters, v.zone_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("longitude", point.longitude())
                .param("latitude", point.latitude())
                .param("at", timestamp(at))
                .query((row, number) -> new DeliveryBranchMatch(
                        row.getObject("location_id", UUID.class),
                        row.getString("display_name"),
                        row.getObject("zone_id", UUID.class),
                        row.getInt("version"),
                        row.getInt("priority"),
                        row.getDouble("area_sq_meters")))
                .list();
    }

    /** See {@link uz.horecaos.platform.fulfillment.api.BranchResolutionPort#pickupCandidates}. */
    public List<PickupBranchCandidate> pickupCandidates(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT id, display_name
                  FROM tenant.locations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND status = 'ACTIVE'
                 ORDER BY display_name
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) ->
                        new PickupBranchCandidate(row.getObject("id", UUID.class), row.getString("display_name")))
                .list();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
