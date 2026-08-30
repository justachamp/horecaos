package uz.qoida.platform.payments.infrastructure.payme;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import uz.qoida.platform.payments.domain.PaymentAttemptStatus;
import uz.qoida.platform.payments.domain.SomAmount;
import uz.qoida.platform.payments.domain.TiyinAmount;

/**
 * One transaction as Payme needs to read it back (ADR 0013).
 *
 * <p>Assembled for {@code CheckTransaction} and {@code GetStatement}, which want
 * the same fields and differ only in how many rows and whether the account is
 * included. Keeping them on one shape is what stops the two methods drifting into
 * reporting different states for the same transaction — which is the failure that
 * makes a reconciliation argument unwinnable.
 *
 * @param paymeTransactionId Payme's own 24-character id, which Payme minted and
 *                           Qoida stored. Identity runs the other way on Click
 * @param orderReference     the attempt's {@code merchant_trans_id}, which is what
 *                           went out in the checkout link as {@code account.order_id}
 * @param paymeCreatedAt     {@code params.time}: Payme's creation moment, the only
 *                           clock the twelve-hour expiry may be measured from, and
 *                           the field {@code GetStatement} filters on. Not Qoida's
 *                           {@code create_time}, which is the next field down
 * @param createTime         when the transaction was created in merchant billing
 * @param reason             Payme's cancellation reason, verbatim, or null for a
 *                           transaction that was never cancelled
 */
public record PaymeTransactionView(
        UUID attemptId,
        UUID tenantId,
        String paymeTransactionId,
        String orderReference,
        Instant paymeCreatedAt,
        SomAmount amount,
        PaymentAttemptStatus status,
        String reason,
        Instant createTime,
        Instant performTime,
        Instant cancelTime) {

    /**
     * The merchant-side transaction number.
     *
     * <p>Documented as "the merchant's choice of format". The attempt id is used
     * because it is the row a support conversation will be about, and because
     * anything derived from an order number would leak how many orders the
     * restaurant has taken.
     */
    public String merchantTransactionNumber() {
        return attemptId.toString();
    }

    public int state() {
        return PaymeState.of(status)
                .orElseThrow(() -> new IllegalStateException(
                        "A Payme transaction is attached to an attempt in " + status
                                + ", which Payme has no state for"))
                .code();
    }

    /** The {@code CheckTransaction} result. */
    public Map<String, Object> asTransactionState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("create_time", millis(createTime));
        result.put("perform_time", millis(performTime));
        result.put("cancel_time", millis(cancelTime));
        result.put("transaction", merchantTransactionNumber());
        result.put("state", state());
        result.put("reason", reason == null ? null : Integer.valueOf(reason));
        return result;
    }

    /**
     * One row of {@code GetStatement}.
     *
     * <p>The account object must be reproduced in the shape it arrived, because
     * that is how Payme matches a statement row back to its own receipt.
     * {@code receivers} is omitted rather than sent as null: the cashbox owner is
     * the sole receiver on every Qoida payment, and the docs say it may be omitted
     * in that case.
     */
    public Map<String, Object> asStatementRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", paymeTransactionId);
        row.put("time", millis(paymeCreatedAt));
        // The one multiplication by 100, and it is the type that guarantees it: a
        // som figure cannot be passed where TiyinAmount is built.
        row.put("amount", TiyinAmount.of(amount).value());
        row.put("account", PaymeAccount.of(orderReference));
        row.put("create_time", millis(createTime));
        row.put("perform_time", millis(performTime));
        row.put("cancel_time", millis(cancelTime));
        row.put("transaction", merchantTransactionNumber());
        row.put("state", state());
        row.put("reason", reason == null ? null : Integer.valueOf(reason));
        return row;
    }

    /**
     * Zero for an unset timestamp.
     *
     * <p>The docs' own {@code CheckTransaction} example shows {@code cancel_time: 0}
     * beside {@code reason: null}, so the two unset markers are genuinely different
     * and both templates get the timestamp one wrong by emitting null. Open
     * question U13: this follows the docs, and a sandbox can settle it.
     */
    private static long millis(Instant at) {
        return at == null ? 0L : at.toEpochMilli();
    }
}
