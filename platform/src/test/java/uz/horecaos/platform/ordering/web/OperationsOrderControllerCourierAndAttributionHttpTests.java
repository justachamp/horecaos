package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.accounts.StaffDisplayNames;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map rows 1.1, 1.1c, 1.1e and 9.2d through the real HTTP stack: the
 * board's Курьер field and `paymentStatus` filter, {@code ASSIGN_COURIER} in
 * {@code actions[]}, and the detail read's resolved actor display names.
 *
 * <p>Mirrors {@link OperationsOrderControllerActionCapabilitiesHttpTests}'
 * own style and fixture shape — a real request through {@code
 * DispatcherServlet}, the production {@code AuthorizationService}, and real
 * Jackson serialization, which is the one hop {@code OrderActionsPolicyTests}
 * and {@code CourierAssignmentQueryService}'s own {@code
 * ManualDispatchServiceTests} coverage cannot reach on their own. {@link
 * StaffDisplayNames} is the one collaborator overridden by {@link
 * MockitoBean}: the real bean is Keycloak-backed and this suite has no
 * Keycloak to seed a name into.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsOrderControllerCourierAndAttributionHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb900-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb900-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018fb900-4000-7000-8000-0000000000c1");

    /** Holds {@code ORDER_READ}/{@code ORDER_ADVANCE}/{@code DELIVERY_MANUAL_ASSIGN} at {@code LOCATION_A}. */
    private static final String MANAGER = "courier-attribution-http-manager";

    /** Holds only {@code ORDER_READ} at {@code LOCATION_A} — never offered ASSIGN_COURIER. */
    private static final String READ_ONLY = "courier-attribution-http-read-only";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the courier/attribution HTTP test");
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

    @MockitoBean
    @SuppressWarnings("NullAway")
    private StaffDisplayNames staffDisplayNames;

    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION_A);
        grant(READ_ONLY, PlatformRole.TENANT_FINANCE, "LOCATION", LOCATION_A);
    }

    @Test
    @DisplayName("gap map 1.1: an unassigned CONFIRMED delivery order offers ASSIGN_COURIER to a "
            + "DELIVERY_MANUAL_ASSIGN holder, and courierId is absent")
    void unassignedDeliveryOrderOffersAssignCourierAndCarriesNoCourierId() throws Exception {
        UUID orderId = seedConfirmedDeliveryOrder("5001", null, null);

        String board = readBoard(LOCATION_A, MANAGER);
        assertThat(board).contains("\"action\":\"ASSIGN_COURIER\"").contains("\"courierId\":null");

        String detail = readDetail(LOCATION_A, orderId, MANAGER);
        assertThat(detail).contains("\"action\":\"ASSIGN_COURIER\"");

        // A reader without DELIVERY_MANUAL_ASSIGN never sees the action, even
        // though the plan is just as unassigned for them.
        String boardReadOnly = readBoard(LOCATION_A, READ_ONLY);
        assertThat(boardReadOnly).doesNotContain("\"action\":\"ASSIGN_COURIER\"");
    }

    @Test
    @DisplayName("gap map 1.1/1.1e: an assigned order's board row carries courierId and never offers "
            + "ASSIGN_COURIER again")
    void assignedDeliveryOrderCarriesCourierIdAndNeverOffersAssignCourierAgain() throws Exception {
        UUID orderId = seedConfirmedDeliveryOrder("5002", null, null);
        UUID courierId = seedAssignedCourier(orderId);

        String board = readBoard(LOCATION_A, MANAGER);
        assertThat(board).contains("\"courierId\":\"" + courierId + "\"");
        assertThat(board).doesNotContain("\"action\":\"ASSIGN_COURIER\"");

        String detail = readDetail(LOCATION_A, orderId, MANAGER);
        assertThat(detail).contains("\"courierId\":\"" + courierId + "\"");
    }

    @Test
    @DisplayName("gap map 9.2d: the detail read resolves createdByActorId to a display name instead of "
            + "leaving the console to show the raw UUID")
    void detailResolvesCreatedByActorIdToADisplayName() throws Exception {
        String operatorSubject = "operator-subject-42";
        when(staffDisplayNames.displayName(eq(operatorSubject))).thenReturn("Шахзод Каримов");

        UUID orderId = seedConfirmedDeliveryOrder("5003", "USER", operatorSubject);

        String detail = readDetail(LOCATION_A, orderId, MANAGER);
        assertThat(detail).contains("\"createdByDisplayName\":\"Шахзод Каримов\"");
        // The raw subject is still on the wire for a caller that wants it.
        assertThat(detail).contains("\"createdByActorId\":\"" + operatorSubject + "\"");
    }

    @Test
    @DisplayName("9.2d: a subject with no name on file falls back to null rather than a broken lookup "
            + "breaking the read")
    void detailFallsBackToNullWhenNoDisplayNameIsOnFile() throws Exception {
        String operatorSubject = "operator-subject-unknown";
        when(staffDisplayNames.displayName(eq(operatorSubject))).thenReturn(null);

        UUID orderId = seedConfirmedDeliveryOrder("5004", "USER", operatorSubject);

        String detail = readDetail(LOCATION_A, orderId, MANAGER);
        assertThat(detail).contains("\"createdByDisplayName\":null");
    }

    @Test
    @DisplayName("gap map 1.1c: paymentStatus narrows the board to orders.payment_status_projection, "
            + "and an unknown value is refused rather than silently answering \"no orders\"")
    void paymentStatusFiltersTheBoardAndRefusesAnUnknownValue() throws Exception {
        UUID captured = seedConfirmedDeliveryOrder("5005", null, null);
        setPaymentStatusProjection(captured, "CAPTURED");
        UUID notRequired = seedConfirmedDeliveryOrder("5006", null, null);

        MvcResult filtered = mvc.perform(get(ordersPath(LOCATION_A) + "/board")
                        .param("paymentStatus", "CAPTURED")
                        .with(tokenFor(MANAGER)))
                .andReturn();
        assertThat(filtered.getResponse().getStatus()).isEqualTo(200);
        String body = filtered.getResponse().getContentAsString();
        assertThat(body).contains(captured.toString()).doesNotContain(notRequired.toString());

        MvcResult refused = mvc.perform(get(ordersPath(LOCATION_A) + "/board")
                        .param("paymentStatus", "NONSENSE")
                        .with(tokenFor(MANAGER)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
    }

    private void setPaymentStatusProjection(UUID orderId, String projection) {
        jdbc.sql("UPDATE ordering.orders SET payment_status_projection = :projection WHERE id = :id")
                .param("projection", projection)
                .param("id", orderId)
                .update();
    }

    // --------------------------------------------------------------- helpers

    private String readBoard(UUID locationId, String subject) throws Exception {
        MvcResult result = mvc.perform(get(ordersPath(locationId) + "/board").with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("board status").isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private String readDetail(UUID locationId, UUID orderId, String subject) throws Exception {
        MvcResult result = mvc.perform(
                        get(ordersPath(locationId) + "/" + orderId).with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("detail status").isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private static String ordersPath(UUID locationId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId + "/orders";
    }

    /**
     * A {@code CONFIRMED}, {@code DELIVERY} order — the one status/mode pair
     * {@code DeliveryPlanTrigger} would have opened a plan for, and {@code
     * OrderActionsPolicy.canAssignCourier}'s own window.
     */
    private UUID seedConfirmedDeliveryOrder(
            String number, @Nullable String createdByActorType, @Nullable String createdByActorId) {
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
                .param("loc", LOCATION_A)
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
                .param("loc", LOCATION_A)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    created_at, confirmed_at, created_by_actor_type, created_by_actor_id)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY', 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', 'CONFIRMED', 'UZS', 20000, 0, 0, 20000, :quote, :hash,
                    :pub, :cart, :key, 1, :at, :at, :createdType, :createdId)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION_A)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .param("createdType", createdByActorType)
                .param("createdId", createdByActorId)
                .update();

        return orderId;
    }

    /** A real courier, plan and {@code ASSIGNED} shipment — the minimal shape {@code courierIdsByOrders} reads. */
    private UUID seedAssignedCourier(UUID orderId) {
        UUID courierTypeId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class, max_concurrent_assignments, offer_ttl_seconds, status)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER', 2, 60, 'ACTIVE')
                """).param("id", courierTypeId).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status, version)
                VALUES (:id, :t, :typeId, :subject, 'К-042', 'protected', 'ACTIVE', 1)
                """)
                .param("id", courierId)
                .param("t", TENANT)
                .param("typeId", courierTypeId)
                .param("subject", "keycloak-courier-" + courierId)
                .update();

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id,
                    order_id, status, sourcing_mode, service_level, customer_delivery_fee_minor,
                    currency, confirmed_at, preparation_seconds, estimated_ready_at,
                    pickup_window_start, pickup_window_end, source_at, latest_assignment_at,
                    branch_zone, calculation_version, version)
                VALUES (:id, :t, :b, :loc, :orderId, 'ASSIGNED', 'FLEET_FIRST', 'STANDARD', 0,
                    'UZS', :now, 600, :now, :now, :windowEnd, :now, :windowEnd, 'Asia/Tashkent', 1, 1)
                """)
                .param("id", planId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION_A)
                .param("orderId", orderId)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("windowEnd", now.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO fulfillment.shipments (id, tenant_id, brand_id, location_id, order_id,
                    delivery_plan_id, status, source_type, courier_id, assigned_at, version)
                VALUES (:id, :t, :b, :loc, :orderId, :planId, 'ASSIGNED', 'INTERNAL', :courierId,
                    :now, 1)
                """)
                .param("id", shipmentId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION_A)
                .param("orderId", orderId)
                .param("planId", planId)
                .param("courierId", courierId)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();

        return courierId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'courier-attribution-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
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
                """)
                .param("id", LOCATION_A)
                .param("t", TENANT)
                .param("b", BRAND)
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

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'courier/attribution http test', :validFrom)
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
