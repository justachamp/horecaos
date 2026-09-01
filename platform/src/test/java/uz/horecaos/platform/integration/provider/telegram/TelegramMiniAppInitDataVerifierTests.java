package uz.horecaos.platform.integration.provider.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.provider.telegram.TelegramMiniAppInitDataVerifier.Verified;

/**
 * Telegram Mini App {@code initData} verification (ADR 0058 stage 2), against
 * a vector this test builds itself using Telegram's official two-step
 * HMAC-SHA-256 construction — independently of {@link TelegramMiniAppInitDataVerifier},
 * so a bug shared between the production code and the test fixture cannot
 * hide behind a pair of matching mistakes.
 */
class TelegramMiniAppInitDataVerifierTests {

    private static final String BOT_TOKEN = "123456789:AAH-test-bot-token-value";

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T09:00:00Z"), ZoneOffset.UTC);
    private final TelegramMiniAppInitDataVerifier verifier =
            new TelegramMiniAppInitDataVerifier(JsonMapper.builder().build(), clock, Duration.ofHours(1));

    @Test
    @DisplayName("a validly signed payload verifies and yields the Telegram user id")
    void validPayloadVerifies() {
        long telegramUserId = 555_555_555L;
        String initData = signedInitData(Map.of(
                "auth_date",
                String.valueOf(secondsAgo(Duration.ofMinutes(5))),
                "query_id",
                "AAHdgxxx",
                "user",
                "{\"id\":" + telegramUserId + ",\"first_name\":\"Test\"}"));

        Optional<Verified> result = verifier.verify(initData, BOT_TOKEN);

        assertThat(result).isPresent();
        assertThat(result.get().telegramUserId()).isEqualTo(telegramUserId);
    }

    @Test
    @DisplayName("a tampered field breaks the hash and is refused")
    void tamperedFieldIsRefused() {
        String initData = signedInitData(Map.of(
                "auth_date",
                String.valueOf(secondsAgo(Duration.ofMinutes(5))),
                "user",
                "{\"id\":1,\"first_name\":\"Test\"}"));
        // Changes a byte inside the already-signed payload — the id an
        // attacker would want to change to claim someone else's chat.
        String tampered = initData.replace("%22id%22%3A1", "%22id%22%3A2");

        assertThat(verifier.verify(tampered, BOT_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("a tampered hash is refused")
    void tamperedHashIsRefused() {
        String initData = signedInitData(Map.of(
                "auth_date",
                String.valueOf(secondsAgo(Duration.ofMinutes(5))),
                "user",
                "{\"id\":1,\"first_name\":\"Test\"}"));
        String tamperedHash = initData.substring(0, initData.length() - 1)
                + (initData.charAt(initData.length() - 1) == '0' ? '1' : '0');

        assertThat(verifier.verify(tamperedHash, BOT_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("a stale auth_date is refused even with a valid signature")
    void staleAuthDateIsRefused() {
        String initData = signedInitData(Map.of(
                "auth_date",
                String.valueOf(secondsAgo(Duration.ofHours(2))),
                "user",
                "{\"id\":1,\"first_name\":\"Test\"}"));

        assertThat(verifier.verify(initData, BOT_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("an auth_date in the future is refused, not forgiven as clock skew")
    void futureAuthDateIsRefused() {
        String initData = signedInitData(Map.of(
                "auth_date",
                String.valueOf(clock.instant().plus(Duration.ofMinutes(5)).getEpochSecond()),
                "user",
                "{\"id\":1,\"first_name\":\"Test\"}"));

        assertThat(verifier.verify(initData, BOT_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("a payload verified against the wrong bot token is refused")
    void wrongBotTokenIsRefused() {
        String initData = signedInitData(Map.of(
                "auth_date",
                String.valueOf(secondsAgo(Duration.ofMinutes(5))),
                "user",
                "{\"id\":1,\"first_name\":\"Test\"}"));

        assertThat(verifier.verify(initData, "999999999:a-completely-different-token"))
                .isEmpty();
    }

    @Test
    @DisplayName("a payload with no hash at all is refused")
    void missingHashIsRefused() {
        assertThat(verifier.verify("auth_date=1&user=%7B%22id%22%3A1%7D", BOT_TOKEN))
                .isEmpty();
    }

    private long secondsAgo(Duration duration) {
        return clock.instant().minus(duration).getEpochSecond();
    }

    /**
     * Signs {@code fields} exactly the way Telegram's own client does —
     * {@code secret_key = HMAC_SHA256(key = "WebAppData", data = botToken)},
     * then {@code hash = HMAC_SHA256(key = secret_key, data =
     * data_check_string)} over the fields sorted by key and joined with
     * {@code \n} — and returns the URL-encoded query string
     * {@link TelegramMiniAppInitDataVerifier#verify} expects, hash included.
     */
    private String signedInitData(Map<String, String> fields) {
        String dataCheckString = fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));

        byte[] secretKey =
                hmacSha256("WebAppData".getBytes(StandardCharsets.UTF_8), BOT_TOKEN.getBytes(StandardCharsets.UTF_8));
        byte[] hash = hmacSha256(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
        String hashHex = HexFormat.of().formatHex(hash);

        String encodedFields = fields.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return encodedFields + "&hash=" + hashHex;
    }

    private static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
