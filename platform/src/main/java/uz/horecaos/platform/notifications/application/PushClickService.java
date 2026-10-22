package uz.horecaos.platform.notifications.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcPushClickTokenStore;

/**
 * The tap that is a read (ADR 0182, D14).
 *
 * <p>Web Push reports nothing back: the push service says it accepted a message and no more, and the
 * browser never tells the sender it showed one. The one thing the platform can learn is that a customer
 * <em>tapped</em> the notification, and only because its own service worker, running in that browser,
 * tells it. So the encrypted payload carries an opaque token, and the service worker posts it to the
 * platform when the notification is clicked.
 *
 * <p><strong>A token is a capability, not a credential.</strong> It can do one thing, which is to say
 * that this message was read, so it can be used more than once (a second tap is the same fact and
 * changes nothing) and it is valid for as long as the push service may hold the message, and a week
 * past it. It is 192 random bits stored only as a SHA-256, names no customer, and is never logged. An
 * unknown token and an expired one are one answer.
 */
@Service
public class PushClickService {

    private static final int TOKEN_BYTES = 24;

    private final JdbcPushClickTokenStore tokens;
    private final DeliveryReceiptService receipts;
    private final Clock clock;
    private final Duration grace;
    private final SecureRandom random = new SecureRandom();

    public PushClickService(
            JdbcPushClickTokenStore tokens,
            DeliveryReceiptService receipts,
            Clock clock,
            @Value("${horecaos.notifications.push.click-token-grace:P7D}") Duration grace) {
        this.tokens = tokens;
        this.receipts = receipts;
        this.clock = clock;
        this.grace = grace;
    }

    /**
     * Mints a token for one message and returns it, to be put in that message's payload and nowhere
     * else.
     *
     * @param validFor how long the push service may hold the message; the token outlives it by the grace
     * @return the raw token. Not recoverable afterwards: only its hash is kept
     */
    @Transactional
    public String issue(UUID tenantId, UUID notificationId, Duration validFor) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.insert(
                hash(token), tenantId, notificationId, now, now.plus(validFor).plus(grace));
        return token;
    }

    /**
     * Records that the customer tapped the message the token was minted for.
     *
     * @return false for an unknown or expired token. A known token is true whether this tap was the
     *         first or the tenth: reporting a read twice is not an error a service worker could act on
     */
    @Transactional
    public boolean click(String token) {
        return tokens.find(hash(token), clock.instant())
                .map(found -> {
                    receipts.applyRead(found.tenantId(), found.notificationId());
                    return true;
                })
                .orElse(false);
    }

    /** Housekeeping for the sweeper: tokens past their expiry hold a row for nothing. */
    @Transactional
    public int purgeExpired(int limit) {
        return tokens.deleteExpired(clock.instant(), limit);
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every JVM", impossible);
        }
    }
}
