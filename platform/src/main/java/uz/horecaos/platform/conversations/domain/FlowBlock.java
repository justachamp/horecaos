package uz.horecaos.platform.conversations.domain;

/**
 * ADR 0059's deliberately small block vocabulary: message, buttons,
 * input-to-field, delay, condition, operator handoff. Exactly these six —
 * {@link FlowDocumentParser} rejects an unknown {@code type} at authoring
 * time rather than accepting a seventh.
 */
public sealed interface FlowBlock
        permits MessageBlock, ButtonsBlock, InputToFieldBlock, DelayBlock, ConditionBlock, OperatorHandoffBlock {

    /** The YAML {@code type} discriminator this block was authored under. */
    String type();
}
