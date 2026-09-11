package uz.horecaos.platform.iam.application.passwordresets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.audit.StaffSecurityFact;
import uz.horecaos.platform.iam.api.mail.StaffEmail;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcPasswordResetStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0098 end to end against the migrated schema: asked for from a sign-in
 * page, emailed by the relay with a link only the email holds, inspected,
 * spent once, and every other session of the account ended.
 *
 * <p>Shaped after {@code OwnerInvitationFlowTests}, whose mechanism this
 * reuses. The identity provider and the mail server are the two fakes and both
 * are narrow: the accounts fake keeps what Keycloak would and records which
 * operations were asked of it, which is how the "a reset changes the password
 * and nothing else" property below is actually checked rather than assumed.
 * Every duration -- the link's sixty minutes, the request cooldown, the retry
 * backoff -- is lived through by moving the clock.
 *
 * <p>The service is built over a real {@link TransactionTemplate} rather than
 * a bare {@code JdbcClient}, because two of the properties below are about
 * transactions: that an accept and a competing accept cannot cross, and that a
 * request's two writes land together. A fixture where every statement
 * autocommits would agree with the code whatever the code did.
 */
class PasswordResetFlowTests {

    private static final Pattern LINK =
            Pattern.compile("https://(ops|cp)\\.test/reset-password#token=([A-Za-z0-9_-]{43})");

    private static final String SUBJECT = "cashier-subject";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private FakeAccounts accounts;
    private RecordingSender mailer;
    private List<StaffSecurityFact> facts;
    private JdbcPasswordResetStore store;
    private TransactionTemplate transactions;
    private PasswordResetService resets;
    private PasswordResetRelay relay;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE iam.password_resets").update();
        clock = new MovableClock(Instant.parse("2026-09-11T09:00:00Z"));
        accounts = new FakeAccounts();
        mailer = new RecordingSender();
        facts = Collections.synchronizedList(new ArrayList<>());
        store = new JdbcPasswordResetStore(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        resets = new PasswordResetService(store, accounts, facts::add, transactions, clock);
        relay = new PasswordResetRelay(
                store, accounts, mailer, facts::add, clock, "https://ops.test/", "https://cp.test");

        accounts.put(SUBJECT, "dilnoza.karimova@example.uz", "dilnoza");
    }

    @Test
    @DisplayName("asked for, emailed with a link only the email holds, inspected, spent once, sessions ended")
    void aStaffMemberResetsTheirPassword() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "uz", "corr");
        assertThat(store.forSubject(SUBJECT).orElseThrow().status()).isEqualTo("QUEUED");

        assertThat(relay.runOnce()).isEqualTo(1);
        StaffEmail mail = mailer.last();
        assertThat(mail.to()).isEqualTo("dilnoza.karimova@example.uz");
        assertThat(mail.subject()).isEqualTo("HorecaOS parolingizni tiklash");
        String token = tokenIn(mail.text());
        assertThat(mail.html()).contains(token);
        assertThat(mail.text())
                .as("the email never repeats the address or the user name back")
                .doesNotContain("dilnoza");

        assertThat(jdbc.sql("SELECT row_to_json(r)::text FROM iam.password_resets r")
                        .query(String.class)
                        .single())
                .as("the table holds the token's hash, never the token and never the address")
                .doesNotContain(token)
                .doesNotContain("dilnoza")
                .contains(PasswordResetService.hash(token));

        var inspection = resets.inspect(token);
        assertThat(inspection.console()).isEqualTo("OPERATIONS");
        assertThat(inspection.maskedLogin()).isEqualTo("d***a@example.uz");
        assertThat(inspection.locale()).isEqualTo("uz");
        assertThat(store.forSubject(SUBJECT).orElseThrow().openedAt()).isNotNull();

        resets.accept(token, "a-long-enough-passphrase", "corr");

        assertThat(accounts.passwords).containsEntry(SUBJECT, "a-long-enough-passphrase");
        assertThat(accounts.loggedOut).containsExactly(SUBJECT);
        assertThat(accounts.setUp)
                .as("a reset is not a setup: it must not touch the name or the verified flag")
                .isEmpty();
        assertThat(store.forSubject(SUBJECT).orElseThrow().status()).isEqualTo("ACCEPTED");

        assertThatThrownBy(() -> resets.accept(token, "another-long-passphrase", "corr"))
                .as("a spent link is spent")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "INVALID"));

        assertThat(facts)
                .extracting(StaffSecurityFact::actionCode)
                .containsExactly(
                        "iam.password_reset.requested", "iam.password_reset.sent", "iam.password_reset.accepted");
        assertThat(fact("iam.password_reset.accepted").changed())
                .as("the fact says whether the sessions were actually ended, rather than asserting it")
                .containsEntry("sessionsEnded", true);
    }

    /**
     * The unauthenticated request endpoint has no actor, so its fact must not
     * claim one. Recording the account's own subject would let a stranger who
     * knows an address write append-only evidence that its owner asked for
     * this themselves -- ten times a minute, in a table operations cannot
     * prune. Accepting is different and is attributed to the staff member:
     * possession of the emailed token is evidence about them.
     */
    @Test
    @DisplayName("a request is attributed to the surface, not to the account it names; an accept to the account")
    void anAnonymousRequestIsNotRecordedAsTheAccountHoldersOwnAction() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "caller-address-hash");

        StaffSecurityFact requested = fact("iam.password_reset.requested");
        assertThat(requested.staffSubjectId())
                .as("an anonymous caller must not be recorded as the staff member")
                .isNull();
        assertThat(requested.service()).isEqualTo("staff-password-reset-request");
        assertThat(requested.correlationId())
                .as("what an investigator joins a burst of these by is the caller, not a per-request value")
                .isEqualTo("caller-address-hash");
        assertThat(requested.changed().toString())
                .as("and never the login that was typed")
                .doesNotContain("dilnoza");

        relay.runOnce();
        resets.accept(tokenIn(mailer.last().text()), "a-long-enough-passphrase", "corr");
        assertThat(fact("iam.password_reset.accepted").staffSubjectId())
                .as("holding the emailed token is evidence about the account holder")
                .isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("an email address resolves the same account a user name does, and the console picks the origin")
    void eitherLoginWorksAndTheConsoleDecidesTheLink() {
        resets.request("dilnoza.karimova@example.uz", StaffConsole.CONTROL_PLANE, "en", "corr");
        relay.runOnce();

        assertThat(mailer.last().subject()).isEqualTo("Reset your HorecaOS password");
        assertThat(mailer.last().text())
                .as("a control-plane reset comes back to the control plane, not to operations")
                .contains("https://cp.test/reset-password#token=")
                .doesNotContain("ops.test");
        assertThat(resets.inspect(tokenIn(mailer.last().text())).console()).isEqualTo("CONTROL_PLANE");
    }

    @Test
    @DisplayName("a login nobody holds queues nothing and sends nothing, and says so no differently")
    void anUnknownLoginIsANonEvent() {
        resets.request("nobody", StaffConsole.OPERATIONS, "ru", "corr");

        assertThat(store.forSubject(SUBJECT)).isEmpty();
        assertThat(relay.runOnce()).isZero();
        assertThat(mailer.sent).isEmpty();
        assertThat(facts)
                .as("not even an audit fact, which would be a record of who exists")
                .isEmpty();
    }

    /**
     * The answer is identical for a login that names an account and one that
     * does not; so is the work behind it. {@code findByLogin} would read the
     * account as well as search for it -- two further admin round trips, but
     * only for a login that resolves, which is a difference in latency on the
     * one bit this endpoint exists to hide.
     */
    @Test
    @DisplayName("a known login costs the identity provider exactly what an unknown one does")
    void thereIsNothingToTimeBetweenAKnownAndAnUnknownLogin() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        int forAnAccountThatExists = accounts.identityCalls.getAndSet(0);

        resets.request("nobody-at-all", StaffConsole.OPERATIONS, "ru", "corr");

        assertThat(accounts.identityCalls.get()).isEqualTo(forAnAccountThatExists);
        assertThat(accounts.subjectLookups.get())
                .as("it is the subject lookup that is used on this path")
                .isEqualTo(2);
        assertThat(accounts.accountReads.get())
                .as("reading the account would cost two admin round trips the request path has no use for")
                .isZero();
    }

    @Test
    @DisplayName("an identity provider that is down is swallowed rather than raised")
    void anIdentityOutageIsNotAnError() {
        accounts.unavailable = true;

        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");

        assertThat(store.forSubject(SUBJECT)).isEmpty();
        assertThat(mailer.sent).isEmpty();
    }

    @Test
    @DisplayName("asking again once the cooldown is over replaces the link: the first stops working, one row still")
    void asecondRequestReplacesTheFirst() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        relay.runOnce();
        String first = tokenIn(mailer.last().text());

        clock.advance(PasswordResetService.REQUEST_COOLDOWN);
        resets.request("dilnoza", StaffConsole.CONTROL_PLANE, "en", "corr");

        assertThatThrownBy(() -> resets.inspect(first))
                .as("the link already emailed stops working the moment a new one is asked for")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "INVALID"));
        assertThat(jdbc.sql("SELECT count(*) FROM iam.password_resets")
                        .query(Integer.class)
                        .single())
                .as("one live reset per account is the table's own unique key, not a convention")
                .isEqualTo(1);

        relay.runOnce();
        String second = tokenIn(mailer.last().text());
        assertThat(second).isNotEqualTo(first);
        assertThat(resets.inspect(second).console())
                .as("the newest request decides the console too")
                .isEqualTo("CONTROL_PLANE");
    }

    /**
     * The endpoint is unauthenticated, so "ask again" is something a stranger
     * can do on somebody else's behalf. Without this, posting a known staff
     * address every few seconds both kills the link its owner is holding and
     * queues another email to them, indefinitely, inside the per-address rate
     * limit and from one machine.
     */
    @Test
    @DisplayName("asking again while a link delivered minutes ago is live changes nothing and sends nothing")
    void aLiveLinkSurvivesAnImmediateSecondRequest() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        relay.runOnce();
        String emailed = tokenIn(mailer.last().text());
        var sent = store.forSubject(SUBJECT).orElseThrow();

        clock.advance(PasswordResetService.REQUEST_COOLDOWN.minusSeconds(1));
        resets.request("dilnoza", StaffConsole.CONTROL_PLANE, "en", "corr");

        var after = store.forSubject(SUBJECT).orElseThrow();
        assertThat(after.status()).isEqualTo("SENT");
        assertThat(after.tokenHash()).isEqualTo(sent.tokenHash());
        assertThat(after.expiresAt()).isEqualTo(sent.expiresAt());
        assertThat(after.sentAt()).isEqualTo(sent.sentAt());
        assertThat(after.console())
                .as("a second request inside the cooldown must not even move the link to another console")
                .isEqualTo("OPERATIONS");
        assertThat(resets.inspect(emailed).console())
                .as("the link in the person's mailbox still works")
                .isEqualTo("OPERATIONS");
        assertThat(relay.runOnce())
                .as("and nothing is queued, so no second email goes out")
                .isZero();
        assertThat(mailer.sent).hasSize(1);
        assertThat(facts)
                .extracting(StaffSecurityFact::actionCode)
                .as("the suppression is recorded, because a burst of them is the abuse this exists to stop")
                .contains("iam.password_reset.request_suppressed");

        clock.advance(Duration.ofSeconds(2));
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        assertThat(store.forSubject(SUBJECT).orElseThrow().status())
                .as("once the cooldown is over, somebody who never got the first email is served")
                .isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("a link works for sixty minutes from the send, not from the request")
    void aLinkExpires() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        // A mail server that was down for forty minutes: the hour has to start
        // when the link is emailed, or a retried send delivers a link that is
        // already most of the way dead.
        clock.advance(Duration.ofMinutes(40));
        Instant sentAt = clock.instant();
        relay.runOnce();
        String token = tokenIn(mailer.last().text());

        assertThat(resets.inspect(token).expiresAt())
                .as("the hour starts when the link is emailed, not when it was asked for")
                .isEqualTo(sentAt.plus(PasswordResetService.LINK_LIFETIME).toString());

        clock.advance(Duration.ofMinutes(59));
        assertThat(resets.inspect(token).console()).isEqualTo("OPERATIONS");

        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> resets.inspect(token))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "EXPIRED"));
        assertThatThrownBy(() -> resets.accept(token, "a-long-enough-passphrase", "corr"))
                .as("and an expired link cannot set a password either")
                .isInstanceOf(ApiException.class);
        assertThat(accounts.passwords).isEmpty();
    }

    @Test
    @DisplayName("a refused password spends nothing: the link still works and no session is ended")
    void aRefusedPasswordChangesNothing() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        relay.runOnce();
        String token = tokenIn(mailer.last().text());
        accounts.refuse = true;

        assertThatThrownBy(() -> resets.accept(token, "short", "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties())
                                .containsEntry("policy", "invalidPasswordMinLengthMessage"));

        assertThat(store.forSubject(SUBJECT).orElseThrow().status()).isEqualTo("SENT");
        assertThat(accounts.loggedOut)
                .as("ending sessions for a reset that was then refused would sign out somebody who asked for nothing")
                .isEmpty();

        accounts.refuse = false;
        resets.accept(token, "a-long-enough-passphrase", "corr");
        assertThat(store.forSubject(SUBJECT).orElseThrow().status()).isEqualTo("ACCEPTED");
    }

    /**
     * The failure this is about used to unwind the whole accept: the password
     * had already changed at Keycloak, the row went back to {@code SENT} with
     * its hash restored, no audit fact was written at all, and the staff member
     * was told it failed. A revocation that cannot be completed is an operator's
     * problem, recorded as one -- not a reason to tell somebody their new
     * password did not take.
     */
    @Test
    @DisplayName("a revocation that fails leaves the password changed, the link spent, and says so in the trail")
    void aFailedLogoutDoesNotUndoTheReset() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        relay.runOnce();
        String token = tokenIn(mailer.last().text());
        accounts.logoutFails = true;

        assertThatCode(() -> resets.accept(token, "a-long-enough-passphrase", "corr"))
                .as("the password did change, so the caller is not told the reset failed")
                .doesNotThrowAnyException();

        assertThat(accounts.passwords).containsEntry(SUBJECT, "a-long-enough-passphrase");
        assertThat(store.forSubject(SUBJECT).orElseThrow().status()).isEqualTo("ACCEPTED");
        assertThat(store.forSubject(SUBJECT).orElseThrow().tokenHash()).isNull();
        assertThatThrownBy(() -> resets.accept(token, "another-long-passphrase", "corr"))
                .as("and the link cannot be used again by whoever else can read that mailbox")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "INVALID"));

        assertThat(fact("iam.password_reset.accepted").changed()).containsEntry("sessionsEnded", false);
        assertThat(facts)
                .extracting(StaffSecurityFact::actionCode)
                .as("a credential changed and the sessions it was changed because of are still live: alertable")
                .contains("iam.password_reset.sessions_not_ended");
    }

    /**
     * Two accepts of one link, on two connections, in real transactions.
     *
     * <p>The link is spent by a conditional {@code UPDATE ... WHERE status =
     * 'SENT'} before either accept calls Keycloak, so the loser finds nothing to
     * spend and is told the link is invalid -- rather than both changing the
     * password and the loser being told, afterwards, that something changed
     * underneath it.
     */
    @Test
    @DisplayName("two accepts of one link cross without both setting a password")
    void onlyOneOfTwoConcurrentAcceptsSpendsTheLink() throws Exception {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        relay.runOnce();
        String token = tokenIn(mailer.last().text());

        // Both threads are held inside the fake's account read, which happens
        // after each has resolved the row and before either has spent it. That
        // is the interleaving the property is about; without it the two accepts
        // usually run one after the other on a local database, and the test
        // would pass against an implementation that serialises nothing.
        accounts.arriveBeforeAnswering = new CyclicBarrier(2);
        List<Throwable> refusals = new CopyOnWriteArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        Runnable accept = () -> {
            try {
                transactions.executeWithoutResult(status -> resets.accept(token, "a-long-enough-passphrase", "corr"));
                accepted.incrementAndGet();
            } catch (RuntimeException refused) {
                refusals.add(refused);
            }
        };
        Thread first = new Thread(accept, "accept-1");
        Thread second = new Thread(accept, "accept-2");
        first.start();
        second.start();
        first.join(30_000);
        second.join(30_000);

        assertThat(accepted.get()).as("exactly one accept succeeds").isEqualTo(1);
        assertThat(refusals)
                .singleElement()
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties())
                                .as("the loser is told the link is gone, not that it changed mid-use")
                                .containsEntry("reason", "INVALID"));
        assertThat(accounts.passwordWrites.get())
                .as("the loser must not have written a password of its own at Keycloak")
                .isEqualTo(1);
        assertThat(accounts.loggedOut).containsExactly(SUBJECT);
        assertThat(store.forSubject(SUBJECT).orElseThrow().status()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("with no mail server configured, it waits five minutes without using up its attempts")
    void anUnconfiguredMailerLeavesItWaiting() {
        assertThat(PasswordResetRelay.UNCONFIGURED_WAIT)
                .as("shorter than an invitation's wait, because a reset link only lives an hour (ADR 0098)")
                .isEqualTo(Duration.ofMinutes(5));

        mailer.next = email -> StaffEmailSender.Delivery.NOT_CONFIGURED;
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");

        relay.runOnce();
        assertThat(mailer.attempts.get()).isEqualTo(1);
        clock.advance(PasswordResetRelay.UNCONFIGURED_WAIT.minusSeconds(1));
        relay.runOnce();
        assertThat(mailer.attempts.get())
                .as("a second before the wait is up, the row is not due")
                .isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        relay.runOnce();
        assertThat(mailer.attempts.get()).isEqualTo(2);

        for (int pass = 0; pass < 10; pass++) {
            clock.advance(PasswordResetRelay.UNCONFIGURED_WAIT);
            relay.runOnce();
        }

        var row = store.forSubject(SUBJECT).orElseThrow();
        assertThat(row.status()).isEqualTo("QUEUED");
        assertThat(row.lastErrorCode()).isEqualTo("MAIL_NOT_CONFIGURED");
        assertThat(row.attempts())
                .as("nothing was attempted, so nothing was used up")
                .isZero();

        clock.advance(PasswordResetRelay.UNCONFIGURED_WAIT);
        mailer.next = email -> StaffEmailSender.Delivery.SENT;
        relay.runOnce();
        assertThat(store.forSubject(SUBJECT).orElseThrow().status())
                .as("and the mail settings arriving mid-wait are picked up on the next sweep")
                .isEqualTo("SENT");
    }

    @Test
    @DisplayName("a failing mail server waits a minute, then two, then is left for a person")
    void aFailingMailerIsRetriedThenFails() {
        mailer.next = email -> new StaffEmailSender.Delivery(StaffEmailSender.Status.FAILED, "SMTP_UNAVAILABLE");
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");

        relay.runOnce();
        assertThat(mailer.sent).hasSize(1);

        // Walked by hand rather than advanced by an hour a pass: an hour is
        // longer than every wait the schedule can produce, so a relay that
        // waited an hour after the first failure would pass that loop too.
        clock.advance(Duration.ofSeconds(59));
        relay.runOnce();
        assertThat(mailer.sent).as("the first retry waits a minute").hasSize(1);
        clock.advance(Duration.ofSeconds(1));
        relay.runOnce();
        assertThat(mailer.sent).hasSize(2);

        clock.advance(Duration.ofMinutes(2).minusSeconds(1));
        relay.runOnce();
        assertThat(mailer.sent).as("and the second waits two, not another one").hasSize(2);
        clock.advance(Duration.ofSeconds(1));
        relay.runOnce();
        assertThat(mailer.sent).hasSize(3);

        for (int pass = 0; pass < 20; pass++) {
            clock.advance(Duration.ofHours(1));
            relay.runOnce();
        }

        var row = store.forSubject(SUBJECT).orElseThrow();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isEqualTo(PasswordResetRelay.MAX_ATTEMPTS);
        assertThat(mailer.sent).hasSize(PasswordResetRelay.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("the backoff doubles from a minute and stops at an hour")
    void theBackoffScheduleIsWhatTheCommentSays() {
        assertThat(PasswordResetRelay.backoff(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(PasswordResetRelay.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(PasswordResetRelay.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(PasswordResetRelay.backoff(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(PasswordResetRelay.backoff(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(PasswordResetRelay.backoff(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(PasswordResetRelay.backoff(6)).isEqualTo(Duration.ofMinutes(32));
        assertThat(PasswordResetRelay.backoff(7))
                .as("sixty-four minutes is where the cap bites, not the shift clamp")
                .isEqualTo(Duration.ofMinutes(60));
        assertThat(PasswordResetRelay.backoff(8)).isEqualTo(Duration.ofMinutes(60));
        assertThat(PasswordResetRelay.backoff(99)).isEqualTo(Duration.ofMinutes(60));
    }

    /**
     * A relay whose claim was overtaken -- a slow mail server, a second replica
     * -- must not record a link over the newer attempt's row. The send itself
     * cannot be taken back, but the hash is never stored, so the link it
     * carried matches nothing and the newer attempt's link is the only one that
     * works.
     */
    @Test
    @DisplayName("a relay that lost its claim while sending records nothing")
    void aStaleClaimCannotRecordItsLink() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        mailer.next = email -> {
            jdbc.sql("""
                            UPDATE iam.password_resets
                               SET status = 'QUEUED', attempts = attempts + 1, next_attempt_at = :now
                             WHERE subject_id = :subject
                            """)
                    .param("now", java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .param("subject", SUBJECT)
                    .update();
            return StaffEmailSender.Delivery.SENT;
        };

        assertThat(relay.runOnce()).isZero();

        var row = store.forSubject(SUBJECT).orElseThrow();
        assertThat(row.status()).isEqualTo("QUEUED");
        assertThat(row.tokenHash())
                .as("the overtaken claim must not write its link over a newer attempt's row")
                .isNull();
        assertThat(facts).extracting(StaffSecurityFact::actionCode).doesNotContain("iam.password_reset.sent");
        assertThatThrownBy(() -> resets.inspect(tokenIn(mailer.last().text())))
                .as("so the link that did go out matches nothing")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an address the provider refuses for good is not retried at all")
    void aRejectedAddressFailsAtOnce() {
        mailer.next = email -> new StaffEmailSender.Delivery(StaffEmailSender.Status.REJECTED, "ADDRESS_REJECTED");
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");

        relay.runOnce();

        var row = store.forSubject(SUBJECT).orElseThrow();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.lastErrorCode()).isEqualTo("ADDRESS_REJECTED");
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("the masking shows enough to recognise an account and not enough to use one")
    void aLoginIsMaskedRatherThanShown() {
        assertThat(PasswordResetService.mask("dilnoza.karimova@example.uz")).isEqualTo("d***a@example.uz");
        assertThat(PasswordResetService.mask("ab@example.uz")).isEqualTo("a***@example.uz");
        assertThat(PasswordResetService.mask("cashier")).isEqualTo("c***r");
        assertThat(PasswordResetService.mask("dilnoza.karimova@example.uz"))
                .as("a mask that leaked the local part would defeat the point of masking")
                .doesNotContain("ilnoza");
    }

    // ------------------------------------------------------------- fixtures

    private StaffSecurityFact fact(String actionCode) {
        return facts.stream()
                .filter(fact -> fact.actionCode().equals(actionCode))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + actionCode + " fact was recorded"));
    }

    private static String tokenIn(String text) {
        Matcher link = LINK.matcher(text);
        assertThat(link.find()).as("the email carries the link").isTrue();
        return link.group(2);
    }

    /**
     * What Keycloak would keep, plus a record of what was asked of it.
     *
     * <p>{@code setUp} stays empty for a reset and that is asserted: an
     * account's name and verified flag are the two things {@code completeSetup}
     * writes and a reset must not. The counters are how the request path's
     * "a known login costs what an unknown one does" property is checked, and
     * the collections are concurrent because one test runs two accepts at once.
     */
    private static final class FakeAccounts implements StaffAccounts {
        private final Map<String, StaffAccount> accounts = new ConcurrentHashMap<>();
        private final Map<String, String> usernames = new ConcurrentHashMap<>();
        private final Map<String, String> passwords = new ConcurrentHashMap<>();
        private final Map<String, String> setUp = new ConcurrentHashMap<>();
        private final List<String> loggedOut = new CopyOnWriteArrayList<>();

        /** Every call a real adapter would turn into an admin round trip. */
        private final AtomicInteger identityCalls = new AtomicInteger();

        private final AtomicInteger subjectLookups = new AtomicInteger();
        private final AtomicInteger accountReads = new AtomicInteger();
        private final AtomicInteger passwordWrites = new AtomicInteger();

        private volatile boolean refuse;
        private volatile boolean unavailable;
        private volatile boolean logoutFails;

        /** When set, {@link #find} waits here for the other thread, pinning the interleaving. */
        private volatile @org.jspecify.annotations.Nullable CyclicBarrier arriveBeforeAnswering;

        void put(String subject, String email, String username) {
            accounts.put(subject, new StaffAccount(subject, email, false, true));
            usernames.put(username, subject);
            usernames.put(email, subject);
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            identityCalls.incrementAndGet();
            accountReads.incrementAndGet();
            if (unavailable) {
                throw new IllegalStateException("Keycloak is not answering");
            }
            CyclicBarrier barrier = arriveBeforeAnswering;
            if (barrier != null) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } catch (BrokenBarrierException | TimeoutException never) {
                    throw new IllegalStateException(never);
                }
            }
            return Optional.ofNullable(accounts.get(subjectId));
        }

        @Override
        public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
            identityCalls.incrementAndGet();
            if (unavailable) {
                throw new IllegalStateException("Keycloak is not answering");
            }
            return Optional.ofNullable(usernames.get(usernameOrEmail)).map(accounts::get);
        }

        @Override
        public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
            identityCalls.incrementAndGet();
            subjectLookups.incrementAndGet();
            if (unavailable) {
                throw new IllegalStateException("Keycloak is not answering");
            }
            return Optional.ofNullable(usernames.get(usernameOrEmail));
        }

        @Override
        public void setPassword(String subjectId, String password) {
            if (refuse) {
                throw new PasswordRejectedException("invalidPasswordMinLengthMessage");
            }
            passwordWrites.incrementAndGet();
            passwords.put(subjectId, password);
        }

        @Override
        public void logoutEverywhere(String subjectId) {
            if (logoutFails) {
                throw new IllegalStateException("Keycloak refused to end the account's sessions with 503");
            }
            loggedOut.add(subjectId);
        }

        @Override
        public void completeSetup(String subjectId, String firstName, String lastName, String password) {
            setUp.put(subjectId, firstName + " " + lastName);
        }
    }

    private static final class RecordingSender implements StaffEmailSender {
        private final List<StaffEmail> sent = new CopyOnWriteArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile Function<StaffEmail, Delivery> next = email -> Delivery.SENT;

        StaffEmail last() {
            return sent.getLast();
        }

        @Override
        public Delivery send(StaffEmail email) {
            attempts.incrementAndGet();
            Delivery delivery = next.apply(email);
            if (delivery.status() != Status.NOT_CONFIGURED) {
                sent.add(email);
            }
            return delivery;
        }

        @Override
        public boolean configured() {
            return true;
        }
    }

    private static final class MovableClock extends Clock {
        private volatile Instant now;

        private MovableClock(Instant now) {
            this.now = now;
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
