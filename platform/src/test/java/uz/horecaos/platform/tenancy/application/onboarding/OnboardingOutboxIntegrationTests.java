package uz.horecaos.platform.tenancy.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.grants.TenantOwnerAuthorityGrantor;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.integration.outbox.JdbcOutboxStore;
import uz.horecaos.platform.integration.outbox.TenancyOutboxEventListener;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

/**
 * ADR 0008 and ADR 0004: an onboarding fact reaches Kafka through the outbox,
 * and the outbox row is written in the same transaction as the state change.
 *
 * <p>The unit tests next door prove which facts are produced. What can only be
 * proved here is that they are produced *atomically* — a run that rolls back
 * must not leave a fact behind claiming it started, and a step that committed
 * must not lose the fact that says so. That needs a real transaction manager, a
 * real transactional event listener, and a real database, which is why this test
 * builds a container context rather than calling the service directly.
 */
class OnboardingOutboxIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121601");
    private static final UUID TEMPLATE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121602");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121603");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121604");
    private static final ActorRef ADMIN = ActorRef.user("platform-admin-1", "Platform Admin");

    private static TestDatabase.Handle db;
    private static DriverManagerDataSource dataSource;

    private AnnotationConfigApplicationContext context;
    private JdbcClient jdbc;
    private OnboardingService service;
    private TransactionTemplate transactions;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.onboarding_runs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.onboarding_templates CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        TestConfiguration.dataSource = dataSource;
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        service = context.getBean(OnboardingService.class);
        transactions = context.getBean(TransactionTemplate.class);

        insertTemplate();
        insertTenant();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void theStartFactIsWrittenToTheOutboxAndNotToKafka() {
        UUID runId = service.startRun(TENANT, TEMPLATE, 1, Map.of("ownerEmail", "owner@acme.example"), ADMIN);

        assertThat(outboxRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("eventType", "TenantOnboardingStarted");
            assertThat(row).containsEntry("topic", "tenancy.events");
            assertThat(row).containsEntry("tenantId", TENANT);
            assertThat(row)
                    .as("ADR 0008 partitions onboarding by tenant")
                    .containsEntry("partitionKey", TENANT.toString());
            assertThat(row).containsEntry("status", "PENDING");
            assertThat(row).containsEntry("runId", runId.toString());
        });
    }

    @Test
    void aRunThatRollsBackLeavesNoFactClaimingItStarted() {
        transactions.execute(status -> {
            service.startRun(TENANT, TEMPLATE, 1, Map.of(), ADMIN);
            status.setRollbackOnly();
            return null;
        });

        assertThat(jdbc.sql("SELECT count(*) FROM tenant.onboarding_runs")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM integration.outbox_events")
                        .query(Long.class)
                        .single())
                .as("ADR 0004: the fact and the state change are one write, so neither survives alone")
                .isZero();
    }

    @Test
    void everyTransitionOfACompleteRunReachesTheOutbox() {
        UUID runId = service.startRun(TENANT, TEMPLATE, 1, Map.of("ownerEmail", "owner@acme.example"), ADMIN);
        drain(runId);
        service.activate(runId, ADMIN, "go live");

        // In any order rather than in sequence: `now()` in PostgreSQL is the
        // transaction's start time, so the last step's completion and the READY
        // it produces share a created_at and cannot be ordered by it. The unit
        // test next door asserts the sequence; what matters here is that every
        // fact is durable — one per completed step (all eleven buildable ones,
        // as of 2026-08-30), plus start, ready and activated.
        assertThat(outboxRows())
                .extracting(row -> row.get("eventType"))
                .containsExactlyInAnyOrder(
                        "TenantOnboardingStarted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantOnboardingStepCompleted",
                        "TenantReady",
                        "TenantActivated");
    }

    @Test
    void noOnboardingFactCarriesTheOwnerEmail() {
        UUID runId = service.startRun(TENANT, TEMPLATE, 1, Map.of("ownerEmail", "owner@acme.example"), ADMIN);
        drain(runId);

        assertThat(jdbc.sql("SELECT payload::text FROM integration.outbox_events")
                        .query(String.class)
                        .list())
                .as("ADR 0029: an owner's email address is personal data and never reaches a topic")
                .noneMatch(payload -> payload.contains("owner@acme.example"));
    }

    private void drain(UUID runId) {
        for (int guard = 0; guard < 20 && service.runNextStep(runId); guard++) {
            // Each call advances at most one step.
        }
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbc.sql("""
                SELECT event_type, topic, tenant_id, partition_key, status, payload->>'runId' AS run_id
                  FROM integration.outbox_events
                 ORDER BY created_at
                """)
                .query((rs, n) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("eventType", rs.getString("event_type"));
                    row.put("topic", rs.getString("topic"));
                    row.put("tenantId", rs.getObject("tenant_id"));
                    row.put("partitionKey", rs.getString("partition_key"));
                    row.put("status", rs.getString("status"));
                    row.put("runId", rs.getString("run_id"));
                    return row;
                })
                .list();
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
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {

        private static DataSource dataSource;

        @Bean
        DataSource dataSource() {
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource configuredDataSource) {
            return JdbcClient.create(configuredDataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource configuredDataSource) {
            return new DataSourceTransactionManager(configuredDataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean
        JdbcOutboxStore jdbcOutboxStore(JdbcClient jdbc) {
            return new JdbcOutboxStore(jdbc);
        }

        @Bean
        TenancyOutboxEventListener tenancyOutboxEventListener(JdbcOutboxStore outbox, ObjectMapper objectMapper) {
            return new TenancyOutboxEventListener(outbox, objectMapper, "tenancy.events");
        }

        @Bean
        AuditRecorder auditRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
            return new JdbcAuditRecorder(jdbc, objectMapper);
        }

        @Bean
        ApprovalService approvalService(JdbcClient jdbc, AuditRecorder recorder, Clock clock) {
            return new JdbcApprovalService(jdbc, recorder, clock, new SimpleMeterRegistry());
        }

        @Bean
        uz.horecaos.platform.tenancy.api.PolicyAuthor policyAuthor(
                JdbcClient jdbc, ObjectMapper objectMapper, AuditRecorder recorder, Clock clock) {
            return new uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyAuthor(
                    jdbc, objectMapper, recorder, clock);
        }

        @Bean
        TenantControlPlaneStore tenantControlPlaneStore(JdbcClient jdbc) {
            return new JdbcTenantControlPlaneStore(jdbc);
        }

        /** Stands in for Keycloak; ADR 0009's own adapter is tested against a real one. */
        @Bean
        OrganizationProvisioner organizationProvisioner() {
            return new OrganizationProvisioner() {

                @Override
                public OrganizationRef ensureOrganization(EnsureOrganization command) {
                    return new OrganizationRef("org-" + command.tenantId(), command.alias(), true);
                }

                @Override
                public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
                    return Optional.of(new OrganizationSnapshot(organizationId, "acme", "Acme", true));
                }

                @Override
                public MembershipRef ensureMembership(EnsureMembership command) {
                    return new MembershipRef(command.organizationId(), "subject-1", true);
                }
            };
        }

        /** A no-op: this file proves outbox atomicity, not ADR 0025 grant plumbing. */
        @Bean
        TenantOwnerAuthorityGrantor tenantOwnerAuthorityGrantor() {
            return (tenantId, subjectId, reason) -> {};
        }

        /**
         * The four real handlers, plus a trivial always-completes fake for each
         * of the seven newly-required steps this file's fixture (one tenant,
         * one brand, one location) sets up no data for. This file's subject is
         * the outbox's atomicity, not the seven steps' own business rules,
         * which are proved next to {@code OnboardingStepHandlers} itself.
         */
        @Bean
        List<OnboardingStepHandler> onboardingStepHandlers(
                OrganizationProvisioner organizations,
                TenantOwnerAuthorityGrantor authority,
                TenantControlPlaneStore tenants,
                JdbcClient jdbc,
                uz.horecaos.platform.tenancy.api.PolicyAuthor policyAuthor) {
            List<OnboardingStepHandler> handlers = new java.util.ArrayList<>(List.of(
                    new OnboardingStepHandlers.KeycloakOrganizationReconcile(organizations, tenants),
                    new OnboardingStepHandlers.TenantOwnerLinkOrInvite(organizations, authority),
                    new OnboardingStepHandlers.DefaultConfigurationApply(jdbc, policyAuthor),
                    new OnboardingStepHandlers.BrandsAndLocationsValidate(tenants)));
            for (OnboardingStep step : new OnboardingStep[] {
                OnboardingStep.PAYMENT_CONFIGURATION_VALIDATE,
                OnboardingStep.DELIVERY_CONFIGURATION_VALIDATE,
                OnboardingStep.POS_BINDINGS_VALIDATE,
                OnboardingStep.CATALOG_READINESS_VALIDATE,
                OnboardingStep.MEDIA_READINESS_VALIDATE,
                OnboardingStep.FRONTEND_DOMAIN_VALIDATE,
                OnboardingStep.ACTIVATION_SMOKE_TEST
            }) {
                handlers.add(new OnboardingStepHandler() {
                    @Override
                    public OnboardingStep step() {
                        return step;
                    }

                    @Override
                    public StepResult execute(StepContext context) {
                        return StepResult.completed(Map.of(), null);
                    }
                });
            }
            return handlers;
        }

        @Bean
        OnboardingService onboardingService(
                JdbcClient jdbc,
                TransactionTemplate transactions,
                List<OnboardingStepHandler> handlers,
                AuditRecorder recorder,
                ApprovalService approvals,
                org.springframework.context.ApplicationEventPublisher events,
                ObjectMapper objectMapper,
                Clock clock) {
            return new OnboardingService(
                    jdbc, transactions, handlers, recorder, approvals, events, objectMapper, clock);
        }
    }
}
