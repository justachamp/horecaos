package uz.horecaos.platform.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingStatus;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.CancelCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.CancellationReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.QuoteOutcome;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.BookOutcome;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.Decision;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.QuoteResult;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryCostSubsidyStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryExceptionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryQuoteStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJournal;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Provider quote-delta confirmation — the Millenium pattern (ADR 0014, gap map
 * row 1.2f), against a real PostgreSQL — {@code uq_quote_request} and the
 * single-winner indexes {@link JdbcSourcingJournal#openPartnerAttempt} leans
 * on are the database's own. Setup mirrors {@code ManualDispatchServiceTests}.
 *
 * <p>{@link ManualExternalBookingService#book} takes no price of its own —
 * only a {@code quoteId} — which is what proves "a price increase cannot be
 * accepted implicitly" on the server rather than only in the dialog: {@link
 * #acceptBooksAtThePersistedQuotePriceAndRecordsTheSubsidy} shows the booked
 * price and the recorded {@code DELIVERY_COST_SUBSIDY} both come from {@code
 * fulfillment.delivery_quotes}, never from anything this test's call to
 * {@code book} supplies.
 */
class ManualExternalBookingServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant CONFIRMED = Instant.parse("2026-09-15T12:00:00Z");
    private static final ActorRef OPERATOR = ActorRef.user("operator-1", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcDeliveryPlanStore planStore;
    private JdbcDeliveryQuoteStore quoteStore;
    private RecordingAudit audit;
    private RecordingRealtimeSignals realtime;
    private RecordingBookingPort bookings;
    private ManualExternalBookingService service;
    private DeliveryPlanningService planning;
    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID binding;
    private int sequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for external booking tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE TABLE
                    fulfillment.delivery_cost_subsidies,
                    fulfillment.delivery_quotes,
                    fulfillment.assignment_attempts,
                    fulfillment.shipments,
                    fulfillment.delivery_plans,
                    fulfillment.delivery_sourcing_jobs,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    integration.bindings,
                    integration.installations,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(CONFIRMED, ZoneOffset.UTC);
        planStore = new JdbcDeliveryPlanStore(jdbc);
        JdbcAssignmentStore assignments = new JdbcAssignmentStore(jdbc);
        quoteStore = new JdbcDeliveryQuoteStore(jdbc, JsonMapper.builder().build());
        SourcingJournal journal = new JdbcSourcingJournal(
                assignments,
                quoteStore,
                new JdbcDeliveryExceptionStore(jdbc),
                new JdbcDeliveryCostSubsidyStore(jdbc),
                planStore);
        audit = new RecordingAudit();
        realtime = new RecordingRealtimeSignals();
        JdbcDispatchBranchStore branchStore = new JdbcDispatchBranchStore(jdbc);

        seedTenancy();
        binding = seedBinding();
        bookings = new RecordingBookingPort(List.of(new PartnerOption(binding, "noor-delivery", false, true)));
        service = new ManualExternalBookingService(
                planStore,
                quoteStore,
                new SingleOrder(),
                branchStore,
                bookings,
                journal,
                unconfigured(),
                audit,
                realtime,
                clock);
        planning = new DeliveryPlanningService(
                new SingleOrder(), planStore, new JdbcSourcingJobStore(jdbc), branchStore, unconfigured(), clock);
    }

    @Test
    @DisplayName("a quote against the customer's own fee reports the delta the Millenium dialog exists to show")
    void quoteReportsTheDeltaAgainstTheCustomerFee() {
        DeliveryPlan plan = openPlan();
        bookings.quoteOutcome = QuoteOutcome.priced(19_000L, "UZS", 300, 900, 4_200, null);

        QuoteResult result = service.quote(TENANT, BRAND, branch, plan.id(), binding);

        assertThat(result.priced()).isTrue();
        DeliveryQuote quote = Objects.requireNonNull(result.quote());
        assertThat(quote.priceMinor()).isEqualTo(19_000L);
        assertThat(result.customerFeeMinor()).isEqualTo(plan.customerDeliveryFeeMinor());
        // 19,000 quoted against a 12,000 customer fee (DeliveryOrderPort.SingleOrder's own figure).
        assertThat(Objects.requireNonNull(quote.priceMinor()) - result.customerFeeMinor())
                .isEqualTo(7_000L);

        // Recorded as evidence exactly like an automated tick would -- the same
        // table, the same write-once contract.
        assertThat(quoteStore.find(TENANT, plan.id(), quote.id())).isPresent();
    }

    @Test
    @DisplayName("accepting books at the price fulfillment.delivery_quotes already recorded, and a price "
            + "above the customer's fee is recognised as the same DELIVERY_COST_SUBSIDY an automated "
            + "booking would have written")
    void acceptBooksAtThePersistedQuotePriceAndRecordsTheSubsidy() {
        DeliveryPlan plan = openPlan();
        bookings.quoteOutcome = QuoteOutcome.priced(19_000L, "UZS", 300, 900, 4_200, null);
        QuoteResult quoted = service.quote(TENANT, BRAND, branch, plan.id(), binding);
        UUID quoteId = Objects.requireNonNull(quoted.quote()).id();
        bookings.bookStatus = BookingStatus.BOOKED;

        BookOutcome outcome = service.book(
                TENANT, BRAND, branch, plan.id(), binding, quoteId, Decision.ACCEPT, "OPERATOR_ACCEPT", OPERATOR);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.abandoned()).isFalse();
        assertThat(outcome.shipmentId()).isNotNull();
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status().name())
                .isEqualTo("ASSIGNED");

        // The partner was asked for exactly the quoted price's own journey --
        // never a figure this test's call to book() supplied, because book()
        // takes no price argument at all.
        assertThat(bookings.booked).hasSize(1);

        long subsidyMinor = jdbc.sql("""
                        SELECT subsidy_amount_minor FROM fulfillment.delivery_cost_subsidies
                        WHERE tenant_id = :tenantId AND delivery_plan_id = :planId
                        """)
                .param("tenantId", TENANT)
                .param("planId", plan.id())
                .query(Long.class)
                .single();
        assertThat(subsidyMinor).isEqualTo(19_000L - plan.customerDeliveryFeeMinor());

        assertThat(audit.facts)
                .extracting(AuditFact::actionCode)
                .containsExactly("fulfillment.dispatch.external-book-accept");
    }

    @Test
    @DisplayName("abandoning a re-quote books nothing and leaves an audit trail of the refusal -- the plan "
            + "and shipment tables are untouched")
    void abandonBooksNothingAndLeavesAnAuditTrail() {
        DeliveryPlan plan = openPlan();
        bookings.quoteOutcome = QuoteOutcome.priced(25_000L, "UZS", null, null, null, null);
        QuoteResult quoted = service.quote(TENANT, BRAND, branch, plan.id(), binding);
        UUID quoteId = Objects.requireNonNull(quoted.quote()).id();

        BookOutcome outcome = service.book(
                TENANT, BRAND, branch, plan.id(), binding, quoteId, Decision.ABANDON, "PRICE_TOO_HIGH", OPERATOR);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.abandoned()).isTrue();
        assertThat(outcome.shipmentId()).isNull();
        assertThat(bookings.booked).as("an abandoned quote is never booked").isEmpty();
        assertThat(planStore.find(TENANT, plan.id()).orElseThrow().status().name())
                .isEqualTo("PLANNED");

        assertThat(audit.facts)
                .extracting(AuditFact::actionCode)
                .containsExactly("fulfillment.dispatch.external-book-abandon");
        assertThat(audit.facts.getFirst().reason()).isEqualTo("PRICE_TOO_HIGH");
    }

    @Test
    @DisplayName("accepting a quote past its own TTL is refused rather than booked at a price that may no "
            + "longer be true -- the operator must re-quote, never reuse a stale price")
    void acceptRefusesAnExpiredQuote() {
        DeliveryPlan plan = openPlan();
        bookings.quoteOutcome = QuoteOutcome.priced(15_000L, "UZS", null, null, null, null);
        QuoteResult quoted = service.quote(TENANT, BRAND, branch, plan.id(), binding);
        UUID quoteId = Objects.requireNonNull(quoted.quote()).id();

        // Force the persisted quote's own expiry into the past -- HORECAOS_POLICY's
        // TTL, not a partner guarantee, which is exactly what usableAt() checks.
        jdbc.sql("UPDATE fulfillment.delivery_quotes SET expires_at = :past WHERE id = :id")
                .param("past", CONFIRMED.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("id", quoteId)
                .update();

        BookOutcome outcome = service.book(
                TENANT, BRAND, branch, plan.id(), binding, quoteId, Decision.ACCEPT, "OPERATOR_ACCEPT", OPERATOR);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).isEqualTo("QUOTE_EXPIRED");
        assertThat(bookings.booked).isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private DeliveryPlan openPlan() {
        UUID orderId = seedDeliveryOrder();
        return planning.open(TENANT, BRAND, branch, orderId, CONFIRMED).orElseThrow();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", TENANT)
                .param("slug", "external-booking-tenant")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
                """)
                .param("id", branch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
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
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private UUID seedBinding() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('noor-test', 'DELIVERY', 'noor-delivery', 'https://noor.test', false, 'noor.test')
                ON CONFLICT (code) DO NOTHING
                """).update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'DELIVERY', 'noor-delivery', 'noor-test', 'noor-delivery',
                        'ACTIVE', :secret)
                """)
                .param("id", installationId)
                .param("tenantId", TENANT)
                .param("secret", "horecaos:test:provider_delivery:tenant:noor-delivery")
                .update();

        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id,
                    location_id, status, priority)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', 100)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .update();
        return bindingId;
    }

    private UUID seedDeliveryOrder() {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String reference = "external-booking-" + sequence;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        50000, 0, 50000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("publicationId", publicationId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :reference, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        :reference, 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'CONFIRMED', 'UZS',
                        50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", orderId)
                .param("number", "EB-" + sequence)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();

        return orderId;
    }

    private static PolicyResolver unconfigured() {
        return new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                return Optional.empty();
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                return Optional.empty();
            }
        };
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }

    private static final class RecordingRealtimeSignals implements RealtimeSignalPublisher {

        private final List<RealtimeSignal> signals = new ArrayList<>();

        @Override
        public void publish(RealtimeSignal signal) {
            signals.add(signal);
        }
    }

    /** Answers whatever quote/book outcome the test configured. Never asked to cancel. */
    private static final class RecordingBookingPort implements ShipmentBookingPort {

        private final List<PartnerOption> options;
        private final List<BookingCommand> booked = new ArrayList<>();
        private QuoteOutcome quoteOutcome = QuoteOutcome.unavailable(QUOTE_NOT_WIRED);
        private BookingStatus bookStatus = BookingStatus.BOOKED;

        RecordingBookingPort(List<PartnerOption> options) {
            this.options = options;
        }

        @Override
        public List<PartnerOption> partners(UUID tenantId, UUID brandId, UUID locationId) {
            return options;
        }

        @Override
        public QuoteOutcome quote(BookingCommand command) {
            return quoteOutcome;
        }

        @Override
        public BookingReceipt book(BookingCommand command) {
            booked.add(command);
            return BookingReceipt.of(
                    bookStatus,
                    command,
                    "noor-delivery",
                    bookStatus == BookingStatus.BOOKED ? "ext-1" : null,
                    null,
                    null);
        }

        @Override
        public CancellationReceipt cancel(CancelCommand command) {
            throw new UnsupportedOperationException("This suite never cancels; only quotes and books");
        }
    }

    /** Answers for whichever order id it is asked about — this suite only ever plans one at a time. */
    private final class SingleOrder implements DeliveryOrderPort {

        @Override
        public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
            return Optional.of(new DeliveryOrder(
                    orderId,
                    "EB-" + sequence,
                    Duration.ofMinutes(15),
                    12_000L,
                    null,
                    "UZS",
                    true,
                    50_000L,
                    new Waypoint(41.325, 69.281, "Home", "Customer", "+998900000002", null, "2", "5", "17"),
                    "Test zone, Home"));
        }
    }
}
