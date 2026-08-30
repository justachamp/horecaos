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

    private ReportingRefusals() {
    }

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
                    + "(ADR 0043).").formatted(recutCompletedThrough));
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
            super("%s is not a single value per slice; read it from %s"
                    .formatted(metricCode, endpoint));
            this.metricCode = metricCode;
        }

        public String metricCode() {
            return metricCode;
        }
    }
}
