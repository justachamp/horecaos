package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.context.annotation.Primary;
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
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code POST .../orders/{orderId}/external-courier} through the real HTTP
 * stack (gap map rows 1.2e/2.1c) — the order-keyed action the order detail
 * pane and the KDS pass now share, over {@code ManualExternalBookingService}
 * unchanged (gap map row 1.2f). Mirrors {@code
 * OrderDeliveryControllerEndpointTests}' shape: raw SQL rows for tenancy, the
 * order and its plan, since the HTTP/capability/idempotency wiring is what
 * this class exists to prove, not delivery planning itself.
 *
 * <p>{@link FakeShipmentBookingPort} is "the fake provider the codebase
 * already has" for this seam — the same {@code ShipmentBookingPort} test
 * double shape {@code ManualExternalBookingServiceTests.RecordingBookingPort}
 * already uses, wired here as the real {@code @Primary} bean so a real
 * {@code POST} never reaches a real courier partner. {@link
 * FakeDeliveryOrderPort} stands in for the ADR 0029 decrypt the same way
 * that suite's own {@code SingleOrder} does, so this class needs no
 * encrypted customer snapshot row to prove the HTTP/capability seam.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderDeliveryExternalCourierHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb500-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb500-6000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb500-6000-7000-8000-0000000000c1");
    private static final UUID BINDING = UUID.fromString("018fb500-6000-7000-8000-0000000000d1");

    /** Holds {@code DELIVERY_MANUAL_ASSIGN} (and {@code DELIVERY_PLAN_READ}) at {@code LOCATION}, same as the dispatch board's own dispatcher. */
    private static final String DISPATCHER = "external-courier-http-dispatcher";

    /** Holds plenty of other tenant authority but never {@code DELIVERY_MANUAL_ASSIGN}. */
    private static final String FINANCE = "external-courier-http-finance";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the external courier HTTP test");
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

    @Autowired
    @SuppressWarnings("NullAway")
    private FakeShipmentBookingPort fakeBookings;

    private UUID channelId;
    private UUID publicationId;
    private int sequence;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_plans CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        seedBinding();
        roleRegistry.synchronize();
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
        grant(FINANCE, PlatformRole.TENANT_FINANCE);

        fakeBookings.options = List.of(new ShipmentBookingPort.PartnerOption(BINDING, "FAKE_PROVIDER", true, true));
        fakeBookings.quoteOutcome = ShipmentBookingPort.QuoteOutcome.priced(12_000L, "UZS", 300, 900, 3_000, 200);
        fakeBookings.bookStatus = ShipmentBookingPort.BookingStatus.BOOKED;
    }

    @Test
    @DisplayName("prices one partner (phase QUOTED) without booking anything")
    void quotesWithoutBooking() throws Exception {
        UUID orderId = seedDeliveryOrder();
        seedDeliveryPlan(orderId);

        MvcResult result = mvc.perform(post(externalCourierPath(orderId))
                        .with(tokenFor(DISPATCHER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"" + BINDING + "\",\"reasonCode\":\"HTTP_TEST\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"phase\":\"QUOTED\"")
                .contains("\"priced\":true")
                .contains("\"priceMinor\":12000");
        assertThat(fakeBookings.booked).isEmpty();
    }

    @Test
    @DisplayName(
            "accepts a quote at the price fulfillment.delivery_quotes recorded (phase BOOKED), never a client-supplied one")
    void acceptsAtThePersistedPrice() throws Exception {
        UUID orderId = seedDeliveryOrder();
        seedDeliveryPlan(orderId);

        MvcResult quoted = mvc.perform(post(externalCourierPath(orderId))
                        .with(tokenFor(DISPATCHER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"" + BINDING + "\",\"reasonCode\":\"HTTP_TEST\"}"))
                .andReturn();
        String quoteId = extractQuoteId(quoted.getResponse().getContentAsString());

        MvcResult booked = mvc.perform(post(externalCourierPath(orderId))
                        .with(tokenFor(DISPATCHER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"" + BINDING + "\",\"quoteId\":\"" + quoteId
                                + "\",\"decision\":\"ACCEPT\",\"reasonCode\":\"HTTP_TEST_ACCEPT\"}"))
                .andReturn();

        assertThat(booked.getResponse().getStatus()).isEqualTo(200);
        String body = booked.getResponse().getContentAsString();
        assertThat(body).contains("\"phase\":\"BOOKED\"").contains("\"applied\":true");
        // The one property the Millenium pattern exists to guarantee: the fake
        // provider was asked to book, and the request it received carries the
        // order's own command shape, never a price this test's request body
        // supplied (this endpoint's request has no price field to begin with).
        assertThat(fakeBookings.booked).hasSize(1);
    }

    @Test
    @DisplayName("DELIVERY_MANUAL_ASSIGN is required -- a principal without it is refused, and nothing is booked")
    void withoutDeliveryManualAssignTheCallIsRefused() throws Exception {
        UUID orderId = seedDeliveryOrder();
        seedDeliveryPlan(orderId);

        MvcResult result = mvc.perform(post(externalCourierPath(orderId))
                        .with(tokenFor(FINANCE))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"" + BINDING + "\",\"reasonCode\":\"HTTP_TEST\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.DELIVERY_MANUAL_ASSIGN.code());
        assertThat(fakeBookings.booked).isEmpty();
    }

    @Test
    @DisplayName("ADR 0031: a mutation with no Idempotency-Key header is refused before anything runs")
    void anIdempotencyKeyIsRequired() throws Exception {
        UUID orderId = seedDeliveryOrder();
        seedDeliveryPlan(orderId);

        MvcResult result = mvc.perform(post(externalCourierPath(orderId))
                        .with(tokenFor(DISPATCHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"" + BINDING + "\",\"reasonCode\":\"HTTP_TEST\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    // ------------------------------------------------------------------ fixtures

    private String externalCourierPath(UUID orderId) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/"
                + orderId + "/external-courier";
    }

    private static String extractQuoteId(String json) {
        int index = json.indexOf("\"quoteId\":\"");
        assertThat(index)
                .as("the QUOTED response names its own quoteId: %s", json)
                .isPositive();
        int start = index + "\"quoteId\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    private UUID seedDeliveryOrder() {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

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
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 20000, 0, 20000,
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
                    status, currency, subtotal_minor, tax_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, confirmed_at, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', 'CONFIRMED',
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", "EC-HTTP-" + sequence)
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
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    /** A minimal, directly-inserted plan -- delivery planning itself is not what this suite proves. */
    private UUID seedDeliveryPlan(UUID orderId) {
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id, order_id,
                    status, currency, customer_delivery_fee_minor, confirmed_at, preparation_seconds,
                    estimated_ready_at, pickup_window_start, pickup_window_end, source_at,
                    latest_assignment_at, branch_zone, version)
                VALUES (:id, :t, :b, :loc, :orderId, 'PLANNED', 'UZS', 12000, :confirmedAt, 900,
                    :readyAt, :pickupStart, :pickupEnd, :sourceAt, :latestAssignmentAt,
                    'Asia/Tashkent', 1)
                """)
                .param("id", planId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("confirmedAt", now.atOffset(ZoneOffset.UTC))
                .param("readyAt", now.plusSeconds(900).atOffset(ZoneOffset.UTC))
                .param("pickupStart", now.plusSeconds(900).atOffset(ZoneOffset.UTC))
                .param("pickupEnd", now.plusSeconds(1_200).atOffset(ZoneOffset.UTC))
                .param("sourceAt", now.atOffset(ZoneOffset.UTC))
                .param("latestAssignmentAt", now.plusSeconds(1_200).atOffset(ZoneOffset.UTC))
                .update();
        return planId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'external-courier-http-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        // latitude/longitude/coordinate_source are required -- `DispatchBranch
        // .origin()` refuses an absent coordinate loudly (`BranchOrigin.of`),
        // which `ManualExternalBookingService.quote`'s own `branch.asWaypoint()`
        // call reaches on every request this suite makes.
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0,
                    41.311081, 69.240562, 'MERCHANT_PIN')
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();

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

    /**
     * {@code fulfillment.delivery_quotes.provider_binding_id} carries a real
     * FK to {@code integration.bindings} regardless of which {@code
     * ShipmentBookingPort} answers -- {@link FakeShipmentBookingPort} stands
     * in for the provider call, never for the database's own referential
     * integrity, so {@code BINDING} must be a real row here even though its
     * provider fields are never read by anything this suite exercises.
     */
    private void seedBinding() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('fake-provider-test', 'DELIVERY', 'FAKE_PROVIDER', 'https://fake.test', false, 'fake.test')
                ON CONFLICT (code) DO NOTHING
                """).update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :t, 'DELIVERY', 'FAKE_PROVIDER', 'fake-provider-test', 'FAKE_PROVIDER',
                        'ACTIVE', :secret)
                """)
                .param("id", installationId)
                .param("t", TENANT)
                .param("secret", "horecaos:test:provider_delivery:tenant:fake-provider")
                .update();

        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id,
                    location_id, status, priority)
                VALUES (:id, :t, :installationId, :b, :loc, 'ACTIVE', 100)
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("installationId", installationId)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'external courier http endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + LOCATION).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", LOCATION)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubBeans {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        @Bean
        @Primary
        FakeShipmentBookingPort fakeShipmentBookingPort() {
            return new FakeShipmentBookingPort();
        }

        @Bean
        @Primary
        DeliveryOrderPort fakeDeliveryOrderPort() {
            return new FakeDeliveryOrderPort();
        }
    }

    /**
     * The fake provider this suite books against -- the same shape {@code
     * ManualExternalBookingServiceTests.RecordingBookingPort} already uses,
     * wired here as the real Spring bean so a real {@code POST} to this
     * endpoint never reaches Yandex or Noor. {@code @Primary} and package-
     * visible so each test can steer its price/outcome before making its
     * call, exactly like that suite's own field-mutation style.
     */
    static final class FakeShipmentBookingPort implements ShipmentBookingPort {

        volatile List<PartnerOption> options = List.of();
        volatile QuoteOutcome quoteOutcome = QuoteOutcome.unavailable("NOT_CONFIGURED");
        volatile BookingStatus bookStatus = BookingStatus.BOOKED;
        final List<BookingCommand> booked = new CopyOnWriteArrayList<>();

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
                    "FAKE_PROVIDER",
                    bookStatus == BookingStatus.BOOKED ? "ext-ref-" + booked.size() : null,
                    null,
                    null);
        }

        @Override
        public CancellationReceipt cancel(CancelCommand command) {
            throw new UnsupportedOperationException("This suite never cancels; only quotes and books");
        }
    }

    /** Stands in for the ADR 0029 decrypt (`JdbcDeliveryOrderPort`) -- answers for whichever order id it is asked about, the same "one plan at a time" shape `ManualExternalBookingServiceTests.SingleOrder` uses. */
    static final class FakeDeliveryOrderPort implements DeliveryOrderPort {

        @Override
        public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
            return Optional.of(new DeliveryOrder(
                    orderId,
                    "EC-HTTP-" + orderId,
                    Duration.ofMinutes(15),
                    12_000L,
                    null,
                    "UZS",
                    true,
                    40_000L,
                    new ShipmentBookingPort.Waypoint(
                            41.32, 69.28, "Test dropoff", "Customer", "+998900000002", null, null, null, null)));
        }
    }
}
