package uz.horecaos.platform.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.application.StaffAuthService.StaffSession;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.TokenOutcome;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * ADR 0062's orchestration: rate limiting, and the uniform-versus-distinguishable
 * failure shape.
 *
 * <p>{@link StaffDirectGrantClient} is mocked — its own HTTP/JSON contract with
 * Keycloak is {@link uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClientTests}'s
 * job. This class instead proves what {@link StaffAuthService} does with each
 * of the outcomes that adapter can hand back, and it uses the real {@link
 * InProcessRateLimiter} rather than a mock, so the rate-limit test is proof of
 * actual bucket behaviour rather than a stub told what to say.
 */
class StaffAuthServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC);

    private StaffDirectGrantClient keycloak;
    private InProcessRateLimiter rateLimiter;
    private StaffAuthService service;

    @BeforeEach
    void setUp() {
        keycloak = mock(StaffDirectGrantClient.class);
        rateLimiter = new InProcessRateLimiter(CLOCK);
        service = new StaffAuthService(keycloak, rateLimiter);
    }

    @Test
    @DisplayName("a successful exchange carries the tokens straight through, including a null refresh expiry")
    void happyPathIssuesASession() {
        TokenOutcome.Issued issued = new TokenOutcome.Issued(
                "access-token-value",
                "refresh-token-value",
                CLOCK.instant().plusSeconds(300),
                null, // ADR 0062: an offline-scoped refresh token; see TokenOutcome.Issued's own doc
                "Bearer");
        when(keycloak.signIn("cashier@bukhara.local", "correct horse")).thenReturn(TokenOutcome.issued(issued));

        StaffSession session = service.signIn("cashier@bukhara.local", "correct horse", "rate-limit-key-1");

        assertThat(session.accessToken()).isEqualTo("access-token-value");
        assertThat(session.refreshToken()).isEqualTo("refresh-token-value");
        assertThat(session.accessTokenExpiresAt()).isEqualTo(CLOCK.instant().plusSeconds(300));
        assertThat(session.refreshTokenExpiresAt()).isNull();
        assertThat(session.tokenType()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("a wrong password answers the uniform invalid-credentials failure")
    void wrongPasswordIsUniform() {
        when(keycloak.signIn("cashier@bukhara.local", "wrong"))
                .thenReturn(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));

        ApiException thrown = (ApiException)
                catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", "rate-limit-key-2"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(thrown.getMessage()).isEqualTo("Invalid credentials.");
    }

    @Test
    @DisplayName("an unknown username answers exactly the same failure as a wrong password -- no enumeration oracle")
    void unknownUsernameIsIndistinguishableFromAWrongPassword() {
        when(keycloak.signIn("nobody@bukhara.local", "anything"))
                .thenReturn(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));
        when(keycloak.signIn("cashier@bukhara.local", "wrong"))
                .thenReturn(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));

        ApiException unknownUser =
                (ApiException) catchThrowable(() -> service.signIn("nobody@bukhara.local", "anything", "key-a"));
        ApiException wrongPassword =
                (ApiException) catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", "key-b"));

        assertThat(unknownUser.errorCode()).isEqualTo(wrongPassword.errorCode());
        assertThat(unknownUser.getMessage()).isEqualTo(wrongPassword.getMessage());
        assertThat(unknownUser.properties()).isEqualTo(wrongPassword.properties());
    }

    @Test
    @DisplayName("a required-action refusal is the one distinguishable failure the ADR allows")
    void requiredActionIsDistinguishable() {
        when(keycloak.signIn("newstaff@bukhara.local", "correct horse"))
                .thenReturn(TokenOutcome.refused(TokenOutcome.FailureReason.ACCOUNT_ACTION_REQUIRED));

        ApiException thrown =
                (ApiException) catchThrowable(() -> service.signIn("newstaff@bukhara.local", "correct horse", "key-c"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.ACCOUNT_ACTION_REQUIRED);
    }

    @Test
    @DisplayName(
            "the sixth attempt in a minute against the same IP-plus-username pair is refused before Keycloak is asked")
    void rateLimitRefusesTheSixthAttempt() {
        when(keycloak.signIn(anyString(), anyString()))
                .thenReturn(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));

        for (int attempt = 1; attempt <= 5; attempt++) {
            var _ = catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", "same-key"));
        }
        ApiException sixth =
                (ApiException) catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", "same-key"));

        assertThat(sixth.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(sixth.properties()).containsKey("retryAfterSeconds");
        // Five calls reached Keycloak; the sixth was refused locally and never did.
        verify(keycloak, times(5)).signIn(anyString(), anyString());
    }

    @Test
    @DisplayName("a different IP-plus-username pair has its own budget")
    void rateLimitIsPerKey() {
        when(keycloak.signIn(anyString(), anyString()))
                .thenReturn(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));

        for (int attempt = 1; attempt <= 5; attempt++) {
            var _ = catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", "key-x"));
        }
        // A fresh key is a fresh budget, not refused by the other caller's exhaustion.
        ApiException fromANewKey =
                (ApiException) catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", "key-y"));

        assertThat(fromANewKey.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName(
            "a failed refresh answers session-expired, not invalid credentials -- there is no password to have gotten wrong")
    void aFailedRefreshIsSessionExpired() {
        when(keycloak.refresh("a-stale-refresh-token"))
                .thenReturn(TokenOutcome.refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS));

        ApiException thrown = (ApiException) catchThrowable(() -> service.refresh("a-stale-refresh-token"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    @Test
    @DisplayName("a successful refresh carries the new tokens through")
    void aSuccessfulRefreshIssuesANewSession() {
        TokenOutcome.Issued issued = new TokenOutcome.Issued(
                "new-access", "new-refresh", CLOCK.instant().plusSeconds(300), null, "Bearer");
        when(keycloak.refresh("a-live-refresh-token")).thenReturn(TokenOutcome.issued(issued));

        StaffSession session = service.refresh("a-live-refresh-token");

        assertThat(session.accessToken()).isEqualTo("new-access");
    }

    @Test
    @DisplayName("sign-out always revokes and never throws, whatever Keycloak would have said")
    void signOutAlwaysRevokes() {
        service.signOut("a-refresh-token");

        verify(keycloak).revoke("a-refresh-token");
    }

    @Test
    @DisplayName("sign-out is not rate-limited: it is not a credential-guessing surface")
    void signOutIsNotRateLimited() {
        for (int i = 0; i < 50; i++) {
            service.signOut("a-refresh-token");
        }

        verify(keycloak, times(50)).revoke(any());
        verify(keycloak, never()).signIn(anyString(), anyString());
    }
}
