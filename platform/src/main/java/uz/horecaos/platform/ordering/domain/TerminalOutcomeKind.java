package uz.horecaos.platform.ordering.domain;

import java.util.Optional;

/**
 * The terminal fact an order ended in (ADR 0039).
 *
 * <p>The legacy system records one status and one free-text {@code cancel_reason}
 * for a customer who changed their mind, a restaurant that refused, and an
 * approval nobody answered. Those are three commercial facts with three different
 * stock consequences and three different liable parties, and a report cannot pull
 * them apart afterwards. This enum is what makes them different rows.
 */
public enum TerminalOutcomeKind {
    COMPLETED,
    CANCELLED,
    REJECTED,
    EXPIRED,

    /**
     * Terminal in the ADR 0019 machine and recognised here so a row for it is not
     * refused, but nothing writes one today: the payment-first checkout path is a
     * known ADR 0013 gap and no order reaches the status.
     */
    PAYMENT_FAILED;

    /** The outcome kind for a status, or empty when the status is not terminal. */
    public static Optional<TerminalOutcomeKind> of(OrderStatus status) {
        if (!status.terminal()) {
            return Optional.empty();
        }
        return Optional.of(valueOf(status.name()));
    }
}
