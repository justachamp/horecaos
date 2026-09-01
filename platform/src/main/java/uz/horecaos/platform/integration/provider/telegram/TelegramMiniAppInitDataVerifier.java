package uz.horecaos.platform.integration.provider.telegram;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Telegram Mini App {@code initData} verification (ADR 0058 stage 2) — the
 * gap ADR 0035 tracked as "server-side Telegram {@code initData} verification
 * does not exist anywhere in this codebase", closed here for customer chat
 * linking.
 *
 * <p>Telegram's official two-step HMAC-SHA-256 construction, implemented
 * exactly: {@code secret_key = HMAC_SHA256(key = "WebAppData", data =
 * botToken)}, then {@code computedHash = HMAC_SHA256(key = secret_key, data =
 * data_check_string)} — the alphabetically sorted {@code key=value} pairs of
 * every field except {@code hash} itself, joined with {@code \n}. Compared to
 * the received hash in constant time, the discipline every signature check in
 * this codebase follows ({@code HandoverCodeHasher}'s own reasoning applies
 * unchanged here).
 *
 * <p>{@code auth_date} freshness is checked separately from the signature: a
 * validly signed but stale payload is still a replay of whatever a device
 * sent hours ago, and the Mini App host re-issues {@code initData} on every
 * open, so there is no legitimate reason for an old one to arrive.
 */
@Component
public class TelegramMiniAppInitDataVerifier {

    private static final TypeReference<Map<String, Object>> USER_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration maxAge;

    public TelegramMiniAppInitDataVerifier(
            ObjectMapper objectMapper,
            Clock clock,
            // Telegram does not mandate a value; the Mini App re-issues
            // initData on every open, so this only ever needs to cover one
            // session's worth of clock skew and retry, not a day of reuse.
            @Value("${horecaos.notifications.telegram.miniapp-init-data-max-age:PT1H}") Duration maxAge) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /**
     * @param rawInitData the exact string {@code window.Telegram.WebApp.initData}
     *                     gave the client, unparsed and still URL-encoded
     * @param botToken the resolved bot token for the installation the
     *                 storefront claims this {@code initData} is for — a
     *                 payload that verifies against the wrong bot's token is
     *                 indistinguishable from a forged one, which is correct
     * @return the verified Telegram user id, or empty for a bad signature, a
     *         stale or missing {@code auth_date}, or a malformed payload —
     *         one answer for everything wrong, the same discipline every
     *         {@code /link}-family code resolution in this package follows
     */
    public Optional<Verified> verify(String rawInitData, String botToken) {
        Map<String, String> fields = parse(rawInitData);
        String receivedHash = fields.remove("hash");
        if (receivedHash == null || receivedHash.isBlank()) {
            return Optional.empty();
        }

        String dataCheckString = fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));

        byte[] secretKey =
                hmacSha256("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
        byte[] computed = hmacSha256(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
        String computedHex = HexFormat.of().formatHex(computed);

        if (!constantTimeEquals(computedHex, receivedHash.toLowerCase(java.util.Locale.ROOT))) {
            return Optional.empty();
        }

        String authDateRaw = fields.get("auth_date");
        if (authDateRaw == null) {
            return Optional.empty();
        }
        Instant authDate;
        try {
            authDate = Instant.ofEpochSecond(Long.parseLong(authDateRaw));
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        // isAfter rather than a tolerant window: an auth_date in the future
        // is not clock skew this platform should forgive, it is a value
        // nothing legitimate produces.
        if (authDate.isAfter(now) || Duration.between(authDate, now).compareTo(maxAge) > 0) {
            return Optional.empty();
        }

        String userJson = fields.get("user");
        if (userJson == null) {
            return Optional.empty();
        }
        long telegramUserId;
        try {
            Map<String, Object> user = objectMapper.readValue(userJson, USER_TYPE);
            if (!(user.get("id") instanceof Number id)) {
                return Optional.empty();
            }
            telegramUserId = id.longValue();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }

        return Optional.of(new Verified(telegramUserId, authDate));
    }

    private static Map<String, String> parse(String rawInitData) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : rawInitData.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            fields.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    private static boolean constantTimeEquals(String computedHex, String receivedHex) {
        return MessageDigest.isEqual(
                computedHex.getBytes(StandardCharsets.UTF_8), receivedHex.getBytes(StandardCharsets.UTF_8));
    }

    public record Verified(long telegramUserId, Instant authDate) {}
}
