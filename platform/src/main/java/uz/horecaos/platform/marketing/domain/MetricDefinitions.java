package uz.horecaos.platform.marketing.domain;

/**
 * Which ADR 0043 definitions the projection was computed under (ADR 0044).
 *
 * <p>A constant in code rather than a row, for the reason ADR 0043 gives for its
 * own registry: a definition that can be changed with an UPDATE is a definition
 * two surfaces can disagree about, and the whole point of stamping this version
 * onto every projection row is that "average check" in an audience and "average
 * check" on a dashboard can be shown to mean the same thing.
 *
 * <p>Version 1 is ADR 0043's provisional registry. Its open question — the signed
 * treatment of cancelled and refunded orders — is finance's to answer through
 * ADR 0043 rather than this module's, which is why the projection carries
 * {@code order_count} beside {@code completed_order_count} and
 * {@code gross_spend_minor} beside {@code net_spend_minor}. A registry revision
 * bumps this number and restates the projection; it does not reshape it.
 */
public final class MetricDefinitions {

    /** ADR 0043's registry version 1, provisional and stamped on every row. */
    public static final int CURRENT_VERSION = 1;

    private MetricDefinitions() {}
}
