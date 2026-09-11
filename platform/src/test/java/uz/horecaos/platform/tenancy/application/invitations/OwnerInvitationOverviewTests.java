package uz.horecaos.platform.tenancy.application.invitations;

import static org.assertj.core.api.Assertions.assertThat;

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
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.OwnerInvitationOverviewRow;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationEventStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore;

/**
 * ADR 0100's cross-tenant overview against the migrated schema: which tenants
 * have an owner who has not set up an account.
 *
 * <p>Four tenants stand for the four cases the screen exists to tell apart —
 * one whose owner accepted, one still waiting on an email, one whose owner was
 * linked by an onboarding run that predates invitations and was therefore never
 * told, and one archived. The third is the one a list built from
 * {@code owner_invitations} alone would silently leave out, so it is asserted
 * for by name rather than by count.
 */
class OwnerInvitationOverviewTests {

    private static final Pattern LINK = Pattern.compile("https://ops\\.test/invite#token=([A-Za-z0-9_-]{43})");

    private static final ActorRef ONBOARDER = ActorRef.user("operator", null);
    private static final ActorRef READER = ActorRef.user("reader", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private FakeAccounts accounts;
    private RecordingMailer mailer;
    private FakeAuthorization authorization;
    private List<AuditFact> facts;
    private OwnerInvitationService invitations;
    private OwnerInvitationRelay relay;

    private UUID accepted;
    private UUID waiting;
    private UUID neverTold;
    private UUID archived;

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
        authorization = new FakeAuthorization();
        facts = new ArrayList<>();
        JdbcOwnerInvitationStore store = new JdbcOwnerInvitationStore(jdbc);
        JdbcOwnerInvitationEventStore events = new JdbcOwnerInvitationEventStore(jdbc);
        invitations = new OwnerInvitationService(store, events, accounts, authorization, facts::add, clock);
        mailer = new RecordingMailer();
        relay = new OwnerInvitationRelay(store, events, accounts, mailer, facts::add, clock, "https://ops.test/");

        accepted = tenant("accepted-co", "Accepted & Co", "ACTIVE");
        waiting = tenant("waiting-co", "Waiting Kafe", "PROVISIONING");
        neverTold = tenant("never-told-co", "Never Told Osh", "PROVISIONING");
        archived = tenant("archived-co", "Archived Choyxona", "ARCHIVED");

        accounts.put("accepted-owner", "anvar@example.uz", false);
        accounts.put("waiting-owner", "dilnoza.karimova@example.uz", false);
        accounts.put("never-told-owner", "sardor@example.uz", false);
        accounts.put("archived-owner", "gone@example.uz", false);

        invitations.inviteIfNeeded(accepted, "accepted-owner", "ru", UUID.randomUUID());
        invitations.inviteIfNeeded(waiting, "waiting-owner", "uz", UUID.randomUUID());
        invitations.inviteIfNeeded(archived, "archived-owner", "ru", UUID.randomUUID());
        linkedOwnerWithNoInvitation(neverTold, "never-told-owner");
    }

    @Test
    @DisplayName("every tenant whose owner matters is listed, including the one nobody ever invited")
    void theOverviewListsEveryTenantWithAnOwnerToChase() {
        var rows = invitations.overview(null, ONBOARDER, "corr");

        assertThat(rows)
                .extracting(OwnerInvitationOverviewRow::tenantSlug)
                .as("the archived tenant is nobody's work; the never-invited one is everybody's")
                .containsExactlyInAnyOrder("accepted-co", "waiting-co", "never-told-co");

        var untold = row(rows, "never-told-co");
        assertThat(untold.state())
                .as("no invitation row exists for it at all, which is the case the list exists for")
                .isEqualTo(OwnerInvitationService.NONE);
        assertThat(untold.queuedAt()).isNull();
        assertThat(untold.attempts()).isZero();
        assertThat(untold.emailMasked()).isEqualTo("s***r@example.uz");
    }

    @Test
    @DisplayName("a tenant with no linked owner and no invitation is not listed at all")
    void aTenantWithNoOwnerIsNotListed() {
        tenant("nothing-yet", "Nothing Yet", "PROVISIONING");

        assertThat(invitations.overview(null, ONBOARDER, "corr"))
                .extracting(OwnerInvitationOverviewRow::tenantSlug)
                .doesNotContain("nothing-yet");
    }

    @Test
    @DisplayName("the most urgent tenants come first: failed and expired before queued and accepted")
    void theWorstNewsIsAtTheTop() {
        relay.runOnce();
        invitations.accept(tokenFor("anvar@example.uz"), "Anvar", "Anvarov", "a-long-enough-passphrase", "corr");
        clock.advance(OwnerInvitationService.LINK_LIFETIME.plus(Duration.ofMinutes(1)));

        var rows = invitations.overview(null, ONBOARDER, "corr");
        assertThat(rows)
                .extracting(OwnerInvitationOverviewRow::state)
                .as("expired first, then the tenant nobody told, then the one that is done")
                .containsExactly("EXPIRED", OwnerInvitationService.NONE, "ACCEPTED");
    }

    @Test
    @DisplayName("OUTSTANDING is everything an operator still has to chase")
    void theOutstandingFilterLeavesOutTheFinishedOnes() {
        relay.runOnce();
        invitations.accept(tokenFor("anvar@example.uz"), "Anvar", "Anvarov", "a-long-enough-passphrase", "corr");

        assertThat(invitations.overview(OwnerInvitationService.OUTSTANDING, ONBOARDER, "corr"))
                .extracting(OwnerInvitationOverviewRow::tenantSlug)
                .containsExactlyInAnyOrder("waiting-co", "never-told-co");
        assertThat(invitations.overview("ACCEPTED", ONBOARDER, "corr"))
                .extracting(OwnerInvitationOverviewRow::tenantSlug)
                .containsExactly("accepted-co");
        assertThat(invitations.overview(OwnerInvitationService.NONE, ONBOARDER, "corr"))
                .extracting(OwnerInvitationOverviewRow::tenantSlug)
                .containsExactly("never-told-co");
        assertThat(invitations.overview("sent", ONBOARDER, "corr"))
                .as("a lower-case state from a query string is the same state")
                .extracting(OwnerInvitationOverviewRow::tenantSlug)
                .containsExactly("waiting-co");
    }

    @Test
    @DisplayName("the recipient is whole for the onboarding capability and masked for everyone else")
    void addressesAreRevealedOnlyToTheCapabilityThatChoseThem() {
        facts.clear();
        var masked = invitations.overview(null, READER, "corr");
        assertThat(masked).allSatisfy(row -> assertThat(row.recipient()).isNull());
        assertThat(masked).extracting(OwnerInvitationOverviewRow::emailMasked).contains("d***a@example.uz");
        assertThat(facts).as("nothing was revealed, so nothing is recorded").isEmpty();

        authorization.grant("operator", Capability.TENANT_ONBOARDING_MANAGE, ResourceScope.platform());
        var whole = invitations.overview(null, ONBOARDER, "corr");
        assertThat(whole)
                .extracting(OwnerInvitationOverviewRow::recipient)
                .containsExactlyInAnyOrder("anvar@example.uz", "dilnoza.karimova@example.uz", "sardor@example.uz");

        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.actionCode()).isEqualTo("tenant.owner_invitation.recipient_revealed");
            assertThat(fact.reason()).isEqualTo("tenancy.onboarding.invitation.recipient");
            assertThat(fact.changeDocument())
                    .as("one fact per screen load, saying how many and never which")
                    .containsExactly(Map.entry("revealedCount", 3));
            assertThat(fact.changeDocument().toString()).doesNotContain("example.uz");
        });
    }

    @Test
    @DisplayName("the reveal fact counts only what was filtered in")
    void theRevealCountIsTheCountThatWasShown() {
        authorization.grant("operator", Capability.TENANT_ONBOARDING_MANAGE, ResourceScope.platform());
        facts.clear();

        invitations.overview(OwnerInvitationService.NONE, ONBOARDER, "corr");

        assertThat(facts)
                .singleElement()
                .satisfies(fact -> assertThat(fact.changeDocument()).containsExactly(Map.entry("revealedCount", 1)));
    }

    // ------------------------------------------------------------- fixtures

    private static OwnerInvitationOverviewRow row(List<OwnerInvitationOverviewRow> rows, String slug) {
        return rows.stream()
                .filter(row -> row.tenantSlug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + slug));
    }

    /** The link out of the email the relay sent to this address; the platform never stored it. */
    private String tokenFor(String address) {
        OutgoingMail mail = mailer.sent.stream()
                .filter(sent -> sent.to().equals(address))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("nothing was sent to " + address));
        Matcher link = LINK.matcher(mail.text());
        assertThat(link.find()).as("the email carries the link").isTrue();
        return link.group(1);
    }

    private UUID tenant(String slug, String displayName, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                            default_timezone, status, version)
                        VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', :status, 0)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("name", displayName)
                .param("status", status)
                .update();
        return id;
    }

    /**
     * A tenant onboarded before invitations existed: the owner step completed and
     * linked a subject, and no invitation was ever queued for it.
     */
    private void linkedOwnerWithNoInvitation(UUID tenantId, String subjectId) {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tenant.onboarding_runs (id, tenant_id, template_id, template_version, status,
                            current_phase, started_by)
                        VALUES (:id, :tenantId, '94cc9f54-7451-4db1-ac13-4073f6833b15', 1, 'ACTIVE',
                            'PROVISIONING', 'operator')
                        """).param("id", runId).param("tenantId", tenantId).update();
        jdbc.sql("""
                        INSERT INTO tenant.onboarding_steps (id, tenant_id, run_id, step_key, phase, sequence_number,
                            status, required, external_reference)
                        VALUES (:id, :tenantId, :runId, 'TENANT_OWNER_LINK_OR_INVITE', 'PROVISIONING', 2,
                            'COMPLETED', true, :subjectId)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("subjectId", subjectId)
                .update();
    }

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

        void put(String subject, String email, boolean hasPassword) {
            accounts.put(subject, new StaffAccount(subject, email, false, hasPassword));
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            return Optional.ofNullable(accounts.get(subjectId));
        }

        @Override
        public void completeSetup(String subjectId, String firstName, String lastName, String password) {
            StaffAccount account = java.util.Objects.requireNonNull(accounts.get(subjectId));
            accounts.put(subjectId, new StaffAccount(subjectId, account.email(), true, true));
        }
    }

    private static final class RecordingMailer implements PlatformMailer {
        private final List<OutgoingMail> sent = new ArrayList<>();

        @Override
        public MailOutcome send(OutgoingMail mail) {
            sent.add(mail);
            return new MailOutcome.Sent();
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
