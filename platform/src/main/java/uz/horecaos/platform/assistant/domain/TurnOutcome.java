package uz.horecaos.platform.assistant.domain;

/** How a turn ended, as the ledger records it. */
public enum TurnOutcome {

    /** A grounded answer was sent. */
    ANSWERED,

    /** The assistant could not answer reliably, said so, and handed the conversation to a person. */
    REFUSED,

    /** The customer's topic is one a person handles; handed over without a model call. */
    ESCALATED,

    /** Nothing was sent: the turn was dropped by a rate limit. */
    DECLINED;

    /** Whether the conversation became a person's. */
    public boolean handedToPerson() {
        return this == REFUSED || this == ESCALATED;
    }
}
