package uz.horecaos.platform.integration.provider.telegram;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The opaque code behind {@code /link <code>} (ADR 0058, ADR 0044).
 *
 * <p>96 bits from a CSPRNG, Base64url without padding — sixteen characters, short
 * enough to paste into a Telegram message and well under ADR 0044's 64-character
 * deep-link payload bound, though this handshake is not itself a {@code /start}
 * deep link: it is typed (or pasted) as a command inside the group being linked,
 * because that is the only way Telegram tells the bot which chat the operator
 * means. Stored as plaintext in {@code integration.telegram_pending_links} rather
 * than hashed like {@code BearerToken}: unlike a session credential, redeeming
 * this code only creates an operations-alert subscription in a tenant that
 * already exists, it expires quickly, and it is single-use — the blast radius of
 * a leaked row is an unwanted alert group, not an account takeover.
 */
public final class TelegramLinkCode {

    private static final int CODE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TelegramLinkCode() {}

    public static String generate() {
        byte[] material = new byte[CODE_BYTES];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }
}
