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
    CLOSED
}
