package uz.horecaos.platform.fiscal.web;

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
 * The HTTP surface this wave adds an order-number projection to, and the
 * scope enforcement on it (ADR 0025, ADR 0031, operations-spec/finance.md
 * &sect;8.2).
 *
 * <p>{@code FiscalDocumentService}'s own lifecycle -- what turns a document
 * {@code BLOCKED}, what a retry does -- is {@code FiscalDocumentLifecycleTests}'
 * coverage and is not repeated here. What is new and untested until this
 * class is: that {@link FiscalDocumentController.BlockedDocumentResponse#publicOrderNumber()}
 * is resolved through {@code OrderDirectory} on both the worklist and the
 * per-order read, and that {@code fiscal.document.read} genuinely requires
 * {@code TENANT} scope rather than being satisfiable by a narrower grant.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FiscalDocumentControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b30-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b30-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9b30-4000-7000-8000-0000000000c1");
    private static final UUID ENTITY = UUID.fromString("018f9b30-4000-7000-8000-0000000000e1");

    private static final String FINANCE = "fiscal-endpoint-finance";
    private static final String LOCATION_SCOPED = "fiscal-endpoint-location-scoped";

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

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM fiscal.fiscal_documents").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.legal_entities CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
        // A branch manager's own location grant -- real staff hold this, and it
        // must not be enough. ADR 0025 scopes cover downward, never up: a fiscal
        // obligation belongs to a legal entity that cuts across brands, which is
        // exactly why the controller's own doc requires TENANT and not lower.
        grant(LOCATION_SCOPED, PlatformRole.TENANT_FINANCE, "LOCATION", LOCATION);
    }

    @Test
    void theBlockedWorklistNamesTheOrderByItsPublicNumberNotItsUuid() throws Exception {
        UUID orderId = seedOrder("F-101");
        blockedDocument(orderId, "PROVIDER_REPORT_OVERDUE");

        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/fiscal/documents/blocked")
                        .with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .as("a worklist row must be traceable to the order without a second tool")
                .contains("\"publicOrderNumber\":\"F-101\"")
                .contains("\"orderId\":\"" + orderId + "\"");
    }

    @Test
    void perOrderDocumentsAlsoCarryTheOrdersPublicNumber() throws Exception {
        UUID orderId = seedOrder("F-102");
        blockedDocument(orderId, "CLASSIFICATION_MISSING");

        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/fiscal/orders/" + orderId + "/documents")
                        .with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"publicOrderNumber\":\"F-102\"");
    }

    @Test
    void aLocationScopedGrantCannotReadAnOrdersFiscalDocuments() throws Exception {
        UUID orderId = seedOrder("F-103");
        blockedDocument(orderId, "TERMINAL_OFFLINE");

        MvcResult refused = mvc.perform(get("/api/v1/tenants/" + TENANT + "/fiscal/orders/" + orderId + "/documents")
                        .with(tokenFor(LOCATION_SCOPED)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("fiscal.document.read");
    }

    // ------------------------------------------------------------------ fixtures

    private void blockedDocument(UUID orderId, String reasonCode) {
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, legal_entity_id,
                    provider_type, document_type, status, reason_code, reason_note,
                    submitted_at, blocked_at, reporting_deadline_at, created_at, updated_at)
                VALUES (:id, :t, :o, :e, 'PAYME', 'SALE', 'BLOCKED', :reason,
                    'awaiting a person', :submittedAt, :blockedAt, :deadline, :submittedAt, :blockedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("o", orderId)
                .param("e", ENTITY)
                .param("reason", reasonCode)
                .param("submittedAt", NOW.atOffset(ZoneOffset.UTC))
                .param("blockedAt", NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("deadline", NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private UUID seedOrder(String number) {
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
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 50000, 0, 50000,
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
                    status, currency, subtotal_minor, tax_minor, fee_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, version, created_at, confirmed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'AUTO_CONFIRM', 'NONE', 'CONFIRMED', 'UZS', 50000, 0, 0, 50000, :quote,
                    :hash, :pub, :cart, :key, 1, :at, :at)
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
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'fiscal-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
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

        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'FISCAL-LE', 'Fiskal MCHJ', '123456789', 'ACTIVE')
                """).param("id", ENTITY).param("t", TENANT).update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'fiscal document endpoint test', :validFrom)
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
