package uz.horecaos.platform.conversations.domain;

/**
 * {@code conversations.conversations.state} (V0108). ADR 0059 names {@code
 * FLOW_ACTIVE}/{@code HANDED_TO_OPERATOR}/{@code CLOSED} explicitly ("incl.");
 * {@link #IDLE} is this build's addition for a conversation that exists —
 * a chat with no linked customer is still a conversation — but currently has
 * no run: before the first {@code /start}, and again once a run completes.
 */
public enum ConversationState {
    IDLE,
    FLOW_ACTIVE,
    HANDED_TO_OPERATOR,
    CLOSED;

    /**
     * Whether, in this state, the customer's next message is answered by a machine --
     * the flow engine or, since ADR 0069, a participant such as the assistant --
     * rather than by a person or by nobody. The one definition of "somebody could still
     * take this over from the machine": the inbox's «assistant is answering» marker and
     * the takeover rule both read it.
     */
    public boolean machineAnswers() {
        return this == IDLE || this == FLOW_ACTIVE;
    }
}
