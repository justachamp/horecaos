package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
 * {@code POST .../orders/{orderId}/amendments} through the real HTTP stack for
 * wave 10's financial commands (gap map rows {@code 1.2c}/{@code 2.1d}) —
 * {@code OrderAmendmentAndOutcomeTests} proves every command's own business
 * rule against a hand-wired {@code OrderAmendmentService}; nothing anywhere
 * called the controller itself, so {@code AmendmentCommandRequest}'s JSON
 * shape, the real {@code CapabilityEnforcementInterceptor}, and the
 * translation from a thrown exception to an ADR 0031 {@code ProblemDetail}
 * were all unexercised, mirroring the exact gap {@link
 * OperationsOrderControllerDecisionsHttpTests}' own class doc describes for
 * {@code decisions}/{@code timeline}/{@code revisions}.
 *
 * <p>{@link #aCutPointRefusalArrivesAsAConflictNotAFault} is the one that
 * matters most: {@code OrderAmendmentService.propose} raises {@code
 * AmendmentRefusedException} directly for the §3.11 cut point (and for every
 * other financial refusal — an out-of-zone address, a quantity decrease with
 * no ADR 0017 primitive, a payment-method change this build cannot settle),
 * and {@code amend}'s own catch list had no clause for that exception type.
 * Uncaught, it fell through to the container's generic handler as a 500
 * rather than the {@code RESOURCE_CONFLICT} every sibling refusal in the same
 * method already returns — a fault where the API contract promises a stable
 * code, for every command this wave built. No test anywhere could have found
 * this without going through {@code DispatcherServlet}, because {@code
 * OrderAmendmentAndOutcomeTests} asserts on the Java exception type directly
 * and never reaches the controller's own translation at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsOrderControllerAmendmentsHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb900-a000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb900-a000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018fb900-a000-7000-8000-0000000000c1");
    private static final UUID LOCATION_B = UUID.fromString("018fb900-a000-7000-8000-0000000000c2");

    /** Holds {@code ORDER_AMEND} (and {@code ORDER_READ}) at {@code LOCATION_A} only. */
    private static final String AMENDER = "amendments-http-amender";

    /** Holds {@code ORDER_READ} at {@code LOCATION_A} only -- never {@code ORDER_AMEND}. */
    private static final String READ_ONLY = "amendments-http-read-only";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the amendments HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // CHANGE_CONTACT writes the recipient name/phone through EnvelopeFieldProtection
        // (ADR 0029) -- not supplied by application-local.yml for tests on purpose (see
        // that file's own comment); every test that touches protected data registers it.
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

    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.order_amendment_commands, ordering.order_amendments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_customer_snapshots CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(AMENDER, PlatformRole.LOCATION_MANAGER, LOCATION_A);
        grant(READ_ONLY, PlatformRole.LOCATION_STAFF, LOCATION_A);
    }

    @Test
    @DisplayName(
            "CHANGE_CONTACT applies over real HTTP with the console's own JSON shape and needs no increase confirmation")
    void changeContactAppliesOverHttp() throws Exception {
        UUID orderId = seedOrder(LOCATION_A, "AMD-1", "CONFIRMED");

        MvcResult result = mvc.perform(post(amendmentsPath(LOCATION_A, orderId))
                        .with(tokenFor(AMENDER))
                        .header("Idempotency-Key", "amend-change-contact-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .content("""
                                {"commands":[{"type":"CHANGE_CONTACT","recipientName":"Aziz Karimov",\
                                "recipientPhone":"+998901234567"}],"applyImmediately":true,\
                                "reasonCode":"OPERATOR_CORRECTION"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"APPLIED\"");
        assertThat(jdbc.sql("SELECT display_name_encrypted IS NOT NULL FROM ordering.order_customer_snapshots "
                                + "WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Boolean.class)
                        .single())
                .as("the corrected name landed on the snapshot, encrypted -- never in the response body itself")
                .isTrue();
    }

    /**
     * The regression this class exists for. See the class doc: before this
     * catch existed, every financial refusal {@code propose} raises directly
     * -- not only the cut point -- surfaced as an opaque 500.
     */
    @Test
    @DisplayName("a financial command past the ADR 0039 cut point is refused as a 409 conflict, not a 500 fault")
    void aCutPointRefusalArrivesAsAConflictNotAFault() throws Exception {
        UUID orderId = seedOrder(LOCATION_A, "AMD-2", "READY");

        MvcResult result = mvc.perform(post(amendmentsPath(LOCATION_A, orderId))
                        .with(tokenFor(AMENDER))
                        .header("Idempotency-Key", "amend-cut-point-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .content("""
                                {"commands":[{"type":"CHANGE_CONTACT","recipientName":"Aziz Karimov",\
                                "recipientPhone":"+998901234567"}],"applyImmediately":false,\
                                "reasonCode":"OPERATOR_CORRECTION"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("READY is at the default cut point -- refused, never a 500")
                .isEqualTo(409);
        assertThat(result.getResponse().getContentAsString())
                .contains("RESOURCE_CONFLICT")
                .contains("AMENDMENT_PAST_CUT_POINT");
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_amendments WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Integer.class)
                        .single())
                .as("a refused propose leaves no amendment row behind")
                .isZero();
    }

    @Test
    @DisplayName("REMOVE_LINES is refused by name over HTTP -- declared by ADR 0039, not built")
    void removeLinesIsRefusedByNameOverHttp() throws Exception {
        UUID orderId = seedOrder(LOCATION_A, "AMD-3", "CONFIRMED");

        MvcResult result = mvc.perform(post(amendmentsPath(LOCATION_A, orderId))
                        .with(tokenFor(AMENDER))
                        .header("Idempotency-Key", "amend-remove-lines-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .content("""
                                {"commands":[{"type":"REMOVE_LINES","orderLineId":"%s","quantity":1}],\
                                "applyImmediately":false,"reasonCode":"OPERATOR_CORRECTION"}""".formatted(UUID.randomUUID())))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("not built");
    }

    @Test
    @DisplayName("a sibling-location order is not found, not leaked across the location boundary")
    void aSiblingLocationOrderIsNotFound() throws Exception {
        UUID orderId = seedOrder(LOCATION_B, "AMD-4", "CONFIRMED");

        MvcResult result = mvc.perform(post(amendmentsPath(LOCATION_A, orderId))
                        .with(tokenFor(AMENDER))
                        .header("Idempotency-Key", "amend-cross-branch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .content("""
                                {"commands":[{"type":"CHANGE_CONTACT","recipientName":"Aziz Karimov",\
                                "recipientPhone":"+998901234567"}],"applyImmediately":false,\
                                "reasonCode":"OPERATOR_CORRECTION"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("ORDER_AMEND is required -- a principal holding only ORDER_READ is refused at the real interceptor")
    void withoutOrderAmendTheCallIsRefused() throws Exception {
        UUID orderId = seedOrder(LOCATION_A, "AMD-5", "CONFIRMED");

        MvcResult result = mvc.perform(post(amendmentsPath(LOCATION_A, orderId))
                        .with(tokenFor(READ_ONLY))
                        .header("Idempotency-Key", "amend-no-capability-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .content("""
                                {"commands":[{"type":"CHANGE_CONTACT","recipientName":"Aziz Karimov",\
                                "recipientPhone":"+998901234567"}],"applyImmediately":false,\
                                "reasonCode":"OPERATOR_CORRECTION"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_AMEND.code());
    }

    @Test
    @DisplayName("a missing Idempotency-Key is refused before anything is priced (ADR 0031)")
    void missingIdempotencyKeyIsRefused() throws Exception {
        UUID orderId = seedOrder(LOCATION_A, "AMD-6", "CONFIRMED");

        MvcResult result = mvc.perform(post(amendmentsPath(LOCATION_A, orderId))
                        .with(tokenFor(AMENDER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .content("""
                                {"commands":[{"type":"CHANGE_CONTACT","recipientName":"Aziz Karimov",\
                                "recipientPhone":"+998901234567"}],"applyImmediately":false,\
                                "reasonCode":"OPERATOR_CORRECTION"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ fixtures

    private String amendmentsPath(UUID locationId, UUID orderId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId + "/orders/" + orderId
                + "/amendments";
    }

    private UUID seedOrder(UUID locationId, String number, String status) {
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
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    current_revision, created_at, confirmed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'PICKUP', 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', :status, 'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub,
                    :cart, :key, 1, 1, :at, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("status", status)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO ordering.order_revisions (order_id, revision, tenant_id, source,
                    pricing_quote_id, pricing_context_hash, currency, subtotal_minor, tax_minor,
                    discount_minor, fee_minor, total_minor, delta_total_minor,
                    created_by_actor_type, created_at)
                VALUES (:id, 1, :t, 'CHECKOUT', :quote, :hash, 'UZS', 20000, 0, 0, 0, 20000, 0,
                    'USER', :at)
                """)
                .param("id", orderId)
                .param("t", TENANT)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        // Every order carries a snapshot row from checkout (JdbcOrderStore
        // #updateCustomerSnapshot's own doc) -- CHANGE_CONTACT is always an
        // UPDATE, never an upsert.
        jdbc.sql("INSERT INTO ordering.order_customer_snapshots (order_id, tenant_id) VALUES (:id, :t)")
                .param("id", orderId)
                .param("t", TENANT)
                .update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'amendments-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                        'ACTIVE', 'test-fixture', 'amendments http endpoint test', :validFrom)
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
