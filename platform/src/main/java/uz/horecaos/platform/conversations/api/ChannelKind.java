package uz.horecaos.platform.conversations.api;

/**
 * The channel a conversation runs over. {@code TELEGRAM} is the only value an
 * adapter exists for (ADR 0059 stage 1); the type exists so the engine and
 * storage are channel-neutral in shape from day one, per the ADR's own "the
 * core is channel-neutral; only the Telegram adapter is built."
 */
public enum ChannelKind {
    TELEGRAM
}
