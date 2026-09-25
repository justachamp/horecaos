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
import org.jspecify.annotations.Nullable;
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
 * Staff 9.2d, over the real HTTP stack: a manager reading a person's own
 * card can see how many orders that operator created or accepted today —
 * {@code GET .../orders/operators/{subject}/today-counts}, unlike {@code
 * my-work/channel-mix} (self-scoped only), named by any subject a
 * TENANT-scope {@code ORDER_READ} grant is willing to read about.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperatorTodayCountsControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fd500-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd500-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd500-4000-7000-8000-0000000000c1");

    private static final UUID OTHER_TENANT = UUID.fromString("018fd500-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fd500-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fd500-4000-7000-8000-0000000000c2");

    private static final String MANAGER = "today-counts-manager";
    private static final String LOCATION_MANAGER = "today-counts-location-manager";
    private static final String UNGRANTED = "today-counts-ungranted";

    /** The operator whose card is being read — never the caller in any test below. */
    private static final String OPERATOR = "today-counts-operator";

    private static final String COLLEAGUE = "today-counts-colleague";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
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

    @SuppressWarnings("NullAway")
    private UUID otherChannelId;

    @SuppressWarnings("NullAway")
    private UUID otherPublicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy(TENANT, BRAND, LOCATION, "today-counts");
        seedTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, "today-counts-other");
        roleRegistry.synchronize();
        grant(TENANT, MANAGER, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
        grant(TENANT, LOCATION_MANAGER, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);
    }

    @Test
    @DisplayName("nobody created or accepted anything yet: 200 with two zeroes, not a denial")
    void emptyIsNotDenied() throws Exception {
        MvcResult result =
                mvc.perform(get(path(TENANT, OPERATOR)).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"createdCount\":0").contains("\"acceptedCount\":0");
    }

    @Test
    @DisplayName("created and accepted are counted separately, and a colleague's orders never count")
    void createdAndAcceptedCountSeparately() throws Exception {
        // Two the operator created themselves...
        seedOrder(TENANT, "C1", OPERATOR, null, Instant.now());
        seedOrder(TENANT, "C2", OPERATOR, null, Instant.now());
        // ...one an aggregator order the operator approved by hand, never created...
        seedOrder(TENANT, "A1", COLLEAGUE, OPERATOR, Instant.now());
        // ...and one a colleague's, naming this operator nowhere.
        seedOrder(TENANT, "X1", COLLEAGUE, COLLEAGUE, Instant.now());

        MvcResult result =
                mvc.perform(get(path(TENANT, OPERATOR)).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"createdCount\":2").contains("\"acceptedCount\":1");
    }

    @Test
    @DisplayName("an order from two business days ago does not count as today's")
    void onlyTodayCounts() throws Exception {
        seedOrder(TENANT, "OLD", OPERATOR, OPERATOR, Instant.now().minus(Duration.ofDays(2)));
        seedOrder(TENANT, "NEW", OPERATOR, OPERATOR, Instant.now());

        MvcResult result =
                mvc.perform(get(path(TENANT, OPERATOR)).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"createdCount\":1").contains("\"acceptedCount\":1");
    }

    @Test
    @DisplayName("a second tenant's orders for the same subject never leak into this tenant's count")
    void aSecondTenantNeverLeaksIn() throws Exception {
        seedOrder(OTHER_TENANT, "OTHER", OPERATOR, OPERATOR, Instant.now());

        MvcResult result =
                mvc.perform(get(path(TENANT, OPERATOR)).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"createdCount\":0").contains("\"acceptedCount\":0");
    }

    @Test
    @DisplayName("a principal with no grant at all is refused")
    void aPrincipalWithNoGrantIsRefused() throws Exception {
        MvcResult refused = mvc.perform(get(path(TENANT, OPERATOR)).with(tokenFor(UNGRANTED)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("order.read");
    }

    /**
     * A branch manager's own LOCATION grant must not satisfy the TENANT-scope
     * requirement — the same boundary {@code
     * OrderNumberLookupControllerEndpointTests
     * .aLocationScopedGrantCannotResolveAnOrderNumber} proves for the sibling
     * tenant-wide read, and for the identical reason: a staff person's own
     * card has no single location to scope the grant to.
     */
    @Test
    @DisplayName("a LOCATION-scoped grant cannot read a tenant-wide operator count")
    void aLocationScopedGrantIsRefused() throws Exception {
        MvcResult refused = mvc.perform(get(path(TENANT, OPERATOR)).with(tokenFor(LOCATION_MANAGER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("order.read");
    }

    // ------------------------------------------------------------------ fixtures

    private static String path(UUID tenantId, String subject) {
        return "/api/v1/tenants/" + tenantId + "/orders/operators/" + subject + "/today-counts";
    }

    private void seedOrder(
            UUID tenantId,
            String number,
            String createdByActorId,
            @Nullable String acceptedByActorId,
            Instant createdAt) {
        UUID brandId = tenantId.equals(TENANT) ? BRAND : OTHER_BRAND;
        UUID locationId = tenantId.equals(TENANT) ? LOCATION : OTHER_LOCATION;
        UUID thisChannelId = tenantId.equals(TENANT) ? channelId : otherChannelId;
        UUID thisPublicationId = tenantId.equals(TENANT) ? publicationId : otherPublicationId;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'PICKUP', 'UZS', 'ACTIVE', :guest,
                    now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", thisChannelId)
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
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("pub", thisPublicationId)
                .param("hash", "hash-" + orderId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    created_at, confirmed_at, created_by_actor_type, created_by_actor_id,
                    accepted_by_actor_type, accepted_by_actor_id, accepted_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'PICKUP', 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', 'READY', 'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub,
                    :cart, :key, 1, :at, :at, 'USER', :createdBy, :acceptedType, :acceptedBy, :acceptedAt)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", thisChannelId)
                .param("guest", "guest-" + orderId)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", thisPublicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", createdAt.atOffset(ZoneOffset.UTC))
                .param("createdBy", createdByActorId)
                .param("acceptedType", acceptedByActorId == null ? null : "USER")
                .param("acceptedBy", acceptedByActorId)
                .param("acceptedAt", acceptedByActorId == null ? null : createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy(UUID tenantId, UUID brandId, UUID locationId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();

        UUID thisChannelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", thisChannelId).param("t", tenantId).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();

        UUID thisPublicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", thisPublicationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("cat", catalogId)
                .update();

        if (tenantId.equals(TENANT)) {
            channelId = thisChannelId;
            publicationId = thisPublicationId;
        } else {
            otherChannelId = thisChannelId;
            otherPublicationId = thisPublicationId;
        }
    }

    private void grant(UUID tenantId, String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'operator today counts endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeType).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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
