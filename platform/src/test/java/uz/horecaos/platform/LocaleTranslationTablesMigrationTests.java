package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * V0430 and V0431 seed the per-locale translations tables from the columns they sit
 * beside (row 10.12's one-release transition), so the release that drops the columns
 * has nothing left to backfill.
 *
 * <p>Runs the migrations over rows that already exist, which is the only state in
 * which a backfill can be wrong: a fixture migrated from an empty schema has no rows
 * for it to copy and would pass a migration that seeded nothing.
 */
class LocaleTranslationTablesMigrationTests {

    /** The last migration before V0430. */
    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("0428");

    @Test
    @DisplayName("existing presets, tenant regions and zones are copied into their translations tables")
    void existingRowsAreBackfilled() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();
            JdbcClient old = JdbcClient.create(dataSource);

            UUID tenant = UUID.randomUUID();
            UUID brand = UUID.randomUUID();
            old.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, 'backfill', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant).update();
            old.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                    """).param("id", brand).param("tenantId", tenant).update();

            UUID preset = UUID.randomUUID();
            // label_uz is whitespace: no CHECK ever stopped a hand-written one, and it
            // must be skipped rather than fail ck_comment_preset_translation_label.
            old.sql("""
                    INSERT INTO catalog.comment_presets
                        (id, tenant_id, code, label_ru, label_uz, label_en, sort_order, status, version)
                    VALUES (:id, :tenantId, 'NO_ONION', 'Без лука', '   ', 'No onion', 0, 'ACTIVE', 1)
                    """).param("id", preset).param("tenantId", tenant).update();

            UUID tenantRegion = UUID.randomUUID();
            UUID platformRegion = UUID.randomUUID();
            for (Object[] region : new Object[][] {
                {tenantRegion, tenant, "TASH", "Ташкент", "Toshkent", "Tashkent"},
                {platformRegion, null, "UZB", "Узбекистан", "Oʻzbekiston", "Uzbekistan"}
            }) {
                old.sql("""
                        INSERT INTO fulfillment.regions (id, tenant_id, code, display_name_ru, display_name_uz,
                            display_name_en, centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon)
                        VALUES (:id, :tenantId, :code, :ru, :uz, :en, 41.3, 69.2, 37, 56, 45.6, 73.2)
                        """)
                        .param("id", region[0])
                        .param("tenantId", region[1])
                        .param("code", region[2])
                        .param("ru", region[3])
                        .param("uz", region[4])
                        .param("en", region[5])
                        .update();
            }

            UUID zone = UUID.randomUUID();
            old.sql("""
                    INSERT INTO fulfillment.service_zones
                        (id, tenant_id, brand_id, zone_role, code, display_name_ru, display_name_uz, display_name_en)
                    VALUES (:id, :tenantId, :brandId, 'DELIVERY', 'CENTRE', 'Центр', 'Markaz', 'Centre')
                    """)
                    .param("id", zone)
                    .param("tenantId", tenant)
                    .param("brandId", brand)
                    .update();

            Flyway.configure().dataSource(dataSource).load().migrate();

            assertThat(rows(
                            old,
                            "SELECT locale, label FROM catalog.comment_preset_translations WHERE preset_id = :id",
                            preset))
                    .as("the blank uz-Latn column is skipped, the other two are copied")
                    .containsOnly(Map.entry("ru", "Без лука"), Map.entry("en", "No onion"));
            assertThat(rows(
                            old,
                            "SELECT locale, display_name FROM fulfillment.region_translations WHERE region_id = :id",
                            tenantRegion))
                    .containsOnly(
                            Map.entry("ru", "Ташкент"), Map.entry("uz-Latn", "Toshkent"), Map.entry("en", "Tashkent"));
            assertThat(rows(
                            old,
                            "SELECT locale, display_name FROM fulfillment.region_translations WHERE region_id = :id",
                            platformRegion))
                    .as("a platform region has no tenant to own a translation row")
                    .isEmpty();
            assertThat(rows(
                            old,
                            "SELECT locale, display_name FROM fulfillment.service_zone_translations WHERE zone_id = :id",
                            zone))
                    .containsOnly(Map.entry("ru", "Центр"), Map.entry("uz-Latn", "Markaz"), Map.entry("en", "Centre"));
        }
    }

    private static Map<String, String> rows(JdbcClient jdbc, String sql, UUID id) {
        Map<String, String> rows = new LinkedHashMap<>();
        jdbc.sql(sql)
                .param("id", id)
                .query((rs, n) -> rows.put(rs.getString(1), rs.getString(2)))
                .list();
        return rows;
    }
}
