package uz.horecaos.platform.assistant.api;

import java.util.Map;
import java.util.Objects;

/**
 * One platform fact retrieved for a turn, as the model receives it (ADR 0069:
 * "retrieved facts are passed as structured context, not prose, so the model
 * composes rather than recalls").
 *
 * @param id         unique within the turn and stable for its life; what a reply
 *                   cites. Opaque to the model
 * @param kind       what sort of fact this is: {@code PRICE}, {@code
 *                   AVAILABILITY}, {@code BRANCH}, {@code HOURS}, {@code
 *                   COVERAGE}, {@code ORDER}, {@code KNOWLEDGE}
 * @param attributes the fact's own fields, already rendered for the customer in
 *                   the reply's language -- an amount is {@code "45 000 so'm"},
 *                   not 45000 -- so the model copies a value rather than
 *                   formatting one. Never a customer's personal data
 */
public record RetrievedFact(String id, String kind, Map<String, String> attributes) {

    public RetrievedFact {
        Objects.requireNonNull(id, "A fact needs an id");
        Objects.requireNonNull(kind, "A fact needs a kind");
        attributes = Map.copyOf(attributes);
    }
}
