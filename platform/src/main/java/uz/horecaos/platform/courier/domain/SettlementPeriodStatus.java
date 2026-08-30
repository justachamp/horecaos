package uz.horecaos.platform.courier.domain;

/**
 * The settlement period lifecycle (ADR 0042).
 *
 * <p>A closed period is never reopened, because reopening changes a figure
 * somebody has already been paid against. Anything arriving afterwards lands in
 * the next open period as a {@code PRIOR_PERIOD_ADJUSTMENT} keeping its original
 * occurrence instant, which accountants understand and couriers find confusing
 * on a statement — a stated cost of refusing to reopen.
 */
public enum SettlementPeriodStatus {

    OPEN,
    CLOSING,
    CLOSED,
    SETTLED;

    public boolean acceptsEntries() {
        return this == OPEN;
    }
}
