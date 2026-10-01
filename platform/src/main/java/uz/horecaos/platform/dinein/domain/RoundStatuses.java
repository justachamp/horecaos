package uz.horecaos.platform.dinein.domain;

import java.util.Set;

/**
 * What an order's status means to a guest's claim on a table (ADR 0143, Decision 4).
 *
 * <p>The three classes are transcribed from ADR 0019's {@code ck_order_status}
 * rather than inverted from one of them, so that a status added there has to be
 * placed here on purpose; a test reads the live constraint and fails if any
 * status is in none of the three, or in two.
 *
 * <ul>
 *   <li><b>Accepted</b>: the restaurant has said yes. A claim with one of these on
 *       it is an ordinary session.
 *   <li><b>In flight</b>: nobody has said yes or no yet. The sweeper defers the
 *       decision, but only until {@code claim_expires_at + walk_in_payment_defer_minutes}.
 *   <li><b>Failed</b>: the round will never be accepted. It counts for nothing.
 * </ul>
 */
public final class RoundStatuses {

    private static final Set<String> ACCEPTED = Set.of("CONFIRMED", "PREPARING", "READY", "FULFILLING", "COMPLETED");

    private static final Set<String> IN_FLIGHT = Set.of("RECEIVED", "PAYMENT_AUTHORIZING", "AWAITING_APPROVAL");

    private static final Set<String> FAILED = Set.of("PAYMENT_FAILED", "REJECTED", "EXPIRED", "CANCELLED");

    private RoundStatuses() {}

    public static boolean accepted(String orderStatus) {
        return ACCEPTED.contains(orderStatus);
    }

    public static boolean inFlight(String orderStatus) {
        return IN_FLIGHT.contains(orderStatus);
    }

    public static boolean failed(String orderStatus) {
        return FAILED.contains(orderStatus);
    }

    /** Every status this class knows, for the test that compares it with the database. */
    public static Set<String> known() {
        Set<String> all = new java.util.TreeSet<>(ACCEPTED);
        all.addAll(IN_FLIGHT);
        all.addAll(FAILED);
        return Set.copyOf(all);
    }
}
