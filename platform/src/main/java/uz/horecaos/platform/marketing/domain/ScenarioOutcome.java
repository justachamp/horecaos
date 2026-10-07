package uz.horecaos.platform.marketing.domain;

/**
 * How a guest's run through a scenario ended (ADR 0112). A guest still in progress
 * has no outcome at all, which is a different thing from every value here.
 */
public enum ScenarioOutcome {
    COMPLETED,
    STOPPED_BY_CONDITION,

    /** ADR 0015 no longer has a positive decision. Consent is read, never re-decided. */
    STOPPED_BY_CONSENT_WITHDRAWN,

    /** ADR 0044: an active suppression, which outranks consent. */
    STOPPED_BY_SUPPRESSION
}
