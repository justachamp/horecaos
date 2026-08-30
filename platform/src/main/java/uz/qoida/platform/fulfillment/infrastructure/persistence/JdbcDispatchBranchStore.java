package uz.qoida.platform.fulfillment.infrastructure.persistence;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
import uz.qoida.platform.fulfillment.domain.BranchOrigin;

/**
 * The branch end of a delivery, read for dispatch (ADR 0014, V0023).
 *
 * <p>Separate from {@code JdbcServiceZoneStore.findBranch}, which reads the same
 * table for a different question. That one answers "where is this branch, so a
 * polygon can be drawn around it"; this one answers "what does a courier need in
 * order to arrive at the right door", which is the address, the landmark and the
 * line to ring when he cannot find it.
 *
 * <p>All of it in clear, and V0023's own comment is the argument: a restaurant's
 * address is published by the merchant on purpose — printed on the receipt, shown
 * in the storefront, handed to every courier — so encrypting it would put a
 * decrypt on the hot path of every dispatch for information the merchant is
 * actively advertising. The customer end is the opposite and comes from
 * {@code DeliveryOrderPort}.
 */
@Repository
public class JdbcDispatchBranchStore {

    private final JdbcClient jdbc;

    public JdbcDispatchBranchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return empty when no such location belongs to this tenant and brand. An
     *         entity id alone is never proof of ownership, so all three are in the
     *         predicate rather than the id alone
     */
    public Optional<DispatchBranch> find(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                SELECT id, display_name, timezone, latitude, longitude, coordinate_source,
                       address_line, district, city, landmark, contact_phone
                FROM tenant.locations
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :locationId
                """)
                .param("tenantId", tenantId).param("brandId", brandId)
                .param("locationId", locationId)
                .query((row, number) -> new DispatchBranch(
                        row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        ZoneId.of(row.getString("timezone")),
                        // getDouble answers 0 for SQL NULL and 0 is a real
                        // coordinate, so an unplaced branch must not arrive here as
                        // a point in the Gulf of Guinea.
                        row.getObject("latitude", Double.class),
                        row.getObject("longitude", Double.class),
                        row.getString("coordinate_source"),
                        row.getString("address_line"),
                        row.getString("district"),
                        row.getString("city"),
                        row.getString("landmark"),
                        row.getString("contact_phone")))
                .optional();
    }

    /**
     * @param coordinateSource carried as the string V0023 stores. Only one value
     *                         decides anything and {@link BranchOrigin} names it
     */
    public record DispatchBranch(
            UUID locationId,
            String displayName,
            ZoneId timezone,
            Double latitude,
            Double longitude,
            String coordinateSource,
            String addressLine,
            String district,
            String city,
            String landmark,
            String contactPhone) {

        /** Refuses an absent coordinate and the null island, both loudly. */
        public BranchOrigin origin() {
            return BranchOrigin.of(locationId, latitude, longitude, coordinateSource);
        }

        /**
         * The pickup end of a partner's route.
         *
         * <p>The landmark is appended rather than dropped: a large share of
         * addresses in this market are given as one, and a courier who cannot find
         * the service entrance of a branch inside a mall loses ten minutes of
         * somebody's promise.
         */
        public Waypoint asWaypoint() {
            BranchOrigin origin = origin();
            return new Waypoint(origin.point().latitude(), origin.point().longitude(),
                    address(), displayName, contactPhone, landmark, null, null, null);
        }

        private String address() {
            StringBuilder address = new StringBuilder();
            append(address, addressLine);
            append(address, district);
            append(address, city);
            return address.isEmpty() ? displayName : address.toString();
        }

        private static void append(StringBuilder address, String part) {
            if (part == null || part.isBlank()) {
                return;
            }
            if (!address.isEmpty()) {
                address.append(", ");
            }
            address.append(part);
        }
    }
}
