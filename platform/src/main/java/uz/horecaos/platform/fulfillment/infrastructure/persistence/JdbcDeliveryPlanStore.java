package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;

/**
 * {@code fulfillment.delivery_plans} (ADR 0014, V0054).
 *
 * <p>Two rules are left to the database on purpose. {@code ux_plan_one_live} is
 * what makes a replayed confirmation produce one plan rather than two sets of
 * sourcing jobs racing for one order, and {@code ck_plan_window} and
 * {@code ck_plan_latest_assignment} are what make a nonsensical window fail at the
 * INSERT rather than at a courier. Re-checking either here would put the
 * invariant in two places, and the second place is always the one that drifts.
 */
@Repository
public class JdbcDeliveryPlanStore {

    private final JdbcClient jdbc;

    public JdbcDeliveryPlanStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The plan for this order, created once.
     *
     * <p>{@code ON CONFLICT ... DO NOTHING} against the live-plan index rather
     * than a read-then-write: two confirmations of one order arriving on two
     * threads both read "no plan" and both insert, and only the index decides
     * which is right. An empty return therefore means "somebody else already
     * planned this", which is a success for the caller and not a failure.
     *
     * @return the plan as stored, whether this call inserted it or found it
     */
    public DeliveryPlan create(DeliveryPlan plan) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", plan.id());
        params.put("tenantId", plan.tenantId());
        params.put("brandId", plan.brandId());
        params.put("locationId", plan.locationId());
        params.put("orderId", plan.orderId());
        params.put("status", plan.status().name());
        params.put("mode", plan.mode().name());
        params.put("serviceLevel", plan.serviceLevel());
        params.put("fee", plan.customerDeliveryFeeMinor());
        params.put("currency", plan.currency());
        params.put("resolutionId", plan.deliveryFeeResolutionId());
        params.put("confirmedAt", utc(plan.pickup().confirmedAt()));
        params.put("preparationSeconds", (int) plan.pickup().preparation().toSeconds());
        params.put("readyAt", utc(plan.pickup().estimatedReadyAt()));
        params.put("windowStart", utc(plan.pickup().pickupWindowStart()));
        params.put("windowEnd", utc(plan.pickup().pickupWindowEnd()));
        params.put("promisedStart", utc(plan.promisedDeliveryStart()));
        params.put("promisedEnd", utc(plan.promisedDeliveryEnd()));
        params.put("sourceAt", utc(plan.pickup().sourceAt()));
        params.put("latestAssignmentAt", utc(plan.pickup().latestAssignmentAt()));
        params.put("branchZone", plan.pickup().branchZone().getId());
        params.put("calculationVersion", plan.pickup().calculationVersion());
        params.put("distanceMeters", plan.distanceMeters());
        params.put("distanceSource", plan.distanceSource());
        params.put("policyId", plan.policyId());
        params.put("policyVersion", plan.policyVersion());

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (
                    id, tenant_id, brand_id, location_id, order_id,
                    status, sourcing_mode, service_level,
                    customer_delivery_fee_minor, currency, delivery_fee_resolution_id,
                    confirmed_at, preparation_seconds, estimated_ready_at,
                    pickup_window_start, pickup_window_end,
                    promised_delivery_start, promised_delivery_end,
                    source_at, latest_assignment_at, branch_zone, calculation_version,
                    distance_meters, distance_source, policy_id, policy_version)
                VALUES (
                    :id, :tenantId, :brandId, :locationId, :orderId,
                    :status, :mode, :serviceLevel,
                    :fee, :currency, :resolutionId,
                    :confirmedAt, :preparationSeconds, :readyAt,
                    :windowStart, :windowEnd,
                    :promisedStart, :promisedEnd,
                    :sourceAt, :latestAssignmentAt, :branchZone, :calculationVersion,
                    :distanceMeters, :distanceSource, :policyId, :policyVersion)
                ON CONFLICT (tenant_id, order_id) WHERE status <> 'CANCELLED' DO NOTHING
                """).params(params).update();

        return findByOrder(plan.tenantId(), plan.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "The plan for order " + plan.orderId() + " vanished between its insert and its read"));
    }

    public Optional<DeliveryPlan> find(UUID tenantId, UUID planId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND id = :planId")
                .param("tenantId", tenantId)
                .param("planId", planId)
                .query(JdbcDeliveryPlanStore::mapPlan)
                .optional();
    }

    /** The live plan, which {@code ux_plan_one_live} guarantees is at most one. */
    public Optional<DeliveryPlan> findByOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND order_id = :orderId AND status <> 'CANCELLED'
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcDeliveryPlanStore::mapPlan)
                .optional();
    }

    /**
     * Moves the plan, and only from where the caller believed it was.
     *
     * <p>The {@code from} guard is what stops a slow worker whose lease expired
     * from dragging a plan back out of ASSIGNED after somebody else finished
     * sourcing it. A false return is that case and is not an error.
     */
    public boolean transition(UUID tenantId, UUID planId, PlanStatus from, PlanStatus to, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.delivery_plans
                SET status = :to, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :planId AND status = :from
                """)
                        .param("tenantId", tenantId)
                        .param("planId", planId)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Moves the plan from any state automated sourcing still owns.
     *
     * <p>Used for the terminal moves — assigned, or handed to a human — where the
     * caller knows the plan is one of several sourcing states and re-reading it to
     * find out which would be a lost update waiting to happen. A plan already
     * settled is left alone, so a late tick cannot un-assign a courier.
     */
    public boolean settle(UUID tenantId, UUID planId, PlanStatus to, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.delivery_plans
                SET status = :to, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :planId
                  AND status IN ('PLANNED', 'WAITING_TO_SOURCE', 'SOURCING', 'BOOKING',
                                 'RETRY_PENDING', 'SCHEDULED')
                """)
                        .param("tenantId", tenantId)
                        .param("planId", planId)
                        .param("to", to.name())
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    private static final String SELECT = """
            SELECT id, tenant_id, brand_id, location_id, order_id, status, sourcing_mode,
                   service_level, customer_delivery_fee_minor, currency,
                   delivery_fee_resolution_id, confirmed_at, preparation_seconds,
                   estimated_ready_at, pickup_window_start, pickup_window_end,
                   promised_delivery_start, promised_delivery_end, source_at,
                   latest_assignment_at, branch_zone, calculation_version,
                   distance_meters, distance_source, policy_id, policy_version, version
            FROM fulfillment.delivery_plans
            """;

    private static DeliveryPlan mapPlan(java.sql.ResultSet row, int number) throws java.sql.SQLException {

        // Every column PickupPlan reads here is NOT NULL in V0054 — the whole time
        // model is computed once, at plan creation, and never partially written.
        // instant() is shared with genuinely nullable columns (below), so its
        // return type is honestly nullable; these six are asserted rather than
        // propagated as optional.
        PickupPlan pickup = new PickupPlan(
                Objects.requireNonNull(instant(row, "confirmed_at")),
                Duration.ofSeconds(row.getInt("preparation_seconds")),
                Objects.requireNonNull(instant(row, "estimated_ready_at")),
                Objects.requireNonNull(instant(row, "pickup_window_start")),
                Objects.requireNonNull(instant(row, "pickup_window_end")),
                Objects.requireNonNull(instant(row, "source_at")),
                Objects.requireNonNull(instant(row, "latest_assignment_at")),
                ZoneId.of(row.getString("branch_zone")),
                row.getInt("calculation_version"));

        return new DeliveryPlan(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("order_id", UUID.class),
                PlanStatus.valueOf(row.getString("status")),
                SourcingMode.valueOf(row.getString("sourcing_mode")),
                row.getString("service_level"),
                row.getLong("customer_delivery_fee_minor"),
                row.getString("currency"),
                row.getObject("delivery_fee_resolution_id", UUID.class),
                pickup,
                instant(row, "promised_delivery_start"),
                instant(row, "promised_delivery_end"),
                // getInt answers 0 for SQL NULL and 0 is a real distance, so every
                // nullable number here goes through getObject.
                row.getObject("distance_meters", Integer.class),
                row.getString("distance_source"),
                row.getObject("policy_id", UUID.class),
                row.getObject("policy_version", Integer.class),
                row.getInt("version"));
    }

    static @Nullable Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
