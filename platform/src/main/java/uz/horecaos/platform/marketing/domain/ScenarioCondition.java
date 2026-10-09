package uz.horecaos.platform.marketing.domain;

/**
 * The two closed sets a step's conditions are drawn from (ADR 0112).
 *
 * <p>Deliberately not an expression language. A scenario step may ask one question of
 * a guest, whether they have ordered since entering, and everything the evaluator can
 * be wrong about is this list. Richer predicates are a decision about a richer model
 * (ADR 0112's accepted trade-off on how many a marketer arriving from a mature CVM
 * platform will ask for), not something to grow by accident in a string column.
 */
public final class ScenarioCondition {

    private ScenarioCondition() {}

    /** Whether to go on to a step at all. A guest for whom it is false ends {@code STOPPED_BY_CONDITION}. */
    public enum Continuation {
        ALWAYS,
        NO_ORDER_SINCE_ENTRY
    }

    /** Whether the whole scenario is over for the guest, checked before each step. */
    public enum Stop {
        NONE,
        ORDER_PLACED_SINCE_ENTRY
    }
}
