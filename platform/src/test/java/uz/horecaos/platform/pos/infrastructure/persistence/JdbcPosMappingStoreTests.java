package uz.horecaos.platform.pos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.integration.api.provider.MappingEntityType;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore.ExternalCandidate;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore.MappingRow;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore.NamedCandidate;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The tenant-facing mapping pane's store (ADR 0012/0026, gap-map row 10.8b).
 *
 * <p>{@code mapping_source = 'OPERATOR'} has been legal under V0013's {@code
 * ck_mapping_source} CHECK since that migration; {@link #createWritesAnOperatorSourcedMapping}
 * is the first thing in this codebase to actually write it.
 */
class JdbcPosMappingStoreTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac125001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac125002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac125003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac125004");

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPosMappingStore store;

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
        store = new JdbcPosMappingStore(jdbc);

        jdbc.sql("DELETE FROM integration.provider_entity_mappings WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM payments.payment_methods WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.pos_sync_runs WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.bindings WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.installations WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t").param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'pos-mapping-store', 'Legal', 'POS mapping store', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAPPING_BRAND', 'mapping-brand', 'Mapping brand', 'ACTIVE', 0)
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
    }

    @Test
    @DisplayName("create writes an OPERATOR-sourced, ACTIVE mapping -- allowed by V0013's CHECK, never written before")
    void createWritesAnOperatorSourcedMapping() {
        UUID horecaosId = Ids.newId();

        UUID mappingId = store.create(
                TENANT, INSTALLATION, BINDING, MappingEntityType.COURIER, horecaosId, "ext-courier-1", null, NOW);

        MappingRow row = store.find(TENANT, mappingId).orElseThrow();
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.mappingSource()).isEqualTo("OPERATOR");
        assertThat(row.entityType()).isEqualTo("COURIER");
        assertThat(row.horecaosEntityId()).isEqualTo(horecaosId);
        assertThat(row.externalEntityId()).isEqualTo("ext-courier-1");
        assertThat(row.version()).isZero();
    }

    @Test
    @DisplayName("a second ACTIVE mapping cannot claim an external id or a HorecaOS id another mapping already holds")
    void uniqueConstraintsRefuseADoubleClaim() {
        UUID firstHorecaos = Ids.newId();
        store.create(TENANT, INSTALLATION, BINDING, MappingEntityType.COURIER, firstHorecaos, "ext-shared", null, NOW);

        assertThat(store.findActiveConflict(TENANT, BINDING, MappingEntityType.COURIER, Ids.newId(), "ext-shared"))
                .as("the external id is already actively claimed")
                .isPresent();
        assertThat(store.findActiveConflict(TENANT, BINDING, MappingEntityType.COURIER, firstHorecaos, "ext-other"))
                .as("the HorecaOS id is already actively claimed")
                .isPresent();

        // The database's own constraint (V0013's uq_mapping_external), not just
        // the pre-check above: a bug in findActiveConflict's own query must not
        // be able to open a second claim on the same external id.
        assertThatThrownBy(() -> store.create(
                        TENANT, INSTALLATION, BINDING, MappingEntityType.COURIER, Ids.newId(), "ext-shared", null, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("retire is version-checked and terminal")
    void retireIsVersionCheckedAndTerminal() {
        UUID mappingId = store.create(
                TENANT, INSTALLATION, BINDING, MappingEntityType.DISCOUNT, Ids.newId(), "ext-d1", null, NOW);

        assertThat(store.retire(TENANT, mappingId, 5, NOW))
                .as("a stale expected version is refused")
                .isFalse();
        assertThat(store.find(TENANT, mappingId).orElseThrow().status()).isEqualTo("ACTIVE");

        assertThat(store.retire(TENANT, mappingId, 0, NOW)).isTrue();
        MappingRow retired = store.find(TENANT, mappingId).orElseThrow();
        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.version()).isEqualTo(1);

        assertThat(store.retire(TENANT, mappingId, 1, NOW))
                .as("retiring an already-RETIRED row a second time is a no-op, not a second transition")
                .isFalse();
    }

    @Test
    @DisplayName("list filters by entity type and status, newest-updated first")
    void listFiltersByTypeAndStatus() {
        UUID courier = store.create(
                TENANT, INSTALLATION, BINDING, MappingEntityType.COURIER, Ids.newId(), "ext-courier", null, NOW);
        store.create(TENANT, INSTALLATION, BINDING, MappingEntityType.DISCOUNT, Ids.newId(), "ext-discount", null, NOW);
        store.retire(TENANT, courier, 0, NOW);

        List<MappingRow> allCouriers = store.list(TENANT, BINDING, MappingEntityType.COURIER, null, 50, null);
        assertThat(allCouriers).extracting(MappingRow::id).containsExactly(courier);

        List<MappingRow> activeCouriers = store.list(TENANT, BINDING, MappingEntityType.COURIER, "ACTIVE", 50, null);
        assertThat(activeCouriers).isEmpty();

        List<MappingRow> retiredCouriers = store.list(TENANT, BINDING, MappingEntityType.COURIER, "RETIRED", 50, null);
        assertThat(retiredCouriers).extracting(MappingRow::id).containsExactly(courier);
    }

    @Test
    @DisplayName("unmapped payment methods excludes an already-ACTIVE-mapped one and includes the rest")
    void unmappedPaymentMethodsExcludesMapped() {
        UUID cash = insertPaymentMethod("CASH", "Cash");
        UUID card = insertPaymentMethod("CARD", "Card");
        store.create(TENANT, INSTALLATION, BINDING, MappingEntityType.PAYMENT_TYPE, cash, "ext-cash", null, NOW);

        List<NamedCandidate> unmapped = store.unmappedPaymentMethods(TENANT, BINDING);

        assertThat(unmapped).extracting(NamedCandidate::id).containsExactly(card);
    }

    @Test
    @DisplayName(
            "resolveHorecaosNames names an already-mapped row for the linked-pairs table, and skips an unknown id rather than guessing")
    void resolveHorecaosNamesForLinkedPairs() {
        UUID cash = insertPaymentMethod("CASH", "Cash");
        UUID unknown = Ids.newId();

        Map<UUID, String> names =
                store.resolveHorecaosNames(TENANT, null, MappingEntityType.PAYMENT_TYPE, Set.of(cash, unknown));

        assertThat(names).containsEntry(cash, "Cash").doesNotContainKey(unknown);
    }

    @Test
    @DisplayName("unmapped staged products come from the latest run and exclude an already-mapped one")
    void unmappedStagedProductsFromLatestRun() {
        openRunWithStagedProducts();
        store.create(TENANT, INSTALLATION, BINDING, MappingEntityType.PRODUCT, Ids.newId(), "ext-osh", null, NOW);

        List<ExternalCandidate> unmapped = store.unmappedStagedProducts(TENANT, BINDING);

        assertThat(unmapped).extracting(ExternalCandidate::externalId).containsExactly("ext-lagman");
    }

    private UUID insertPaymentMethod(String code, String displayName) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility)
                VALUES (:id, :t, :code, :displayName, 'TERMINAL')
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("code", code)
                .param("displayName", displayName)
                .update();
        return id;
    }

    private UUID openRunWithStagedProducts() {
        JdbcPosSyncStore syncStore = new JdbcPosSyncStore(
                jdbc, tools.jackson.databind.json.JsonMapper.builder().build());
        UUID runId = syncStore.openRun(TENANT, BINDING, "MANUAL", true, "test-adapter-1", 1, NOW);
        jdbc.sql("""
                INSERT INTO integration.pos_staged_products
                    (run_id, tenant_id, external_entity_id, name, source_kind, comparable, raw_payload)
                VALUES (:runId, :t, 'ext-osh', 'Osh', 'DISH', true, '{}'::jsonb),
                       (:runId, :t, 'ext-lagman', 'Lagman', 'DISH', true, '{}'::jsonb)
                """).param("runId", runId).param("t", TENANT).update();
        return runId;
    }
}
