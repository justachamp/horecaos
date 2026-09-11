package uz.horecaos.platform.iam.application.passwordresets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Every duration -- the link's sixty minutes, the retry backoff -- is lived
 * through by moving the clock.
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
        facts = new ArrayList<>();
        store = new JdbcPasswordResetStore(jdbc);
        resets = new PasswordResetService(store, accounts, facts::add, clock);
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

    @Test
    @DisplayName("an identity provider that is down is swallowed rather than raised")
    void anIdentityOutageIsNotAnError() {
        accounts.unavailable = true;

        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");

        assertThat(store.forSubject(SUBJECT)).isEmpty();
        assertThat(mailer.sent).isEmpty();
    }

    @Test
    @DisplayName("asking again replaces the link: the first stops working at once, and only one row exists")
    void asecondRequestReplacesTheFirst() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        relay.runOnce();
        String first = tokenIn(mailer.last().text());

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

    @Test
    @DisplayName("a link works for sixty minutes and not a minute more")
    void aLinkExpires() {
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");
        relay.runOnce();
        String token = tokenIn(mailer.last().text());

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

    @Test
    @DisplayName("with no mail server configured, it waits without using up its attempts")
    void anUnconfiguredMailerLeavesItWaiting() {
        mailer.next = email -> StaffEmailSender.Delivery.NOT_CONFIGURED;
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");

        for (int pass = 0; pass < 12; pass++) {
            relay.runOnce();
            clock.advance(PasswordResetRelay.UNCONFIGURED_WAIT);
        }

        var row = store.forSubject(SUBJECT).orElseThrow();
        assertThat(row.status()).isEqualTo("QUEUED");
        assertThat(row.lastErrorCode()).isEqualTo("MAIL_NOT_CONFIGURED");
        assertThat(row.attempts())
                .as("nothing was attempted, so nothing was used up")
                .isZero();

        mailer.next = email -> StaffEmailSender.Delivery.SENT;
        relay.runOnce();
        assertThat(store.forSubject(SUBJECT).orElseThrow().status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a failing mail server is retried with backoff, then left for a person")
    void aFailingMailerIsRetriedThenFails() {
        mailer.next = email -> new StaffEmailSender.Delivery(StaffEmailSender.Status.FAILED, "SMTP_UNAVAILABLE");
        resets.request("dilnoza", StaffConsole.OPERATIONS, "ru", "corr");

        relay.runOnce();
        assertThat(relay.runOnce()).as("the first retry waits a minute").isZero();
        assertThat(mailer.sent).hasSize(1);

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
     * writes and a reset must not.
     */
    private static final class FakeAccounts implements StaffAccounts {
        private final Map<String, StaffAccount> accounts = new HashMap<>();
        private final Map<String, String> usernames = new HashMap<>();
        private final Map<String, String> passwords = new LinkedHashMap<>();
        private final Map<String, String> setUp = new LinkedHashMap<>();
        private final List<String> loggedOut = new ArrayList<>();
        private boolean refuse;
        private boolean unavailable;

        void put(String subject, String email, String username) {
            accounts.put(subject, new StaffAccount(subject, email, false, true));
            usernames.put(username, subject);
            usernames.put(email, subject);
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            if (unavailable) {
                throw new IllegalStateException("Keycloak is not answering");
            }
            return Optional.ofNullable(accounts.get(subjectId));
        }

        @Override
        public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
            if (unavailable) {
                throw new IllegalStateException("Keycloak is not answering");
            }
            return Optional.ofNullable(usernames.get(usernameOrEmail)).map(accounts::get);
        }

        @Override
        public void setPassword(String subjectId, String password) {
            if (refuse) {
                throw new PasswordRejectedException("invalidPasswordMinLengthMessage");
            }
            passwords.put(subjectId, password);
        }

        @Override
        public void logoutEverywhere(String subjectId) {
            loggedOut.add(subjectId);
        }

        @Override
        public void completeSetup(String subjectId, String firstName, String lastName, String password) {
            setUp.put(subjectId, firstName + " " + lastName);
        }
    }

    private static final class RecordingSender implements StaffEmailSender {
        private final List<StaffEmail> sent = new ArrayList<>();
        private Function<StaffEmail, Delivery> next = email -> Delivery.SENT;

        StaffEmail last() {
            return sent.getLast();
        }

        @Override
        public Delivery send(StaffEmail email) {
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
        private Instant now;

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
