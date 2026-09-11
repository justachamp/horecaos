package uz.horecaos.platform.tenancy.application.invitations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0097 end to end against the migrated schema: queued by onboarding,
 * emailed by the relay with a link whose token only the email holds, opened,
 * accepted once, and resent with a link that replaces the first.
 *
 * <p>The identity provider and the mail server are the two fakes, and both are
 * narrow: the accounts fake keeps what Keycloak would, and the mailer records
 * what it was asked to send. Every duration -- the link's 72 hours, the retry
 * backoff -- is lived through by moving the clock.
 */
class OwnerInvitationFlowTests {

    private static final Pattern LINK = Pattern.compile("https://ops\\.test/invite#token=([A-Za-z0-9_-]{43})");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private FakeAccounts accounts;
    private RecordingMailer mailer;
    private List<AuditFact> facts;
    private OwnerInvitationService invitations;
    private OwnerInvitationRelay relay;
    private UUID tenantId;

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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        clock = new MovableClock(Instant.parse("2026-09-11T09:00:00Z"));
        accounts = new FakeAccounts();
        mailer = new RecordingMailer();
        facts = new ArrayList<>();
        JdbcOwnerInvitationStore store = new JdbcOwnerInvitationStore(jdbc);
        invitations = new OwnerInvitationService(store, accounts, facts::add, clock);
        relay = new OwnerInvitationRelay(store, accounts, mailer, facts::add, clock, "https://ops.test/");

        tenantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'qoida', 'Qoida MCHJ', 'Qoida & Co', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        accounts.put("owner-subject", "dilnoza.karimova@example.uz", false);
    }

    @Test
    @DisplayName("queued, emailed with a link only the email holds, opened, accepted once")
    void anOwnerIsInvitedAndSetsUpTheirAccount() {
        assertThat(invitations.inviteIfNeeded(tenantId, "owner-subject", "uz", UUID.randomUUID()))
                .isEqualTo(OwnerInvitations.QUEUED);
        assertThat(invitations.view(tenantId).orElseThrow().state()).isEqualTo("QUEUED");

        assertThat(relay.runOnce()).isEqualTo(1);
        OutgoingMail mail = mailer.last();
        assertThat(mail.to()).isEqualTo("dilnoza.karimova@example.uz");
        assertThat(mail.subject()).isEqualTo("Qoida & Co uchun HorecaOS hisobingizni sozlang");
        assertThat(mail.html()).contains("Qoida &amp; Co").doesNotContain("Qoida & Co");
        String token = tokenIn(mail.text());
        assertThat(mail.html()).contains(token);

        assertThat(jdbc.sql("SELECT row_to_json(i)::text FROM tenant.owner_invitations i")
                        .query(String.class)
                        .single())
                .as("the table holds the token's hash, never the token and never the address")
                .doesNotContain(token)
                .doesNotContain("dilnoza")
                .contains(OwnerInvitationService.hash(token));

        var inspection = invitations.inspect(token);
        assertThat(inspection.tenantName()).isEqualTo("Qoida & Co");
        assertThat(inspection.emailMasked()).isEqualTo("d***a@example.uz");
        assertThat(invitations.view(tenantId).orElseThrow().openedAt()).isNotNull();

        var accepted = invitations.accept(token, " Dilnoza ", "Karimova", "a-long-enough-passphrase", "corr");
        assertThat(accepted.signInName()).isEqualTo("dilnoza.karimova@example.uz");
        assertThat(accounts.setUp).containsEntry("owner-subject", "Dilnoza Karimova");
        assertThat(invitations.view(tenantId).orElseThrow().state()).isEqualTo("ACCEPTED");

        assertThatThrownBy(() -> invitations.accept(token, "Someone", "Else", "another-long-passphrase", "corr"))
                .as("a spent link is spent")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "INVALID"));
        assertThat(facts)
                .extracting(AuditFact::actionCode)
                .containsExactly(
                        "tenant.owner_invitation.queued",
                        "tenant.owner_invitation.sent",
                        "tenant.owner_invitation.accepted");
    }

    @Test
    @DisplayName("a retried onboarding step does not send a second email")
    void queuingTwiceQueuesOnce() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());

        assertThat(relay.runOnce()).isZero();
        assertThat(mailer.sent).hasSize(1);
        assertThat(invitations.view(tenantId).orElseThrow().state()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a resend replaces the link: the first stops working at once")
    void aResendReplacesTheLink() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        String first = tokenIn(mailer.last().text());

        invitations.resend(tenantId, "en", ActorRef.user("operator", null), "the owner lost the email", "corr");
        assertThatThrownBy(() -> invitations.inspect(first))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "INVALID"));

        relay.runOnce();
        String second = tokenIn(mailer.last().text());
        assertThat(second).isNotEqualTo(first);
        assertThat(mailer.last().subject()).isEqualTo("Set up your HorecaOS account for Qoida & Co");
        assertThat(invitations.inspect(second).locale()).isEqualTo("en");
    }

    @Test
    @DisplayName("a link works for 72 hours and not a minute more")
    void aLinkExpires() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        String token = tokenIn(mailer.last().text());

        clock.advance(Duration.ofHours(72).minusMinutes(1));
        assertThat(invitations.inspect(token).tenantName()).isEqualTo("Qoida & Co");

        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> invitations.inspect(token))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "EXPIRED"));
        assertThat(invitations.view(tenantId).orElseThrow().state()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("with no mail server configured, it waits without using up its attempts")
    void anUnconfiguredMailerLeavesItWaiting() {
        mailer.next = mail -> new MailOutcome.NotConfigured();
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());

        for (int pass = 0; pass < 12; pass++) {
            relay.runOnce();
            clock.advance(OwnerInvitationRelay.UNCONFIGURED_WAIT);
        }

        var view = invitations.view(tenantId).orElseThrow();
        assertThat(view.state()).isEqualTo("QUEUED");
        assertThat(view.lastErrorCode()).isEqualTo("MAIL_NOT_CONFIGURED");
        assertThat(view.attempts())
                .as("nothing was attempted, so nothing was used up")
                .isZero();

        mailer.next = mail -> new MailOutcome.Sent();
        relay.runOnce();
        assertThat(invitations.view(tenantId).orElseThrow().state()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a failing mail server is retried with backoff, then left for a person")
    void aFailingMailerIsRetriedThenFails() {
        mailer.next = mail -> new MailOutcome.Failed("SMTP_UNAVAILABLE");
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());

        relay.runOnce();
        assertThat(relay.runOnce()).as("the first retry waits a minute").isZero();
        assertThat(mailer.sent).hasSize(1);

        for (int pass = 0; pass < 20; pass++) {
            clock.advance(Duration.ofHours(1));
            relay.runOnce();
        }
        var view = invitations.view(tenantId).orElseThrow();
        assertThat(view.state()).isEqualTo("FAILED");
        assertThat(view.attempts()).isEqualTo(OwnerInvitationRelay.MAX_ATTEMPTS);
        assertThat(mailer.sent).hasSize(OwnerInvitationRelay.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("an account that already has a password is not invited, and a refused password spends nothing")
    void nothingIsSentThatIsNotNeeded() {
        accounts.put("set-up-owner", "owner@example.uz", true);
        assertThat(invitations.inviteIfNeeded(tenantId, "set-up-owner", "ru", UUID.randomUUID()))
                .isEqualTo(OwnerInvitations.NOT_NEEDED);

        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        String token = tokenIn(mailer.last().text());
        accounts.refuse = true;
        assertThatThrownBy(() -> invitations.accept(token, "Dilnoza", "Karimova", "password123456", "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties())
                                .containsEntry("policy", "invalidPasswordNotUsernameMessage"));
        assertThat(invitations.view(tenantId).orElseThrow().state())
                .as("the link still works after a refused password")
                .isEqualTo("SENT");
    }

    /**
     * A tenant onboarded before invitations existed: the owner step completed,
     * linked an owner with no password, and told nobody. Sending from the
     * control plane invites the owner that step linked.
     */
    @Test
    @DisplayName("a tenant onboarded before invitations existed can have its first one sent")
    void aTenantOnboardedEarlierGetsItsFirstInvitation() {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.onboarding_runs (id, tenant_id, template_id, template_version, status,
                    current_phase, started_by)
                VALUES (:id, :tenantId, '94cc9f54-7451-4db1-ac13-4073f6833b15', 1, 'FAILED', 'VALIDATING', 'operator')
                """).param("id", runId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.onboarding_steps (id, tenant_id, run_id, step_key, phase, sequence_number,
                    status, required, external_reference)
                VALUES (:id, :tenantId, :runId, 'TENANT_OWNER_LINK_OR_INVITE', 'PROVISIONING', 2,
                    'COMPLETED', true, 'owner-subject')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .update();
        assertThat(invitations.view(tenantId)).isEmpty();

        invitations.resend(tenantId, "uz", ActorRef.user("operator", null), "onboarded before invitations", "corr");

        assertThat(invitations.view(tenantId).orElseThrow().state()).isEqualTo("QUEUED");
        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(mailer.last().to()).isEqualTo("dilnoza.karimova@example.uz");
        assertThat(facts).extracting(AuditFact::actionCode).first().isEqualTo("tenant.owner_invitation.queued");
    }

    @Test
    @DisplayName("nothing is sent for a tenant with no linked owner, or one whose owner already has a password")
    void aFirstInvitationNeedsAnOwnerWithoutAPassword() {
        assertThatThrownBy(() -> invitations.resend(tenantId, null, ActorRef.user("operator", null), "why", "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "NO_OWNER"));

        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.onboarding_runs (id, tenant_id, template_id, template_version, status,
                    current_phase, started_by)
                VALUES (:id, :tenantId, '94cc9f54-7451-4db1-ac13-4073f6833b15', 1, 'ACTIVE', 'ACTIVATING', 'operator')
                """).param("id", runId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.onboarding_steps (id, tenant_id, run_id, step_key, phase, sequence_number,
                    status, required, external_reference)
                VALUES (:id, :tenantId, :runId, 'TENANT_OWNER_LINK_OR_INVITE', 'PROVISIONING', 2,
                    'COMPLETED', true, 'set-up-owner')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .update();
        accounts.put("set-up-owner", "owner@example.uz", true);

        assertThatThrownBy(() -> invitations.resend(tenantId, null, ActorRef.user("operator", null), "why", "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "ALREADY_SET_UP"));
        assertThat(invitations.view(tenantId)).isEmpty();
    }

    // ------------------------------------------------------------- fixtures

    private static String tokenIn(String text) {
        Matcher link = LINK.matcher(text);
        assertThat(link.find()).as("the email carries the link").isTrue();
        return link.group(1);
    }

    private static final class FakeAccounts implements StaffAccounts {
        private final Map<String, StaffAccount> accounts = new HashMap<>();
        private final Map<String, String> setUp = new HashMap<>();
        private boolean refuse;

        void put(String subject, String email, boolean hasPassword) {
            accounts.put(subject, new StaffAccount(subject, email, false, hasPassword));
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            return Optional.ofNullable(accounts.get(subjectId));
        }

        @Override
        public void completeSetup(String subjectId, String firstName, String lastName, String password) {
            if (refuse) {
                throw new PasswordRejectedException("invalidPasswordNotUsernameMessage");
            }
            StaffAccount account = java.util.Objects.requireNonNull(accounts.get(subjectId));
            accounts.put(subjectId, new StaffAccount(subjectId, account.email(), true, true));
            setUp.put(subjectId, firstName + " " + lastName);
        }
    }

    private static final class RecordingMailer implements PlatformMailer {
        private final List<OutgoingMail> sent = new ArrayList<>();
        private Function<OutgoingMail, MailOutcome> next = mail -> new MailOutcome.Sent();

        OutgoingMail last() {
            return sent.getLast();
        }

        @Override
        public MailOutcome send(OutgoingMail mail) {
            MailOutcome outcome = next.apply(mail);
            if (!(outcome instanceof MailOutcome.NotConfigured)) {
                sent.add(mail);
            }
            return outcome;
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
