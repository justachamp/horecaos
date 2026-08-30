package uz.horecaos.platform.tenancy.domain.channel;

/**
 * A location's manual override of its own timetable (ADR 0036).
 *
 * <pre>
 * FOLLOW_SCHEDULE -&gt; FORCE_CLOSED    (manual, reason required)
 * FOLLOW_SCHEDULE -&gt; FORCE_OPEN      (manual, reason required)
 * FORCE_CLOSED    -&gt; FOLLOW_SCHEDULE (manual reopen, or effective_until elapses)
 * FORCE_OPEN      -&gt; FOLLOW_SCHEDULE (manual, or effective_until elapses)
 * </pre>
 *
 * <p>{@link #FORCE_OPEN} skips the dated exception and the weekly window and
 * nothing else. A manager overriding hours does not thereby override an
 * entitlement, an empty menu, or the kitchen ceiling — those refusals are not
 * about the clock and a manager cannot fix them by deciding to be open.
 */
public enum ServiceMode {
    FOLLOW_SCHEDULE,
    FORCE_OPEN,
    FORCE_CLOSED
}
