package uz.horecaos.platform.iam.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.mfa.MfaRequirementMode;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Requirement;
import uz.horecaos.platform.iam.application.mfa.MfaPolicy;
import uz.horecaos.platform.iam.application.mfa.StaffAccountFacts;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.AppRoleProbe;
import uz.horecaos.platform.support.TestDatabase;

/**
 * What the second-factor policy reads about a company-made job (ADR 0148, ADR 0186), as the
 * application role.
 *
 * <p>{@code iam.roles} has row-level security (V0693), and a table's owner is exempt from its own
 * policies, so a probe from the migrator's connection would prove nothing: this runs as a login
 * role holding only {@code horecaos_application}, the shape the production pool has. The unbound
 * halves are what make the bound halves evidence. An unbound session sees no job at all, which is
 * precisely the answer that, read as "carries nothing sensitive", would let a holder of a job
 * with {@code refund.approve} sign in without the factor their tenant asked for.
 */
class JdbcStaffAccountFactsTests {

    private static final String PROBE_ROLE = "iam_staff_account_facts_probe";

    private static final UUID TENANT_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac151201");
    private static final UUID TENANT_B = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1512f1");

    private static final String FINANCIER = "job-aaaaaaaaaa";
    private static final String CASHIER = "job-bbbbbbbbbb";
    private static final String EMPTY = "job-cccccccccc";
    private static final String OTHERS = "job-dddddddddd";

    private static final String FINANCIER_HOLDER = "financier-1";
    private static final String CASHIER_HOLDER = "cashier-1";

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private static TestDatabase.Handle db;
    private static JdbcClient owner;
    private static AppRoleProbe app;

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the row-level security probes");
        db = TestDatabase.migrated();
        owner = JdbcClient.create(db.dataSource());
        app = AppRoleProbe.open(db, PROBE_ROLE);

        new RoleRegistrySynchronizer(owner).synchronize();
        for (Object[] tenant : new Object[][] {{TENANT_A, "facts-a"}, {TENANT_B, "facts-b"}}) {
            owner.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant[0]).param("slug", tenant[1]).update();
        }
        UUID financier = job(TENANT_A, FINANCIER, "order.read", "refund.approve");
        UUID cashier = job(TENANT_A, CASHIER, "order.read", "order.cancel");
        job(TENANT_A, EMPTY);
        job(TENANT_B, OTHERS, "order.read");
        grant(FINANCIER_HOLDER, financier);
        grant(CASHIER_HOLDER, cashier);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static StaffAccountFacts bound() {
        return app.transactional(new JdbcStaffAccountFacts(app.application, clock(), app.rls));
    }

    private static StaffAccountFacts unbound() {
        return app.transactional(new JdbcStaffAccountFacts(app.application, clock()));
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    @DisplayName(
            "a bound read answers what each of the tenant's own jobs carries, and a job with nothing as an empty set")
    void aBoundReadAnswersWhatTheJobsCarry() {
        Map<String, Set<String>> carried = bound().tenantJobCapabilities(TENANT_A, Set.of(FINANCIER, CASHIER, EMPTY));

        assertThat(carried)
                .containsOnlyKeys(FINANCIER, CASHIER, EMPTY)
                .containsEntry(FINANCIER, Set.of("order.read", "refund.approve"))
                .containsEntry(CASHIER, Set.of("order.read", "order.cancel"))
                .containsEntry(EMPTY, Set.of());
    }

    @Test
    @DisplayName("another tenant's job, a code-owned one and a code nobody defined are all absent from the answer")
    void onlyTheTenantsOwnJobsAreAnswered() {
        assertThat(bound().tenantJobCapabilities(TENANT_A, Set.of(OTHERS, "tenant-owner", "job-zzzzzzzzzz", CASHIER)))
                .containsOnlyKeys(CASHIER);
        assertThat(bound().tenantJobCapabilities(TENANT_B, Set.of(FINANCIER)))
                .as("bound to B, A's job is not even visible")
                .isEmpty();
        assertThat(bound().tenantJobCapabilities(TENANT_A, Set.of())).isEmpty();
    }

    @Test
    @DisplayName(
            "unbound, the same read sees no job at all, which is why the policy treats a missing answer as sensitive")
    void anUnboundReadSeesNothing() {
        assertThat(unbound().tenantJobCapabilities(TENANT_A, Set.of(FINANCIER, CASHIER)))
                .as("the failure the binding exists to prevent, and the half that makes the bound test evidence")
                .isEmpty();
    }

    @Test
    @DisplayName("the policy, over the real reads as the application role: the financier is asked, the cashier is not")
    void thePolicyOverTheRealReads() {
        MfaPolicy policy = policy(bound());

        assertThat(rolesOf(FINANCIER_HOLDER)).containsEntry(TENANT_A, Set.of(FINANCIER));
        assertThat(policy.requirementFor(FINANCIER_HOLDER, false))
                .as("job-aaaaaaaaaa carries refund.approve, which only a sensitive job carries")
                .isEqualTo(Requirement.REQUIRED);
        assertThat(policy.requirementFor(CASHIER_HOLDER, false))
                .as("job-bbbbbbbbbb carries order.read and order.cancel, which a branch manager has too")
                .isEqualTo(Requirement.NOT_REQUIRED);
    }

    @Test
    @DisplayName("the policy over a read that comes back short: the holder is asked rather than let through")
    void aShortReadAsksForTheFactor() {
        MfaPolicy policy = policy(new DelegatingFacts(bound()) {
            @Override
            public Map<String, Set<String>> tenantJobCapabilities(UUID tenantId, Set<String> roleCodes) {
                return unbound().tenantJobCapabilities(tenantId, roleCodes);
            }
        });

        assertThat(policy.requirementFor(CASHIER_HOLDER, false))
                .as("an ordinary job read short is the same as an unknown one: asked")
                .isEqualTo(Requirement.REQUIRED);
    }

    private static Map<UUID, Set<String>> rolesOf(String subject) {
        return bound().rolesByTenant(subject);
    }

    private static MfaPolicy policy(StaffAccountFacts facts) {
        return new MfaPolicy(facts, tenantId -> MfaRequirementMode.SENSITIVE_ROLES, "OFF");
    }

    /** Everything but the one read a test replaces. */
    private abstract static class DelegatingFacts implements StaffAccountFacts {
        private final StaffAccountFacts delegate;

        DelegatingFacts(StaffAccountFacts delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean holdsPlatformGrant(String subject) {
            return delegate.holdsPlatformGrant(subject);
        }

        @Override
        public Map<UUID, Set<String>> rolesByTenant(String subject) {
            return delegate.rolesByTenant(subject);
        }

        @Override
        public boolean holdsRoleInTenant(String subject, UUID tenantId, String roleCode) {
            return delegate.holdsRoleInTenant(subject, tenantId, roleCode);
        }

        @Override
        public java.util.Optional<String> uiLocale(String subject) {
            return delegate.uiLocale(subject);
        }
    }

    private static UUID job(UUID tenantId, String code, String... capabilities) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO iam.roles
                    (id, tenant_id, code, name, scope_type, status, is_platform_defined, version, created_by, updated_by)
                VALUES (:id, :tenantId, :code, :name, 'LOCATION', 'ACTIVE', false, 1, 'fixture', 'fixture')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("name", code)
                .update();
        for (String capability : List.of(capabilities)) {
            owner.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:id, :capability)")
                    .param("id", id)
                    .param("capability", capability)
                    .update();
        }
        return id;
    }

    private static void grant(String subject, UUID roleId) {
        owner.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, false, 'TENANT', :tenantId,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT_A)
                .param("subject", subject)
                .param("roleId", roleId)
                .param("validFrom", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }
}
