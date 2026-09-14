package uz.horecaos.platform.partner.web;

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
import uz.horecaos.platform.partner.domain.HandoverCodeHasher;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code POST .../marketplace/orders/{orderId}/handover-verifications} and
 * {@code .../handover-bypasses} through the real HTTP stack (wave T02, gap map
 * row 2.3).
 *
 * <p>Both endpoints and the service behind them ({@code
 * HandoverVerificationService}) predate this wave and are already exercised as
 * plain service calls by {@code MarketplaceChannelTests} — attempt consumption,
 * the peppered-hash compare, and the bypass's reason/audit requirement are
 * proven there. What was missing, and what this class adds, is the HTTP-layer
 * property those calls cannot see: that {@code verify} is actually gated by
 * {@link uz.horecaos.platform.iam.api.Capability#MARKETPLACE_HANDOVER_VERIFY}
 * (this wave's own capability — the endpoint held the broader {@code
 * ORDER_ADVANCE} as a placeholder before it) and {@code bypass} by {@link
 * uz.horecaos.platform.iam.api.Capability#MARKETPLACE_HANDOVER_BYPASS}, each
 * refused to a principal who does not hold it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplaceHandoverVerificationHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb400-4000-7000-8000-0000000000d1");
    private static final UUID BRAND = UUID.fromString("018fb400-4000-7000-8000-0000000000d2");
    private static final UUID LOCATION = UUID.fromString("018fb400-4000-7000-8000-0000000000d3");

    /** Holds {@code MARKETPLACE_HANDOVER_VERIFY} (granted {@code location-manager} at tenant scope) and nothing else. */
    private static final String EXPO = "handover-verify-http-expo";

    /** Holds {@code MARKETPLACE_HANDOVER_BYPASS} (granted {@code tenant-admin} at tenant scope) — and, with it, verify too. */
    private static final String SUPERVISOR = "handover-verify-http-supervisor";

    /** Holds nothing at all. */
    private static final String STRANGER = "handover-verify-http-stranger";

    private static final String CODE = "4417";

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

    @Autowired
    @SuppressWarnings("NullAway")
    private HandoverCodeHasher hasher;

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
        grant(EXPO, PlatformRole.LOCATION_MANAGER);
        grant(SUPERVISOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    @DisplayName("the correct code consumes one attempt and settles the challenge, verified")
    void theCorrectCodeVerifiesAndSettles() throws Exception {
        UUID orderId = seedOrder("5001");
        seedChallenge(orderId, 0, 3, CODE);

        MvcResult result = mvc.perform(post(verifyPath(orderId))
                        .with(tokenFor(EXPO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"verified\":true")
                .contains("\"status\":\"VERIFIED\"")
                .as("one attempt was consumed on the way to the match")
                .contains("\"attemptsRemaining\":2");

        assertThat(jdbc.sql("SELECT status, attempts FROM ordering.order_handover_challenges WHERE order_id = :id")
                        .param("id", orderId)
                        .query((row, n) -> List.of(row.getString(1), String.valueOf(row.getInt(2))))
                        .single())
                .containsExactly("VERIFIED", "1");
    }

    @Test
    @DisplayName("a wrong code consumes an attempt and reports attempts remaining, without settling")
    void aWrongCodeConsumesAnAttemptAndReportsWhatIsLeft() throws Exception {
        UUID orderId = seedOrder("5002");
        seedChallenge(orderId, 0, 3, CODE);

        MvcResult result = mvc.perform(post(verifyPath(orderId))
                        .with(tokenFor(EXPO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"code\":\"0000\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"verified\":false")
                .contains("\"status\":\"PENDING\"")
                .contains("\"attemptsRemaining\":2")
                .as("the response never says how wrong the guess was, or what the code is")
                .doesNotContain(CODE);

        assertThat(jdbc.sql("SELECT status, attempts FROM ordering.order_handover_challenges WHERE order_id = :id")
                        .param("id", orderId)
                        .query((row, n) -> List.of(row.getString(1), String.valueOf(row.getInt(2))))
                        .single())
                .as("an attempt was consumed even though it did not match")
                .containsExactly("PENDING", "1");
    }

    @Test
    @DisplayName("MARKETPLACE_HANDOVER_VERIFY is required — a principal with no grant at all is refused")
    void verifyingWithoutTheCapabilityIsRefused() throws Exception {
        UUID orderId = seedOrder("5003");
        seedChallenge(orderId, 0, 3, CODE);

        MvcResult result = mvc.perform(post(verifyPath(orderId))
                        .with(tokenFor(STRANGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT attempts FROM ordering.order_handover_challenges WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Integer.class)
                        .single())
                .as("a refused call must not have consumed an attempt")
                .isZero();
    }

    @Test
    @DisplayName("the supervisor bypass settles the challenge and requires MARKETPLACE_HANDOVER_BYPASS")
    void theBypassSettlesTheChallengeAndNeedsItsOwnCapability() throws Exception {
        UUID orderId = seedOrder("5004");
        seedChallenge(orderId, 3, 3, CODE);

        // EXPO holds MARKETPLACE_HANDOVER_VERIFY but not the bypass — the two
        // capabilities are deliberately separate, and this is that separation
        // enforced, not merely declared.
        MvcResult refused = mvc.perform(post(bypassPath(orderId))
                        .with(tokenFor(EXPO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"reasonCode\":\"COURIER_APP_OFFLINE\",\"supervisorName\":\"Aziza\"}"))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);

        MvcResult ok = mvc.perform(post(bypassPath(orderId))
                        .with(tokenFor(SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"reasonCode\":\"COURIER_APP_OFFLINE\",\"supervisorName\":\"Aziza\"}"))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(204);

        assertThat(jdbc.sql("SELECT status, bypass_reason_code FROM ordering.order_handover_challenges "
                                + "WHERE order_id = :id")
                        .param("id", orderId)
                        .query((row, n) -> List.of(row.getString(1), row.getString(2)))
                        .single())
                .as("available after attempts are exhausted, per the endpoint's own doc")
                .containsExactly("BYPASSED", "COURIER_APP_OFFLINE");
    }

    @Test
    @DisplayName("a bypass with a blank reason is rejected before it ever reaches the service")
    void aBlankBypassReasonIsRejected() throws Exception {
        UUID orderId = seedOrder("5005");
        seedChallenge(orderId, 0, 3, CODE);

        MvcResult result = mvc.perform(post(bypassPath(orderId))
                        .with(tokenFor(SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"reasonCode\":\"\",\"supervisorName\":\"Aziza\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT status FROM ordering.order_handover_challenges WHERE order_id = :id")
                        .param("id", orderId)
                        .query(String.class)
                        .single())
                .as("the challenge must not have settled on a request the validator refused")
                .isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ fixtures

    private String verifyPath(UUID orderId) {
        return "/api/v1/operations/tenants/" + TENANT + "/marketplace/orders/" + orderId + "/handover-verifications";
    }

    private String bypassPath(UUID orderId) {
        return "/api/v1/operations/tenants/" + TENANT + "/marketplace/orders/" + orderId + "/handover-bypasses";
    }

    private void seedChallenge(UUID orderId, int attempts, int maxAttempts, String code) {
        jdbc.sql("""
                INSERT INTO ordering.order_handover_challenges
                    (id, tenant_id, order_id, challenge_type, issued_by, expected_value_hash,
                     attempts, max_attempts, status)
                VALUES (:id, :t, :orderId, 'CODE', 'HORECAOS', :hash, :attempts, :maxAttempts, 'PENDING')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("orderId", orderId)
                .param("hash", hasher.hash(orderId, code))
                .param("attempts", attempts)
                .param("maxAttempts", maxAttempts)
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
                VALUES (:id, 'handover-verify-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                VALUES (:id, :t, 'AGG', 'AGGREGATOR', 'Aggregator', 'ACTIVE')
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
                VALUES (:id, :t, :b, :cat, 'AGG', 'PUBLISHED', 'hash', now())
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
                        'ACTIVE', 'test-fixture', 'handover verification http test', :validFrom)
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
