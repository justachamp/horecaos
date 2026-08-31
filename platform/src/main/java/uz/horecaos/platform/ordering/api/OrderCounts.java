package uz.horecaos.platform.ordering.api;

/**
 * The board's tab badges, exposed to another module (orders.md §2.3).
 *
 * <p>Its own type rather than {@code JdbcOrderStore.OrderCountsRow}: that record
 * lives in an internal package, and a consumer outside {@code ordering} gets
 * counts, never an order or a process-state type — the same rule {@code
 * ordering.api}'s package doc already states.
 */
public record OrderCounts(
        long newOrders,
        long awaitingApproval,
        long inKitchen,
        long ready,
        long fulfilling,
        long completed,
        long cancelled,
        long totalNonTerminal,
        long total) {}
