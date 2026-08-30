package uz.horecaos.platform.courier.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.DutyState;
import uz.horecaos.platform.courier.domain.EngagementStatus;
import uz.horecaos.platform.courier.domain.ShiftEnforcement;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Whether this courier may be offered this order (ADR 0042, ADR 0014).
 *
 * <p>ADR 0014 owns sourcing and the assignment attempt; this owns the half of
 * the {@code INTERNAL_COURIER} decision that is about the courier rather than
 * about the order, and hands back a decision an attempt can be stamped with.
 *
 * <p>The answer is never a bare boolean. Every refusal names its reason, because
 * the failure mode of a dispatch gate is a dispatcher watching a fleet of six
 * with nobody eligible and no way to find out why — and the answer at that
 * moment is usually "his registration expired this morning", which is a fact
 * somebody can act on in a minute.
 */
@Service
public class CourierDispatchGate {

    private final JdbcCourierStore couriers;
    private final JdbcCourierShiftStore shifts;
    private final CourierPolicyResolver policies;

    public CourierDispatchGate(
            JdbcCourierStore couriers, JdbcCourierShiftStore shifts, CourierPolicyResolver policies) {
        this.couriers = couriers;
        this.shifts = shifts;
        this.policies = policies;
    }

    public Eligibility evaluate(UUID tenantId, UUID brandId, UUID locationId, UUID courierId, int distanceMeters) {

        ResolvedPolicy<CourierCompensationPolicy> policy =
                policies.resolveWithIdentity(ResourceScope.location(tenantId, brandId, locationId));
        ShiftEnforcement enforcement = policy.document().shiftEnforcement();
        List<String> refusals = new ArrayList<>();

        Optional<CourierRow> courier = couriers.findCourier(tenantId, courierId);
        if (courier.isEmpty() || !"ACTIVE".equals(courier.get().status())) {
            refusals.add("COURIER_NOT_ACTIVE");
            return new Eligibility(false, refusals, enforcement, policy.policyId(), policy.policyVersion());
        }

        Optional<EngagementRow> engagement = couriers.findLiveEngagement(tenantId, courierId);
        if (engagement.isEmpty() || !engagement.get().status().dispatchable()) {
            // The whole compliance lever. Work already accepted still finishes,
            // and every som already accrued is still owed and still paid: this
            // refuses new work and touches nothing else.
            refusals.add(engagement
                    .filter(row -> row.status() == EngagementStatus.SUSPENDED_COMPLIANCE)
                    .map(row -> "REGISTRATION_LAPSED")
                    .orElse("ENGAGEMENT_NOT_ACTIVE"));
        }

        Optional<ShiftRow> shift = shifts.findLiveShift(tenantId, courierId);
        if (shift.isEmpty()) {
            if (enforcement == ShiftEnforcement.ENFORCED) {
                refusals.add("NO_OPEN_SHIFT");
            }
        } else if (shift.get().dutyState() != DutyState.AVAILABLE) {
            // Refused under every enforcement mode. ON_BREAK is not a policy
            // question: a courier on break is not assignable whatever the branch
            // decided about shifts, and ADR 0045 is not even collecting his
            // position.
            refusals.add("DUTY_STATE_" + shift.get().dutyState().name());
        }

        courier.flatMap(row -> couriers.findType(tenantId, row.courierTypeId())).ifPresent(type -> {
            if (!withinBand(type, distanceMeters)) {
                refusals.add("OUTSIDE_DISTANCE_BAND");
            }
        });

        // ADVISORY computes the same decision and refuses nothing, so the gate's
        // false negatives appear in a report rather than as couriers unable to
        // work during a dinner rush. That is the rollout order ADR 0042 states.
        boolean eligible =
                refusals.isEmpty() || (enforcement != ShiftEnforcement.ENFORCED && onlyShiftRefusals(refusals));

        return new Eligibility(eligible, List.copyOf(refusals), enforcement, policy.policyId(), policy.policyVersion());
    }

    private static boolean onlyShiftRefusals(List<String> refusals) {
        return refusals.stream().allMatch("NO_OPEN_SHIFT"::equals);
    }

    private static boolean withinBand(CourierTypeRow type, int distanceMeters) {
        if (distanceMeters < type.minDistanceMeters()) {
            return false;
        }
        return type.maxDistanceMeters() == null || distanceMeters <= type.maxDistanceMeters();
    }

    /**
     * @param enforcementMode   snapshotted onto the assignment attempt by the
     *                          caller, together with the policy version, so a
     *                          later policy change cannot restate a past decision
     * @param refusals          empty when eligible; carries the reasons otherwise,
     *                          including under ADVISORY where they refuse nothing
     */
    public record Eligibility(
            boolean eligible,
            List<String> refusals,
            ShiftEnforcement enforcementMode,
            UUID policyId,
            int policyVersion) {}
}
