package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0149: the order a product name falls back in is the registry's rank, not a {@code CASE} spelled
 * in four places.
 *
 * <p>Two of the four said {@code WHEN 'ru' ... WHEN 'uz-Latn' ... WHEN 'en'} against a table whose
 * Uzbek rows are stored as the catalog's own {@code uz}. A product named in Uzbek and English was
 * therefore ranked English first, because {@code uz} fell to the {@code ELSE} and {@code en} did not.
 * The other two knew both spellings, so the same product read in Uzbek on one screen and English on
 * the next. This pins the order against a database that holds what the catalog holds.
 */
class CatalogNameFallbackOrderTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-4000-7000-8000-000000000001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-4000-7000-8000-000000000002");

    @Test
    @DisplayName(
            "a product named in uz and en reads in uz, and in ru before either: the catalog's code ranks as uz-Latn")
    void theCatalogsUzRanksWithUzLatn() throws SQLException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        try (TestDatabase.Handle db = TestDatabase.migrated();
                Connection connection = db.dataSource().getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
            }
            JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            JdbcCatalogStore store =
                    new JdbcCatalogStore(jdbc, JsonMapper.builder().build());

            UUID uzAndEn = variantOf(jdbc, "p1", Map.of("en", "Lagman", "uz", "Lag'mon"));
            UUID ruAndUz = variantOf(jdbc, "p2", Map.of("uz", "Lag'mon", "ru", "Лагман"));
            UUID enOnly = variantOf(jdbc, "p3", Map.of("en", "Lagman"));
            UUID nothingKnown = variantOf(jdbc, "p4", Map.of("kk", "Лағман"));

            assertThat(store.productNameFor(TENANT, uzAndEn))
                    .as("uz is the catalog's stored spelling of uz-Latn and outranks en")
                    .contains("Lag'mon");
            assertThat(store.productNameFor(TENANT, ruAndUz)).as("ru first").contains("Лагман");
            assertThat(store.productNameFor(TENANT, enOnly)).contains("Lagman");
            assertThat(store.productNameFor(TENANT, nothingKnown))
                    .as("a name in none of the languages is still shown, not dropped")
                    .contains("Лағман");

            assertThat(store.productNamesFor(TENANT, Set.of(uzAndEn, ruAndUz, enOnly, nothingKnown)))
                    .as("the batch read answers exactly as the single read does")
                    .containsEntry(uzAndEn, "Lag'mon")
                    .containsEntry(ruAndUz, "Лагман")
                    .containsEntry(enOnly, "Lagman")
                    .containsEntry(nothingKnown, "Лағман");
        }
    }

    private static UUID variantOf(JdbcClient jdbc, String code, Map<String, String> names) {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        jdbc.sql("INSERT INTO catalog.products (id, tenant_id, brand_id, code) VALUES (:id, :tenant, :brand, :code)")
                .param("id", product)
                .param("tenant", TENANT)
                .param("brand", BRAND)
                .param("code", code)
                .update();
        jdbc.sql("""
                        INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku)
                        VALUES (:id, :tenant, :brand, :product, :sku)
                        """)
                .param("id", variant)
                .param("tenant", TENANT)
                .param("brand", BRAND)
                .param("product", product)
                .param("sku", code)
                .update();
        names.forEach((locale, name) -> jdbc.sql("""
                        INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                        VALUES (:tenant, :brand, 'PRODUCT', :product, :locale, :name)
                        """)
                .param("tenant", TENANT)
                .param("brand", BRAND)
                .param("product", product)
                .param("locale", locale)
                .param("name", name)
                .update());
        return variant;
    }
}
