package uz.horecaos.platform.conversations.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whole-document invariants {@link FlowDocumentParser} cannot see one state at
 * a time: every transition target actually exists, and no state chain that
 * advances on its own (no wait for a tap, free text, or a delay) can loop
 * forever.
 *
 * <p>A broken flow is rejected here, at authoring time — never discovered only
 * when a customer's {@code /start} hits it (ADR 0059's own authoring
 * discipline).
 */
public final class FlowDocumentValidator {

    private FlowDocumentValidator() {}

    /** @throws FlowDocumentException carrying every problem found, if any */
    public static void validate(FlowDocument document) {
        List<String> problems = new ArrayList<>();

        if (!document.states().containsKey(document.startState())) {
            problems.add("startState \"%s\" is not a declared state".formatted(document.startState()));
        }
        if (document.states().containsKey(FlowDocument.END)) {
            problems.add("A state must not be named \"%s\" — that id is reserved to mean the flow ends here"
                    .formatted(FlowDocument.END));
        }

        for (FlowState state : document.states().values()) {
            for (String target : targetsOf(state.block())) {
                if (!isValidTarget(document, target)) {
                    problems.add("State \"%s\" points at \"%s\", which is not a declared state (or \"%s\")"
                            .formatted(state.id(), target, FlowDocument.END));
                }
            }
        }

        if (problems.isEmpty()) {
            String cycle = findAutoAdvanceCycle(document);
            if (cycle != null) {
                problems.add(
                        ("States %s form a cycle with no exit — none of them waits for a tap, free text, or a delay, "
                                        + "so the engine would loop forever")
                                .formatted(cycle));
            }
        }

        if (!problems.isEmpty()) {
            throw new FlowDocumentException(problems);
        }
    }

    /** Every state (or {@link FlowDocument#END}) this block's transitions can name. */
    private static List<String> targetsOf(FlowBlock block) {
        return switch (block) {
            case MessageBlock message -> message.next() == null ? List.of() : List.of(message.next());
            case ButtonsBlock buttons ->
                buttons.buttons().stream()
                        .filter(button -> button.kind() == FlowButtonKind.CALLBACK)
                        .map(FlowButton::next)
                        .filter(java.util.Objects::nonNull)
                        .toList();
            case InputToFieldBlock inputToField -> List.of(inputToField.next());
            case DelayBlock delay -> List.of(delay.next());
            case ConditionBlock condition -> List.of(condition.whenTrue(), condition.whenFalse());
            case OperatorHandoffBlock handoff -> handoff.next() == null ? List.of() : List.of(handoff.next());
        };
    }

    private static boolean isValidTarget(FlowDocument document, String target) {
        return FlowDocument.END.equals(target) || document.states().containsKey(target);
    }

    /**
     * The auto-advancing subgraph: message's unconditional {@code next} and
     * condition's two branches, since both execute immediately without
     * waiting for anything external. Buttons, input-to-field, and delay all
     * stop the chain — a cycle that passes through one of them is fine,
     * because the engine is not spinning while it waits.
     *
     * @return a readable list of the states forming a cycle, or null if none
     */
    private static @Nullable String findAutoAdvanceCycle(FlowDocument document) {
        Map<String, List<String>> autoAdvanceEdges = new java.util.LinkedHashMap<>();
        for (FlowState state : document.states().values()) {
            autoAdvanceEdges.put(state.id(), autoAdvanceTargets(state.block()));
        }

        Set<String> visiting = new LinkedHashSet<>();
        Set<String> settled = new LinkedHashSet<>();
        for (String start : autoAdvanceEdges.keySet()) {
            Deque<String> path = new ArrayDeque<>();
            String cycle = dfs(start, autoAdvanceEdges, visiting, settled, path);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static @Nullable String dfs(
            String state,
            Map<String, List<String>> edges,
            Set<String> visiting,
            Set<String> settled,
            Deque<String> path) {
        if (settled.contains(state)) {
            return null;
        }
        if (visiting.contains(state)) {
            List<String> cycleStates = new ArrayList<>(path);
            int start = cycleStates.indexOf(state);
            return cycleStates.subList(start, cycleStates.size()).toString();
        }
        visiting.add(state);
        path.addLast(state);
        for (String next : edges.getOrDefault(state, List.of())) {
            String found = dfs(next, edges, visiting, settled, path);
            if (found != null) {
                return found;
            }
        }
        path.removeLast();
        visiting.remove(state);
        settled.add(state);
        return null;
    }

    private static List<String> autoAdvanceTargets(FlowBlock block) {
        return switch (block) {
            case MessageBlock message ->
                message.next() == null || FlowDocument.END.equals(message.next()) ? List.of() : List.of(message.next());
            case ConditionBlock condition ->
                java.util.stream.Stream.of(condition.whenTrue(), condition.whenFalse())
                        .filter(target -> !FlowDocument.END.equals(target))
                        .toList();
            case ButtonsBlock ignored -> List.of();
            case InputToFieldBlock ignored -> List.of();
            case DelayBlock ignored -> List.of();
            case OperatorHandoffBlock ignored -> List.of();
        };
    }
}
