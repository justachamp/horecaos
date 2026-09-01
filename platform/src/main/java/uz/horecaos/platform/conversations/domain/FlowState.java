package uz.horecaos.platform.conversations.domain;

/**
 * One named position in a {@link FlowDocument}: an id and the block executed
 * on arrival.
 */
public record FlowState(String id, FlowBlock block) {}
