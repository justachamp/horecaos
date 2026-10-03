package uz.horecaos.platform.ordering.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@code ordering.payment_window} policy document (ADR 0142 Decision 7, ADR 0030): how long an
 * order may sit in {@code PAYMENT_AUTHORIZING} before it reaches the stuck list, and what is done then.
 *
 * <p>It belongs to {@code ordering}, not to the dispatch rules. A dispatch plan does not exist for an
 * unpaid order -- {@code DeliveryPlanTrigger} opens one only on {@code OrderConfirmed}, and a
 * payment-first order is confirmed only after capture -- so a window written into the dispatch
 * document would make {@code fulfillment} own a rule about {@code PAYMENT_AUTHORIZING}, inverting the
 * dependency that trigger exists to keep. It is shown on the same console screen because an operator
 * thinks of it as one question: what happens to an order nobody has paid for.
 *
 * <p>Until now the window was a deploy property
 * ({@code horecaos.ordering.workers.payment.stale-after}, thirty minutes). That property survives as the
 * fallback when no document is published, so nothing changes until someone does.
 *
 * @param windowMinutes whole minutes, one to a day
 * @param action        what the sweep does once the window has passed
 */
public record PaymentWindowPolicy(int windowMinutes, Action action) {

    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 1440;

    /** What happens to an order still unpaid after the window. */
    public enum Action {

        /** The order reaches the stuck list ({@code MANUAL_ACTION_REQUIRED}) for a person to look at. Today's behaviour, and the only one available. */
        FLAG_ONLY,

        /**
         * The order is cancelled. A named value that is <strong>refused at publish</strong> until
         * ADR 0019's open input -- "checkout payment timing, cancellation, approval timeout" -- is
         * answered by product, and until finance and product settle what a payment captured after the
         * cancellation does (the void path exists; whether a late capture may revive the order is a
         * money-and-promise question the record does not settle).
         */
        CANCEL
    }

    public PaymentWindowPolicy {
        Objects.requireNonNull(action, "A payment window needs an action");
    }

    public Duration window() {
        return Duration.ofMinutes(windowMinutes);
    }

    /** Why this document may not be published, as sentences; empty when it may. */
    public List<String> violations() {
        List<String> found = new ArrayList<>();
        if (windowMinutes < MIN_MINUTES || windowMinutes > MAX_MINUTES) {
            found.add("windowMinutes must be between %d and %d".formatted(MIN_MINUTES, MAX_MINUTES));
        }
        if (action == Action.CANCEL) {
            found.add("Cancelling an unpaid order is not available yet: product has to answer ADR 0019's open "
                    + "question on checkout payment timing and cancellation first, so the window only decides "
                    + "when an order reaches the stuck list");
        }
        return List.copyOf(found);
    }
}
