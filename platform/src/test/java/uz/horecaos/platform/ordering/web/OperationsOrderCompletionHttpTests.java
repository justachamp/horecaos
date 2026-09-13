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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.application.OrderOutcomeReasonService;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.domain.OutcomeSystemCategory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * {@code POST .../{orderId}/completion} through the real HTTP stack (wave
 * P09, gap map {@code 1.2j}) — no test exercised this endpoint at all before
 * this wave, only {@code OrderOutcomeService.complete} directly ({@code
 * OrderAmendmentAndOutcomeTests}). Mirrors {@code
 * OperationsOrderControllerActionCapabilitiesHttpTests}' seeding style: raw
 * SQL rows rather than a full checkout, since the HTTP dispatch and the
 * capability/JSON wiring are what this class exists to prove, not checkout.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsOrderCompletionHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb300-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb300-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb300-4000-7000-8000-0000000000c1");

    /** Holds {@code ORDER_ADVANCE} at {@code LOCATION} only — what {@code POST .../completion} declares. */
    private static final String ADVANCER = "completion-http-advancer";

    /** Holds {@code ORDER_READ} only — never {@code ORDER_ADVANCE}. */
    private static final String READ_ONLY = "completion-http-read-only";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the completion HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
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
    private OrderOutcomeReasonService reasons;

    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_outcome_reasons CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(ADVANCER, PlatformRole.LOCATION_STAFF, LOCATION);
        grant(READ_ONLY, PlatformRole.TENANT_FINANCE, LOCATION);
    }

    @Test
    @DisplayName("completing a pickup order with no reasonId records the mode's own category, no dialog needed")
    void completingWithNoReasonRecordsTheModeDefault() throws Exception {
        UUID orderId = seedOrder(FulfillmentMode.PICKUP, OrderStatus.READY, "3001");

        MvcResult result = mvc.perform(post(completionPath(orderId))
                        .with(tokenFor(ADVANCER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("status").isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"COMPLETED\"");
        assertThat(jdbc.sql("SELECT status FROM ordering.orders WHERE id = :id")
                        .param("id", orderId)
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
        // No reasonId was supplied, so OrderStateService.advance falls back to
        // its own default outcome (OutcomeSystemCategory.defaultCompletionFor)
        // rather than a registry reason: the row exists and names the right
        // category for a pickup order, but carries no reasonId — a delivery
        // order would default to DELIVERED_OWN_COURIER here regardless of who
        // actually delivered it, which is exactly the gap the order detail
        // pane (wave P09) closes by resolving a real reasonId first whenever
        // the registry has one to offer.
        assertThat(jdbc.sql(
                        "SELECT system_category, reason_id FROM ordering.order_outcomes WHERE order_id = :id")
                        .param("id", orderId)
                        .query((row, n) -> new Object[] {row.getString("system_category"), row.getObject("reason_id")})
                        .single())
                .containsExactly("COLLECTED_BY_CUSTOMER", null);
    }

    @Test
    @DisplayName(
            "completing a delivery order with the partner-courier reason records that reason, not the own-courier default")
    void completingWithAValidReasonRecordsIt() throws Exception {
        UUID reasonId = reasons.create(
                TENANT,
                new OrderOutcomeReasonService.CreateReason(
                        OutcomeReasonKind.COMPLETION,
                        OutcomeSystemCategory.DELIVERED_PARTNER_COURIER,
                        "Доставлен сторонней службой",
                        null,
                        null,
                        null,
                        List.of(FulfillmentMode.DELIVERY),
                        Map.of("ru", "Доставлено", "uz-Latn", "Yetkazildi", "en", "Delivered")));
        UUID orderId = seedOrder(FulfillmentMode.DELIVERY, OrderStatus.FULFILLING, "3002");

        MvcResult result = mvc.perform(post(completionPath(orderId))
                        .with(tokenFor(ADVANCER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonId\":\"" + reasonId + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("status").isEqualTo(200);
        assertThat(jdbc.sql("SELECT system_category FROM ordering.order_outcomes WHERE order_id = :id")
                        .param("id", orderId)
                        .query(String.class)
                        .single())
                .as("the console must never book DELIVERED_OWN_COURIER for an order it was told went by a partner")
                .isEqualTo("DELIVERED_PARTNER_COURIER");
    }

    @Test
    @DisplayName("a reason valid only for delivery is refused on a pickup order")
    void aReasonInvalidForTheModeIsRefused() throws Exception {
        UUID reasonId = reasons.create(
                TENANT,
                new OrderOutcomeReasonService.CreateReason(
                        OutcomeReasonKind.COMPLETION,
                        OutcomeSystemCategory.DELIVERED_PARTNER_COURIER,
                        "Доставлен сторонней службой",
                        null,
                        null,
                        null,
                        List.of(FulfillmentMode.DELIVERY),
                        Map.of("ru", "Доставлено", "uz-Latn", "Yetkazildi", "en", "Delivered")));
        UUID orderId = seedOrder(FulfillmentMode.PICKUP, OrderStatus.READY, "3003");

        MvcResult result = mvc.perform(post(completionPath(orderId))
                        .with(tokenFor(ADVANCER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonId\":\"" + reasonId + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("status").isEqualTo(400);
        assertThat(jdbc.sql("SELECT status FROM ordering.orders WHERE id = :id")
                        .param("id", orderId)
                        .query(String.class)
                        .single())
                .as("the order never moved")
                .isEqualTo("READY");
    }

    @Test
    @DisplayName("ORDER_ADVANCE is required — a read-only principal is refused")
    void withoutOrderAdvanceTheCallIsRefused() throws Exception {
        UUID orderId = seedOrder(FulfillmentMode.PICKUP, OrderStatus.READY, "3004");

        MvcResult result = mvc.perform(post(completionPath(orderId))
                        .with(tokenFor(READ_ONLY))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT status FROM ordering.orders WHERE id = :id")
                        .param("id", orderId)
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
    }

    // ------------------------------------------------------------------ fixtures

    private String completionPath(UUID orderId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/" + orderId
                + "/completion";
    }

    private UUID seedOrder(FulfillmentMode mode, OrderStatus status, String number) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, :mode, 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("mode", mode.name())
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
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, :mode,
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', :status,
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("mode", mode.name())
                .param("status", status.name())
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'completion-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
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
                        'ACTIVE', 'test-fixture', 'completion http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", "LOCATION")
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
