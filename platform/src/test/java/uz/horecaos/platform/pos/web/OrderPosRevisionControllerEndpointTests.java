package uz.horecaos.platform.pos.web;

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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code GET/POST .../orders/{orderId}/till-revision[/send|/acknowledge]} through the real HTTP stack, as the
 * people who stand at the pass (ADR 0137, ADR 0180, row {@code 4.2c}).
 *
 * <p>{@code OrderPosRevisionControllerTests} calls the controller directly and so never proved who may use it.
 * The banner this feeds is mounted on every expo ticket and on the order detail of a branch, and the routes
 * were tenant-scoped: a location manager holds no tenant-scope grant, so every ticket answered 403 and the
 * notice the branch chat had just announced could be closed by an owner or an administrator only. Here the
 * callers are the roles a branch actually has, at the scope they actually hold.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPosRevisionControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb900-a000-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018fb900-a000-7000-8000-0000000000c2");
    private static final UUID LOCATION = UUID.fromString("018fb900-a000-7000-8000-0000000000c3");

    /** A second branch of the same brand: its people and its orders are not this branch's. */
    private static final UUID OTHER_LOCATION = UUID.fromString("018fb900-a000-7000-8000-0000000000c4");

    /** What the console sends: the revision of the notice it showed (the seeded notice is revision 2). */
    private static final String SAW_REVISION_2 = "{\"expectedRevision\":2}";

    private static final String MANAGER = "till-revision-http-manager";
    private static final String STAFF = "till-revision-http-staff";
    private static final String MANAGER_ELSEWHERE = "till-revision-http-manager-elsewhere";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the till-revision HTTP test");
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

    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.order_till_revision_notices CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, LOCATION);
        grant(STAFF, PlatformRole.LOCATION_STAFF, LOCATION);
        grant(MANAGER_ELSEWHERE, PlatformRole.LOCATION_MANAGER, OTHER_LOCATION);
    }

    // ------------------------------------------------------------------ the people at the pass

    @Test
    @DisplayName("a location manager reads the open notice of an order at their own branch")
    void aLocationManagerReadsTheNotice() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-1001");
        seedOpenNotice(orderId, LOCATION);

        MvcResult result = mvc.perform(get(path(LOCATION, orderId)).with(tokenFor(MANAGER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"status\":\"OPEN\"")
                .contains("\"tillTotalMinor\":215000")
                .contains("\"revisedTotalMinor\":208000");
    }

    @Test
    @DisplayName("a line cook reads the notice, which is what lets them tell the manager, and closes nothing")
    void floorStaffReadButDoNotClose() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-1002");
        seedOpenNotice(orderId, LOCATION);

        MvcResult read =
                mvc.perform(get(path(LOCATION, orderId)).with(tokenFor(STAFF))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);

        MvcResult acknowledge = mvc.perform(post(path(LOCATION, orderId) + "/acknowledge")
                        .with(tokenFor(STAFF))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAW_REVISION_2))
                .andReturn();
        assertThat(acknowledge.getResponse().getStatus()).isEqualTo(403);
        assertThat(acknowledge.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.POS_EXPORT_RESOLVE.code());

        MvcResult send = mvc.perform(post(path(LOCATION, orderId) + "/send")
                        .with(tokenFor(STAFF))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAW_REVISION_2))
                .andReturn();
        assertThat(send.getResponse().getStatus()).isEqualTo(403);
        assertThat(noticeStatus(orderId)).as("the notice is still open").isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a location manager says the till was changed, and the notice closes against them")
    void aLocationManagerAcknowledges() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-1003");
        seedOpenNotice(orderId, LOCATION);

        MvcResult result = mvc.perform(post(path(LOCATION, orderId) + "/acknowledge")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAW_REVISION_2))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"ACKNOWLEDGED\"");
        assertThat(noticeStatus(orderId)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.sql("SELECT resolved_by FROM ordering.order_till_revision_notices WHERE order_id = :o")
                        .param("o", orderId)
                        .query(String.class)
                        .single())
                .isEqualTo(MANAGER);
    }

    @Test
    @DisplayName("a location manager can ask the till to be told: the answer is an ordinary one, not a refusal")
    void aLocationManagerMaySend() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-1004");
        seedOpenNotice(orderId, LOCATION);

        MvcResult result = mvc.perform(post(path(LOCATION, orderId) + "/send")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAW_REVISION_2))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .as("no till binding is wired for this fixture, so the platform says it cannot tell it")
                .contains("\"outcome\":\"NOT_AVAILABLE\"");
    }

    // ------------------------------------------------------------------ the notice the operator saw

    @Test
    @DisplayName("an order weighed again since the operator looked: acknowledging is a 409 STALE_VERSION and the "
            + "newer notice stays open")
    void aStaleAcknowledgementIsAConflict() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-3001");
        seedOpenNotice(orderId, LOCATION);
        jdbc.sql("UPDATE ordering.order_till_revision_notices SET revision = 3, revised_total_minor = 203000 "
                        + "WHERE order_id = :o")
                .param("o", orderId)
                .update();

        MvcResult result = mvc.perform(post(path(LOCATION, orderId) + "/acknowledge")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAW_REVISION_2))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString())
                .contains("STALE_VERSION")
                .contains("\"expectedVersion\":2")
                .contains("\"currentVersion\":3");
        assertThat(noticeStatus(orderId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName(
            "sending a total the operator saw after the order was weighed again is a 409 and tells the till nothing")
    void aStaleSendIsAConflict() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-3002");
        seedOpenNotice(orderId, LOCATION);
        jdbc.sql("UPDATE ordering.order_till_revision_notices SET revision = 3, revised_total_minor = 203000 "
                        + "WHERE order_id = :o")
                .param("o", orderId)
                .update();

        MvcResult result = mvc.perform(post(path(LOCATION, orderId) + "/send")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAW_REVISION_2))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(noticeStatus(orderId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("an action that does not say which notice it is about is refused, not read as whichever is open")
    void anActionWithoutARevisionIsRefused() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-3003");
        seedOpenNotice(orderId, LOCATION);

        for (String action : List.of("/acknowledge", "/send")) {
            MvcResult result = mvc.perform(post(path(LOCATION, orderId) + action)
                            .with(tokenFor(MANAGER))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(action).isEqualTo(400);
        }
        assertThat(noticeStatus(orderId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("the read says which revision the notice is, because that is what an action quotes")
    void theReadCarriesTheRevision() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-3004");
        seedOpenNotice(orderId, LOCATION);

        MvcResult result = mvc.perform(get(path(LOCATION, orderId)).with(tokenFor(MANAGER)))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"revision\":2");
    }

    // ------------------------------------------------------------------ the branch boundary

    @Test
    @DisplayName("a manager of another branch is refused at this branch's path")
    void aManagerOfAnotherBranchIsRefused() throws Exception {
        UUID orderId = seedConfirmedOrder(LOCATION, "TR-2001");
        seedOpenNotice(orderId, LOCATION);

        MvcResult result = mvc.perform(get(path(LOCATION, orderId)).with(tokenFor(MANAGER_ELSEWHERE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
    }

    @Test
    @DisplayName("an order of another branch is not found through this branch's path, not leaked or closed")
    void anOrderOfAnotherBranchIsNotFoundThroughThisPath() throws Exception {
        UUID foreignOrderId = seedConfirmedOrder(OTHER_LOCATION, "TR-2002");
        seedOpenNotice(foreignOrderId, OTHER_LOCATION);

        MvcResult read = mvc.perform(get(path(LOCATION, foreignOrderId)).with(tokenFor(MANAGER)))
                .andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(404);
        assertThat(read.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");

        MvcResult acknowledge = mvc.perform(post(path(LOCATION, foreignOrderId) + "/acknowledge")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAW_REVISION_2))
                .andReturn();
        assertThat(acknowledge.getResponse().getStatus()).isEqualTo(404);
        assertThat(noticeStatus(foreignOrderId)).isEqualTo("OPEN");
    }

    // ------------------------------------------------------------------ fixtures

    private static String path(UUID locationId, UUID orderId) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId + "/orders/"
                + orderId + "/till-revision";
    }

    private String noticeStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM ordering.order_till_revision_notices WHERE order_id = :o")
                .param("o", orderId)
                .query(String.class)
                .single();
    }

    private void seedOpenNotice(UUID orderId, UUID locationId) {
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO ordering.order_till_revision_notices (
                    id, tenant_id, brand_id, location_id, order_id, source, revision, currency,
                    till_total_minor, revised_total_minor, status, opened_at, updated_at)
                VALUES (:id, :t, :b, :loc, :order, 'CATCHWEIGHT', 2, 'UZS', 215000, 208000, 'OPEN', :at, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
                .param("order", orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private UUID seedConfirmedOrder(UUID locationId, String number) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'PICKUP', 'UZS', 'ACTIVE', :guest,
                    now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
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
                .param("loc", locationId)
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
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'PICKUP',
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', 'CONFIRMED',
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
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

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'till-revision-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'NORTH', 'north', 'North', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_LOCATION)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

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

    private void grant(String subject, PlatformRole role, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'till-revision http endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", locationId)
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
