package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code GET .../orders/{orderId}/delivery} through the real HTTP stack (ADR
 * 0014, gap map rows 1.2e/1.2n/2.1a) — second-pass adversarial review,
 * P11.
 *
 * <p>{@code OrderDeliveryControllerTests} calls {@code controller.delivery(...)}
 * directly and never through {@code MockMvc}, so {@code
 * @RequiresCapability(DELIVERY_PLAN_READ)} was never proven wired to the real
 * {@code CapabilityEnforcementInterceptor} — a session lacking that
 * capability being let through would not have been caught by that suite.
 * Mirrors {@code ReportingControllerCapabilityHttpTests}' and {@code
 * OperationsOrderCompletionHttpTests}' shape: raw SQL rows rather than a full
 * checkout and dispatch flow, since the HTTP/capability wiring is what this
 * class exists to prove, not delivery planning itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderDeliveryControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb400-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb400-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb400-5000-7000-8000-0000000000c1");

    /** Holds {@code DELIVERY_PLAN_READ} at {@code LOCATION} — a dispatcher's own read. */
    private static final String DISPATCHER = "delivery-http-dispatcher";

    /** Holds plenty of other tenant authority but never {@code DELIVERY_PLAN_READ}. */
    private static final String FINANCE = "delivery-http-finance";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the delivery HTTP test");
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

    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_plans CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
    }

    @Test
    @DisplayName("a principal holding DELIVERY_PLAN_READ reads the order's delivery plan")
    void succeedsWithDeliveryPlanRead() throws Exception {
        UUID orderId = seedDeliveryOrder();
        UUID planId = seedDeliveryPlan(orderId);

        MvcResult result = mvc.perform(get(deliveryPath(orderId)).with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"planId\":\"" + planId + "\"");
    }

    @Test
    @DisplayName("DELIVERY_PLAN_READ is required -- a principal without it is refused")
    void withoutDeliveryPlanReadTheCallIsRefused() throws Exception {
        UUID orderId = seedDeliveryOrder();
        seedDeliveryPlan(orderId);

        MvcResult result =
                mvc.perform(get(deliveryPath(orderId)).with(tokenFor(FINANCE))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.DELIVERY_PLAN_READ.code());
    }

    // ------------------------------------------------------------------ fixtures

    private String deliveryPath(UUID orderId) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/"
                + orderId + "/delivery";
    }

    private UUID seedDeliveryOrder() {
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
                .param("number", "OD-HTTP-" + orderId.toString().substring(0, 8))
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

        return orderId;
    }

    /** A minimal, directly-inserted plan -- delivery planning itself is not what this suite proves. */
    private UUID seedDeliveryPlan(UUID orderId) {
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id, order_id,
                    status, currency, customer_delivery_fee_minor, confirmed_at, preparation_seconds,
                    estimated_ready_at, pickup_window_start, pickup_window_end, source_at,
                    latest_assignment_at, branch_zone, version)
                VALUES (:id, :t, :b, :loc, :orderId, 'PLANNED', 'UZS', 10000, :confirmedAt, 900,
                    :readyAt, :pickupStart, :pickupEnd, :sourceAt, :latestAssignmentAt,
                    'Asia/Tashkent', 1)
                """)
                .param("id", planId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("confirmedAt", now.atOffset(ZoneOffset.UTC))
                .param("readyAt", now.plusSeconds(900).atOffset(ZoneOffset.UTC))
                .param("pickupStart", now.plusSeconds(900).atOffset(ZoneOffset.UTC))
                .param("pickupEnd", now.plusSeconds(1_200).atOffset(ZoneOffset.UTC))
                .param("sourceAt", now.atOffset(ZoneOffset.UTC))
                .param("latestAssignmentAt", now.plusSeconds(1_200).atOffset(ZoneOffset.UTC))
                .update();
        return planId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'delivery-http-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'delivery http endpoint test', :validFrom)
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
