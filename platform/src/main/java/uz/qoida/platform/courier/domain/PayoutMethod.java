package uz.qoida.platform.courier.domain;

/**
 * How the tenant settles what it owes (ADR 0042).
 *
 * <p>Qoida computes, approves, and records the payout; it does not move the
 * money. {@link #CASH_AT_BRANCH} is the common case here — the courier keeps
 * cash he already collected — and recording it makes a real settlement entry out
 * of what would otherwise be an off-books arrangement.
 */
public enum PayoutMethod {

    CASH_AT_BRANCH,
    BANK_TRANSFER,
    CARD_TRANSFER
}
