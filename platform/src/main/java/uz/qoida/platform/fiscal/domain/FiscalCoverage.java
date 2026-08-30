package uz.qoida.platform.fiscal.domain;

import java.time.Instant;

/**
 * How much of a window's trade actually has a provider receipt (ADR 0038).
 *
 * <p>ADR 0038 states the uncomfortable number twice, once under Consequences and
 * once under Cash, because it is the thing a reader must not be able to miss:
 * <strong>provider fiscalization covers the minority of orders</strong> until the
 * {@code TERMINAL} responsibility lands. Cash is enabled in the legacy and is this
 * market's dominant tender, and every cash order carries {@code NOT_APPLICABLE}.
 *
 * <p>So this record has no field called "coverage percent". A single figure would
 * have to decide whether a cash order counts as covered, and both answers are
 * wrong: counted, it reports an unreceipted majority as healthy; excluded, it
 * reports a compliant restaurant as failing. The two are reported side by side
 * instead — {@link #issuedBasisPoints()} against {@link #notApplicableBasisPoints()}
 * — and {@link #unreceipted()} counts what is genuinely owed and missing.
 *
 * <p>Basis points, integer, for the same reason money is minor units: a rounded
 * double is how a report shows 100% coverage on a day that had one missing
 * receipt in five hundred.
 */
public record FiscalCoverage(
        Instant from,
        Instant to,
        long saleDocuments,
        long issued,
        long notApplicable,
        long notApplicableCash,
        long blocked,
        long failed,
        long awaitingProvider,
        boolean partnerFiscalizationWired) {

    private static final int BASIS_POINTS = 10_000;

    /**
     * Documents that owe a receipt and do not have one.
     *
     * <p>Blocked, failed, and still waiting. {@code NOT_APPLICABLE} is
     * deliberately not in this number: those orders owe a receipt from the
     * restaurant's own equipment, which is a different problem with a different
     * owner, and folding the two together produces a figure that no action can
     * move.
     */
    public long unreceipted() {
        return blocked + failed + awaitingProvider;
    }

    /** The share with a provider receipt on file. */
    public int issuedBasisPoints() {
        return share(issued);
    }

    /**
     * The share that no payment provider can receipt at all.
     *
     * <p>Overwhelmingly cash. This is the figure that must appear beside the
     * issued share on any report, or the report is describing a different
     * business than the one being run.
     */
    public int notApplicableBasisPoints() {
        return share(notApplicable);
    }

    /** The share that owes a receipt and has not got one. */
    public int unreceiptedBasisPoints() {
        return share(unreceipted());
    }

    /**
     * Whether the provider path is the minority of this window's trade.
     *
     * <p>Rendered on the report rather than left to a reader's arithmetic, because
     * ADR 0038 predicts this will be true for the whole pilot and a prediction
     * nobody checks is a prediction nobody notices coming true.
     */
    public boolean providerPathIsMinority() {
        return saleDocuments > 0 && issued * 2 < saleDocuments;
    }

    private int share(long part) {
        if (saleDocuments <= 0) {
            return 0;
        }
        return Math.toIntExact(part * BASIS_POINTS / saleDocuments);
    }
}
