package uz.horecaos.platform.conversations.api;

import java.util.Optional;

/**
 * The namespace an adapter's {@code CALLBACK}-kind button payload is wrapped
 * in, so a tap can never be mistaken for a different subsystem's own
 * callback-data token.
 *
 * <p>Provably collision-free with every token this platform already mints
 * through opaque, random generation ({@code TelegramLinkCode.generate()}'s
 * Base64url alphabet, for one): {@link #PREFIX} contains a colon, a character
 * no Base64url-without-padding token can ever contain. A caller does not need
 * to consult a store to know a token is (or is not) this module's — the
 * prefix alone decides it, which is what lets the adapter check it before,
 * rather than after, an existing token store lookup that would otherwise
 * answer "not found" for a flow's own button and describe it as an expired
 * action.
 */
public final class ConversationCallbackToken {

    public static final String PREFIX = "cvb:";

    private ConversationCallbackToken() {}

    public static String wrap(String key) {
        return PREFIX + key;
    }

    public static Optional<String> unwrap(String callbackData) {
        return callbackData.startsWith(PREFIX)
                ? Optional.of(callbackData.substring(PREFIX.length()))
                : Optional.empty();
    }
}
