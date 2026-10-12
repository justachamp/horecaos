package uz.horecaos.platform.fulfillment.infrastructure.sourcing;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * How many orders each courier is already carrying (ADR 0014).
 *
 * <p>Counted from {@code fulfillment.shipments} and not from
 * {@code assignment_attempts}. An accepted attempt is the moment a courier said
 * yes; the shipment is the order in his hands, and it is the shipment that
 * reaches {@code DELIVERED} or {@code CANCELLED} and stops being carried. Both
 * tables would agree today because an acceptance creates exactly one shipment,
 * but only one of them is updated when the order finishes, and counting the
 * other would leave every courier permanently at capacity by the end of his
 * first evening.
 *
 * <p>The predicate is V0054's {@code ix_shipment_courier_open} verbatim —
 * {@code courier_id IS NOT NULL AND status NOT IN ('DELIVERED', 'CANCELLED')} —
 * so this query uses the partial index rather than scanning around it. That
 * index was created for this question and, until ADR 0042's dispatch half
 * landed, was asked it by nobody.
 *
 * <p>Tenant-scoped, and the courier ids are constrained inside the tenant they
 * arrived with rather than trusted on their own. The caller is the courier
 * module answering a question about one branch; a courier id that belongs to
 * another tenant simply produces no row.
 */
@Component
public class JdbcActiveAssignments implements InternalFleetPort.ActiveAssignments {

    /** Why the drop-off of an order is read for the grouping feed (ADR 0029, ADR 0170 Decision 9). */
    public static final String PURPOSE_DISPATCH_GROUPING = "DISPATCH_GROUPING";

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    /**
     * The job the audit fact of a reveal names when nobody asked for it: sourcing runs as a system job. A reveal
     * a person asked for (the dispatch-rule simulator) names that person instead.
     */
    private static final String SYSTEM_JOB = "delivery-sourcing";

    private final JdbcClient jdbc;
    private final DeliveryOrderPort deliveryOrders;
    private final @Nullable MeterRegistry meters;
    private final @Nullable AuditRecorder audit;
    private final Clock clock;

    @Autowired
    public JdbcActiveAssignments(
            JdbcClient jdbc,
            DeliveryOrderPort deliveryOrders,
            @Nullable MeterRegistry meters,
            @Nullable AuditRecorder audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.deliveryOrders = deliveryOrders;
        this.meters = meters;
        this.audit = audit;
        this.clock = clock;
    }

    public JdbcActiveAssignments(JdbcClient jdbc, DeliveryOrderPort deliveryOrders, @Nullable AuditRecorder audit) {
        this(jdbc, deliveryOrders, null, audit, Clock.systemUTC());
    }

    public JdbcActiveAssignments(JdbcClient jdbc, DeliveryOrderPort deliveryOrders) {
        this(jdbc, deliveryOrders, null, null, Clock.systemUTC());
    }

    /** Counts only: no order port, so no drop-off can be read and nothing is ever groupable. */
    public JdbcActiveAssignments(JdbcClient jdbc) {
        this(jdbc, (tenantId, orderId) -> Optional.empty(), null, null, Clock.systemUTC());
    }

    @Override
    public Map<UUID, Integer> byCourier(UUID tenantId, Collection<UUID> courierIds) {
        if (courierIds.isEmpty()) {
            // NamedParameterJdbcTemplate renders an empty collection as `IN ()`,
            // which PostgreSQL rejects. An empty question also has an answer.
            return Map.of();
        }

        List<Carrying> rows = jdbc.sql("""
                SELECT courier_id, count(*) AS carrying
                  FROM fulfillment.shipments
                 WHERE tenant_id = :tenantId
                   AND courier_id IN (:courierIds)
                   AND status NOT IN ('DELIVERED', 'CANCELLED')
                 GROUP BY courier_id
                """)
                .param("tenantId", tenantId)
                .param("courierIds", courierIds)
                .query((row, rowNumber) ->
                        new Carrying(row.getObject("courier_id", UUID.class), row.getInt("carrying")))
                .list();

        Map<UUID, Integer> carried = new HashMap<>();
        for (Carrying row : rows) {
            carried.put(row.courierId(), row.carrying());
        }
        return Map.copyOf(carried);
    }

    /**
     * ADR 0170 Decision 8, computed here because {@code fulfillment} owns the shipments and may not be read by
     * {@code courier}, and because the drop-off is personal data revealed only under a named purpose.
     *
     * <p>Asked only when a rule groups. For each courier it needs the shipments they carry and have not
     * picked up ({@code ASSIGNED} or {@code PICKUP_PENDING}) from the same branch as the plan, and it asks
     * {@link DeliveryOrderPort#dropoffPoint} for the plan's own door once and for each carried door once.
     * A courier already at {@code maxOrdersPerRun} is not looked at: the cheapest filter first, so the
     * reveals are bounded by the couriers carrying something un-picked-up at one branch times {@code
     * maxOrdersPerRun - 1}. Live on every offer round, never cached (ADR 0033): a cache of drop-off
     * coordinates is a store of personal data with no purpose limit.
     *
     * <p>Straight-line distance. Road distance when ADR 0147's routing provider is bound is not wired into
     * this read yet; the distance stamped on the attempt is the straight line either way.
     */
    @Override
    public Map<UUID, InternalFleetPort.Groupable> groupable(
            UUID tenantId, UUID planId, Collection<UUID> courierIds, int radiusMeters, int maxOrdersPerRun) {
        return groupable(tenantId, planId, courierIds, radiusMeters, maxOrdersPerRun, null);
    }

    @Override
    public Map<UUID, InternalFleetPort.Groupable> groupable(
            UUID tenantId,
            UUID planId,
            Collection<UUID> courierIds,
            int radiusMeters,
            int maxOrdersPerRun,
            @Nullable ActorRef requestedBy) {

        if (courierIds.isEmpty()) {
            return Map.of();
        }
        Optional<PlanDoor> planDoor = jdbc.sql("""
                        SELECT order_id, brand_id, location_id FROM fulfillment.delivery_plans
                        WHERE tenant_id = :tenantId AND id = :planId
                        """)
                .param("tenantId", tenantId)
                .param("planId", planId)
                .query((row, number) -> new PlanDoor(
                        row.getObject("order_id", UUID.class),
                        ResourceScope.location(
                                tenantId,
                                row.getObject("brand_id", UUID.class),
                                row.getObject("location_id", UUID.class))))
                .optional();
        if (planDoor.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> carrying = byCourier(tenantId, courierIds);
        List<UnPickedShipment> shipments = jdbc.sql("""
                        SELECT s.courier_id, s.id AS shipment_id, s.order_id
                        FROM fulfillment.shipments s
                        WHERE s.tenant_id = :tenantId
                          AND s.courier_id IN (:courierIds)
                          AND s.status IN ('ASSIGNED', 'PICKUP_PENDING')
                          AND s.location_id = (SELECT location_id FROM fulfillment.delivery_plans
                                               WHERE tenant_id = :tenantId AND id = :planId)
                        ORDER BY s.assigned_at, s.id
                        """)
                .param("tenantId", tenantId)
                .param("courierIds", courierIds)
                .param("planId", planId)
                .query((row, number) -> new UnPickedShipment(
                        row.getObject("courier_id", UUID.class),
                        row.getObject("shipment_id", UUID.class),
                        row.getObject("order_id", UUID.class)))
                .list();
        List<UnPickedShipment> candidates = shipments.stream()
                .filter(shipment -> carrying.getOrDefault(shipment.courierId(), 0) < maxOrdersPerRun)
                .toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }

        ResourceScope scope = planDoor.get().scope();
        Optional<GeoPoint> here = reveal(tenantId, scope, planDoor.get().orderId(), requestedBy);
        if (here.isEmpty()) {
            return Map.of();
        }
        Map<UUID, InternalFleetPort.Groupable> nearest = new HashMap<>();
        for (UnPickedShipment shipment : candidates) {
            Optional<GeoPoint> there = reveal(tenantId, scope, shipment.orderId(), requestedBy);
            if (there.isEmpty()) {
                continue;
            }
            int metres = (int) Math.round(distanceMeters(here.get(), there.get()));
            if (metres > radiusMeters) {
                continue;
            }
            InternalFleetPort.Groupable known = nearest.get(shipment.courierId());
            if (known == null || metres < known.distanceMeters()) {
                nearest.put(shipment.courierId(), new InternalFleetPort.Groupable(metres, shipment.shipmentId()));
            }
        }
        return Map.copyOf(nearest);
    }

    /**
     * A door, or nothing: an unreadable or un-geocoded address makes a courier not groupable and is never an error.
     *
     * <p>Every attempt to read one is a recorded reveal (ADR 0029, ADR 0027): a SECURITY fact that names the order
     * and the purpose, and who asked (the person, when one did; the sourcing job otherwise), and carries no part
     * of the address, and a counter by purpose. The fact is written before the
     * read, so a read that fails is still on record as attempted.
     */
    private Optional<GeoPoint> reveal(
            UUID tenantId, ResourceScope scope, UUID orderId, @Nullable ActorRef requestedBy) {
        if (meters != null) {
            meters.counter("fulfillment.grouping.reveals", "purpose", PURPOSE_DISPATCH_GROUPING)
                    .increment();
        }
        if (audit != null) {
            audit.record(AuditFact.of("fulfillment.grouping.dropoff-revealed", AuditClass.SECURITY)
                    .by(requestedBy != null ? requestedBy : ActorRef.systemJob(SYSTEM_JOB))
                    .at(scope)
                    .target("ordering.order", orderId)
                    .because("The grouping bias measured a drop-off (" + PURPOSE_DISPATCH_GROUPING + ")")
                    .changed(ChangeDocuments.created(Map.of("purpose", PURPOSE_DISPATCH_GROUPING)))
                    .correlatedBy(orderId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        try {
            return deliveryOrders.dropoffPoint(tenantId, orderId, PURPOSE_DISPATCH_GROUPING);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** Great-circle distance in metres (haversine): straight-line, which is what the radius is stated in. */
    static double distanceMeters(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    private record PlanDoor(UUID orderId, ResourceScope scope) {}

    private record UnPickedShipment(UUID courierId, UUID shipmentId, UUID orderId) {}

    private record Carrying(UUID courierId, int carrying) {}
}
