package uz.horecaos.platform.conversations.api;

/**
 * @param value for {@link OutboundButtonKind#URL}, the link to open; for
 *              {@link OutboundButtonKind#CALLBACK}, the flow-local button key
 *              (never wrapped in {@link ConversationCallbackToken}'s prefix —
 *              the adapter applies that on the way out and strips it on the
 *              way back in, so this module never constructs a channel-shaped
 *              callback payload)
 */
public record OutboundButton(String label, OutboundButtonKind kind, String value) {}
