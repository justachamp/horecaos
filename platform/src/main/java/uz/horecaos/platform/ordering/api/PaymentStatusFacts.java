package uz.horecaos.platform.ordering.api;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the seven values of {@code ordering.orders.payment_status_projection} mean, said once for the
 * modules that can share one definition (ADR 0168 Decision 2).
 *
 * <p>Two questions, never one, and neither is "is the order paid":
 *
 * <ul>
 *   <li>{@link #moneyIsHeld}: HorecaOS has the money. {@code AUTHORIZED} and {@code CAPTURED}. A cash
 *       order is {@code NOT_REQUIRED}, collected at the door, and is <em>not</em> held; neither is
 *       money that has not arrived.
 *   <li>{@link #moneyRequiredAndAbsent}: payment is required before the kitchen and has not arrived.
 *       {@code PENDING} and {@code FAILED} (a failed attempt leaves the money required and absent; the
 *       customer may retry). Never {@code NOT_REQUIRED}, whatever else is true, which is what keeps
 *       a cash order, a dine-in tab and a weighed order paid by an online method after the weighing
 *       (ADR 0153) from ever being held. {@code VOIDED} and {@code REFUNDED} mean money moved back, a
 *       remedy on a live order and not a reason to starve its kitchen.
 * </ul>
 *
 * <p>A value this class does not know answers {@code false} to both, so a status added later gates
 * nothing and holds nothing until a record says it should (the fail-open rule). {@code fulfillment}
 * keeps two private copies of {@link #moneyIsHeld} because it may not import {@code ordering} without
 * closing {@code fulfillment -> ordering -> pricing -> fulfillment}; {@code PaymentStatusParityTests}
 * pins all the copies together over every value the column allows.
 */
public final class PaymentStatusFacts {

    /** The values {@link #moneyIsHeld} answers true for, for a caller that reads them as SQL parameters. */
    public static final Set<String> MONEY_HELD = Set.of("AUTHORIZED", "CAPTURED");

    /** The values {@link #moneyRequiredAndAbsent} answers true for, for a caller that reads them as SQL parameters. */
    public static final Set<String> REQUIRED_AND_ABSENT = Set.of("PENDING", "FAILED");

    private PaymentStatusFacts() {}

    /** HorecaOS holds the money: authorised or captured. */
    public static boolean moneyIsHeld(@Nullable String paymentStatusProjection) {
        return paymentStatusProjection != null && MONEY_HELD.contains(paymentStatusProjection);
    }

    /** Payment is required before the kitchen and has not arrived: pending, or failed and retryable. */
    public static boolean moneyRequiredAndAbsent(@Nullable String paymentStatusProjection) {
        return paymentStatusProjection != null && REQUIRED_AND_ABSENT.contains(paymentStatusProjection);
    }
}
