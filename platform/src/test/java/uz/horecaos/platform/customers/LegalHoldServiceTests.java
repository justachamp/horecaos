package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.ApprovalService.Decision;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.application.CustomerErasureService.AccountNotFoundException;
import uz.horecaos.platform.customers.application.LegalHoldService;
import uz.horecaos.platform.customers.application.LegalHoldService.NoSuchHoldException;
import uz.horecaos.platform.customers.application.LegalHoldService.Reason;
import uz.horecaos.platform.customers.application.LegalHoldService.Result;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLegalHoldStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLegalHoldStore.HoldRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * A legal hold is placed and lifted by two people (ADR 0029, ADR 0027), against the real approval model and the policies V0682
 * seeds.
 *
 * <p>What each test would still pass on if the code were broken: the placing case asserts no hold exists after the first call,
 * so a service that placed on the maker's word and asked afterwards fails it; the checker case asserts the maker cannot approve
 * their own request, so an approval model that accepted self-approval fails it; the binding case asserts an approval for one
 * reason does not place a hold for another; the audit case reads the facts back with the approval ids they were made under.
 */
class LegalHoldServiceTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-7000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-7000-7000-8000-0000000000a2");
    private static final UUID ACCOUNT = UUID.fromString("018f9f10-7000-7000-8000-0000000000b1");
    private static final ActorRef MAKER = ActorRef.user("hold-maker", null);
    private static final ActorRef CHECKER = ActorRef.user("hold-checker", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ApprovalService approvals;
    private LegalHoldService holdService;
    private TransactionTemplate tx;
    private Holds holds;

    /**
     * The service as a request reaches it: every call in its own transaction. An approved action must run inside one, so
     * that spending the approval commits with the action or rolls back with it; the service is {@code @Transactional}, which
     * a hand-built instance has no proxy to honour.
     */
    private final class Holds {
        Result place(UUID tenant, UUID account, Reason reason, String note, ActorRef actor) {
            return tx.execute(status -> holdService.place(tenant, account, reason, note, actor));
        }

        Result lift(UUID tenant, UUID account, UUID holdId, String note, ActorRef actor) {
            return tx.execute(status -> holdService.lift(tenant, account, holdId, note, actor));
        }

        List<HoldRow> history(UUID tenant, UUID account) {
            return holdService.history(tenant, account);
        }
    }

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
        // Not TRUNCATE ... CASCADE on the tenants: that follows the foreign key from audit.approval_policies and would
        // take the
        // platform policies V0682 seeds with it, which are exactly what this suite is about.
        jdbc.sql("DELETE FROM audit.approval_requests").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("DELETE FROM customer.legal_holds").update();
        jdbc.sql("DELETE FROM customer.customer_accounts WHERE tenant_id IN (:a, :b)")
                .param("a", TENANT)
                .param("b", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id IN (:a, :b)")
                .param("a", TENANT)
                .param("b", OTHER_TENANT)
                .update();
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant).param("slug", "holds-" + tenant).update();
        }
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", ACCOUNT).param("t", TENANT).update();

        // After V0682's policies take effect (2026-10-14).
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
        JsonMapper mapper = JsonMapper.builder().build();
        JdbcAuditRecorder audit = new JdbcAuditRecorder(jdbc, mapper);
        approvals = new JdbcApprovalService(jdbc, audit, clock, new SimpleMeterRegistry(), mapper);
        holdService = new LegalHoldService(
                new JdbcLegalHoldStore(jdbc), new JdbcCustomerStore(jdbc), approvals, audit, clock);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        holds = new Holds();
    }

    private UUID pendingRequestOf(Result result) {
        assertThat(result).isInstanceOf(Result.Awaiting.class);
        ApprovalOutcome outcome = ((Result.Awaiting) result).outcome();
        assertThat(outcome).isInstanceOf(ApprovalOutcome.Pending.class);
        return ((ApprovalOutcome.Pending) outcome).requestId();
    }

    private long activeHolds() {
        return jdbc.sql(
                        "SELECT count(*) FROM customer.legal_holds WHERE customer_account_id = :a AND status = 'ACTIVE'")
                .param("a", ACCOUNT)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName(
            "placing a hold asks a second person first, places nothing on the maker's word, and places it once they approve")
    void aHoldNeedsASecondPerson() {
        Result first = holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim 2026/118", MAKER);

        UUID request = pendingRequestOf(first);
        assertThat(activeHolds()).as("recorded and not performed").isZero();

        approvals.decide(request, Decision.APPROVE, CHECKER, "Seen the claim");
        Result second = holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim 2026/118", MAKER);

        assertThat(second).isInstanceOf(Result.Done.class);
        HoldRow hold = ((Result.Done) second).hold();
        assertThat(hold.status()).isEqualTo("ACTIVE");
        assertThat(hold.reasonCode()).isEqualTo("LITIGATION");
        assertThat(hold.placedByActorId()).isEqualTo("hold-maker");
        assertThat(hold.placeApprovalId()).isEqualTo(request);
        assertThat(activeHolds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a maker cannot approve their own hold")
    void theMakerCannotApproveTheirOwnHold() {
        UUID request = pendingRequestOf(holds.place(TENANT, ACCOUNT, Reason.INVESTIGATION, "Fraud query", MAKER));

        assertThatThrownBy(() -> approvals.decide(request, Decision.APPROVE, MAKER, "me"))
                .isInstanceOf(ApprovalService.SelfApprovalException.class);
        assertThat(activeHolds()).isZero();
    }

    @Test
    @DisplayName("an approval is for the reason it was asked about: another reason needs its own signature")
    void anApprovalIsBoundToTheReason() {
        UUID request = pendingRequestOf(holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim", MAKER));
        approvals.decide(request, Decision.APPROVE, CHECKER, "ok");

        Result other = holds.place(TENANT, ACCOUNT, Reason.REGULATORY_REQUEST, "Claim", MAKER);

        assertThat(pendingRequestOf(other)).isNotEqualTo(request);
        assertThat(activeHolds()).isZero();
    }

    @Test
    @DisplayName("a declined request places no hold and says so")
    void aDeclinedRequestPlacesNothing() {
        UUID request = pendingRequestOf(holds.place(TENANT, ACCOUNT, Reason.OTHER, "Because", MAKER));
        approvals.decide(request, Decision.DECLINE, CHECKER, "No basis");

        Result again = holds.place(TENANT, ACCOUNT, Reason.OTHER, "Because", MAKER);

        assertThat(again).isInstanceOf(Result.Awaiting.class);
        assertThat(((Result.Awaiting) again).outcome()).isInstanceOf(ApprovalOutcome.Declined.class);
        assertThat(activeHolds()).isZero();
    }

    @Test
    @DisplayName("a customer who already has a hold gets that hold back, with no new approval")
    void placingTwiceIsTheSameHold() {
        UUID request = pendingRequestOf(holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim", MAKER));
        approvals.decide(request, Decision.APPROVE, CHECKER, "ok");
        HoldRow placed = ((Result.Done) holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim", MAKER)).hold();
        long requestsBefore = approvalRequests();

        Result again = holds.place(TENANT, ACCOUNT, Reason.REGULATORY_REQUEST, "Different", CHECKER);

        assertThat(((Result.Done) again).hold().id()).isEqualTo(placed.id());
        assertThat(approvalRequests())
                .as("a hold already in force needs no second signature")
                .isEqualTo(requestsBefore);
        assertThat(activeHolds()).isEqualTo(1);
    }

    @Test
    @DisplayName("lifting a hold asks a second person too, and lifting twice is the same lift")
    void liftingNeedsASecondPersonToo() {
        UUID placeRequest = pendingRequestOf(holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim", MAKER));
        approvals.decide(placeRequest, Decision.APPROVE, CHECKER, "ok");
        HoldRow hold = ((Result.Done) holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim", MAKER)).hold();

        UUID liftRequest = pendingRequestOf(holds.lift(TENANT, ACCOUNT, hold.id(), "Claim withdrawn", MAKER));
        assertThat(activeHolds())
                .as("still in force until a second person agrees")
                .isEqualTo(1);

        approvals.decide(liftRequest, Decision.APPROVE, CHECKER, "Confirmed withdrawn");
        HoldRow lifted = ((Result.Done) holds.lift(TENANT, ACCOUNT, hold.id(), "Claim withdrawn", MAKER)).hold();
        assertThat(lifted.status()).isEqualTo("LIFTED");
        assertThat(lifted.liftedByActorId()).isEqualTo("hold-maker");
        assertThat(lifted.liftApprovalId()).isEqualTo(liftRequest);
        assertThat(activeHolds()).isZero();

        Result replay = holds.lift(TENANT, ACCOUNT, hold.id(), "Claim withdrawn", MAKER);
        assertThat(((Result.Done) replay).hold().liftedAt()).isEqualTo(lifted.liftedAt());
    }

    @Test
    @DisplayName(
            "placing and lifting are audited with the approval each was made under, and no free text is stored on the hold")
    void bothDirectionsAreAudited() {
        UUID placeRequest = pendingRequestOf(holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim 2026/118", MAKER));
        approvals.decide(placeRequest, Decision.APPROVE, CHECKER, "ok");
        HoldRow hold = ((Result.Done) holds.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim 2026/118", MAKER)).hold();
        UUID liftRequest = pendingRequestOf(holds.lift(TENANT, ACCOUNT, hold.id(), "Withdrawn", MAKER));
        approvals.decide(liftRequest, Decision.APPROVE, CHECKER, "ok");
        holds.lift(TENANT, ACCOUNT, hold.id(), "Withdrawn", MAKER);

        List<String> facts = jdbc.sql("""
                        SELECT action_code || '|' || coalesce(approval_request_id::text, '-')
                          FROM audit.audit_events
                         WHERE action_code LIKE 'customer.legal-hold.%'
                         ORDER BY recorded_at, id
                        """).query(String.class).list();
        assertThat(facts)
                .containsExactlyInAnyOrder(
                        "customer.legal-hold.placed|" + placeRequest, "customer.legal-hold.lifted|" + liftRequest);
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM information_schema.columns
                                 WHERE table_schema = 'customer' AND table_name = 'legal_holds'
                                   AND data_type IN ('text', 'character varying')
                                   AND column_name NOT IN ('status', 'reason_code', 'placed_by_actor_type',
                                       'placed_by_actor_id', 'lifted_by_actor_type', 'lifted_by_actor_id')
                                """).query(Long.class).single())
                .as(
                        "the justification lives on the approval request; a free-text column here would be one more place a name could end up")
                .isZero();
    }

    @Test
    @DisplayName("with the platform policy gone, a hold cannot be placed at all: fail-closed, not one person alone")
    void withoutAPolicyNothingIsPlaced() {
        // Its own database: deleting the platform policies would take them from every other test in this class.
        try (TestDatabase.Handle own = TestDatabase.migrated()) {
            JdbcClient alone = JdbcClient.create(own.dataSource());
            alone.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, 'no-policy', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", TENANT).update();
            alone.sql("""
                    INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version, version)
                    VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                    """).param("id", ACCOUNT).param("t", TENANT).update();
            alone.sql(
                            "DELETE FROM audit.approval_policies WHERE action_code IN ('customer.legal-hold.place', 'customer.legal-hold.lift')")
                    .update();
            Clock clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
            JsonMapper mapper = JsonMapper.builder().build();
            JdbcAuditRecorder audit = new JdbcAuditRecorder(alone, mapper);
            LegalHoldService unguarded = new LegalHoldService(
                    new JdbcLegalHoldStore(alone),
                    new JdbcCustomerStore(alone),
                    new JdbcApprovalService(alone, audit, clock, new SimpleMeterRegistry(), mapper),
                    audit,
                    clock);
            TransactionTemplate inTransaction =
                    new TransactionTemplate(new DataSourceTransactionManager(own.dataSource()));

            assertThatThrownBy(() -> inTransaction.execute(
                            status -> unguarded.place(TENANT, ACCOUNT, Reason.LITIGATION, "Claim", MAKER)))
                    .isInstanceOf(ApiException.class);
            assertThat(alone.sql("SELECT count(*) FROM customer.legal_holds")
                            .query(Long.class)
                            .single())
                    .isZero();
        }
    }

    @Test
    @DisplayName(
            "another tenant's account cannot be held, and another account's hold cannot be lifted through this one")
    void tenantAndAccountBoundaries() {
        assertThatThrownBy(() -> holds.place(OTHER_TENANT, ACCOUNT, Reason.OTHER, "x", MAKER))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> holds.lift(TENANT, ACCOUNT, UUID.randomUUID(), "x", MAKER))
                .isInstanceOf(NoSuchHoldException.class);
        assertThatThrownBy(() -> holds.history(OTHER_TENANT, ACCOUNT)).isInstanceOf(AccountNotFoundException.class);
    }

    private long approvalRequests() {
        return jdbc.sql("SELECT count(*) FROM audit.approval_requests WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }
}
