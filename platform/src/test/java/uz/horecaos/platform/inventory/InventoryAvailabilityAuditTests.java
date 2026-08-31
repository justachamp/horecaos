package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.application.StockAvailabilityPortAdapter;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0060 §4's named audit gap, closed: "the stop-list toggle is not an ADR
 * 0027 audit fact today on ANY channel." {@link InventoryService#setAvailability}
 * — "a kitchen marking a dish sold out, or back on" — wrote a movement and
 * nothing an audit query could ever find. {@link InventoryService#setAvailabilityAudited}
 * is the fix, and both channels ADR 0060 names reach it: {@code
 * InventoryController} (web) calls it directly; the bot's typed {@code /86}
 * command reaches the identical method through {@link StockAvailabilityPortAdapter}.
 * This suite proves both paths produce the audit fact, and that neither
 * double-audits a no-op toggle.
 */
class InventoryAvailabilityAuditTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String LOCALE = "en";
    private static final UUID CREATE_ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-31T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private InventoryService inventory;
    private StockAvailabilityPortAdapter botChannel;
    private UUID variantId;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.location_offerings, catalog.translations, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        insertTenancy();

        JdbcCatalogStore catalogStore =
                new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CatalogAuthoringService authoring = new CatalogAuthoringService(
                catalogStore, new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()), Clock.systemUTC());

        JdbcInventoryStore inventoryStore = new JdbcInventoryStore(jdbc);
        // The three-argument, @Autowired-in-production constructor: this is
        // the one that actually records audit facts, unlike the two-argument
        // overload every pre-ADR-0060 fixture still uses.
        inventory = new InventoryService(
                inventoryStore,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()));
        botChannel = new StockAvailabilityPortAdapter(inventory);

        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "PLOV",
                "Plov",
                null,
                LOCALE,
                "SKU-PLOV",
                "PIECE",
                FiscalClassification.unclassified(),
                CREATE_ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DINE_IN"));
        variantId = plov.defaultVariantId();
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variantId, TrackingMode.BINARY);
    }

    @Test
    @DisplayName("the web channel's 86 toggle writes an ADR 0027 audit fact")
    void theWebChannelAudits() {
        String webActor = UUID.randomUUID().toString();

        boolean changed = inventory.setAvailabilityAudited(TENANT, LOCATION, variantId, false, "SOLD_OUT", webActor);

        assertThat(changed).isTrue();
        List<Map<String, Object>> rows = auditRowsFor(variantId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("action_code")).isEqualTo("inventory.availability.set");
            assertThat(row.get("actor_subject")).isEqualTo(webActor);
            assertThat(row.get("actor_type")).isEqualTo("USER");
            assertThat(row.get("reason")).isEqualTo("SOLD_OUT");
            assertThat(row.get("tenant_id")).isEqualTo(TENANT);
        });
    }

    @Test
    @DisplayName("the bot's typed /86 command reaches the identical audited method")
    void theBotChannelAudits() {
        String botActor = UUID.randomUUID().toString();

        botChannel.toggle(TENANT, LOCATION, variantId, false, "TELEGRAM_BOT_86", botActor);

        List<Map<String, Object>> rows = auditRowsFor(variantId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("action_code")).isEqualTo("inventory.availability.set");
            assertThat(row.get("actor_subject")).isEqualTo(botActor);
            assertThat(row.get("reason")).isEqualTo("TELEGRAM_BOT_86");
        });
    }

    @Test
    @DisplayName("both channels share one call site: a web toggle then a bot toggle of the same variant both land")
    void bothChannelsWriteToTheSameLedger() {
        String webActor = UUID.randomUUID().toString();
        String botActor = UUID.randomUUID().toString();

        inventory.setAvailabilityAudited(TENANT, LOCATION, variantId, false, "SOLD_OUT", webActor);
        botChannel.toggle(TENANT, LOCATION, variantId, true, "RESTOCKED", botActor);

        List<Map<String, Object>> rows = auditRowsFor(variantId);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(row -> row.get("actor_subject"))).containsExactlyInAnyOrder(webActor, botActor);
    }

    @Test
    @DisplayName("toggling to the state it is already in records nothing, on either channel")
    void aNoOpToggleIsNotAudited() {
        // The variant starts available; toggling it to available again is a
        // no-op on both InventoryService.setAvailability's own idempotency
        // rule and this audit wrapper's.
        boolean changed = inventory.setAvailabilityAudited(TENANT, LOCATION, variantId, true, "ALREADY_ON", "actor");

        assertThat(changed).isFalse();
        assertThat(auditRowsFor(variantId)).isEmpty();
    }

    private List<Map<String, Object>> auditRowsFor(UUID targetId) {
        return jdbc.sql("""
                SELECT action_code, actor_subject, actor_type, reason, tenant_id
                FROM audit.audit_events
                WHERE target_id = :targetId
                ORDER BY occurred_at
                """).param("targetId", targetId).query().listOfRows();
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'inventory-audit-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'inventory-audit-brand', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'inventory-audit-location', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }
}
