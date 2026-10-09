package uz.horecaos.platform.kitchen.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0168 through the real HTTP stack: the paid-hold policy endpoints (their capabilities, {@code If-Match},
 * scope and replace-not-merge) and the buffer's release endpoint (the {@code 409 PAYMENT_HOLD} and the
 * override that needs both a capability and a registered reason).
 *
 * <p>The decision itself (what holds, what clears, what the sweep records) is proved against a real database
 * in {@code KitchenExecutionTests.PaidHold}; this proves the doors in front of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenPaidHoldHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fe300-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fe300-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fe300-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe300-4000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fe300-4000-7000-8000-0000000000c2");

    /** LOCATION_MANAGER at LOCATION: reads tickets, releases, overrides, and manages the paid hold. */
    private static final String MANAGER = "paid-hold-manager";

    /** TENANT_OWNER at TENANT. */
    private static final String OWNER = "paid-hold-owner";

    /** A tenant-defined role holding the ticket read and release, but not the override or the policy. */
    private static final String LINE = "paid-hold-line";

    private static final String OTHER_OWNER = "paid-hold-other-owner";
    private static final String OTHER_BRANCH_MANAGER = "paid-hold-other-branch";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the paid-hold HTTP test");
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
    private CacheManager cacheManager;

    private UUID channelId;
    private UUID publicationId;
    private int sequence;

    @BeforeEach
    void reset() {
        Cache policyCurrent = cacheManager.getCache("tenant.policy_current");
        if (policyCurrent != null) {
            policyCurrent.clear();
        }
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE kitchen.ticket_events, kitchen.ticket_items, kitchen.tickets CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(MANAGER, roleId(PlatformRole.LOCATION_MANAGER), true, "LOCATION", LOCATION, TENANT);
        grant(OWNER, roleId(PlatformRole.TENANT_OWNER), true, "TENANT", TENANT, TENANT);
        grant(OTHER_OWNER, roleId(PlatformRole.TENANT_OWNER), true, "TENANT", OTHER_TENANT, OTHER_TENANT);
        grant(OTHER_BRANCH_MANAGER, roleId(PlatformRole.LOCATION_MANAGER), true, "LOCATION", OTHER_LOCATION, TENANT);
        UUID line = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :t, 'LINE_RELEASE', 'Line release', 'LOCATION', 'ACTIVE', false)
                """).param("id", line).param("t", TENANT).update();
        for (String capability : List.of("kitchen.ticket.read", "kitchen.ticket.release")) {
            jdbc.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:r, :c)")
                    .param("r", line)
                    .param("c", capability)
                    .update();
        }
        grant(LINE, line, false, "LOCATION", LOCATION, TENANT);
    }

    // ------------------------------------------------------------------ the policy

    @Test
    @DisplayName("nothing published reads as the built-in SHADOW with ten minutes, and says it is the default")
    void theDefaultIsShadow() throws Exception {
        JsonNode body = read(policyPath(null, null), OWNER);

        assertThat(body.path("mode").asText()).isEqualTo("SHADOW");
        assertThat(body.path("overdueAfterMinutes").asInt()).isEqualTo(10);
        assertThat(body.path("isDefault").asBoolean()).isTrue();
        assertThat(body.path("versionAtScope").asInt()).isZero();
    }

    @Test
    @DisplayName(
            "a tenant publishes HOLD_UNTIL_PAID under If-Match; a brand's own document replaces it rather than merging")
    void publishingIsWholeDocumentAndMostSpecificWins() throws Exception {
        long auditedBefore = audited("kitchen.paid_hold.published");
        Response tenantWrite = write(policyPath(null, null), OWNER, "p1", "0", """
                {"mode":"HOLD_UNTIL_PAID","overdueAfterMinutes":20,"reason":"pilot"}""");
        assertThat(tenantWrite.status()).isEqualTo(200);
        assertThat(tenantWrite.etag()).isEqualTo("W/\"1\"");
        assertThat(tenantWrite.json().path("mode").asText()).isEqualTo("HOLD_UNTIL_PAID");

        Response brandWrite = write(policyPath(BRAND, null), OWNER, "p2", "0", """
                {"mode":"OFF","reason":"regulars on trust"}""");
        assertThat(brandWrite.status()).isEqualTo(200);
        assertThat(brandWrite.json().path("overdueAfterMinutes").asInt())
                .as("a brand's document replaces the tenant's; a missing field is the default ten, not twenty")
                .isEqualTo(10);

        JsonNode atLocation = read(policyPath(BRAND, LOCATION), MANAGER);
        assertThat(atLocation.path("mode").asText()).isEqualTo("OFF");
        assertThat(atLocation.path("winningScope").asText()).isEqualTo("BRAND");
        assertThat(atLocation.path("versionAtScope").asInt())
                .as("the location inherits")
                .isZero();

        assertThat(audited("kitchen.paid_hold.published") - auditedBefore).isEqualTo(2);
    }

    @Test
    @DisplayName("a stale If-Match, an out-of-range minute count and a missing mode are refused with stable codes")
    void badWritesAreRefused() throws Exception {
        write(policyPath(null, null), OWNER, "s1", "0", """
                {"mode":"HOLD_UNTIL_PAID","reason":"first"}""");

        Response stale = write(policyPath(null, null), OWNER, "s2", "0", """
                {"mode":"OFF","reason":"second"}""");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json().path("code").asText()).isEqualTo("STALE_VERSION");

        Response tooLong = write(policyPath(null, null), OWNER, "s3", "1", """
                {"mode":"SHADOW","overdueAfterMinutes":99999,"reason":"x"}""");
        assertThat(tooLong.status()).isEqualTo(400);

        Response noMode = write(policyPath(null, null), OWNER, "s4", "1", """
                {"overdueAfterMinutes":5,"reason":"x"}""");
        assertThat(noMode.status()).isEqualTo(400);

        Response noIfMatch = writeWithoutIfMatch(policyPath(null, null), OWNER, "s5", """
                {"mode":"OFF","reason":"x"}""");
        assertThat(noIfMatch.status()).isEqualTo(400);
        assertThat(read(policyPath(null, null), OWNER).path("mode").asText())
                .as("nothing refused was published")
                .isEqualTo("HOLD_UNTIL_PAID");
    }

    @Test
    @DisplayName("writing needs kitchen.paid-hold.manage at the scope; another tenant and another branch are refused")
    void theWriteIsScopedAndCapabilityGated() throws Exception {
        String body = """
                {"mode":"OFF","reason":"x"}""";

        assertThat(write(policyPath(null, null), LINE, "c1", "0", body).status())
                .as("a line role holds neither the policy capability nor tenant scope")
                .isEqualTo(403);
        assertThat(write(policyPath(null, null), OTHER_OWNER, "c2", "0", body).status())
                .as("another tenant's owner")
                .isEqualTo(403);
        assertThat(write(policyPath(null, null), MANAGER, "c3", "0", body).status())
                .as("a location manager cannot publish at the tenant")
                .isEqualTo(403);
        assertThat(write(policyPath(BRAND, LOCATION), OTHER_BRANCH_MANAGER, "c4", "0", body)
                        .status())
                .as("a manager of another branch")
                .isEqualTo(403);
        assertThat(write(policyPath(BRAND, LOCATION), MANAGER, "c5", "0", body).status())
                .as("a location manager at their own location")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ the release

    @Test
    @DisplayName(
            "releasing a payment-held ticket is 409 PAYMENT_HOLD without the override and a registered reason code")
    void releasingAHeldTicketNeedsTheOverride() throws Exception {
        UUID ticket = bufferedTicket(true);
        int version = versionOf(ticket);

        Response lineCook = release(LINE, ticket, version, null);
        assertThat(lineCook.status()).isEqualTo(409);
        assertThat(lineCook.json().path("code").asText()).isEqualTo("PAYMENT_HOLD");
        assertThat(lineCook.json().toString()).contains("MANAGER_DECISION");

        Response noCode = release(MANAGER, ticket, version, null);
        assertThat(noCode.status()).isEqualTo(409);
        Response freeText = release(MANAGER, ticket, version, "because");
        assertThat(freeText.status()).isEqualTo(409);
        assertThat(statusOf(ticket)).isEqualTo("HELD");

        Response fired = release(MANAGER, ticket, version, "MANAGER_DECISION");
        assertThat(fired.status()).isEqualTo(200);
        assertThat(fired.json().path("status").asText()).isEqualTo("FIRED");
        assertThat(fired.json().path("holdReason").isNull()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_events
                        WHERE action_code = 'kitchen.ticket.payment_hold.override' AND reason = 'MANAGER_DECISION'
                        """).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("a ticket that is not held is released by a line role as before, with or without a code")
    void anOrdinaryReleaseNeedsNothingNew() throws Exception {
        UUID ticket = bufferedTicket(false);

        Response fired = release(LINE, ticket, versionOf(ticket), null);

        assertThat(fired.status()).isEqualTo(200);
        assertThat(fired.json().path("status").asText()).isEqualTo("FIRED");
    }

    @Test
    @DisplayName("the buffer stream says why a ticket waits: holdReason, holdSince and the blocked and overdue marks")
    void theBufferShowsTheHold() throws Exception {
        UUID ticket = bufferedTicket(true);
        jdbc.sql("UPDATE kitchen.tickets SET hold_blocked_notified_at = now() WHERE id = :id")
                .param("id", ticket)
                .update();

        MvcResult buffer = mvc.perform(get(kitchenPath() + "/tickets")
                        .param("stream", "buffer")
                        .with(tokenFor(MANAGER)))
                .andReturn();

        assertThat(buffer.getResponse().getStatus()).isEqualTo(200);
        JsonNode first = JSON.readTree(buffer.getResponse().getContentAsString())
                .path("tickets")
                .get(0);
        assertThat(first.path("holdReason").asText()).isEqualTo("PAYMENT");
        assertThat(first.path("holdSince").asText()).isNotBlank();
        assertThat(first.path("holdBlocked").asBoolean()).isTrue();
        assertThat(first.path("holdOverdue").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a ticket of another branch is not found from this branch's path")
    void aSiblingBranchTicketIsInvisible() throws Exception {
        UUID ticket = bufferedTicket(true);

        MvcResult fromOtherBranch = mvc.perform(
                        post(kitchenPath(OTHER_LOCATION) + "/tickets/" + ticket + "/release")
                                .with(tokenFor(OTHER_BRANCH_MANAGER))
                                .header("Idempotency-Key", "sibling-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expectedVersion\":1,\"reasonCode\":\"RUSH\",\"overrideReasonCode\":\"MANAGER_DECISION\"}"))
                .andReturn();

        assertThat(fromOtherBranch.getResponse().getStatus()).isEqualTo(404);
        assertThat(statusOf(ticket)).isEqualTo("HELD");
    }

    // ------------------------------------------------------------------ helpers

    private record Response(
            int status, String raw, @Nullable String etag) {
        JsonNode json() throws Exception {
            return JSON.readTree(raw);
        }
    }

    private static String policyPath(@Nullable UUID brandId, @Nullable UUID locationId) {
        String query = "";
        if (brandId != null) {
            query = "?brandId=" + brandId + (locationId == null ? "" : "&locationId=" + locationId);
        }
        return "/api/v1/operations/tenants/" + TENANT + "/kitchen-paid-hold" + query;
    }

    private static String kitchenPath() {
        return kitchenPath(LOCATION);
    }

    private static String kitchenPath(UUID location) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location + "/kitchen";
    }

    private JsonNode read(String path, String subject) throws Exception {
        MvcResult result = mvc.perform(get(path).with(tokenFor(subject))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private Response write(String path, String subject, String key, String ifMatch, String body) throws Exception {
        MvcResult result = mvc.perform(put(path)
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .header(HttpHeaders.IF_MATCH, "\"" + ifMatch + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        return new Response(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString(),
                result.getResponse().getHeader(HttpHeaders.ETAG));
    }

    private Response writeWithoutIfMatch(String path, String subject, String key, String body) throws Exception {
        MvcResult result = mvc.perform(put(path)
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        return new Response(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString(),
                result.getResponse().getHeader(HttpHeaders.ETAG));
    }

    private Response release(String subject, UUID ticket, int version, @Nullable String overrideReason)
            throws Exception {
        String body = overrideReason == null
                ? "{\"expectedVersion\":%d,\"reasonCode\":\"RUSH\"}".formatted(version)
                : "{\"expectedVersion\":%d,\"reasonCode\":\"RUSH\",\"overrideReasonCode\":\"%s\"}"
                        .formatted(version, overrideReason);
        MvcResult result = mvc.perform(post(kitchenPath() + "/tickets/" + ticket + "/release")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", "release-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        return new Response(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString(),
                result.getResponse().getHeader(HttpHeaders.ETAG));
    }

    private long audited(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :a")
                .param("a", action)
                .query(Long.class)
                .single();
    }

    private String statusOf(UUID ticket) {
        return jdbc.sql("SELECT status FROM kitchen.tickets WHERE id = :id")
                .param("id", ticket)
                .query(String.class)
                .single();
    }

    private int versionOf(UUID ticket) {
        return jdbc.sql("SELECT version FROM kitchen.tickets WHERE id = :id")
                .param("id", ticket)
                .query(Integer.class)
                .single();
    }

    /** A pre-order's ticket waiting in the buffer, held for payment or not. */
    private UUID bufferedTicket(boolean heldForPayment) {
        UUID orderId = seedOrder();
        UUID ticketId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO kitchen.tickets (id, tenant_id, brand_id, location_id, order_id, sequence_label,
                    fulfilment_mode, channel_code, status, release_mode, release_at, routing_version,
                    hold_reason, hold_since, hold_policy_id, hold_policy_version)
                VALUES (:id, :t, :b, :loc, :order, :label, 'PICKUP', 'WEB', 'HELD', 'SCHEDULED', :releaseAt, 1,
                    :holdReason, :holdSince, NULL, NULL)
                """)
                .param("id", ticketId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("order", orderId)
                .param("label", "K-" + sequence)
                .param("releaseAt", now.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .param("holdReason", heldForPayment ? "PAYMENT" : null)
                .param("holdSince", heldForPayment ? now.atOffset(ZoneOffset.UTC) : null)
                .update();
        return ticketId;
    }

    private UUID seedOrder() {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'PICKUP', 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
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
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    created_at, confirmed_at, payment_status_projection)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'PICKUP', 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', 'CONFIRMED', 'UZS', 20000, 0, 0, 20000, :quote, :hash,
                    :pub, :cart, :key, 1, :at, :at, 'PENDING')
                """)
                .param("id", orderId)
                .param("number", String.valueOf(8000 + sequence))
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

    private void seedTenancy() {
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenant)
                    .param("slug", "paid-hold-" + tenant)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :t, :b, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("t", TENANT)
                    .param("b", BRAND)
                    .param("code", location.equals(LOCATION) ? "CENTRE" : "NORTH")
                    .param("slug", location.equals(LOCATION) ? "centre" : "north")
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

    private static UUID roleId(PlatformRole role) {
        return RoleRegistrySynchronizer.platformRoleId(role);
    }

    private void grant(String subject, UUID roleId, boolean platformRole, String scopeType, UUID scopeId, UUID tenant) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :t, :subject, :roleId, :platform, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'paid hold http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + roleId + scopeId).getBytes(UTF_8)))
                .param("t", tenant)
                .param("subject", subject)
                .param("roleId", roleId)
                .param("platform", platformRole)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
