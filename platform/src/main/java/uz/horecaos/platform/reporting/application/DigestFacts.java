package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;

/**
 * One tenant's closed business day, in the shape a Telegram digest needs (ADR
 * 0043, ADR 0058).
 *
 * @param businessDate       the day these totals belong to
 * @param ordersCompleted    completed orders, {@code orders.count.v1}'s filter
 * @param ordersCancelled    cancelled/rejected/expired orders that day
 * @param grossRevenueSom    {@code revenue.gross.v1}: pre-discount, completed only
 * @param netRevenueSom      {@code revenue.net.v1}: gross minus discount, refunds excluded here
 * @param refundedSom        refunds attributed to this business date, whatever day the order was
 * @param hasOpenDivergences whether the settle recut has flagged this day and not been resolved —
 *                           a digest says so rather than presenting the number as settled
 */
public record DigestFacts(
        LocalDate businessDate,
        long ordersCompleted,
        long ordersCancelled,
        long grossRevenueSom,
        long netRevenueSom,
        long refundedSom,
        boolean hasOpenDivergences) {}
