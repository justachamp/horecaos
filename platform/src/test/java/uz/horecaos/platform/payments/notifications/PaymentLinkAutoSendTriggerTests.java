package uz.horecaos.platform.payments.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.integration.api.payment.MerchantApiCall;
import uz.horecaos.platform.integration.api.payment.MerchantApiTransport;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.notifications.api.NotificationConfigurationKeys;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.api.PaymentIntentCreated;
import uz.horecaos.platform.payments.application.CapturedMoneyPort;
import uz.horecaos.platform.payments.application.PaymentAttemptService;
import uz.horecaos.platform.payments.application.PaymentCheckoutService;
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentMethod;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.click.ClickMerchantApi;
import uz.horecaos.platform.payments.infrastructure.click.ClickPaymentAdapter;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentBindingResolver;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentBusinessCalendar;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentTransactionStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.RecordingCustomerAlertPort;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link PaymentLinkAutoSendTrigger} (gap map row {@code 10.9d}, first half):
 * {@code notifications.payment_link_auto_send} finally gets a reader.
 *
 * <p>Run against a real database and the real {@link PaymentCheckoutService},
 * the same discipline {@code PaymentCheckoutSurfaceTests} documents for its
 * own suite — the claim under test is that this trigger actually mints a
 * payable Click link through the production checkout path, not a stub of
 * one.
 */
class PaymentLinkAutoSendTriggerTests {

    private static final String UZS = "UZS";
    private static final long AMOUNT_SOM = 12_000L;

    private static final String CLICK_SERVICE_ID = "12345";
    private static final String CLICK_MERCHANT_ID = "9999";
    private static final String CLICK_MERCHANT_USER_ID = "3333";

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID LEGAL_ENTITY = UUID.randomUUID();
    private static final UUID CLICK_BINDING = UUID.randomUUID();
    private static final UUID CLICK_INSTALLATION = UUID.randomUUID();
    private static final UUID INTEGRATION_BINDING = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-22T04:03:11Z"), ZoneOffset.UTC);

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;
    private static TransactionTemplate unitOfWork;

    private JdbcPaymentIntentStore intents;
    private PaymentCheckoutService checkout;
    private RecordingCustomerAlertPort customerAlerts;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the payments schema");
        db = TestDatabase.migrated();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        jdbc = JdbcClient.create(dataSource);
        unitOfWork = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        seedTenantAndMerchantAccount();
        seedOrder();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void wire() {
        jdbc.sql("DELETE FROM payments.payment_transactions").update();
        jdbc.sql("DELETE FROM payments.payment_attempts").update();
        jdbc.sql("DELETE FROM payments.payment_intents").update();

        intents = new JdbcPaymentIntentStore(jdbc);
        JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore(jdbc);
        JdbcPaymentTransactionStore transactions = new JdbcPaymentTransactionStore(jdbc);
        JdbcPaymentBindingResolver bindings = new JdbcPaymentBindingResolver(jdbc);

        ClickPaymentAdapter click =
                new ClickPaymentAdapter(new ClickMerchantApi(new RecordingTransport(), CLOCK), CLOCK);

        PaymentAttemptService attemptService = new PaymentAttemptService(
                intents,
                attempts,
                transactions,
                bindings,
                List.of(click),
                CapturedMoneyPort.NONE,
                unitOfWork,
                event -> {},
                CLOCK);

        checkout = new PaymentCheckoutService(
                intents,
                attempts,
                attemptService,
                bindings,
                new JdbcPaymentBusinessCalendar(jdbc),
                new SeededOrders(),
                CLOCK);

        customerAlerts = new RecordingCustomerAlertPort();
    }

    private PaymentLinkAutoSendTrigger triggerWithAutoSend(boolean enabled) {
        FakeConfigurationResolver configuration = new FakeConfigurationResolver(
                Map.of(NotificationConfigurationKeys.PAYMENT_LINK_AUTO_SEND_CODE, enabled));
        return new PaymentLinkAutoSendTrigger(configuration, checkout, customerAlerts, java.time.Duration.ofHours(2));
    }

    private UUID givenIntent() {
        UUID intentId = UUID.randomUUID();
        intents.insert(new PaymentIntent(
                intentId,
                TENANT,
                ORDER,
                BRAND,
                LOCATION,
                null,
                LEGAL_ENTITY,
                PaymentTender.PROVIDER,
                PaymentMethod.CLICK,
                PaymentProviderType.CLICK,
                new SomAmount(AMOUNT_SOM, UZS),
                PaymentIntentStatus.PENDING,
                CaptureTiming.BEFORE_CONFIRMATION,
                UUID.randomUUID().toString(),
                1,
                CLOCK.instant(),
                null));
        return intentId;
    }

    private PaymentIntentCreated eventFor(UUID intentId) {
        return new PaymentIntentCreated(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                ORDER,
                intentId,
                PaymentProviderType.CLICK,
                ACCOUNT,
                CLOCK.instant());
    }

    @Test
    @DisplayName("the switch on: an actual Click link is minted and the order's own customer is notified")
    void autoSendMintsAndNotifiesWhenTheSwitchIsOn() {
        UUID intentId = givenIntent();

        triggerWithAutoSend(true).onIntentCreated(eventFor(intentId));

        assertThat(customerAlerts.calls()).hasSize(1);
        RecordingCustomerAlertPort.Call call = customerAlerts.calls().get(0);
        assertThat(call.tenantId()).isEqualTo(TENANT);
        assertThat(call.orderId()).isEqualTo(ORDER);
        assertThat(call.templateKey()).isEqualTo(PaymentLinkAutoSendTrigger.PAYMENT_LINK_AUTO_SEND);
        assertThat(call.subjectType()).isEqualTo("Order");
        assertThat(call.variables()).containsOnlyKeys("checkoutUrl");
        assertThat(call.variables().get("checkoutUrl")).startsWith("https://my.click.uz/services/pay/?");

        // The attempt this trigger opened is real and payable, not a
        // side-effect-free stub of one.
        assertThat(jdbc.sql("SELECT count(*) FROM payments.payment_attempts WHERE intent_id = :id")
                        .param("id", intentId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the switch off: no link is minted and nobody is notified")
    void nothingHappensWhenTheSwitchIsOff() {
        UUID intentId = givenIntent();

        triggerWithAutoSend(false).onIntentCreated(eventFor(intentId));

        assertThat(customerAlerts.calls()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM payments.payment_attempts WHERE intent_id = :id")
                        .param("id", intentId)
                        .query(Long.class)
                        .single())
                .as("a switch left off must not have the side effect of opening a payable attempt either")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("re-presenting the same intent does not send a second message")
    void aSecondEventForTheSameOrderCarriesTheSameIdempotencyKey() {
        UUID intentId = givenIntent();
        PaymentLinkAutoSendTrigger trigger = triggerWithAutoSend(true);

        trigger.onIntentCreated(eventFor(intentId));
        trigger.onIntentCreated(eventFor(intentId));

        assertThat(customerAlerts.calls()).hasSize(2);
        assertThat(customerAlerts.calls().get(0).idempotencyKey())
                .isEqualTo(customerAlerts.calls().get(1).idempotencyKey());
    }

    /** The ordering read, answered from the row this suite seeded. */
    private static final class SeededOrders implements OrderDirectory {

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            if (!TENANT.equals(tenantId) || !ORDER.equals(orderId)) {
                return Optional.empty();
            }
            return Optional.of(new OrderSummary(
                    orderId,
                    tenantId,
                    BRAND,
                    LOCATION,
                    "31",
                    ACCOUNT,
                    "guest-hash",
                    "PAYMENT_AUTHORIZING",
                    UZS,
                    AMOUNT_SOM,
                    1));
        }
    }

    /** Never answered: Click's PAYMENT_LINK is a string built with nothing sent anywhere. */
    private static final class RecordingTransport implements MerchantApiTransport {
        @Override
        public ProviderOutcome exchange(MerchantApiCall call) {
            throw new AssertionError("A PAYMENT_LINK presentation must never reach the transport: " + call.path());
        }
    }

    private static void seedTenantAndMerchantAccount() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status)
                VALUES (:id, 'paymentlinkautosend', 'Payment Link Auto-Send LLC', 'Payment Link Auto-Send',
                    'UZS', 'Asia/Tashkent', 'ACTIVE')
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'BRAND1', 'brand-one', 'Brand One', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :tenantId, :brandId, 'LOC1', 'location-one', 'Location One', 'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :tenantId, 'LE-1', 'LE-1 MCHJ', '123456789', 'ACTIVE')
                """).param("id", LEGAL_ENTITY).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type,
                    base_url, is_production, egress_allowlist)
                VALUES ('click-sandbox', 'PAYMENT', 'CLICK', 'https://api.click.uz/v2/merchant', false,
                    'api.click.uz')
                """).update();

        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category, provider_type,
                    environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', 'CLICK', 'click-sandbox', 'Click', 'ACTIVE',
                    'horecaos:test:provider_payment:tenant:click')
                """).param("id", CLICK_INSTALLATION).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", INTEGRATION_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", CLICK_INSTALLATION)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id, provider_type,
                    installation_id, binding_id, merchant_account_reference, merchant_user_reference,
                    merchant_id_reference, secret_reference, callback_path_segment, supports_reversal,
                    supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'CLICK', :installationId, :bindingId, :account,
                    :user, :merchantId, 'horecaos:test:provider_payment:tenant:click', 'click-brandone',
                    true, true, 'ACTIVE', :effectiveFrom)
                """)
                .param("id", CLICK_BINDING)
                .param("tenantId", TENANT)
                .param("legalEntityId", LEGAL_ENTITY)
                .param("installationId", CLICK_INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .param("account", CLICK_SERVICE_ID)
                .param("user", CLICK_MERCHANT_USER_ID)
                .param("merchantId", CLICK_MERCHANT_ID)
                .param("effectiveFrom", java.time.LocalDate.of(2026, 1, 1))
                .update();
    }

    private static void seedOrder() {
        UUID channel = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID cart = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
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
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalog)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor, tax_minor,
                    total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE', :publicationId, 1, 'hash',
                    12000, 0, 12000, now() + interval '1 day')
                """)
                .param("id", quote)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("publicationId", publication)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    guest_reference_hash, fulfillment_mode, currency, status, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'guest-hash', 'DELIVERY', 'UZS',
                    'CHECKOUT_IN_PROGRESS', :quoteId, 'hash', :publicationId, now() + interval '1 day')
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
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key)
                VALUES (
                    :id, '31', :tenantId, :brandId, :locationId, :channelId, 'WEB', 'guest-hash',
                    'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'PAYMENT_AUTHORIZING', 'UZS', 12000, 0, 12000,
                    :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey)
                """)
                .param("id", ORDER)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channel)
                .param("quoteId", quote)
                .param("publicationId", publication)
                .param("cartId", cart)
                .param("idempotencyKey", UUID.randomUUID().toString())
                .update();
    }
}
