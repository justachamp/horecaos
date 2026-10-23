package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * V0683 copies the consent purposes that already exist into their translations table, so the release that drops the three
 * legacy columns has nothing left to backfill (ADR 0149, V0430's precedent).
 *
 * <p>Runs the migration over rows that already exist, which is the only state in which a backfill can be wrong: a fixture
 * migrated from an empty schema has no rows for it to copy and would pass a migration that seeded nothing.
 */
class ConsentTypeTranslationsMigrationTests {

    /** The last migration before V0683. */
    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("0682");

    @Test
    @DisplayName(
            "existing purposes are copied into the translations table, a blank column is skipped, and the columns stop being required")
    void existingPurposesAreBackfilled() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();
            JdbcClient old = JdbcClient.create(dataSource);

            UUID tenant = UUID.randomUUID();
            old.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, 'consent-backfill', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant).update();
            UUID purpose = UUID.randomUUID();
            // label_uz is whitespace: no CHECK ever stopped a hand-written one, and it must be skipped rather than
            // fail ck_consent_type_translation_label.
            old.sql("""
                    INSERT INTO customer.consent_types (id, tenant_id, code, label_ru, label_uz, label_en, created_by, updated_by)
                    VALUES (:id, :t, 'MARKETING_PROMOTIONS', 'Маркетинг', '   ', 'Marketing', 'a-test', 'a-test')
                    """).param("id", purpose).param("t", tenant).update();

            Flyway.configure().dataSource(dataSource).load().migrate();

            Map<String, String> copied = old
                    .sql("""
                            SELECT locale, label FROM customer.consent_type_translations
                             WHERE consent_type_id = :id
                            """)
                    .param("id", purpose)
                    .query((row, number) -> Map.entry(row.getString("locale"), row.getString("label")))
                    .list()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(copied)
                    .as("the blank Uzbek column is skipped, the other two are copied under the platform's own tags")
                    .containsOnly(Map.entry("ru", "Маркетинг"), Map.entry("en", "Marketing"));

            assertThat(old.sql("""
                                    SELECT count(*) FROM information_schema.columns
                                     WHERE table_schema = 'customer' AND table_name = 'consent_types'
                                       AND column_name IN ('label_ru', 'label_uz', 'label_en') AND is_nullable = 'NO'
                                    """).query(Long.class).single())
                    .as("a tenant whose default language is outside the triple has nothing to put in them")
                    .isZero();
        }
    }
}
