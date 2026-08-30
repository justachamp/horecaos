package uz.qoida.platform.tenancy.application.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.qoida.platform.iam.api.organizations.OrganizationDirectory;
import uz.qoida.platform.iam.api.organizations.OrganizationProvisioner.OrganizationSnapshot;
import uz.qoida.platform.tenancy.application.identity.IdentityDriftReporter.DriftCode;
import uz.qoida.platform.tenancy.application.identity.IdentityDriftReporter.DriftFinding;
import uz.qoida.platform.tenancy.infrastructure.persistence.JdbcTenantOrganizationLinkStore;
import uz.qoida.platform.support.TestDatabase;

/**
 * ADR 0009 drift reporting.
 *
 * <p>The tenant table is real, because the comparison this class makes is
 * between that table and a realm, and a stubbed link store would remove one of
 * the two things being compared. Keycloak is stubbed here on purpose — what is
 * under test is the classification and the tenant scoping of a finding, not the
 * Admin API, which {@code KeycloakOrganizationIntegrationTests} exercises for
 * real.
 */
class IdentityDriftReporterTests {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final UUID TENANT_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121701");
    private static final UUID TENANT_B = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121702");

    private static TestDatabase.Handle db;
    private static DriverManagerDataSource dataSource;

    private JdbcClient jdbc;
    private FakeDirectory keycloak;
    private SimpleMeterRegistry meters;
    private IdentityDriftReporter reporter;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        dataSource = new DriverManagerDataSource(
                db.jdbcUrl(), db.username(), db.password());
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        keycloak = new FakeDirectory();
        meters = new SimpleMeterRegistry();
        reporter = new IdentityDriftReporter(
                new JdbcTenantOrganizationLinkStore(jdbc),
                keycloak,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                meters,
                Clock.fixed(NOW, ZoneOffset.UTC),
                500);
    }

    @Test
    void anEstateThatAgreesWithTheRealmProducesNoFinding() {
        insertTenant(TENANT_A, "acme", "ACTIVE", "org-a");
        keycloak.holds("org-a", "acme", true);

        assertThat(reporter.scan().findings()).isEmpty();
        assertThat(meters.find("qoida.iam.identity.drift").gauge().value()).isZero();
    }

    @Test
    void aStoredOrganizationThatNoLongerExistsIsReportedAndNotRepaired() {
        insertTenant(TENANT_A, "acme", "ACTIVE", "org-a");

        var report = reporter.scan();

        assertThat(report.findings())
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.tenantId()).isEqualTo(TENANT_A);
                    assertThat(finding.code()).isEqualTo(DriftCode.ORGANIZATION_MISSING);
                });
        assertThat(keycloak.writes)
                .as("ADR 0009 rejected automatic correction: a report never writes")
                .isZero();
    }

    @Test
    void anOrganizationDisabledUnderALiveTenantIsReported() {
        insertTenant(TENANT_A, "acme", "ACTIVE", "org-a");
        keycloak.holds("org-a", "acme", false);

        assertThat(reporter.scan().findings())
                .extracting(DriftFinding::code)
                .containsExactly(DriftCode.ORGANIZATION_DISABLED);
    }

    @Test
    void aSuspendedTenantWithADisabledOrganizationIsNotDrift() {
        insertTenant(TENANT_A, "acme", "SUSPENDED", "org-a");
        keycloak.holds("org-a", "acme", false);

        assertThat(reporter.scan().findings())
                .as("suspension is supposed to disable access; reporting it would be noise")
                .isEmpty();
    }

    @Test
    void anAliasThatNoLongerMatchesTheTenantIsReported() {
        insertTenant(TENANT_A, "acme", "ACTIVE", "org-a");
        keycloak.holds("org-a", "something-else", true);

        assertThat(reporter.scan().findings())
                .extracting(DriftFinding::code)
                .containsExactly(DriftCode.ORGANIZATION_ALIAS_MISMATCH);
    }

    @Test
    void aProvisioningTenantWithNoOrganizationIsNotYetDrift() {
        insertTenant(TENANT_A, "acme", "PROVISIONING", null);

        assertThat(reporter.scan().findings())
                .as("onboarding has not reached the organization step; every new tenant "
                        + "would otherwise be a finding for as long as it takes")
                .isEmpty();
    }

    @Test
    void anActiveTenantWithNoOrganizationIsDrift() {
        insertTenant(TENANT_A, "acme", "ACTIVE", null);

        assertThat(reporter.scan().findings())
                .extracting(DriftFinding::code)
                .containsExactly(DriftCode.ORGANIZATION_UNLINKED);
    }

    /**
     * The scoping rule, and the reason this test exists at all: organization
     * membership is how the platform decides which tenant a person belongs to,
     * so a finding that named the wrong tenant would send an operator to correct
     * identity data in a business that has nothing wrong with it.
     */
    @Test
    void aFindingNamesOnlyTheTenantItBelongsTo() {
        insertTenant(TENANT_A, "acme", "ACTIVE", "org-a");
        insertTenant(TENANT_B, "beta", "ACTIVE", "org-b");
        keycloak.holds("org-b", "beta", true);

        var report = reporter.scan();

        assertThat(report.checked()).isEqualTo(2);
        assertThat(report.findings())
                .extracting(DriftFinding::tenantId)
                .containsExactly(TENANT_A);
        assertThat(auditedTenantScopes())
                .as("the audit fact is scoped to the tenant that is actually wrong")
                .containsExactly(TENANT_A);
    }

    @Test
    void oneTenantsOrganizationIsNeverAcceptedAsEvidenceForAnother() {
        insertTenant(TENANT_A, "acme", "ACTIVE", "org-a");
        insertTenant(TENANT_B, "beta", "ACTIVE", "org-b");
        // Only B's organization exists, under B's alias. A must not be judged
        // healthy because some organization in the realm happens to be fine.
        keycloak.holds("org-b", "beta", true);

        assertThat(reporter.scan().findings())
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.tenantId()).isEqualTo(TENANT_A);
                    assertThat(finding.detail())
                            .contains("org-a")
                            .doesNotContain("org-b");
                });
    }

    @Test
    void aKeycloakThatCannotBeAskedIsNotReportedAsDrift() {
        insertTenant(TENANT_A, "acme", "ACTIVE", "org-a");
        insertTenant(TENANT_B, "beta", "ACTIVE", "org-b");
        keycloak.unreachable = true;

        var report = reporter.scan();

        assertThat(report.findings())
                .as("an unreachable realm would otherwise raise a finding for every tenant at once")
                .isEmpty();
        assertThat(report.unreachable()).isEqualTo(2);
        assertThat(meters.find("qoida.iam.identity.drift.scans").tag("outcome", "partial")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void everyFindingIsAuditedWithItsCodeAndNoKeycloakProse() {
        insertTenant(TENANT_A, "acme", "ACTIVE", "org-a");

        reporter.scan();

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'iam.identity_drift_detected' AND audit_class = 'SECURITY'
                """).query(Long.class).single()).isEqualTo(1L);
        assertThat(meters.find("qoida.iam.identity.drift.detected")
                .tag("code", DriftCode.ORGANIZATION_MISSING.name())
                .counter().count()).isEqualTo(1);
    }

    /**
     * A report that has stopped running reports no drift, which looks exactly
     * like a healthy estate until somebody notices the number has not moved.
     */
    @Test
    void theReportPublishesItsOwnAgeSoASilentReportIsVisible() {
        assertThat(meters.find("qoida.iam.identity.drift.report.age.seconds").gauge().value())
                .as("negative until the first pass completes, so an absent report is not zero")
                .isNegative();

        reporter.scan();

        assertThat(meters.find("qoida.iam.identity.drift.report.age.seconds").gauge().value())
                .isZero();
    }

    @Test
    void anArchivedTenantIsNotReconciled() {
        insertTenant(TENANT_A, "acme", "ARCHIVED", "org-a");

        assertThat(reporter.scan().checked())
                .as("ADR 0009 preserves an archived tenant's organization rather than deleting it")
                .isZero();
    }

    private List<UUID> auditedTenantScopes() {
        return jdbc.sql("""
                SELECT tenant_id FROM audit.audit_events
                 WHERE action_code = 'iam.identity_drift_detected'
                """).query(UUID.class).list();
    }

    private void insertTenant(UUID id, String slug, String status, String organizationId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     status, version, keycloak_organization_id, created_at)
                VALUES (:id, :slug, :slug, :slug, 'UZS', 'Asia/Tashkent', :status, 0, :organizationId, :now)
                """)
                .param("id", id)
                .param("slug", slug)
                .param("status", status)
                .param("organizationId", organizationId)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Keycloak as the reporter sees it, with a switch for "cannot ask". */
    private static final class FakeDirectory implements OrganizationDirectory {

        private final Map<String, OrganizationSnapshot> organizations = new HashMap<>();
        private boolean unreachable;
        private int writes;

        void holds(String organizationId, String alias, boolean enabled) {
            organizations.put(organizationId,
                    new OrganizationSnapshot(organizationId, alias, alias, enabled));
        }

        @Override
        public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
            if (unreachable) {
                throw new IllegalStateException("Keycloak is not answering");
            }
            return Optional.ofNullable(organizations.get(organizationId));
        }

        @Override
        public List<OrganizationSnapshot> findByAlias(String alias) {
            if (unreachable) {
                throw new IllegalStateException("Keycloak is not answering");
            }
            return organizations.values().stream()
                    .filter(organization -> alias.equals(organization.alias()))
                    .toList();
        }
    }
}
