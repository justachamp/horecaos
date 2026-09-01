package uz.horecaos.platform.conversations.domain;

import java.util.Map;
import java.util.Optional;

/**
 * A parsed, validated flow: a workflow name, its states, and where it starts.
 * Never constructed except by {@link FlowDocumentParser} followed by {@link
 * FlowDocumentValidator} — nothing else may hand the engine a {@code
 * FlowDocument} that has not passed both.
 *
 * @param flowKey the stable name this flow is authored under
 * @param name a human-readable label for the authoring console
 * @param startState the state a new run begins at
 * @param states every state, keyed by id
 */
public record FlowDocument(String flowKey, String name, String startState, Map<String, FlowState> states) {

    /**
     * The sentinel a block's {@code next} (or a condition/button branch) names
     * instead of a real state id to mean "the flow ends here" — never a key in
     * {@link #states()}.
     */
    public static final String END = "END";

    public Optional<FlowState> state(String id) {
        return Optional.ofNullable(states.get(id));
    }
}
