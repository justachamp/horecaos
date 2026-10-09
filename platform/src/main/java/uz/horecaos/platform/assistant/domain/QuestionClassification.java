package uz.horecaos.platform.assistant.domain;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a question was recognised as: the platform reads it needs, and the words
 * left over once the question itself is taken out -- which is what the customer
 * called the dish.
 *
 * @param kinds       the retrievals to attempt; always contains {@link RetrievalKind#KNOWLEDGE}
 * @param escalation  set when the topic is one a person handles, in which case
 *                    nothing is retrieved and no model is asked
 * @param dishTerms   skeleton words ({@code SearchText}) that are neither filler
 *                    nor part of what the customer is asking, in the order typed
 * @param skeleton    the whole question's matching skeleton, for knowledge search and cache keys
 */
public record QuestionClassification(
        Set<RetrievalKind> kinds, @Nullable EscalationTopic escalation, List<String> dishTerms, String skeleton) {

    public QuestionClassification {
        kinds = Set.copyOf(kinds);
        dishTerms = List.copyOf(dishTerms);
    }

    public boolean escalates() {
        return escalation != null;
    }

    /** Whether the question needs the live menu: a price or availability, with a dish named or not. */
    public boolean needsMenu() {
        return kinds.contains(RetrievalKind.PRICE) || kinds.contains(RetrievalKind.AVAILABILITY);
    }
}
