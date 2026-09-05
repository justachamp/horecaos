package uz.horecaos.platform.payments.web;

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
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentMethod;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The HTTP surface finance reads an order's payment through, and the one write
 * on it (ADR 0013, ADR 0025, ADR 0031, operations-spec/finance.md &sect;8.1).
 *
 * <p>{@link uz.horecaos.platform.payments.application.PaymentCheckoutService}'s
 * own properties -- what a re-presentation may reuse, what each provider surface
 * looks like -- are {@code PaymentCheckoutSurfaceTests}'s job and are not
 * repeated here. What is new and untested until this class is: that {@link
 * uz.horecaos.platform.iam.api.Capability#PAYMENT_INITIATE} actually gates the
 * endpoint, that a staff call with no customer account reaches the same service
 * successfully, and that a real inserted intent round-trips through this
 * controller's own DTOs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsPaymentControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9b20-4000-7000-8000-0000000000c1");

    private static final String OWNER = "order-payment-owner";
    private static final String FINANCE = "order-payment-finance";
    private static final String ADMINISTRATOR = "order-payment-administrator";

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

    @Autowired
    private JdbcPaymentIntentStore intents;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents, payments.payment_attempts, "
                        + "payments.payment_transactions CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void anAdministratorCannotReadAnOrdersPayment() throws Exception {
        UUID orderId = order(3_000_000L);

        MvcResult refused = mvc.perform(get(paymentPath(orderId)).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("payment.read");
    }

    @Test
    void readingAnUnknownOrdersPaymentIsNotFoundNotA500() throws Exception {
        MvcResult result = mvc.perform(get(paymentPath(UUID.randomUUID())).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    void financeSeesACashOrdersLiveIntentAndItsAttemptsAreEmpty() throws Exception {
        UUID orderId = order(12_000L);
        insertCashIntent(orderId);

        MvcResult result =
                mvc.perform(get(paymentPath(orderId)).with(tokenFor(FINANCE))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"tender\":\"CASH\"")
                .contains("\"method\":\"CASH\"")
                .contains("\"status\":\"PENDING\"")
                .contains("\"attempts\":[]")
                // Nothing has ever settled or reversed against a cash intent.
                .contains("\"captured\":{\"amountMinor\":0")
                .contains("\"returned\":{\"amountMinor\":0");
    }

    @Test
    void anAdministratorCannotReIssueAPaymentLink() throws Exception {
        UUID orderId = order(12_000L);
        insertCashIntent(orderId);

        MvcResult refused = mvc.perform(post(paymentPath(orderId) + "/re-presentations")
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reissue-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("payment.initiate");
    }

    /**
     * Cash has no checkout surface: {@code PaymentCheckoutService} refuses it
     * with {@code NOT_PAYABLE_ONLINE} rather than an empty session. The property
     * this proves is that this controller reaches the real service and maps its
     * refusal to Problem Details rather than a 500 -- the success path against a
     * real Click surface is {@code PaymentCheckoutSurfaceTests}'s coverage, not
     * duplicated here.
     */
    @Test
    void reIssuingACashOrdersPaymentIsRefusedAsNotPayableOnline() throws Exception {
        UUID orderId = order(12_000L);
        insertCashIntent(orderId);

        MvcResult refused = mvc.perform(post(paymentPath(orderId) + "/re-presentations")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reissue-cash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString())
                .contains("RESOURCE_CONFLICT")
                .contains("NOT_PAYABLE_ONLINE");
    }

    @Test
    void aMalformedPushRecipientIsAProblemDetailsValidationFailureNotA500() throws Exception {
        UUID orderId = order(12_000L);
        insertCashIntent(orderId);

        MvcResult result = mvc.perform(post(paymentPath(orderId) + "/re-presentations")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reissue-malformed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pushRecipient\":\"not-a-phone\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------------ fixtures

    private static String paymentPath(UUID orderId) {
        return "/api/v1/operations/tenants/" + TENANT + "/orders/" + orderId + "/payment";
    }

    private void insertCashIntent(UUID orderId) {
        intents.insert(new PaymentIntent(
                UUID.randomUUID(),
                TENANT,
                orderId,
                BRAND,
                LOCATION,
                null,
                null,
                PaymentTender.CASH,
                PaymentMethod.CASH,
                null,
                new SomAmount(12_000L, "UZS"),
                PaymentIntentStatus.PENDING,
                CaptureTiming.ON_HANDOVER,
                "idem-" + orderId,
                1,
                Instant.now(),
                null));
    }

    private UUID order(long totalMinor) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        String guestHash = "guest-" + orderId;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        :total, 0, :total, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("publicationId", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, customer_account_id,
                    guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', NULL, :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channelId)
                .param("guest", guestHash)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    guest_reference_hash, fulfillment_mode, acceptance_mode_snapshot,
                    acceptance_policy_id, acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    fee_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    NULL, :guest, 'DELIVERY', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL,
                    'CONFIRMED', 'UZS', :total, 0, 0, :total, :quoteId, 'hash',
                    :publicationId, :cartId, :key, 1, now())
                """)
                .param("id", orderId)
                .param("number", "ORD-" + orderId.toString().substring(0, 8))
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channelId)
                .param("quoteId", quoteId)
                .param("cartId", cartId)
                .param("publicationId", publicationId)
                .param("guest", guestHash)
                .param("total", totalMinor)
                .param("key", "idem-" + orderId)
                .update();

        return orderId;
    }

    private UUID channelId;
    private UUID publicationId;

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'order-payment-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'order payment endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // Backdated rather than the column's own now(): a grant read
                // back through JdbcAuthorizationService.grantsFor compares
                // valid_from against this JVM's Clock.systemUTC(), and under
                // heavy concurrent fork load the container's own wall clock can
                // momentarily skew against it.
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
