package uz.horecaos.platform.dinein.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The two dine-in state machines, transcribed from ADR 0047.
 *
 * <p>Code and not configuration, for the same reason {@code OrderStateMachine} and
 * {@code KitchenStateMachine} are: a tenant chooses whether it takes bookings, and
 * never which transitions exist.
 *
 * <p>Two edges are worth naming because they look like mistakes.
 * {@code SETTLING -> OPEN} is a card that declined, or a party that ordered one
 * more round after asking for the bill; without it a failed payment would strand
 * a table in a status nothing can leave. And {@code OPEN -> CLOSED} without ever
 * settling is a session opened on the wrong table, or a party that sat down and
 * left before ordering — closing it is not a walkout and must not require the
 * force-close grant, which is why the application checks the balance rather than
 * this table.
 */
public final class DineInStateMachine {

    private static final Map<ReservationStatus, Set<ReservationStatus>> RESERVATION =
            reservationTransitions();
    private static final Map<SessionStatus, Set<SessionStatus>> SESSION = sessionTransitions();

    private DineInStateMachine() {
    }

    private static Map<ReservationStatus, Set<ReservationStatus>> reservationTransitions() {
        Map<ReservationStatus, Set<ReservationStatus>> transitions =
                new EnumMap<>(ReservationStatus.class);

        transitions.put(ReservationStatus.REQUESTED, EnumSet.of(
                ReservationStatus.CONFIRMED, ReservationStatus.REJECTED,
                // A guest who books and calls back ten minutes later has not been
                // rejected by the restaurant, and reporting that reads a rejection
                // rate would be wrong about every one of them.
                ReservationStatus.CANCELLED));
        transitions.put(ReservationStatus.CONFIRMED, EnumSet.of(
                ReservationStatus.SEATED, ReservationStatus.CANCELLED,
                ReservationStatus.NO_SHOW));
        transitions.put(ReservationStatus.SEATED, EnumSet.of(ReservationStatus.COMPLETED));

        for (ReservationStatus status : ReservationStatus.values()) {
            if (status.terminal()) {
                transitions.put(status, EnumSet.noneOf(ReservationStatus.class));
            }
        }
        return Map.copyOf(transitions);
    }

    private static Map<SessionStatus, Set<SessionStatus>> sessionTransitions() {
        Map<SessionStatus, Set<SessionStatus>> transitions = new EnumMap<>(SessionStatus.class);

        transitions.put(SessionStatus.OPEN, EnumSet.of(
                SessionStatus.BILL_REQUESTED, SessionStatus.CLOSED, SessionStatus.FORCE_CLOSED));
        transitions.put(SessionStatus.BILL_REQUESTED, EnumSet.of(
                SessionStatus.SETTLING, SessionStatus.OPEN, SessionStatus.FORCE_CLOSED));
        transitions.put(SessionStatus.SETTLING, EnumSet.of(
                SessionStatus.CLOSED, SessionStatus.OPEN, SessionStatus.FORCE_CLOSED));

        for (SessionStatus status : SessionStatus.values()) {
            if (status.terminal()) {
                transitions.put(status, EnumSet.noneOf(SessionStatus.class));
            }
        }
        return Map.copyOf(transitions);
    }

    public static boolean permits(ReservationStatus from, ReservationStatus to) {
        return RESERVATION.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean permits(SessionStatus from, SessionStatus to) {
        return SESSION.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<ReservationStatus> nextFor(ReservationStatus from) {
        return RESERVATION.getOrDefault(from, Set.of());
    }

    public static Set<SessionStatus> nextFor(SessionStatus from) {
        return SESSION.getOrDefault(from, Set.of());
    }
}
