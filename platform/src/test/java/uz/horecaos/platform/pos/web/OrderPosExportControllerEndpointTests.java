package uz.horecaos.platform.pos.web;

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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code GET/POST .../orders/{orderId}/pos-export[/push]} through the real
 * HTTP stack (ADR 0011, gap map row {@code 1.2i}, wave P42) — second-pass
 * adversarial review, batch 7.
 *
 * <p>{@code OrderPosExportControllerTests} calls {@code
 * controller.forOrder(...)}/{@code controller.push(...)} directly and never
 * through {@code MockMvc}, so neither {@code
 * @RequiresCapability(POS_EXPORT_READ)} nor {@code
 * @RequiresCapability(POS_EXPORT_RESOLVE)} was ever proven wired to the real
 * {@code CapabilityEnforcementInterceptor} — a session lacking the grant being
 * let through would not have been caught by that suite. Mirrors {@code
 * OrderDeliveryControllerEndpointTests}' shape (raw SQL rows rather than a
 * full checkout and confirm flow, since the HTTP/capability/tenant wiring is
 * what this class exists to prove).
 *
 * <p>The cross-tenant tests are the other half: {@code
 * anUnknownOrderIsNotFound} in the direct-call suite only ever tries {@code
 * UUID.randomUUID()} against its own single tenant, so a regression that
 * dropped the {@code tenant_id} predicate from the order lookup -- letting the
 * endpoint find any tenant's order by id alone -- would not be caught by that
 * suite either. Here a second tenant's real order is looked up under the
 * first tenant's own grant, mirroring {@code OperationsOrderController
 * .requireOrderAtLocation}'s own reasoning: an id a client supplies is never
 * trusted without the scope predicate that goes with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPosExportControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb900-a000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb900-a000-7000-8000-0000000000a2");
    private static final UUID LOCATION = UUID.fromString("018fb900-a000-7000-8000-0000000000a3");

    /** A second, unrelated tenant -- its order must never be reachable through the first tenant's grant. */
    private static final UUID TENANT_2 = UUID.fromString("018fb900-a000-7000-8000-0000000000b1");

    private static final UUID BRAND_2 = UUID.fromString("018fb900-a000-7000-8000-0000000000b2");
    private static final UUID LOCATION_2 = UUID.fromString("018fb900-a000-7000-8000-0000000000b3");

    /** Holds both {@code POS_EXPORT_READ} and {@code POS_EXPORT_RESOLVE} at {@code TENANT}. */
    private static final String FULL_ACCESS = "pos-export-http-admin";

    /** Holds {@code POS_EXPORT_READ} but never {@code POS_EXPORT_RESOLVE} at {@code TENANT}. */
    private static final String READ_ONLY = "pos-export-http-support-view";

    /** Holds plenty of other tenant authority but never {@code POS_EXPORT_READ} or {@code POS_EXPORT_RESOLVE}. */
    private static final String NO_GRANT = "pos-export-http-finance";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the pos-export HTTP test");
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
    private UUID channelId2;
    private UUID publicationId2;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE integration.pos_order_exports CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy(TENANT, BRAND, LOCATION, "pos-export-http-1");
        channelId = channelIdSeeded;
        publicationId = publicationIdSeeded;
        seedTenancy(TENANT_2, BRAND_2, LOCATION_2, "pos-export-http-2");
        channelId2 = channelIdSeeded;
        publicationId2 = publicationIdSeeded;

        roleRegistry.synchronize();
        grant(FULL_ACCESS, PlatformRole.TENANT_ADMIN, TENANT);
        grant(READ_ONLY, PlatformRole.SUPPORT_SESSION_VIEW, TENANT);
        grant(NO_GRANT, PlatformRole.TENANT_FINANCE, TENANT);
    }

    // ------------------------------------------------------------------ capability refusal

    @Test
    @DisplayName("POS_EXPORT_READ is required -- a principal without it is refused on the GET")
    void getWithoutPosExportReadIsRefused() throws Exception {
        UUID orderId = seedConfirmedOrder(TENANT, BRAND, LOCATION, channelId, publicationId, "PR-1001");

        MvcResult result = mvc.perform(get(posExportPath(TENANT, orderId)).with(tokenFor(NO_GRANT)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.POS_EXPORT_READ.code());
    }

    @Test
    @DisplayName("POS_EXPORT_RESOLVE is required -- a principal holding only POS_EXPORT_READ is refused on the push")
    void pushWithoutPosExportResolveIsRefused() throws Exception {
        UUID orderId = seedConfirmedOrder(TENANT, BRAND, LOCATION, channelId, publicationId, "PR-1002");

        MvcResult result = mvc.perform(post(posExportPath(TENANT, orderId) + "/push")
                        .with(tokenFor(READ_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"operator retry\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.POS_EXPORT_RESOLVE.code());
    }

    @Test
    @DisplayName("a principal holding POS_EXPORT_READ reads the order's export picture")
    void getWithPosExportReadSucceeds() throws Exception {
        UUID orderId = seedConfirmedOrder(TENANT, BRAND, LOCATION, channelId, publicationId, "PR-1003");

        MvcResult result = mvc.perform(get(posExportPath(TENANT, orderId)).with(tokenFor(FULL_ACCESS)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .as("no POS binding is registered for this fixture, so the affordance is (truthfully) absent")
                .contains("\"posCapable\":false")
                .contains("\"export\":null");
    }

    // ------------------------------------------------------------------ cross-tenant isolation

    @Test
    @DisplayName("an order belonging to another tenant is not found through the GET, not leaked")
    void getForAnOrderAtAnotherTenantIsNotFound() throws Exception {
        UUID foreignOrderId = seedConfirmedOrder(TENANT_2, BRAND_2, LOCATION_2, channelId2, publicationId2, "PR-2001");

        // TENANT's own grant, TENANT's own path -- but the order id names a row
        // that only exists under TENANT_2.
        MvcResult result = mvc.perform(
                        get(posExportPath(TENANT, foreignOrderId)).with(tokenFor(FULL_ACCESS)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("an order belonging to another tenant is not found through the push, not leaked")
    void pushForAnOrderAtAnotherTenantIsNotFound() throws Exception {
        UUID foreignOrderId = seedConfirmedOrder(TENANT_2, BRAND_2, LOCATION_2, channelId2, publicationId2, "PR-2002");

        MvcResult result = mvc.perform(post(posExportPath(TENANT, foreignOrderId) + "/push")
                        .with(tokenFor(FULL_ACCESS))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"operator retry\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ fixtures

    private String posExportPath(UUID tenantId, UUID orderId) {
        return "/api/v1/operations/tenants/" + tenantId + "/orders/" + orderId + "/pos-export";
    }

    private UUID seedConfirmedOrder(
            UUID tenantId, UUID brandId, UUID locationId, UUID channelId, UUID publicationId, String number) {
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
                    status, currency, subtotal_minor, tax_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, confirmed_at, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'PICKUP',
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', 'CONFIRMED',
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
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
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    /** Set by {@link #seedTenancy} for the caller to pick up right after -- avoids a five-field return record. */
    @SuppressWarnings("NullAway")
    private UUID channelIdSeeded;

    @SuppressWarnings("NullAway")
    private UUID publicationIdSeeded;

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

        UUID channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("t", tenantId).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();

        UUID publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("cat", catalogId)
                .update();

        channelIdSeeded = channelId;
        publicationIdSeeded = publicationId;
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :scopeId,
                        'ACTIVE', 'test-fixture', 'pos-export http endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", tenantId)
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
