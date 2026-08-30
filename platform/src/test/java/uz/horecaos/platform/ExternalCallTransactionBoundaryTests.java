package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.media.application.MediaAssetService;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcDerivativeJobStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;
import uz.horecaos.platform.payments.application.CapturedMoneyPort;
import uz.horecaos.platform.payments.application.PaymentAttemptService;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.application.PaymentProviderPort;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.ProviderInvoice;
import uz.horecaos.platform.payments.domain.ProviderOutcome;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentTransactionStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingService;

/**
 * No pooled database connection is held across a call to something we do not
 * control.
 *
 * <p>One test class for three modules, because it is one property and it is
 * architectural rather than local. The pool is ten connections wide and shared by
 * every module: a media finalize waiting on a degraded MinIO, an onboarding step
 * waiting on Keycloak, and a checkout waiting on Click each used to hold one for
 * the whole of that wait, so ten slow calls to any <em>one</em> of those three
 * stalled all of the others — ordering, tenancy, reporting and the rest included.
 *
 * <p>These run through a real Spring context on purpose. The property under test
 * is what {@code @Transactional} does when the proxy is in place, and every other
 * test in these modules constructs its service with {@code new}, where the
 * annotation is inert and this would pass whatever the code said.
 */
class ExternalCallTransactionBoundaryTests {

    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1215a1");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1215a2");
    private static final UUID TEMPLATE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1215a3");
    private static final ActorRef ADMIN = ActorRef.user("platform-admin-1", "Platform Admin");

    private static TestDatabase.Handle db;
    private static DriverManagerDataSource dataSource;

    private AnnotationConfigApplicationContext context;
    private JdbcClient jdbc;

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
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.onboarding_runs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.onboarding_templates CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        seedTenantAndTemplate();

        TestConfiguration.dataSource = dataSource;
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    @DisplayName("finalizing an upload asks the object store with no connection checked out")
    void mediaFinalizeDoesNotHoldAConnection() {
        WatchfulStorage storage = context.getBean(WatchfulStorage.class);
        MediaAssetService media = context.getBean(MediaAssetService.class);

        var ticket = media.requestUpload(
                TENANT, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC, "image/jpeg", 1024, "burger.jpg", null);
        media.finalizeUpload(TENANT, ticket.assetId());

        assertThat(storage.headCalls).isEqualTo(1);
        assertThat(storage.insideTransaction)
                .as("head() is a blocking round-trip to MinIO; a transaction around it "
                        + "holds one of ten pooled connections for its whole duration")
                .isFalse();
    }

    @Test
    @DisplayName("an onboarding step calls Keycloak with no connection checked out, and its claim is committed")
    void onboardingStepDoesNotHoldAConnection() {
        WatchfulHandler handler = context.getBean(WatchfulHandler.class);
        OnboardingService onboarding = context.getBean(OnboardingService.class);

        UUID runId = onboarding.startRun(TENANT, TEMPLATE, 1, Map.of("ownerEmail", "owner@acme.example"), ADMIN);
        onboarding.runNextStep(runId);

        assertThat(handler.calls).isEqualTo(1);
        assertThat(handler.insideTransaction)
                .as("a step handler holds the step's row lock as well as the connection")
                .isFalse();
        // The other half of the same change: the claim is durable before the
        // handler runs, which is what makes the lease and the attempt count mean
        // anything. A handler that saw its own RUNNING row would also have been
        // inside the claim's transaction.
        assertThat(handler.observedStatus).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("presenting a payment calls the provider with no connection checked out")
    void paymentPresentationDoesNotHoldAConnection() {
        WatchfulProvider provider = context.getBean(WatchfulProvider.class);
        PaymentAttemptService attempts = context.getBean(PaymentAttemptService.class);

        attempts.present(anAttempt(), aBinding(), PresentationRequest.link());

        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.insideTransaction)
                .as("createInvoice is an HTTP call to Click or Payme; ten checkouts during "
                        + "an outage would otherwise own every connection the platform has")
                .isFalse();
    }

    private static PaymentAttempt anAttempt() {
        // Synthetic rather than seeded: every write present() makes is a
        // conditional UPDATE that matches nothing, and what is under test is
        // where the transaction starts, not what it wrote.
        return new PaymentAttempt(
                UUID.randomUUID(),
                TENANT,
                UUID.randomUUID(),
                PaymentProviderType.CLICK,
                UUID.randomUUID(),
                "0123456789abcdef0123456789abcdef",
                LocalDate.parse("2026-08-24"),
                null,
                null,
                SomAmount.of(45_000, "UZS"),
                PaymentAttemptStatus.INITIATED,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                NOW,
                null);
    }

    private static ProviderBinding aBinding() {
        return new ProviderBinding(
                UUID.randomUUID(),
                TENANT,
                UUID.randomUUID(),
                PaymentProviderType.CLICK,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "service-1",
                "user-1",
                "merchant-1",
                SecretReference.parse("horecaos:test:provider_payment:tenant:click-binding"),
                "test-binding",
                true,
                false,
                LocalDate.parse("2026-01-01"),
                null);
    }

    private void seedTenantAndTemplate() {
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
                INSERT INTO tenant.onboarding_templates
                    (id, code, version, status, required_steps, created_by)
                VALUES (:id, 'default', 1, 'ACTIVE', '[]'::jsonb, 'test')
                """).param("id", TEMPLATE).update();
    }

    // proxyTargetClass, because Spring Boot sets it and this context has to match
    // what the application actually runs. Left at the default, MediaAssetService
    // would be proxied through MediaAvailability alone and this class would be
    // testing a bean shape production never has.
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {

        private static DataSource dataSource;

        @Bean
        DataSource dataSource() {
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean
        JdbcClient jdbcClient(DataSource source) {
            return JdbcClient.create(source);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        WatchfulStorage watchfulStorage() {
            return new WatchfulStorage();
        }

        @Bean
        MediaAssetService mediaAssetService(
                JdbcClient client,
                WatchfulStorage storage,
                TransactionTemplate transactions,
                ApplicationEventPublisher events,
                Clock clock) {
            return new MediaAssetService(
                    new JdbcMediaAssetStore(client),
                    new JdbcDerivativeJobStore(client),
                    storage,
                    transactions,
                    events,
                    clock,
                    "test-bucket");
        }

        @Bean
        WatchfulHandler watchfulHandler(DataSource source) {
            return new WatchfulHandler(source);
        }

        @Bean
        AuditRecorder auditRecorder(JdbcClient client, ObjectMapper mapper) {
            return new JdbcAuditRecorder(client, mapper);
        }

        @Bean
        ApprovalService approvalService(JdbcClient client, AuditRecorder recorder, Clock clock) {
            return new JdbcApprovalService(client, recorder, clock, new SimpleMeterRegistry());
        }

        @Bean
        OnboardingService onboardingService(
                JdbcClient client,
                TransactionTemplate transactions,
                WatchfulHandler handler,
                AuditRecorder recorder,
                ApprovalService approvals,
                ApplicationEventPublisher events,
                ObjectMapper mapper,
                Clock clock) {
            return new OnboardingService(
                    client, transactions, List.of(handler), recorder, approvals, events, mapper, clock);
        }

        @Bean
        WatchfulProvider watchfulProvider() {
            return new WatchfulProvider();
        }

        @Bean
        PaymentAttemptService paymentAttemptService(
                JdbcClient client, TransactionTemplate transactions, WatchfulProvider provider, Clock clock) {
            return new PaymentAttemptService(
                    new JdbcPaymentIntentStore(client),
                    new JdbcPaymentAttemptStore(client),
                    new JdbcPaymentTransactionStore(client),
                    new NoBindings(),
                    List.of(provider),
                    CapturedMoneyPort.NONE,
                    transactions,
                    event -> {},
                    clock);
        }
    }

    /** Answers a head with a plausible object, and remembers whether it was called inside a transaction. */
    static final class WatchfulStorage implements ObjectStorage {

        private int headCalls;
        private boolean insideTransaction;

        @Override
        public PresignedUpload presignUpload(
                String bucket, String key, String contentType, long sizeBytes, java.time.Duration window) {
            return new PresignedUpload(
                    java.net.URI.create("http://example.invalid/" + key),
                    Map.of("Content-Type", contentType),
                    NOW.plus(window));
        }

        @Override
        public java.net.URI presignDownload(String bucket, String key, java.time.Duration window) {
            return java.net.URI.create("http://example.invalid/" + key);
        }

        @Override
        public Optional<StoredObject> head(String bucket, String key) {
            headCalls++;
            insideTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            return Optional.of(new StoredObject(1024, "image/jpeg", Optional.empty(), "etag-1"));
        }

        @Override
        public void delete(String bucket, String key) {
            // Nothing under test reaches here.
        }
    }

    /**
     * Stands in for the Keycloak organization step, and reads its own row on a
     * connection of its own so the claim's visibility can be asserted.
     */
    static final class WatchfulHandler implements OnboardingStepHandler {

        private final DataSource dataSource;
        private int calls;
        private boolean insideTransaction;
        private String observedStatus;

        WatchfulHandler(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.KEYCLOAK_ORGANIZATION_RECONCILE;
        }

        @Override
        public StepResult execute(StepContext context) {
            calls++;
            insideTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            observedStatus = JdbcClient.create(dataSource)
                    .sql("""
                            SELECT status FROM tenant.onboarding_steps
                             WHERE run_id = :runId AND step_key = 'KEYCLOAK_ORGANIZATION_RECONCILE'
                            """)
                    .param("runId", context.runId())
                    .query(String.class)
                    .single();
            return StepResult.completed(Map.of("organizationId", "org-" + context.tenantId()), null);
        }
    }

    /** Stands in for the Click adapter. */
    static final class WatchfulProvider implements PaymentProviderPort {

        private int calls;
        private boolean insideTransaction;

        @Override
        public PaymentProviderType providerType() {
            return PaymentProviderType.CLICK;
        }

        @Override
        public ProviderInvoice createInvoice(
                PaymentAttempt attempt, ProviderBinding binding, PresentationRequest request) {
            calls++;
            insideTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            return ProviderInvoice.link("https://my.click.uz/pay/test", NOW.plusSeconds(900));
        }

        @Override
        public ProviderOutcome queryOutcome(PaymentAttempt attempt, ProviderBinding binding) {
            throw new UnsupportedOperationException("Not reached by these tests");
        }

        @Override
        public ProviderOutcome reverse(PaymentAttempt attempt, ProviderBinding binding, String reason) {
            throw new UnsupportedOperationException("Not reached by these tests");
        }
    }

    /** No binding is ever resolved here; present() is given one directly. */
    static final class NoBindings implements PaymentBindingResolver {

        @Override
        public Optional<ProviderBinding> resolve(
                UUID tenantId, UUID legalEntityId, PaymentProviderType providerType, LocalDate businessDate) {
            return Optional.empty();
        }

        @Override
        public Optional<ProviderBinding> byCallbackSegment(String callbackPathSegment) {
            return Optional.empty();
        }
    }
}
