package uz.horecaos.platform.payments.click;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort;
import uz.horecaos.platform.fiscal.application.FiscalObligationSweeper;
import uz.horecaos.platform.payments.application.PartnerFiscalizationBridge;
import uz.horecaos.platform.payments.application.PaymentCheckoutService;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.infrastructure.click.fake.FakeClickHttpProvider;
import uz.horecaos.platform.payments.infrastructure.click.fake.FakeCustomerPaymentService;
import uz.horecaos.platform.payments.infrastructure.click.fake.FakeCustomerPaymentService.FakePaymentResult;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The whole fake-provider story, told once, against real Postgres and the real
 * application context (ADR 0007, ADR 0013, ADR 0038).
 *
 * <p>Seed an entity and a CLICK binding pointed at {@link FakeClickHttpProvider}
 * (SQL fixtures, like {@code PaymentCheckoutSurfaceTests}) &rarr; open a payment
 * session through the real {@link PaymentCheckoutService} &rarr; a fake customer
 * pays through the real {@code ClickShopApiController}'s processor
 * ({@link FakeCustomerPaymentService}) &rarr; attempt {@code CAPTURED}, intent
 * {@code PAID}, order {@code CONFIRMED}, projection {@code CAPTURED} — all from the
 * one real webhook call, the same {@code PaymentCaptured} event firing the real
 * {@code PaymentCaptureConfirmationTrigger} and {@code PaymentProjectionTrigger}
 * &rarr; the order completes &rarr; the fiscal obligation opens &rarr; {@link
 * FiscalObligationSweeper} resolves it through {@link PartnerFiscalizationBridge},
 * which calls the real {@code ClickFiscalAdapter}, which calls the real fake
 * provider's {@code submit_items} and reads back {@code ofd_data} &rarr; the fiscal
 * document row carries the parsed {@code ofd.soliq.uz} evidence.
 *
 * <p>A full {@link SpringBootTest} rather than {@code PaymentCheckoutSurfaceTests}'
 * hand-wired style, deliberately: this is the one test meant to prove the fake
 * provider exercises the real Camel route, {@code PaymentGateway}, and ADR 0028
 * secret resolution, none of which a hand-wired {@code RecordingTransport}-style
 * test would ever touch. The default {@code MOCK} web environment, not {@code NONE}
 * — Spring Security's {@code HttpSecurity} bean, which {@code SecurityConfiguration}
 * needs to exist at all, is only registered inside a servlet web application
 * context — but no HTTP call is actually made in this test: every surface under
 * test, including the SHOP API webhook, is called as a plain bean method, exactly
 * as {@code ClickShopApiCallbackTests} already calls {@code
 * ClickShopApiController.prepare}/{@code .complete} directly.
 */
@SpringBootTest
@ActiveProfiles("local")
class ClickFakeProviderRoundTripTests {

    private static final long AMOUNT_SOM = 15_000L;
    private static final String CLICK_SECRET = "round-trip-test-click-secret";
    private static final String CLICK_SERVICE_ID = "roundtrip-service-1";
    private static final String CLICK_MERCHANT_USER_ID = "5555";

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID LEGAL_ENTITY = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID INTEGRATION_BINDING = UUID.randomUUID();
    private static final UUID MERCHANT_BINDING = UUID.randomUUID();

    // NullAway does not recognise @DynamicPropertySource (see #properties below) as a
    // field initializer the way it does @BeforeAll/@BeforeEach; `database` is always
    // set there before any @Test method runs.
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle database;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the round-trip test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        database = TestDatabase.migrated();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // The ADR 0028 EnvironmentSecretResolver reads horecaos.secrets.{category}.{owner}.{id},
        // ignoring the reference's environment segment — see EnvironmentSecretResolver.propertyNameFor.
        registry.add("horecaos.secrets.provider_payment.tenant.click", () -> CLICK_SECRET);
        // Ephemeral: this JVM may share a host with another agent's own fake provider.
        registry.add("horecaos.fake-providers.click.port", () -> "0");
        registry.add("horecaos.fake-providers.click.secret", () -> CLICK_SECRET);
        // The sweeper's methods are called directly (see #theWholeStory) rather
        // than waited on; its own @Scheduled wall clock is pushed out so a tick
        // cannot land mid-@BeforeEach TRUNCATE and log a spurious failure that has
        // nothing to do with this test. The bean itself must stay present — it is
        // what is under test — so this delays the schedule rather than disabling it.
        registry.add("horecaos.fiscal.obligation-opener.initial-delay", () -> "PT1H");
        registry.add("horecaos.fiscal.obligation-opener.submit-initial-delay", () -> "PT1H");
        registry.add("horecaos.fiscal.reporting-sweeper.initial-delay", () -> "PT1H");
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PaymentCheckoutService checkout;

    @Autowired
    private FakeCustomerPaymentService fakeCustomer;

    @Autowired
    private FakeClickHttpProvider fakeProvider;

    @Autowired
    private JdbcPaymentIntentStore intents;

    @Autowired
    private JdbcPaymentAttemptStore attempts;

    @Autowired
    private FiscalObligationSweeper sweeper;

    @Autowired
    private PaymentFiscalService fiscalDocuments;

    @Autowired
    private PartnerFiscalizationPort partnerFiscalization;

    private UUID orderId;
    private UUID intentId;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM fiscal.fiscal_documents").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_transactions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_attempts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.merchant_bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.location_fiscal_assignments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.legal_entities CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        // Confirms this bridge, and not FiscalPortConfiguration's NOT_WIRED
        // stand-in, is what the fiscal module actually resolved.
        assertThat(partnerFiscalization).isInstanceOf(PartnerFiscalizationBridge.class);
        assertThat(partnerFiscalization.isWired()).isTrue();

        seedTenancy();
        seedClickProviderPointedAtTheFake();
        seedLegalEntityAndBinding();
        orderId = seedOrder();
        intentId = seedIntent(orderId);
    }

    @Test
    @DisplayName("checkout, a fake CLICK payment, and fiscalization through the real fake provider "
            + "and the real bridge, end to end")
    void theWholeStory() {
        // ---------------------------------------------------------- checkout
        var session = checkout.openOrRePresent(TENANT, orderId, ACCOUNT, PresentationRequest.link());
        assertThat(session.checkoutUrl()).startsWith("https://my.click.uz/services/pay/?");

        // ------------------------------------------------- the fake customer pays
        FakePaymentResult paid = fakeCustomer.payOpenClickAttempt(TENANT, orderId);
        assertThat(paid.paymentId()).isNotBlank();

        PaymentAttempt attempt = attempts.find(TENANT, paid.attemptId()).orElseThrow();
        assertThat(attempt.status())
                .as("the real ClickShopApiController processor captured the attempt")
                .isEqualTo(PaymentAttemptStatus.CAPTURED);

        PaymentIntent intent = intents.find(TENANT, intentId).orElseThrow();
        assertThat(intent.status()).isEqualTo(PaymentIntentStatus.PAID);

        String orderStatus = jdbc.sql("SELECT status FROM ordering.orders WHERE tenant_id = :t AND id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(String.class)
                .single();
        assertThat(orderStatus)
                .as("PaymentCaptureConfirmationTrigger, listening for the real PaymentCaptured event")
                .isEqualTo("CONFIRMED");

        String projection = jdbc.sql(
                        "SELECT payment_status_projection FROM ordering.orders WHERE tenant_id = :t AND id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(String.class)
                .single();
        assertThat(projection)
                .as("PaymentProjectionTrigger, listening for the same event")
                .isEqualTo("CAPTURED");

        // -------------------------------------------------------- complete the order
        // Driven directly, matching FiscalObligationTests' own fixture shape: the
        // full kitchen/fulfillment path to COMPLETED is not this story's to drive.
        jdbc.sql("UPDATE ordering.orders SET status = 'COMPLETED', closed_at = now() "
                        + "WHERE tenant_id = :t AND id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .update();

        // ------------------------------------------- the sweeper resolves it, for real
        // Called directly rather than waited on @Scheduled's wall clock, per
        // FiscalObligationTests' own convention for exercising the same methods.
        sweeper.openObligations();
        sweeper.submitCapturedObligations();

        List<FiscalDocument> documents = fiscalDocuments.forOrder(TENANT, orderId);
        assertThat(documents).hasSize(1);
        FiscalDocument document = documents.getFirst();

        assertThat(document.status())
                .as("submit_items answered error_code 0 and the read-back found a receipt")
                .isEqualTo(FiscalStatus.ISSUED);

        FiscalDocument.FiscalEvidence evidence = document.fiscalEvidence().orElseThrow();
        assertThat(evidence.receiptUrl()).startsWith("https://ofd.soliq.uz/epi?t=");
        assertThat(evidence.terminalId()).isEqualTo("FAKE-TERMINAL-" + CLICK_SERVICE_ID);
        assertThat(evidence.fiscalSign()).startsWith("FAKESIGN");
        assertThat(evidence.externalReceiptId()).isNotBlank();
        assertThat(evidence.registeredAt()).isNotNull();
    }

    // ------------------------------------------------------------------- fixtures

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status)
                VALUES (:id, 'clickroundtrip', 'Click Round Trip LLC', 'Click Round Trip', 'UZS',
                    'Asia/Tashkent', 'ACTIVE')
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'BRAND1', 'brand-one', 'Brand One', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status)
                VALUES (:id, :tenantId, :brandId, 'LOC1', 'location-one', 'Location One',
                    'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private void seedClickProviderPointedAtTheFake() {
        int port = fakeProvider.port();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('click-roundtrip-fake', 'PAYMENT', 'CLICK', :baseUrl, false, 'localhost')
                """).param("baseUrl", "http://localhost:" + port).update();

        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', 'CLICK', 'click-roundtrip-fake', 'Click (fake)',
                    'ACTIVE', :secretReference)
                """)
                .param("id", INSTALLATION)
                .param("tenantId", TENANT)
                .param("secretReference", "horecaos:test:provider_payment:tenant:click")
                .update();

        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", INTEGRATION_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();
    }

    private void seedLegalEntityAndBinding() {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin,
                    vat_registered, status)
                VALUES (:id, :tenantId, 'ROUNDTRIP', 'Round Trip Foods LLC', '111222333', false,
                    'ACTIVE')
                """).param("id", LEGAL_ENTITY).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id,
                    location_id, legal_entity_id, effective_from, approved_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :legalEntityId, DATE '2020-01-01',
                    'ClickFakeProviderRoundTripTests')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("legalEntityId", LEGAL_ENTITY)
                .update();

        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    merchant_user_reference, merchant_id_reference, secret_reference,
                    callback_path_segment, supports_reversal, supports_partner_fiscalization,
                    status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'CLICK', :installationId, :bindingId,
                    :serviceId, :merchantUserId, :merchantId, :secretReference, :segment, true,
                    true, 'ACTIVE', DATE '2020-01-01')
                """)
                .param("id", MERCHANT_BINDING)
                .param("tenantId", TENANT)
                .param("legalEntityId", LEGAL_ENTITY)
                .param("installationId", INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .param("serviceId", CLICK_SERVICE_ID)
                .param("merchantUserId", CLICK_MERCHANT_USER_ID)
                .param("merchantId", "7777")
                .param("secretReference", "horecaos:test:provider_payment:tenant:click")
                .param("segment", "click-roundtrip-1")
                .update();
    }

    private UUID seedOrder() {
        UUID orderId = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID cart = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channel).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MENU', 'Menu', 'ACTIVE')
                """)
                .param("id", catalog)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalog)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE', :publicationId,
                    1, 'hash', :amount, 0, :amount, now() + interval '1 day')
                """)
                .param("id", quote)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("publicationId", publication)
                .param("amount", AMOUNT_SOM)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    guest_reference_hash, fulfillment_mode, currency, status, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'guest-hash',
                    'DELIVERY', 'UZS', 'CHECKOUT_IN_PROGRESS', :quoteId, 'hash', :publicationId,
                    now() + interval '1 day')
                """)
                .param("id", cart)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channel)
                .param("quoteId", quote)
                .param("publicationId", publication)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (
                    id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status,
                    payment_status_projection, currency,
                    subtotal_minor, tax_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key)
                VALUES (
                    :id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    'guest-hash', 'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'PAYMENT_AUTHORIZING',
                    'AUTHORIZED', 'UZS',
                    :amount, 0, :amount, :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey)
                """)
                .param("id", orderId)
                .param("number", "RT-1")
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channel)
                .param("quoteId", quote)
                .param("publicationId", publication)
                .param("cartId", cart)
                .param("amount", AMOUNT_SOM)
                .param("idempotencyKey", UUID.randomUUID().toString())
                .update();

        return orderId;
    }

    private UUID seedIntent(UUID orderId) {
        UUID intentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.payment_intents (id, tenant_id, order_id, brand_id,
                    location_id, legal_entity_id, tender, payment_method_code, provider_type,
                    requested_amount_minor, currency, status, capture_timing, idempotency_key,
                    version, created_at)
                VALUES (:id, :tenantId, :orderId, :brandId, :locationId, :legalEntityId,
                    'PROVIDER', 'CLICK', 'CLICK', :amount, 'UZS', 'PENDING', 'BEFORE_CONFIRMATION',
                    :idempotencyKey, 1, now())
                """)
                .param("id", intentId)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("legalEntityId", LEGAL_ENTITY)
                .param("amount", AMOUNT_SOM)
                .param("idempotencyKey", UUID.randomUUID().toString())
                .update();
        return intentId;
    }

    /** Avoids contacting Keycloak; nothing here goes through a security filter chain. */
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
