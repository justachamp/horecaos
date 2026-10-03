package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.staff.StaffMemberRegistry;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map row 0.1d, the live operator band, over the real HTTP stack: who is
 * taking and confirming orders during service, with names from the tenant's own
 * staff directory (ADR 0139).
 *
 * <p>The counts were always readable; what the row lacked was a person to show
 * beside them. So the weight of this suite is on the name: it is the tenant's
 * own, a stranger to the directory shows without one rather than failing the
 * board, and the two levels the board is read at (the brand, and one branch for a
 * shift supervisor) refuse each other's readers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperatorTodayLeaderboardEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb200-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb200-4000-7000-8000-0000000000b1");
    private static final UUID LOC_1 = UUID.fromString("018fb200-4000-7000-8000-0000000000c1");
    private static final UUID LOC_2 = UUID.fromString("018fb200-4000-7000-8000-0000000000c2");

    private static final UUID OTHER_TENANT = UUID.fromString("018fb200-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fb200-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOC = UUID.fromString("018fb200-4000-7000-8000-0000000000c3");

    private static final String BRAND_READER = "board-brand-reader";
    private static final String L1_SUPERVISOR = "board-l1-supervisor";
    private static final String UNGRANTED = "board-ungranted";

    private static final String AZIZA = "board-aziza";
    private static final String BOBUR = "board-bobur";
    private static final String STRANGER = "board-stranger";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        // The staff member record seals names and phones under ADR 0029; a context
        // that serves it needs the platform key-encryption key, as every other
        // suite that reaches FieldProtection supplies.
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

    @Autowired
    @SuppressWarnings("NullAway")
    private StaffMemberRegistry registry;

    @Autowired
    @SuppressWarnings("NullAway")
    private TransactionTemplate tx;

    private final java.util.Map<UUID, UUID> channels = new java.util.HashMap<>();
    private final java.util.Map<UUID, UUID> publications = new java.util.HashMap<>();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        channels.clear();
        publications.clear();

        seedTenancy(TENANT, BRAND, LOC_1, "board-a");
        seedLocation(TENANT, BRAND, LOC_2, "L2", "board-l2");
        seedTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOC, "board-b");
        roleRegistry.synchronize();

        grant(BRAND_READER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(L1_SUPERVISOR, PlatformRole.LOCATION_MANAGER, "LOCATION", LOC_1);

        member(TENANT, AZIZA, "Aziza", "Karimova");
        member(TENANT, BOBUR, "Bobur", "Yusupov");
        member(OTHER_TENANT, STRANGER, "Someone", "Else");
    }

    @Test
    @DisplayName("the brand band lists today's operators, most accepted first, each with the tenant's own name")
    void theBrandBandNamesItsOperators() throws Exception {
        seedOrder(TENANT, LOC_1, "A1", AZIZA, AZIZA, Instant.now());
        seedOrder(TENANT, LOC_1, "A2", AZIZA, AZIZA, Instant.now());
        seedOrder(TENANT, LOC_2, "B1", BOBUR, AZIZA, Instant.now());
        seedOrder(TENANT, LOC_2, "B2", BOBUR, null, Instant.now());

        JsonNode body = readBody(get(brandPath(), BRAND_READER));

        List<JsonNode> rows = body.get("rows").valueStream().toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("operatorPrincipalId").asText()).isEqualTo(AZIZA);
        assertThat(rows.get(0).get("displayName").asText()).isEqualTo("Aziza Karimova");
        assertThat(rows.get(0).get("acceptedCount").asInt()).isEqualTo(3);
        assertThat(rows.get(0).get("createdCount").asInt()).isEqualTo(2);
        assertThat(rows.get(1).get("operatorPrincipalId").asText()).isEqualTo(BOBUR);
        assertThat(rows.get(1).get("displayName").asText()).isEqualTo("Bobur Yusupov");
        assertThat(rows.get(1).get("createdCount").asInt()).isEqualTo(2);
        assertThat(rows.get(1).get("acceptedCount").asInt()).isZero();
    }

    @Test
    @DisplayName(
            "an operator the tenant keeps no name for still counts, with a null name, and a name kept by another tenant is never shown")
    void aStrangerToTheDirectoryShowsWithoutAName() throws Exception {
        seedOrder(TENANT, LOC_1, "S1", STRANGER, STRANGER, Instant.now());

        JsonNode body = readBody(get(brandPath(), BRAND_READER));

        JsonNode row = body.get("rows").get(0);
        assertThat(row.get("operatorPrincipalId").asText()).isEqualTo(STRANGER);
        assertThat(row.get("displayName").isNull())
                .as("the subject is a member of the other tenant, not of this one")
                .isTrue();
        assertThat(row.get("acceptedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("only people count: a bot's or a customer's order names a channel or an account, not a colleague")
    void onlyUserActorsAreOperators() throws Exception {
        seedOrderWithActorType(TENANT, LOC_1, "BOT1", "CUSTOMER", "customer-account-1", Instant.now());
        seedOrder(TENANT, LOC_1, "U1", AZIZA, null, Instant.now());

        JsonNode body = readBody(get(brandPath(), BRAND_READER));

        assertThat(body.get("rows")).hasSize(1);
        assertThat(body.get("rows").get(0).get("operatorPrincipalId").asText()).isEqualTo(AZIZA);
    }

    @Test
    @DisplayName("the branch band is the supervisor's own branch only, and a brand reader may read it too")
    void theBranchBandIsOnlyTheBranch() throws Exception {
        seedOrder(TENANT, LOC_1, "L1A", AZIZA, AZIZA, Instant.now());
        seedOrder(TENANT, LOC_2, "L2A", BOBUR, BOBUR, Instant.now());

        JsonNode branch1 = readBody(get(branchPath(LOC_1), L1_SUPERVISOR));
        assertThat(branch1.get("rows")).hasSize(1);
        assertThat(branch1.get("rows").get(0).get("displayName").asText()).isEqualTo("Aziza Karimova");

        assertThat(readBody(get(branchPath(LOC_2), BRAND_READER)).get("rows")).hasSize(1);
    }

    @Test
    @DisplayName(
            "a location-scoped grant is refused the brand band and a sibling branch; no grant at all is refused both")
    void theLevelsRefuseEachOthersReaders() throws Exception {
        assertCapabilityRefusal(get(brandPath(), L1_SUPERVISOR));
        assertCapabilityRefusal(get(branchPath(LOC_2), L1_SUPERVISOR));
        assertCapabilityRefusal(get(brandPath(), UNGRANTED));
        assertCapabilityRefusal(get(branchPath(LOC_1), UNGRANTED));
    }

    @Test
    @DisplayName("another tenant's orders never reach this tenant's band, even for the same subject")
    void anotherTenantsOrdersDoNotLeakIn() throws Exception {
        seedOrder(OTHER_TENANT, OTHER_LOC, "OT1", AZIZA, AZIZA, Instant.now());

        JsonNode body = readBody(get(brandPath(), BRAND_READER));

        assertThat(body.get("rows")).isEmpty();
    }

    @Test
    @DisplayName("an order from two business days ago is not on today's band")
    void onlyTodayCounts() throws Exception {
        seedOrder(TENANT, LOC_1, "OLD", AZIZA, AZIZA, Instant.now().minus(Duration.ofDays(2)));
        seedOrder(TENANT, LOC_1, "NEW", AZIZA, AZIZA, Instant.now());

        JsonNode body = readBody(get(brandPath(), BRAND_READER));

        assertThat(body.get("rows").get(0).get("acceptedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the single operator's card carries the same name the band does")
    void theTodayCountsCardIsNamedToo() throws Exception {
        seedOrder(TENANT, LOC_1, "C1", AZIZA, AZIZA, Instant.now());
        grant("board-tenant-reader", PlatformRole.TENANT_FINANCE, "TENANT", TENANT);

        JsonNode card = readBody(get(
                "/api/v1/tenants/" + TENANT + "/orders/operators/" + AZIZA + "/today-counts", "board-tenant-reader"));

        assertThat(card.get("displayName").asText()).isEqualTo("Aziza Karimova");
        assertThat(card.get("createdCount").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------- fixtures

    private static String brandPath() {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/orders/operators/today";
    }

    private static String branchPath(UUID location) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location
                + "/orders/operators/today";
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private static JsonNode readBody(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private static void assertCapabilityRefusal(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString(UTF_8))
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("order.read");
    }

    private void member(UUID tenantId, String subject, String first, String last) {
        tx.executeWithoutResult(status -> {
            registry.registerInvited(tenantId, subject, first, last, null, "fixture-inviter", "corr");
            registry.activate(tenantId, subject, first, last, "corr");
        });
    }

    private void seedOrder(
            UUID tenantId,
            UUID locationId,
            String number,
            String createdBy,
            @Nullable String acceptedBy,
            Instant createdAt) {
        insertOrder(tenantId, locationId, number, "USER", createdBy, acceptedBy, createdAt);
    }

    private void seedOrderWithActorType(
            UUID tenantId, UUID locationId, String number, String actorType, String createdBy, Instant createdAt) {
        insertOrder(tenantId, locationId, number, actorType, createdBy, null, createdAt);
    }

    private void insertOrder(
            UUID tenantId,
            UUID locationId,
            String number,
            String createdByType,
            String createdBy,
            @Nullable String acceptedBy,
            Instant createdAt) {
        UUID brandId = tenantId.equals(TENANT) ? BRAND : OTHER_BRAND;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'PICKUP', 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channels.get(tenantId))
                .param("guest", "guest-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 20000, 0, 20000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("pub", publications.get(tenantId))
                .param("hash", "hash-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    created_at, confirmed_at, created_by_actor_type, created_by_actor_id,
                    accepted_by_actor_type, accepted_by_actor_id, accepted_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'PICKUP', 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', 'READY', 'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub,
                    :cart, :key, 1, :at, :at, :createdType, :createdBy, :acceptedType, :acceptedBy, :acceptedAt)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channels.get(tenantId))
                .param("guest", "guest-" + orderId)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publications.get(tenantId))
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", createdAt.atOffset(ZoneOffset.UTC))
                .param("createdType", createdByType)
                .param("createdBy", createdBy)
                .param("acceptedType", acceptedBy == null ? null : "USER")
                .param("acceptedBy", acceptedBy)
                .param("acceptedAt", acceptedBy == null ? null : createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy(UUID tenantId, UUID brandId, UUID locationId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        seedLocation(tenantId, brandId, locationId, "L1", slug + "-l1");

        UUID channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("t", tenantId).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();
        UUID publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("cat", catalogId)
                .update();
        channels.put(tenantId, channelId);
        publications.put(tenantId, publicationId);
    }

    private void seedLocation(UUID tenantId, UUID brandId, UUID locationId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'operator band endpoint test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
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
