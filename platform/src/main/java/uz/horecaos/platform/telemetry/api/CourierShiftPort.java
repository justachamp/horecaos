package uz.horecaos.platform.telemetry.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The one thing this module asks of ADR 0042 before it collects anything
 * (ADR 0045).
 *
 * <p>A duty session opens from a shift and never independently. That sentence is
 * in both ADRs and it is the reason this interface exists rather than a column: a
 * courier is tracked only inside the hours they themselves chose to work, so the
 * window has to be somebody else's fact, and ADR 0042 owns it along with the
 * self-employment registration record and its validity.
 *
 * <p>Two refusals, and they are different failures. A shift that is not open
 * means the courier is not working and nothing should be collected. A
 * registration that is absent or expired means the arrangement has stopped being
 * a declared one, and the platform is the only party positioned to notice — ADR
 * 0045 does not fix that, it only refuses to dispatch and to collect until ADR
 * 0042's record is valid again.
 *
 * <p>Until ADR 0042 ships an implementation, {@code UnwiredCourierShiftPort}
 * stands in behind {@code @ConditionalOnMissingBean} and refuses every open. That
 * is the house pattern for a known gap and it is also the correct direction to
 * fail: a stand-in that said yes would collect a self-employed person's location
 * with nothing checked, which is precisely the arrangement ADR 0045's privacy
 * analysis is written to avoid.
 */
public interface CourierShiftPort {

    /**
     * The shift a duty session may be opened from, or empty when there is none.
     *
     * @param locationId the branch the session is being opened at, so a shift at
     *                   another branch is not accepted as this one's
     */
    Optional<OpenShift> openShift(UUID tenantId, UUID courierId, UUID locationId);

    /**
     * The shift a duty session may open from, and the registration fact checked
     * to allow it.
     *
     * @param registrationValidUntil the day ADR 0042's self-employment
     *                               registration lapses. Copied onto the session
     *                               so the check that was made is evidence rather
     *                               than a claim.
     */
    record OpenShift(UUID shiftId, UUID locationId, UUID brandId, LocalDate registrationValidUntil) {}

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }

    /** The reason code a refused duty session carries while this port is unwired. */
    String NOT_WIRED_REASON = "COURIER_SHIFT_NOT_WIRED";
}
