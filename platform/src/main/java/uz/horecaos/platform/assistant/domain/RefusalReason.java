package uz.horecaos.platform.assistant.domain;

/**
 * Why the assistant declined to answer. Stable codes: they are recorded on the
 * turn, written to the audit trail, counted in the metrics and tested for, so a
 * rename is a migration of all four. A refusal is a first-class outcome of the
 * design, not a failure mode (ADR 0069), and each of these has its own test.
 */
public enum RefusalReason {

    /** Nothing retrieved answers the question: no matching dish, branch, order or knowledge entry. */
    NO_GROUNDING,

    /** The model replied, but with something its facts do not support -- an uncited reply, a foreign citation, a figure no fact contains. */
    UNGROUNDED_REPLY,

    /** The model itself said the facts do not answer the question, or declined. */
    MODEL_REFUSED,

    /** The provider could not be reached, or refused. */
    PROVIDER_UNAVAILABLE,

    /** The tenant's monthly spend ceiling is reached (ADR 0069: "Spend is capped"). */
    SPEND_CEILING,

    /** The conversation has had as many assistant turns as it may in a day. */
    TURN_CAP,

    /** The plan's included assistant turns are used up and the commercial mode refuses more. */
    ENTITLEMENT_LIMIT,

    /** Too many questions too fast; nothing is sent. */
    RATE_LIMITED
}
