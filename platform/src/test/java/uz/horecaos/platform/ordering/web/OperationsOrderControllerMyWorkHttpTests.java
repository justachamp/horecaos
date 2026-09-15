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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * IA 0.2a, over the real HTTP stack: {@code GET .../orders/my-work/channel-mix}
 * is self-scoped by the token's own subject, never by a request parameter
 * (wave T01).
 *
 * <p>Mirrors {@code OperationsOrderControllerActionCapabilitiesHttpTests}'s own
 * fixture — a stub {@link JwtDecoder}, {@link RoleRegistrySynchronizer}-backed
 * grants, the real {@code CapabilityEnforcementInterceptor} — because the
 * property under test is a request-handling one: what the endpoint does with
 * an {@code actorId} query parameter naming somebody else. That question has
 * no answer below the web layer, where {@code MyWorkQueryServiceTests} already
 * proves the aggregate itself is correct once it is given a subject to read.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsOrderControllerMyWorkHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc400-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc400-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fc400-4000-7000-8000-0000000000c1");

    /** Holds {@code ORDER_READ} at {@code LOCATION} scope — every line cook's own bundle. */
    private static final String OPERATOR_A = "my-work-http-operator-a";

    /** A colleague at the same branch. Never the caller in any test below — only ever named by one. */
    private static final String OPERATOR_B = "my-work-http-operator-b";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the my-work HTTP test");
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

    @SuppressWarnings("NullAway")
    private UUID channelId;

    @SuppressWarnings("NullAway")
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(OPERATOR_A, PlatformRole.LOCATION_STAFF, LOCATION);
    }

    @Test
    @DisplayName("no orders yet: 200 with an empty channel mix, not a denial")
    void emptyIsNotDenied() throws Exception {
        MvcResult result =
                mvc.perform(get(myWorkPath()).with(tokenFor(OPERATOR_A))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"channelMix\":[]");
    }

    @Test
    @DisplayName("only my own orders are counted, never a colleague's at the same branch")
    void onlyMyOwnOrdersCount() throws Exception {
        seedOrder("MINE", OPERATOR_A);
        seedOrder("THEIRS", OPERATOR_B);

        MvcResult result =
                mvc.perform(get(myWorkPath()).with(tokenFor(OPERATOR_A))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"key\":\"WEB\"").contains("\"orders\":1");
        assertThat(body)
                .as("no principal's subject id is ever printed, this operator's own included")
                .doesNotContain(OPERATOR_A)
                .doesNotContain(OPERATOR_B);
    }

    @Test
    @DisplayName("naming my own subject in `actorId` is accepted — the same answer as omitting it")
    void namingMyOwnSubjectIsAccepted() throws Exception {
        seedOrder("MINE", OPERATOR_A);

        MvcResult result = mvc.perform(
                        get(myWorkPath()).queryParam("actorId", OPERATOR_A).with(tokenFor(OPERATOR_A)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"orders\":1");
    }

    @Test
    @DisplayName("naming a colleague's subject in `actorId` is refused with 403, not answered empty")
    void namingAnotherSubjectIsRefused() throws Exception {
        // Present, so a naive implementation that simply ignored the
        // parameter and always read the caller's own orders would still
        // return 200 with data in it -- the point of this test is the status
        // code, not the absence of a result.
        seedOrder("MINE", OPERATOR_A);

        MvcResult result = mvc.perform(
                        get(myWorkPath()).queryParam("actorId", OPERATOR_B).with(tokenFor(OPERATOR_A)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("ORDER_READ, however wide the grant, never authorizes reading another operator's own read")
                .isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .as("the refused subject is never echoed back into the error body")
                .doesNotContain(OPERATOR_B);
    }

    // ------------------------------------------------------------ fixtures

    private static String myWorkPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/my-work"
                + "/channel-mix";
    }

    private void seedOrder(String seed, String createdByActorId) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant at = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'PICKUP', 'UZS', 'ACTIVE', :guest,
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
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    created_at, confirmed_at, created_by_actor_type, created_by_actor_id)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'PICKUP', 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', 'READY', 'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub,
                    :cart, :key, 1, :at, :at, 'USER', :actorId)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("actorId", createdByActorId)
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'my-work-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                        'ACTIVE', 'test-fixture', 'my-work http test', :validFrom)
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
