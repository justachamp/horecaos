package uz.horecaos.platform.pos.domain;

/**
 * Whether an entity's absence from a run is a removal or a missed read
 * (ADR 0012).
 *
 * <p>The provider offers offset pagination and nothing else — no cursor, no
 * keyset, no snapshot isolation, no ETag. Paging by page number over a table the
 * restaurant is editing can skip a row: insert a product while we are reading
 * page two, page three shifts by one, and a product that exists is never read.
 *
 * <p>To the difference engine a product that was never read looks exactly like a
 * product that was deleted. So a single run's absence is an observation, and a
 * {@link SyncDifference.DifferenceCategory#REMOVAL_SIGNAL} needs
 * {@link #REQUIRED_AGREEING_RUNS} consecutive runs that agree.
 *
 * <p>The cost is one extra catalog read before a removal is actionable. The
 * alternative cost is a review queue full of phantom removals, and that is worse
 * than it sounds: ADR 0012 already refuses to delete on a removal signal, so a
 * phantom's direct blast radius is only a queue item — but a queue that is
 * usually wrong is a queue an operator learns to approve without reading, and
 * then the real removal goes through the same way.
 *
 * <p>A walk that provably cannot skip rows short-circuits this. If the provider
 * ever becomes sortable by identifier, paging by {@code id > last_seen} makes the
 * walk stable under inserts and one absence is then evidence. The flag is
 * carried on the run rather than assumed, so the day that changes it is a
 * normalizer change and not a rewrite of this class.
 */
public final class RemovalQuorum {

    /**
     * Two, not three. Two independent offset races skipping the same row is
     * already implausible; three would double the latency of every genuine
     * removal for a marginal gain, and a menu item that stays sellable for two
     * extra days after the kitchen stopped making it has its own cost.
     */
    public static final int REQUIRED_AGREEING_RUNS = 2;

    private RemovalQuorum() {}

    /**
     * Whether a streak of absences is enough to treat an entity as removed.
     *
     * @param consecutiveAbsentRuns how many consecutive runs have failed to see
     *                              this entity, including the current one
     * @param everyWalkStable       whether every run in that streak used a paging
     *                              strategy that cannot skip rows
     */
    public static boolean actionable(int consecutiveAbsentRuns, boolean everyWalkStable) {
        if (consecutiveAbsentRuns <= 0) {
            return false;
        }
        if (everyWalkStable) {
            // A walk that cannot skip rows means absence is absence, and making a
            // restaurant wait a second day to remove a discontinued dish would be
            // caution about a risk that does not exist here.
            return true;
        }
        return consecutiveAbsentRuns >= REQUIRED_AGREEING_RUNS;
    }

    /** What to tell an operator about an absence that is not yet actionable. */
    public static String inconclusiveReason(int consecutiveAbsentRuns) {
        return ("Absent from %d of the %d consecutive runs a removal needs. "
                        + "The provider pages by offset, so a single absence may be a row the "
                        + "walk skipped rather than a product the restaurant deleted.")
                .formatted(consecutiveAbsentRuns, REQUIRED_AGREEING_RUNS);
    }
}
