package uz.horecaos.platform.integration.failures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0006 exit criterion: operations can identify, safely retry, and audit
 * every exhausted item without direct SQL, and no replay can duplicate a
 * confirmed provider side effect.
 */
class FailureOperationsServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e02");
    private static final String CONSUMER = "test-consumer";
    private static final String OTHER_CONSUMER = "other-consumer";
    private static final ActorRef OPERATOR = ActorRef.user("operator-1", "Operator One");
    private static final ActorRef CHECKER = ActorRef.user("checker-1", "Checker One");
    private static final String RECONCILED = "Provider confirms no charge was taken";
    private static final String EVIDENCE = "recon-2026-08-20-17";

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private FailureOperationsService operations;
    private uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService approvals;
    private Clock clock;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant();

        clock = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);
        JdbcAuditRecorder recorder =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        approvals = new uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService(
                jdbc, recorder, clock, new SimpleMeterRegistry());
        // A real transaction manager over the same DataSource, because the two
        // properties these tests exist for are both properties of a boundary: the
        // approval request has to survive the exception that reports it, and an
        // approved outcome carries a grant that has to be spent in the
        // transaction performing the resolution.
        operations = new FailureOperationsService(
                jdbc,
                recorder,
                approvals,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                clock);
    }

    @Test
    void listsDeadLetteredOutboxEvents() {
        UUID eventId = deadLetteredOutboxEvent();

        List<FailureOperationsService.FailureSummary> failures =
                operations.listOutboxFailures(TENANT, "DEAD_LETTER", 50);

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().id()).isEqualTo(eventId);
        assertThat(failures.getFirst().eventType()).isEqualTo("TenantCreated");
    }

    @Test
    void oneOutboxEventIsReadableOnItsOwn() {
        UUID eventId = deadLetteredOutboxEvent();

        FailureOperationsService.OutboxFailureDetail detail =
                operations.findOutboxFailure(eventId, TENANT).orElseThrow();

        assertThat(detail.eventId()).isEqualTo(eventId);
        assertThat(detail.status()).isEqualTo("DEAD_LETTER");
        assertThat(detail.errorCode()).isEqualTo("TRANSIENT_INFRASTRUCTURE");
        assertThat(detail.topic()).isEqualTo("tenancy.events");
        assertThat(detail.attemptCount()).isEqualTo(10);
        assertThat(detail.deadLetteredAt()).isNotNull();
    }

    @Test
    void aSingleReadStillAnswersAfterTheItemHasMovedOn() {
        // Not filtered by status on purpose. An operator reaches this read right
        // after retrying or resolving, and one that only answered for
        // DEAD_LETTER would go blank exactly when they wanted to confirm the
        // transition they had just made.
        UUID eventId = deadLetteredOutboxEvent();
        operations.resolveOutboxEvent(eventId, FailureCategory.DOMAIN_REJECTED, OPERATOR, "Tenant was deleted", null);

        FailureOperationsService.OutboxFailureDetail detail =
                operations.findOutboxFailure(eventId, TENANT).orElseThrow();

        assertThat(detail.status()).isEqualTo("RESOLVED");
        assertThat(detail.resolvedBy())
                .as("RESOLVED exists to keep an operator override visible; a read that hid "
                        + "who decided and why would give that away again")
                .isEqualTo("operator-1");
        assertThat(detail.resolutionReason()).isEqualTo("Tenant was deleted");
    }

    @Test
    void anotherTenantsEventIsAbsentRatherThanReturned() {
        insertOtherTenant();
        UUID theirs = UUID.randomUUID();
        insertOutboxEvent(theirs, "DEAD_LETTER", OTHER_TENANT);

        assertThat(operations.findOutboxFailure(theirs, TENANT))
                .as("the narrowing is applied in the query, so the caller never holds the row at all")
                .isEmpty();
        assertThat(operations.findOutboxFailure(theirs, OTHER_TENANT)).isPresent();
    }

    @Test
    void anInboxReadResolvesTheRightConsumersRow() {
        UUID eventId = deadLetteredInboxMessage(CONSUMER);
        deadLetteredInboxMessage("other-consumer", eventId);

        assertThat(operations
                        .findInboxFailure(CONSUMER, eventId, TENANT)
                        .orElseThrow()
                        .consumerName())
                .isEqualTo(CONSUMER);
        assertThat(operations
                        .findInboxFailure("other-consumer", eventId, TENANT)
                        .orElseThrow()
                        .consumerName())
                .as("(consumer, event) is the key; one event reaches several consumers and each "
                        + "carries its own decision")
                .isEqualTo("other-consumer");
        assertThat(operations.findInboxFailure("never-saw-it", eventId, TENANT)).isEmpty();
    }

    @Test
    void neitherProjectionCarriesThePayload() {
        // Structural rather than serialisation-level, so it survives a change of
        // mapper and cannot be satisfied by renaming the field. ADR 0029: an
        // operator's authority to work the failure queue is not authority to
        // read the customer record behind an item.
        assertThat(componentsOf(FailureOperationsService.OutboxFailureDetail.class))
                .doesNotContain("payload", "payloadJson", "traceContext");
        assertThat(componentsOf(FailureOperationsService.InboxFailureDetail.class))
                .doesNotContain("payload", "payloadJson")
                .as("the hash stands in for it: it discloses nothing and it is the fact a "
                        + "hash-collision decision actually needs")
                .contains("payloadSha256");
    }

    private static List<String> componentsOf(Class<?> record) {
        return java.util.Arrays.stream(record.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
    }

    @Test
    void retryingReturnsTheSameImmutableWorkToPending() {
        UUID eventId = deadLetteredOutboxEvent();

        assertThat(operations.retryOutboxEvent(eventId, OPERATOR, "Broker recovered"))
                .isTrue();

        assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT event_id FROM integration.outbox_events")
                        .query(UUID.class)
                        .single())
                .as("""
                        The provider idempotency key derives from the event id, so a retry
                        that minted a new id would defeat the deduplication it depends on.""")
                .isEqualTo(eventId);
    }

    @Test
    void retryingIsAuditedWithActorAndReason() {
        UUID eventId = deadLetteredOutboxEvent();

        operations.retryOutboxEvent(eventId, OPERATOR, "Broker recovered");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'integration.outbox.retried'
                   AND actor_subject = 'operator-1' AND reason = 'Broker recovered'
                """).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void twoOperatorsRetryingConcurrentlyProduceOneTransition() throws Exception {
        UUID eventId = deadLetteredOutboxEvent();
        int operators = 8;

        long succeeded;
        try (ExecutorService pool = Executors.newFixedThreadPool(operators)) {
            List<Callable<Boolean>> calls = java.util.Collections.nCopies(
                    operators, () -> operations.retryOutboxEvent(eventId, OPERATOR, "incident response"));
            List<Future<Boolean>> results = pool.invokeAll(calls);
            succeeded = results.stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception failure) {
                            return false;
                        }
                    })
                    .count();
        }

        assertThat(succeeded)
                .as("compare-and-set from the terminal state means one transition, not eight republished events")
                .isEqualTo(1);
        assertThat(auditCount("integration.outbox.retried")).isEqualTo(1L);
    }

    @Test
    void anEventThatIsNotDeadLetteredCannotBeRetried() {
        UUID eventId = UUID.randomUUID();
        insertOutboxEvent(eventId, "PENDING");

        assertThat(operations.retryOutboxEvent(eventId, OPERATOR, "premature"))
                .as("retry acts only on exhausted work, never on work still in flight")
                .isFalse();
    }

    @Test
    void resolvingRecordsAnOperatorOverrideDistinctFromPublished() {
        UUID eventId = deadLetteredOutboxEvent();

        assertThat(operations.resolveOutboxEvent(
                        eventId, FailureCategory.DOMAIN_REJECTED, OPERATOR, "Tenant was deleted before delivery", null))
                .isTrue();

        assertThat(outboxStatus(eventId))
                .as("RESOLVED must stay visible as an override rather than looking like success")
                .isEqualTo("RESOLVED");
        assertThat(jdbc.sql("SELECT published_at FROM integration.outbox_events WHERE event_id = :id")
                        .param("id", eventId)
                        .query(java.time.OffsetDateTime.class)
                        .optional())
                .isEmpty();
    }

    @Test
    void resolvingRequiresAReason() {
        UUID eventId = deadLetteredOutboxEvent();

        assertThatThrownBy(() ->
                        operations.resolveOutboxEvent(eventId, FailureCategory.DOMAIN_REJECTED, OPERATOR, "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void resolvingAnUncertainProviderOutcomeRequiresReconciliationEvidence() {
        UUID eventId = deadLetteredOutboxEvent();

        assertThatThrownBy(() -> operations.resolveOutboxEvent(
                        eventId, FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME, OPERATOR, "looks fine", null))
                .as(
                        "declaring an uncertain provider outcome resolved without evidence is how a duplicate charge is blessed")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconciliation evidence");

        assertThat(operations.resolveOutboxEvent(
                        eventId,
                        FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME,
                        OPERATOR,
                        "Provider confirms no charge",
                        "recon-2026-08-20-17"))
                .isTrue();
    }

    @Test
    void inboxFailuresRetryAndResolveIndependentlyPerConsumer() {
        UUID eventId = deadLetteredInboxMessage(CONSUMER);
        deadLetteredInboxMessage("other-consumer", eventId);

        assertThat(operations.retryInboxMessage(CONSUMER, eventId, OPERATOR, "handler fixed"))
                .isTrue();

        assertThat(inboxStatus(CONSUMER, eventId)).isEqualTo("RETRY_PENDING");
        assertThat(inboxStatus("other-consumer", eventId))
                .as("replaying one consumer must not replay another")
                .isEqualTo("DEAD_LETTER");
    }

    @Test
    void resolvingAnUncertainOutcomeNeedsASecondApproverWhenAPolicySaysSo() {
        insertApprovalPolicy();
        UUID eventId = deadLetteredOutboxEvent();

        assertThatThrownBy(() -> operations.resolveOutboxEvent(
                        eventId,
                        FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME,
                        OPERATOR,
                        "provider says no charge",
                        "recon-2026-08-20-17"))
                .as("an irreversible decision about money needs a second pair of eyes")
                .isInstanceOf(FailureOperationsService.SecondApproverRequiredException.class);

        assertThat(outboxStatus(eventId))
                .as("nothing changes while approval is pending")
                .isEqualTo("DEAD_LETTER");
    }

    /**
     * The request has to outlive the exception that announces it.
     *
     * <p>{@code requireApproval} INSERTs a PENDING row and the resolve then threw
     * {@code SecondApproverRequiredException} to say so. Both ran inside one
     * transaction and the exception is a {@code RuntimeException}, so Spring
     * rolled the INSERT back with it: {@code count(*)} on
     * {@code audit.approval_requests} was zero, no checker ever saw a request,
     * and arming the control on the one category that requires a second approver
     * blocked resolution permanently with nothing for anybody to approve.
     */
    @Test
    void aResolutionAwaitingApprovalLeavesAPendingRequestACheckerCanSee() {
        insertApprovalPolicy();
        UUID eventId = deadLetteredOutboxEvent();

        Throwable refusal = catchThrowable(() -> operations.resolveOutboxEvent(
                eventId, FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME, OPERATOR, RECONCILED, EVIDENCE));

        List<UUID> pending = pendingRequestIds();
        assertThat(pending)
                .as("the request is the whole point of refusing; a rolled-back one asks nobody")
                .hasSize(1);
        assertThat(refusal).isInstanceOf(FailureOperationsService.SecondApproverRequiredException.class);
        assertThat(((FailureOperationsService.SecondApproverRequiredException) refusal).approvalRequestId())
                .as("and the operator is told which request to wait on")
                .isEqualTo(pending.getFirst());
        assertThat(refusal.getMessage()).contains(pending.getFirst().toString());
        assertThat(outboxStatus(eventId))
                .as("nothing changes while approval is pending")
                .isEqualTo("DEAD_LETTER");
    }

    /**
     * A maker who tries again while waiting must not queue up a second signature.
     */
    @Test
    void repeatingAResolutionFindsTheSameRequestRatherThanOpeningASecond() {
        insertApprovalPolicy();
        UUID eventId = deadLetteredOutboxEvent();

        UUID first = requestIdFrom(catchThrowable(() -> operations.resolveOutboxEvent(
                eventId, FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME, OPERATOR, RECONCILED, EVIDENCE)));
        UUID second = requestIdFrom(catchThrowable(() -> operations.resolveOutboxEvent(
                eventId, FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME, OPERATOR, RECONCILED, EVIDENCE)));

        assertThat(second)
                .as("the same action under the same parameters is the same request")
                .isEqualTo(first);
        assertThat(pendingRequestIds())
                .as("two rows would mean a checker approving one and the maker still blocked")
                .containsExactly(first);
    }

    /**
     * The signature is bound to the row the checker read, not to the event id.
     *
     * <p>{@code parametersHash} was {@code sha256(targetId + ":" + category)} on
     * both paths, and an inbox row is keyed by {@code (consumer_name, event_id)}
     * — {@code uq_inbox_consumer_event} — not by the event id. One event reaches
     * several consumers, and the outbound event carries the same id again. So one
     * APPROVED request fitted every inbox row for that event and the outbox row
     * too: a checker who read {@code test-consumer}'s dead letter had, without
     * knowing it, signed for {@code other-consumer}'s copy and for suppressing
     * the outbound event. Single use bounds that at one spend; it does not decide
     * which row gets it.
     */
    @Test
    void anApprovalForOneConsumersDeadLetterResolvesNoOtherRow() {
        insertApprovalPolicy();
        UUID eventId = deadLetteredInboxMessage(CONSUMER);
        deadLetteredInboxMessage(OTHER_CONSUMER, eventId);
        insertOutboxEvent(eventId, "DEAD_LETTER");

        // The maker asks about one consumer's dead letter, and that is the row a
        // checker opens and reconciles.
        Throwable refusal = catchThrowable(() -> operations.resolveInboxMessage(
                CONSUMER, eventId, FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME, OPERATOR, RECONCILED, EVIDENCE));
        assertThat(refusal).isInstanceOf(FailureOperationsService.SecondApproverRequiredException.class);
        UUID requestId = pendingRequestIds().getFirst();
        approvals.decide(
                requestId, ApprovalService.Decision.APPROVE, CHECKER, "Reconciled against the provider statement");

        assertThat(catchThrowable(() -> operations.resolveInboxMessage(
                        OTHER_CONSUMER,
                        eventId,
                        FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME,
                        OPERATOR,
                        RECONCILED,
                        EVIDENCE)))
                .as("a different consumer's copy of the same event is a different decision, "
                        + "and this signature was not given for it")
                .isInstanceOf(FailureOperationsService.SecondApproverRequiredException.class);
        assertThat(inboxStatus(OTHER_CONSUMER, eventId))
                .as("the row the checker never opened is untouched")
                .isEqualTo("DEAD_LETTER");

        assertThat(catchThrowable(() -> operations.resolveOutboxEvent(
                        eventId, FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME, OPERATOR, RECONCILED, EVIDENCE)))
                .as("and suppressing the outbound event carrying the same id is a third " + "decision again")
                .isInstanceOf(FailureOperationsService.SecondApproverRequiredException.class);
        assertThat(outboxStatus(eventId))
                .as("the outbound event is not suppressed by an inbox consumer's signature")
                .isEqualTo("DEAD_LETTER");

        assertThat(operations.resolveInboxMessage(
                        CONSUMER, eventId, FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME, OPERATOR, RECONCILED, EVIDENCE))
                .as("the row the signature was actually given for still resolves")
                .isTrue();
        assertThat(inboxStatus(CONSUMER, eventId)).isEqualTo("RESOLVED");
        assertThat(jdbc.sql("SELECT status FROM audit.approval_requests WHERE id = :id")
                        .param("id", requestId)
                        .query(String.class)
                        .single())
                .as("spent exactly once, by the action it authorised")
                .isEqualTo("CONSUMED");
    }

    private List<UUID> pendingRequestIds() {
        return jdbc.sql("""
                SELECT id FROM audit.approval_requests
                 WHERE status = 'PENDING' ORDER BY requested_at, id
                """).query(UUID.class).list();
    }

    private static UUID requestIdFrom(Throwable refusal) {
        assertThat(refusal).isInstanceOf(FailureOperationsService.SecondApproverRequiredException.class);
        return ((FailureOperationsService.SecondApproverRequiredException) refusal).approvalRequestId();
    }

    @Test
    void anOrdinaryResolutionNeedsNoSecondApproverEvenWhenAPolicyExists() {
        insertApprovalPolicy();
        UUID eventId = deadLetteredOutboxEvent();

        assertThat(operations.resolveOutboxEvent(
                        eventId, FailureCategory.DOMAIN_REJECTED, OPERATOR, "tenant was deleted", null))
                .as("friction belongs where money is, not on every incident action")
                .isTrue();
    }

    @Test
    void retryingNeverNeedsASecondApprover() {
        insertApprovalPolicy();
        UUID eventId = deadLetteredOutboxEvent();

        assertThat(operations.retryOutboxEvent(eventId, OPERATOR, "broker recovered"))
                .as("retrying is safe and repeatable, so gating it would only slow an incident")
                .isTrue();
    }

    @Test
    void classificationDecidesWhichResolutionsNeedASecondApprover() {
        assertThat(FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME.requiresSecondApprover())
                .isTrue();
        assertThat(FailureCategory.DOMAIN_REJECTED.requiresSecondApprover()).isFalse();
        assertThat(FailureCategory.PAYLOAD_INVALID.requiresSecondApprover()).isFalse();
        assertThat(FailureCategory.TRANSIENT_INFRASTRUCTURE.requiresSecondApprover())
                .isFalse();
    }

    @Test
    void classificationDecidesWhatATimerMayRetry() {
        assertThat(FailureCategory.TRANSIENT_INFRASTRUCTURE.retryableByTimer()).isTrue();
        assertThat(FailureCategory.TRANSIENT_PROVIDER.retryableByTimer()).isTrue();
        assertThat(FailureCategory.CONTRACT_UNSUPPORTED.retryableByTimer()).isFalse();
        assertThat(FailureCategory.PAYLOAD_INVALID.retryableByTimer()).isFalse();
        assertThat(FailureCategory.UNCERTAIN_EXTERNAL_OUTCOME.retryableByTimer())
                .as("an uncertain outcome must be reconciled by a person, never retried by a timer")
                .isFalse();
        assertThat(FailureCategory.AUTHORIZATION_REJECTED.isSecurityRelevant()).isTrue();
    }

    private long auditCount(String actionCode) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :code")
                .param("code", actionCode)
                .query(Long.class)
                .single();
    }

    private String outboxStatus(UUID eventId) {
        return jdbc.sql("SELECT status FROM integration.outbox_events WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private String inboxStatus(String consumerName, UUID eventId) {
        return jdbc.sql("""
                SELECT status FROM integration.inbox_messages
                 WHERE consumer_name = :consumer AND event_id = :id
                """)
                .param("consumer", consumerName)
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private UUID deadLetteredOutboxEvent() {
        UUID eventId = UUID.randomUUID();
        insertOutboxEvent(eventId, "DEAD_LETTER");
        return eventId;
    }

    private void insertOutboxEvent(UUID eventId, String status) {
        insertOutboxEvent(eventId, status, TENANT);
    }

    private void insertOutboxEvent(UUID eventId, String status, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO integration.outbox_events (
                    event_id, event_type, event_version, tenant_id, aggregate_type, aggregate_id,
                    topic, partition_key, correlation_id, occurred_at, payload, status,
                    attempt_count, dead_lettered_at, error_code, last_error)
                VALUES (
                    :eventId, 'TenantCreated', 1, :tenantId, 'Tenant', :tenantId,
                    'tenancy.events', :tenantId, 'correlation-1', now(), '{}'::jsonb, :status,
                    10, CASE WHEN :status = 'DEAD_LETTER' THEN now() ELSE NULL END,
                    'TRANSIENT_INFRASTRUCTURE', 'broker unavailable')
                """)
                .param("eventId", eventId)
                .param("tenantId", tenantId)
                .param("status", status)
                .update();
    }

    private UUID deadLetteredInboxMessage(String consumerName) {
        return deadLetteredInboxMessage(consumerName, UUID.randomUUID());
    }

    private UUID deadLetteredInboxMessage(String consumerName, UUID eventId) {
        jdbc.sql("""
                INSERT INTO integration.inbox_messages (
                    id, consumer_name, event_id, topic, partition, record_offset, tenant_id,
                    event_type, event_version, aggregate_type, aggregate_id, correlation_id,
                    occurred_at, payload, payload_sha256, status, attempt_count,
                    dead_lettered_at, last_error_code, last_error)
                VALUES (
                    :id, :consumer, :eventId, 'tenancy.events', 0, :offset, :tenantId,
                    'TenantCreated', 1, 'Tenant', :tenantId, 'correlation-1',
                    now(), '{}'::jsonb, :hash, 'DEAD_LETTER', 10,
                    now(), 'TRANSIENT_INFRASTRUCTURE', 'handler failed')
                """)
                .param("id", UUID.randomUUID())
                .param("consumer", consumerName)
                .param("eventId", eventId)
                .param("offset", Math.abs(consumerName.hashCode() % 1000))
                .param("tenantId", TENANT)
                .param("hash", "a".repeat(64))
                .update();
        return eventId;
    }

    private void insertApprovalPolicy() {
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, 'integration.failure.resolve', 'TENANT',
                        '{"description":"any uncertain provider outcome"}'::jsonb,
                        'integration.failure.resolve', :validFrom, 1, 'platform-admin')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                // Derived from the same fixed clock the service reads, not from
                // database now(). With database time this fixture was valid only
                // when the wall clock's time of day happened to fall before the
                // frozen instant, so the test passed in the morning and failed in
                // the afternoon.
                .param(
                        "validFrom",
                        java.time.OffsetDateTime.ofInstant(
                                clock.instant().minus(java.time.Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-failures', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    /** Inserted only by the tests that need somebody else's row to be invisible. */
    private void insertOtherTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-failures-other', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
    }
}
