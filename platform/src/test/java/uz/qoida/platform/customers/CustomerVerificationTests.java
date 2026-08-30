package uz.qoida.platform.customers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.customers.api.CustomerAccountRef;
import uz.qoida.platform.customers.api.CustomerIdentityPolicy;
import uz.qoida.platform.customers.application.CodeProtection;
import uz.qoida.platform.customers.application.CustomerIdentityService;
import uz.qoida.platform.customers.application.CustomerProfileService.ContactType;
import uz.qoida.platform.customers.application.CustomerVerificationService;
import uz.qoida.platform.customers.application.CustomerVerificationService.Challenge;
import uz.qoida.platform.customers.application.CustomerVerificationService.Grant;
import uz.qoida.platform.customers.application.RandomVerificationCodeSource;
import uz.qoida.platform.customers.application.VerificationChallengeIssuer;
import uz.qoida.platform.customers.domain.ChallengeStatus;
import uz.qoida.platform.customers.domain.VerificationCode;
import uz.qoida.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.qoida.platform.customers.spi.VerificationCodeTransport;
import uz.qoida.platform.customers.spi.VerificationCodeTransport.Outcome;
import uz.qoida.platform.customers.spi.VerificationCodeTransport.VerificationMessage;
import uz.qoida.platform.iam.api.protection.FieldProtection;
import uz.qoida.platform.iam.api.protection.FieldProtection.ProtectionIntegrityException;
import uz.qoida.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.qoida.platform.iam.api.protection.ProtectedValue;
import uz.qoida.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.qoida.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.qoida.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;
import uz.qoida.platform.web.cache.InProcessRateLimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Customer identity: getting a code, proving a number, and becoming an account
 * (ADR 0015, ADR 0003, ADR 0029).
 *
 * <p>No database. The security properties here are conditions rather than
 * queries — an attempt is spent only while a challenge is live and under its
 * limit, a code settles exactly once, a grant redeems exactly once — and
 * {@link InMemoryVerificationChallengeStore} implements exactly those conditions,
 * so they can be driven past their edges without waiting on a container.
 *
 * <p>The encryption is real. {@link EnvelopeFieldProtection} over a throwaway
 * key-encryption key is what makes "the code cannot be read back" and "the number
 * is bound to its row" genuine assertions rather than two stubs agreeing with each
 * other.
 */
class CustomerVerificationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String ISSUER = "https://auth.qoida.uz/realms/qoida";
    private static final String SUBJECT = "keycloak-subject-1";
    private static final String PHONE = "+998901112233";
    private static final String CALLER = "caller-digest";

    private TickingClock clock;
    private InMemoryVerificationChallengeStore challenges;
    private CapturingTransport transport;
    private RecordingAuditRecorder audit;
    private FieldProtection protection;
    private CodeProtection codes;
    private JdbcCustomerStore customers;
    private CustomerIdentityService identity;
    private CustomerVerificationService verification;

    @BeforeEach
    void setUp() {
        clock = new TickingClock(Instant.parse("2026-08-25T09:00:00Z"));
        challenges = new InMemoryVerificationChallengeStore();
        transport = new CapturingTransport();
        audit = new RecordingAuditRecorder();
        protection = fieldProtection("a-test-key-encryption-key");
        codes = new CodeProtection(protection);
        customers = mock(JdbcCustomerStore.class);
        identity = mock(CustomerIdentityService.class);

        verification = service(transport);
    }

    private CustomerVerificationService service(VerificationCodeTransport wired) {
        VerificationChallengeIssuer issuer = new VerificationChallengeIssuer(
                challenges, codes, protection, clock,
                Duration.ofMinutes(5), 5, Duration.ofMinutes(1), Duration.ofHours(1), 5);

        return new CustomerVerificationService(issuer, challenges, codes, customers, identity,
                protection, new InProcessRateLimiter(clock), new RandomVerificationCodeSource(),
                provider(wired), audit, clock, Duration.ofMinutes(10));
    }

    // ------------------------------------------------------------ storing a code

    @Test
    @DisplayName("the code is never stored in a form it can be read back from")
    void theCodeIsNotRecoverableFromTheRow() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String code = transport.lastCode();
        InMemoryVerificationChallengeStore.Row row =
                challenges.row(challenge.challengeId()).orElseThrow();

        // Nothing on the row is the code, and nothing on it contains the code.
        assertThat(row.codeHash()).isNotEqualTo(code).doesNotContain(code);
        assertThat(row.destinationValue()).doesNotContain(code);
    }

    @Test
    @DisplayName("the stored code hash is worthless without the key, which is not in the database")
    void theCodeHashIsKeyed() {
        // The point of a keyed MAC over a plain digest. Six digits is a domain of
        // one million: an unkeyed hash of the code would be the code, enumerable
        // from a table dump in milliseconds. Recomputing it here under a different
        // key-encryption key — which is what an attacker holding only the database
        // has — produces something unrelated.
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String code = transport.lastCode();

        CodeProtection withoutTheKey =
                new CodeProtection(fieldProtection("a-different-key-encryption-key"));

        assertThat(withoutTheKey.hash(TENANT, challenge.challengeId(), code))
                .isNotEqualTo(codes.hash(TENANT, challenge.challengeId(), code));
    }

    @Test
    @DisplayName("two challenges holding the same code store different hashes")
    void theCodeHashIsSaltedPerChallenge() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(codes.hash(TENANT, first, "123456"))
                .isNotEqualTo(codes.hash(TENANT, second, "123456"));

        // And a right code under the wrong challenge is still wrong, which is what
        // stops a code observed on one challenge being replayed against another.
        assertThat(codes.matches(TENANT, second, "123456", codes.hash(TENANT, first, "123456")))
                .isFalse();
    }

    @Test
    @DisplayName("a code is six digits, zero-padded")
    void codesAreSixDigits() {
        for (int draw = 0; draw < 200; draw++) {
            assertThat(VerificationCode.issue()).hasSize(6).containsOnlyDigits();
        }
        assertThat(VerificationCode.isWellFormed("00042")).isFalse();
        assertThat(VerificationCode.isWellFormed("0004a2")).isFalse();
        assertThat(VerificationCode.isWellFormed("000042")).isTrue();
    }

    // ---------------------------------------------------------------- verifying

    @Test
    @DisplayName("the right code yields a grant, and the same code cannot be used twice")
    void aCodeIsSingleUse() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String code = transport.lastCode();

        Grant grant = verification.verify(TENANT, challenge.challengeId(), code, CALLER);
        assertThat(grant.secret()).isNotBlank();

        Throwable second = catchThrowable(
                () -> verification.verify(TENANT, challenge.challengeId(), code, CALLER));

        assertThat(second).isInstanceOf(ApiException.class);
        assertThat(((ApiException) second).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("five wrong codes end the challenge, and the sixth attempt is not offered")
    void attemptsAreLimitedPerChallenge() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String code = transport.lastCode();

        for (int attempt = 1; attempt <= 4; attempt++) {
            Throwable refusal = catchThrowable(() -> verification.verify(
                    TENANT, challenge.challengeId(), wrong(code), CALLER));
            assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
            assertThat(((ApiException) refusal).properties()).containsKey("attemptsRemaining");
        }

        // The fifth wrong answer settles it, and says so the same way an unknown
        // challenge does.
        Throwable last = catchThrowable(() -> verification.verify(
                TENANT, challenge.challengeId(), wrong(code), CALLER));
        assertThat(((ApiException) last).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);

        assertThat(challenges.row(challenge.challengeId()).orElseThrow().status())
                .isEqualTo(ChallengeStatus.EXHAUSTED);

        // And the correct code no longer works, which is the property that matters:
        // exhaustion is not a pause.
        Throwable afterwards = catchThrowable(
                () -> verification.verify(TENANT, challenge.challengeId(), code, CALLER));
        assertThat(((ApiException) afterwards).errorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("a malformed code is refused without spending an attempt")
    void aMalformedCodeCostsNothing() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);

        Throwable refusal = catchThrowable(
                () -> verification.verify(TENANT, challenge.challengeId(), "not-a-code", CALLER));

        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(challenges.row(challenge.challengeId()).orElseThrow().attemptsUsed()).isZero();
    }

    @Test
    @DisplayName("a code stops working when its five minutes are up")
    void aCodeExpires() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String code = transport.lastCode();

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        Throwable refusal = catchThrowable(
                () -> verification.verify(TENANT, challenge.challengeId(), code, CALLER));

        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("a challenge belongs to its tenant; another tenant cannot spend it")
    void aChallengeIsTenantScoped() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String code = transport.lastCode();

        Throwable refusal = catchThrowable(() -> verification.verify(
                UUID.randomUUID(), challenge.challengeId(), code, CALLER));

        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(challenges.row(challenge.challengeId()).orElseThrow().attemptsUsed())
                .as("a challenge named by the wrong tenant is not even worth an attempt")
                .isZero();
    }

    // ----------------------------------------------------------- rate limiting

    @Test
    @DisplayName("asking again retires the earlier challenge, so the attempt limit stays five")
    void aNewChallengeSupersedesTheOldOne() {
        Challenge first = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String firstCode = transport.lastCode();

        clock.advance(Duration.ofMinutes(2));
        Challenge second = verification.issue(TENANT, BRAND, PHONE, CALLER);

        assertThat(challenges.row(first.challengeId()).orElseThrow().status())
                .isEqualTo(ChallengeStatus.SUPERSEDED);
        assertThat(challenges.row(second.challengeId()).orElseThrow().status())
                .isEqualTo(ChallengeStatus.PENDING);

        // Without this, three requests would leave three live challenges with five
        // attempts each and the limit would be whatever an attacker will pay for.
        Throwable refusal = catchThrowable(
                () -> verification.verify(TENANT, first.challengeId(), firstCode, CALLER));
        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("a second code for the same number inside a minute is refused")
    void resendsAreThrottledPerNumber() {
        verification.issue(TENANT, BRAND, PHONE, CALLER);

        clock.advance(Duration.ofSeconds(30));
        Throwable refusal = catchThrowable(
                () -> verification.issue(TENANT, BRAND, PHONE, "another-caller"));

        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(((ApiException) refusal).properties()).containsKey("retryAfterSeconds");
        assertThat(transport.sent()).hasSize(1);
    }

    @Test
    @DisplayName("the per-number budget counts challenges however they ended")
    void theDestinationBudgetIsNotRefundedByBurningAChallenge() {
        for (int round = 0; round < 5; round++) {
            Challenge challenge = verification.issue(TENANT, BRAND, PHONE, "caller-" + round);
            String code = transport.lastCode();
            // Spend it, so a budget that only counted live challenges would let
            // this loop run forever.
            verification.verify(TENANT, challenge.challengeId(), code, "caller-" + round);
            clock.advance(Duration.ofMinutes(2));
        }

        Throwable refusal = catchThrowable(
                () -> verification.issue(TENANT, BRAND, PHONE, "caller-last"));

        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(transport.sent()).hasSize(5);
    }

    @Test
    @DisplayName("one caller is limited across different numbers, which no per-number budget sees")
    void oneCallerIsLimitedAcrossNumbers() {
        List<Throwable> refusals = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String number = "+99890111%04d".formatted(index);
            refusals.add(catchThrowable(() -> verification.issue(TENANT, BRAND, number, CALLER)));
        }

        assertThat(refusals.stream().filter(failure -> failure != null).toList())
                .as("a walk down a list of numbers spends no number's budget twice, so only "
                        + "the per-caller limit can stop it")
                .isNotEmpty();
        assertThat(transport.sent()).hasSizeLessThan(8);
    }

    // ----------------------------------------------------------- enumeration

    @Test
    @DisplayName("asking for a code cannot reveal whether the number has an account")
    void issuanceNeverLooksAtTheCustomerTables() {
        verification.issue(TENANT, BRAND, PHONE, CALLER);

        // The strongest form this assertion can take. Enumeration resistance here
        // is not a rule somebody remembered to apply — the issuance path does not
        // read the account or contact tables at all, so there is nothing for it to
        // leak, and this fails the moment that changes.
        verifyNoInteractions(customers);
        verifyNoInteractions(identity);
    }

    @Test
    @DisplayName("nothing on the issuance path reads an account by phone number")
    void identityIsNeverResolvedByPhone() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        verification.verify(TENANT, challenge.challengeId(), transport.lastCode(), CALLER);

        // ADR 0015 calls matching a customer on a phone the most dangerous option
        // available: recycled numbers change owner and households share a handset.
        verify(customers, never()).accountsWithContact(any(), anyString(), anyString());
    }

    // ---------------------------------------------------- personal data handling

    @Test
    @DisplayName("the number is encrypted, and its ciphertext will not open on another row")
    void theNumberIsBoundToItsOwnRow() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        InMemoryVerificationChallengeStore.Row row =
                challenges.row(challenge.challengeId()).orElseThrow();

        assertThat(row.destinationValue()).doesNotContain(PHONE).doesNotContain("901112233");

        RecordRef itsOwn = new RecordRef(
                "customer.verification_challenges", "destination_encrypted", challenge.challengeId());
        assertThat(protection.reveal(TENANT,
                ProtectedValue.deserialize(row.destinationValue()), itsOwn, "TEST"))
                .isEqualTo(PHONE);

        RecordRef somebodyElses = new RecordRef(
                "customer.verification_challenges", "destination_encrypted", UUID.randomUUID());
        assertThat(catchThrowable(() -> protection.reveal(TENANT,
                ProtectedValue.deserialize(row.destinationValue()), somebodyElses, "TEST")))
                .isInstanceOf(ProtectionIntegrityException.class);
    }

    @Test
    @DisplayName("neither the number nor the code can escape through a toString")
    void nothingPrintsTheNumberOrTheCode() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String code = transport.lastCode();
        VerificationMessage message = transport.sent().getLast();

        assertThat(message.toString()).doesNotContain(PHONE).doesNotContain(code);
        assertThat(challenge.toString()).doesNotContain(PHONE);

        Grant grant = verification.verify(TENANT, challenge.challengeId(), code, CALLER);
        assertThat(grant.toString()).doesNotContain(grant.secret());
    }

    @Test
    @DisplayName("no audit fact carries a number, a code, or a grant")
    void auditEvidenceIsIdentifiersOnly() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        String code = transport.lastCode();
        Grant grant = verification.verify(TENANT, challenge.challengeId(), code, CALLER);

        when(identity.resolve(TENANT, BRAND, ISSUER, SUBJECT)).thenReturn(
                new CustomerIdentityService.Resolution(new CustomerAccountRef(ACCOUNT, TENANT),
                        true, CustomerIdentityPolicy.TENANT_SHARED));
        verification.redeem(TENANT, BRAND, grant.secret(), ISSUER, SUBJECT);

        assertThat(audit.facts()).isNotEmpty();
        for (AuditFact fact : audit.facts()) {
            String rendered = String.valueOf(fact.changeDocument());
            assertThat(rendered).doesNotContain(PHONE).doesNotContain(code)
                    .doesNotContain(grant.secret());
        }
    }

    @Test
    @DisplayName("a number that is not an Uzbek mobile is refused, and the message does not echo it")
    void unroutableNumbersAreRefused() {
        for (String rejected : List.of("+12025550143", "+9989011122", "not a number", "+998")) {
            Throwable refusal = catchThrowable(
                    () -> verification.issue(TENANT, BRAND, rejected, CALLER));

            assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            // Compared to a constant rather than merely searched for the input.
            // A message assembled from what arrived would eventually put a phone
            // number into an ADR 0031 problem document, which is a response body,
            // an access log and a browser console at once; a constant cannot.
            assertThat(refusal.getMessage())
                    .isEqualTo("That is not an Uzbek mobile number. Expected the +998 form.");
        }
        assertThat(transport.sent()).isEmpty();
    }

    @Test
    @DisplayName("the same number written three ways gets one budget, not three")
    void spellingsOfOneNumberShareABudget() {
        verification.issue(TENANT, BRAND, "+998 90 111-22-33", CALLER);
        clock.advance(Duration.ofMinutes(2));

        Throwable second = catchThrowable(() -> {
            verification.issue(TENANT, BRAND, "998901112233", CALLER);
            clock.advance(Duration.ofMinutes(2));
            verification.issue(TENANT, BRAND, "901112233", CALLER);
            clock.advance(Duration.ofMinutes(2));
            verification.issue(TENANT, BRAND, "+998901112233", CALLER);
            clock.advance(Duration.ofMinutes(2));
            verification.issue(TENANT, BRAND, "(90) 111 22 33", CALLER);
        });

        assertThat(second).as("five spellings is five challenges; the sixth is over budget")
                .isNull();
        assertThat(catchThrowable(() -> verification.issue(TENANT, BRAND, PHONE, CALLER)))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------- the SMS seam

    @Test
    @DisplayName("with no SMS adapter wired, nothing is issued and no challenge is left behind")
    void anUnwiredDeploymentIssuesNothing() {
        CustomerVerificationService unwired = service(null);

        Throwable refusal = catchThrowable(() -> unwired.issue(TENANT, BRAND, PHONE, CALLER));

        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(((ApiException) refusal).properties()).containsEntry("reason", "NO_TRANSPORT");
        assertThat(challenges.all())
                .as("a challenge whose code was never sent must not charge the customer's budget")
                .isEmpty();
    }

    @Test
    @DisplayName("a gateway failure withdraws the challenge so the customer can try again at once")
    void aFailedSendCostsTheCustomerNothing() {
        CustomerVerificationService failing =
                service(message -> Outcome.unavailable("GATEWAY_TIMEOUT"));

        assertThat(catchThrowable(() -> failing.issue(TENANT, BRAND, PHONE, CALLER)))
                .isInstanceOf(ApiException.class);
        assertThat(challenges.all()).isEmpty();

        // And the retry works immediately: the resend interval is measured from a
        // challenge that exists, and the withdrawn one does not.
        assertThat(verification.issue(TENANT, BRAND, PHONE, CALLER).challengeId()).isNotNull();
    }

    // ---------------------------------------------------------------- redeeming

    @Test
    @DisplayName("redeeming links the principal to an account and attaches the proven number")
    void redeemingCreatesTheAccountAndTheVerifiedContact() {
        Grant grant = provenNumber();

        when(identity.resolve(TENANT, BRAND, ISSUER, SUBJECT)).thenReturn(
                new CustomerIdentityService.Resolution(new CustomerAccountRef(ACCOUNT, TENANT),
                        true, CustomerIdentityPolicy.TENANT_SHARED));
        when(customers.markContactVerified(any(), any(), anyString(), anyString(), any()))
                .thenReturn(0);
        when(customers.hasPrimaryContact(any(), any(), anyString())).thenReturn(false);

        CustomerVerificationService.Redemption redemption =
                verification.redeem(TENANT, BRAND, grant.secret(), ISSUER, SUBJECT);

        assertThat(redemption.account().accountId()).isEqualTo(ACCOUNT);
        assertThat(redemption.created()).isTrue();

        // Resolution is on issuer and subject, never on the number.
        verify(identity).resolve(TENANT, BRAND, ISSUER, SUBJECT);
        verify(customers).insertVerifiedContactPoint(any(), eq(TENANT), eq(ACCOUNT),
                eq(ContactType.PHONE.name()),
                // The domain is spelled out rather than read off the enum, so this
                // pins the value a contact-point lookup will later hash under: if
                // the two ever drift, a number proved by OTP stops matching the
                // contact point stored for it.
                eq(protection.lookupHash(TENANT, "customer.contact.phone", PHONE)),
                anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("a number the account already holds is promoted rather than duplicated")
    void anExistingContactIsPromoted() {
        Grant grant = provenNumber();

        when(identity.resolve(TENANT, BRAND, ISSUER, SUBJECT)).thenReturn(
                new CustomerIdentityService.Resolution(new CustomerAccountRef(ACCOUNT, TENANT),
                        false, CustomerIdentityPolicy.TENANT_SHARED));
        when(customers.markContactVerified(any(), any(), anyString(), anyString(), any()))
                .thenReturn(1);

        verification.redeem(TENANT, BRAND, grant.secret(), ISSUER, SUBJECT);

        verify(customers, never()).insertVerifiedContactPoint(any(), any(), any(), anyString(),
                anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("a grant is single-use")
    void aGrantRedeemsOnce() {
        Grant grant = provenNumber();
        when(identity.resolve(TENANT, BRAND, ISSUER, SUBJECT)).thenReturn(
                new CustomerIdentityService.Resolution(new CustomerAccountRef(ACCOUNT, TENANT),
                        true, CustomerIdentityPolicy.TENANT_SHARED));

        verification.redeem(TENANT, BRAND, grant.secret(), ISSUER, SUBJECT);

        Throwable second = catchThrowable(
                () -> verification.redeem(TENANT, BRAND, grant.secret(), ISSUER, SUBJECT));

        assertThat(((ApiException) second).errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("a grant proved at one brand is refused at another")
    void aGrantIsBoundToItsBrand() {
        Grant grant = provenNumber();

        Throwable refusal = catchThrowable(
                () -> verification.redeem(TENANT, OTHER_BRAND, grant.secret(), ISSUER, SUBJECT));

        // Under BRAND_ISOLATED the account created at another brand is a different
        // person's account in every sense that matters.
        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        verifyNoInteractions(identity);
    }

    @Test
    @DisplayName("a grant expires")
    void aGrantExpires() {
        Grant grant = provenNumber();
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThat(catchThrowable(
                () -> verification.redeem(TENANT, BRAND, grant.secret(), ISSUER, SUBJECT)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(identity);
    }

    @Test
    @DisplayName("a guessed grant is refused without touching identity")
    void anUnknownGrantIsRefused() {
        provenNumber();

        assertThat(catchThrowable(
                () -> verification.redeem(TENANT, BRAND, "not-a-real-grant", ISSUER, SUBJECT)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(identity);
    }

    // ------------------------------------------------------------------ helpers

    private Grant provenNumber() {
        Challenge challenge = verification.issue(TENANT, BRAND, PHONE, CALLER);
        return verification.verify(TENANT, challenge.challengeId(), transport.lastCode(), CALLER);
    }

    /** A code guaranteed to be wrong, and still six digits. */
    private static String wrong(String code) {
        char first = code.charAt(0);
        return (first == '0' ? '1' : '0') + code.substring(1);
    }

    private static FieldProtection fieldProtection(String kek) {
        return new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("qoida.secrets.data_encryption.platform.kek", kek)::get,
                        Clock.systemUTC()),
                "local"));
    }

    private static ObjectProvider<VerificationCodeTransport> provider(
            VerificationCodeTransport transport) {

        return new ObjectProvider<>() {
            @Override
            public VerificationCodeTransport getObject() throws BeansException {
                return Optional.ofNullable(transport).orElseThrow();
            }

            @Override
            public VerificationCodeTransport getObject(Object... args) throws BeansException {
                return getObject();
            }

            @Override
            public VerificationCodeTransport getIfAvailable() throws BeansException {
                return transport;
            }

            @Override
            public VerificationCodeTransport getIfUnique() throws BeansException {
                return transport;
            }
        };
    }

    /** Captures what would have been sent, which is the only place the code exists. */
    private static final class CapturingTransport implements VerificationCodeTransport {

        private final List<VerificationMessage> sent = new ArrayList<>();

        @Override
        public Outcome send(VerificationMessage message) {
            sent.add(message);
            return Outcome.accepted();
        }

        List<VerificationMessage> sent() {
            return sent;
        }

        String lastCode() {
            return sent.getLast().code();
        }
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {

        private final List<AuditFact> facts = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }

        List<AuditFact> facts() {
            return facts;
        }
    }

    /** A clock a test can push forward, so expiry is exercised rather than waited for. */
    private static final class TickingClock extends Clock {

        private Instant now;

        private TickingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
