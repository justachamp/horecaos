package uz.horecaos.platform.content.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * An accumulator for what is wrong with a document, capped so a hostile body cannot make the
 * response as large as itself (ADR 0157, the typed documents).
 */
public final class DocumentProblems {

    /** Enough to fix a form in one round trip; more is noise. */
    public static final int LIMIT = 40;

    private final List<FieldProblem> problems = new ArrayList<>();

    public void add(String field, String code, String message) {
        if (problems.size() < LIMIT) {
            problems.add(new FieldProblem(field, code, message));
        }
    }

    public boolean isEmpty() {
        return problems.isEmpty();
    }

    public List<FieldProblem> all() {
        return List.copyOf(problems);
    }

    /** Thrown when a document is not the grammar. Carries every problem found, up to {@link #LIMIT}. */
    public static final class InvalidDocumentException extends RuntimeException {

        private final transient List<FieldProblem> problems;

        public InvalidDocumentException(List<FieldProblem> problems) {
            super("The document is not valid (" + problems.size() + " problem(s))", null, false, false);
            this.problems = List.copyOf(problems);
        }

        public List<FieldProblem> problems() {
            return problems;
        }
    }
}
