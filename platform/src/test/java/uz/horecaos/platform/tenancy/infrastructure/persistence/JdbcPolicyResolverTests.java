package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * ADR 0030 exit criterion: a business fact that referenced a policy resolves to
 * the same policy after that policy changes.
 */
class JdbcPolicyResolverTests {

    /** A small stand-in document; the mechanism is what is under test. */
    public record ApprovalPolicy(int timeoutSeconds, String timeoutAction) {}

    private static final PolicyKey<ApprovalPolicy> KEY = new PolicyKey<>(
            "ordering.acceptance",
            ApprovalPolicy.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "ordering",
            false,
            "Order acceptance policy");

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120501");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120502");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120503");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcPolicyResolver resolver;

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
        jdbc.sql("TRUNCATE TABLE tenant.policy_current CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        resolver = new JdbcPolicyResolver(jdbc, JsonMapper.builder().build());
        insertHierarchy();
    }

    @Test
    void resolvesTheMostSpecificActivePolicy() {
        activate(insertPolicy(ScopeType.TENANT, TENANT, null, null, 1, 600, "AUTO_REJECT", HASH_A));
        activate(insertPolicy(ScopeType.BRAND, TENANT, BRAND, null, 1, 300, "AUTO_CONFIRM", HASH_B));

        ResolvedPolicy<ApprovalPolicy> resolved =
                resolver.resolve(KEY, locationScope()).orElseThrow();

        assertThat(resolved.winningScope()).isEqualTo(ScopeType.BRAND);
        assertThat(resolved.document().timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void resolvesNothingWhenNoPolicyIsActive() {
        assertThat(resolver.resolve(KEY, locationScope())).isEmpty();
    }

    @Test
    void aPinnedVersionSurvivesALaterActivation() {
        UUID versionOne = insertPolicy(ScopeType.TENANT, TENANT, null, null, 1, 600, "AUTO_REJECT", HASH_A);
        activate(versionOne);

        ResolvedPolicy<ApprovalPolicy> atDecisionTime =
                resolver.resolve(KEY, locationScope()).orElseThrow();
        assertThat(atDecisionTime.document().timeoutSeconds()).isEqualTo(600);

        // The tenant later changes the policy.
        UUID versionTwo = insertPolicy(ScopeType.TENANT, TENANT, null, null, 2, 90, "AUTO_CONFIRM", HASH_B);
        jdbc.sql("UPDATE tenant.policies SET status = 'RETIRED' WHERE id = :id")
                .param("id", versionOne)
                .update();
        jdbc.sql("DELETE FROM tenant.policy_current WHERE policy_id = :id")
                .param("id", versionOne)
                .update();
        activate(versionTwo);

        assertThat(resolver.resolve(KEY, locationScope())
                        .orElseThrow()
                        .document()
                        .timeoutSeconds())
                .as("new decisions use the new policy")
                .isEqualTo(90);

        ResolvedPolicy<ApprovalPolicy> replayed = resolver.pinned(
                        KEY, atDecisionTime.policyId(), atDecisionTime.policyVersion())
                .orElseThrow();

        assertThat(replayed.document().timeoutSeconds())
                .as("the historical decision still resolves under the policy that applied to it")
                .isEqualTo(600);
        assertThat(replayed.document().timeoutAction()).isEqualTo("AUTO_REJECT");
    }

    @Test
    void aRetiredPolicyRemainsPinnable() {
        UUID policyId = insertPolicy(ScopeType.TENANT, TENANT, null, null, 1, 600, "AUTO_REJECT", HASH_A);
        jdbc.sql("UPDATE tenant.policies SET status = 'RETIRED' WHERE id = :id")
                .param("id", policyId)
                .update();

        assertThat(resolver.pinned(KEY, policyId, 1))
                .as("evidence must stay readable after a policy is retired")
                .isPresent();
    }

    @Test
    void anotherTenantsPolicyIsNeverResolved() {
        UUID otherTenant = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120601");
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-other-policy', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", otherTenant).update();
        activate(insertPolicy(ScopeType.TENANT, otherTenant, null, null, 1, 42, "AUTO_REJECT", HASH_A));

        assertThat(resolver.resolve(KEY, locationScope())).isEmpty();
    }

    private ResourceScope locationScope() {
        return ResourceScope.location(TENANT, BRAND, LOCATION);
    }

    private UUID insertPolicy(
            ScopeType scopeType,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            int version,
            int timeoutSeconds,
            String timeoutAction,
            String hash) {

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.policies
                    (id, key_code, scope_type, tenant_id, brand_id, location_id, version, status,
                     document, document_hash, valid_from, created_by)
                VALUES (:id, :keyCode, :scopeType, :tenantId, :brandId, :locationId, :version, 'ACTIVE',
                        CAST(:document AS jsonb), :hash, now(), 'test')
                """)
                .param("id", id)
                .param("keyCode", KEY.code())
                .param("scopeType", scopeType.name())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("version", version)
                .param("document", """
                        {"timeoutSeconds":%d,"timeoutAction":"%s"}""".formatted(timeoutSeconds, timeoutAction))
                .param("hash", hash)
                .update();
        return id;
    }

    private void activate(UUID policyId) {
        jdbc.sql("""
                INSERT INTO tenant.policy_current
                    (key_code, scope_type, tenant_id, brand_id, location_id,
                     policy_id, policy_version, activated_by)
                SELECT key_code, scope_type, tenant_id, brand_id, location_id, id, version, 'test'
                  FROM tenant.policies WHERE id = :id
                """).param("id", policyId).update();
    }

    private void insertHierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-policy', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND_P', 'brand-p', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC_P', 'loc-p', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }
}
