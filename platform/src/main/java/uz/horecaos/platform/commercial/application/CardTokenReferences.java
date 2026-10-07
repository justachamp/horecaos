package uz.horecaos.platform.commercial.application;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The form a card reference takes on {@code tenant_billing} (ADR 0095): the
 * platform card installation it was minted under, then the provider's own token.
 *
 * <p>A token is only meaningful to the merchant account that minted it. Without
 * the installation in the reference, replacing the account (a new merchant
 * agreement, a provider switch) would send every stored token to an account that
 * has never heard of it, and each charge would fail as a decline that looks like
 * the cardholder's fault. With it, the gateway can say what is actually wrong.
 *
 * <p>A reference without the prefix is one a staff member typed; it is passed to
 * the active adapter as it stands.
 */
public final class CardTokenReferences {

    private static final int UUID_LENGTH = 36;

    private CardTokenReferences() {}

    public static String compose(UUID installationId, String providerToken) {
        return installationId + ":" + providerToken;
    }

    /** The installation and the provider token, or empty when the reference was typed by hand. */
    public static Optional<Parsed> parse(@Nullable String reference) {
        if (reference == null || reference.length() <= UUID_LENGTH + 1 || reference.charAt(UUID_LENGTH) != ':') {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parsed(
                    UUID.fromString(reference.substring(0, UUID_LENGTH)), reference.substring(UUID_LENGTH + 1)));
        } catch (IllegalArgumentException notComposed) {
            return Optional.empty();
        }
    }

    /** What a composed reference holds. {@code toString} hides the token. */
    public record Parsed(UUID installationId, String providerToken) {
        @Override
        public String toString() {
            return "Parsed[installation=" + installationId + "]";
        }
    }
}
