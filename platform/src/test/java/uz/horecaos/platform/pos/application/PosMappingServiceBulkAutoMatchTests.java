package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.integration.api.provider.MappingEntityType;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.application.PosMappingService.BulkAutoMatchResult;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Bulk auto-match's own rule (gap-map row 10.8b): a name shared by more than
 * one candidate on either side is reported as a conflict, never resolved by
 * picking one — the whole argument the row makes against a last-write-wins
 * auto-match.
 */
class PosMappingServiceBulkAutoMatchTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126004");

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private FakePosAdapter adapter;
    private PosMappingService service;

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

        jdbc.sql("DELETE FROM integration.provider_entity_mappings WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM payments.payment_methods WHERE tenant_id = :t")
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
                VALUES (:id, 'pos-bulk-auto-match', 'Legal', 'POS bulk auto-match', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'BULK_MATCH_BRAND', 'bulk-match-brand', 'Bulk match brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'POS', :providerType, 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """)
                .param("id", INSTALLATION)
                .param("t", TENANT)
                .param("providerType", FakePosAdapter.PROVIDER_TYPE)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();

        adapter = new FakePosAdapter();
        JdbcPosMappingStore mappingStore = new JdbcPosMappingStore(jdbc);
        JdbcPosBindingConfiguration configuration =
                new JdbcPosBindingConfiguration(jdbc, JsonMapper.builder().build());
        PosAdapterRegistry registry = new PosAdapterRegistry(List.of(adapter));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PosMappingService(mappingStore, configuration, registry, clock);
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

    @Test
    @DisplayName("an unambiguous 1:1 name match on both sides is mapped automatically")
    void unambiguousPairsAreMapped() {
        UUID cash = insertPaymentMethod("CASH", "Cash");
        UUID card = insertPaymentMethod("CARD", "Card");
        adapter.scriptPaymentTypes(List.of(
                new PosAdapter.ExternalReference("ext-cash", "Cash"),
                new PosAdapter.ExternalReference("ext-card", "Card")));

        BulkAutoMatchResult result = service.bulkAutoMatch(TENANT, BINDING, MappingEntityType.PAYMENT_TYPE);

        assertThat(result.sourced()).isTrue();
        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.conflicts()).isEmpty();

        JdbcPosMappingStore store = new JdbcPosMappingStore(jdbc);
        assertThat(store.list(TENANT, BINDING, MappingEntityType.PAYMENT_TYPE, "ACTIVE", 50, null))
                .extracting(JdbcPosMappingStore.MappingRow::horecaosEntityId)
                .containsExactlyInAnyOrder(cash, card);
    }

    @Test
    @DisplayName("two candidates sharing one name on either side is a conflict, never a last-write-wins guess")
    void ambiguousNamesAreReportedAsConflictsNotGuessed() {
        UUID cash1 = insertPaymentMethod("CASH_TILL_1", "Cash");
        UUID cash2 = insertPaymentMethod("CASH_TILL_2", "Cash");
        adapter.scriptPaymentTypes(List.of(
                new PosAdapter.ExternalReference("ext-cash-a", "Cash"),
                new PosAdapter.ExternalReference("ext-cash-b", "Cash")));

        BulkAutoMatchResult result = service.bulkAutoMatch(TENANT, BINDING, MappingEntityType.PAYMENT_TYPE);

        assertThat(result.matchedCount())
                .as(
                        "neither pairing may be picked for the other -- that is the last-write-wins behaviour this row exists to avoid")
                .isZero();
        assertThat(result.conflicts()).hasSize(1);
        PosMappingService.MatchConflict conflict = result.conflicts().getFirst();
        assertThat(conflict.name()).isEqualTo("Cash");
        assertThat(conflict.externalIds()).containsExactlyInAnyOrder("ext-cash-a", "ext-cash-b");
        assertThat(conflict.horecaosEntityIds()).containsExactlyInAnyOrder(cash1, cash2);

        JdbcPosMappingStore store = new JdbcPosMappingStore(jdbc);
        assertThat(store.list(TENANT, BINDING, MappingEntityType.PAYMENT_TYPE, "ACTIVE", 50, null))
                .as("a conflict is left for a person to resolve, not written")
                .isEmpty();
    }

    @Test
    @DisplayName("a vendor with no discovery for this entity type is reported as unsourced, not silently empty")
    void unsupportedProviderIsReportedAsUnsourced() {
        adapter.failPaymentTypesWith(ProviderOutcome.rejected("NOT_SUPPORTED", "no such list"));

        BulkAutoMatchResult result = service.bulkAutoMatch(TENANT, BINDING, MappingEntityType.PAYMENT_TYPE);

        assertThat(result.sourced()).isFalse();
        assertThat(result.matchedCount()).isZero();
        assertThat(result.conflicts()).isEmpty();
    }
}
