package uz.horecaos.platform.ordering.api;

import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.ordering.domain.OrderStatus;

/**
 * What an order's status name means to a module that reads it as text (ADR 0168).
 *
 * <p>{@code kitchen} reads {@code ordering.orders.status} as a string through its own port and must not
 * keep a second list of the terminal statuses ({@code PAYMENT_FAILED}, {@code REJECTED}, {@code EXPIRED},
 * {@code COMPLETED}, {@code CANCELLED}); {@link OrderStatus#terminal()} owns it. A name this build does not
 * know answers {@code false}: an unrecognised status is never the reason a ticket is voided.
 */
public final class OrderStatusFacts {

    private OrderStatusFacts() {}

    /** Whether no transition leaves this status: a correction is a new order. */
    public static boolean isTerminal(@Nullable String status) {
        if (status == null) {
            return false;
        }
        try {
            return OrderStatus.valueOf(status).terminal();
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
