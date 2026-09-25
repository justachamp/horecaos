package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.BranchBindingRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.ComplianceSummaryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierGroupRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRosterRow;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.telemetry.api.CourierLastSeenPort;

/**
 * The in-house roster, with today's load (IA operations §3.3, §3.1's fleet
 * rail).
 *
 * <p>One query serves both screens deliberately: §3.3 Couriers and §3.1
 * Dispatch board's fleet rail are both "who is engaged and how loaded are
 * they right now", and a second, near-identical projection would drift from
 * this one the first time somebody fixed a bug in only one of them.
 *
 * <p>Load comes from {@link InternalFleetPort.ActiveAssignments}, the same
 * count sourcing itself reads before offering an order — imported from
 * {@code fulfillment.api} rather than reimplemented here, for the reason its
 * own Javadoc gives: two places counting {@code fulfillment.shipments} is how
 * a courier ends up carrying one more order than his vehicle class allows.
 *
 * <p><strong>Online status (gap map row {@code 3.3})</strong> is the same
 * shape: a courier's most recent telemetry fix ({@link CourierLastSeenPort},
 * {@code telemetry.api}) compared against {@code
 * CourierCompensationPolicy.onlineWithinMinutes} — resolved once per call at
 * TENANT scope, since this query itself carries no location, the same
 * tenant-wide resolution the roster's other fields already use. A courier
 * with no live row at all (no open duty session, or one closed long enough
 * ago that the retention sweep already removed the row) is offline; there is
 * no third state, because an operator deciding whether to dispatch to
 * somebody needs a yes-or-no, not "unknown since Tuesday".
 */
@Service
public class CourierRosterQueryService {

    private final JdbcCourierStore couriers;
    private final InternalFleetPort.ActiveAssignments activeAssignments;
    private final CourierLastSeenPort lastSeen;
    private final CourierPolicyResolver policies;
    private final Clock clock;

    public CourierRosterQueryService(
            JdbcCourierStore couriers,
            InternalFleetPort.ActiveAssignments activeAssignments,
            CourierLastSeenPort lastSeen,
            CourierPolicyResolver policies,
            Clock clock) {
        this.couriers = couriers;
        this.activeAssignments = activeAssignments;
        this.lastSeen = lastSeen;
        this.policies = policies;
        this.clock = clock;
    }

    public List<RosterEntry> roster(UUID tenantId) {
        List<CourierRosterRow> rows = couriers.listCouriers(tenantId);
        List<UUID> courierIds = rows.stream().map(CourierRosterRow::id).toList();
        Map<UUID, Integer> load = activeAssignments.byCourier(tenantId, courierIds);
        Map<UUID, Instant> lastFix = lastSeen.lastFixByCourier(tenantId, courierIds);
        Duration onlineWindow = onlineWindow(tenantId);
        Instant now = clock.instant();
        return rows.stream()
                .map(row -> toEntry(row, load, lastFix, onlineWindow, now))
                .toList();
    }

    /**
     * One courier as the detail pane behind the roster reads them (IA 3.3).
     *
     * <p>Everything here is either already on the list row or is presence rather
     * than content: which compliance fields exist, which groups the courier is
     * in, which branches they ride for. Not one decrypted value — opening a
     * courier shows a manager that the file is complete, and reading what is in
     * it is the separate, audited act {@code courier.pii.reveal} gates.
     */
    public Optional<CourierDetail> detail(UUID tenantId, UUID courierId) {
        return couriers.findRosterEntry(tenantId, courierId)
                .map(row -> new CourierDetail(
                        toEntry(
                                row,
                                activeAssignments.byCourier(tenantId, List.of(row.id())),
                                lastSeen.lastFixByCourier(tenantId, List.of(row.id())),
                                onlineWindow(tenantId),
                                clock.instant()),
                        couriers.findComplianceSummary(tenantId, courierId).orElseThrow(),
                        couriers.groupsOf(tenantId, courierId),
                        couriers.bindingsOf(tenantId, courierId)));
    }

    private Duration onlineWindow(UUID tenantId) {
        return Duration.ofMinutes(
                policies.resolve(ResourceScope.tenant(tenantId)).onlineWithinMinutes());
    }

    private static RosterEntry toEntry(
            CourierRosterRow row,
            Map<UUID, Integer> load,
            Map<UUID, Instant> lastFix,
            Duration onlineWindow,
            Instant now) {
        Instant seenAt = lastFix.get(row.id());
        boolean online = seenAt != null && !seenAt.isBefore(now.minus(onlineWindow));
        return new RosterEntry(row, load.getOrDefault(row.id(), 0), online, seenAt);
    }

    /**
     * @param activeAssignments carried orders right now; absent from the map reads as zero
     * @param online whether the courier's most recent telemetry fix is within the
     *               tenant's {@code onlineWithinMinutes} policy — always {@code false}
     *               when {@code lastSeenAt} is null
     * @param lastSeenAt the most recent live fix this call found, or null for a
     *                   courier with no live row at all (never tracked this
     *                   session, or tracked and swept after sign-off)
     */
    public record RosterEntry(
            CourierRosterRow courier,
            int activeAssignments,
            boolean online,
            @Nullable Instant lastSeenAt) {}

    public record CourierDetail(
            RosterEntry entry,
            ComplianceSummaryRow compliance,
            List<CourierGroupRow> groups,
            List<BranchBindingRow> branches) {}
}
