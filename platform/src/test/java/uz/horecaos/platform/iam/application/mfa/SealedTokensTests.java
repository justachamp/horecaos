package uz.horecaos.platform.iam.application.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.application.mfa.SealedTokens.Purpose;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The sealed enrolment secret and the enrolment ticket (ADR 0148, Decision 3): authenticated
 * encryption, a lifetime inside the token, and the purpose and the account bound in as associated
 * data so that a token is single-purpose and useless on another account by construction.
 */
class SealedTokensTests {

    private static final SecretReference KEY =
            new SecretReference("test", SecretCategory.DATA_ENCRYPTION, "platform", "key");
    private static final String SUBJECT = "6f1f7d54-2f4f-4c3f-9a53-1f7f0f8f0001";
    private static final String OTHER = "6f1f7d54-2f4f-4c3f-9a53-1f7f0f8f0002";

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-07T10:00:00Z"));

    private static SecretResolver resolving(String value) {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of(value);
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return resolve(reference);
            }
        };
    }

    private SealedTokens tokens() {
        return new SealedTokens(resolving("sealing-key-one"), KEY, clock);
    }

    @Test
    @DisplayName("a token opens for its purpose and its account, and returns what was sealed")
    void roundTrip() {
        SealedTokens tokens = tokens();
        String sealed = tokens.seal(Purpose.ENROLMENT, SUBJECT, Map.of("secret", "abc"), Duration.ofMinutes(10));

        var opened = tokens.open(Purpose.ENROLMENT, SUBJECT, sealed);

        assertThat(opened.claims()).containsEntry("secret", "abc");
        assertThat(opened.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("the secret is not readable in the token")
    void theTokenDoesNotContainItsContent() {
        String sealed = tokens().seal(
                        Purpose.ENROLMENT, SUBJECT, Map.of("secret", "Zk3fT9qLw2XvB7mNc5Rd"), Duration.ofMinutes(10));

        assertThat(sealed).doesNotContain("Zk3fT9qLw2XvB7mNc5Rd").doesNotContain(SUBJECT);
        assertThat(java.util.Base64.getUrlDecoder().decode(sealed))
                .asString(java.nio.charset.StandardCharsets.ISO_8859_1)
                .doesNotContain("Zk3fT9qLw2XvB7mNc5Rd");
    }

    @Test
    @DisplayName("a token expires after its lifetime, and not a second sooner")
    void expiry() {
        SealedTokens tokens = tokens();
        String sealed = tokens.seal(Purpose.ENROLMENT, SUBJECT, Map.of(), Duration.ofMinutes(10));

        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(tokens.open(Purpose.ENROLMENT, SUBJECT, sealed)).isNotNull();

        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> tokens.open(Purpose.ENROLMENT, SUBJECT, sealed))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a token sealed for one account is useless on another")
    void anotherAccountCannotOpenIt() {
        SealedTokens tokens = tokens();
        String sealed = tokens.seal(Purpose.ENROLMENT, SUBJECT, Map.of("secret", "abc"), Duration.ofMinutes(10));

        assertThatThrownBy(() -> tokens.open(Purpose.ENROLMENT, OTHER, sealed))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("a ticket cannot be presented where a sealed secret is wanted, nor the other way round")
    void purposesAreNotInterchangeable() {
        SealedTokens tokens = tokens();
        String ticket = tokens.sealTicket(SUBJECT, Duration.ofMinutes(15));
        String secret = tokens.seal(Purpose.ENROLMENT, SUBJECT, Map.of("secret", "abc"), Duration.ofMinutes(10));

        assertThatThrownBy(() -> tokens.open(Purpose.ENROLMENT, SUBJECT, ticket))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens.openTicket(secret)).isInstanceOf(ApiException.class);
        assertThat(tokens.openTicket(ticket)).isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("a forged, truncated, empty or foreign token is refused with one message")
    void malformedTokensAreRefusedAlike() {
        SealedTokens tokens = tokens();
        String sealed = tokens.seal(Purpose.ENROLMENT, SUBJECT, Map.of(), Duration.ofMinutes(10));
        String flipped = sealed.substring(0, sealed.length() - 2) + (sealed.endsWith("AA") ? "BB" : "AA");

        java.util.Set<String> messages = new java.util.HashSet<>();
        for (String bad : new String[] {null, "", "  ", "not base64 !!", "AAAA", flipped, sealed.substring(10)}) {
            Throwable failure = catchThrowable(() -> tokens.open(Purpose.ENROLMENT, SUBJECT, bad));
            assertThat(failure).as("token " + bad).isInstanceOf(ApiException.class);
            ApiException refused = (ApiException) failure;
            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            messages.add(String.valueOf(refused.getMessage()));
        }
        assertThat(messages).as("which way it failed must not be learnable").hasSize(1);
    }

    @Test
    @DisplayName("a token sealed under another key does not open, so rotating the key ends enrolments in progress")
    void aRotatedKeyEndsTheToken() {
        String sealed = tokens().seal(Purpose.ENROLMENT, SUBJECT, Map.of(), Duration.ofMinutes(10));
        SealedTokens rotated = new SealedTokens(resolving("sealing-key-two"), KEY, clock);

        assertThatThrownBy(() -> rotated.open(Purpose.ENROLMENT, SUBJECT, sealed))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("two tokens for the same claims differ: a fresh nonce every time")
    void aFreshNonceEveryTime() {
        SealedTokens tokens = tokens();

        assertThat(tokens.seal(Purpose.ENROLMENT, SUBJECT, Map.of(), Duration.ofMinutes(10)))
                .isNotEqualTo(tokens.seal(Purpose.ENROLMENT, SUBJECT, Map.of(), Duration.ofMinutes(10)));
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
