package uz.horecaos.platform.partner.web;

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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code GET .../marketplace/orders/{orderId}/handover-challenge} through the
 * real HTTP stack — the one backend addition wave P09 (gap map {@code 1.2m})
 * makes, over the read {@link uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore#findChallengeForOrder}
 * that {@code bypass} already used. {@code MarketplaceOperationsController}
 * had no HTTP-level test at all before this class; {@code
 * MarketplaceChannelTests} exercises {@code verify}/{@code bypass} only as
 * plain service calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplaceHandoverChallengeHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb400-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb400-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb400-4000-7000-8000-0000000000c1");

    /**
     * Holds {@code ORDER_READ} at {@code TENANT} scope — exactly what {@code
     * handoverChallenge} declares, and no narrower grant would cover it (ADR
     * 0025: a broader scope covers a narrower one, never the reverse).
     */
    private static final String READER = "handover-challenge-http-reader";

    /** Holds nothing at all. */
    private static final String STRANGER = "handover-challenge-http-stranger";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the handover HTTP test");
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
        jdbc.sql("TRUNCATE TABLE ordering.order_handover_challenges CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(READER, PlatformRole.LOCATION_STAFF);
    }

    @Test
    @DisplayName("a pending challenge reads back its type, status and remaining attempts — never the expected value")
    void aPendingChallengeReadsBack() throws Exception {
        UUID orderId = seedOrder("4001");
        seedChallenge(orderId, "CODE", "PENDING", 2, 5, "peppered-hash-should-never-appear");

        MvcResult result =
                mvc.perform(get(challengePath(orderId)).with(tokenFor(READER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"type\":\"CODE\"")
                .contains("\"status\":\"PENDING\"")
                .contains("\"attempts\":2")
                .contains("\"maxAttempts\":5")
                .contains("\"attemptsRemaining\":3")
                .as("the peppered hash must never leave the database")
                .doesNotContain("peppered-hash-should-never-appear");
    }

    @Test
    @DisplayName("no challenge for this order is a 404, not an empty 200")
    void noChallengeIsNotFound() throws Exception {
        UUID orderId = seedOrder("4002");

        MvcResult result =
                mvc.perform(get(challengePath(orderId)).with(tokenFor(READER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("ORDER_READ is required — a principal with no grant at all is refused")
    void withoutOrderReadTheCallIsRefused() throws Exception {
        UUID orderId = seedOrder("4003");
        seedChallenge(orderId, "CODE", "PENDING", 0, 5, "hash");

        MvcResult result = mvc.perform(get(challengePath(orderId)).with(tokenFor(STRANGER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ fixtures

    private String challengePath(UUID orderId) {
        return "/api/v1/operations/tenants/" + TENANT + "/marketplace/orders/" + orderId + "/handover-challenge";
    }

    private void seedChallenge(
            UUID orderId, String type, String status, int attempts, int maxAttempts, String expectedValueHash) {
        jdbc.sql("""
                INSERT INTO ordering.order_handover_challenges
                    (id, tenant_id, order_id, challenge_type, issued_by, expected_value_hash,
                     attempts, max_attempts, status, verified_at, verified_by)
                VALUES (:id, :t, :orderId, :type, 'HORECAOS', :hash, :attempts, :maxAttempts, :status,
                    CASE WHEN :status = 'VERIFIED' THEN now() ELSE NULL END,
                    CASE WHEN :status = 'VERIFIED' THEN 'expo-1' ELSE NULL END)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("orderId", orderId)
                .param("type", type)
                .param("hash", expectedValueHash)
                .param("attempts", attempts)
                .param("maxAttempts", maxAttempts)
                .param("status", status)
                .update();
    }

    private UUID seedOrder(String number) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'DELIVERY', 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
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
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', 'FULFILLING',
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", number)
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

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'handover-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :scopeId,
                        'ACTIVE', 'test-fixture', 'handover challenge http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + TENANT).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", TENANT)
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
