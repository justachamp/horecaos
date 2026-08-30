package uz.horecaos.platform.tenancy.application.onboarding;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.tenancy.api.TenancyEvent;
import uz.horecaos.platform.tenancy.api.TenantActivated;
import uz.horecaos.platform.tenancy.api.TenantOnboardingFailed;
import uz.horecaos.platform.tenancy.api.TenantOnboardingStarted;
import uz.horecaos.platform.tenancy.api.TenantOnboardingStepCompleted;
import uz.horecaos.platform.tenancy.api.TenantReady;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

/**
 * ADR 0008.
 *
 * <p>The tests that matter: a blocked step is never reported as success, a
 * failed step resumes without repeating completed external work, and activation
 * cannot bypass readiness or happen twice.
 */
class OnboardingServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121501");
    private static final UUID TEMPLATE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121502");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121503");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121504");
    private static final ActorRef ADMIN = ActorRef.user("platform-admin-1", "Platform Admin");
    private static final ActorRef APPROVER = ActorRef.user("platform-admin-2", "Second Admin");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private OnboardingService service;
    private RecordingProvisioner provisioner;
    private RecordingEvents published;
    private MutableClock clock;

    // Only so that the gauge under test stays strongly reachable while it is
    // read; see stalledAgeSeconds().
    private SimpleMeterRegistry gaugeMeters;
    private OnboardingScheduler gaugeScheduler;

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
        jdbc.sql("TRUNCATE TABLE tenant.onboarding_runs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.onboarding_templates CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        var mapper = JsonMapper.builder().build();
        var recorder = new JdbcAuditRecorder(jdbc, mapper);
        var store = new JdbcTenantControlPlaneStore(jdbc);
        provisioner = new RecordingProvisioner();
        published = new RecordingEvents();

        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new OnboardingService(
                jdbc,
                // A real one, because runNextStep now decides for itself where a
                // transaction starts and stops; a no-op here would test a shape
                // the application never runs.
                transactions,
                List.of(
                        new OnboardingStepHandlers.KeycloakOrganizationReconcile(provisioner, store),
                        new OnboardingStepHandlers.TenantOwnerLinkOrInvite(provisioner),
                        new OnboardingStepHandlers.DefaultConfigurationApply(),
                        new OnboardingStepHandlers.BrandsAndLocationsValidate(store)),
                recorder,
                new JdbcApprovalService(jdbc, recorder, clock, new SimpleMeterRegistry()),
                published,
                mapper,
                clock);

        insertTemplate();
        insertTenant();
    }

    @Test
    void everyStepIsMaterialisedIncludingTheBlockedOnes() {
        UUID runId = startRun();

        var steps = jdbc.sql("""
                SELECT step_key, status FROM tenant.onboarding_steps
                 WHERE run_id = :runId ORDER BY sequence_number
                """).param("runId", runId).query((rs, n) -> rs.getString("step_key") + "=" + rs.getString("status")).list();

        assertThat(steps).hasSize(OnboardingStep.values().length);
        assertThat(steps)
                .as("a template that silently skips a check reads exactly like one that passed it")
                .contains("CATALOG_READINESS_VALIDATE=BLOCKED", "POS_BINDINGS_VALIDATE=BLOCKED");
    }

    @Test
    void aBlockedStepCarriesTheAdrThatWouldUnblockIt() {
        UUID runId = startRun();

        assertThat(jdbc.sql("""
                SELECT last_error FROM tenant.onboarding_steps
                 WHERE run_id = :runId AND step_key = 'CATALOG_READINESS_VALIDATE'
                """).param("runId", runId).query(String.class).single())
                .contains("ADR 0016");
    }

    @Test
    void aCompleteRunReachesReady() {
        UUID runId = startRun();
        drain(runId);

        assertThat(service.outstandingRequiredSteps(runId)).isEmpty();
        assertThat(runStatus(runId)).isEqualTo("READY");
    }

    @Test
    void aTenantWithNoLocationCannotReachReady() {
        jdbc.sql("DELETE FROM tenant.locations WHERE id = :id").param("id", LOCATION).update();
        UUID runId = startRun();
        drain(runId);

        assertThat(service.outstandingRequiredSteps(runId))
                .as("a tenant with no location cannot receive an order and must not activate")
                .contains("BRANDS_AND_LOCATIONS_VALIDATE");
        assertThat(runStatus(runId)).isEqualTo("FAILED");
    }

    @Test
    void activationIsRefusedWhileRequiredStepsAreOutstanding() {
        jdbc.sql("DELETE FROM tenant.locations WHERE id = :id").param("id", LOCATION).update();
        UUID runId = startRun();
        drain(runId);

        var outcome = service.activate(runId, ADMIN, "go live");

        assertThat(outcome.activated()).isFalse();
        assertThat(outcome.outcome()).isEqualTo("READINESS_INCOMPLETE");
        assertThat(outcome.outstandingRequired()).isNotEmpty();
    }

    @Test
    void activationWaitsForPlatformApprovalWhenAPolicyRequiresIt() {
        insertActivationApprovalPolicy();
        UUID runId = startRun();
        drain(runId);

        var outcome = service.activate(runId, ADMIN, "go live");

        assertThat(outcome.activated()).isFalse();
        assertThat(outcome.outcome()).isEqualTo("AWAITING_APPROVAL");
        assertThat(tenantStatus()).isEqualTo("PROVISIONING");
    }

    @Test
    void activationSucceedsOnceApproved() {
        insertActivationApprovalPolicy();
        UUID runId = startRun();
        drain(runId);

        UUID requestId = service.activate(runId, ADMIN, "go live").approvalRequestId();
        new JdbcApprovalService(jdbc, new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                clock, new SimpleMeterRegistry())
                .decide(requestId, uz.horecaos.platform.audit.api.ApprovalService.Decision.APPROVE,
                        APPROVER, "readiness evidence reviewed");

        // Wrapped because this service is constructed rather than proxied here, so
        // its @Transactional does nothing. Activating under an approval spends the
        // approval in the action's transaction, and there has to be one.
        var outcome = transactions.execute(status -> service.activate(runId, ADMIN, "go live"));

        assertThat(outcome.activated()).isTrue();
        assertThat(runStatus(runId)).isEqualTo("ACTIVE");
        assertThat(tenantStatus()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT status FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId).query(String.class).single())
                .as("one signature activates one tenant; the approval is spent by the activation")
                .isEqualTo("CONSUMED");
    }

    @Test
    void activatingTwiceProducesOneTransition() {
        UUID runId = startRun();
        drain(runId);

        assertThat(service.activate(runId, ADMIN, "go live").activated()).isTrue();
        assertThat(service.activate(runId, ADMIN, "go live again").activated())
                .as("a second activation must not produce a second transition")
                .isFalse();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events WHERE action_code = 'tenant.activated'
                """).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aFailedStepResumesWithoutRepeatingCompletedExternalWork() {
        provisioner.failNextMembership = true;
        UUID runId = startRun();
        drain(runId);

        assertThat(runStatus(runId)).isEqualTo("FAILED");
        int organizationCallsBefore = provisioner.organizationCalls;

        provisioner.failNextMembership = false;
        service.resume(runId, ADMIN, "provider recovered");
        drain(runId);

        assertThat(runStatus(runId)).isEqualTo("READY");
        assertThat(provisioner.createdOrganizations)
                .as("a resumed run must reconcile the existing organization, never create a second")
                .isEqualTo(1);
        assertThat(provisioner.organizationCalls)
                .as("the completed organization step is re-read, not re-created")
                .isGreaterThanOrEqualTo(organizationCallsBefore);
    }

    @Test
    void aCompletedStepIsNeverResetByResume() {
        UUID runId = startRun();
        drain(runId);
        var completedAt = stepCompletedAt(runId, "KEYCLOAK_ORGANIZATION_RECONCILE");

        service.resume(runId, ADMIN, "unnecessary resume");

        assertThat(stepCompletedAt(runId, "KEYCLOAK_ORGANIZATION_RECONCILE")).isEqualTo(completedAt);
    }

    @Test
    void theOrganizationIdReachesTheOwnerStep() {
        UUID runId = startRun();
        drain(runId);

        assertThat(provisioner.lastMembershipOrganizationId)
                .as("a step's output must reach later steps' input")
                .isEqualTo("org-" + TENANT);
    }

    @Test
    void startingARunPublishesItsFact() {
        UUID runId = startRun();

        assertThat(published.ofType(TenantOnboardingStarted.class))
                .singleElement()
                .satisfies(started -> {
                    assertThat(started.runId()).isEqualTo(runId);
                    assertThat(started.tenantId().value()).isEqualTo(TENANT);
                    assertThat(started.aggregateId())
                            .as("ADR 0008 partitions onboarding by tenant, so the key is the tenant")
                            .isEqualTo(TENANT);
                });
    }

    @Test
    void everyCompletedStepPublishesExactlyOneFact() {
        UUID runId = startRun();
        drain(runId);

        assertThat(published.ofType(TenantOnboardingStepCompleted.class))
                .extracting(TenantOnboardingStepCompleted::stepKey)
                .containsExactly(
                        "KEYCLOAK_ORGANIZATION_RECONCILE",
                        "TENANT_OWNER_LINK_OR_INVITE",
                        "DEFAULT_CONFIGURATION_APPLY",
                        "BRANDS_AND_LOCATIONS_VALIDATE");
    }

    @Test
    void readyIsPublishedOnceHoweverOftenTheSchedulerLooks() {
        UUID runId = startRun();
        drain(runId);
        drain(runId);

        assertThat(published.ofType(TenantReady.class))
                .as("READY is a transition, not a state the scheduler re-announces on every pass")
                .singleElement()
                .satisfies(ready -> assertThat(ready.runId()).isEqualTo(runId));
    }

    @Test
    void aFailedRunPublishesTheCodeAndNotTheDetail() {
        jdbc.sql("DELETE FROM tenant.locations WHERE id = :id").param("id", LOCATION).update();
        UUID runId = startRun();
        drain(runId);

        assertThat(published.ofType(TenantOnboardingFailed.class))
                .singleElement()
                .satisfies(failure -> {
                    assertThat(failure.stepKey()).isEqualTo("BRANDS_AND_LOCATIONS_VALIDATE");
                    assertThat(failure.errorCode()).isEqualTo("NO_LOCATION");
                });
        assertThat(published.ofType(TenantOnboardingFailed.class).getFirst().payload().toString())
                .as("ADR 0008 forbids a raw error on a topic; only the code travels")
                .doesNotContain("no location", "cannot receive an order");
    }

    @Test
    void activationPublishesOneFactHoweverOftenItIsRequested() {
        UUID runId = startRun();
        drain(runId);

        service.activate(runId, ADMIN, "go live");
        service.activate(runId, ADMIN, "go live again");

        assertThat(published.ofType(TenantActivated.class))
                .singleElement()
                .satisfies(activated -> {
                    assertThat(activated.runId()).isEqualTo(runId);
                    assertThat(activated.status()).isEqualTo("ACTIVE");
                });
    }

    @Test
    void startingARunIsAudited() {
        startRun();

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'tenant.onboarding_started' AND actor_subject = 'platform-admin-1'
                """).query(Long.class).single()).isEqualTo(1L);
    }

    /**
     * ADR 0008: the stalled-run gauge is the input to a probe alert, and its
     * whole value is that it does not fire while a person has yet to decide.
     *
     * <p>Driven through {@link OnboardingScheduler#drive()} rather than
     * {@link #drain(UUID)}, because they stop in different places and only one
     * of them is production. {@code drain} keeps calling
     * {@code runNextStep} until nothing at all is due, so it always reaches
     * {@code TENANT_ACTIVATE} and parks it far in the future. The scheduler
     * stops the moment the run turns {@code READY}, because {@code dueRuns} no
     * longer returns it — and with a batch of four it stops one iteration
     * short of the activation step, leaving it {@code PENDING} and due for as
     * long as the approval takes.
     */
    @Test
    void aRunWaitingForPlatformApprovalIsNeverStalled() {
        UUID runId = startRun();
        OnboardingScheduler scheduler = schedulerWithBatchSize(4);

        assertThat(service.dueRuns(5))
                .as("the scheduler's claim query has to be legal SQL against the real database; "
                        + "PostgreSQL rejects FOR UPDATE with DISTINCT and every tick threw")
                .containsExactly(runId);

        for (int tick = 0; tick < 5; tick++) {
            scheduler.drive();
            clock.advance(java.time.Duration.ofMinutes(1));
        }

        assertThat(runStatus(runId)).isEqualTo("READY");
        assertThat(jdbc.sql("""
                SELECT status FROM tenant.onboarding_steps
                 WHERE run_id = :runId AND step_key = 'TENANT_ACTIVATE'
                """).param("runId", runId).query(String.class).single())
                .as("the activation step is still due; the exclusion cannot rely on it being parked")
                .isEqualTo("PENDING");

        clock.advance(java.time.Duration.ofDays(2));

        assertThat(stalledAgeSeconds())
                .as("waiting for a platform administrator is not the workflow stopping")
                .isZero();
    }

    @Test
    void aRunThatFailedItsRequiredStepIsStalled() {
        jdbc.sql("DELETE FROM tenant.locations WHERE id = :id").param("id", LOCATION).update();
        UUID runId = startRun();
        drain(runId);
        assertThat(runStatus(runId)).isEqualTo("FAILED");

        clock.advance(java.time.Duration.ofHours(2));

        assertThat(stalledAgeSeconds())
                .as("a required step out of attempts is exactly what the alert exists for")
                .isGreaterThanOrEqualTo(7200);
    }

    /**
     * The gauge the ADR 0023 probe reads, under the name the probe reads it by.
     *
     * <p>The scheduler is kept in a field rather than a local because Micrometer
     * holds only a weak reference to the object a gauge reads from; a collected
     * one reports {@code NaN} and the assertion would be about garbage
     * collection rather than about onboarding.
     */
    private double stalledAgeSeconds() {
        gaugeMeters = new SimpleMeterRegistry();
        gaugeScheduler = schedulerWithBatchSize(gaugeMeters, 4);
        double age = gaugeMeters.get("horecaos.onboarding.runs.stalled.age.seconds").gauge().value();
        java.lang.ref.Reference.reachabilityFence(gaugeScheduler);
        return age;
    }

    private OnboardingScheduler schedulerWithBatchSize(int batchSize) {
        return schedulerWithBatchSize(new SimpleMeterRegistry(), batchSize);
    }

    private OnboardingScheduler schedulerWithBatchSize(SimpleMeterRegistry meters, int batchSize) {
        return new OnboardingScheduler(jdbc, service, meters, clock, batchSize);
    }

    private UUID startRun() {
        return service.startRun(TENANT, TEMPLATE, 1,
                Map.of("ownerEmail", "owner@acme.example",
                        "defaultConfiguration", Map.of("locale", "uz")),
                ADMIN);
    }

    /**
     * Runs until nothing is due, advancing the clock so retry backoff elapses.
     * A real scheduler waits for it; a test with a frozen clock would otherwise
     * stop at the first retry and look like a failure that never happened.
     */
    private void drain(UUID runId) {
        for (int guard = 0; guard < 60; guard++) {
            if (!service.runNextStep(runId)) {
                clock.advance(java.time.Duration.ofMinutes(10));
                if (!service.runNextStep(runId)) {
                    return;
                }
            }
        }
    }

    private String runStatus(UUID runId) {
        return jdbc.sql("SELECT status FROM tenant.onboarding_runs WHERE id = :id")
                .param("id", runId).query(String.class).single();
    }

    private String tenantStatus() {
        return jdbc.sql("SELECT status FROM tenant.tenants WHERE id = :id")
                .param("id", TENANT).query(String.class).single();
    }

    private String stepCompletedAt(UUID runId, String stepKey) {
        return jdbc.sql("""
                SELECT completed_at::text FROM tenant.onboarding_steps
                 WHERE run_id = :runId AND step_key = :stepKey
                """).param("runId", runId).param("stepKey", stepKey).query(String.class).single();
    }

    private void insertActivationApprovalPolicy() {
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, 'tenant.activate', 'TENANT',
                        '{"description":"every tenant activation"}'::jsonb,
                        'tenant.write', :from, 1, 'platform-admin')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("from", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertTemplate() {
        jdbc.sql("""
                INSERT INTO tenant.onboarding_templates
                    (id, code, version, status, required_steps, created_by)
                VALUES (:id, 'default', 1, 'ACTIVE', '[]'::jsonb, 'test')
                """).param("id", TEMPLATE).update();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'acme', 'Acme Foods LLC', 'Acme', 'UZS', 'Asia/Tashkent', 'PROVISIONING', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'ACME', 'acme-brand', 'Acme Burgers', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC', 'loc', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("tenantId", TENANT).param("brandId", BRAND).update();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Collects what the service asked Spring to publish.
     *
     * <p>Not a mock: the ADR 0004 path is a transactional application event that
     * {@code TenancyOutboxEventListener} turns into an outbox row, and this
     * records exactly what that listener would receive.
     * {@code OnboardingOutboxIntegrationTests} proves the other half — that the
     * row lands in the same transaction as the run.
     */
    private static final class RecordingEvents implements ApplicationEventPublisher {

        private final List<TenancyEvent> events = new java.util.ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            if (event instanceof TenancyEvent tenancyEvent) {
                events.add(tenancyEvent);
            }
        }

        <T extends TenancyEvent> List<T> ofType(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    /** Records what was asked of it, so re-creation can be distinguished from reconciliation. */
    private static final class RecordingProvisioner implements OrganizationProvisioner {

        private int organizationCalls;
        private int createdOrganizations;
        private String lastMembershipOrganizationId;
        private boolean failNextMembership;

        @Override
        public OrganizationRef ensureOrganization(EnsureOrganization command) {
            organizationCalls++;
            String id = "org-" + command.tenantId();
            if (command.existingOrganizationId() != null) {
                return new OrganizationRef(command.existingOrganizationId(), command.alias(), false);
            }
            createdOrganizations++;
            return new OrganizationRef(id, command.alias(), true);
        }

        @Override
        public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
            return Optional.of(new OrganizationSnapshot(organizationId, "acme", "Acme", true));
        }

        @Override
        public MembershipRef ensureMembership(EnsureMembership command) {
            if (failNextMembership) {
                throw new IllegalStateException("provider unavailable");
            }
            lastMembershipOrganizationId = command.organizationId();
            return new MembershipRef(command.organizationId(), "subject-1", true);
        }
    }
}
