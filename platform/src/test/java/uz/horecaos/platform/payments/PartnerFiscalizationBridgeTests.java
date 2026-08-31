package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort.Outcome;
import uz.horecaos.platform.integration.api.payment.MerchantApiCall;
import uz.horecaos.platform.integration.api.payment.MerchantApiTransport;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.application.PartnerFiscalizationBridge;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentMethod;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.click.ClickFiscalAdapter;
import uz.horecaos.platform.payments.infrastructure.click.ClickMerchantApi;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeFiscalAdapter;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentBindingResolver;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcLegalEntityStore;

/**
 * {@link PartnerFiscalizationBridge}'s own decisions, in isolation from the
 * fake-provider round trip (ADR 0013, ADR 0038).
 *
 * <p>Run against real Postgres, hand-wired like {@code PaymentCheckoutSurfaceTests}
 * and {@code FiscalObligationTests} rather than through Spring: what is under test
 * is the bridge's own branching, not the application context.
 */
class PartnerFiscalizationBridgeTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID CLICK_ENTITY = UUID.randomUUID();
    private static final UUID PAYME_ENTITY = UUID.randomUUID();
    private static final UUID UNBOUND_ENTITY = UUID.randomUUID();
    private static final UUID CLICK_INSTALLATION = UUID.randomUUID();
    private static final UUID PAYME_INSTALLATION = UUID.randomUUID();
    private static final UUID INTEGRATION_BINDING = UUID.randomUUID();

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC);

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    private JdbcFiscalDocumentStore documents;
    private JdbcPaymentIntentStore intents;
    private PaymentFiscalService fiscalService;
    private PaymentBindingResolver bindings;
    private OrderDirectory orders;
    private LegalEntityDirectory legalEntities;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the fiscal bridge tests");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void wire() {
        jdbc.sql("DELETE FROM fiscal.fiscal_documents").update();
        jdbc.sql("DELETE FROM payments.merchant_bindings").update();
        jdbc.sql("DELETE FROM tenant.location_fiscal_assignments").update();
        jdbc.sql("DELETE FROM tenant.legal_entities").update();
        jdbc.sql("DELETE FROM payments.payment_intents").update();
        jdbc.sql("DELETE FROM integration.bindings").update();
        jdbc.sql("DELETE FROM integration.installations").update();
        jdbc.sql("DELETE FROM integration.provider_environments").update();
        jdbc.sql("DELETE FROM ordering.orders").update();
        jdbc.sql("DELETE FROM ordering.carts").update();
        jdbc.sql("DELETE FROM pricing.quotes").update();
        jdbc.sql("DELETE FROM catalog.publications").update();
        jdbc.sql("DELETE FROM catalog.catalogs").update();
        jdbc.sql("DELETE FROM tenant.sales_channels").update();
        jdbc.sql("DELETE FROM tenant.locations").update();
        jdbc.sql("DELETE FROM tenant.brands").update();
        jdbc.sql("DELETE FROM tenant.tenants").update();

        seedTenancy();

        documents = new JdbcFiscalDocumentStore(jdbc);
        intents = new JdbcPaymentIntentStore(jdbc);
        bindings = new JdbcPaymentBindingResolver(jdbc);
        legalEntities = new JdbcLegalEntityStore(jdbc);
        orders = (tenantId, orderId) -> Optional.of(new OrderDirectory.OrderSummary(
                orderId, TENANT, BRAND, LOCATION, "BR-1", null, "guest-hash", "COMPLETED", "UZS", 15_000L, 1));

        ClickFiscalAdapter click = new ClickFiscalAdapter(
                new ClickMerchantApi(refusingTransport(), CLOCK), new JdbcPaymentAttemptStore(jdbc), CLOCK);
        PaymeFiscalAdapter payme = new PaymeFiscalAdapter(intents, CLOCK);
        fiscalService = new PaymentFiscalService(documents, List.of(click, payme));
    }

    // -----------------------------------------------------------------------
    // Blocked / no provider path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a legal entity with no active merchant binding is NO_PROVIDER_PATH, not an error")
    void noActiveBindingIsReportedAsNoProviderPath() {
        // A real legal entity — the fiscal_documents FK requires one — with
        // deliberately no payments.merchant_bindings row of its own.
        seedLegalEntity(UNBOUND_ENTITY, "UNBOUND");
        UUID intentId = seedIntent(PaymentProviderType.CLICK, UNBOUND_ENTITY);
        UUID documentId = seedPendingDocument(intentId);

        PartnerFiscalizationBridge bridge = bridge();
        Outcome outcome = bridge.retry(TENANT, documentId, "idem-1");

        assertThat(outcome).isEqualTo(Outcome.NO_PROVIDER_PATH);
        // Nothing was recorded as though the provider had answered.
        FiscalDocument document = fiscalService.find(TENANT, documentId).orElseThrow();
        assertThat(document.status()).isEqualTo(FiscalStatus.PENDING);
    }

    // -----------------------------------------------------------------------
    // Already issued
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a document already ISSUED is never asked again, and no binding is even resolved")
    void anAlreadyIssuedDocumentIsNeverAskedAgain() {
        seedClickBinding();
        UUID intentId = seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY);
        UUID documentId = seedPendingDocument(intentId);

        fiscalService.attachEvidence(
                TENANT,
                documentId,
                new FiscalDocument.FiscalEvidence(
                        "R-1",
                        "SIGN-1",
                        "TERM-1",
                        "R-1",
                        CLOCK.instant(),
                        "https://ofd.soliq.uz/epi?t=1&r=1&c=1&s=1",
                        null,
                        null),
                "protected-response-1",
                CLOCK.instant());

        // A resolver that fails the test the moment it is asked anything: the
        // whole point of the ALREADY_ISSUED shortcut is that nothing downstream of
        // "the document already holds a receipt" runs at all.
        PaymentBindingResolver refusingResolver = new PaymentBindingResolver() {
            @Override
            public Optional<uz.horecaos.platform.payments.domain.ProviderBinding> resolve(
                    UUID tenantId,
                    UUID legalEntityId,
                    PaymentProviderType providerType,
                    java.time.LocalDate businessDate) {
                throw new AssertionError("an already-ISSUED document must never resolve a binding");
            }

            @Override
            public Optional<uz.horecaos.platform.payments.domain.ProviderBinding> byCallbackSegment(String segment) {
                throw new AssertionError("not used by the bridge");
            }
        };

        PartnerFiscalizationBridge bridge =
                new PartnerFiscalizationBridge(fiscalService, refusingResolver, intents, orders, legalEntities, CLOCK);

        Outcome outcome = bridge.retry(TENANT, documentId, "idem-2");

        assertThat(outcome).isEqualTo(Outcome.ALREADY_ISSUED);
    }

    // -----------------------------------------------------------------------
    // Payme's silence: a missing SetFiscalData is the documented normal case
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a fresh Payme submission is UNCERTAIN, never REJECTED — a missing SetFiscalData is "
            + "the documented normal case")
    void aFreshPaymeSubmissionIsUncertainNotRejected() {
        seedPaymeBinding();
        UUID intentId = seedIntent(PaymentProviderType.PAYME, PAYME_ENTITY);
        UUID documentId = seedPendingDocument(intentId);

        PartnerFiscalizationBridge bridge = bridge();
        Outcome outcome = bridge.retry(TENANT, documentId, "idem-3");

        assertThat(outcome)
                .as("Payme's adapter makes no call and reports SUBMITTED; the bridge must not "
                        + "read that as a failure")
                .isEqualTo(Outcome.UNCERTAIN);

        FiscalDocument document = fiscalService.find(TENANT, documentId).orElseThrow();
        assertThat(document.status())
                .as("still owed, not failed — the sweeper's reporting deadline is what eventually "
                        + "turns real silence into BLOCKED, never this call")
                .isEqualTo(FiscalStatus.SUBMITTED);
    }

    // ------------------------------------------------------------------- helpers

    private PartnerFiscalizationBridge bridge() {
        return new PartnerFiscalizationBridge(fiscalService, bindings, intents, orders, legalEntities, CLOCK);
    }

    /** Fails the test if the fake provider transport is ever reached. */
    private static MerchantApiTransport refusingTransport() {
        return (MerchantApiCall call) -> {
            throw new AssertionError("this test must never reach a provider transport: " + call);
        };
    }

    private UUID seedPendingDocument(UUID paymentIntentId) {
        // The FK from fiscal.fiscal_documents to payments.payment_intents means the
        // intent must already be a real row — see seedIntent, always called first.
        PaymentIntent intent = intents.find(TENANT, paymentIntentId).orElseThrow();
        return fiscalService.openPartnerObligation(intent, CLOCK.instant());
    }

    private UUID seedIntent(PaymentProviderType providerType, UUID legalEntityId) {
        UUID intentId = UUID.randomUUID();
        intents.insert(new PaymentIntent(
                intentId,
                TENANT,
                ORDER,
                BRAND,
                LOCATION,
                null,
                legalEntityId,
                PaymentTender.PROVIDER,
                providerType == PaymentProviderType.CLICK ? PaymentMethod.CLICK : PaymentMethod.PAYME,
                providerType,
                new SomAmount(15_000L, "UZS"),
                PaymentIntentStatus.PENDING,
                CaptureTiming.BEFORE_CONFIRMATION,
                UUID.randomUUID().toString(),
                1,
                CLOCK.instant(),
                null));
        // insert() never writes settled_at (see JdbcPaymentIntentStore); PAID
        // requires one, per ck_payment_intent_settled, so this moves it the same
        // way a real capture would.
        intents.transition(TENANT, intentId, PaymentIntentStatus.PENDING, PaymentIntentStatus.PAID, 1, CLOCK.instant());
        return intentId;
    }

    private static void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status)
                VALUES (:id, 'bridgetests', 'Bridge Tests LLC', 'Bridge Tests', 'UZS',
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

        // The order every intent in this file references. Only what payment_intents
        // itself needs by FK — no channel, catalog, or quote is read by anything
        // under test here.
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
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE', :publicationId, 1,
                    'hash', 15000, 0, 15000, now() + interval '1 day')
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
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'guest-hash', 'DELIVERY',
                    'UZS', 'CHECKOUT_IN_PROGRESS', :quoteId, 'hash', :publicationId,
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
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    confirmed_at, closed_at)
                VALUES (
                    :id, 'BR-1', :tenantId, :brandId, :locationId, :channelId, 'WEB', 'guest-hash',
                    'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'COMPLETED', 'UZS', 15000, 0, 15000,
                    :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey, now(), now())
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

    private void seedClickBinding() {
        seedLegalEntity(CLICK_ENTITY, "CLICKENT");
        seedInstallation(CLICK_INSTALLATION, "CLICK", "click-bridge-tests");
        seedIntegrationBinding(CLICK_INSTALLATION);
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    merchant_user_reference, secret_reference, callback_path_segment,
                    supports_reversal, supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'CLICK', :installationId, :bindingId,
                    'service-1', '4444', 'horecaos:test:provider_payment:tenant:click',
                    'bridge-tests-click', true, true, 'ACTIVE', DATE '2020-01-01')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("legalEntityId", CLICK_ENTITY)
                .param("installationId", CLICK_INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .update();
    }

    private void seedPaymeBinding() {
        seedLegalEntity(PAYME_ENTITY, "PAYMEENT");
        seedInstallation(PAYME_INSTALLATION, "PAYME", "payme-bridge-tests");
        seedIntegrationBinding(PAYME_INSTALLATION);
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    secret_reference, callback_path_segment, supports_reversal,
                    supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'PAYME', :installationId, :bindingId,
                    'cashbox-1', 'horecaos:test:provider_payment:tenant:payme',
                    'bridge-tests-payme', false, true, 'ACTIVE', DATE '2020-01-01')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("legalEntityId", PAYME_ENTITY)
                .param("installationId", PAYME_INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .update();
    }

    private void seedLegalEntity(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin,
                    vat_registered, status)
                VALUES (:id, :tenantId, :code, :name, :tin, false, 'ACTIVE')
                ON CONFLICT DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("name", code + " LLC")
                .param("tin", String.format("%09d", Math.floorMod(id.hashCode(), 1_000_000_000)))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id,
                    location_id, legal_entity_id, effective_from, approved_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :legalEntityId, DATE '2020-01-01',
                    'PartnerFiscalizationBridgeTests')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("legalEntityId", id)
                .update();
    }

    private void seedInstallation(UUID id, String providerType, String environmentCode) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'PAYMENT', :providerType, 'https://example.invalid', false, 'example.invalid')
                ON CONFLICT DO NOTHING
                """)
                .param("code", environmentCode)
                .param("providerType", providerType)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', :providerType, :environmentCode, :name, 'ACTIVE',
                    :secretReference)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("providerType", providerType)
                .param("environmentCode", environmentCode)
                .param("name", providerType + " (bridge tests)")
                .param(
                        "secretReference",
                        "horecaos:test:provider_payment:tenant:" + providerType.toLowerCase(Locale.ROOT))
                .update();
    }

    private void seedIntegrationBinding(UUID installationId) {
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET installation_id = EXCLUDED.installation_id
                """)
                .param("id", INTEGRATION_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .update();
    }
}
