package uz.horecaos.platform.pricing.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.pricing.PromotionDbFixture;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0140's operations endpoints through the real capability interceptor, the
 * real idempotency interceptor and the real application context.
 *
 * <p>The JSON here is what the console sends: optional fields are simply absent,
 * which is exactly what Jackson 3 refuses for a missing primitive, so a body that
 * omits {@code exclusive}, {@code priority} and the limits and still drafts proves
 * those components are boxed. The database is seeded by {@link PromotionDbFixture}
 * (a pizza menu in a Tashkent location) so the simulator has something real to
 * price.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromotionControllerEndpointTests {

    private static final String OWNER = "promotion-owner";
    private static final String READER = "promotion-reader";
    private static final String OUTSIDER = "promotion-outsider";
    private static final Instant NOW = PromotionDbFixture.TASHKENT_LUNCH;

    private static final String BASE = "/api/v1/operations/tenants/" + PromotionDbFixture.TENANT + "/brands/"
            + PromotionDbFixture.BRAND + "/promotions";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private static final AtomicInteger KEYS = new AtomicInteger();

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
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    private PromotionDbFixture fixture;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        fixture = new PromotionDbFixture(db, NOW);
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER, PromotionDbFixture.TENANT);
        grant(READER, PlatformRole.TENANT_ADMIN, PromotionDbFixture.TENANT);
    }

    // ------------------------------------------------------------ authorization

    @Test
    @DisplayName("a role with PRICING_READ but not PRICING_PROMOTION_MANAGE can read and simulate but not author")
    void aReaderCannotAuthor() throws Exception {
        assertThat(mvc.perform(get(BASE).with(tokenFor(READER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult refused = mvc.perform(post(BASE)
                        .with(tokenFor(READER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("READER10", 1_000)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.PRICING_PROMOTION_MANAGE.code());
    }

    @Test
    @DisplayName("someone with no grant in this tenant is refused everything, including the simulator")
    void anOutsiderIsRefused() throws Exception {
        assertThat(mvc.perform(get(BASE).with(tokenFor(OUTSIDER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(post(BASE + "/simulate")
                                .with(tokenFor(OUTSIDER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(simulationBody(null)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a draft needs an Idempotency-Key")
    void aDraftNeedsAnIdempotencyKey() throws Exception {
        MvcResult refused = mvc.perform(post(BASE)
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOKEY", 1_000)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    // ----------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("draft, validate, activate and the simulator, with If-Match and an ETag at every step")
    void theWholeLifecycleOverHttp() throws Exception {
        JsonNode drafted = json(
                post(BASE)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("HTTP10", 1_000)),
                201);
        String id = drafted.get("promotionId").asString();
        assertThat(drafted.get("status").asString()).isEqualTo("DRAFT");
        assertThat(drafted.get("exclusive").asBoolean())
                .as("an omitted primitive defaults, it is not a 400")
                .isFalse();
        assertThat(drafted.get("priority").asInt()).isZero();

        // No If-Match, no validation.
        MvcResult missing = mvc.perform(post(BASE + "/" + id + "/validate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key()))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);

        MvcResult validate = mvc.perform(post(BASE + "/" + id + "/validate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(drafted)))
                .andReturn();
        assertThat(validate.getResponse().getStatus()).isEqualTo(200);
        JsonNode validated = mapper().readTree(validate.getResponse().getContentAsString());
        assertThat(validated.get("valid").asBoolean()).isTrue();
        assertThat(validated.get("promotion").get("status").asString()).isEqualTo("VALIDATED");
        assertThat(java.util.Objects.requireNonNull(validate.getResponse().getHeader("ETag")))
                .isNotBlank();

        MvcResult stale = mvc.perform(post(BASE + "/" + id + "/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(drafted)))
                .andReturn();
        assertThat(stale.getResponse().getStatus())
                .as("the draft's version is stale now")
                .isEqualTo(409);

        JsonNode activated = jsonOf(
                mvc.perform(post(BASE + "/" + id + "/activate")
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                                .header(
                                        "If-Match",
                                        java.util.Objects.requireNonNull(
                                                validate.getResponse().getHeader("ETag")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"go live\"}"))
                        .andReturn(),
                200);
        assertThat(activated.get("outcome").asString()).isEqualTo("ACTIVATED");
        assertThat(activated.get("promotion").get("status").asString()).isEqualTo("ACTIVE");

        // A live promotion is never edited in place.
        MvcResult edit = mvc.perform(put(BASE + "/" + id)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header(
                                "If-Match",
                                String.valueOf(activated
                                        .get("promotion")
                                        .get("version")
                                        .asInt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("HTTP10", 2_000)))
                .andReturn();
        assertThat(edit.getResponse().getStatus()).isEqualTo(409);

        // The detail read carries the recorded definition versions.
        JsonNode detail = json(get(BASE + "/" + id).with(tokenFor(READER)), 200);
        assertThat(detail.get("versions")).hasSize(1);
        assertThat(detail.get("versions").get(0).get("definitionVersion").asInt())
                .isEqualTo(1);

        // The simulator prices the pizza cart with the live promotion and explains it.
        JsonNode simulated = json(
                post(BASE + "/simulate")
                        .with(tokenFor(READER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulationBody(null)),
                200);
        assertThat(simulated.get("discountMinor").asLong()).isEqualTo(9_000L);
        assertThat(simulated.get("totalMinor").asLong()).isEqualTo(81_000L);
        assertThat(simulated.get("trace").get(0).get("verdict").asString()).isEqualTo("APPLIED");
        assertThat(simulated.get("trace").get(0).get("code").asString()).isEqualTo("HTTP10");
        assertThat(count("pricing.quotes")).as("the simulator wrote no quote").isZero();

        // A reader may not suspend.
        MvcResult forbidden = mvc.perform(post(BASE + "/" + id + "/suspend")
                        .with(tokenFor(READER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header(
                                "If-Match",
                                String.valueOf(activated
                                        .get("promotion")
                                        .get("version")
                                        .asInt())))
                .andReturn();
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a refusal is returned as a stable code with the sequence it concerns, and the draft stays a draft")
    void aRefusalIsAStableCode() throws Exception {
        JsonNode drafted = json(
                post(BASE)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"code":"GIFTHTTP","name":"Gift","kind":"DISCOUNT","scope":"ITEM","stackingGroup":"gift",
                         "currency":"UZS",
                         "conditions":[{"sequence":1,"type":"CATEGORY","operands":{"categoryIds":["%s"]}}],
                         "actions":[{"sequence":1,"type":"FREE_ITEM","operands":{"variantIds":["%s"]}}]}
                        """.formatted(fixture.classicCategoryId(), fixture.colaVariantId())),
                201);

        JsonNode report = json(
                post(BASE + "/" + drafted.get("promotionId").asString() + "/validate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(drafted)),
                200);

        assertThat(report.get("valid").asBoolean()).isFalse();
        assertThat(report.get("refusals").get(0).get("code").asString()).isEqualTo("FREE_ITEM_UNBOUNDED");
        assertThat(report.get("refusals").get(0).get("sequence").asInt()).isEqualTo(1);
        assertThat(report.get("promotion").get("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("a discount over the threshold answers 202 with the approval request, and activates nothing")
    void aLargeDiscountWaitsForASecondPerson() throws Exception {
        JsonNode drafted = json(
                post(BASE)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BIGHTTP", 4_000)),
                201);
        String id = drafted.get("promotionId").asString();
        MvcResult validate = mvc.perform(post(BASE + "/" + id + "/validate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(drafted)))
                .andReturn();

        MvcResult activate = mvc.perform(post(BASE + "/" + id + "/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header(
                                "If-Match",
                                java.util.Objects.requireNonNull(
                                        validate.getResponse().getHeader("ETag"))))
                .andReturn();

        assertThat(activate.getResponse().getStatus()).isEqualTo(202);
        JsonNode pending = mapper().readTree(activate.getResponse().getContentAsString());
        assertThat(pending.get("outcome").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.get("approvalRequestId").asString()).isNotBlank();
        assertThat(jdbc.sql("SELECT status FROM pricing.promotions WHERE id = :id")
                        .param("id", UUID.fromString(id))
                        .query(String.class)
                        .single())
                .isEqualTo("VALIDATED");
    }

    @Test
    @DisplayName("a priority reorder is one call, and the first id gets the highest priority")
    void priorityReorder() throws Exception {
        String first = draftId("ORDER-A");
        String second = draftId("ORDER-B");

        JsonNode reordered = json(
                put(BASE + "/priority")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stackingGroup\":\"order\",\"orderedPromotionIds\":[\"%s\",\"%s\"]}"
                                .formatted(second, first)),
                200);

        assertThat(reordered).hasSize(2);
        assertThat(jdbc.sql("SELECT priority FROM pricing.promotions WHERE id = :id")
                        .param("id", UUID.fromString(second))
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    // --------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("a promotion drafted for one brand is not found under another brand or another tenant")
    void promotionsDoNotCrossBrandsOrTenants() throws Exception {
        String id = draftId("PRIVATE");

        MvcResult otherBrand = mvc.perform(get("/api/v1/operations/tenants/" + PromotionDbFixture.TENANT + "/brands/"
                                + PromotionDbFixture.OTHER_BRAND + "/promotions/" + id)
                        .with(tokenFor(OWNER)))
                .andReturn();
        MvcResult otherTenant = mvc.perform(get("/api/v1/operations/tenants/" + PromotionDbFixture.OTHER_TENANT
                                + "/brands/" + PromotionDbFixture.OTHER_TENANT_BRAND + "/promotions/" + id)
                        .with(tokenFor(OWNER)))
                .andReturn();

        assertThat(otherBrand.getResponse().getStatus())
                .as("same tenant, other brand")
                .isEqualTo(404);
        assertThat(otherTenant.getResponse().getStatus())
                .as("the owner holds no grant in the other tenant")
                .isEqualTo(403);
    }

    // ---------------------------------------------------------------- fixtures

    private String draftId(String code) throws Exception {
        return json(
                        post(BASE)
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(code, 1_000)),
                        201)
                .get("promotionId")
                .asString();
    }

    private String body(String code, int basisPoints) {
        // What the console sends: no exclusive, no priority, no limits, no loyalty flags.
        return """
                {"code":"%s","name":"Promotion %s","kind":"DISCOUNT","scope":"ORDER","stackingGroup":"order",
                 "currency":"UZS",
                 "actions":[{"sequence":1,"type":"ORDER_PERCENTAGE_DISCOUNT","operands":{"basisPoints":%d}}]}
                """.formatted(code, code, basisPoints);
    }

    private String simulationBody(@Nullable String payment) {
        return """
                {"locationId":"%s","channelCode":"STOREFRONT",%s
                 "lines":[{"lineId":"line-0","variantId":"%s","quantity":2}]}
                """.formatted(
                        PromotionDbFixture.LOCATION,
                        payment == null ? "" : "\"paymentMethodCode\":\"" + payment + "\",",
                        fixture.margheritaVariantId());
    }

    private JsonNode json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status)
            throws Exception {
        return jsonOf(mvc.perform(request).andReturn(), status);
    }

    private JsonNode jsonOf(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(status);
        return mapper().readTree(result.getResponse().getContentAsString());
    }

    private static String etag(JsonNode promotion) {
        return String.valueOf(promotion.get("version").asInt());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static JsonMapper mapper() {
        return JsonMapper.builder().build();
    }

    private static String key() {
        return "promotion-test-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID();
    }

    private void grant(String subject, PlatformRole role, UUID tenant) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :scopeId,
                        'ACTIVE', 'test-fixture', 'promotion endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenant)
                .param("scopeId", tenant)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
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
