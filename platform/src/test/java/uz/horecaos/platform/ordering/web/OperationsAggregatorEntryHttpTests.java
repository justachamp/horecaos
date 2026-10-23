package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Row {@code 1.3g}, over real HTTP: {@code POST .../orders/aggregator-entries} through the production
 * controller, service, store and idempotency interceptor, with the JSON the New order screen's
 * aggregator panel sends ({@code submitAggregator} in {@code new-order-page.ts}).
 *
 * <p>{@code AggregatorOrderIntakeServiceTests} proves the service and the console spec mocks the API,
 * so "tested both ends" never met over HTTP until this class: no test named the path, the request
 * record or the capability in front of it. What this adds that the service test cannot say: the body
 * the console actually sends deserialises (four primitive money fields among them, which Jackson 3
 * refuses when omitted), {@code ORDER_PLACE} at {@code LOCATION} is what guards it, a replayed
 * {@code Idempotency-Key} writes one order, and the order is found where the board's filters look for
 * it. The {@code DELIVERY} case is the one row {@code 1.3b} unblocked: a {@code LOCATION_STAFF}
 * operator, who holds no customer-PII capability, gives the address through the route ADR 0177
 * grants and then records the aggregator's delivery against it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class OperationsAggregatorEntryHttpTests {

    private static final UUID TENANT = UUID.fromString("018fe100-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fe100-4000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018fe100-4000-7000-8000-0000000000c3");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fe100-4000-7000-8000-0000000000c4");

    /** {@code LOCATION_MANAGER} at the branch: the persona the earlier cases of the order entry test use. */
    private static final String MANAGER = "aggregator-entry-http-manager";

    /** {@code LOCATION_STAFF} at the branch: the call-centre operator, with no customer-PII grant. */
    private static final String STAFF = "aggregator-entry-http-staff";

    /** {@code TENANT_FINANCE} at the branch: reads money, places nothing. */
    private static final String NO_PLACE = "aggregator-entry-http-finance";

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String CHANNEL = "UZUM-TEZKOR";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the aggregator entry HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "300");
        // A delivery entry names a saved address, which is envelope-encrypted (ADR 0029).
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    private UUID bindingId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.order_number_counters CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_sourcing_jobs, fulfillment.delivery_plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        roleRegistry.synchronize();

        seedTenancyChannelAndBinding();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(STAFF, PlatformRole.LOCATION_STAFF);
        grant(NO_PLACE, PlatformRole.TENANT_FINANCE);
    }

    @Test
    @DisplayName(
            "the console's JSON records a manual entry, and the board finds it by the aggregator's number and by origin")
    void theConsoleJsonRecordsAManualEntryTheBoardFinds() throws Exception {
        MvcResult created = enter(MANAGER, pickupBody("YE-1001", 30_000, 0, 5_000, 35_000), "agg-key-1");

        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        JsonNode answer = JSON.readTree(created.getResponse().getContentAsString(UTF_8));
        UUID orderId = UUID.fromString(answer.path("orderId").asText());
        assertThat(answer.path("publicOrderNumber").asText()).isNotBlank();
        assertThat(answer.path("outcome").asText()).isEqualTo("CREATED");

        Map<String, Object> row = jdbc.sql("""
                        SELECT origin, pricing_authority, entry_mode, marketplace_binding_id, currency,
                               subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
                               created_by_actor_id
                        FROM ordering.orders WHERE id = :id
                        """).param("id", orderId).query().singleRow();
        assertThat(row.get("origin")).isEqualTo("MARKETPLACE");
        assertThat(row.get("pricing_authority"))
                .as("the aggregator priced it; HorecaOS validates arithmetic and nothing else")
                .isEqualTo("EXTERNAL");
        assertThat(row.get("entry_mode")).isEqualTo("MANUAL");
        assertThat(row.get("marketplace_binding_id")).isEqualTo(bindingId);
        assertThat(row.get("created_by_actor_id")).isEqualTo(MANAGER);
        assertThat(row.get("subtotal_minor")).isEqualTo(30_000L);
        assertThat(row.get("fee_minor")).isEqualTo(5_000L);
        assertThat(row.get("total_minor")).isEqualTo(35_000L);

        assertThat(boardIds(MANAGER, "reference=YE-1001"))
                .as("found by the aggregator's own number")
                .containsExactly(orderId);
        assertThat(boardIds(MANAGER, "origin=MARKETPLACE")).containsExactly(orderId);
        assertThat(boardIds(MANAGER, "origin=HORECAOS"))
                .as("and excluded from the own-channel mix")
                .isEmpty();
        assertThat(boardIds(MANAGER, "reference=NOT-THIS-ONE")).isEmpty();
    }

    @Test
    @DisplayName("a subject without ORDER_PLACE at the branch is refused, and nothing is written")
    void aSubjectWithoutOrderPlaceIsRefused() throws Exception {
        MvcResult refused = enter(NO_PLACE, pickupBody("YE-2001", 30_000, 0, 0, 30_000), "agg-key-denied");

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName(
            "the same Idempotency-Key writes one order and answers the same one; a different body under it is refused")
    void aReplayedKeyWritesOneOrder() throws Exception {
        String body = pickupBody("YE-3001", 30_000, 0, 0, 30_000);
        MvcResult first = enter(MANAGER, body, "agg-key-replay");
        MvcResult second = enter(MANAGER, body, "agg-key-replay");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus())
                .as(second.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        assertThat(orderIdOf(second)).isEqualTo(orderIdOf(first));
        assertThat(orderCount()).as("a double-tapped button is one order").isEqualTo(1L);

        MvcResult changed = enter(MANAGER, pickupBody("YE-3002", 40_000, 0, 0, 40_000), "agg-key-replay");
        assertThat(changed.getResponse().getStatus())
                .as("the same key for a different request is the server refusing to guess")
                .isEqualTo(409);
        assertThat(orderCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "arithmetic that does not reconcile, a missing money field and a channel that is not an aggregator are refused")
    void whatCannotBeBookedIsRefused() throws Exception {
        MvcResult mismatch = enter(MANAGER, pickupBody("YE-4001", 30_000, 0, 0, 29_000), "agg-key-mismatch");
        MvcResult lines = enter(
                MANAGER,
                pickupBody("YE-4002", 30_000, 0, 0, 31_000)
                        .replace("\"subtotalMinor\":30000", "\"subtotalMinor\":31000"),
                "agg-key-lines");
        MvcResult noTotal = enter(
                MANAGER,
                pickupBody("YE-4003", 30_000, 0, 0, 30_000).replace(",\"totalMinor\":30000", ""),
                "agg-key-missing");
        MvcResult notAggregator = enter(
                MANAGER,
                pickupBody("YE-4004", 30_000, 0, 0, 30_000).replace("\"" + CHANNEL + "\"", "\"STOREFRONT\""),
                "agg-key-wrong-channel");

        assertThat(mismatch.getResponse().getStatus())
                .as("a total below the subtotal would imply negative tax")
                .isEqualTo(400);
        assertThat(lines.getResponse().getStatus())
                .as("a header subtotal that disagrees with the lines typed under it")
                .isEqualTo(400);
        assertThat(noTotal.getResponse().getStatus())
                .as("Jackson 3 refuses a missing primitive; the console always sends all four money fields")
                .isEqualTo(400);
        assertThat(notAggregator.getResponse().getStatus()).isBetween(400, 499);
        assertThat(orderCount()).as("none of them wrote an order").isZero();
    }

    @Test
    @DisplayName(
            "a LOCATION_STAFF operator records an aggregator's delivery against an address given through the 1.3b route")
    void aCallCentreOperatorRecordsAnAggregatorDelivery() throws Exception {
        UUID account = openAccount(STAFF, "+998935550201");
        MvcResult address = mvc.perform(post(addressesPath(account))
                        .with(tokenFor(STAFF))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "agg-address-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home",
                                 "fields":{"line1":"Amir Temur ko'chasi 12","city":"Tashkent","district":"Yunusobod",
                                           "apartment":"5","landmark":"blue gate"},
                                 "latitude":41.312,"longitude":69.241,"coordinateSource":"OPERATOR_PIN"}"""))
                .andReturn();
        assertThat(address.getResponse().getStatus())
                .as(address.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        UUID addressId = UUID.fromString(JSON.readTree(address.getResponse().getContentAsString(UTF_8))
                .path("id")
                .asText());

        MvcResult entered = enter(STAFF, deliveryBody("YE-5001", account, addressId), "agg-key-delivery");

        assertThat(entered.getResponse().getStatus())
                .as(entered.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        UUID orderId = orderIdOf(entered);
        JsonNode answer = JSON.readTree(entered.getResponse().getContentAsString(UTF_8));
        assertThat(answer.path("status").asText())
                .as("a delivery entry is written already confirmed, as its plan is opened against that status")
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT fulfillment_mode FROM ordering.orders WHERE id = :id")
                        .param("id", orderId)
                        .query(String.class)
                        .single())
                .isEqualTo("DELIVERY");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.delivery_plans WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Long.class)
                        .single())
                .as("the same delivery plan a native order's confirmation opens")
                .isEqualTo(1L);
        assertThat(boardIds(STAFF, "reference=YE-5001")).containsExactly(orderId);
    }

    @Test
    @DisplayName("a delivery entry for an address that is not the named customer's is refused")
    void aDeliveryEntryForSomeoneElsesAddressIsRefused() throws Exception {
        UUID mine = openAccount(STAFF, "+998935550202");
        UUID theirs = openAccount(STAFF, "+998935550203");
        MvcResult address = mvc.perform(post(addressesPath(theirs))
                        .with(tokenFor(STAFF))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "agg-address-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home",
                                 "fields":{"line1":"Navoi ko'chasi 3","city":"Tashkent","district":"Chilonzor"},
                                 "latitude":41.31,"longitude":69.24,"coordinateSource":"OPERATOR_PIN"}"""))
                .andReturn();
        UUID theirAddress = UUID.fromString(JSON.readTree(address.getResponse().getContentAsString(UTF_8))
                .path("id")
                .asText());

        MvcResult entered = enter(STAFF, deliveryBody("YE-5002", mine, theirAddress), "agg-key-stolen");

        assertThat(entered.getResponse().getStatus()).isEqualTo(400);
        assertThat(orderCount()).isZero();
    }

    // ----------------------------------------------------------------------------------- helpers

    private MvcResult enter(String subject, String body, String key) throws Exception {
        return mvc.perform(post(ordersPath() + "/aggregator-entries")
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /**
     * What {@code submitAggregator} sends for a pickup: every key the console writes, in its
     * order, with {@code fulfillmentMode} spelled out and the delivery-only members {@code null}.
     */
    private static String pickupBody(String externalId, long subtotal, long discount, long fee, long total) {
        return """
                {"channelCode":"%s","externalOrderId":"%s",
                 "lines":[{"variantId":"%s","nameSnapshot":"Osh","quantity":1,"unitAmountMinor":%d}],
                 "currency":"UZS","subtotalMinor":%d,"discountMinor":%d,"feeMinor":%d,"totalMinor":%d,
                 "fulfillmentMode":"PICKUP","customerAccountId":null,"destination":null}""".formatted(CHANNEL, externalId, UUID.randomUUID(), subtotal, subtotal, discount, fee, total);
    }

    private static String deliveryBody(String externalId, UUID account, UUID address) {
        return """
                {"channelCode":"%s","externalOrderId":"%s",
                 "lines":[{"variantId":"%s","nameSnapshot":"Osh","quantity":1,"unitAmountMinor":30000}],
                 "currency":"UZS","subtotalMinor":30000,"discountMinor":0,"feeMinor":0,"totalMinor":30000,
                 "fulfillmentMode":"DELIVERY","customerAccountId":"%s",
                 "destination":{"customerAddressId":"%s","recipientName":"Aziz","recipientPhone":"+998935550201",
                                "deliveryNote":null}}""".formatted(CHANNEL, externalId, UUID.randomUUID(), account, address);
    }

    private List<UUID> boardIds(String subject, String query) throws Exception {
        MvcResult board = mvc.perform(get(ordersPath() + "/board?" + query).with(tokenFor(subject)))
                .andReturn();
        assertThat(board.getResponse().getStatus())
                .as(board.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return java.util.stream.StreamSupport.stream(
                        JSON.readTree(board.getResponse().getContentAsString(UTF_8))
                                .path("items")
                                .spliterator(),
                        false)
                .map(item -> UUID.fromString(item.path("orderId").asText()))
                .toList();
    }

    private UUID openAccount(String subject, String phone) throws Exception {
        MvcResult created = mvc.perform(
                        post("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/customers")
                                .with(tokenFor(subject))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "agg-create-" + phone)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone\":\"" + phone + "\",\"displayName\":\"Aziz\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        return UUID.fromString(JSON.readTree(created.getResponse().getContentAsString(UTF_8))
                .path("id")
                .asText());
    }

    private static UUID orderIdOf(MvcResult result) throws Exception {
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString(UTF_8))
                .path("orderId")
                .asText());
    }

    private long orderCount() {
        return jdbc.sql("SELECT count(*) FROM ordering.orders")
                .query(Long.class)
                .single();
    }

    private static String ordersPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders";
    }

    private static String addressesPath(UUID account) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/customers/" + account
                + "/addresses";
    }

    private void seedTenancyChannelAndBinding() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'aggregator-entry-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            String code = location.equals(LOCATION) ? "MAIN01" : "MAIN02";
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version, latitude, longitude, coordinate_source)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent',
                        'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", code)
                    .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                    .update();
        }
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('uzum-sandbox-p14', 'MARKETPLACE', 'UZUM_TEZKOR',
                        'https://sandbox.example.uz', false, 'sandbox.example.uz')
                ON CONFLICT (code) DO NOTHING
                """).update();
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :tenantId, 'MARKETPLACE', 'UZUM_TEZKOR', 'uzum-sandbox-p14', 'Uzum Tezkor', 'ACTIVE')
                """).param("id", installationId).param("tenantId", TENANT).update();
        bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (
                    id, tenant_id, installation_id, brand_id, location_id, status, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', :from)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("from", Instant.now().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status,
                    externally_priced, provider_installation_id)
                VALUES (:id, :tenantId, :code, 'AGGREGATOR', 'Uzum Tezkor', 'ACTIVE', true, :installationId)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("code", CHANNEL)
                .param("installationId", installationId)
                .update();
        // A second, ordinary channel, so "this channel is not an aggregator" has something to name.
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status,
                    guest_orders_allowed)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE', false)
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'aggregator entry http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + LOCATION).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", LOCATION)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
