package uz.horecaos.platform.ordering.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the modules that come after the pass may know about an order's marking codes (ADR 0161).
 *
 * <p>Consumer-declared and implemented by {@code ordering}, which owns {@code ordering.order_line_marks}:
 * {@code fiscal} and {@code payments} each import {@code ordering.api} already, and {@code ordering}
 * imports neither, which {@code ModularArchitectureTests} asserts. That direction is the whole reason the
 * table lives in {@code ordering} and not in the {@code fiscal} schema ADR 0038 sketched.
 *
 * <p>Two reads, of two different strengths. {@link #markCounts} is for the fiscal worklist and for
 * reopening a blocked document: counts only, nothing decrypted, no reference to a code. {@link
 * #receiptSource} is for the receipt projection in {@code payments} and nowhere else: it carries the
 * protected envelope of every live code, which the one caller decrypts once per document under a recorded
 * purpose. No method here returns a code, and no screen shows one.
 */
public interface OrderMarksPort {

    /**
     * The table and column an envelope is bound to ({@code RecordRef}), so the caller that decrypts one
     * can rebuild the associated data without ordering's schema leaking into it.
     */
    String CODE_TABLE = "ordering.order_line_marks";

    String CODE_COLUMN = "code_encrypted";

    /**
     * Per marked line of the order: how many units it needs and how many live marks it holds.
     *
     * <p>Empty for an order with no marked line (the usual case) and for a tenant whose {@code
     * feature.marked_goods} flag is off. A line that already holds a live mark stays marked whatever the
     * catalog later says.
     */
    List<LineMarkCount> markCounts(UUID tenantId, UUID orderId);

    /**
     * Every live line of the order with its marks, for building a receipt that carries the codes.
     *
     * <p>Empty {@code marked} lines everywhere (and so no reason to project) for an order that is not a
     * marked order; the caller checks {@link ReceiptSource#hasMarkedLine()} before doing any work.
     */
    ReceiptSource receiptSource(UUID tenantId, UUID orderId);

    /** @param required units the line needs scanned; @param captured live marks it holds */
    record LineMarkCount(UUID lineId, int required, int captured) {

        public boolean complete() {
            return captured >= required;
        }
    }

    /** @param lines every live line of the order, in line order */
    record ReceiptSource(UUID brandId, String currency, List<ReceiptLine> lines) {

        public boolean hasMarkedLine() {
            return lines.stream().anyMatch(ReceiptLine::marked);
        }
    }

    /**
     * One live order line as a receipt needs it.
     *
     * @param marked      whether the line is a marked one (the catalog says so now, or it already holds a live mark)
     * @param requiredMarks units that must be scanned when {@code marked}, else zero
     * @param marks       the live marks in sequence order; empty for an unmarked line
     * @param taxAmountMinor the quote's recorded tax share, never recomputed
     */
    record ReceiptLine(
            UUID lineId,
            UUID variantId,
            String productName,
            BigDecimal quantity,
            long finalAmountMinor,
            long taxAmountMinor,
            boolean marked,
            int requiredMarks,
            List<MarkRef> marks) {}

    /**
     * A live mark's identity and protected envelope.
     *
     * @param protectedCode the serialised ADR 0029 envelope; revealing it is a decision the caller records
     */
    record MarkRef(UUID markId, int sequence, String gtin, String protectedCode) {

        @Override
        public String toString() {
            return "MarkRef[markId=" + markId + ", sequence=" + sequence + "]";
        }
    }
}
