package uz.qoida.platform.telemetry.api;

/**
 * The two numbers ADR 0045's retention floor is derived from (ADR 0042).
 *
 * <p>Thirty days is not a number somebody liked. The only thing downstream of a
 * courier's track is a dispute, and a dispute about a delivery is argued against
 * the settlement statement its period produced. A statement is issued at period
 * close and challenged afterwards, and ADR 0042 never reopens a closed period, so
 * evidence that expires before the close plus the dispute window is a figure
 * nobody can check — which is worse than no evidence, because it looks like
 * evidence until somebody asks for it.
 *
 * <pre>
 * track_retention_days &gt;= settlement_period_days + statement_dispute_days
 * </pre>
 *
 * <p>Both terms belong to ADR 0042, which owns the settlement calendar. Reading
 * them through a port rather than copying them into a telemetry configuration key
 * is what keeps the floor derived: when finance lengthens the settlement period,
 * the floor moves with it and the next production start refuses a retention that
 * has silently become too short. A copied constant would not.
 *
 * <p>Until ADR 0042 ships, the stand-in answers the pilot's 7 and 7 and says so
 * once at startup. That is honest — it is the calendar ADR 0045 states the pilot
 * runs — and it is why the check still has something to check.
 */
public interface SettlementCalendarPort {

    /** Days in one settlement period. */
    int settlementPeriodDays();

    /** Days a courier has to dispute the statement that period produced. */
    int statementDisputeDays();

    /** Whether ADR 0042 is supplying these, or the pilot's stand-in is. */
    default boolean isWired() {
        return true;
    }
}
