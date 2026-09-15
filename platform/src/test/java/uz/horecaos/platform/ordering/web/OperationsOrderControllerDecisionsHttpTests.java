package uz.horecaos.platform.ordering.web;

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
 * {@code GET .../orders/{orderId}/decisions} through the real HTTP stack (gap
 * map row 1.2b) — second-pass adversarial review, P11.
 *
 * <p>{@code JdbcOrderStoreApprovalDecisionsTests} proves {@code
 * JdbcOrderStore.decisionsOf}'s own shape only; no test anywhere called
 * {@code OperationsOrderController.decisions} at all, HTTP or direct — unlike
 * {@link OperationsOrderControllerActionCapabilitiesHttpTests}, which covers
 * {@code list}/{@code board}/{@code detail} but says so in its own class
 * doc and goes no further. This class is that endpoint's first test, in
 * {@code decisions}' own real-stack shape (mirroring {@code
 * OperationsOrderControllerActionCapabilitiesHttpTests}): a sibling-location
 * order answers not found (the same {@code requireOrderAtLocation} boundary
 * every order-detail sub-resource shares) and a principal without {@code
 * ORDER_READ} is refused by the real {@code CapabilityEnforcementInterceptor}.
 *
 * <p>{@code timeline} and {@code revisions} — {@code decisions}' two
 * siblings on the same order-detail sub-resource family, declaring the
 * identical {@code @RequiresCapability(ORDER_READ, LOCATION)} — share this
 * exact coverage gap and are not fixed here; see the fix-round notes for
 * this finding.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsOrderControllerDecisionsHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb600-7000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb600-7000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018fb600-7000-7000-8000-0000000000c1");
    private static final UUID LOCATION_B = UUID.fromString("018fb600-7000-7000-8000-0000000000c2");

    /** Holds {@code ORDER_READ} at {@code LOCATION_A} only. */
    private static final String READER = "decisions-http-reader";

    /** Holds nothing at {@code LOCATION_A} -- {@code ORDER_APPROVE} at {@code LOCATION_B} only. */
    private static final String WRONG_LOCATION = "decisions-http-wrong-location";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the decisions HTTP test");
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
        jdbc.sql("TRUNCATE TABLE ordering.approval_decisions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(READER, PlatformRole.LOCATION_STAFF, LOCATION_A);
        grant(WRONG_LOCATION, PlatformRole.LOCATION_STAFF, LOCATION_B);
    }

    @Test
    @DisplayName("a principal holding ORDER_READ at the order's own location reads its decisions")
    void succeedsWithOrderReadAtTheOrdersLocation() throws Exception {
        UUID orderId = seedOrder(LOCATION_A, "DEC-1");
        jdbc.sql("""
                INSERT INTO ordering.approval_decisions (id, tenant_id, order_id, decision_id, action,
                    decision_channel, actor_type, actor_id, reason_code, effective, issued_at)
                VALUES (:id, :t, :orderId, 'click-1', 'REJECT', 'HORECAOS_OPERATIONS', 'USER', 'op-a',
                    'OUT_OF_STOCK', false, now())
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("orderId", orderId)
                .update();

        MvcResult result = mvc.perform(get(decisionsPath(LOCATION_A, orderId)).with(tokenFor(READER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"decisionId\":\"click-1\"");
    }

    @Test
    @DisplayName("a sibling-location order is not found, not leaked across the location boundary")
    void aSiblingLocationOrderIsNotFound() throws Exception {
        UUID orderId = seedOrder(LOCATION_B, "DEC-2");

        MvcResult result = mvc.perform(get(decisionsPath(LOCATION_A, orderId)).with(tokenFor(READER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("ORDER_READ is required at the order's own location -- a grant at a sibling location is refused")
    void withoutOrderReadAtTheLocationTheCallIsRefused() throws Exception {
        UUID orderId = seedOrder(LOCATION_A, "DEC-3");

        MvcResult result = mvc.perform(get(decisionsPath(LOCATION_A, orderId)).with(tokenFor(WRONG_LOCATION)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_READ.code());
    }

    // ------------------------------------------------------------------ fixtures

    private String decisionsPath(UUID locationId, UUID orderId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId + "/orders/" + orderId
                + "/decisions";
    }

    private UUID seedOrder(UUID locationId, String number) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'PICKUP', 'UZS', 'ACTIVE', :guest,
                    now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
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
                .param("loc", locationId)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    created_at, confirmed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'PICKUP', 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', 'READY', 'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub,
                    :cart, :key, 1, :at, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
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

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'decisions-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION_A)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHILONZOR', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION_B)
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
                        'ACTIVE', 'test-fixture', 'decisions http endpoint test', :validFrom)
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
