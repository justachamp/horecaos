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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private JdbcOwnerInvitationStore store;
    private JdbcOwnerInvitationEventStore events;
    private TransactionTemplate transactions;
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
        store = new JdbcOwnerInvitationStore(jdbc);
        events = new JdbcOwnerInvitationEventStore(jdbc);
        transactions = new TransactionTemplate(new JdbcTransactionManager(db.dataSource()));
        invitations =
                new OwnerInvitationService(store, events, accounts, authorization, facts::add, transactions, clock);
        mailer = new RecordingMailer();
        relay = new OwnerInvitationRelay(
                store, events, accounts, mailer, facts::add, transactions, clock, "https://ops.test/");

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
            assertThat(fact.capabilityUsed())
                    .as("under which capability a staff address was read, or the attribution says nothing")
                    .isEqualTo(Capability.TENANT_ONBOARDING_MANAGE.code());
            assertThat(fact.scope())
                    .as("a cross-tenant read is recorded at platform scope, where it was authorised")
                    .isEqualTo(ResourceScope.platform());
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

    /**
     * The cap is applied by the query and the state filter by the service, in
     * that order, so whatever the query leaves beyond the cap is what the
     * screen will not have. Ordering alphabetically would make that an
     * arbitrary letter; ordering settled-last makes it a tenant nobody has to
     * chase. Exercised with a limit of one rather than two hundred tenants.
     */
    @Test
    @DisplayName("when the cap bites it takes the tenants whose owner is already set up")
    void theCapFallsOnTheTenantsNobodyHasToChase() {
        relay.runOnce();
        invitations.accept(tokenFor("anvar@example.uz"), "Anvar", "Anvarov", "a-long-enough-passphrase", "corr");

        // 'Accepted & Co' sorts first by name, so an alphabetical cap of one
        // would return it and lose both tenants that still need an owner.
        assertThat(store.overview(1))
                .extracting(JdbcOwnerInvitationStore.OverviewRow::tenantSlug)
                .containsAnyOf("waiting-co", "never-told-co")
                .doesNotContain("accepted-co");
        assertThat(store.overview(2))
                .extracting(JdbcOwnerInvitationStore.OverviewRow::tenantSlug)
                .containsExactlyInAnyOrder("waiting-co", "never-told-co");
    }

    /**
     * The urgency order is the screen's whole argument for existing, and three
     * of its seven ranks used to be all that was ever asserted. A reshuffle that
     * sent FAILED to the bottom -- the one row an operator opens this screen to
     * find -- would have been invisible.
     */
    @Test
    @DisplayName("every rank is ordered, from the worst news to the tenants that are done")
    void theOrderRunsFromTheWorstNewsToTheDone() {
        invited("failed-co", "Failed Choyxona", "failed-owner", "failed@example.uz");
        invited("not-needed-co", "Not Needed Kafe", "not-needed-owner", "not-needed@example.uz");
        accounts.put("not-needed-owner", "not-needed@example.uz", true);
        mailer.next = mail -> "failed@example.uz".equals(mail.to())
                ? new MailOutcome.Rejected("ADDRESS_REJECTED")
                : new MailOutcome.Sent();

        relay.runOnce();
        invitations.accept(tokenFor("anvar@example.uz"), "Anvar", "Anvarov", "a-long-enough-passphrase", "corr");
        clock.advance(OwnerInvitationService.LINK_LIFETIME.plus(Duration.ofMinutes(1)));

        // Sent after the advance, so its own link is live while the fixture's
        // has run out: a state the same clock cannot produce twice.
        invited("sent-co", "Sent Osh", "sent-owner", "sent@example.uz");
        relay.runOnce();
        invited("queued-co", "Queued Non", "queued-owner", "queued@example.uz");

        assertThat(invitations.overview(null, ONBOARDER, "corr"))
                .extracting(OwnerInvitationOverviewRow::state)
                .containsExactly(
                        "FAILED", "EXPIRED", OwnerInvitationService.NONE, "QUEUED", "SENT", "NOT_NEEDED", "ACCEPTED");
    }

    /**
     * The alphabetical order inside one state is the query's, and this is the
     * test of the query. It is not a test of the service's tiebreak and must
     * not be read as one: every row here shares an urgency rank, the query
     * already hands them over by {@code display_name}, and {@link List#sort} is
     * stable -- so deleting {@code .thenComparing(tenantName)} in the service
     * leaves both assertions below green. {@link
     * #theTiebreakSortsWhatTheQueryHandedBackOutOfOrder} is the one that
     * observes the comparator.
     */
    @Test
    @DisplayName("tenants in one state are listed alphabetically, which is the query's ordering")
    void tenantsInOneStateAreAlphabetical() {
        // Inserted last-first, so a list that did not sort would hand them back
        // in this order.
        invited("zulfiya-co", "Zulfiya Osh", "zulfiya-owner", "zulfiya@example.uz");
        invited("alisher-co", "Alisher Kafe", "alisher-owner", "alisher@example.uz");

        assertThat(invitations.overview("QUEUED", ONBOARDER, "corr"))
                .extracting(OwnerInvitationOverviewRow::tenantName)
                .containsExactly("Accepted & Co", "Alisher Kafe", "Waiting Kafe", "Zulfiya Osh");
        assertThat(store.overview(200))
                .extracting(JdbcOwnerInvitationStore.OverviewRow::tenantName)
                .as("the query's half of the same guarantee: alphabetical inside each settled/unsettled half")
                .containsSubsequence("Accepted & Co", "Alisher Kafe", "Waiting Kafe", "Zulfiya Osh");
    }

    /**
     * The service sorts by urgency and then by name, and the second half of
     * that is invisible through the real store: every urgency rank lies wholly
     * inside one of the query's two settled/unsettled groups, so within a rank
     * the query's own {@code t.display_name} always already holds. The only way
     * to see the tiebreak work is to hand the service the order the query never
     * produces -- which is also the order a future change to that ORDER BY
     * would start producing, with nothing else in this file noticing.
     */
    @Test
    @DisplayName("two tenants in one state come back alphabetically however the query ordered them")
    void theTiebreakSortsWhatTheQueryHandedBackOutOfOrder() {
        UUID zulfiya = UUID.randomUUID();
        UUID alisher = UUID.randomUUID();
        JdbcOwnerInvitationStore backwards = new JdbcOwnerInvitationStore(jdbc) {
            @Override
            public List<JdbcOwnerInvitationStore.OverviewRow> overview(int limit) {
                return List.of(
                        queued(zulfiya, "zulfiya-co", "Zulfiya Osh"), queued(alisher, "alisher-co", "Alisher Kafe"));
            }
        };
        OwnerInvitationService sorting =
                new OwnerInvitationService(backwards, events, accounts, authorization, facts::add, transactions, clock);

        assertThat(sorting.overview(null, READER, "corr"))
                .extracting(OwnerInvitationOverviewRow::tenantName)
                .as("one urgency rank, handed over backwards: the service's own tiebreak is all that orders it")
                .containsExactly("Alisher Kafe", "Zulfiya Osh");
    }

    /**
     * Both caps are the service's to pass, and neither call site was pinned:
     * {@code store.ownerStates(OVERVIEW_LIMIT)} is a plausible copy/paste
     * between two constants ten lines apart, and it would quietly shrink the
     * directory's column to the first 200 tenants -- which ADR 0100 says must
     * read as "not known" -- with every other assertion in this file green.
     */
    @Test
    @DisplayName("each read asks the store for the cap its own documentation names")
    void theDocumentedCapsAreTheOnesAskedFor() {
        List<Integer> overviewLimits = new ArrayList<>();
        List<Integer> stateLimits = new ArrayList<>();
        JdbcOwnerInvitationStore recording = new JdbcOwnerInvitationStore(jdbc) {
            @Override
            public List<JdbcOwnerInvitationStore.OverviewRow> overview(int limit) {
                overviewLimits.add(limit);
                return super.overview(limit);
            }

            @Override
            public List<JdbcOwnerInvitationStore.OwnerStateRow> ownerStates(int limit) {
                stateLimits.add(limit);
                return super.ownerStates(limit);
            }
        };
        OwnerInvitationService counted =
                new OwnerInvitationService(recording, events, accounts, authorization, facts::add, transactions, clock);

        counted.overview(null, READER, "corr");
        counted.ownerStates();

        assertThat(overviewLimits).containsExactly(OwnerInvitationService.OVERVIEW_LIMIT);
        assertThat(stateLimits)
                .as("the address-free projection's cap, not the overview's")
                .containsExactly(OwnerInvitationService.OWNER_STATE_LIMIT);
        assertThat(OwnerInvitationService.OVERVIEW_LIMIT)
                .as("ADR 0100 documents 200 rows for the overview")
                .isEqualTo(200);
        assertThat(OwnerInvitationService.OWNER_STATE_LIMIT)
                .as("ADR 0100 documents 1000 rows for the address-free projection")
                .isEqualTo(1000);
    }

    /**
     * The overview used to be {@code @Transactional}, which bound a Hikari
     * connection at method entry and held it across every recipient the loop
     * resolved -- up to 200 tenants, two blocking Keycloak calls each, each one
     * able to wait out a ten-second read timeout without failing the request.
     * Three of those on one screen would have held three of ten pool
     * connections for minutes. So: the rows and the addresses are read with
     * nothing bound, and only the reveal fact is written in a transaction.
     */
    @Test
    @DisplayName("recipients are resolved with no transaction bound, and the reveal fact is written inside one")
    void theIdentityProviderIsNeverCalledInsideATransaction() {
        authorization.grant("operator", Capability.TENANT_ONBOARDING_MANAGE, ResourceScope.platform());
        facts.clear();
        List<Boolean> whileResolving = new ArrayList<>();
        List<Boolean> whileRecording = new ArrayList<>();
        StaffAccounts watched = new StaffAccounts() {
            @Override
            public Optional<StaffAccount> find(String subjectId) {
                whileResolving.add(TransactionSynchronizationManager.isActualTransactionActive());
                return accounts.find(subjectId);
            }

            @Override
            public void completeSetup(String subjectId, String firstName, String lastName, String password) {
                throw new UnsupportedOperationException("nothing under test sets a password");
            }

            @Override
            public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
                return Optional.empty();
            }

            @Override
            public void setPassword(String subjectId, String password) {
                throw new UnsupportedOperationException("not part of this test");
            }

            @Override
            public void logoutEverywhere(String subjectId) {
                throw new UnsupportedOperationException("not part of this test");
            }
        };
        OwnerInvitationService watchedService = new OwnerInvitationService(
                store,
                events,
                watched,
                authorization,
                fact -> {
                    whileRecording.add(TransactionSynchronizationManager.isActualTransactionActive());
                    facts.add(fact);
                },
                transactions,
                clock);

        var rows = watchedService.overview(null, ONBOARDER, "corr");

        assertThat(rows).hasSize(3);
        assertThat(whileResolving)
                .as("a Keycloak round trip inside a transaction pins a pool connection for as long as it takes")
                .hasSize(3)
                .containsOnly(false);
        assertThat(whileRecording)
                .as("the fact is still a transaction's worth of work, just its own")
                .containsExactly(true);
        assertThat(facts)
                .singleElement()
                .satisfies(fact -> assertThat(fact.changeDocument()).containsExactly(Map.entry("revealedCount", 3)));
    }

    /** One overview row for a tenant with no invitation and no owner to resolve. */
    private static JdbcOwnerInvitationStore.OverviewRow queued(UUID tenantId, String slug, String name) {
        return new JdbcOwnerInvitationStore.OverviewRow(
                tenantId,
                slug,
                name,
                "PROVISIONING",
                UUID.randomUUID(),
                null,
                "QUEUED",
                "ru",
                0,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * NOT_NEEDED is the only state the relay alone can produce, and the only one
     * no fixture reached: an owner who gained a password between the queue and
     * the send. It has to behave like ACCEPTED everywhere, or the screen an
     * operator opens to find work lists people who can already sign in.
     */
    @Test
    @DisplayName("an owner who already had a password is settled, like one who accepted")
    void aNotNeededTenantIsSettledLikeAnAcceptedOne() {
        invited("settled-co", "Settled Choyxona", "settled-owner", "settled@example.uz");
        accounts.put("settled-owner", "settled@example.uz", true);
        relay.runOnce();

        assertThat(invitations.overview(OwnerInvitationService.OUTSTANDING, ONBOARDER, "corr"))
                .extracting(OwnerInvitationOverviewRow::tenantSlug)
                .as("nobody has to chase an owner who can already sign in")
                .doesNotContain("settled-co");
        assertThat(invitations.overview("NOT_NEEDED", ONBOARDER, "corr"))
                .extracting(OwnerInvitationOverviewRow::tenantSlug)
                .containsExactly("settled-co");
        assertThat(store.overview(2))
                .extracting(JdbcOwnerInvitationStore.OverviewRow::tenantSlug)
                .as("settled sorts last, so the cap takes it before a tenant that still needs an owner")
                .doesNotContain("settled-co");
    }

    /**
     * ADR 0100: the directory marks which tenants are waiting and renders no
     * address at all, so it must be able to ask without one being read. A
     * projection that fetched the addresses and dropped them would put every
     * outstanding owner's address in a response body nobody reads, and would
     * record a reveal of people no human was shown.
     */
    @Test
    @DisplayName("the address-free projection tells the four cases apart, and reads no address to do it")
    void theProjectionAnswersWithoutAnAddress() {
        UUID nothingYet = tenant("nothing-yet", "Nothing Yet", "PROVISIONING");
        UUID settled = invited("settled-co", "Settled Choyxona", "settled-owner", "settled@example.uz");
        accounts.put("settled-owner", "settled@example.uz", true);
        relay.runOnce();
        invitations.accept(tokenFor("anvar@example.uz"), "Anvar", "Anvarov", "a-long-enough-passphrase", "corr");

        authorization.grant("operator", Capability.TENANT_ONBOARDING_MANAGE, ResourceScope.platform());
        facts.clear();
        accounts.reads = 0;

        var states = invitations.ownerStates();

        assertThat(stateOf(states, nothingYet))
                .as("nobody has linked or invited an owner, which is not the same as an owner who is fine")
                .isEqualTo(OwnerInvitationService.NO_OWNER);
        assertThat(stateOf(states, neverTold)).isEqualTo(OwnerInvitationService.NONE);
        assertThat(stateOf(states, waiting)).isEqualTo("SENT");
        assertThat(stateOf(states, accepted)).isEqualTo("ACCEPTED");
        assertThat(stateOf(states, settled)).isEqualTo("NOT_NEEDED");
        assertThat(states)
                .extracting(OwnerInvitationService.OwnerStateView::tenantId)
                .as("an archived tenant is nobody's work here either")
                .doesNotContain(archived);

        assertThat(accounts.reads)
                .as("no address is fetched, rather than fetched and dropped")
                .isZero();
        assertThat(facts)
                .as("no reveal, so the count of the reveals that matter keeps its meaning")
                .isEmpty();
        assertThat(states.toString()).doesNotContain("example.uz");

        // The query's own half of the cap ADR 0100 documents: a bound, and an
        // alphabetical one, so a tenant beyond it is simply absent. Five
        // unarchived tenants stand here -- Accepted & Co, Never Told Osh,
        // Nothing Yet, Settled Choyxona, Waiting Kafe -- and a dropped LIMIT or
        // a dropped ORDER BY is invisible to every assertion above.
        assertThat(store.ownerStates(2))
                .extracting(JdbcOwnerInvitationStore.OwnerStateRow::tenantId)
                .as("the cap is the query's, and it falls alphabetically by display name")
                .containsExactly(accepted, neverTold);
    }

    // ------------------------------------------------------------- fixtures

    private static String stateOf(List<OwnerInvitationService.OwnerStateView> states, UUID tenantId) {
        return states.stream()
                .filter(state -> state.tenantId().equals(tenantId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no state for " + tenantId))
                .state();
    }

    /** A tenant, an owner without a password, and an invitation queued for them. */
    private UUID invited(String slug, String displayName, String subjectId, String email) {
        UUID id = tenant(slug, displayName, "PROVISIONING");
        accounts.put(subjectId, email, false);
        invitations.inviteIfNeeded(id, subjectId, "ru", UUID.randomUUID());
        return id;
    }

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

        /** How many times the identity provider was asked for an address. */
        private int reads;

        void put(String subject, String email, boolean hasPassword) {
            accounts.put(subject, new StaffAccount(subject, email, false, hasPassword));
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            reads++;
            return Optional.ofNullable(accounts.get(subjectId));
        }

        @Override
        public void completeSetup(String subjectId, String firstName, String lastName, String password) {
            StaffAccount account = java.util.Objects.requireNonNull(accounts.get(subjectId));
            accounts.put(subjectId, new StaffAccount(subjectId, account.email(), true, true));
        }

        @Override
        public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
            return Optional.empty();
        }

        @Override
        public void setPassword(String subjectId, String password) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public void logoutEverywhere(String subjectId) {
            throw new UnsupportedOperationException("not part of this test");
        }
    }

    private static final class RecordingMailer implements PlatformMailer {
        private final List<OutgoingMail> sent = new ArrayList<>();

        /** Per address, so one pass of the relay can produce more than one outcome. */
        private Function<OutgoingMail, MailOutcome> next = mail -> new MailOutcome.Sent();

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
