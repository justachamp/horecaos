package uz.horecaos.platform.conversations.domain;

import java.util.List;

/**
 * A flow document that cannot be authored — malformed YAML, an unknown block
 * type, a transition naming a state that does not exist, or a cycle with no
 * exit. Carries every problem found, not just the first, because a document
 * with three mistakes should not make an author fix them one submission at a
 * time.
 */
public final class FlowDocumentException extends RuntimeException {

    private final List<String> problems;

    public FlowDocumentException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public FlowDocumentException(String problem) {
        this(List.of(problem));
    }

    /** Every problem found, each an actionable, standalone sentence. */
    public List<String> problems() {
        return problems;
    }
}
