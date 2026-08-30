package uz.qoida.platform.telemetry.domain;

import java.util.List;
import java.util.Objects;

/**
 * The rule that makes thirty days derived rather than picked (ADR 0045).
 *
 * <pre>
 * track_retention_days &gt;= settlement_period_days + statement_dispute_days
 * </pre>
 *
 * <p>Pure, and separate from the startup check that applies it, because the
 * argument behind the number is the part worth testing exhaustively and it has
 * nothing to do with a database or a Spring profile.
 *
 * <p>The floor exists in both directions. Below it, a track expires before the
 * settlement period it evidences, and the first draft of ADR 0045 — 72 hours —
 * was exactly that: an answer to a dispute raised the next day and to nothing
 * raised against a statement. Far above it, an operational tool becomes a
 * movement history of identified individuals accumulating for no purpose, which
 * is the failure ADR 0029's {@code PERSONAL_SENSITIVE} class exists to prevent.
 * The configured 30 carries the longest period ADR 0030 is expected to allow,
 * plus its dispute window, plus a margin for a claim that arrives late, and is
 * deliberately shorter than that class's provisional six-month default.
 */
public final class TrackRetentionFloor {

    /**
     * The value ADR 0045 configures, and the code default of the ADR 0030 key.
     *
     * <p>At the pilot's 7-day period and 7-day dispute window the floor is 14, so
     * this is not the floor with a rounding on top; it is the floor for a
     * calendar the platform has not been asked for yet.
     */
    public static final int CONFIGURED_TRACK_RETENTION_DAYS = 30;

    /**
     * Past this, holding a track is holding a movement archive because storage is
     * cheap. Breaching it is not a startup failure — a legal obligation could
     * legitimately require it — but it is reported at every start, so nobody
     * discovers a six-month courier history by finding the rows.
     */
    public static final int REVIEW_CEILING_DAYS = 90;

    private TrackRetentionFloor() {
    }

    /** The minimum retention a calendar of this shape can be evidenced with. */
    public static int floorDays(int settlementPeriodDays, int statementDisputeDays) {
        if (settlementPeriodDays <= 0 || statementDisputeDays <= 0) {
            throw new IllegalArgumentException(
                    "A settlement period and a dispute window are both positive day counts");
        }
        return settlementPeriodDays + statementDisputeDays;
    }

    /**
     * Checks one configured retention against the calendar behind it.
     *
     * @param origin where the value came from, so a breach names the tenant or
     *               brand that set it rather than saying "some row"
     */
    public static Verdict check(String origin, int retentionDays,
            int settlementPeriodDays, int statementDisputeDays) {

        Objects.requireNonNull(origin, "An origin is required");
        int floor = floorDays(settlementPeriodDays, statementDisputeDays);

        if (retentionDays < floor) {
            return new Verdict(origin, retentionDays, floor, Outcome.BELOW_FLOOR,
                    ("%s retains courier tracks for %d days, below the %d-day floor "
                            + "(settlement period %d + statement dispute window %d). A track that "
                            + "expires before the period it evidences is worse than no track, "
                            + "because it looks like evidence until somebody asks for it.")
                            .formatted(origin, retentionDays, floor,
                                    settlementPeriodDays, statementDisputeDays));
        }
        if (retentionDays > REVIEW_CEILING_DAYS) {
            return new Verdict(origin, retentionDays, floor, Outcome.ABOVE_REVIEW_CEILING,
                    ("%s retains courier tracks for %d days, past the %d-day review ceiling. "
                            + "Nothing reads a track past the dispute window, so this is a "
                            + "movement history of identified people kept for no stated purpose.")
                            .formatted(origin, retentionDays, REVIEW_CEILING_DAYS));
        }
        return new Verdict(origin, retentionDays, floor, Outcome.WITHIN_FLOOR, null);
    }

    /** Checks every configured value at once, returning only what is wrong. */
    public static List<Verdict> problems(List<Verdict> verdicts) {
        return verdicts.stream().filter(Verdict::isProblem).toList();
    }

    public enum Outcome {
        WITHIN_FLOOR,
        BELOW_FLOOR,
        ABOVE_REVIEW_CEILING
    }

    /**
     * @param explanation null when the value is fine; otherwise the sentence a
     *                    startup failure or a report prints, written so the
     *                    reader learns why the floor exists rather than only that
     *                    it was breached
     */
    public record Verdict(String origin, int retentionDays, int floorDays,
            Outcome outcome, String explanation) {

        public boolean isProblem() {
            return outcome != Outcome.WITHIN_FLOOR;
        }

        /** Only a value below the floor refuses a production start. */
        public boolean refusesStartup() {
            return outcome == Outcome.BELOW_FLOOR;
        }
    }
}
