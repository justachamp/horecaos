package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.util.List;

/**
 * The four things a reporting query is refused for (ADR 0043).
 *
 * <p>Each of them is a case where returning a number would be worse than
 * returning nothing, which is the only reason a read is ever refused.
 */
public final class ReportingRefusals {

    private ReportingRefusals() {}

    /**
     * A money figure was asked for without naming the legal entity.
     *
     * <p>ADR 0038 assigns fiscal identity per location and business date, so one
     * tenant can trade as two companies on the same evening. A tenant-grouped
     * revenue or tax total for such a tenant sums two taxpayers into a number that
     * reconciles to neither filing — and it is a figure somebody would otherwise
     * carry into a tax return. Operational cuts are unaffected: only money is
     * refused, and only when more than one entity is in range.
     */
    public static final class CombinedEntityTotalException extends IllegalArgumentException {

        private final List<String> metricCodes;

        public CombinedEntityTotalException(List<String> metricCodes, int entityCount) {
            super(("%s cannot be totalled across %d legal entities. Group by LEGAL_ENTITY: a "
                            + "combined total reconciles to neither tax filing (ADR 0038).")
                    .formatted(String.join(", ", metricCodes), entityCount));
            this.metricCodes = List.copyOf(metricCodes);
        }

        public List<String> metricCodes() {
            return metricCodes;
        }
    }

    /**
     * The range crosses a business-day boundary change that has not been recut.
     *
     * <p>Answering it would mix two definitions of the same Tuesday in one column
     * and nothing on the response would say so.
     */
    public static final class MixedBoundaryRegimeException extends IllegalArgumentException {

        private final LocalDate recutCompletedThrough;

        public MixedBoundaryRegimeException(LocalDate recutCompletedThrough) {
            super(("The range spans a business-day boundary change. The recut has reached %s; "
                            + "a range crossing it would mix two definitions of the same day "
                            + "(ADR 0043).")
                    .formatted(recutCompletedThrough));
            this.recutCompletedThrough = recutCompletedThrough;
        }

        public LocalDate recutCompletedThrough() {
            return recutCompletedThrough;
        }
    }

    /**
     * The metric is defined but its source fact is not built.
     *
     * <p>Refused rather than answered with zero. "We do not know" and "it is
     * nothing" are different answers and only one of them is honest; a zero on a
     * cost variance reads as a perfectly reconciled month.
     */
    public static final class MetricNotBuiltException extends IllegalArgumentException {

        private final String metricCode;

        public MetricNotBuiltException(String metricCode, String reason) {
            super("%s is defined but not built: %s".formatted(metricCode, reason));
            this.metricCode = metricCode;
        }

        public String metricCode() {
            return metricCode;
        }
    }

    /**
     * T14 (7.7a/7.7b): a classification run was asked for over a range
     * shorter than the 28-day floor.
     *
     * <p>The trap this exists to catch: the month preset is month-to-date, so
     * the closest available pill silently produces a four-day window on the
     * 4th of a month. statistics.md S2.7 is explicit that the floor is
     * refused rather than narrowed — "a Pareto over four days is an artefact
     * of one large party order" — so this throws instead of rounding the
     * range up or answering anyway.
     */
    public static final class RangeTooShortException extends IllegalArgumentException {

        private final int minimumDays;
        private final int actualDays;

        public RangeTooShortException(int minimumDays, int actualDays) {
            super(("A classification run needs at least %d days; the requested range is %d "
                            + "(statistics.md S2.7 — a shorter Pareto is an artefact of one large order).")
                    .formatted(minimumDays, actualDays));
            this.minimumDays = minimumDays;
            this.actualDays = actualDays;
        }

        public int minimumDays() {
            return minimumDays;
        }

        public int actualDays() {
            return actualDays;
        }
    }

    /**
     * The metric is not a single number per slice.
     *
     * <p>A median cannot be composed from per-slice medians and a distribution is
     * several rows, so neither fits the typed query's one-value-per-cell shape.
     * Both have their own endpoint. Silently omitting them would leave a report
     * rendering with a column missing, which reads as a quiet day.
     */
    public static final class NonScalarMetricException extends IllegalArgumentException {

        private final String metricCode;

        public NonScalarMetricException(String metricCode, String endpoint) {
            super("%s is not a single value per slice; read it from %s".formatted(metricCode, endpoint));
            this.metricCode = metricCode;
        }

        public String metricCode() {
            return metricCode;
        }
    }

    /**
     * T13 (7.6a): a {@code /queries} request named both a customer-type-grain
     * metric ({@code revenue.new_vs_returning.v1}) and a metric sourced from
     * {@code agg_branch_day}. The two are read from different fact tables by
     * different code paths and cannot share one slice, so answering both in
     * one call would either silently drop one or fabricate a combined slice
     * neither source actually produced.
     */
    public static final class MixedCustomerTypeGrainException extends IllegalArgumentException {

        private final List<String> metricCodes;

        public MixedCustomerTypeGrainException(List<String> metricCodes) {
            super(("%s mixes a customer-type-grain metric with one sourced from agg_branch_day. "
                            + "Request revenue.new_vs_returning.v1 on its own.")
                    .formatted(String.join(", ", metricCodes)));
            this.metricCodes = List.copyOf(metricCodes);
        }

        public List<String> metricCodes() {
            return metricCodes;
        }
    }

    /**
     * T13 (7.6a): a cohort/retention read asked for a wider window than the
     * read tracks. Every cohort read observes cohort formation and every
     * subsequent month of retention inside the same {@code [from, to]}
     * window ({@code CustomerCohortService.MAX_WINDOW_MONTHS}); a wider
     * window would either silently truncate retention for the earliest
     * cohorts or need data the request never bounded.
     */
    public static final class CohortRangeTooWideException extends IllegalArgumentException {

        private final int requestedMonths;
        private final int maxMonths;

        public CohortRangeTooWideException(int requestedMonths, int maxMonths) {
            super(("The cohort window covers %d months; the retention window tracks at most %d. " + "Narrow the range.")
                    .formatted(requestedMonths, maxMonths));
            this.requestedMonths = requestedMonths;
            this.maxMonths = maxMonths;
        }

        public int requestedMonths() {
            return requestedMonths;
        }

        public int maxMonths() {
            return maxMonths;
        }
    }
}
