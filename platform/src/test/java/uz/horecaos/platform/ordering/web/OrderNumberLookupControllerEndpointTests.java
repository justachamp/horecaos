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
 * Resolving the number an operator actually has to the order(s) it names
 * (ADR 0019, ADR 0025, ADR 0031) — the read `payments-page` and the fiscal
 * queue both took a raw order id in a text box until this wave.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderNumberLookupControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b40-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b40-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018f9b40-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_B = UUID.fromString("018f9b40-4000-7000-8000-0000000000c2");

    /** A second, unrelated tenant -- proves the tenant_id predicate, not just the count. */
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b40-4000-7000-8000-0000000000a2");

    private static final UUID OTHER_BRAND = UUID.fromString("018f9b40-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9b40-4000-7000-8000-0000000000c3");

    private static final String FINANCE = "order-lookup-finance";
    /** Never granted anything at this tenant -- the plain "nobody said yes" case. */
    private static final String UNGRANTED = "order-lookup-ungranted";
    /**
     * A branch manager's own grant -- real staff hold this, and it must not be
     * enough. The controller requires TENANT scope specifically because a
     * match can span every branch in the tenant (its own class-level javadoc
     * says so); this proves a narrower grant is refused rather than merely
     * "some grant, any grant" being accepted.
     */
    private static final String LOCATION_SCOPED = "order-lookup-location-scoped";

    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");

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
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    private UUID channelId;
    private UUID publicationId;
    private UUID otherChannelId;
    private UUID otherPublicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        seedOtherTenant();
        roleRegistry.synchronize();
        grant(FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
        grant(LOCATION_SCOPED, PlatformRole.TENANT_FINANCE, "LOCATION", LOCATION_A);
    }

    @Test
    void findsTheOneOrderCarryingThisNumber() throws Exception {
        UUID orderId = seedOrder("0007", LOCATION_A, NOW);

        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/orders/by-number")
                        .param("publicOrderNumber", "0007")
                        .with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"count\":1").contains("\"orderId\":\"" + orderId + "\"");
    }

    @Test
    void anUnknownNumberIsAnEmptyListNotA404() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/orders/by-number")
                        .param("publicOrderNumber", "9999")
                        .with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"count\":0")
                .contains("\"matches\":[]");
    }

    /**
     * {@code uq_order_number} is {@code (tenant_id, location_id,
     * public_order_number)}: two branches can hand out "0042" on the same
     * afternoon, and a caller who only has the number must be shown both
     * rather than one chosen for them.
     */
    @Test
    void theSameNumberAtTwoBranchesIsTwoCandidates() throws Exception {
        UUID orderA = seedOrder("0042", LOCATION_A, NOW);
        UUID orderB = seedOrder("0042", LOCATION_B, NOW.plusSeconds(60));

        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/orders/by-number")
                        .param("publicOrderNumber", "0042")
                        .with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // Both genuine branch candidates must be named, not merely two of
        // something -- a duplicated row (a join defect, or a LIMIT/ORDER BY
        // bug repeating the first match) would also satisfy "count":2.
        assertThat(result.getResponse().getContentAsString())
                .contains("\"count\":2")
                .contains("\"orderId\":\"" + orderA + "\"")
                .contains("\"orderId\":\"" + orderB + "\"");
    }

    @Test
    void aPrincipalWithNoGrantCannotResolveAnOrderNumber() throws Exception {
        MvcResult refused = mvc.perform(get("/api/v1/tenants/" + TENANT + "/orders/by-number")
                        .param("publicOrderNumber", "0007")
                        .with(tokenFor(UNGRANTED)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("order.read");
    }

    /**
     * A branch manager's own LOCATION grant must not satisfy a TENANT-scope
     * requirement. {@code ResourceScope.covers} only ever looks downward
     * (TENANT covers LOCATION, never the reverse), and the controller
     * requires TENANT specifically because a match can span every location in
     * the tenant -- see its class-level javadoc. Mirrors
     * {@code FiscalDocumentControllerEndpointTests
     * .aLocationScopedGrantCannotReadAnOrdersFiscalDocuments} for the
     * analogous endpoint added in the same wave.
     */
    @Test
    void aLocationScopedGrantCannotResolveAnOrderNumber() throws Exception {
        seedOrder("0007", LOCATION_A, NOW);

        MvcResult refused = mvc.perform(get("/api/v1/tenants/" + TENANT + "/orders/by-number")
                        .param("publicOrderNumber", "0007")
                        .with(tokenFor(LOCATION_SCOPED)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("order.read");
    }

    /**
     * {@code findByPublicOrderNumber}'s only tenant boundary is {@code
     * WHERE tenant_id = :tenantId AND public_order_number = :number}. This is
     * the negative-space counterpart to {@link #theSameNumberAtTwoBranchesIsTwoCandidates}:
     * it seeds a genuinely colliding number at a second, unrelated tenant and
     * proves it never leaks into a lookup scoped to the first -- the case
     * that would still pass if the {@code tenant_id} predicate were ever
     * dropped or the query swapped for a differently-scoped variant.
     */
    @Test
    void aSecondTenantsOrderWithTheSameNumberIsNeverReturned() throws Exception {
        UUID orderId = seedOrder("0007", LOCATION_A, NOW);
        seedOrderForTenant(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, otherChannelId, otherPublicationId, "0007", NOW);

        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/orders/by-number")
                        .param("publicOrderNumber", "0007")
                        .with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"count\":1")
                .contains("\"orderId\":\"" + orderId + "\"");
    }

    // ------------------------------------------------------------------ fixtures

    private UUID seedOrder(String number, UUID locationId, Instant createdAt) {
        return seedOrderForTenant(TENANT, BRAND, locationId, channelId, publicationId, number, createdAt);
    }

    private UUID seedOrderForTenant(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            UUID publicationId,
            String number,
            Instant createdAt) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'DELIVERY', 'UZS', 'ACTIVE', :guest,
                    now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", tenantId)
                .param("b", brandId)
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
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, fee_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, version, created_at, confirmed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'AUTO_CONFIRM', 'NONE', 'CONFIRMED', 'UZS', 20000, 0, 0, 20000, :quote,
                    :hash, :pub, :cart, :key, 1, :at, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", createdAt.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'order-lookup', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION_A)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("code", "CENTRE")
                .param("slug", "centre")
                .param("name", "Centre")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION_B)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("code", "CHILONZOR")
                .param("slug", "chilonzor")
                .param("name", "Chilonzor")
                .update();

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

    /** A second, wholly unrelated tenant -- exists only to prove it can never leak in. */
    private void seedOtherTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'order-lookup-other', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("t", OTHER_TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_LOCATION)
                .param("t", OTHER_TENANT)
                .param("b", OTHER_BRAND)
                .update();

        otherChannelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", otherChannelId).param("t", OTHER_TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", OTHER_TENANT)
                .param("b", OTHER_BRAND)
                .update();

        otherPublicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", otherPublicationId)
                .param("t", OTHER_TENANT)
                .param("b", OTHER_BRAND)
                .param("cat", catalogId)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'order number lookup endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeType).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * Carries no realm role, so a refusal proves the ADR 0025 grant decided it
     * and not the bootstrap bypass a platform-admin token gets.
     */
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
