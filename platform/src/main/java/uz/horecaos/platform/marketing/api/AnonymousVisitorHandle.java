package uz.horecaos.platform.marketing.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An opaque stand-in for a visitor, for the ADR 0033 limiter and nothing else (ADR 0167 Decision 5).
 *
 * <p>A keyed hash of whatever the surface can see of the visitor (a client address, a Telegram user id), under a key
 * minted at process start and never persisted. The handle therefore means nothing outside this process's sixty-second
 * limiter window: it cannot be correlated across restarts, with another surface, or with any table. The {@code source}
 * namespaces the input so that a Telegram user id and an address string can never collide.
 */
public final class AnonymousVisitorHandle {

    private static final String HMAC = "HmacSHA256";
    private static final byte[] PROCESS_KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(PROCESS_KEY);
    }

    private AnonymousVisitorHandle() {}

    /**
     * @param source who is speaking: {@code "ip"} for a client address, {@code "tg"} for a Telegram user
     * @param value what that source calls the visitor
     */
    public static String of(String source, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(PROCESS_KEY, HMAC));
            return HexFormat.of().formatHex(mac.doFinal((source + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(HMAC + " is unavailable", impossible);
        }
    }
}
