package uz.horecaos.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0177 decision 5, status addition of 2026-10-23: {@code ordering.preorder_branch_resolution} is
 * settable at platform, tenant and brand and no longer at a branch, because the resolver reads it for the
 * whole brand and a value at a branch was read by nothing.
 *
 * <p>Until that change the key took a value at any scope, so a database where a manager once set it on a
 * branch holds a row the resolver ignores. The configuration writer now refuses that scope, which would
 * also leave the page showing the row as «Set at brand level» with no way to clear it. The migration that
 * ships with the change removes those rows. The migration has already run when the database comes up,
 * against an empty table, so this puts the rows back and runs the very file Flyway ran. What would still
 * pass if the delete were too wide: nothing here, because the brand and tenant rows of the same key and
 * the branch row of a key that is read at a branch are kept for exactly that.
 */
class PreOrderBranchRuleStoredAtABranchMigrationTests {

    private static final String KEY = "ordering.preorder_branch_resolution";

    /** Read at a branch (the order-policy page edits it there), so its branch row is a live setting. */
    private static final String READ_AT_A_BRANCH = "ordering.operator_promo_code_allowed";

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private JdbcClient jdbc;

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
        jdbc.sql("DELETE FROM tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'preorder-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'MAIN01', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    @Test
    @DisplayName("the migration removes the branch rows of the pre-order rule and nothing else")
    void theMigrationRemovesTheBranchRowsOfTheKeyOnly() throws IOException {
        store(KEY, "PLATFORM", null, null, null, "BY_DISTANCE");
        store(KEY, "TENANT", TENANT, null, null, "BY_DISTANCE");
        store(KEY, "BRAND", TENANT, BRAND, null, "BY_OPENING_TIME");
        store(KEY, "LOCATION", TENANT, BRAND, LOCATION, "BY_OPENING_TIME");
        store(READ_AT_A_BRANCH, "LOCATION", TENANT, BRAND, LOCATION, "false");

        jdbc.sql(migrationSql()).update();

        assertThat(stored())
                .as("the rule keeps its platform, tenant and brand values, and a key read at a branch keeps its own")
                .containsExactlyInAnyOrder(
                        KEY + " PLATFORM", KEY + " TENANT", KEY + " BRAND", READ_AT_A_BRANCH + " LOCATION");
    }

    @Test
    @DisplayName("the migration is a no-op on a database where nobody set the rule at a branch")
    void theMigrationIsHarmlessWhereNothingWasSaved() throws IOException {
        store(KEY, "BRAND", TENANT, BRAND, null, "BY_DISTANCE");

        jdbc.sql(migrationSql()).update();

        assertThat(stored()).containsExactly(KEY + " BRAND");
    }

    private List<String> stored() {
        return jdbc.sql("SELECT key_code || ' ' || scope_type FROM tenant.configuration_values")
                .query(String.class)
                .list();
    }

    private void store(
            String code,
            String scope,
            @Nullable UUID tenant,
            @Nullable UUID brand,
            @Nullable UUID location,
            String value) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id, brand_id,
                    location_id, value_type, string_value, set_by)
                VALUES (:id, :code, :scope, :tenant, :brand, :location, 'STRING', :value, 'migration-test')
                """)
                .param("id", UUID.randomUUID())
                .param("code", code)
                .param("scope", scope)
                .param("tenant", tenant)
                .param("brand", brand)
                .param("location", location)
                .param("value", value)
                .update();
    }

    private static String migrationSql() throws IOException {
        var resource =
                new ClassPathResource("db/migration/V0654__the_pre_order_branch_rule_is_not_stored_at_a_branch.sql");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
