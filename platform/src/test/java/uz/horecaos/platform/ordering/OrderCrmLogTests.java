package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.ordering.application.OrderCrmLogQueryService;
import uz.horecaos.platform.ordering.application.OrderCrmLogQueryService.CrmLogEntry;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore.CrmLogRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore.LogCursor;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;

/**
 * Wave 9 w4-reports-distance-crm (7.2a): the CRM half of the console order
 * log — customer, operator, courier — read straight off {@code
 * ordering}/{@code fulfillment}, outside the reporting module entirely.
 */
class OrderCrmLogTests {

    private static final UUID TENANT = UUID.fromString("018fa000-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fa000-3000-7000-8000-0000000000a2");
    private static final UUID LOCATION = UUID.fromString("018fa000-3000-7000-8000-0000000000a3");
    private static final UUID CUSTOMER = UUID.fromString("018fa000-3000-7000-8000-0000000000a4");
    private static final UUID COURIER_TYPE = UUID.fromString("018fa000-3000-7000-8000-0000000000a5");
    private static final UUID COURIER_1 = UUID.fromString("018fa000-3000-7000-8000-0000000000a6");
    private static final UUID COURIER_2 = UUID.fromString("018fa000-3000-7000-8000-0000000000a7");

    private static final Instant NOON = Instant.parse("2026-09-10T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcOrderCrmLogStore store;
    private OrderCrmLogQueryService service;
    private UUID catalogId;
    private UUID publicationId;
    private UUID channelId;

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
                TRUNCATE TABLE fulfillment.shipments, fulfillment.delivery_plans,
                    fulfillment.couriers, fulfillment.courier_types,
                    ordering.order_state_history, ordering.order_customer_snapshots,
                    ordering.orders, ordering.carts, pricing.quotes,
                    catalog.publications, catalog.catalogs, tenant.sales_channels,
                    customer.customer_accounts CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        FieldProtection protection = TestProtection.envelope();
        store = new JdbcOrderCrmLogStore(jdbc);
        service = new OrderCrmLogQueryService(store, protection);

        seedTenancy();
        seedCouriers();
    }

    @Test
    @DisplayName("decrypts the customer name and phone for an account order")
    void decryptsCustomerNameAndPhoneForAnAccountOrder() {
        UUID orderId = seedOrder("acc-order", NOON, CUSTOMER, null, null, "TELEGRAM");
        seedSnapshot(orderId, "Alisher Karimov", "+998901234567");

        List<CrmLogEntry> rows = service.log(TENANT, NOON.minusSeconds(60), NOON.plusSeconds(60), List.of(), 10, null);

        assertThat(rows).hasSize(1);
        CrmLogEntry entry = rows.getFirst();
        assertThat(entry.customerType()).isEqualTo("ACCOUNT");
        assertThat(entry.customerName()).isEqualTo("Alisher Karimov");
        assertThat(entry.customerPhone()).isEqualTo("+998901234567");
    }

    @Test
    @DisplayName("a guest order carries no customer name")
    void guestOrderCarriesNoCustomerName() {
        seedOrder("guest-order", NOON, null, null, null, "WEBSITE");

        List<CrmLogEntry> rows = service.log(TENANT, NOON.minusSeconds(60), NOON.plusSeconds(60), List.of(), 10, null);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().customerType()).isEqualTo("GUEST");
        assertThat(rows.getFirst().customerName()).isNull();
    }

    @Test
    @DisplayName("the operator column prefers whoever approved the order over whoever created it")
    void operatorPrefersAcceptedOverCreated() {
        UUID orderId = seedOrder("op-order", NOON, null, "USER", "created-by", "TELEGRAM");
        jdbc.sql("""
                UPDATE ordering.orders
                   SET accepted_by_actor_type = 'USER', accepted_by_actor_id = 'accepted-by',
                       accepted_at = :acceptedAt
                 WHERE tenant_id = :t AND id = :id
                """)
                .param("acceptedAt", NOON.plusSeconds(30).atOffset(ZoneOffset.UTC))
                .param("t", TENANT)
                .param("id", orderId)
                .update();

        List<CrmLogEntry> rows = service.log(TENANT, NOON.minusSeconds(60), NOON.plusSeconds(60), List.of(), 10, null);

        assertThat(rows.getFirst().operatorPrincipalId()).isEqualTo("accepted-by");
    }

    @Test
    @DisplayName("an order with no human actor is credited to a pseudo-operator named after its channel")
    void ordersWithNoHumanActorCreditThePseudoOperator() {
        seedOrder("bot-order", NOON, null, "CUSTOMER", "some-customer-principal", "BOT");

        List<CrmLogEntry> rows = service.log(TENANT, NOON.minusSeconds(60), NOON.plusSeconds(60), List.of(), 10, null);

        assertThat(rows.getFirst().operatorPrincipalId()).isEqualTo("channel:BOT");
    }

    @Test
    @DisplayName("the courier column resolves from the most recent shipment, not fanned out across both")
    void courierResolvesFromTheMostRecentShipmentOnly() {
        // The first courier cancels and a second is dispatched under the same
        // plan (ux_shipment_one_active_per_plan permits only one non-cancelled
        // shipment per plan) -- the realistic shape of a re-dispatch, and the
        // exact case the store's own ORDER BY assigned_at DESC must resolve
        // to the second courier, not double the row.
        UUID orderId = seedOrder("courier-order", NOON, null, null, null, "TELEGRAM");
        UUID plan = seedDeliveryPlan(orderId, NOON);
        seedShipment(orderId, plan, COURIER_1, NOON, "CANCELLED");
        seedShipment(orderId, plan, COURIER_2, NOON.plusSeconds(600), "ASSIGNED");

        List<CrmLogRow> rows = store.list(TENANT, NOON.minusSeconds(60), NOON.plusSeconds(3600), List.of(), 10, null);

        // Exactly one row for the order -- a join that fanned out on the two
        // shipments would return two, silently doubling every other figure a
        // caller folds this log into.
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().courierDisplayReference()).isEqualTo("K-002");
    }

    @Test
    @DisplayName("pages the log newest-first by (occurredAt, orderId), never skipping or repeating a row")
    void pagesTheLogByOccurredAtAndOrderId() {
        UUID first = seedOrder("page-1", NOON, null, null, null, "TELEGRAM");
        UUID second = seedOrder("page-2", NOON.plusSeconds(60), null, null, null, "TELEGRAM");
        UUID third = seedOrder("page-3", NOON.plusSeconds(120), null, null, null, "TELEGRAM");

        List<CrmLogRow> firstPage =
                store.list(TENANT, NOON.minusSeconds(60), NOON.plusSeconds(600), List.of(), 2, null);
        assertThat(firstPage).extracting(CrmLogRow::orderId).containsExactly(third, second);

        LogCursor cursor = new LogCursor(
                firstPage.getLast().occurredAt(), firstPage.getLast().orderId());
        List<CrmLogRow> secondPage =
                store.list(TENANT, NOON.minusSeconds(60), NOON.plusSeconds(600), List.of(), 2, cursor);
        assertThat(secondPage).extracting(CrmLogRow::orderId).containsExactly(first);
    }

    @Test
    @DisplayName("never reaches past ordering/fulfillment into the reporting module")
    void neverReadsTheReportingSchema() throws Exception {
        // The one assertion that keeps this store honest about row 7.2a's own
        // constraint: SubjectPseudonym exists precisely so a reporting fact is
        // never re-linked to a customer, and that guarantee is void if this
        // store ever joins reporting.* directly. A source scan, not a runtime
        // check, because the whole point is that the SQL text itself must
        // never name that schema.
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/uz/horecaos/platform/ordering/infrastructure/persistence/JdbcOrderCrmLogStore.java");
        String content = java.nio.file.Files.readString(source);

        // Doc comments are free to name reporting.fact_order in prose (this
        // class's own header does, to explain the boundary) -- what must
        // never appear is a live import or a SQL clause naming the schema.
        // Blank javadoc/line comments out before checking, rather than
        // matching the raw source, so this assertion tracks the code and
        // not the commentary about it.
        String withoutComments = content.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");

        assertThat(withoutComments).doesNotContainIgnoringCase("reporting.");
        assertThat(withoutComments).doesNotContain("uz.horecaos.platform.reporting");
    }

    // --------------------------------------------------------------- fixture

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'crm-log-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("t", TENANT).update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();

        catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
    }

    private void seedCouriers() {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", COURIER_TYPE).param("t", TENANT).update();
        insertCourier(COURIER_1, "K-001");
        insertCourier(COURIER_2, "K-002");
    }

    private void insertCourier(UUID courierId, String displayReference) {
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id, principal_subject,
                    display_reference, protected_full_name)
                VALUES (:id, :t, :type, :subject, :reference, 'ciphertext')
                """)
                .param("id", courierId)
                .param("t", TENANT)
                .param("type", COURIER_TYPE)
                .param("subject", "subject-" + displayReference)
                .param("reference", displayReference)
                .update();
    }

    private UUID seedDeliveryPlan(UUID orderId, Instant anchor) {
        UUID planId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id, order_id,
                    status, currency, confirmed_at, preparation_seconds, estimated_ready_at,
                    pickup_window_start, pickup_window_end, source_at, latest_assignment_at, branch_zone)
                VALUES (:id, :t, :b, :loc, :orderId, 'ASSIGNED', 'UZS', :anchor, 900, :anchor,
                    :anchor, :anchor, :anchor, :anchor, 'Asia/Tashkent')
                """)
                .param("id", planId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("anchor", anchor.atOffset(ZoneOffset.UTC))
                .update();
        return planId;
    }

    /**
     * @param status "CANCELLED" for a shipment being replaced -- {@code
     *               ux_shipment_one_active_per_plan} permits only one
     *               non-cancelled shipment per plan, so a second live
     *               shipment under the same plan requires the first to be
     *               cancelled first, exactly as a real re-dispatch would
     *               leave it.
     */
    private void seedShipment(UUID orderId, UUID planId, UUID courierId, Instant assignedAt, String status) {
        boolean cancelled = "CANCELLED".equals(status);
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (id, tenant_id, brand_id, location_id, order_id,
                    delivery_plan_id, status, source_type, courier_id, assigned_at, cancelled_at)
                VALUES (:id, :t, :b, :loc, :orderId, :plan, :status, 'INTERNAL', :courier, :assignedAt,
                    :cancelledAt)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("plan", planId)
                .param("status", status)
                .param("courier", courierId)
                .param("assignedAt", assignedAt.atOffset(ZoneOffset.UTC))
                .param("cancelledAt", cancelled ? assignedAt.plusSeconds(60).atOffset(ZoneOffset.UTC) : null)
                .update();
    }

    private void seedSnapshot(UUID orderId, String name, String phone) {
        FieldProtection protection = TestProtection.envelope();
        String nameCipher = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "display_name_encrypted", orderId),
                        name)
                .serialize();
        String phoneCipher = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "contact_encrypted", orderId),
                        phone)
                .serialize();
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots (order_id, tenant_id, display_name_encrypted, contact_encrypted)
                VALUES (:orderId, :t, :name, :phone)
                """)
                .param("orderId", orderId)
                .param("t", TENANT)
                .param("name", nameCipher)
                .param("phone", phoneCipher)
                .update();
    }

    private UUID seedOrder(
            String seed,
            Instant createdAt,
            @org.jspecify.annotations.Nullable UUID customerAccountId,
            @org.jspecify.annotations.Nullable String createdByActorType,
            @org.jspecify.annotations.Nullable String createdByActorId,
            String channelCode) {
        UUID orderId = derived("order:" + seed);
        UUID cartId = derived("cart:" + seed);
        UUID quoteId = derived("quote:" + seed);

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor, tax_minor,
                    total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, 'hash', 45000, 0, 45000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("pub", publicationId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status,
                    guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, :cust, 'DELIVERY', 'UZS', 'ACTIVE', :guestHash,
                    now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", customerAccountId)
                .param("guestHash", customerAccountId == null ? "guest-" + seed : null)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, customer_account_id, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, version, created_at, created_by_actor_type, created_by_actor_id)
                VALUES (:id, :num, :t, :b, :loc, :ch, :channelCode, :cust, :guestHash, 'DELIVERY',
                    'AUTO_CONFIRM', 0, 'NONE', 'RECEIVED', 'UZS', 45000, 0, 45000, :quoteId, 'hash',
                    :pub, :cartId, :idem, 1, :createdAt, :createdByType, :createdById)
                """)
                .param("id", orderId)
                .param("num", "F-" + seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("channelCode", channelCode)
                .param("cust", customerAccountId)
                .param("guestHash", customerAccountId == null ? "guest-" + seed : null)
                .param("quoteId", quoteId)
                .param("pub", publicationId)
                .param("cartId", cartId)
                .param("idem", "crm-log-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("createdByType", createdByActorType)
                .param("createdById", createdByActorId)
                .update();
        return orderId;
    }

    private static UUID derived(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
