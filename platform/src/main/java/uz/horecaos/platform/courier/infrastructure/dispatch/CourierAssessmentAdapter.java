package uz.horecaos.platform.courier.infrastructure.dispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.courier.application.CourierDispatchGate;
import uz.horecaos.platform.courier.domain.ShiftEnforcement;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.fulfillment.api.CourierAssessmentPort;

/**
 * ADR 0172's answer to "may a person hand this courier an order": the platform's one
 * eligibility definition, {@link CourierDispatchGate}, read for a <em>person's</em>
 * choice (ADR 0042).
 *
 * <p>Decides nothing the gate does not. The gate's refusals are returned whole, with one
 * deliberate difference: {@code OUTSIDE_DISTANCE_BAND} is a ranking input for the
 * automated lane, and a dispatcher who sends a scooter across town has decided to, so
 * here it is a note. A missing shift is a refusal where the branch enforces shifts
 * (the gate says so) and a note where it does not.
 *
 * <p>Declared by fulfillment and implemented here, beside {@link InternalFleetAdapter}:
 * fulfillment never acquires a dependency on this module.
 */
@Component
public class CourierAssessmentAdapter implements CourierAssessmentPort {

    static final String OUTSIDE_DISTANCE_BAND = "OUTSIDE_DISTANCE_BAND";
    static final String NO_OPEN_SHIFT = "NO_OPEN_SHIFT";

    private final CourierDispatchGate gate;
    private final JdbcCourierStore couriers;
    private final JdbcCourierShiftStore shifts;

    public CourierAssessmentAdapter(CourierDispatchGate gate, JdbcCourierStore couriers, JdbcCourierShiftStore shifts) {
        this.gate = gate;
        this.couriers = couriers;
        this.shifts = shifts;
    }

    @Override
    public Assessment assess(UUID tenantId, UUID brandId, UUID locationId, UUID courierId, int distanceMeters) {
        CourierDispatchGate.Eligibility verdict =
                gate.evaluate(tenantId, brandId, locationId, courierId, distanceMeters);

        List<String> blocking = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String refusal : verdict.refusals()) {
            if (OUTSIDE_DISTANCE_BAND.equals(refusal)) {
                notes.add(refusal);
            } else {
                blocking.add(refusal);
            }
        }

        int ceiling = couriers.findCourier(tenantId, courierId)
                .flatMap(row -> couriers.findType(tenantId, row.courierTypeId()))
                .map(JdbcCourierStore.CourierTypeRow::maxConcurrentAssignments)
                .orElse(0);

        // The gate keeps a missing shift only as a refusal under ENFORCED, and says nothing
        // about it otherwise. A person assigning under ADVISORY or OFF still wants to know.
        boolean onlyShiftRefusals = blocking.stream().allMatch(NO_OPEN_SHIFT::equals);
        boolean eligible =
                blocking.isEmpty() || (verdict.enforcementMode() != ShiftEnforcement.ENFORCED && onlyShiftRefusals);
        if (eligible) {
            if (blocking.contains(NO_OPEN_SHIFT)
                    || (verdict.enforcementMode() != ShiftEnforcement.ENFORCED
                            && shifts.findLiveShift(tenantId, courierId).isEmpty())) {
                notes.add(NO_OPEN_SHIFT);
            }
            return new Assessment(true, List.of(), distinct(notes), ceiling);
        }
        return new Assessment(false, blocking, distinct(notes), ceiling);
    }

    private static List<String> distinct(List<String> codes) {
        return codes.stream().distinct().toList();
    }
}
