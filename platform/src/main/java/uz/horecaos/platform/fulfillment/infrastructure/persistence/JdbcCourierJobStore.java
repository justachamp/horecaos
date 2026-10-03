package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.instant;
import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Job;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.JobStatus;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Offer;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Pickup;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Step;

/**
 * The courier-app reads and writes over {@code fulfillment.assignment_attempts} and
 * {@code fulfillment.shipments} (ADR 0014, V0054, V0484).
 *
 * <p>Separate from {@link JdbcAssignmentStore}, which answers to sourcing and to the dispatch board
 * and has no business growing a courier's point of view; the winning move itself --
 * {@link JdbcAssignmentStore#acceptOffer} -- is still that store's, so the single-winner rule
 * lives in exactly one place.
 *
 * <p><b>Every statement names the courier.</b> An id is never proof of ownership, so "this courier's
 * offer" and "this courier's shipment" are predicates of the {@code WHERE}, not a read the caller
 * is trusted to have made first. The result is that an id belonging to somebody else is
 * indistinguishable from an id that does not exist.
 *
 * <p>The joins reach into {@code ordering.orders} for the order's number, total, payment status and
 * whether the kitchen has finished -- plain SQL, no Java import of an ordering type, the shape
 * {@code JdbcDeliveryCompletionAdapter} already uses for the same reason: ADR 0042's boundary
 * keeps {@code courier} out of both schemas, and this module's own read of a column ordering
 * publishes in clear is the cheaper side of that line to stand on.
 */
@Repository
public class JdbcCourierJobStore {

    /** A plan sourcing has not yet settled: a courier can still be asked. */
    private static final String UNSETTLED_PLAN =
            "p.status NOT IN ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'MANUAL_ACTION_REQUIRED', 'CANCELLED')";

    /** An order that is still a live obligation: cooked, being cooked, or on the road. */
    private static final String LIVE_ORDER = "o.status IN ('CONFIRMED', 'PREPARING', 'READY', 'FULFILLING')";

    private static final String PLAN_ORDER_BRANCH_COLUMNS = """
            p.id AS plan_id, p.order_id, p.brand_id, p.location_id, p.currency, p.destination_label,
            p.distance_meters, p.pickup_window_start, p.pickup_window_end, p.promised_delivery_end,
            o.public_order_number, o.total_minor, o.payment_status_projection,
            (o.status IN ('READY', 'FULFILLING', 'COMPLETED')) AS kitchen_ready,
            loc.display_name, loc.address_line, loc.district, loc.city, loc.landmark,
            loc.latitude, loc.longitude
            """;

    private static final String PLAN_ORDER_BRANCH_JOINS = """
            JOIN fulfillment.delivery_plans p
              ON p.id = %s.delivery_plan_id AND p.tenant_id = %s.tenant_id
            JOIN ordering.orders o
              ON o.id = p.order_id AND o.tenant_id = p.tenant_id
            JOIN tenant.locations loc
              ON loc.id = p.location_id AND loc.tenant_id = p.tenant_id AND loc.brand_id = p.brand_id
            """;

    private final JdbcClient jdbc;

    public JdbcCourierJobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ offers

    public List<Offer> openOffers(UUID tenantId, UUID courierId, Instant now) {
        return jdbc.sql(offerSelect() + """
                 WHERE a.tenant_id = :tenantId AND a.courier_id = :courierId
                   AND a.source_type = 'INTERNAL' AND a.status = 'OFFERED' AND a.expires_at > :now
                   AND %s AND %s
                 ORDER BY a.expires_at, a.id
                """.formatted(UNSETTLED_PLAN, LIVE_ORDER))
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("now", utc(now))
                .query(JdbcCourierJobStore::mapOffer)
                .list();
    }

    public Optional<Offer> offer(UUID tenantId, UUID courierId, UUID offerId, Instant now) {
        return jdbc.sql(offerSelect() + """
                 WHERE a.tenant_id = :tenantId AND a.courier_id = :courierId AND a.id = :offerId
                   AND a.source_type = 'INTERNAL' AND a.status = 'OFFERED' AND a.expires_at > :now
                   AND %s AND %s
                """.formatted(UNSETTLED_PLAN, LIVE_ORDER))
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("offerId", offerId)
                .param("now", utc(now))
                .query(JdbcCourierJobStore::mapOffer)
                .optional();
    }

    /**
     * Turns the offer down, bound to the courier it was made to and to its own lifetime.
     *
     * <p>Not {@link JdbcAssignmentStore#close}: that one closes any live attempt of the tenant by id
     * and trusts its caller to have established whose it is, which is the check-then-act this class
     * exists not to repeat. {@code failed_at} stays null -- a person saying no is not a failure,
     * and {@code ck_attempt_failed_pair} ties that column to a failure code.
     */
    public boolean decline(UUID tenantId, UUID courierId, UUID offerId, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.assignment_attempts
                SET status = 'DECLINED', declined_at = :now, version = version + 1
                WHERE tenant_id = :tenantId AND id = :offerId AND courier_id = :courierId
                  AND source_type = 'INTERNAL' AND status = 'OFFERED' AND expires_at > :now
                """)
                        .param("tenantId", tenantId)
                        .param("courierId", courierId)
                        .param("offerId", offerId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    // -------------------------------------------------------------------- jobs

    /** What the courier is carrying: assigned, waiting at the pickup, or on the road. */
    public List<Job> activeJobs(UUID tenantId, UUID courierId) {
        return jdbc.sql(jobSelect() + """
                 WHERE s.tenant_id = :tenantId AND s.courier_id = :courierId
                   AND s.source_type = 'INTERNAL'
                   AND s.status IN ('ASSIGNED', 'PICKUP_PENDING', 'PICKED_UP')
                 ORDER BY s.assigned_at, s.id
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcCourierJobStore::mapJob)
                .list();
    }

    public Optional<Job> job(UUID tenantId, UUID courierId, UUID shipmentId) {
        return jdbc.sql(jobSelect() + """
                 WHERE s.tenant_id = :tenantId AND s.courier_id = :courierId AND s.id = :shipmentId
                   AND s.source_type = 'INTERNAL' AND s.status <> 'PENDING'
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("shipmentId", shipmentId)
                .query(JdbcCourierJobStore::mapJob)
                .optional();
    }

    /**
     * The compare-and-set behind {@code ASSIGNED -> PICKUP_PENDING -> PICKED_UP -> DELIVERED}.
     * The {@code WHERE} names the statuses the step may be taken from, so a tap on a shipment that
     * has moved on matches nothing rather than moving it backwards.
     *
     * @return true when this call moved the shipment
     */
    public boolean advance(UUID tenantId, UUID courierId, UUID shipmentId, Step to, Instant now) {
        List<String> from =
                switch (to) {
                    case PICKUP_PENDING -> List.of("ASSIGNED");
                    case PICKED_UP -> List.of("ASSIGNED", "PICKUP_PENDING");
                    case DELIVERED -> List.of("PICKED_UP");
                };
        return jdbc.sql("""
                UPDATE fulfillment.shipments
                SET status = CAST(:to AS varchar),
                    picked_up_at = CASE WHEN CAST(:to AS varchar) = 'PICKED_UP' THEN :now ELSE picked_up_at END,
                    delivered_at = CASE WHEN CAST(:to AS varchar) = 'DELIVERED' THEN :now ELSE delivered_at END,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :shipmentId AND courier_id = :courierId
                  AND source_type = 'INTERNAL' AND status IN (:from)
                """)
                        .param("to", to.name())
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("shipmentId", shipmentId)
                        .param("courierId", courierId)
                        .param("from", from)
                        .update()
                == 1;
    }

    /**
     * Records the courier's payment confirmation, once. The first moment stands: a handset
     * replaying the tap must not move it, and {@code payment_confirmed_at IS NULL} in the
     * statement is what says so.
     *
     * @return true when this call recorded it
     */
    public boolean confirmPayment(
            UUID tenantId, UUID courierId, UUID shipmentId, long collectedMinor, String currency, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.shipments
                SET payment_confirmed_at = :now, payment_confirmed_minor = :minor,
                    payment_confirmed_currency = :currency, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :shipmentId AND courier_id = :courierId
                  AND source_type = 'INTERNAL' AND status = 'PICKED_UP' AND payment_confirmed_at IS NULL
                """)
                        .param("now", utc(now))
                        .param("minor", collectedMinor)
                        .param("currency", currency)
                        .param("tenantId", tenantId)
                        .param("shipmentId", shipmentId)
                        .param("courierId", courierId)
                        .update()
                == 1;
    }

    // ----------------------------------------------------------------- mapping

    private static String offerSelect() {
        return """
                SELECT a.id AS offer_id, a.version AS offer_version, a.requested_at, a.expires_at,
                       %s
                FROM fulfillment.assignment_attempts a
                %s
                """.formatted(PLAN_ORDER_BRANCH_COLUMNS, PLAN_ORDER_BRANCH_JOINS.formatted("a", "a"));
    }

    private static String jobSelect() {
        return """
                SELECT s.id AS shipment_id, s.version AS shipment_version, s.status AS shipment_status,
                       s.assigned_at, s.picked_up_at, s.delivered_at,
                       s.payment_confirmed_at, s.payment_confirmed_minor,
                       %s
                FROM fulfillment.shipments s
                %s
                """.formatted(PLAN_ORDER_BRANCH_COLUMNS, PLAN_ORDER_BRANCH_JOINS.formatted("s", "s"));
    }

    private static Offer mapOffer(ResultSet row, int number) throws SQLException {
        return new Offer(
                row.getObject("offer_id", UUID.class),
                row.getInt("offer_version"),
                row.getObject("plan_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("public_order_number"),
                Objects.requireNonNull(instant(row, "requested_at")),
                Objects.requireNonNull(instant(row, "expires_at")),
                row.getString("currency"),
                row.getLong("total_minor"),
                isPrepaid(row.getString("payment_status_projection")),
                row.getBoolean("kitchen_ready"),
                row.getString("destination_label"),
                row.getObject("distance_meters", Integer.class),
                Objects.requireNonNull(instant(row, "pickup_window_start")),
                Objects.requireNonNull(instant(row, "pickup_window_end")),
                instant(row, "promised_delivery_end"),
                mapPickup(row));
    }

    private static Job mapJob(ResultSet row, int number) throws SQLException {
        return new Job(
                row.getObject("shipment_id", UUID.class),
                row.getInt("shipment_version"),
                JobStatus.valueOf(row.getString("shipment_status")),
                row.getObject("plan_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("public_order_number"),
                Objects.requireNonNull(instant(row, "assigned_at")),
                instant(row, "picked_up_at"),
                instant(row, "delivered_at"),
                instant(row, "payment_confirmed_at"),
                row.getObject("payment_confirmed_minor", Long.class),
                row.getString("currency"),
                row.getLong("total_minor"),
                isPrepaid(row.getString("payment_status_projection")),
                row.getBoolean("kitchen_ready"),
                row.getString("destination_label"),
                row.getObject("distance_meters", Integer.class),
                Objects.requireNonNull(instant(row, "pickup_window_start")),
                Objects.requireNonNull(instant(row, "pickup_window_end")),
                instant(row, "promised_delivery_end"),
                mapPickup(row));
    }

    private static Pickup mapPickup(ResultSet row) throws SQLException {
        String name = row.getString("display_name");
        return new Pickup(
                name,
                addressOf(name, row.getString("address_line"), row.getString("district"), row.getString("city")),
                row.getString("landmark"),
                // getDouble answers 0 for SQL NULL and 0 is a real coordinate, so an unplaced
                // branch must not arrive as a point in the Gulf of Guinea.
                row.getObject("latitude", Double.class),
                row.getObject("longitude", Double.class));
    }

    /** The branch's own address as a courier reads it; the name when it has published none. */
    private static String addressOf(String name, @Nullable String line, @Nullable String district, @Nullable String city) {
        StringBuilder address = new StringBuilder();
        for (String part : new String[] {line, district, city}) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!address.isEmpty()) {
                address.append(", ");
            }
            address.append(part.trim());
        }
        return address.isEmpty() ? name : address.toString();
    }

    /**
     * Prepaid means HorecaOS has the money; the same reading {@code JdbcDeliveryOrderPort} and
     * {@code JdbcDeliveryCompletionAdapter} give the same column.
     */
    private static boolean isPrepaid(@Nullable String paymentStatus) {
        return "AUTHORIZED".equals(paymentStatus) || "CAPTURED".equals(paymentStatus);
    }
}
