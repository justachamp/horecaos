package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
 * {@code actions[]} exercised through the real HTTP stack — Spring MVC
 * dispatch, the production {@link uz.horecaos.platform.iam.api.AuthorizationService}
 * bean, {@link RoleRegistrySynchronizer}-resolved grants, and Jackson — rather
 * than the mocked collaborators {@code OperationsOrderControllerActionCapabilitiesTests}
 * exercises. That suite proves {@code grantedOrderActionCapabilities} asks
 * {@code AuthorizationService} the right question; {@code OrderActionsPolicyTests}
 * proves the pure policy function is correct for every role. Neither proves
 * the wiring between a real request and a real database survives Spring's DI,
 * the authorization stack's own scope-covering logic, and JSON serialization —
 * a Jackson field rename, a bean misconfiguration, or a scope resolved at the
 * wrong level would leave every operator console board wrong while both of
 * those suites stayed green, because neither ever calls through {@code
 * DispatcherServlet}. This class is the missing hop, mirroring {@code
 * OperationsBrandOrderCountsEndpointTests}' own real-stack style.
 *
 * <p><b>Wave P05 adversarial review, finding 3.</b> The review's most specific
 * concern was a principal whose action grant is scoped to one branch reading
 * another branch's order through a broader read — the exact shape a
 * brand-wide "board" would create if one served full order objects. No such
 * endpoint exists: {@link OperationsBrandOrderController} serves only
 * aggregate counts (IA 0.1c), never an {@code actions[]}-bearing order, and
 * every endpoint that does carry {@code actions[]} — {@link
 * OperationsOrderController#list}, {@link OperationsOrderController#board}
 * and {@link OperationsOrderController#detail} — is path-scoped to one {@code
 * locationId} and declares {@code ORDER_READ} at exactly that {@code
 * LOCATION} scope. {@link #aLocationScopedActionGrantNeverLeaksIntoAnotherBranch}
 * builds the closest real equivalent instead: one subject holding {@code
 * ORDER_READ} at {@code BRAND} scope (reaching every branch, the same way a
 * brand-wide board would) plus {@code ORDER_APPROVE} at exactly one branch —
 * and proves the broader read never carries the narrower grant's action into
 * the branch it was never given.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsOrderControllerActionCapabilitiesHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb200-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb200-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018fb200-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_B = UUID.fromString("018fb200-4000-7000-8000-0000000000c2");

    /** Holds {@code ORDER_READ}/{@code ORDER_APPROVE}/{@code ORDER_ADVANCE} at {@code LOCATION_A} only. */
    private static final String APPROVER = "action-caps-http-approver";

    /** Holds {@code ORDER_READ} at {@code LOCATION_A} only — none of the four action capabilities. */
    private static final String READ_ONLY = "action-caps-http-read-only";

    /**
     * Holds {@code ORDER_READ} at {@code BRAND} scope (reaches both
     * locations) and {@code ORDER_APPROVE}/{@code ORDER_ADVANCE} at {@code
     * LOCATION_A} only (reaches neither {@code LOCATION_B} nor, on its own,
     * anywhere else). The subject the cross-branch test reads both locations
     * as.
     */
    private static final String CROSS_BRANCH = "action-caps-http-cross-branch";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the actions[] capability wiring HTTP test");
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
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(APPROVER, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION_A);
        grant(READ_ONLY, PlatformRole.TENANT_FINANCE, "LOCATION", LOCATION_A);
        grant(CROSS_BRANCH, PlatformRole.COURIER_DISPATCHER, "BRAND", BRAND);
        grant(CROSS_BRANCH, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION_A);
    }

    @Test
    @DisplayName("ORDER_APPROVE at LOCATION scope offers APPROVE and REJECT on its own branch's awaiting-approval "
            + "order, on the list, the board and the detail read")
    void approverSeesApproveAndRejectOnListBoardAndDetail() throws Exception {
        UUID orderId = seedAwaitingApprovalOrder(LOCATION_A, "1001");

        for (String body : readAllThreeAt(LOCATION_A, orderId, APPROVER)) {
            assertThat(body)
                    .as("approver's read of order %s", orderId)
                    .contains("\"action\":\"APPROVE\"")
                    .contains("\"action\":\"REJECT\"");
        }
    }

    @Test
    @DisplayName("ORDER_READ without ORDER_APPROVE still reads the order, but never offers APPROVE")
    void aPrincipalWithoutOrderApproveSeesNoApprove() throws Exception {
        UUID orderId = seedAwaitingApprovalOrder(LOCATION_A, "1002");

        for (String body : readAllThreeAt(LOCATION_A, orderId, READ_ONLY)) {
            assertThat(body)
                    .as("read-only principal's read of order %s", orderId)
                    .doesNotContain("\"action\":\"APPROVE\"")
                    .doesNotContain("\"action\":\"REJECT\"")
                    .contains("\"actions\":[]");
        }
    }

    /**
     * The review's own refuted-finding concern, proved against the real
     * endpoints rather than a hypothetical brand-wide board — see the class
     * doc for why no such board exists to test directly. {@code
     * CROSS_BRANCH}'s {@code ORDER_READ} reaches both branches (a {@code
     * BRAND}-scope grant), but its {@code ORDER_APPROVE} reaches only {@code
     * LOCATION_A}. Reading {@code LOCATION_B}'s own order must still succeed
     * (the read grant covers it) and must still omit {@code APPROVE} (the
     * action grant does not) — proving a broad read scope can never smuggle a
     * narrower action grant across a scope boundary it was not given at.
     */
    @Test
    @DisplayName("a brand-wide ORDER_READ grant never lets a branch-scoped ORDER_APPROVE leak into another branch")
    void aLocationScopedActionGrantNeverLeaksIntoAnotherBranch() throws Exception {
        UUID orderA = seedAwaitingApprovalOrder(LOCATION_A, "2001");
        UUID orderB = seedAwaitingApprovalOrder(LOCATION_B, "2002");

        for (String body : readAllThreeAt(LOCATION_A, orderA, CROSS_BRANCH)) {
            assertThat(body)
                    .as("cross-branch principal's read of its own branch's order %s", orderA)
                    .contains("\"action\":\"APPROVE\"")
                    .contains("\"action\":\"REJECT\"");
        }

        for (String body : readAllThreeAt(LOCATION_B, orderB, CROSS_BRANCH)) {
            assertThat(body)
                    .as(
                            "cross-branch principal's read of the OTHER branch's order %s must still succeed, "
                                    + "just never with APPROVE",
                            orderB)
                    .doesNotContain("\"action\":\"APPROVE\"")
                    .doesNotContain("\"action\":\"REJECT\"")
                    .contains("\"actions\":[]");
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** The list, board and detail response bodies for one order, all read as one subject. All three must be 200. */
    private List<String> readAllThreeAt(UUID locationId, UUID orderId, String subject) throws Exception {
        String list = ordersPath(locationId);
        String board = list + "/board";
        String detail = list + "/" + orderId;

        MvcResult listResult = mvc.perform(get(list).with(tokenFor(subject))).andReturn();
        MvcResult boardResult = mvc.perform(get(board).with(tokenFor(subject))).andReturn();
        MvcResult detailResult =
                mvc.perform(get(detail).with(tokenFor(subject))).andReturn();

        assertThat(listResult.getResponse().getStatus()).as("list status").isEqualTo(200);
        assertThat(boardResult.getResponse().getStatus()).as("board status").isEqualTo(200);
        assertThat(detailResult.getResponse().getStatus()).as("detail status").isEqualTo(200);

        return List.of(
                listResult.getResponse().getContentAsString(),
                boardResult.getResponse().getContentAsString(),
                detailResult.getResponse().getContentAsString());
    }

    private static String ordersPath(UUID locationId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId + "/orders";
    }

    private UUID seedAwaitingApprovalOrder(UUID locationId, String number) {
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

        // AWAITING_APPROVAL, RESTAURANT_APPROVAL acceptance: the one status the
        // finding's fix asks for by name, because APPROVE/REJECT are only ever
        // offered on it (OrderActionsPolicy.availableFor).
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    approval_deadline_at, status, currency, subtotal_minor, tax_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'RESTAURANT_APPROVAL', 'HORECAOS_OPERATIONS', :deadline, 'AWAITING_APPROVAL',
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, 1, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("deadline", now.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
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
                VALUES (:id, 'action-caps-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
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
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHILONZOR', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION_B)
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
                        'ACTIVE', 'test-fixture', 'action capabilities http test', :validFrom)
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
