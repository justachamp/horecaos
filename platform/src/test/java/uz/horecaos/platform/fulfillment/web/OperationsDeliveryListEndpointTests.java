package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The deliveries list (ADR 0164 slice two, gap map row 3.1b) over the real HTTP stack and database: a row per plan
 * with history, the filters, keyset paging, the scope its filters name, and no recipient anywhere in the payload.
 *
 * <p>Every plan carries an explicit, distinct {@code confirmed_at}: the list orders by it, and fixtures that took
 * {@code now()} would tie on a fast machine and reorder on a loaded one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsDeliveryListEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant BASE = Instant.parse("2026-10-20T08:00:00Z");

    private static final UUID TENANT = UUID.fromString("018fd900-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd900-4000-7000-8000-0000000000b1");
    private static final UUID CENTRE = UUID.fromString("018fd900-4000-7000-8000-0000000000c1");
    private static final UUID AIRPORT = UUID.fromString("018fd900-4000-7000-8000-0000000000c2");
    private static final UUID COURIER_TYPE = UUID.fromString("018fd900-4000-7000-8000-0000000000d1");
    private static final UUID ALISHER = UUID.fromString("018fd900-4000-7000-8000-0000000000e1");
    private static final UUID BOBUR = UUID.fromString("018fd900-4000-7000-8000-0000000000e2");

    private static final UUID OTHER_TENANT = UUID.fromString("018fd900-4000-7000-8000-0000000000a2");

    private static final String ADMIN = "delivery-list-admin";
    private static final String CENTRE_MANAGER = "delivery-list-centre-manager";
    private static final String OTHER_ADMIN = "delivery-list-other-admin";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the deliveries list endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
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

    private UUID channelId = UUID.randomUUID();
    private UUID publicationId = UUID.randomUUID();
    private int sequence;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_plans CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_types CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        sequence = 0;

        seedTenancy();
        roleRegistry.synchronize();
        grantTenant(ADMIN, PlatformRole.TENANT_ADMIN, TENANT);
        grantTenant(OTHER_ADMIN, PlatformRole.TENANT_ADMIN, OTHER_TENANT);
        grantAtLocation(CENTRE_MANAGER, PlatformRole.LOCATION_MANAGER, TENANT, CENTRE);
    }

    @Test
    @DisplayName("one row per plan, newest first, history included, with who carries it, the cash and no recipient")
    void aRowPerPlanWithHistory() throws Exception {
        UUID open = plan(CENTRE, "SOURCING", false, 0, null);
        UUID carried = plan(CENTRE, "IN_PROGRESS", false, 1, ALISHER);
        UUID done = plan(CENTRE, "COMPLETED", true, 2, ALISHER);
        UUID cancelled = plan(AIRPORT, "CANCELLED", false, 3, null);
        costLine(shipmentOf(done), "ACCRUED", 7_500);

        JsonNode page = list(ADMIN, "");

        assertThat(planIds(page)).as("newest confirmed first").containsExactly(cancelled, done, carried, open);
        JsonNode completed = row(page, done);
        assertThat(completed.path("planStatus").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("shipmentStatus").asText()).isEqualTo("DELIVERED");
        assertThat(completed.path("courierId").asText()).isEqualTo(ALISHER.toString());
        assertThat(completed.path("sourceType").asText()).isEqualTo("INTERNAL");
        assertThat(completed.path("deliveredAt").isNull()).isFalse();
        assertThat(completed.path("branchName").asText()).isEqualTo("Centre");
        assertThat(completed.path("orderReference").asText()).startsWith("DL-");
        assertThat(completed.path("destinationLabel").asText()).isEqualTo("Chilonzor");
        assertThat(completed.path("prepaid").asBoolean()).isTrue();
        assertThat(completed.path("cashToCollectMinor").asLong())
                .as("the platform holds the money")
                .isZero();
        assertThat(completed.path("cost").path("basis").asText()).isEqualTo("ACCRUED");
        assertThat(completed.path("cost").path("path").asText()).isEqualTo("INTERNAL");
        assertThat(completed.path("cost").path("amountMinor").asLong()).isEqualTo(7_500);

        JsonNode inProgress = row(page, carried);
        assertThat(inProgress.path("cashToCollectMinor").asLong())
                .as("an unpaid order is collected at the door in full when no settlement says otherwise")
                .isEqualTo(20_000);
        assertThat(inProgress.path("cost").isNull())
                .as("no cost recognised yet")
                .isTrue();

        JsonNode unassigned = row(page, open);
        assertThat(unassigned.path("shipmentId").isNull()).isTrue();
        assertThat(unassigned.path("cashToCollectMinor").isNull()).isTrue();

        for (JsonNode item : page.path("items")) {
            item.fieldNames()
                    .forEachRemaining(field -> assertThat(field.toLowerCase())
                            .as("a delivery row never carries a recipient")
                            .doesNotContain("phone", "recipient", "customername", "address", "email"));
        }
    }

    @Test
    @DisplayName("filters narrow by branch, state, courier and date, and a word that is not a state is refused")
    void filters() throws Exception {
        UUID open = plan(CENTRE, "SOURCING", false, 0, null);
        UUID alisher = plan(CENTRE, "IN_PROGRESS", false, 1, ALISHER);
        UUID bobur = plan(AIRPORT, "COMPLETED", false, 2, BOBUR);

        assertThat(planIds(list(ADMIN, "?brandId=" + BRAND + "&locationId=" + AIRPORT)))
                .containsExactly(bobur);
        assertThat(planIds(list(ADMIN, "?status=SOURCING&status=IN_PROGRESS"))).containsExactly(alisher, open);
        assertThat(planIds(list(ADMIN, "?courierId=" + ALISHER))).containsExactly(alisher);
        assertThat(planIds(list(
                        ADMIN,
                        "?from=" + BASE.plus(Duration.ofMinutes(1)) + "&to=" + BASE.plus(Duration.ofMinutes(2)))))
                .as("from is inclusive and to is exclusive")
                .containsExactly(alisher);
        assertThat(planIds(list(ADMIN, "?brandId=" + BRAND + "&locationId=" + CENTRE + "&locationId=" + AIRPORT)))
                .hasSize(3);

        MvcResult unknown = call(ADMIN, "?status=FLYING");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(unknown.getResponse().getContentAsString(UTF_8))
                        .path("code")
                        .asText())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(call(ADMIN, "?locationId=" + CENTRE).getResponse().getStatus())
                .as("a branch needs the brand it belongs to")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("paging by cursor visits every row once, in order, and a cursor from other filters is refused")
    void paging() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(0, plan(CENTRE, "COMPLETED", false, i, ALISHER));
        }

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = list(ADMIN, "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(planIds(page));
            cursor = page.path("nextCursor").isNull()
                    ? null
                    : page.path("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(expected);

        String firstCursor = list(ADMIN, "?limit=2").path("nextCursor").asText();
        assertThat(call(ADMIN, "?limit=2&status=COMPLETED&cursor=" + firstCursor)
                        .getResponse()
                        .getStatus())
                .as("changing a filter invalidates the cursor")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("scope follows the filters: a branch manager reads their branch and is refused the tenant, "
            + "another branch and another tenant")
    void scopeFollowsTheFilter() throws Exception {
        UUID atCentre = plan(CENTRE, "COMPLETED", false, 0, ALISHER);
        plan(AIRPORT, "COMPLETED", false, 1, BOBUR);

        assertThat(planIds(list(CENTRE_MANAGER, "?brandId=" + BRAND + "&locationId=" + CENTRE)))
                .containsExactly(atCentre);
        assertThat(call(CENTRE_MANAGER, "").getResponse().getStatus())
                .as("a branch grant does not read the tenant")
                .isEqualTo(403);
        assertThat(call(CENTRE_MANAGER, "?brandId=" + BRAND).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(call(CENTRE_MANAGER, "?brandId=" + BRAND + "&locationId=" + AIRPORT)
                        .getResponse()
                        .getStatus())
                .as("another branch of the same brand")
                .isEqualTo(403);
        assertThat(call(OTHER_ADMIN, "").getResponse().getStatus())
                .as("another tenant's admin")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult call(String subject, String query) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/operations/tenants/" + TENANT + "/deliveries" + query)
                .with(tokenFor(subject));
        return mvc.perform(request).andReturn();
    }

    private JsonNode list(String subject, String query) throws Exception {
        MvcResult result = call(subject, query);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private static List<UUID> planIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : page.path("items")) {
            ids.add(UUID.fromString(item.path("planId").asText()));
        }
        return ids;
    }

    private static JsonNode row(JsonNode page, UUID planId) {
        for (JsonNode item : page.path("items")) {
            if (planId.toString().equals(item.path("planId").asText())) {
                return item;
            }
        }
        throw new AssertionError("no row for plan " + planId);
    }

    /** An order, its plan confirmed {@code minute} minutes after BASE, and a shipment when a courier carries it. */
    private UUID plan(UUID location, String planStatus, boolean prepaid, int minute, @Nullable UUID courierId) {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant confirmedAt = BASE.plus(Duration.ofMinutes(minute));
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'DELIVERY', 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", location)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 20000, 0, 20000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", location)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, payment_status_projection, currency, subtotal_minor, tax_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, confirmed_at, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', 'COMPLETED', :payment,
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", "DL-" + String.format("%04d", sequence))
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", location)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("payment", prepaid ? "CAPTURED" : "NOT_REQUIRED")
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", confirmedAt.atOffset(ZoneOffset.UTC))
                .update();
        UUID planId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id, order_id,
                    status, currency, customer_delivery_fee_minor, confirmed_at, preparation_seconds,
                    estimated_ready_at, pickup_window_start, pickup_window_end, promised_delivery_start,
                    promised_delivery_end, source_at, latest_assignment_at, branch_zone, distance_meters,
                    distance_source, destination_label, version)
                VALUES (:id, :t, :b, :loc, :orderId, :status, 'UZS', 10000, :at, 900,
                    :at, :at, :at, :at, :at, :at, :at, 'Asia/Tashkent', 2100, 'RADIUS', 'Chilonzor', 1)
                """)
                .param("id", planId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", location)
                .param("orderId", orderId)
                .param("status", planStatus)
                .param("at", confirmedAt.atOffset(ZoneOffset.UTC))
                .update();
        if (courierId != null) {
            boolean delivered = "COMPLETED".equals(planStatus);
            jdbc.sql("""
                    INSERT INTO fulfillment.shipments (id, tenant_id, brand_id, location_id, order_id,
                        delivery_plan_id, status, source_type, courier_id, assigned_at, picked_up_at,
                        delivered_at, version)
                    VALUES (:id, :t, :b, :loc, :orderId, :plan, :status, 'INTERNAL', :courier, :assigned,
                        :picked, :delivered, 1)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", TENANT)
                    .param("b", BRAND)
                    .param("loc", location)
                    .param("orderId", orderId)
                    .param("plan", planId)
                    .param("status", delivered ? "DELIVERED" : "PICKED_UP")
                    .param("courier", courierId)
                    .param("assigned", confirmedAt.plusSeconds(60).atOffset(ZoneOffset.UTC))
                    .param("picked", confirmedAt.plusSeconds(120).atOffset(ZoneOffset.UTC))
                    .param("delivered", delivered ? confirmedAt.plusSeconds(900).atOffset(ZoneOffset.UTC) : null)
                    .update();
        }
        return planId;
    }

    private UUID shipmentOf(UUID planId) {
        return jdbc.sql("SELECT id FROM fulfillment.shipments WHERE delivery_plan_id = :p")
                .param("p", planId)
                .query(UUID.class)
                .single();
    }

    private void costLine(UUID shipmentId, String basis, long amountMinor) {
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_cost_lines (id, tenant_id, shipment_id, business_date, cost_path,
                    cost_basis, amount_minor, currency, source_type, courier_id, recorded_by)
                VALUES (:id, :t, :s, current_date, 'INTERNAL', :basis, :amount, 'UZS', 'ACCRUAL', :courier, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("s", shipmentId)
                .param("basis", basis)
                .param("amount", amountMinor)
                .param("courier", ALISHER)
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).param("slug", "delivery-list").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_TENANT)
                .param("slug", "delivery-list-other")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        for (Object[] location : new Object[][] {{CENTRE, "CENTRE", "Centre"}, {AIRPORT, "AIRPORT", "Airport"}}) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :t, :b, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location[0])
                    .param("t", TENANT)
                    .param("b", BRAND)
                    .param("code", location[1])
                    .param("slug", ((String) location[1]).toLowerCase())
                    .param("name", location[2])
                    .update();
        }
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", COURIER_TYPE).param("t", TENANT).update();
        for (Object[] courier : new Object[][] {{ALISHER, "K-1"}, {BOBUR, "K-2"}}) {
            jdbc.sql("""
                    INSERT INTO fulfillment.couriers
                        (id, tenant_id, courier_type_id, principal_subject, display_reference, protected_full_name, status)
                    VALUES (:id, :t, :type, :subject, :reference, 'ciphertext-not-exercised-here', 'ACTIVE')
                    """)
                    .param("id", courier[0])
                    .param("t", TENANT)
                    .param("type", COURIER_TYPE)
                    .param("subject", "keycloak-" + courier[1])
                    .param("reference", courier[1])
                    .update();
        }
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

    private void grantTenant(String subject, PlatformRole role, UUID tenantId) {
        grantAt(subject, role, tenantId, "TENANT", tenantId);
    }

    private void grantAtLocation(String subject, PlatformRole role, UUID tenantId, UUID locationId) {
        grantAt(subject, role, tenantId, "LOCATION", locationId);
    }

    private void grantAt(String subject, PlatformRole role, UUID tenantId, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'deliveries list endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
    static class Doubles {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
