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
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.ConsentRecorder;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcUnsubscribeTokenStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcUnsubscribeTokenStore.UnsubscribeToken;
import uz.horecaos.platform.tenancy.api.BrandDisplayNames;

/**
 * The way out of a campaign email (ADR 0182, D13): a token in every message, and what it does.
 *
 * <p><strong>An unsubscribe is a withdrawal of consent.</strong> The token names the notification it
 * was minted for, and through it the customer, the brand and the consent purpose the message was
 * sent under. Consuming it records a {@code WITHDRAWN} decision for exactly that purpose on exactly
 * the {@code EMAIL} channel at exactly that brand's scope, which is the scope the campaign's own
 * consent check reads, so the next campaign refuses the customer with {@code CONSENT_WITHHELD}
 * without anything else having to learn about it. Consent to SMS is a different decision and is not
 * touched.
 *
 * <p><strong>A token is a capability, not a credential.</strong> It can do one thing, which is the
 * thing its holder is entitled to do, so it is valid for a year and can be used more than once:
 * unsubscribing twice is the same act, and a customer who clicks again must not be told they did it
 * wrong. It is 192 random bits stored only as a SHA-256, so the table cannot unsubscribe anyone, and
 * it carries no address.
 *
 * <p><strong>Nothing here reveals who anyone is.</strong> Looking a token up tells the page the
 * brand's name and the language, and nothing about the customer.
 */
@Service
public class EmailUnsubscribeService {

    /**
     * The policy version a withdrawal through a link is recorded under. The consent store's
     * evidence reference is reserved for the grant a withdrawal answers, so how the customer
     * withdrew is carried here, where an auditor reading the history can see it.
     */
    public static final String WITHDRAWN_VIA = "EMAIL_UNSUBSCRIBE";

    /** The consent store's closed source list has one entry for a customer's own act. */
    private static final String SOURCE = "STOREFRONT";

    private static final String CHANNEL = "EMAIL";
    private static final int TOKEN_BYTES = 24;

    private final JdbcUnsubscribeTokenStore tokens;
    private final ConsentDirectory consentReader;
    private final ConsentRecorder consentRecorder;
    private final BrandDisplayNames brands;
    private final Clock clock;
    private final Duration validity;
    private final SecureRandom random = new SecureRandom();

    public EmailUnsubscribeService(
            JdbcUnsubscribeTokenStore tokens,
            ConsentDirectory consentReader,
            ConsentRecorder consentRecorder,
            BrandDisplayNames brands,
            Clock clock,
            @Value("${horecaos.notifications.email.unsubscribe-token-validity:P400D}") Duration validity) {
        this.tokens = tokens;
        this.consentReader = consentReader;
        this.consentRecorder = consentRecorder;
        this.brands = brands;
        this.clock = clock;
        this.validity = validity;
    }

    /**
     * Mints a token for one message and returns it, to be put in that message and nowhere else.
     *
     * @return the raw token. Not recoverable afterwards: only its hash is kept
     */
    public String issue(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            UUID notificationId,
            String consentPurpose,
            @Nullable String locale) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.insert(
                hash(token),
                tenantId,
                brandId,
                customerAccountId,
                notificationId,
                consentPurpose,
                locale,
                now,
                now.plus(validity));
        return token;
    }

    /**
     * What the page may say to someone holding this token: the brand's name and the message's
     * language. Empty for an unknown token and for an expired one, which are one answer.
     */
    public Optional<Preview> preview(String token) {
        return tokens.find(hash(token), clock.instant())
                .map(found -> new Preview(
                        brands.displayName(found.tenantId(), found.brandId()).orElse(""), found.locale()));
    }

    /**
     * Withdraws the consent the message was sent under. Idempotent: a customer who unsubscribes
     * twice, or who had already withdrawn another way, gets the same «you are unsubscribed».
     *
     * @return empty for an unknown or expired token
     */
    public Optional<Preview> unsubscribe(String token) {
        Optional<UnsubscribeToken> found = tokens.find(hash(token), clock.instant());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UnsubscribeToken stands = found.get();
        boolean granted = consentReader
                .consentFor(
                        stands.tenantId(),
                        stands.customerAccountId(),
                        stands.brandId(),
                        stands.consentPurpose(),
                        CHANNEL)
                .map(ConsentDirectory.ConsentState::granted)
                .orElse(false);
        if (granted) {
            consentRecorder.recordWithdrawal(
                    stands.tenantId(),
                    stands.customerAccountId(),
                    stands.brandId(),
                    stands.consentPurpose(),
                    CHANNEL,
                    WITHDRAWN_VIA,
                    SOURCE,
                    null);
        }
        return Optional.of(new Preview(
                brands.displayName(stands.tenantId(), stands.brandId()).orElse(""), stands.locale()));
    }

    /**
     * Housekeeping for the sweeper: tokens past their expiry answer like unknown ones and hold a row
     * that names a customer account for nothing.
     *
     * @return how many rows this call removed, at most {@code limit}
     */
    @Transactional
    public int purgeExpired(int limit) {
        return tokens.deleteExpired(clock.instant(), limit);
    }

    /** What an unsubscribe page is allowed to know. */
    public record Preview(String brandName, @Nullable String locale) {}

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every JVM", impossible);
        }
    }
}
