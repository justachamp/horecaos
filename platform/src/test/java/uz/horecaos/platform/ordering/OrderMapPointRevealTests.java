package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.ordering.api.BusinessDayWindows;
import uz.horecaos.platform.ordering.application.OrderMapPointService;
import uz.horecaos.platform.ordering.application.OrderMapPointService.MapPoint;
import uz.horecaos.platform.ordering.application.OrderMapPointService.MapPoints;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;

/**
 * Row {@code 7.10a} (ADR 0145 decision 8): today's delivery orders as pins -- "a dispatcher-scope
 * read with an ADR 0027 audited purpose, never a reporting fact".
 *
 * <p>A pin is a doorstep, and a doorstep is only ever inside an order's encrypted snapshot, so
 * what these tests pin down is the three ways a map of the day could do harm: it could name the
 * person at each door, it could open the doors without anything remembering that it did, and it
 * could quietly show a different set of orders from the one the branch actually took.
 */
class OrderMapPointRevealTests {

    private static final UUID TENANT = UUID.fromString("018fa100-3000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fa100-3000-7000-8000-0000000000e1");
    private static final UUID BRAND = UUID.fromString("018fa100-3000-7000-8000-0000000000a2");
    private static final UUID OTHER_TENANT_BRAND = UUID.fromString("018fa100-3000-7000-8000-0000000000e2");
    private static final UUID LOCATION = UUID.fromString("018fa100-3000-7000-8000-0000000000a3");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fa100-3000-7000-8000-0000000000a4");
    private static final UUID OTHER_TENANT_LOCATION = UUID.fromString("018fa100-3000-7000-8000-0000000000e3");

    private static final Instant DAY_START = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-09-11T00:00:00Z");
    private static final Instant NOON = Instant.parse("2026-09-10T12:00:00Z");
    private static final String PURPOSE = "Dispatch: see where the day's orders are clustering";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OrderMapPointService service;
    private OrderMapPointService smallService;
    private final FieldProtection protection = TestProtection.envelope();
    private final BusinessDayWindows today = (tenantId, at) -> new BusinessDayWindows.Window(DAY_START, DAY_END);

    private UUID channelId;
    private UUID publicationId;
    private UUID otherTenantChannelId;
    private UUID otherTenantPublicationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for ordering tests");
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

        jdbc.sql("""
                TRUNCATE TABLE ordering.order_state_history, ordering.order_customer_snapshots,
                    ordering.orders, ordering.carts, pricing.quotes,
                    catalog.publications, catalog.catalogs, tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        AuditTrail.clear(jdbc);

        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        JdbcOrderStore store = new JdbcOrderStore(jdbc);
        service = new OrderMapPointService(store, protection, JSON, AuditTrail.recorder(jdbc), today, clock);
        smallService = new OrderMapPointService(store, protection, JSON, AuditTrail.recorder(jdbc), today, clock, 2);

        seedTenancy();
    }

    @Test
    @DisplayName("opens the day's delivery orders as points, newest first")
    void opensTheDaysDeliveryOrdersAsPoints() {
        UUID older = seedDelivery("older", NOON.minusSeconds(3600), "RECEIVED", 41.311081, 69.240562);
        UUID newer = seedDelivery("newer", NOON, "CONFIRMED", 41.295, 69.28);

        MapPoints revealed = service.reveal(TENANT, BRAND, LOCATION, PURPOSE, "dispatcher-1");

        assertThat(revealed.points()).extracting(MapPoint::orderId).containsExactly(newer, older);
        MapPoint first = revealed.points().get(0);
        assertThat(first.latitude()).isEqualTo(41.295);
        assertThat(first.longitude()).isEqualTo(69.28);
        assertThat(first.status().name()).isEqualTo("CONFIRMED");
        assertThat(first.publicOrderNumber()).isEqualTo("F-newer");
        assertThat(revealed.withoutPoint()).isZero();
        assertThat(revealed.truncated()).isFalse();
        assertThat(revealed.window().from()).isEqualTo(DAY_START);
    }

    @Test
    @DisplayName("a pin names an order and a place and never a person")
    void aPinHoldsNoCustomerIdentity() throws Exception {
        seedDelivery("named", NOON, "CONFIRMED", 41.311081, 69.240562);

        MapPoints revealed = service.reveal(TENANT, BRAND, LOCATION, PURPOSE, "dispatcher-1");

        assertThat(java.util.Arrays.stream(MapPoint.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .as("the answer's whole shape: add a field that identifies a person and this fails")
                .containsExactly("orderId", "publicOrderNumber", "status", "createdAt", "latitude", "longitude");
        String wire = JSON.writeValueAsString(revealed.points());
        assertThat(wire)
                .doesNotContain("Alisher")
                .doesNotContain("998901234567")
                .doesNotContain("Amir Temur")
                .doesNotContain("blue gate")
                .doesNotContain("Ring the top bell");
        assertThat(revealed.points().get(0).toString())
                .as("a log line holding a pin must not hold a doorstep")
                .doesNotContain("41.311");
    }

    @Test
    @DisplayName("leaves one audit fact for the whole call: who, why and how many, never where")
    void leavesOneAuditFactNamingWhoWhyAndHowManyAndNotWhere() {
        seedDelivery("a", NOON, "CONFIRMED", 41.311081, 69.240562);
        seedDelivery("b", NOON.minusSeconds(60), "CONFIRMED", 41.2999, 69.2111);

        service.reveal(TENANT, BRAND, LOCATION, PURPOSE, "dispatcher-1");

        AuditTrail.Fact fact = AuditTrail.only(jdbc, "order.map_points.revealed");
        assertThat(fact.actorSubject()).isEqualTo("dispatcher-1");
        assertThat(fact.reason()).isEqualTo(PURPOSE);
        assertThat(fact.targetId()).isEqualTo(LOCATION);
        assertThat(fact.after("orders").asInt()).isEqualTo(2);
        assertThat(fact.after("windowFrom").asString()).isEqualTo(DAY_START.toString());
        assertThat(fact.change().toString())
                .as("the evidence says that doors were opened, not which")
                .doesNotContain("41.31")
                .doesNotContain("69.24")
                .doesNotContain("41.29")
                .doesNotContain("69.21");
    }

    @Test
    @DisplayName("a day with no delivery orders still leaves its fact")
    void aReadThatFoundNothingIsStillAReadAndIsRecorded() {
        MapPoints revealed = service.reveal(TENANT, BRAND, LOCATION, PURPOSE, "dispatcher-1");

        assertThat(revealed.points()).isEmpty();
        assertThat(AuditTrail.only(jdbc, "order.map_points.revealed")
                        .after("orders")
                        .asInt())
                .isZero();
    }

    @Test
    @DisplayName("only this branch's delivery orders of this tenant inside the business day")
    void onlyThisBranchesDeliveryOrdersInsideTheWindow() {
        UUID mine = seedDelivery("mine", NOON, "CONFIRMED", 41.31, 69.24);
        seedDelivery("yesterday", DAY_START.minusSeconds(60), "CONFIRMED", 41.31, 69.24);
        seedDelivery("tomorrow", DAY_END, "CONFIRMED", 41.31, 69.24);
        seedDeliveryAt(
                "elsewhere", NOON, "CONFIRMED", 41.31, 69.24, TENANT, BRAND, OTHER_LOCATION, channelId, publicationId);
        seedDeliveryAt(
                "foreign",
                NOON,
                "CONFIRMED",
                41.31,
                69.24,
                OTHER_TENANT,
                OTHER_TENANT_BRAND,
                OTHER_TENANT_LOCATION,
                otherTenantChannelId,
                otherTenantPublicationId);
        seedPickup("pickup", NOON);

        MapPoints revealed = service.reveal(TENANT, BRAND, LOCATION, PURPOSE, "dispatcher-1");

        assertThat(revealed.points())
                .extracting(MapPoint::orderId)
                .as("another branch, another tenant, a pickup, and the days either side are not today's map")
                .containsExactly(mine);
    }

    @Test
    @DisplayName("an order whose address cannot be opened is counted, not dropped and not logged")
    void anUnreadableAddressIsCountedAsWithoutAPoint() {
        seedDelivery("good", NOON, "CONFIRMED", 41.31, 69.24);
        // No snapshot row at all, a snapshot with its address erased, and a ciphertext that was
        // copied under another order's identity (the AAD binds it to its own row, so it will not open).
        seedDeliveryWithSnapshot("no-snapshot", NOON.minusSeconds(10), null, false);
        seedDeliveryWithSnapshot("erased", NOON.minusSeconds(20), null, true);
        UUID stolen = UUID.randomUUID();
        seedDeliveryWithSnapshot("misplaced", NOON.minusSeconds(30), protectedAddress(stolen, 41.31, 69.24), true);

        MapPoints revealed = service.reveal(TENANT, BRAND, LOCATION, PURPOSE, "dispatcher-1");

        assertThat(revealed.points()).hasSize(1);
        assertThat(revealed.withoutPoint())
                .as("the map says three orders have no pin rather than showing a day that looks smaller")
                .isEqualTo(3);
        assertThat(AuditTrail.only(jdbc, "order.map_points.revealed")
                        .after("orders")
                        .asInt())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("cuts the list at the cap, keeps the newest, and says it was cut")
    void aLongDayIsTruncatedToTheNewestAndSaysSo() {
        seedDelivery("o1", NOON.minusSeconds(300), "CONFIRMED", 41.31, 69.24);
        UUID o2 = seedDelivery("o2", NOON.minusSeconds(200), "CONFIRMED", 41.31, 69.24);
        UUID o3 = seedDelivery("o3", NOON.minusSeconds(100), "CONFIRMED", 41.31, 69.24);

        MapPoints revealed = smallService.reveal(TENANT, BRAND, LOCATION, PURPOSE, "dispatcher-1");

        assertThat(revealed.truncated()).isTrue();
        assertThat(revealed.points()).extracting(MapPoint::orderId).containsExactly(o3, o2);
        assertThat(AuditTrail.only(jdbc, "order.map_points.revealed")
                        .after("orders")
                        .asInt())
                .as("the fact counts what was opened, not what was skipped")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a call exactly at the cap is not truncated")
    void aDayExactlyAtTheCapIsNotTruncated() {
        seedDelivery("o1", NOON.minusSeconds(200), "CONFIRMED", 41.31, 69.24);
        seedDelivery("o2", NOON.minusSeconds(100), "CONFIRMED", 41.31, 69.24);

        MapPoints revealed = smallService.reveal(TENANT, BRAND, LOCATION, PURPOSE, "dispatcher-1");

        assertThat(revealed.truncated()).isFalse();
        assertThat(revealed.points()).hasSize(2);
    }

    @Test
    @DisplayName("a reveal with no stated purpose is refused before anything is opened or recorded")
    void aBlankPurposeIsRefusedAndLeavesNothing() {
        seedDelivery("a", NOON, "CONFIRMED", 41.31, 69.24);

        assertThatThrownBy(() -> service.reveal(TENANT, BRAND, LOCATION, "  ", "dispatcher-1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(AuditTrail.count(jdbc)).isZero();
    }

    // --------------------------------------------------------------- fixtures

    private UUID seedDelivery(String seed, Instant createdAt, String status, double latitude, double longitude) {
        return seedDeliveryAt(
                seed, createdAt, status, latitude, longitude, TENANT, BRAND, LOCATION, channelId, publicationId);
    }

    private UUID seedDeliveryAt(
            String seed,
            Instant createdAt,
            String status,
            double latitude,
            double longitude,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channel,
            UUID publication) {
        UUID orderId =
                insertOrder(seed, createdAt, status, "DELIVERY", tenantId, brandId, locationId, channel, publication);
        insertSnapshot(tenantId, orderId, protectedAddress(tenantId, orderId, latitude, longitude));
        return orderId;
    }

    private UUID seedPickup(String seed, Instant createdAt) {
        UUID orderId =
                insertOrder(seed, createdAt, "CONFIRMED", "PICKUP", TENANT, BRAND, LOCATION, channelId, publicationId);
        insertSnapshot(TENANT, orderId, null);
        return orderId;
    }

    /** @param withSnapshotRow false leaves no snapshot row at all, which the read must still count */
    private UUID seedDeliveryWithSnapshot(
            String seed, Instant createdAt, @Nullable String addressCiphertext, boolean withSnapshotRow) {
        UUID orderId = insertOrder(
                seed, createdAt, "CONFIRMED", "DELIVERY", TENANT, BRAND, LOCATION, channelId, publicationId);
        if (withSnapshotRow) {
            insertSnapshot(TENANT, orderId, addressCiphertext);
        }
        return orderId;
    }

    private String protectedAddress(UUID orderId, double latitude, double longitude) {
        return protectedAddress(TENANT, orderId, latitude, longitude);
    }

    private String protectedAddress(UUID tenantId, UUID orderId, double latitude, double longitude) {
        DeliveryDestination destination = new DeliveryDestination(
                "Amir Temur 12", "", "Tashkent", "Yunusobod", "", "2", "5", "41", "blue gate", latitude, longitude);
        return protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "address_encrypted", orderId),
                        JSON.writeValueAsString(destination))
                .serialize();
    }

    private void insertSnapshot(UUID tenantId, UUID orderId, @Nullable String addressCiphertext) {
        String name = protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "display_name_encrypted", orderId),
                        "Alisher Karimov")
                .serialize();
        String phone = protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "contact_encrypted", orderId),
                        "+998901234567")
                .serialize();
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots
                    (order_id, tenant_id, display_name_encrypted, contact_encrypted, address_encrypted)
                VALUES (:orderId, :t, :name, :phone, :address)
                """)
                .param("orderId", orderId)
                .param("t", tenantId)
                .param("name", name)
                .param("phone", phone)
                .param("address", addressCiphertext)
                .update();
    }

    private UUID insertOrder(
            String seed,
            Instant createdAt,
            String status,
            String fulfillmentMode,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channel,
            UUID publication) {
        UUID orderId = derived("order:" + tenantId + seed);
        UUID cartId = derived("cart:" + tenantId + seed);
        UUID quoteId = derived("quote:" + tenantId + seed);

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor, tax_minor,
                    total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, 'hash', 45000, 0, 45000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("pub", publication)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, :mode, 'UZS', 'ACTIVE', :guestHash, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channel)
                .param("mode", fulfillmentMode)
                .param("guestHash", "guest-" + seed)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, version, created_at, confirmed_at)
                VALUES (:id, :num, :t, :b, :loc, :ch, 'WEB', :guestHash, :mode,
                    'AUTO_CONFIRM', 0, 'NONE', :status, 'UZS', 45000, 0, 45000, :quoteId, 'hash',
                    :pub, :cartId, :idem, 1, :createdAt,
                    CASE WHEN :status = 'RECEIVED' THEN NULL ELSE :createdAt END)
                """)
                .param("id", orderId)
                .param("num", "F-" + seed)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channel)
                .param("guestHash", "guest-" + seed)
                .param("mode", fulfillmentMode)
                .param("status", status)
                .param("quoteId", quoteId)
                .param("pub", publication)
                .param("cartId", cartId)
                .param("idem", "map-points-" + tenantId + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
        return orderId;
    }

    private void seedTenancy() {
        channelId = seedTenant(TENANT, BRAND, List.of(LOCATION, OTHER_LOCATION), "map-points-tenant");
        publicationId = publicationOf(TENANT);
        otherTenantChannelId =
                seedTenant(OTHER_TENANT, OTHER_TENANT_BRAND, List.of(OTHER_TENANT_LOCATION), "map-points-other");
        otherTenantPublicationId = publicationOf(OTHER_TENANT);
    }

    private UUID publicationOf(UUID tenantId) {
        return jdbc.sql("SELECT id FROM catalog.publications WHERE tenant_id = :t")
                .param("t", tenantId)
                .query(UUID.class)
                .single();
    }

    /** @return the tenant's sales channel id */
    private UUID seedTenant(UUID tenantId, UUID brandId, List<UUID> locations, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        int index = 0;
        for (UUID location : locations) {
            index++;
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :t, :b, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("t", tenantId)
                    .param("b", brandId)
                    .param("code", "B" + index)
                    .param("slug", "branch-" + index)
                    .update();
        }
        UUID channel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channel).param("t", tenantId).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenantId)
                .param("b", brandId)
                .param("cat", catalogId)
                .update();
        return channel;
    }

    private static UUID derived(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
