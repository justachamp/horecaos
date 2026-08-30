package uz.qoida.platform.courier.infrastructure.dispatch;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import uz.qoida.platform.courier.domain.ShiftStatus;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.qoida.platform.telemetry.api.CourierShiftPort;

/**
 * The shift ADR 0045 opens a duty session from, and the registration validity it
 * copies onto that session as evidence (ADR 0042, ADR 0045).
 *
 * <p>Until this class existed the port was unwired, {@code DutySessionService}
 * refused every open with {@code COURIER_SHIFT_NOT_WIRED}, and no courier
 * telemetry was collected anywhere. ADR 0049 made {@code COURIER_SHIFT_OPEN} a
 * capability a courier can actually hold, so authorization stopped being the
 * blocker and this seam became the only one left.
 *
 * <h2>Three refusals, and none of them is a policy decision made here</h2>
 *
 * <p><b>No open shift.</b> A duty session opens from a shift and never
 * independently, and only the courier opens a shift —
 * {@code ShiftTransition.OPEN} permits {@code COURIER} and nobody else, and
 * V0040's {@code ck_shift_open_source} says the same thing in the database. A
 * manager therefore cannot cause a courier to be tracked, which is the whole
 * arrangement: a manager who could open a shift could both fabricate paid hours
 * and start collecting a self-employed person's location.
 *
 * <p><b>A shift at another branch.</b> {@code locationId} must match. A courier
 * signed on at Chilonzor is not on duty at Yunusobod, and accepting the shift
 * anyway would attach a session — and its live pin — to a branch board he is
 * not working for.
 *
 * <p><b>An engagement that is not ACTIVE.</b> A lapsed or suspended registration
 * means the arrangement has stopped being a declared one. The date this answers
 * with is ADR 0042's {@code registration_valid_until}, and
 * {@code DutySessionService} compares it against its own clock and refuses a
 * session whose registration expired — so the expiry is checked twice, once
 * against the engagement's status and once against the calendar, which is
 * correct: a status is a fact somebody recorded and a date is a fact that
 * changes by itself overnight.
 *
 * <h2>What crosses the boundary</h2>
 *
 * <p>A shift id, a location, a brand, and a date. {@code protected_registration_ref}
 * is never selected — {@code JdbcCourierStore}'s ordinary engagement projection
 * deliberately omits the ciphertext column, and only the one reveal method reads
 * it. ADR 0045 requires a stored track or a registration number to be revealed
 * per person, for a declared purpose, with an audit entry; a validity date is
 * none of those things, and it is the smallest fact that answers "did somebody
 * check before collection started".
 *
 * <p>{@link #isWired()} keeps its default {@code true}. The stand-in answers
 * false so that {@code DutySessionService} can tell a courier "the platform
 * cannot check your registration yet" rather than "you are not on shift"; those
 * are different failures and a courier standing at a counter needs to know which
 * one he is looking at.
 */
@Component
public class CourierShiftAdapter implements CourierShiftPort {

    private final JdbcCourierShiftStore shifts;
    private final JdbcCourierStore couriers;

    public CourierShiftAdapter(JdbcCourierShiftStore shifts, JdbcCourierStore couriers) {
        this.shifts = shifts;
        this.couriers = couriers;
    }

    @Override
    public Optional<OpenShift> openShift(UUID tenantId, UUID courierId, UUID locationId) {
        // findLiveShift constrains on the tenant, so a courier id belonging to
        // another tenant finds nothing rather than finding that tenant's shift.
        Optional<ShiftRow> shift = shifts.findLiveShift(tenantId, courierId)
                // A courier who has asked to close is winding down. Collection
                // that outlives the intention to stop working is exactly what
                // ADR 0045 exists to prevent.
                .filter(row -> row.status() == ShiftStatus.OPEN)
                .filter(row -> row.locationId().equals(locationId));
        if (shift.isEmpty()) {
            return Optional.empty();
        }

        Optional<EngagementRow> engagement = couriers.findLiveEngagement(tenantId, courierId)
                .filter(row -> row.status().dispatchable())
                .filter(row -> row.registrationValidUntil() != null);
        if (engagement.isEmpty()) {
            return Optional.empty();
        }

        ShiftRow row = shift.get();
        return Optional.of(new OpenShift(row.id(), row.locationId(), row.brandId(),
                engagement.get().registrationValidUntil()));
    }
}
