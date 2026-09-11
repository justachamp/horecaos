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
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationEventStore;
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

    /** An operator holding tenant.onboarding.manage: they may read the address back. */
    private static final ActorRef ONBOARDER = ActorRef.user("operator", null);

    /** A platform operator holding only TENANT_READ: they get the mask, as ADR 0097 shipped. */
    private static final ActorRef READER = ActorRef.user("reader", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private FakeAccounts accounts;
    private RecordingMailer mailer;
    private List<AuditFact> facts;
    private JdbcOwnerInvitationStore store;
    private JdbcOwnerInvitationEventStore events;
    private TransactionTemplate transactions;
    private FakeAuthorization authorization;
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
        store = new JdbcOwnerInvitationStore(jdbc);
        events = new JdbcOwnerInvitationEventStore(jdbc);
        // A real transaction manager over the same DataSource the JdbcClient
        // uses, so the relay's settle is a transaction here exactly as it is in
        // the application: the rollback in theHistoryAndTheRowStandOrFallTogether
        // is a genuine one and not a fixture's promise.
        transactions = new TransactionTemplate(new JdbcTransactionManager(db.dataSource()));
        authorization = new FakeAuthorization();
        invitations = new OwnerInvitationService(store, events, accounts, authorization, facts::add, clock);
        relay = relayWith(events);

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
        assertThat(view().state()).isEqualTo("QUEUED");

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
        assertThat(view().openedAt()).isNotNull();

        var accepted = invitations.accept(token, " Dilnoza ", "Karimova", "a-long-enough-passphrase", "corr");
        assertThat(accepted.signInName()).isEqualTo("dilnoza.karimova@example.uz");
        assertThat(accounts.setUp).containsEntry("owner-subject", "Dilnoza Karimova");
        assertThat(view().state()).isEqualTo("ACCEPTED");

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
        assertThat(view().state()).isEqualTo("SENT");
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
        assertThat(view().state()).isEqualTo("EXPIRED");
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

        var view = view();
        assertThat(view.state()).isEqualTo("QUEUED");
        assertThat(view.lastErrorCode()).isEqualTo("MAIL_NOT_CONFIGURED");
        assertThat(view.attempts())
                .as("nothing was attempted, so nothing was used up")
                .isZero();
        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .as("twelve identical waits are one line, or an unconfigured deployment writes 96 rows a day forever")
                .containsExactly("QUEUED", "SEND_DEFERRED");
        var deferred = timeline().getLast();
        assertThat(deferred.outcomeCode()).isEqualTo("MAIL_NOT_CONFIGURED");
        assertThat(deferred.attempt())
                .as("nothing was attempted, so it belongs to no attempt -- and agrees with the 0 on the panel")
                .isZero();

        mailer.next = mail -> new MailOutcome.Sent();
        relay.runOnce();
        assertThat(view().state()).isEqualTo("SENT");
    }

    /**
     * The reason is what the line says, so a new reason is a new line: a resend
     * clears {@code last_error_code}, and the wait that follows it is news
     * again even though the wording has not changed.
     */
    @Test
    @DisplayName("a deferral for the same reason is recorded once, and again after a resend clears it")
    void aRepeatedDeferralIsOneLineUntilItsReasonChanges() {
        mailer.next = mail -> new MailOutcome.NotConfigured();
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        clock.advance(OwnerInvitationRelay.UNCONFIGURED_WAIT);
        relay.runOnce();

        invitations.resend(tenantId, null, ONBOARDER, "trying again once mail is on", "corr");
        relay.runOnce();

        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .containsExactly("QUEUED", "SEND_DEFERRED", "RESENT", "SEND_DEFERRED");
    }

    /**
     * ADR 0100 decision 1: the history is written wherever the state changes,
     * which only means anything if the two cannot come apart. The application
     * role holds {@code SELECT, INSERT} on the events table, so a state write
     * that committed without its line could never be corrected.
     */
    @Test
    @DisplayName("a history write that fails takes the state write down with it, and the row comes due again")
    void theHistoryAndTheRowStandOrFallTogether() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        OwnerInvitationRelay breaking = relayWith(new JdbcOwnerInvitationEventStore(jdbc) {
            @Override
            public void append(Entry entry) {
                throw new IllegalStateException("the history write did not land");
            }
        });

        assertThat(breaking.runOnce()).isZero();

        assertThat(view().state())
                .as("no SENT row without a SENT line: the send is rolled back and tried again")
                .isEqualTo("QUEUED");
        assertThat(view().sentAt()).isNull();
        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .containsExactly("QUEUED");

        clock.advance(OwnerInvitationRelay.LEASE.plusMinutes(1));
        assertThat(relay.runOnce())
                .as("the lease brings it back, and a working relay sends it")
                .isEqualTo(1);
        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .containsExactly("QUEUED", "SENT");
    }

    /**
     * The guards on the state writes already refuse an outcome from a
     * superseded attempt; the history has to refuse it too, or it keeps a claim
     * about the invitation that was never true and cannot be taken back.
     */
    @Test
    @DisplayName("an outcome a resend overtook changes nothing, and is not written into the history")
    void anOvertakenOutcomeIsNotRecorded() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        mailer.next = mail -> {
            // The operator resends while this send is in flight: the row is back
            // in the queue at attempt zero before this attempt's outcome lands.
            invitations.resend(tenantId, null, ONBOARDER, "the owner asked again", "corr");
            return new MailOutcome.Rejected("ADDRESS_REJECTED");
        };

        assertThat(relay.runOnce()).isZero();

        var view = view();
        assertThat(view.state())
                .as("the resend's queue stands; the overtaken failure did not land on it")
                .isEqualTo("QUEUED");
        assertThat(view.lastErrorCode()).isNull();
        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .as("no SEND_FAILED for an attempt a resend had already replaced")
                .containsExactly("QUEUED", "RESENT");
    }

    /**
     * The one branch of the relay's switch that does not retry. A typed-wrong
     * address is exactly what ADR 0100 was written to make visible, and
     * retrying it for two hours would hide it behind QUEUED.
     */
    @Test
    @DisplayName("a refused address fails at the first attempt and is never retried")
    void aRejectedAddressFailsAtOnce() {
        mailer.next = mail -> new MailOutcome.Rejected("ADDRESS_REJECTED");
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());

        assertThat(relay.runOnce()).isZero();

        var view = view();
        assertThat(view.state()).isEqualTo("FAILED");
        assertThat(view.attempts())
                .as("a bounce is spent at once, not after eight tries")
                .isEqualTo(1);
        assertThat(view.lastErrorCode()).isEqualTo("ADDRESS_REJECTED");
        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .containsExactly("QUEUED", "SEND_FAILED");
        var failure = timeline().getLast();
        assertThat(failure.outcomeCode()).isEqualTo("ADDRESS_REJECTED");
        assertThat(failure.attempt()).isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        assertThat(relay.runOnce()).as("nothing is claimed again").isZero();
        assertThat(mailer.sent).hasSize(1);
        assertThat(timeline()).hasSize(2);
    }

    /**
     * The owner set a password some other way between the queue and the send.
     * This is the only writer of a NOT_NEEDED event, so it is also the only
     * thing that proves the value passes V0215's own CHECK constraint.
     */
    @Test
    @DisplayName("an owner who gained a password before the send is not emailed, and the history says why")
    void aQueuedInvitationIsDroppedOnceTheOwnerHasAPassword() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        accounts.put("owner-subject", "dilnoza.karimova@example.uz", true);

        assertThat(relay.runOnce()).isZero();

        assertThat(mailer.sent)
                .as("nothing is emailed to somebody who can already sign in")
                .isEmpty();
        assertThat(view().state()).isEqualTo("NOT_NEEDED");
        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .containsExactly("QUEUED", "NOT_NEEDED");
        assertThat(timeline().getLast().attempt()).isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        assertThat(relay.runOnce())
                .as("a settled invitation is never claimed again")
                .isZero();
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
        var view = view();
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
        assertThat(view().state())
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
        assertThat(invitations.view(tenantId, READER, "corr")).isEmpty();

        invitations.resend(tenantId, "uz", ActorRef.user("operator", null), "onboarded before invitations", "corr");

        assertThat(view().state()).isEqualTo("QUEUED");
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
        assertThat(invitations.view(tenantId, READER, "corr")).isEmpty();
    }

    /**
     * The history is the point of ADR 0100: the invitation row is a position and
     * a resend rewrites it, so the events have to survive that rewrite.
     */
    @Test
    @DisplayName("a resend is on the timeline with its actor and reason, and does not erase what came before")
    void aResendIsRecordedAndErasesNothing() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        invitations.inspect(tokenIn(mailer.last().text()));

        invitations.resend(tenantId, "en", ONBOARDER, "the owner lost the email", "corr");

        assertThat(view().attempts())
                .as("the row itself is back to nothing attempted, which is why the history has to exist")
                .isZero();
        assertThat(view().sentAt()).isNull();

        var resent = timeline().stream()
                .filter(event -> event.type().equals("RESENT"))
                .findFirst()
                .orElseThrow();
        assertThat(resent.actorType()).isEqualTo("USER");
        assertThat(resent.actor()).isEqualTo("operator");
        assertThat(resent.reason()).isEqualTo("the owner lost the email");
        assertThat(resent.locale()).isEqualTo("en");
        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .as("the send and the open the resend wiped off the row are still in the history")
                .containsExactly("QUEUED", "SENT", "OPENED", "RESENT");

        relay.runOnce();
        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .containsExactly("QUEUED", "SENT", "OPENED", "RESENT", "SENT");
    }

    @Test
    @DisplayName("every send attempt leaves its outcome behind: deferred with a code, then failed")
    void everyAttemptOutcomeIsRecorded() {
        mailer.next = mail -> new MailOutcome.Failed("SMTP_UNAVAILABLE");
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        for (int pass = 0; pass < 20; pass++) {
            relay.runOnce();
            clock.advance(Duration.ofHours(1));
        }

        var attempts = timeline().stream()
                .filter(event -> !event.type().equals("QUEUED"))
                .toList();
        assertThat(attempts).hasSize(OwnerInvitationRelay.MAX_ATTEMPTS);
        assertThat(attempts)
                .extracting(OwnerInvitationService.OwnerInvitationEventView::outcomeCode)
                .as("the failure code is on every one of them, and no message ever is")
                .containsOnly("SMTP_UNAVAILABLE");
        assertThat(attempts.getLast().type()).isEqualTo("SEND_FAILED");
        assertThat(attempts.subList(0, attempts.size() - 1))
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .containsOnly("SEND_DEFERRED");
        assertThat(attempts)
                .extracting(OwnerInvitationService.OwnerInvitationEventView::attempt)
                .as("each one says which attempt it was")
                .startsWith(1, 2, 3);
    }

    @Test
    @DisplayName("a mail scanner following the link does not put a second open on the timeline")
    void onlyTheFirstOpenIsRecorded() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        String token = tokenIn(mailer.last().text());

        invitations.inspect(token);
        invitations.inspect(token);
        invitations.inspect(token);

        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .containsExactly("QUEUED", "SENT", "OPENED");
    }

    @Test
    @DisplayName("the history holds no address, and neither does the invitation row")
    void theHistoryHoldsNoAddress() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        invitations.resend(tenantId, null, ONBOARDER, "dilnoza asked us to try again", "corr");

        // The two rows whose actor_reference comes from a person -- OPENED and
        // ACCEPTED -- have to be in the table for this assertion to bind, and a
        // resend has just killed the first link, so the relay has to run again
        // before there is a live one to open.
        relay.runOnce();
        String token = tokenIn(mailer.last().text());
        invitations.inspect(token);
        invitations.accept(token, "Dilnoza", "Karimova", "a-long-enough-passphrase", "corr");

        assertThat(timeline())
                .extracting(OwnerInvitationService.OwnerInvitationEventView::type)
                .as("the fixture cannot quietly stop producing the rows this test exists to inspect")
                .containsExactly("QUEUED", "SENT", "RESENT", "SENT", "OPENED", "ACCEPTED");

        assertThat(jdbc.sql("SELECT string_agg(row_to_json(e)::text, ' ') FROM tenant.owner_invitation_events e")
                        .query(String.class)
                        .single())
                .as("not the local part, not the domain, not anywhere")
                .doesNotContain("dilnoza.karimova")
                .doesNotContain("example.uz")
                .contains("RESENT");
        assertThat(jdbc.sql("SELECT row_to_json(i)::text FROM tenant.owner_invitations i")
                        .query(String.class)
                        .single())
                .as("nor does the row, after a resend and an accept have rewritten it")
                .doesNotContain("dilnoza")
                .doesNotContain("example.uz");
    }

    /**
     * ADR 0100's reveal: the capability that typed the address at onboarding
     * reads it back, and leaves a fact saying so; everybody else keeps ADR
     * 0097's mask.
     */
    @Test
    @DisplayName(
            "the recipient is whole for the onboarding capability, masked for everyone else, and revealing it is a fact")
    void theRecipientIsRevealedOnlyToTheCapabilityThatChoseIt() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        facts.clear();

        var masked = invitations.view(tenantId, READER, "corr").orElseThrow();
        assertThat(masked.recipient()).isNull();
        assertThat(masked.emailMasked()).isEqualTo("d***a@example.uz");
        assertThat(facts).as("nothing was revealed, so nothing is recorded").isEmpty();

        authorization.grant("operator", Capability.TENANT_ONBOARDING_MANAGE, ResourceScope.tenant(tenantId));
        var whole = invitations.view(tenantId, ONBOARDER, "corr").orElseThrow();
        assertThat(whole.recipient()).isEqualTo("dilnoza.karimova@example.uz");
        assertThat(whole.emailMasked()).as("the mask stays alongside it").isEqualTo("d***a@example.uz");
        assertThat(whole.toString())
                .as("a stray log line must not print it")
                .doesNotContain("dilnoza")
                .contains("<redacted>");

        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.actionCode()).isEqualTo("tenant.owner_invitation.recipient_revealed");
            assertThat(fact.reason()).isEqualTo("tenancy.onboarding.invitation.recipient");
            assertThat(fact.changeDocument()).containsEntry("revealedCount", 1);
            assertThat(fact.changeDocument().toString()).doesNotContain("dilnoza");
            assertThat(fact.capabilityUsed())
                    .as("a reveal that does not name the capability it was made under attributes nothing")
                    .isEqualTo(Capability.TENANT_ONBOARDING_MANAGE.code());
            assertThat(fact.scope())
                    .as("recorded where it was authorised: an auditor asking about this tenant must find it")
                    .isEqualTo(ResourceScope.tenant(tenantId));
        });
    }

    @Test
    @DisplayName("an unreachable identity provider costs the address and not the state")
    void theStateSurvivesAnUnreachableIdentityProvider() {
        invitations.inviteIfNeeded(tenantId, "owner-subject", "ru", UUID.randomUUID());
        relay.runOnce();
        authorization.grant("operator", Capability.TENANT_ONBOARDING_MANAGE, ResourceScope.tenant(tenantId));
        accounts.unreachable = true;

        var view = invitations.view(tenantId, ONBOARDER, "corr").orElseThrow();
        assertThat(view.state()).isEqualTo("SENT");
        assertThat(view.recipient()).isNull();
        assertThat(view.emailMasked()).isNull();
        assertThat(view.timeline()).isNotEmpty();
    }

    // ------------------------------------------------------------- fixtures

    private OwnerInvitationRelay relayWith(JdbcOwnerInvitationEventStore eventStore) {
        return new OwnerInvitationRelay(
                store, eventStore, accounts, mailer, facts::add, transactions, clock, "https://ops.test/");
    }

    /** The view an operator without the onboarding capability gets: masked, and no reveal fact. */
    private OwnerInvitationService.OwnerInvitationView view() {
        return invitations.view(tenantId, READER, "corr").orElseThrow();
    }

    private List<OwnerInvitationService.OwnerInvitationEventView> timeline() {
        return view().timeline();
    }

    private static String tokenIn(String text) {
        Matcher link = LINK.matcher(text);
        assertThat(link.find()).as("the email carries the link").isTrue();
        return link.group(1);
    }

    /** Nobody holds anything until a test says so. */
    private static final class FakeAuthorization implements AuthorizationService {
        private final List<String> granted = new ArrayList<>();

        void grant(String subject, Capability capability, ResourceScope scope) {
            granted.add(subject + "|" + capability.code() + "|" + scope);
        }

        @Override
        public boolean has(String subject, Capability capability, ResourceScope scope) {
            return granted.contains(subject + "|" + capability.code() + "|" + scope);
        }

        @Override
        public void require(String subject, Capability capability, ResourceScope scope) {
            if (!has(subject, capability, scope)) {
                throw new AccessDeniedException(capability, scope);
            }
        }

        @Override
        public CapabilityView viewFor(String subject, UUID tenantId) {
            throw new UnsupportedOperationException("nothing under test reads a capability view");
        }
    }

    private static final class FakeAccounts implements StaffAccounts {
        private final Map<String, StaffAccount> accounts = new HashMap<>();
        private final Map<String, String> setUp = new HashMap<>();
        private boolean refuse;
        private boolean unreachable;

        void put(String subject, String email, boolean hasPassword) {
            accounts.put(subject, new StaffAccount(subject, email, false, hasPassword));
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            if (unreachable) {
                throw new IllegalStateException("the identity provider is not answering");
            }
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
