package uz.qoida.platform.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;

import uz.qoida.platform.support.TestDatabase;

/**
 * V0082 preserves a policy row's former broad behavior instead of inventing a
 * brand or location it never identified.
 */
class ApprovalPolicyScopeMigrationTests {

    private static final String ACTION = "payments.remedy.record";
    private static final OffsetDateTime AUTHORED_AT =
            OffsetDateTime.of(2026, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC);

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for migration tests");
    }

    @Test
    void legacyScopedRowsRemainExplicitWideFallbacksAndNewRowsMustNameAResource() {
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource)
                    .target(MigrationVersion.fromVersion("0081"))
                    .load().migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);

            UUID tenantId = UUID.randomUUID();
            insertTenant(jdbc, tenantId);
            UUID oldBrandPolicy = insertLegacyPolicy(jdbc, tenantId, "BRAND", 1);
            UUID oldLocationPolicy = insertLegacyPolicy(jdbc, tenantId, "LOCATION", 1);

            Flyway.configure().dataSource(dataSource).load().migrate();

            assertThat(legacyScope(jdbc, oldBrandPolicy))
                    .as("V0082 must not pretend this old row named a particular brand")
                    .isEqualTo("true:null:null");
            assertThat(legacyScope(jdbc, oldLocationPolicy))
                    .as("the old location label likewise had no location to preserve")
                    .isEqualTo("true:null:null");

            assertThatThrownBy(() -> jdbc.sql("""
                    INSERT INTO audit.approval_policies
                        (id, tenant_id, action_code, scope_type, threshold_json,
                         required_approver_capability, valid_from, version, approved_by)
                    VALUES (:id, :tenantId, :actionCode, 'BRAND',
                            jsonb_build_object('description', 'Unscoped new brand policy'),
                            'refund.approve', :validFrom, 2, 'owner')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("actionCode", ACTION)
                    .param("validFrom", AUTHORED_AT)
                    .update())
                    .isInstanceOf(DataIntegrityViolationException.class);

            UUID brandId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :tenantId, 'MIGRATION', 'migration', 'Migration', 'ACTIVE', 0)
                    """).param("id", brandId).param("tenantId", tenantId).update();
            assertThat(jdbc.sql("""
                    INSERT INTO audit.approval_policies
                        (id, tenant_id, brand_id, action_code, scope_type, threshold_json,
                         required_approver_capability, valid_from, version, approved_by)
                    VALUES (:id, :tenantId, :brandId, :actionCode, 'BRAND',
                            jsonb_build_object('description', 'Exact new brand policy'),
                            'refund.approve', :validFrom, 1, 'owner')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("actionCode", ACTION)
                    .param("validFrom", AUTHORED_AT)
                    .update())
                    .as("an exact row can coexist with the legacy fallback at the same level")
                    .isEqualTo(1);
        }
    }

    private static UUID insertLegacyPolicy(JdbcClient jdbc, UUID tenantId, String scopeType, int version) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, :actionCode, :scopeType,
                        jsonb_build_object('description', 'Legacy broad policy'),
                        'refund.approve', :validFrom, :version, 'owner')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("actionCode", ACTION)
                .param("scopeType", scopeType)
                .param("validFrom", AUTHORED_AT)
                .param("version", version)
                .update();
        return id;
    }

    private static String legacyScope(JdbcClient jdbc, UUID policyId) {
        return jdbc.sql("""
                SELECT legacy_scope_wide::text || ':' || coalesce(brand_id::text, 'null')
                       || ':' || coalesce(location_id::text, 'null')
                  FROM audit.approval_policies WHERE id = :id
                """).param("id", policyId).query(String.class).single();
    }

    private static void insertTenant(JdbcClient jdbc, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'migration-policy', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
    }
}
