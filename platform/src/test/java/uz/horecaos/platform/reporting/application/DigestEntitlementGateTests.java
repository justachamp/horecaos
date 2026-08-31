package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.application.EnforcementCeiling;
import uz.horecaos.platform.commercial.application.EntitlementQueryService;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.integration.api.provider.ProviderHealth;
import uz.horecaos.platform.integration.api.provider.ProviderHealthQuery;
import uz.horecaos.platform.notifications.api.DigestFanout;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory.ScopedBinding;
import uz.horecaos.platform.ordering.api.OrderCounts;
import uz.horecaos.platform.ordering.api.OrderCountsQuery;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.OnboardingHealth;
import uz.horecaos.platform.tenancy.api.OnboardingHealthQuery;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;

/**
 * {@link DigestScheduler} is ADR 0021's first real caller of {@link
 * EntitlementService}: proves the gate itself, isolated from the rest of the
 * pipeline. The 15-minute digest is the vehicle because it is the one cadence
 * that needs no closed business day first — {@link OrderCountsQuery} and
 * {@link ReportQueryService#activeTenantIds()} are the only reads on its path
 * — which keeps this test about the gate and nothing else.
 */
class DigestEntitlementGateTests {

    private static final UUID ENTITLED_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000d001");
    private static final UUID UNENTITLED_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000d002");
    private static final UUID ENTITLED_BINDING = UUID.fromString("018f6f4e-3000-7000-8000-00000000d011");
    private static final UUID UNENTITLED_BINDING = UUID.fromString("018f6f4e-3000-7000-8000-00000000d012");
    private static final UUID BRAND = UUID.fromString("018f6f4e-3000-7000-8000-00000000d021");

    private static final String AUTHOR = "digest-gate.author";
    private static final String APPROVER = "digest-gate.approver";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private SimpleMeterRegistry meters;
    private RecordingFanout fanout;
    private EntitlementService entitlements;
    private ReportQueryService reportQueries;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE commercial.subscriptions, commercial.plan_entitlements,"
                        + " commercial.plan_versions, commercial.plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenant(ENTITLED_TENANT, "digest-gate-entitled");
        seedTenant(UNENTITLED_TENANT, "digest-gate-unentitled");

        Clock clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);

        JdbcPlanStore planStore = new JdbcPlanStore(jdbc);
        JdbcSubscriptionStore subscriptionStore = new JdbcSubscriptionStore(jdbc);
        JdbcUsageStore usageStore =
                new JdbcUsageStore(jdbc, JsonMapper.builder().build());
        EnforcementCeiling ceiling = new EnforcementCeiling(new JdbcConfigurationResolver(jdbc));
        EntitlementQueryService entitlementService =
                new EntitlementQueryService(subscriptionStore, planStore, usageStore, ceiling, clock);
        entitlements = entitlementService;

        AuditRecorder audit = new RecordingAuditRecorder();
        PlanCatalogService plans = new PlanCatalogService(planStore, audit, clock);
        SubscriptionService subscriptions =
                new SubscriptionService(subscriptionStore, planStore, entitlementService, audit, clock);

        // Only the entitled tenant is put on a plan granting the key. The
        // unentitled tenant has no subscription at all, which is the state
        // every tenant is in before onboarding assigns one — the catalogue's
        // own safeDefault(FALSE) is what should hold for it here.
        UUID planId = plans.createPlan("DIGESTS_PILOT", "Digests pilot", ActorRef.user(AUTHOR, null), "pilot", "corr");
        UUID versionId = plans.draftVersion(
                planId,
                "UZS",
                0,
                "NONE",
                null,
                Map.of(
                        EntitlementKeys.TELEGRAM_DIGESTS_ENABLED.code(),
                        PlanEntitlement.feature(
                                EntitlementKeys.TELEGRAM_DIGESTS_ENABLED.code(), true, EnforcementMode.METER_ONLY)),
                ActorRef.user(AUTHOR, null),
                "pilot plan",
                "corr");
        plans.activate(versionId, ActorRef.user(APPROVER, null), "signed off", "corr");
        subscriptions.start(ENTITLED_TENANT, versionId, null, ActorRef.user(AUTHOR, null), "pilot onboarding", "corr");

        JdbcReportingStore store = new JdbcReportingStore(jdbc);
        reportQueries = new ReportQueryService(store, new BusinessDayService(store), clock);

        meters = new SimpleMeterRegistry();
        fanout = new RecordingFanout();
    }

    @Test
    void anEntitledTenantReceivesAndAnUnentitledTenantIsSilentlySkippedWithAMetric() {
        DigestScheduler scheduler = new DigestScheduler(
                new FakeSubscriptions(),
                fanout,
                new FakeLiveCounts(),
                reportQueries,
                new ThrowingOnboardingHealth(),
                new ThrowingProviderHealth(),
                entitlements,
                Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC),
                meters,
                Duration.ofMinutes(20),
                Duration.ofHours(13),
                Duration.ofDays(2));

        scheduler.emitFifteenMinuteDigests();

        assertThat(fanout.sentBindingIds())
                .as("only the entitled tenant's chat receives a digest")
                .containsExactly(ENTITLED_BINDING);

        assertThat(meters.get("horecaos.notifications.digest.entitlement_denied")
                        .tag("kind", "15m")
                        .counter()
                        .count())
                .as("the unentitled tenant's skip is a metric, never an exception")
                .isEqualTo(1.0);
    }

    private void seedTenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    private static UUID bindingFor(UUID tenantId) {
        return tenantId.equals(ENTITLED_TENANT) ? ENTITLED_BINDING : UNENTITLED_BINDING;
    }

    /** Both tenants have exactly one 15-minute-subscribed chat; the gate is the only difference. */
    private static final class FakeSubscriptions implements OperationsSubscriptionDirectory {

        @Override
        public List<UUID> subscribedBindings(UUID tenantId, UUID brandId, UUID locationId, String eventClass) {
            throw new UnsupportedOperationException("Not used by the 15-minute digest path");
        }

        @Override
        public List<ScopedBinding> tenantDigestBindings(UUID tenantId, String eventClass) {
            if (!DigestScheduler.DIGEST_15M.equals(eventClass)) {
                return List.of();
            }
            return List.of(new ScopedBinding(tenantId, bindingFor(tenantId), BRAND, null));
        }

        @Override
        public List<ScopedBinding> platformDigestBindings(String eventClass) {
            return List.of();
        }
    }

    private static final class FakeLiveCounts implements OrderCountsQuery {
        @Override
        public OrderCounts liveCounts(UUID tenantId, UUID brandId, @Nullable UUID locationId) {
            return new OrderCounts(1, 0, 0, 0, 0, 0, 0, 1, 1);
        }
    }

    private static final class RecordingFanout implements DigestFanout {
        private final List<UUID> sentBindingIds = new ArrayList<>();

        @Override
        public void send(
                List<ScopedBinding> bindings,
                String templateKey,
                UUID subjectId,
                String idempotencyKeyBase,
                Map<String, String> variables,
                Duration expiry) {
            bindings.forEach(binding -> sentBindingIds.add(binding.bindingId()));
        }

        List<UUID> sentBindingIds() {
            return sentBindingIds;
        }
    }

    private static final class ThrowingOnboardingHealth implements OnboardingHealthQuery {
        @Override
        public OnboardingHealth onboardingHealth() {
            throw new UnsupportedOperationException("The 15-minute digest path must never read onboarding health");
        }
    }

    private static final class ThrowingProviderHealth implements ProviderHealthQuery {
        @Override
        public ProviderHealth providerHealth() {
            throw new UnsupportedOperationException("The 15-minute digest path must never read provider health");
        }
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {
        @Override
        public void record(AuditFact fact) {
            // Not asserted on here; CommercialPlatformTests owns audit coverage.
        }
    }
}
