package uz.horecaos.platform.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.OtpCredential;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Requirement;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.application.StaffAuthService.StaffSession;
import uz.horecaos.platform.iam.application.mfa.MfaCodeBudget;
import uz.horecaos.platform.iam.application.mfa.MfaMetrics;
import uz.horecaos.platform.iam.application.mfa.MfaPolicy;
import uz.horecaos.platform.iam.application.mfa.SealedTokens;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient.PasswordCheck;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient.PasswordVerified;
import uz.horecaos.platform.iam.infrastructure.keycloak.TokenOutcome;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * ADR 0062's orchestration and ADR 0148's second step: the per-address budget, the fixed order of
 * Keycloak calls, the uniform-versus-distinguishable failure shape, and the account's own code
 * budget.
 *
 * <p>Both Keycloak adapters are mocked -- their HTTP contracts are {@code
 * StaffDirectGrantClientTests}', {@code StaffPasswordCheckClientTests}' and {@code
 * StaffMfaFlowTests}' job, the last against a stateful fake of Keycloak. This class proves what
 * {@link StaffAuthService} does with each answer they can hand back, and it uses the real {@link
 * InProcessRateLimiter}, driven by a clock the test moves, so the budget tests are proof of actual
 * bucket behaviour rather than a stub told what to say.
 */
class StaffAuthServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SUBJECT = "6f1f7d54-2f4f-4c3f-9a53-1f7f0f8f0001";
    private static final String OTHER_SUBJECT = "6f1f7d54-2f4f-4c3f-9a53-1f7f0f8f0002";

    private StaffDirectGrantClient keycloak;
    private StaffPasswordCheckClient passwordCheck;
    private MfaPolicy policy;
    private StaffAccounts accounts;
    private MovableClock limiterClock;
    private SealedTokens tickets;
    private StaffAuthService service;

    @BeforeEach
    void setUp() {
        keycloak = mock(StaffDirectGrantClient.class);
        passwordCheck = mock(StaffPasswordCheckClient.class);
        policy = mock(MfaPolicy.class);
        accounts = mock(StaffAccounts.class);
        limiterClock = new MovableClock(NOW);
        InProcessRateLimiter rateLimiter = new InProcessRateLimiter(limiterClock);
        SecretResolver secrets = new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of("a-sealing-key-for-tests");
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return resolve(reference);
            }
        };
        tickets = new SealedTokens(
                secrets, new SecretReference("test", SecretCategory.DATA_ENCRYPTION, "platform", "key"), CLOCK);
        service = new StaffAuthService(
                keycloak,
                passwordCheck,
                rateLimiter,
                new MfaCodeBudget(rateLimiter, 5, Duration.ofHours(1)),
                policy,
                accounts,
                tickets,
                new MfaMetrics(new SimpleMeterRegistry()),
                CLOCK);
        // Most tests are about what the login client answers; the password-only client confirms the
        // password, and nobody needs a second factor, unless a test says otherwise.
        when(passwordCheck.verify(anyString(), anyString()))
                .thenReturn(new PasswordCheck.Verified(new PasswordVerified(SUBJECT)));
        when(policy.requirementFor(anyString(), anyBoolean())).thenReturn(Requirement.NOT_REQUIRED);
    }

    private static TokenOutcome.Issued issued() {
        return new TokenOutcome.Issued(
                "access-token-value", "refresh-token-value", NOW.plusSeconds(300), null, "Bearer");
    }

    private static TokenOutcome.Refused refusedPassword() {
        return new TokenOutcome.Refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS);
    }

    private static PasswordCheck.Refused wrongPassword() {
        return new PasswordCheck.Refused(TokenOutcome.FailureReason.INVALID_CREDENTIALS);
    }

    // ------------------------------------------------------------ the happy path and its order

    @Test
    @DisplayName("a successful exchange carries the tokens straight through, including a null refresh expiry")
    void happyPathIssuesASession() {
        when(keycloak.signIn("cashier@bukhara.local", "correct horse", null)).thenReturn(issued());

        StaffSession session = service.signIn("cashier@bukhara.local", "correct horse", null, "rate-limit-key-1");

        assertThat(session.accessToken()).isEqualTo("access-token-value");
        assertThat(session.refreshToken()).isEqualTo("refresh-token-value");
        assertThat(session.accessTokenExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(session.refreshTokenExpiresAt()).isNull();
        assertThat(session.tokenType()).isEqualTo("Bearer");
        assertThat(session.mfaEnrolmentOffered()).isFalse();
    }

    @Test
    @DisplayName("the password-only client is asked first, and the login client second")
    void thePasswordIsConfirmedBeforeTheLoginClientIsAsked() {
        when(keycloak.signIn("cashier@bukhara.local", "correct horse", null)).thenReturn(issued());

        service.signIn("cashier@bukhara.local", "correct horse", null, "key");

        InOrder order = inOrder(passwordCheck, keycloak);
        order.verify(passwordCheck).verify("cashier@bukhara.local", "correct horse");
        order.verify(keycloak).signIn("cashier@bukhara.local", "correct horse", null);
    }

    // ------------------------------------------------------------ the uniform failure

    @Test
    @DisplayName("a wrong password answers the uniform invalid-credentials failure and the login client is never asked")
    void wrongPasswordIsUniformAndStopsAtTheProbe() {
        when(passwordCheck.verify("cashier@bukhara.local", "wrong")).thenReturn(wrongPassword());

        ApiException thrown = (ApiException)
                catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", null, "rate-limit-key-2"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(thrown.getMessage()).isEqualTo("Invalid credentials.");
        verifyNoInteractions(keycloak);
    }

    @Test
    @DisplayName("an unknown username answers exactly the same failure as a wrong password -- no enumeration oracle")
    void unknownUsernameIsIndistinguishableFromAWrongPassword() {
        when(passwordCheck.verify(anyString(), anyString())).thenReturn(wrongPassword());

        ApiException unknownUser =
                (ApiException) catchThrowable(() -> service.signIn("nobody@bukhara.local", "anything", null, "key-a"));
        ApiException wrongPassword =
                (ApiException) catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", null, "key-b"));

        assertThat(unknownUser.errorCode()).isEqualTo(wrongPassword.errorCode());
        assertThat(unknownUser.getMessage()).isEqualTo(wrongPassword.getMessage());
        assertThat(unknownUser.properties()).isEqualTo(wrongPassword.properties());
    }

    @Test
    @DisplayName(
            "a wrong password with a code sent is the same uniform failure: the code never makes it distinguishable")
    void aCodeDoesNotChangeTheAnswerToAWrongPassword() {
        when(passwordCheck.verify(anyString(), anyString())).thenReturn(wrongPassword());

        ApiException withCode = (ApiException)
                catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", "123456", "key-a"));

        assertThat(withCode.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(withCode.getMessage()).isEqualTo("Invalid credentials.");
        verifyNoInteractions(keycloak);
    }

    @Test
    @DisplayName(
            "a required-action refusal is the one distinguishable failure ADR 0062 allows, and it is the probe that says it")
    void requiredActionIsDistinguishable() {
        when(passwordCheck.verify("newstaff@bukhara.local", "correct horse"))
                .thenReturn(new PasswordCheck.Refused(TokenOutcome.FailureReason.ACCOUNT_ACTION_REQUIRED));

        ApiException thrown = (ApiException)
                catchThrowable(() -> service.signIn("newstaff@bukhara.local", "correct horse", null, "key-c"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.ACCOUNT_ACTION_REQUIRED);
        verifyNoInteractions(keycloak);
    }

    // ------------------------------------------------------------ the second step

    @Test
    @DisplayName(
            "a right password with no code, refused by the login client, is MFA_REQUIRED -- and spends no code budget")
    void aMissingCodeIsMfaRequired() {
        when(keycloak.signIn("cook@bukhara.local", "correct horse", null)).thenReturn(refusedPassword());

        ApiException thrown = (ApiException)
                catchThrowable(() -> service.signIn("cook@bukhara.local", "correct horse", null, "key-1"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.MFA_REQUIRED);
        // The budget is untouched: five code-bearing attempts still reach Keycloak afterwards.
        when(keycloak.signIn(anyString(), anyString(), eq("111111"))).thenReturn(refusedPassword());
        for (int attempt = 0; attempt < 5; attempt++) {
            int n = attempt;
            ApiException invalid = (ApiException) catchThrowable(
                    () -> service.signIn("cook@bukhara.local", "correct horse", "111111", "address-" + n));
            assertThat(invalid.errorCode()).isEqualTo(ErrorCode.MFA_CODE_INVALID);
        }
    }

    @Test
    @DisplayName("a right password with a wrong code is MFA_CODE_INVALID")
    void aWrongCodeIsMfaCodeInvalid() {
        when(keycloak.signIn("cook@bukhara.local", "correct horse", "000000")).thenReturn(refusedPassword());

        ApiException thrown = (ApiException)
                catchThrowable(() -> service.signIn("cook@bukhara.local", "correct horse", "000000", "key-1"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.MFA_CODE_INVALID);
    }

    @Test
    @DisplayName("the code is forwarded to Keycloak when the budget allows it, and a right code signs in")
    void aRightCodeSignsIn() {
        when(keycloak.signIn("cook@bukhara.local", "correct horse", "482913")).thenReturn(issued());

        StaffSession session = service.signIn("cook@bukhara.local", "correct horse", "482913", "key-1");

        assertThat(session.accessToken()).isEqualTo("access-token-value");
    }

    // ------------------------------------------------------------ the code budget

    @Test
    @DisplayName("the sixth code-bearing sign-in is a 429 and no request carrying that code reaches Keycloak")
    void theSixthCodeIsRefusedBeforeKeycloakIsAsked() {
        when(keycloak.signIn(anyString(), anyString(), any())).thenReturn(refusedPassword());

        for (int attempt = 1; attempt <= 5; attempt++) {
            int n = attempt;
            ApiException invalid = (ApiException) catchThrowable(
                    () -> service.signIn("cook@bukhara.local", "correct horse", "%06d".formatted(n), "address-" + n));
            assertThat(invalid.errorCode()).isEqualTo(ErrorCode.MFA_CODE_INVALID);
        }
        ApiException sixth = (ApiException)
                catchThrowable(() -> service.signIn("cook@bukhara.local", "correct horse", "000006", "address-6"));

        assertThat(sixth.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(sixth.properties()).containsKey("retryAfterSeconds");
        verify(keycloak, times(5)).signIn(anyString(), anyString(), any());
        verify(keycloak, never()).signIn(anyString(), anyString(), eq("000006"));
        // Every attempt was followed by a successful probe: the count Keycloak keeps is zero, and
        // the lockout still held. The budget is the platform's.
        verify(passwordCheck, times(6)).verify("cook@bukhara.local", "correct horse");
    }

    @Test
    @DisplayName(
            "while the budget is spent even the right code is refused with the same 429, and it works again once it refills")
    void theRightCodeIsRefusedWhileTheBudgetIsSpentAndWorksAfterItRefills() {
        when(keycloak.signIn(anyString(), anyString(), eq("111111"))).thenReturn(refusedPassword());
        when(keycloak.signIn(anyString(), anyString(), eq("482913"))).thenReturn(issued());
        for (int attempt = 1; attempt <= 5; attempt++) {
            int n = attempt;
            var ignored = catchThrowable(
                    () -> service.signIn("cook@bukhara.local", "correct horse", "111111", "address-" + n));
        }

        ApiException whileSpent = (ApiException)
                catchThrowable(() -> service.signIn("cook@bukhara.local", "correct horse", "482913", "address-9"));
        assertThat(whileSpent.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        verify(keycloak, never()).signIn(anyString(), anyString(), eq("482913"));

        // A controlled clock, not a sleep: one attempt refills every twelve minutes (five an hour).
        limiterClock.advance(Duration.ofMinutes(13));
        StaffSession session = service.signIn("cook@bukhara.local", "correct horse", "482913", "address-10");

        assertThat(session.accessToken()).isEqualTo("access-token-value");
    }

    @Test
    @DisplayName(
            "rotating the source address does not refill the budget, because it is the account's and not the address'")
    void aRotatingAddressDoesNotRefillTheBudget() {
        when(keycloak.signIn(anyString(), anyString(), any())).thenReturn(refusedPassword());
        for (int attempt = 1; attempt <= 5; attempt++) {
            int n = attempt;
            var ignored = catchThrowable(() ->
                    service.signIn("cook@bukhara.local", "correct horse", "%06d".formatted(n), "fresh-address-" + n));
        }

        // Twenty more, each from an address and a username-spelling never seen before.
        for (int attempt = 0; attempt < 20; attempt++) {
            int n = attempt;
            ApiException thrown = (ApiException) catchThrowable(
                    () -> service.signIn("cook@bukhara.local", "correct horse", "999999", "never-seen-before-" + n));
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        }

        // Keycloak saw five codes in all.
        verify(keycloak, times(5)).signIn(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("signing in by email instead of user name draws on the same account budget")
    void emailAndUsernameShareTheBudget() {
        // Both spellings resolve to one subject, which is what the password-only client reports.
        when(passwordCheck.verify(anyString(), anyString()))
                .thenReturn(new PasswordCheck.Verified(new PasswordVerified(SUBJECT)));
        when(keycloak.signIn(anyString(), anyString(), any())).thenReturn(refusedPassword());
        for (int attempt = 1; attempt <= 3; attempt++) {
            int n = attempt;
            var ignored = catchThrowable(() -> service.signIn("998901234567", "pw", "%06d".formatted(n), "a-" + n));
        }
        for (int attempt = 4; attempt <= 5; attempt++) {
            int n = attempt;
            var ignored =
                    catchThrowable(() -> service.signIn("cook@bukhara.local", "pw", "%06d".formatted(n), "a-" + n));
        }

        ApiException sixth =
                (ApiException) catchThrowable(() -> service.signIn("cook@bukhara.local", "pw", "000006", "a-6"));

        assertThat(sixth.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("a wrong password and an unknown name never spend a budget and answer exactly as before")
    void aWrongPasswordNeverSpendsTheBudget() {
        when(passwordCheck.verify("cook@bukhara.local", "wrong")).thenReturn(wrongPassword());
        for (int attempt = 0; attempt < 40; attempt++) {
            int n = attempt;
            ApiException thrown = (ApiException)
                    catchThrowable(() -> service.signIn("cook@bukhara.local", "wrong", "123456", "address-" + n));
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        }

        // The owner's full budget is untouched.
        when(keycloak.signIn(anyString(), anyString(), eq("482913"))).thenReturn(issued());
        StaffSession session = service.signIn("cook@bukhara.local", "correct horse", "482913", "owner-address");
        assertThat(session.accessToken()).isEqualTo("access-token-value");
    }

    @Test
    @DisplayName("another account has its own budget")
    void budgetsArePerAccount() {
        when(keycloak.signIn(anyString(), anyString(), any())).thenReturn(refusedPassword());
        for (int attempt = 1; attempt <= 5; attempt++) {
            int n = attempt;
            var ignored =
                    catchThrowable(() -> service.signIn("cook@bukhara.local", "pw", "%06d".formatted(n), "a-" + n));
        }
        when(passwordCheck.verify("waiter@bukhara.local", "pw"))
                .thenReturn(new PasswordCheck.Verified(new PasswordVerified(OTHER_SUBJECT)));

        ApiException other =
                (ApiException) catchThrowable(() -> service.signIn("waiter@bukhara.local", "pw", "123456", "a-other"));

        assertThat(other.errorCode()).isEqualTo(ErrorCode.MFA_CODE_INVALID);
    }

    @Test
    @DisplayName(
            "the per-address sign-in budget stays first: the sixth attempt in a minute never reaches either client")
    void rateLimitRefusesTheSixthAttemptBeforeAnyKeycloakCall() {
        when(passwordCheck.verify(anyString(), anyString())).thenReturn(wrongPassword());

        for (int attempt = 1; attempt <= 5; attempt++) {
            var ignored = catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", null, "same-key"));
        }
        ApiException sixth =
                (ApiException) catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", null, "same-key"));

        assertThat(sixth.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(sixth.properties()).containsKey("retryAfterSeconds");
        // Five reached the password-only client; the sixth was refused locally and never did.
        verify(passwordCheck, times(5)).verify(anyString(), anyString());
        verifyNoInteractions(keycloak);
    }

    @Test
    @DisplayName("a different IP-plus-username pair has its own sign-in budget")
    void rateLimitIsPerKey() {
        when(passwordCheck.verify(anyString(), anyString())).thenReturn(wrongPassword());
        for (int attempt = 1; attempt <= 5; attempt++) {
            var ignored = catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", null, "key-x"));
        }

        ApiException fromANewKey =
                (ApiException) catchThrowable(() -> service.signIn("cashier@bukhara.local", "wrong", null, "key-y"));

        assertThat(fromANewKey.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    // ------------------------------------------------------------ the requirement

    @Test
    @DisplayName("an account that needs a second factor and has none gets a ticket, and its session is revoked")
    void requiredAndAbsentRevokesTheSessionAndHandsOverATicket() {
        when(keycloak.signIn("admin@horecaos.uz", "correct horse", null)).thenReturn(issued());
        when(policy.requirementFor(eq(SUBJECT), anyBoolean())).thenReturn(Requirement.REQUIRED);

        ApiException thrown = (ApiException)
                catchThrowable(() -> service.signIn("admin@horecaos.uz", "correct horse", null, "key-1"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.MFA_ENROLMENT_REQUIRED);
        verify(keycloak).revoke("refresh-token-value");
        String ticket = (String) thrown.properties().get("enrolmentTicket");
        assertThat(ticket).isNotBlank();
        assertThat(thrown.properties().get("expiresAt"))
                .isEqualTo(NOW.plus(Duration.ofMinutes(15)).toString());
        // The ticket names this account and nobody else.
        assertThat(tickets.openTicket(ticket)).isEqualTo(SUBJECT);
        // With no code sent and the grant accepted, no factor exists: the admin API need not be asked.
        verifyNoInteractions(accounts);
    }

    @Test
    @DisplayName("a code sent to an account with no factor is ignored by Keycloak, so the credential list decides")
    void aCodeSentToAnAccountWithNoFactorStillMeetsTheRequirement() {
        when(keycloak.signIn("admin@horecaos.uz", "correct horse", "123456")).thenReturn(issued());
        when(policy.requirementFor(eq(SUBJECT), anyBoolean())).thenReturn(Requirement.REQUIRED);
        when(accounts.otpCredentials(SUBJECT)).thenReturn(List.of());

        ApiException thrown = (ApiException)
                catchThrowable(() -> service.signIn("admin@horecaos.uz", "correct horse", "123456", "key-1"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.MFA_ENROLMENT_REQUIRED);
        verify(keycloak).revoke("refresh-token-value");
    }

    @Test
    @DisplayName("an account that needs a second factor and holds one signs in with its code")
    void requiredAndPresentSignsIn() {
        when(keycloak.signIn("admin@horecaos.uz", "correct horse", "123456")).thenReturn(issued());
        when(policy.requirementFor(eq(SUBJECT), anyBoolean())).thenReturn(Requirement.REQUIRED);
        when(accounts.otpCredentials(SUBJECT)).thenReturn(List.of(new OtpCredential("c1", "phone", NOW)));

        StaffSession session = service.signIn("admin@horecaos.uz", "correct horse", "123456", "key-1");

        assertThat(session.accessToken()).isEqualTo("access-token-value");
        assertThat(session.mfaEnrolmentOffered()).isFalse();
        verify(keycloak, never()).revoke(anyString());
    }

    @Test
    @DisplayName("an account the platform rule only offers a factor to gets a session and the offer")
    void offeredAndAbsentSignsInWithTheOffer() {
        when(keycloak.signIn("admin@horecaos.uz", "correct horse", null)).thenReturn(issued());
        when(policy.requirementFor(eq(SUBJECT), anyBoolean())).thenReturn(Requirement.OFFERED);

        StaffSession session = service.signIn("admin@horecaos.uz", "correct horse", null, "key-1");

        assertThat(session.mfaEnrolmentOffered()).isTrue();
        verify(keycloak, never()).revoke(anyString());
    }

    @Test
    @DisplayName("an account nobody asks a second factor of signs in unchanged, with no admin call")
    void notRequiredSignsInUnchanged() {
        when(keycloak.signIn("cashier@bukhara.local", "correct horse", null)).thenReturn(issued());

        StaffSession session = service.signIn("cashier@bukhara.local", "correct horse", null, "key-1");

        assertThat(session.mfaEnrolmentOffered()).isFalse();
        verifyNoInteractions(accounts);
    }

    @Test
    @DisplayName(
            "the realm's platform-admin role in the issued token reaches the requirement, for a bootstrap admin with no grant row")
    void theRealmRoleInTheTokenIsPassedToThePolicy() {
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"x\",\"realm_access\":{\"roles\":[\"platform-admin\"]}}"
                        .getBytes(StandardCharsets.UTF_8));
        TokenOutcome.Issued admin = new TokenOutcome.Issued(
                "e30." + payload + ".sig", "refresh-token-value", NOW.plusSeconds(300), null, "Bearer");
        when(keycloak.signIn("root@horecaos.uz", "correct horse", null)).thenReturn(admin);

        service.signIn("root@horecaos.uz", "correct horse", null, "key-1");

        verify(policy).requirementFor(SUBJECT, true);
    }

    // ------------------------------------------------------------ refresh and sign-out, unchanged

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
        TokenOutcome.Issued issued =
                new TokenOutcome.Issued("new-access", "new-refresh", NOW.plusSeconds(300), null, "Bearer");
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
        verify(keycloak, never()).signIn(anyString(), anyString(), any());
        verifyNoInteractions(passwordCheck);
    }

    /** A clock a test can move, for the limiter: refilling a budget is a duration, not a sleep. */
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
