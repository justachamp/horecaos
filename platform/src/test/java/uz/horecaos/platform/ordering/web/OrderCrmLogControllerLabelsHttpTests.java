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
import java.util.stream.Collectors;
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
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map row 1.1 through the real HTTP stack: {@code POST
 * .../orders/crm-log/labels}, the board's own Клиент column — customer name
 * in full, phone masked, batched by {@code orderId} for one already-fetched
 * page. Mirrors {@link OperationsOrderControllerCourierAndAttributionHttpTests}'
 * own style and fixture shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCrmLogControllerLabelsHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc100-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc100-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fc100-4000-7000-8000-0000000000c1");

    /** Holds {@code ORDER_READ} at {@code TENANT} scope — the capability this endpoint requires. */
    private static final String TENANT_READER = "crm-log-labels-tenant-reader";

    /** Holds {@code ORDER_READ} only at {@code LOCATION} scope — can see the board, refused here. */
    private static final String LOCATION_ONLY_READER = "crm-log-labels-location-reader";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the CRM log labels HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // The labels endpoint decrypts through EnvelopeFieldProtection (ADR 0029) --
        // not supplied by application-local.yml for tests on purpose; every test
        // that touches protected data registers it, the same as
        // OperationsOrderControllerAmendmentsHttpTests does for CHANGE_CONTACT.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
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
    private FieldProtection protection;

    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.order_customer_snapshots, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(TENANT_READER, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
        grant(LOCATION_ONLY_READER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
    }

    @Test
    @DisplayName("gap map 1.1: returns the decrypted name in full and the phone masked, for an account order")
    void returnsNameInFullAndPhoneMasked() throws Exception {
        UUID orderId = seedOrder("6001", CUSTOMER());
        seedSnapshot(orderId, "Alisher Karimov", "+998901234567");

        String body = postLabels(List.of(orderId), TENANT_READER);

        assertThat(body).contains("\"orderId\":\"" + orderId + "\"");
        assertThat(body).contains("\"customerType\":\"ACCOUNT\"");
        assertThat(body).contains("\"customerName\":\"Alisher Karimov\"");
        assertThat(body).contains("\"customerPhone\":\"+998 90 ••• •• 67\"");
        // The plaintext phone never reaches the wire.
        assertThat(body).doesNotContain("+998901234567");
    }

    @Test
    @DisplayName("gap map 1.1: a guest order carries no customer name")
    void guestOrderCarriesNoCustomerName() throws Exception {
        UUID orderId = seedOrder("6002", null);

        String body = postLabels(List.of(orderId), TENANT_READER);

        assertThat(body).contains("\"orderId\":\"" + orderId + "\"");
        assertThat(body).contains("\"customerType\":\"GUEST\"");
        assertThat(body).contains("\"customerName\":null");
    }

    @Test
    @DisplayName("gap map 1.1: an orderId this tenant does not own is simply absent, not refused")
    void unknownOrderIdIsAbsentNotRefused() throws Exception {
        UUID knownOrder = seedOrder("6003", null);
        UUID unknownOrder = UUID.randomUUID();

        String body = postLabels(List.of(knownOrder, unknownOrder), TENANT_READER);

        assertThat(body).contains(knownOrder.toString());
        assertThat(body).doesNotContain(unknownOrder.toString());
    }

    @Test
    @DisplayName(
            "gap map 1.1: a caller with only LOCATION-scope ORDER_READ is refused -- this endpoint is TENANT-scoped, matching GET .../orders/crm-log")
    void locationScopeOnlyIsRefused() throws Exception {
        UUID orderId = seedOrder("6004", null);

        MvcResult result = mvc.perform(post(labelsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "crm-log-labels-location-only")
                        .content("{\"orderIds\":[\"" + orderId + "\"]}")
                        .with(tokenFor(LOCATION_ONLY_READER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("gap map 1.1: an empty orderIds list is refused rather than silently answering an empty page")
    void emptyOrderIdsIsRefused() throws Exception {
        MvcResult result = mvc.perform(post(labelsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "crm-log-labels-empty")
                        .content("{\"orderIds\":[]}")
                        .with(tokenFor(TENANT_READER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    // --------------------------------------------------------------- helpers

    private String postLabels(List<UUID> orderIds, String subject) throws Exception {
        String idsJson = orderIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        MvcResult result = mvc.perform(post(labelsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "crm-log-labels-" + UUID.randomUUID())
                        .content("{\"orderIds\":[" + idsJson + "]}")
                        .with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("labels status").isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private static String labelsPath() {
        return "/api/v1/tenants/" + TENANT + "/orders/crm-log/labels";
    }

    private static UUID CUSTOMER() {
        return UUID.fromString("018fc100-4000-7000-8000-0000000000d1");
    }

    private UUID seedOrder(String number, @Nullable UUID customerAccountId) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, :cust, 'DELIVERY', 'UZS', 'ACTIVE', :guest,
                    now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", customerAccountId)
                .param("guest", customerAccountId == null ? "guest-" + orderId : null)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor, tax_minor,
                    total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 20000, 0, 20000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, customer_account_id, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :cust, :guest, 'DELIVERY', 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', 'RECEIVED', 'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub,
                    :cart, :key, 1, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", customerAccountId)
                .param("guest", customerAccountId == null ? "guest-" + orderId : null)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    private void seedSnapshot(UUID orderId, String name, String phone) {
        String nameCipher = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "display_name_encrypted", orderId),
                        name)
                .serialize();
        String phoneCipher = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "contact_encrypted", orderId),
                        phone)
                .serialize();
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots (order_id, tenant_id, display_name_encrypted, contact_encrypted)
                VALUES (:orderId, :t, :name, :phone)
                """)
                .param("orderId", orderId)
                .param("t", TENANT)
                .param("name", nameCipher)
                .param("phone", phoneCipher)
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'crm-log-labels-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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

        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER()).param("t", TENANT).update();

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

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'crm-log labels http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
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
