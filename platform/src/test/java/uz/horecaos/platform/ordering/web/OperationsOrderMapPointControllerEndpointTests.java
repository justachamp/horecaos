package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * {@code POST .../orders/map-point-reveals} through the real HTTP stack (row {@code 7.10a},
 * ADR 0145 decision 8).
 *
 * <p>{@code OrderMapPointRevealTests} proves what the reveal does; this proves who may ask. The
 * two roles that hold the live courier map and the address reveal hold this, and nothing wider --
 * a kitchen device, a floor operator and a finance clerk all get the same 403, and the 403 leaves
 * no audit fact because nothing was opened.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsOrderMapPointControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb500-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb500-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb500-5000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fb500-5000-7000-8000-0000000000c2");

    private static final String DISPATCHER = "map-points-dispatcher";
    private static final String MANAGER = "map-points-manager";
    private static final String FLOOR_STAFF = "map-points-floor";
    private static final String FINANCE = "map-points-finance";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the map points HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
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

    @Autowired
    @SuppressWarnings("NullAway")
    private FieldProtection protection;

    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_customer_snapshots, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER, LOCATION);
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, LOCATION);
        grant(FLOOR_STAFF, PlatformRole.LOCATION_STAFF, LOCATION);
        grant(FINANCE, PlatformRole.TENANT_FINANCE, LOCATION);
    }

    @Test
    @DisplayName("a dispatcher opens today's pins: points and order numbers, no person, one audit fact")
    void aDispatcherOpensTodaysPins() throws Exception {
        UUID orderId = seedDeliveryOrder("a");

        MvcResult result = reveal(DISPATCHER, LOCATION, "dispatcher-ok", "{\"purpose\":\"Dispatch overview\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        var tree = JSON.readTree(body);
        assertThat(tree.path("points").size()).isEqualTo(1);
        var point = tree.path("points").get(0);
        assertThat(point.path("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(point.path("latitude").asDouble()).isEqualTo(41.311081);
        assertThat(point.path("longitude").asDouble()).isEqualTo(69.240562);
        assertThat(point.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(tree.path("withoutPoint").asInt()).isZero();
        assertThat(tree.path("truncated").asBoolean()).isFalse();
        assertThat(body)
                .as("a map of the day carries no customer identity")
                .doesNotContain("Alisher")
                .doesNotContain("Amir Temur")
                .doesNotContain("customer");

        AuditTrail.Fact fact = AuditTrail.only(jdbc, "order.map_points.revealed");
        assertThat(fact.actorSubject()).isEqualTo(DISPATCHER);
        assertThat(fact.reason()).isEqualTo("Dispatch overview");
        assertThat(fact.after("orders").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the branch manager holds it too")
    void aBranchManagerOpensTodaysPins() throws Exception {
        seedDeliveryOrder("a");

        MvcResult result = reveal(MANAGER, LOCATION, "manager-ok", "{\"purpose\":\"Opening check\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("floor staff and finance are refused with the capability named, and nothing is opened or recorded")
    void everyoneElseIsRefusedAndNothingIsRecorded() throws Exception {
        seedDeliveryOrder("a");

        for (String subject : List.of(FLOOR_STAFF, FINANCE)) {
            MvcResult refused = reveal(subject, LOCATION, "refused-" + subject, "{\"purpose\":\"Curious\"}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("INSUFFICIENT_CAPABILITY")
                    .contains(Capability.ORDER_POINTS_REVEAL.code());
        }
        assertThat(AuditTrail.facts(jdbc, "order.map_points.revealed"))
                .as("a refused call opened nothing, so it records no reveal")
                .isEmpty();
    }

    @Test
    @DisplayName("a grant at one branch does not open another branch's day")
    void aGrantAtOneBranchDoesNotReachAnother() throws Exception {
        MvcResult refused = reveal(DISPATCHER, OTHER_LOCATION, "other-branch", "{\"purpose\":\"Wrong branch\"}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(AuditTrail.facts(jdbc, "order.map_points.revealed")).isEmpty();
    }

    @Test
    @DisplayName("a purpose is required: blank and missing are both refused before anything is opened")
    void aPurposeIsRequired() throws Exception {
        seedDeliveryOrder("a");

        MvcResult blank = reveal(DISPATCHER, LOCATION, "blank-purpose", "{\"purpose\":\"   \"}");
        MvcResult missing = reveal(DISPATCHER, LOCATION, "missing-purpose", "{}");

        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(AuditTrail.facts(jdbc, "order.map_points.revealed")).isEmpty();
    }

    @Test
    @DisplayName("it is a mutation: no Idempotency-Key is refused")
    void anIdempotencyKeyIsRequired() throws Exception {
        MvcResult result = mvc.perform(post(path(LOCATION))
                        .with(tokenFor(DISPATCHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"No key\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    // ------------------------------------------------------------------ fixtures

    private MvcResult reveal(String subject, UUID locationId, String idempotencyKey, String body) throws Exception {
        return mvc.perform(post(path(locationId))
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String path(UUID locationId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId
                + "/orders/map-point-reveals";
    }

    private UUID seedDeliveryOrder(String seed) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'DELIVERY', 'UZS', 'ACTIVE', :guest,
                    now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 20000, 0, 20000,
                    now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, confirmed_at, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', 'CONFIRMED',
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", "MP-" + seed + "-" + orderId.toString().substring(0, 6))
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        DeliveryDestination destination = new DeliveryDestination(
                "Amir Temur 12", "", "Tashkent", "Yunusobod", "", "2", "5", "41", "blue gate", 41.311081, 69.240562);
        String address = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "address_encrypted", orderId),
                        JSON.writeValueAsString(destination))
                .serialize();
        String name = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "display_name_encrypted", orderId),
                        "Alisher Karimov")
                .serialize();
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots
                    (order_id, tenant_id, display_name_encrypted, address_encrypted)
                VALUES (:orderId, :t, :name, :address)
                """)
                .param("orderId", orderId)
                .param("t", TENANT)
                .param("name", name)
                .param("address", address)
                .update();
        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'map-points-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0),
                       (:other, :t, :b, 'NORTH', 'north', 'North', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("other", OTHER_LOCATION)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();
        UUID catalogId = UUID.randomUUID();
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
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'map points http endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", locationId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
