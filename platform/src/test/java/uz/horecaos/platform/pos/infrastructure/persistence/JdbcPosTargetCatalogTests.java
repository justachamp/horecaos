package uz.horecaos.platform.pos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.pos.domain.DifferenceEngine.TargetCatalog;
import uz.horecaos.platform.pos.domain.SyncDifference.EntityType;
import uz.horecaos.platform.support.TestDatabase;

/**
 * What HorecaOS currently holds, read back through the join a Clopos product
 * actually resolves through (ADR 0012, ADR 0038 Q15).
 *
 * <p>Before this class existed, {@code products()}'s query selected {@code
 * p.tax_category_code} — a column V0028 dropped a wave before this one, because
 * nothing had ever read it. Nothing caught the break because nothing exercised
 * this query against a real database; {@code make verify} stayed green while
 * every POS sync comparison run would have thrown at the database. The fix
 * points the query at {@code catalog.fiscal_classifications} through the
 * product's default variant, where an accepted MXIK/ИКПУ (Q15) actually lives.
 */
class JdbcPosTargetCatalogTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122004");
    private static final UUID PRODUCT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122005");
    private static final UUID DEFAULT_VARIANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122006");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPosTargetCatalog targetCatalog;

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
        jdbc = JdbcClient.create(db.dataSource());
        targetCatalog = new JdbcPosTargetCatalog(jdbc);

        jdbc.sql("DELETE FROM integration.provider_entity_mappings WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.bindings WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.installations WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM catalog.fiscal_classifications WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM catalog.variants WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM catalog.products WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t").param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'pos-target-catalog', 'Legal', 'POS target catalog', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'TARGET_BRAND', 'target-brand', 'Target brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'POS', 'clopos', 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """).param("id", INSTALLATION).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :t, :brandId, 'OSH', 'ACTIVE')
                """)
                .param("id", PRODUCT)
                .param("t", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :t, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", DEFAULT_VARIANT)
                .param("t", TENANT)
                .param("brandId", BRAND)
                .param("productId", PRODUCT)
                .update();
        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings
                    (id, tenant_id, installation_id, binding_id, entity_type, horecaos_entity_id,
                     external_entity_id, status, mapping_source)
                VALUES (:id, :t, :installationId, :bindingId, 'VARIANT_PARENT', :productId,
                        '41', 'ACTIVE', 'DISCOVERED')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("bindingId", BINDING)
                .param("productId", PRODUCT)
                .update();
    }

    @Test
    @DisplayName("a product's accepted MXIK is read from its default variant's fiscal classification")
    void theProductsMxikCodeIsReadThroughTheDefaultVariant() {
        jdbc.sql("""
                INSERT INTO catalog.fiscal_classifications
                    (id, tenant_id, brand_id, variant_id, mxik_code, source)
                VALUES (:id, :t, :brandId, :variantId, '07131001001000000', 'MANUAL')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("brandId", BRAND)
                .param("variantId", DEFAULT_VARIANT)
                .update();

        TargetCatalog catalog = targetCatalog.read(TENANT, BINDING, BRAND, "ru");
        TargetCatalog.Entity entity = java.util.Objects.requireNonNull(
                catalog.entities(EntityType.PRODUCT).get("41"),
                "keyed on the external id, the only key the two systems share");

        assertThat(entity.fields())
                .as("this used to select a column V0028 dropped, so this query threw against a real "
                        + "database before this fix — no test exercised it, which is how the break "
                        + "went unnoticed")
                .containsEntry("product.mxikCode", "07131001001000000");
    }

    @Test
    @DisplayName("an unclassified default variant leaves product.mxikCode absent, not blank")
    void anUnclassifiedProductHasNoMxikCodeField() {
        TargetCatalog catalog = targetCatalog.read(TENANT, BINDING, BRAND, "ru");
        TargetCatalog.Entity entity = java.util.Objects.requireNonNull(
                catalog.entities(EntityType.PRODUCT).get("41"));

        assertThat(entity.fields())
                .as("absent and blank are one thing in a comparison; a stray empty string would "
                        + "make the difference engine propose overwriting nothing with nothing")
                .doesNotContainKey("product.mxikCode");
    }
}
