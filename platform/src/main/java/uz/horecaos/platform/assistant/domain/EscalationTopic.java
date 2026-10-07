package uz.horecaos.platform.assistant.domain;

/**
 * Topics a person must handle (ADR 0069: "a complaint or refund topic ... or a
 * customer asking for a person all hand the conversation to ADR 0059's operator
 * inbox"). Decided before any retrieval and before any model call: nothing about
 * a refund is worth an inference, and nothing about a complaint is worth the risk
 * of a fluent reply.
 */
public enum EscalationTopic {
    COMPLAINT,
    REFUND,
    HUMAN_REQUESTED
}
