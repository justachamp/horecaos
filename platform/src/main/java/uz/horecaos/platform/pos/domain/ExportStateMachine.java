package uz.horecaos.platform.pos.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The transitions an export may make, and the ones it may not (ADR 0011).
 *
 * <p>Held in code rather than only in a CHECK constraint for the same reason
 * {@code OrderStateMachine} is: a database can refuse an impossible row, but it
 * cannot tell a caller which move it should have made instead, and the answer to
 * "why did this export not go again" is the single most important thing this
 * class says.
 *
 * <p>The absent edge is the design. There is no path from {@link
 * ExportState#UNCERTAIN} to {@link ExportState#SENT}, and none from {@link
 * ExportState#AWAITING_OPERATOR} either. On a provider with no idempotency key,
 * such an edge is a licence for any retry loop — a Camel redelivery, an outbox
 * republish, an operator pressing a button twice — to print a second kitchen
 * ticket for food nobody ordered.
 */
public final class ExportStateMachine {

    private static final Map<ExportState, Set<ExportState>> ALLOWED = allowed();

    private ExportStateMachine() {
    }

    public static boolean permits(ExportState from, ExportState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * @throws IllegalStateException naming both states, because a transition
     *                               rejection that does not say what was refused
     *                               is a log line somebody has to reproduce
     */
    public static void require(ExportState from, ExportState to) {
        if (!permits(from, to)) {
            throw new IllegalStateException(
                    "A POS export cannot move from %s to %s".formatted(from, to));
        }
    }

    private static Map<ExportState, Set<ExportState>> allowed() {
        Map<ExportState, Set<ExportState>> map = new EnumMap<>(ExportState.class);

        map.put(ExportState.PENDING, EnumSet.of(
                ExportState.SENT,
                // A person may abandon before anything is sent — the branch took
                // the order by telephone while the export was still queued.
                ExportState.ABANDONED));

        map.put(ExportState.SENT, EnumSet.of(
                ExportState.ACCEPTED,
                ExportState.REJECTED,
                ExportState.UNCERTAIN));

        map.put(ExportState.UNCERTAIN, EnumSet.of(
                // Reached only when the provider handed our own correlation
                // reference back on a candidate. Then the match is an identifier
                // and not a heuristic.
                ExportState.RESOLVED_LANDED,
                ExportState.AWAITING_OPERATOR,
                ExportState.ABANDONED));

        map.put(ExportState.AWAITING_OPERATOR, EnumSet.of(
                ExportState.RESOLVED_LANDED,
                ExportState.RESOLVED_ABSENT,
                ExportState.ABANDONED));

        // The one place a second attempt is permitted, and it is permitted
        // because somebody established that the first one did not land.
        map.put(ExportState.RESOLVED_ABSENT, EnumSet.of(
                ExportState.SENT,
                ExportState.ABANDONED));

        map.put(ExportState.ACCEPTED, EnumSet.noneOf(ExportState.class));
        map.put(ExportState.REJECTED, EnumSet.noneOf(ExportState.class));
        map.put(ExportState.RESOLVED_LANDED, EnumSet.noneOf(ExportState.class));
        map.put(ExportState.ABANDONED, EnumSet.noneOf(ExportState.class));

        return Map.copyOf(map);
    }
}
