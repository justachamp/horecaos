package uz.horecaos.platform.assistant.domain;

/**
 * The kinds of platform read a question may need (ADR 0069: "classify the
 * question, resolve scope ..., then fetch").
 *
 * <p>Each kind names a read against the module that owns the fact, never a
 * topic the model is trusted to know: a price is ADR 0018's, availability is ADR
 * 0016's and ADR 0017's, branches and hours are ADR 0036's, coverage is ADR
 * 0037's, an order is ADR 0019's.
 */
public enum RetrievalKind {
    PRICE,
    AVAILABILITY,
    BRANCHES,
    HOURS,
    COVERAGE,
    ORDER_STATUS,
    /** The tenant's own authored answers. Attempted for every question. */
    KNOWLEDGE
}
