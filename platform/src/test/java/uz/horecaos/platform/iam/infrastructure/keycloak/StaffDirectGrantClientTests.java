package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;

/**
 * {@link StaffDirectGrantClient} against a stub token endpoint (ADR 0062).
 *
 * <p>Not a mock of one interface: {@link FakeKeycloakTokenEndpoint} is a real
 * socket the adapter's real {@link RestClient} talks to, so this proves the
 * actual form encoding, status handling, and JSON classification rather than a
 * pre-arranged answer. The scenarios and the exact {@code error_description}
 * wording it answers with were verified live against the running dev-realm
 * Keycloak on 2026-09-01 first (see the class doc on
 * {@link FakeKeycloakTokenEndpoint}); this test is what keeps that finding
 * from rotting the next time Keycloak changes wording underneath it silently.
 */
class StaffDirectGrantClientTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC);
    private static final SecretReference CLIENT_SECRET =
            new SecretReference("test", SecretCategory.IDENTITY_ADMIN, "keycloak", "staff-login-secret");

    private FakeKeycloakTokenEndpoint fake;
    private StaffDirectGrantClient client;

    @BeforeEach
    void setUp() throws IOException {
        fake = FakeKeycloakTokenEndpoint.start();
        RestClient restClient = RestClient.builder().baseUrl(fake.baseUrl()).build();
        SecretResolver secrets = fixedSecret("a-fixed-test-secret");
        client = new StaffDirectGrantClient(
                restClient, "horecaos", "horecaos-staff-login", CLIENT_SECRET, secrets, CLOCK);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    @DisplayName("a successful exchange is issued, with a null refresh expiry when Keycloak reports zero")
    void successfulSignInIsIssued() {
        TokenOutcome outcome =
                client.signIn(FakeKeycloakTokenEndpoint.HAPPY_USERNAME, FakeKeycloakTokenEndpoint.HAPPY_PASSWORD);

        assertThat(outcome).isInstanceOf(TokenOutcome.Issued.class);
        TokenOutcome.Issued issued = (TokenOutcome.Issued) outcome;
        assertThat(issued.accessToken()).isEqualTo("fake-access-token");
        assertThat(issued.refreshToken()).isEqualTo("fake-refresh-token");
        assertThat(issued.accessTokenExpiresAt()).isEqualTo(CLOCK.instant().plusSeconds(300));
        assertThat(issued.refreshTokenExpiresAt())
                .as("refresh_expires_in: 0 must not become an already-expired instant")
                .isNull();
        assertThat(issued.tokenType()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("a wrong password is classified as invalid credentials")
    void wrongPasswordIsInvalidCredentials() {
        TokenOutcome outcome = client.signIn(FakeKeycloakTokenEndpoint.HAPPY_USERNAME, "the-wrong-password");

        assertThat(outcome).isEqualTo(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("an unknown username is classified identically to a wrong password")
    void unknownUsernameIsInvalidCredentials() {
        TokenOutcome outcome = client.signIn("nobody-such-user@bukhara.local", "anything");

        assertThat(outcome).isEqualTo(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("Keycloak's required-action refusal is classified as account-action-required")
    void requiredActionIsClassifiedSeparately() {
        TokenOutcome outcome = client.signIn(FakeKeycloakTokenEndpoint.ACTION_REQUIRED_USERNAME, "correct horse");

        assertThat(outcome).isEqualTo(TokenOutcome.refused(TokenOutcome.FailureReason.ACCOUNT_ACTION_REQUIRED));
    }

    @Test
    @DisplayName("an upstream failure throws rather than being classified as a credential failure")
    void upstreamFailureThrows() {
        assertThatThrownBy(() -> client.signIn(FakeKeycloakTokenEndpoint.SERVER_ERROR_USERNAME, "correct horse"))
                .isInstanceOf(StaffDirectGrantClient.KeycloakUnavailableException.class);
    }

    @Test
    @DisplayName("a live refresh token is refreshed")
    void refreshSucceeds() {
        TokenOutcome outcome = client.refresh(FakeKeycloakTokenEndpoint.LIVE_REFRESH_TOKEN);

        assertThat(outcome).isInstanceOf(TokenOutcome.Issued.class);
    }

    @Test
    @DisplayName("a stale or revoked refresh token is refused")
    void refreshOfAStaleTokenIsRefused() {
        TokenOutcome outcome = client.refresh(FakeKeycloakTokenEndpoint.STALE_REFRESH_TOKEN);

        assertThat(outcome).isEqualTo(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("revocation reaches the endpoint")
    void revocationCallsTheEndpoint() {
        client.revoke(FakeKeycloakTokenEndpoint.LIVE_REFRESH_TOKEN);

        assertThat(fake.revocationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("revocation never throws, even when the endpoint fails")
    void revocationNeverThrows() {
        client.revoke(FakeKeycloakTokenEndpoint.SERVER_ERROR_REFRESH_TOKEN);

        assertThat(fake.revocationCount()).isEqualTo(1);
    }

    private static SecretResolver fixedSecret(String value) {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of(value);
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return SecretValue.of(value);
            }
        };
    }
}
