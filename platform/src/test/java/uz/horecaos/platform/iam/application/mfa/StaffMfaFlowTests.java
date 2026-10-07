package uz.horecaos.platform.iam.application.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.audit.StaffSecurityAudit;
import uz.horecaos.platform.iam.api.audit.StaffSecurityFact;
import uz.horecaos.platform.iam.api.mail.StaffEmail;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender;
import uz.horecaos.platform.iam.api.mfa.MfaRequirementMode;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.ResetCommand;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.application.StaffAuthService;
import uz.horecaos.platform.iam.application.StaffAuthService.StaffSession;
import uz.horecaos.platform.iam.application.mfa.StaffMfaService.Confirmation;
import uz.horecaos.platform.iam.application.mfa.StaffMfaService.Enrolment;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.Totp;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.User;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.CacheRegistry;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * ADR 0148 end to end through the platform's real services and the real Keycloak adapters, against
 * a stateful fake of Keycloak that reproduces the facts the design rests on: a code is single use,
 * a missing code is refused like a wrong password, and a successful password-only grant clears
 * the failure count.
 *
 * <p>The doubles are only what lies outside the record: the audit trail and the mail relay record
 * what they are given, the transaction template just runs its callback, and the grants are a few
 * sets. The exit criteria of the ADR are the test names.
 */
class StaffMfaFlowTests {

    private static final Instant START = Instant.parse("2026-10-07T10:00:00Z");
    private static final String PASSWORD = "correct horse battery";
    private static final String PHONE = "998901234567";
    private static final SecretReference SEALING_KEY =
            new SecretReference("test", SecretCategory.DATA_ENCRYPTION, "platform", "mfa-enrolment-sealing-key");

    private final MovableClock clock = new MovableClock(START);
    private final List<StaffSecurityFact> facts = new ArrayList<>();
    private final List<StaffEmail> emails = new ArrayList<>();
    private final Set<String> platformSubjects = new HashSet<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private FakeKeycloakMfaRealm realm;
    private User admin;
    private StaffAuthService auth;
    private StaffMfaService mfa;
    private SealedTokens tokens;
    private StaffAccounts accounts;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() throws IOException {
        realm = FakeKeycloakMfaRealm.start(clock);
        FakeKeycloakMfaRealm.Adapters adapters = realm.adapters();
        accounts = adapters.accounts();
        admin = realm.addUser(PHONE, PASSWORD, "admin@horecaos.uz");
        platformSubjects.add(admin.id);

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
        tokens = new SealedTokens(secrets, SEALING_KEY, clock);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock);
        MfaMetrics metrics = new MfaMetrics(meters);
        StaffAccountFacts accountFacts = new StaffAccountFacts() {
            @Override
            public boolean holdsPlatformGrant(String subject) {
                return platformSubjects.contains(subject);
            }

            @Override
            public Map<UUID, Set<String>> rolesByTenant(String subject) {
                return Map.of();
            }

            @Override
            public boolean holdsRoleInTenant(String subject, UUID tenantId, String roleCode) {
                return false;
            }

            @Override
            public Optional<String> uiLocale(String subject) {
                return Optional.of("en");
            }
        };
        MfaPolicy policy = policy("REQUIRED", accountFacts);
        MfaCodeBudget budget = new MfaCodeBudget(limiter, 5, Duration.ofHours(1));
        auth = new StaffAuthService(
                adapters.login(), adapters.probe(), limiter, budget, policy, accounts, tokens, metrics, clock);
        ConcurrentMapCacheManager caches =
                new ConcurrentMapCacheManager(CacheRegistry.IAM_STAFF_MFA_CREDENTIALS.cacheName());
        TransactionTemplate transactions = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        StaffSecurityAudit audit = facts::add;
        StaffEmailSender mail = new StaffEmailSender() {
            @Override
            public Delivery send(StaffEmail email) {
                emails.add(email);
                return Delivery.SENT;
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        mfa = new StaffMfaService(
                accounts,
                adapters.probe(),
                adapters.login(),
                budget,
                policy,
                tokens,
                accountFacts,
                metrics,
                audit,
                mail,
                limiter,
                caches,
                transactions,
                clock);

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    private MfaPolicy policy(String enforcement, StaffAccountFacts accountFacts) {
        return new MfaPolicy(accountFacts, tenantId -> MfaRequirementMode.OFF, enforcement);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        logs.stop();
        realm.close();
    }

    // ================================================================ the first enrolment

    @Test
    @DisplayName(
            "a platform administrator who never enrolled signs in, is taken to enrolment, scans, enters the first code and arrives signed in")
    void aFirstEnrolmentThroughATicket() {
        ApiException refused = (ApiException) catchThrowable(() -> auth.signIn(PHONE, PASSWORD, null, "address-1"));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.MFA_ENROLMENT_REQUIRED);
        // The session Keycloak issued for the refused sign-in is revoked, not left to lapse.
        assertThat(realm.calls("REVOKE", FakeKeycloakMfaRealm.LOGIN_CLIENT)).hasSize(1);
        String ticket = (String) refused.properties().get("enrolmentTicket");
        String subject = mfa.subjectOfTicket(ticket);
        assertThat(subject).isEqualTo(admin.id);

        Enrolment enrolment = mfa.begin(subject, true, PASSWORD);
        String secret = secretOf(enrolment);
        assertThat(enrolment.otpauthUri())
                .startsWith("otpauth://totp/HorecaOS%3A")
                .endsWith("&issuer=HorecaOS");
        // The console's QR encoder holds 106 bytes in byte mode; a URI that does not fit renders no code at all.
        assertThat(enrolment.otpauthUri().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(106);
        assertThat(admin.otp)
                .as("nothing is registered until the first code is confirmed")
                .isEmpty();

        Confirmation confirmation = mfa.confirm(
                subject, true, enrolment.sealedSecret(), Totp.code(secret, clock.instant()), PASSWORD, "my phone");

        StaffSession arrived = confirmation.session();
        assertThat(arrived).as("a ticket enrolment arrives signed in").isNotNull();
        assertThat(java.util.Objects.requireNonNull(arrived).accessToken()).isNotBlank();
        assertThat(admin.otp).hasSize(1);
        assertThat(facts).extracting(StaffSecurityFact::actionCode).containsExactly("iam.staff.mfa.enrolled");
        assertThat(emails).hasSize(1);
        assertThat(emails.getFirst().subject()).contains("two-step");

        // Signs out and back in with a password and a code.
        auth.signOut(arrived.refreshToken());
        clock.advance(Duration.ofSeconds(31)); // the first code is spent for its thirty seconds
        ApiException asked = (ApiException) catchThrowable(() -> auth.signIn(PHONE, PASSWORD, null, "address-2"));
        assertThat(asked.errorCode()).isEqualTo(ErrorCode.MFA_REQUIRED);
        StaffSession back = auth.signIn(PHONE, PASSWORD, Totp.code(secret, clock.instant()), "address-2");
        assertThat(back.mfaEnrolmentOffered()).isFalse();
        assertThat(back.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("the key URI fits the QR encoder whatever the account is called, including a long email address")
    void theKeyUriFitsTheQrEncoder() {
        String longest = "a-very-long-local-part.and-a-subdomain@a-very-long-company-name.example.uz";

        String uri = StaffMfaService.otpauthUri(longest, "Zk3fT9qLw2XvB7mNc5Rd");

        assertThat(uri.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(106);
        assertThat(uri).contains("secret=").doesNotContain(longest);
        // A name made of characters that percent-encode to three bytes each still fits.
        assertThat(StaffMfaService.otpauthUri("@".repeat(40), "Zk3fT9qLw2XvB7mNc5Rd")
                        .getBytes(StandardCharsets.UTF_8)
                        .length)
                .isLessThanOrEqualTo(106);
    }

    @Test
    @DisplayName(
            "the authenticator secret is Keycloak's own representation, so a code computed from what the app was given signs in")
    void theSecretTheAppHoldsIsTheSecretKeycloakChecks() {
        Enrolment enrolment = mfa.begin(admin.id, false, PASSWORD);
        String secret = secretOf(enrolment);

        assertThat(secret).hasSize(20).matches("[A-Za-z0-9]{20}");
        // The QR code and the typed text carry the same Base32 of those bytes.
        assertThat(enrolment.otpauthUri()).contains("secret=" + enrolment.secret());
        assertThat(base32Decode(enrolment.secret())).isEqualTo(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a bad first code leaves no credential behind and the person is told")
    void aBadFirstCodeLeavesNothingBehind() {
        Enrolment enrolment = mfa.begin(admin.id, false, PASSWORD);

        ApiException thrown = (ApiException) catchThrowable(
                () -> mfa.confirm(admin.id, false, enrolment.sealedSecret(), "000000", PASSWORD, "phone"));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.MFA_CONFIRMATION_CODE_INVALID);
        assertThat(admin.otp).isEmpty();
        assertThat(realm.deletedCredentialTypes()).containsExactly("otp");
        assertThat(facts)
                .as("nothing was enrolled, so nothing is recorded as enrolled")
                .isEmpty();
        assertThat(emails).isEmpty();
        // And the person can try again with the same sealed secret and the right code.
        Confirmation retry = mfa.confirm(
                admin.id,
                false,
                enrolment.sealedSecret(),
                Totp.code(secretOf(enrolment), clock.instant()),
                PASSWORD,
                "phone");
        assertThat(retry.session())
                .as("from a session, no new session is handed back")
                .isNull();
        assertThat(admin.otp).hasSize(1);
    }

    @Test
    @DisplayName("the sealed secret cannot be replayed once confirmed, is useless on another account, and expires")
    void theSealedSecretIsSingleUseSingleAccountAndShortLived() {
        User other = realm.addUser("998907654321", PASSWORD, "other@horecaos.uz");
        Enrolment enrolment = mfa.begin(admin.id, false, PASSWORD);
        String secret = secretOf(enrolment);

        ApiException elsewhere = (ApiException) catchThrowable(() -> mfa.confirm(
                other.id, false, enrolment.sealedSecret(), Totp.code(secret, clock.instant()), PASSWORD, "phone"));
        assertThat(elsewhere.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(other.otp).isEmpty();

        mfa.confirm(admin.id, false, enrolment.sealedSecret(), Totp.code(secret, clock.instant()), PASSWORD, "phone");
        clock.advance(Duration.ofSeconds(31));
        ApiException replay = (ApiException) catchThrowable(() -> mfa.confirm(
                admin.id, false, enrolment.sealedSecret(), Totp.code(secret, clock.instant()), PASSWORD, "again"));
        assertThat(replay.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(admin.otp).as("the replay added nothing").hasSize(1);

        Enrolment fresh = mfa.begin(admin.id, false, PASSWORD);
        clock.advance(Duration.ofMinutes(11));
        ApiException late = (ApiException) catchThrowable(() -> mfa.confirm(
                admin.id, false, fresh.sealedSecret(), Totp.code(secretOf(fresh), clock.instant()), PASSWORD, "late"));
        assertThat(late.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("the current password is re-proved: a wrong one, or another account's, begins and confirms nothing")
    void thePasswordIsReProved() {
        User other = realm.addUser("998907654321", "another long passphrase", null);

        ApiException wrong = (ApiException) catchThrowable(() -> mfa.begin(admin.id, false, "not the password"));
        ApiException theirs =
                (ApiException) catchThrowable(() -> mfa.begin(admin.id, false, "another long passphrase"));

        assertThat(wrong.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INVALID);
        assertThat(theirs.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INVALID);
        Enrolment enrolment = mfa.begin(admin.id, false, PASSWORD);
        ApiException confirmWrong = (ApiException) catchThrowable(() -> mfa.confirm(
                admin.id,
                false,
                enrolment.sealedSecret(),
                Totp.code(secretOf(enrolment), clock.instant()),
                "nope",
                "x"));
        assertThat(confirmWrong.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INVALID);
        assertThat(admin.otp).isEmpty();
        assertThat(other.otp).isEmpty();
    }

    @Test
    @DisplayName(
            "a ticket opens the first enrolment only: an account that already holds a factor needs a session to add a second device")
    void aTicketIsForTheFirstEnrolmentOnly() {
        realm.enrol(admin, "abcdefghijklmnopqrst", "phone");

        ApiException thrown = (ApiException) catchThrowable(() -> mfa.begin(admin.id, true, PASSWORD));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(mfa.begin(admin.id, false, PASSWORD)).isNotNull();
    }

    @Test
    @DisplayName("an account holds at most two authenticators")
    void atMostTwo() {
        realm.enrol(admin, "abcdefghijklmnopqrst", "phone");
        realm.enrol(admin, "ABCDEFGHIJKLMNOPQRST", "tablet");

        ApiException thrown = (ApiException) catchThrowable(() -> mfa.begin(admin.id, false, PASSWORD));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName("the PROMPT phase offers enrolment without requiring it")
    void thePromptPhaseOffers() {
        StaffAccountFacts facts = platformFacts();
        StaffAuthService prompting = new StaffAuthService(
                realm.adapters().login(),
                realm.adapters().probe(),
                new InProcessRateLimiter(clock),
                new MfaCodeBudget(new InProcessRateLimiter(clock), 5, Duration.ofHours(1)),
                policy("PROMPT", facts),
                accounts,
                tokens,
                new MfaMetrics(meters),
                clock);

        StaffSession session = prompting.signIn(PHONE, PASSWORD, null, "address-1");

        assertThat(session.mfaEnrolmentOffered()).isTrue();
    }

    // ================================================================ the second device and removal

    @Test
    @DisplayName("a second authenticator is added by someone signed in, audited and emailed")
    void aSecondDevice() {
        realm.enrol(admin, "abcdefghijklmnopqrst", "phone");
        Enrolment enrolment = mfa.begin(admin.id, false, PASSWORD);

        mfa.confirm(
                admin.id,
                false,
                enrolment.sealedSecret(),
                Totp.code(secretOf(enrolment), clock.instant()),
                PASSWORD,
                "tablet");

        assertThat(admin.otp).hasSize(2);
        assertThat(facts)
                .extracting(StaffSecurityFact::actionCode)
                .containsExactly("iam.staff.mfa.authenticator_added");
        assertThat(mfa.ownStatus(admin.id).authenticators()).hasSize(2);
        // The proof session this confirmation opened was ended, not handed out.
        assertThat(realm.calls("REVOKE", FakeKeycloakMfaRealm.LOGIN_CLIENT)).isNotEmpty();
    }

    @Test
    @DisplayName(
            "removing an authenticator needs the password and a code, spends the same budget as sign-in, and never takes the last one")
    void removal() {
        String secretOne = "abcdefghijklmnopqrst";
        var first = realm.enrol(admin, secretOne, "phone");
        var second = realm.enrol(admin, "ABCDEFGHIJKLMNOPQRST", "tablet");

        ApiException wrongCode =
                (ApiException) catchThrowable(() -> mfa.remove(admin.id, second.id, PASSWORD, "000000"));
        assertThat(wrongCode.errorCode()).isEqualTo(ErrorCode.MFA_CONFIRMATION_CODE_INVALID);
        assertThat(admin.otp).hasSize(2);

        ApiException noPassword = (ApiException)
                catchThrowable(() -> mfa.remove(admin.id, second.id, "wrong", Totp.code(secretOne, clock.instant())));
        assertThat(noPassword.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INVALID);

        mfa.remove(admin.id, second.id, PASSWORD, Totp.code(secretOne, clock.instant()));
        assertThat(admin.otp).extracting(c -> c.id).containsExactly(first.id);
        assertThat(facts)
                .extracting(StaffSecurityFact::actionCode)
                .containsExactly("iam.staff.mfa.authenticator_removed");

        clock.advance(Duration.ofSeconds(31));
        ApiException last = (ApiException)
                catchThrowable(() -> mfa.remove(admin.id, first.id, PASSWORD, Totp.code(secretOne, clock.instant())));
        assertThat(last.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(admin.otp).hasSize(1);
    }

    @Test
    @DisplayName("removing an authenticator draws on the same code budget as sign-in")
    void removalSpendsTheSignInBudget() {
        String secretOne = "abcdefghijklmnopqrst";
        realm.enrol(admin, secretOne, "phone");
        var second = realm.enrol(admin, "ABCDEFGHIJKLMNOPQRST", "tablet");
        for (int attempt = 1; attempt <= 3; attempt++) {
            int n = attempt;
            var ignored = catchThrowable(() -> auth.signIn(PHONE, PASSWORD, "00000" + n, "address-" + n));
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            var ignored = catchThrowable(() -> mfa.remove(admin.id, second.id, PASSWORD, "999999"));
        }

        ApiException spent = (ApiException)
                catchThrowable(() -> mfa.remove(admin.id, second.id, PASSWORD, Totp.code(secretOne, clock.instant())));
        ApiException signInSpent = (ApiException)
                catchThrowable(() -> auth.signIn(PHONE, PASSWORD, Totp.code(secretOne, clock.instant()), "address-9"));

        assertThat(spent.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(signInSpent.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(admin.otp).hasSize(2);
    }

    // ================================================================ the code lockout

    @Test
    @DisplayName(
            "twenty wrong codes with the right password, from changing addresses, stop at the budget: Keycloak sees five, the owner signs in once it refills, and the alert has fired")
    void theCodeLockoutHoldsWhenKeycloaksCounterDoesNot() {
        String secret = "abcdefghijklmnopqrst";
        realm.enrol(admin, secret, "phone");
        realm.clearCalls();

        List<ErrorCode> answers = new ArrayList<>();
        for (int attempt = 1; attempt <= 20; attempt++) {
            int n = attempt;
            ApiException thrown = (ApiException)
                    catchThrowable(() -> auth.signIn(PHONE, PASSWORD, "%06d".formatted(100_000 + n), "rotating-" + n));
            answers.add(thrown.errorCode());
        }

        assertThat(answers.subList(0, 5)).containsOnly(ErrorCode.MFA_CODE_INVALID);
        assertThat(answers.subList(5, 20)).containsOnly(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(realm.codesSeenByLoginClient())
                .as("Keycloak is never asked about more than five")
                .hasSize(5);
        // Every attempt was followed by a successful probe: Keycloak's own counter never moved, and
        // the account is not locked. The budget is the platform's.
        assertThat(admin.failures).isZero();
        assertThat(admin.disabled).isFalse();
        // The alert's series.
        assertThat(meters.get(MfaMetrics.NAME)
                        .tag("step", "budget")
                        .tag("outcome", "exhausted")
                        .counter()
                        .count())
                .isEqualTo(15.0);

        // The owner, once the budget has refilled (a controlled clock), signs in with a right code.
        clock.advance(Duration.ofMinutes(13));
        StaffSession session = auth.signIn(PHONE, PASSWORD, Totp.code(secret, clock.instant()), "owner-address");
        assertThat(session.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName(
            "Keycloak's own counter, with no probe in the picture, is what the fake models: fifteen wrong codes each followed by a probe leave no failures")
    void theFakeIsAsUnforgivingAsTheRealThing() {
        realm.enrol(admin, "abcdefghijklmnopqrst", "phone");
        var login = realm.adapters().login();
        var probe = realm.adapters().probe();

        for (int attempt = 0; attempt < 15; attempt++) {
            login.signIn(PHONE, PASSWORD, "%06d".formatted(attempt));
            probe.verify(PHONE, PASSWORD);
        }
        assertThat(admin.failures).as("the probe's success clears the count").isZero();

        for (int attempt = 0; attempt < 8; attempt++) {
            login.signIn(PHONE, PASSWORD, "%06d".formatted(attempt));
        }
        assertThat(admin.disabled)
                .as("with no probe the eighth failure disables the account")
                .isTrue();
    }

    @Test
    @DisplayName(
            "a wrong password and an unknown name answer identically with or without a code, and never touch the login client")
    void theUniformFailureHolds() {
        realm.enrol(admin, "abcdefghijklmnopqrst", "phone");
        realm.clearCalls();

        ApiException wrong = (ApiException) catchThrowable(() -> auth.signIn(PHONE, "not it", null, "a-1"));
        ApiException wrongWithCode = (ApiException) catchThrowable(() -> auth.signIn(PHONE, "not it", "123456", "a-2"));
        ApiException unknown = (ApiException) catchThrowable(() -> auth.signIn("998900000000", "not it", null, "a-3"));
        ApiException unknownWithCode =
                (ApiException) catchThrowable(() -> auth.signIn("998900000000", "not it", "123456", "a-4"));

        for (ApiException thrown : List.of(wrong, wrongWithCode, unknown, unknownWithCode)) {
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
            assertThat(thrown.getMessage()).isEqualTo("Invalid credentials.");
            assertThat(thrown.properties()).isEmpty();
        }
        assertThat(realm.calls("TOKEN", FakeKeycloakMfaRealm.LOGIN_CLIENT)).isEmpty();
        assertThat(realm.codesSeenByLoginClient()).isEmpty();
        // One typo is one failure, as it was before the probe existed: not two, not a minute's lock.
        assertThat(admin.failures).isEqualTo(2);
        assertThat(admin.disabled).isFalse();
    }

    // ================================================================ recovery

    @Test
    @DisplayName("a reset removes every authenticator, ends sessions, audits with the reason, and emails the person")
    void aReset() {
        realm.enrol(admin, "abcdefghijklmnopqrst", "phone");
        realm.enrol(admin, "ABCDEFGHIJKLMNOPQRST", "tablet");

        var result = mfa.reset(
                new ResetCommand(admin.id, "another-administrator", "Lost phone, confirmed by voice call", null));

        assertThat(result.authenticatorsRemoved()).isEqualTo(2);
        assertThat(result.sessionsEnded()).isTrue();
        assertThat(result.personNotified()).isTrue();
        assertThat(admin.otp).isEmpty();
        assertThat(realm.calls().stream().map(FakeKeycloakMfaRealm.Call::kind))
                .anyMatch(kind -> kind.startsWith("ADMIN POST") && kind.endsWith("/logout"));
        assertThat(facts).hasSize(1);
        StaffSecurityFact fact = facts.getFirst();
        assertThat(fact.actionCode()).isEqualTo("iam.staff.mfa.reset");
        assertThat(fact.staffSubjectId()).isEqualTo("another-administrator");
        assertThat(fact.because()).isEqualTo("Lost phone, confirmed by voice call");
        assertThat(fact.before()).containsEntry("authenticators", 2);
        assertThat(fact.after()).containsEntry("authenticators", 0);
        assertThat(emails.getFirst().subject()).contains("reset");
        // The reset never touched the password.
        assertThat(realm.deletedCredentialTypes()).containsOnly("otp");
    }

    @Test
    @DisplayName("a reset account is asked to enrol again at the next sign-in, and can")
    void aResetAccountReEnrols() {
        realm.enrol(admin, "abcdefghijklmnopqrst", "phone");
        mfa.reset(new ResetCommand(admin.id, "another-administrator", "Lost phone", null));

        ApiException asked = (ApiException) catchThrowable(() -> auth.signIn(PHONE, PASSWORD, null, "address-1"));

        assertThat(asked.errorCode()).isEqualTo(ErrorCode.MFA_ENROLMENT_REQUIRED);
    }

    // ================================================================ what is never recorded

    @Test
    @DisplayName(
            "no code, secret, sealed token, password or authenticator label reaches an audit fact, an email or a log appender")
    void nothingSecretIsRecorded() {
        Enrolment enrolment = mfa.begin(admin.id, false, PASSWORD);
        String secret = secretOf(enrolment);
        String code = Totp.code(secret, clock.instant());
        mfa.confirm(admin.id, false, enrolment.sealedSecret(), code, PASSWORD, "Aziza's private phone");
        var ignored1 = catchThrowable(() -> auth.signIn(PHONE, PASSWORD, "424242", "a-1"));
        var ignored2 = catchThrowable(() -> auth.signIn(PHONE, "wrong password value", null, "a-2"));
        mfa.reset(new ResetCommand(admin.id, "another-administrator", "Lost phone", null));

        List<String> everything = new ArrayList<>();
        facts.forEach(fact -> everything.add(fact.toString() + fact.before() + fact.after() + fact.because()));
        emails.forEach(email -> everything.add(email.subject() + email.text() + email.html()));
        // The platform's own loggers: a framework's DEBUG line about a form body is not this code's to
        // answer for, and is off in every deployment; what this class writes is.
        synchronized (logs) {
            List.copyOf(logs.list).stream()
                    .filter(event -> event.getLoggerName().startsWith("uz.horecaos"))
                    .forEach(event -> everything.add(event.getFormattedMessage() + event.getThrowableProxy()));
        }
        String all = String.join("\n", everything);

        for (String sensitive : List.of(
                secret,
                enrolment.secret(),
                enrolment.sealedSecret(),
                code,
                "424242",
                PASSWORD,
                "wrong password value",
                "Aziza's private phone",
                "admin@horecaos.uz",
                PHONE)) {
            assertThat(all)
                    .as("must not contain " + sensitive.substring(0, Math.min(6, sensitive.length())))
                    .doesNotContain(sensitive);
        }
        assertThat(URLDecoder.decode(all, StandardCharsets.UTF_8)).doesNotContain(secret);
    }

    // ================================================================ helpers

    private StaffAccountFacts platformFacts() {
        return new StaffAccountFacts() {
            @Override
            public boolean holdsPlatformGrant(String subject) {
                return platformSubjects.contains(subject);
            }

            @Override
            public Map<UUID, Set<String>> rolesByTenant(String subject) {
                return Map.of();
            }

            @Override
            public boolean holdsRoleInTenant(String subject, UUID tenantId, String roleCode) {
                return false;
            }

            @Override
            public Optional<String> uiLocale(String subject) {
                return Optional.empty();
            }
        };
    }

    /** The twenty characters Keycloak stores, recovered from the Base32 the app is shown. */
    private static String secretOf(Enrolment enrolment) {
        return new String(base32Decode(enrolment.secret()), StandardCharsets.UTF_8);
    }

    static byte[] base32Decode(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static final class MovableClock extends Clock {

        private volatile Instant now;

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
