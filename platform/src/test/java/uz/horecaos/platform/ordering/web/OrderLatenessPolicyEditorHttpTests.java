package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.tenancy.application.port.PolicyCurrentCache;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Gap map rows {@code X.39}/{@code 10.3b}: the {@code ordering.lateness} document's editor, over HTTP and
 * end to end -- from the request an owner's order-policy card sends, through the shared ADR 0030
 * author, to {@code GET .../orders/lateness-policy}, which is what both boards, the KDS and the VDUs
 * poll. The two halves are different controllers over different services, so a green test on either
 * alone says nothing about whether a number typed into the card reaches a board.
 *
 * <p>Bodies are the JSON the console really sends: an unset at-risk window is an explicit {@code
 * null}, {@code expectedVersion} is {@code null} for a scope that authored nothing, and Jackson 3
 * refuses a body that omits a primitive -- so the tests also prove that leaving a boxed field out is
 * a validation error naming it, not a malformed body.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderLatenessPolicyEditorHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9d10-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9d10-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9d10-5000-7000-8000-0000000000c1");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f9d10-5000-7000-8000-0000000000c2");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9d10-5000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9d10-5000-7000-8000-0000000000b2");

    private static final String OWNER = "lateness-editor-owner";
    private static final String OWNER_NAME = "Aziza Karimova";
    private static final String STRANGER = "lateness-editor-stranger";
    private static final String OTHER_TENANTS_OWNER = "lateness-editor-other-tenant-owner";

    private static final String EDITOR = "/api/v1/operations/tenants/" + TENANT + "/order-lateness-policy";
    private static final String CONFIG = "/api/v1/operations/tenants/" + TENANT + "/configuration";
    private static final String AT_RISK = "ordering.at_risk_before_minutes";
    private static final String LATE_THRESHOLD = "ordering.late_order_threshold_minutes";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

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
        // The publish reply names its approvers, which is PERSONAL (ADR 0139), so the idempotency record
        // keeps it encrypted under the tenant's key: the tests need a key to encrypt it with.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private PolicyCurrentCache policyCache;

    @Autowired
    private ConfigurationValueCache configurationCache;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.policy_current CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "lateness-editor");
        insertTenant(OTHER_TENANT, "lateness-editor-other");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", OTHER_TENANT).update();
        insertLocation(LOCATION, "CHI", "chilonzor");
        insertLocation(SIBLING_LOCATION, "YUN", "yunusobod");
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(OTHER_TENANTS_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);

        // The fixture reuses one tenant id across tests and truncates underneath the resolvers' caches,
        // so an earlier test's document or scalar would otherwise keep resolving here for up to the
        // cache's TTL -- a stale read the production writers never leave (they evict on write).
        for (ResourceScope scope : List.of(
                ResourceScope.tenant(TENANT),
                ResourceScope.brand(TENANT, BRAND),
                ResourceScope.location(TENANT, BRAND, LOCATION),
                ResourceScope.location(TENANT, BRAND, SIBLING_LOCATION))) {
            policyCache.evict("ordering.lateness", scope);
            configurationCache.evict(AT_RISK, scope);
            configurationCache.evict(LATE_THRESHOLD, scope);
        }
    }

    // ------------------------------------------------------------------- read

    @Test
    @DisplayName("with nothing authored the editor shows the platform default: no mode owns a window, version 0")
    void theEditorStartsAtThePlatformDefault() throws Exception {
        for (String scopeQuery : List.of(
                "scopeType=TENANT",
                "scopeType=BRAND&brandId=" + BRAND,
                "scopeType=LOCATION&brandId=" + BRAND + "&locationId=" + LOCATION)) {
            JsonNode editor = read(scopeQuery);

            assertThat(editor.get("isPlatformDefault").asBoolean())
                    .as(scopeQuery)
                    .isTrue();
            assertThat(editor.get("policyVersion").asInt()).isZero();
            assertThat(editor.get("currentVersionAtScope").asInt()).isZero();
            assertThat(editor.get("winningScope").isNull()).isTrue();
            assertThat(editor.get("atRiskDefault").get("seconds").asInt()).isEqualTo(300);
            assertThat(editor.get("atRiskDefault").get("source").asText()).isEqualTo("PLATFORM_DEFAULT");
            for (String mode : List.of("delivery", "pickup", "dineIn")) {
                assertThat(editor.get(mode).get("atRiskBeforeSeconds").isNull())
                        .as(mode + " owns no window")
                        .isTrue();
                assertThat(editor.get(mode).get("effectiveAtRiskBeforeSeconds").asInt())
                        .isEqualTo(300);
                assertThat(editor.get(mode).get("lateAfterSeconds").asInt()).isZero();
                assertThat(editor.get(mode).get("noPromiseFallbackSeconds").isNull())
                        .as(mode + " owns no fallback either")
                        .isTrue();
                assertThat(editor.get(mode)
                                .get("effectiveNoPromiseFallbackSeconds")
                                .asInt())
                        .isEqualTo(2700);
            }
            assertThat(editor.get("noPromiseDefault").get("seconds").asInt()).isEqualTo(2700);
            assertThat(editor.get("noPromiseDefault").get("source").asText()).isEqualTo("PLATFORM_DEFAULT");
        }
    }

    // ------------------------------------------------- publish: tenant to boards

    @Test
    @DisplayName("a tenant document reaches the boards per mode, and every location under it inherits it")
    void aTenantDocumentReachesTheBoardsPerFulfilmentMode() throws Exception {
        MvcResult published = publish(
                "TENANT",
                null,
                null,
                mode(600, 60, 3600),
                mode(120, 0, 1800),
                mode(null, 30, 1200),
                null,
                "deliveries are slower than the counter");

        JsonNode response = JSON.readTree(published.getResponse().getContentAsString());
        assertThat(response.get("policyVersion").asInt()).isEqualTo(1);
        assertThat(response.get("currentVersionAtScope").asInt()).isEqualTo(1);
        assertThat(response.get("winningScope").asText()).isEqualTo("TENANT");

        for (UUID location : List.of(LOCATION, SIBLING_LOCATION)) {
            JsonNode board = boardPolicy(location);
            assertThat(board.get("delivery").get("atRiskBeforeSeconds").asInt()).isEqualTo(600);
            assertThat(board.get("delivery").get("lateAfterSeconds").asInt()).isEqualTo(60);
            assertThat(board.get("delivery").get("noPromiseFallbackSeconds").asInt())
                    .isEqualTo(3600);
            assertThat(board.get("pickup").get("atRiskBeforeSeconds").asInt()).isEqualTo(120);
            assertThat(board.get("pickup").get("noPromiseFallbackSeconds").asInt())
                    .isEqualTo(1800);
            assertThat(board.get("dineIn").get("atRiskBeforeSeconds").asInt())
                    .as("dine-in owns no window: the platform's five minutes")
                    .isEqualTo(300);
            assertThat(board.get("dineIn").get("lateAfterSeconds").asInt()).isEqualTo(30);
        }

        JsonNode atBrand = read("scopeType=BRAND&brandId=" + BRAND);
        assertThat(atBrand.get("winningScope").asText()).isEqualTo("TENANT");
        assertThat(atBrand.get("policyVersion").asInt()).isEqualTo(1);
        assertThat(atBrand.get("currentVersionAtScope").asInt())
                .as("the brand only inherits: it opens the form at 0, not at the tenant's version")
                .isZero();
        assertThat(atBrand.get("inspectedLevels").get(0).get("scopeType").asText())
                .isEqualTo("BRAND");
        assertThat(atBrand.get("inspectedLevels").get(0).get("outcome").asText())
                .isEqualTo("NOT_SET");
        assertThat(atBrand.get("inspectedLevels").get(1).get("outcome").asText())
                .isEqualTo("VALUE");
    }

    @Test
    @DisplayName("a location override wins for that location alone, opened at version 0 while it only inherited")
    void aLocationOverrideBeatsTheTenantForThatLocationAlone() throws Exception {
        publish("TENANT", null, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "default");

        MvcResult located = publish(
                "LOCATION",
                BRAND,
                LOCATION,
                mode(300, 0, 2700),
                mode(60, 120, 900),
                mode(300, 0, 2700),
                0,
                "a food-court counter");

        assertThat(JSON.readTree(located.getResponse().getContentAsString())
                        .get("currentVersionAtScope")
                        .asInt())
                .isEqualTo(1);
        assertThat(boardPolicy(LOCATION).get("pickup").get("lateAfterSeconds").asInt())
                .isEqualTo(120);
        assertThat(boardPolicy(LOCATION)
                        .get("pickup")
                        .get("atRiskBeforeSeconds")
                        .asInt())
                .isEqualTo(60);
        assertThat(boardPolicy(SIBLING_LOCATION)
                        .get("pickup")
                        .get("lateAfterSeconds")
                        .asInt())
                .as("the sibling still resolves the tenant's document")
                .isZero();
        assertThat(boardPolicy(SIBLING_LOCATION)
                        .get("pickup")
                        .get("atRiskBeforeSeconds")
                        .asInt())
                .isEqualTo(300);
    }

    @Test
    @DisplayName(
            "a tenant document published over a cached one reaches a location the boards had warm, editor and board alike")
    void aTenantPublicationReachesALocationWhoseResolutionWasAlreadyCached() throws Exception {
        String atLocation = "scopeType=LOCATION&brandId=" + BRAND + "&locationId=" + LOCATION;
        publish("TENANT", null, null, mode(600, 0, 2700), mode(600, 0, 2700), mode(600, 0, 2700), null, "v1");
        // What the boards and the late-only filter do all shift: the location resolves through the
        // tenant's document and caches it under its own key.
        assertThat(boardPolicy(LOCATION).get("delivery").get("lateAfterSeconds").asInt())
                .isZero();
        assertThat(read(atLocation).get("policyVersion").asInt()).isEqualTo(1);

        publish("TENANT", null, null, mode(900, 60, 2700), mode(900, 60, 2700), mode(900, 60, 2700), 1, "v2");

        assertThat(boardPolicy(LOCATION).get("delivery").get("lateAfterSeconds").asInt())
                .as("the board's next poll")
                .isEqualTo(60);
        JsonNode editor = read(atLocation);
        assertThat(editor.get("policyVersion").asInt())
                .as("the editor opened at this location shows the tenant's version 2, not the cached 1")
                .isEqualTo(2);
        assertThat(editor.get("delivery").get("atRiskBeforeSeconds").asInt()).isEqualTo(900);
        assertThat(editor.get("delivery").get("lateAfterSeconds").asInt()).isEqualTo(60);
        assertThat(editor.get("currentVersionAtScope").asInt())
                .as("the location has still authored nothing of its own")
                .isZero();
    }

    @Test
    @DisplayName("the batch 15 scalar stays the default for a mode with no window of its own")
    void theScalarIsTheDefaultForTheModesNotSet() throws Exception {
        setScalarMinutes(12);
        publish(
                "TENANT",
                null,
                null,
                mode(null, 0, 2700),
                mode(180, 0, 2700),
                mode(null, 0, 2700),
                null,
                "pickup only");

        JsonNode board = boardPolicy(LOCATION);
        assertThat(board.get("delivery").get("atRiskBeforeSeconds").asInt())
                .as("delivery owns no window: the scalar's 12 minutes")
                .isEqualTo(720);
        assertThat(board.get("pickup").get("atRiskBeforeSeconds").asInt())
                .as("pickup's own three minutes beat the scalar")
                .isEqualTo(180);
        assertThat(board.get("dineIn").get("atRiskBeforeSeconds").asInt()).isEqualTo(720);

        JsonNode editor = read("scopeType=TENANT");
        assertThat(editor.get("atRiskDefault").get("source").asText()).isEqualTo("SCALAR");
        assertThat(editor.get("atRiskDefault").get("seconds").asInt()).isEqualTo(720);
        assertThat(editor.get("delivery").get("atRiskBeforeSeconds").isNull()).isTrue();
        assertThat(editor.get("delivery").get("effectiveAtRiskBeforeSeconds").asInt())
                .isEqualTo(720);
        assertThat(editor.get("pickup").get("atRiskBeforeSeconds").asInt()).isEqualTo(180);
    }

    // ------------------------ ADR 0150: the no-promise fallback is optional like the at-risk window

    @Test
    @DisplayName("a mode with a blank fallback takes the tenant's late-order threshold, and says where it came from")
    void aBlankFallbackTakesTheLateOrderThreshold() throws Exception {
        setLateOrderThreshold(20);
        publish(
                "TENANT",
                null,
                null,
                mode(null, 0, null),
                mode(null, 0, 1800),
                mode(null, 0, null),
                null,
                "pickup keeps its own");

        JsonNode board = boardPolicy(LOCATION);
        assertThat(board.get("delivery").get("noPromiseFallbackSeconds").asInt())
                .as("delivery owns no fallback: the threshold's twenty minutes")
                .isEqualTo(1200);
        assertThat(board.get("pickup").get("noPromiseFallbackSeconds").asInt())
                .as("pickup's own thirty minutes beat the threshold")
                .isEqualTo(1800);
        assertThat(board.get("dineIn").get("noPromiseFallbackSeconds").asInt()).isEqualTo(1200);

        JsonNode editor = read("scopeType=TENANT");
        assertThat(editor.get("noPromiseDefault").get("source").asText()).isEqualTo("SCALAR");
        assertThat(editor.get("noPromiseDefault").get("seconds").asInt()).isEqualTo(1200);
        assertThat(editor.get("delivery").get("noPromiseFallbackSeconds").isNull())
                .as("the editor tells 'none of its own' from a number")
                .isTrue();
        assertThat(editor.get("delivery")
                        .get("effectiveNoPromiseFallbackSeconds")
                        .asInt())
                .isEqualTo(1200);
        assertThat(editor.get("pickup").get("noPromiseFallbackSeconds").asInt()).isEqualTo(1800);
        assertThat(editor.get("pickup").get("effectiveNoPromiseFallbackSeconds").asInt())
                .isEqualTo(1800);
    }

    @Test
    @DisplayName("with nothing set the blank fallback is the platform's forty-five minutes")
    void aBlankFallbackWithNoThresholdIsTheFortyFive() throws Exception {
        publish("TENANT", null, null, mode(null, 0, null), mode(null, 0, null), mode(null, 0, null), null, "blank");

        assertThat(boardPolicy(LOCATION)
                        .get("delivery")
                        .get("noPromiseFallbackSeconds")
                        .asInt())
                .isEqualTo(2700);
        JsonNode editor = read("scopeType=TENANT");
        assertThat(editor.get("noPromiseDefault").get("source").asText()).isEqualTo("PLATFORM_DEFAULT");
        assertThat(editor.get("noPromiseDefault").get("seconds").asInt()).isEqualTo(2700);
    }

    @Test
    @DisplayName("the audit fact tells a blank fallback from a number")
    void theAuditFactDistinguishesABlankFallback() throws Exception {
        publish("TENANT", null, null, mode(null, 0, 1800), mode(null, 0, 1800), mode(null, 0, 1800), null, "first");
        publish("TENANT", null, null, mode(null, 0, null), mode(null, 0, 1800), mode(null, 0, 1800), 1, "second");

        List<String> facts = jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                        WHERE action_code = 'ordering.lateness-policy.authored'
                        ORDER BY occurred_at
                        """).query(String.class).list();
        assertThat(facts).hasSize(2);
        JsonNode second = JSON.readTree(facts.get(1));
        assertThat(second.get("delivery.noPromiseFallbackSeconds").get("before").asInt())
                .isEqualTo(1800);
        assertThat(second.get("delivery.noPromiseFallbackSeconds").get("after").isNull())
                .as("a blank is a null, not a zero and not the default's number")
                .isTrue();
    }

    @Test
    @DisplayName("a zero-minute window is a real window, not 'unset'")
    void zeroIsAWindowOfItsOwn() throws Exception {
        setScalarMinutes(12);
        publish(
                "TENANT",
                null,
                null,
                mode(0, 0, 2700),
                mode(null, 0, 2700),
                mode(null, 0, 2700),
                null,
                "no early warning");

        JsonNode board = boardPolicy(LOCATION);
        assertThat(board.get("delivery").get("atRiskBeforeSeconds").asInt()).isZero();
        assertThat(board.get("pickup").get("atRiskBeforeSeconds").asInt()).isEqualTo(720);
    }

    // -------------------------------------------------------------------- CAS

    @Test
    @DisplayName("a form opened before someone else saved is refused with 409 and both versions, and stores nothing")
    void aStaleExpectedVersionIsRefused() throws Exception {
        publish(
                "TENANT",
                null,
                null,
                mode(300, 0, 2700),
                mode(300, 0, 2700),
                mode(300, 0, 2700),
                null,
                "opened by both");
        publish("TENANT", null, null, mode(600, 0, 2700), mode(600, 0, 2700), mode(600, 0, 2700), 1, "first to save");

        MvcResult stale = attempt(
                "TENANT", null, null, mode(60, 0, 2700), mode(60, 0, 2700), mode(60, 0, 2700), 1, "the slow one");

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = JSON.readTree(stale.getResponse().getContentAsString());
        assertThat(problem.toString()).contains("STALE_VERSION");
        assertThat(problem.toString()).contains("currentVersion");
        assertThat(boardPolicy(LOCATION)
                        .get("delivery")
                        .get("atRiskBeforeSeconds")
                        .asInt())
                .as("the first saver's numbers stand")
                .isEqualTo(600);
        assertThat(read("scopeType=TENANT").get("currentVersionAtScope").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("opening at 'nothing authored here' is stale once anything was authored at that exact scope")
    void anOpenerAtZeroIsStaleOnceThatScopeHoldsADocument() throws Exception {
        publish("BRAND", BRAND, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "first");

        for (Integer expected : new Integer[] {null, 0}) {
            MvcResult stale = attempt(
                    "BRAND", BRAND, null, mode(60, 0, 2700), mode(60, 0, 2700), mode(60, 0, 2700), expected, "second");
            assertThat(stale.getResponse().getStatus())
                    .as("expected " + expected)
                    .isEqualTo(409);
        }
    }

    @Test
    @DisplayName("the same idempotency key replays the answer and publishes one version, not two")
    void aRetriedRequestPublishesOnce() throws Exception {
        String key = "lateness-retry-" + UUID.randomUUID();
        String body =
                body("TENANT", null, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "once");

        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult result = mvc.perform(post(EDITOR)
                            .with(tokenFor(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("attempt " + attempt + ": " + result.getResponse().getContentAsString())
                    .isEqualTo(200);
        }

        assertThat(read("scopeType=TENANT").get("currentVersionAtScope").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a write without an Idempotency-Key is refused")
    void aWriteNeedsAnIdempotencyKey() throws Exception {
        MvcResult result = mvc.perform(post(EDITOR)
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(
                                "TENANT",
                                null,
                                null,
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                null,
                                "x")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    // ------------------------------------------------------------- validation

    @Test
    @DisplayName("windows must be whole minutes within a day, the fallback at least a minute: nothing is stored")
    void outOfBoundsNumbersAreRefusedWithTheFieldNamed() throws Exception {
        MvcResult refused = attempt(
                "TENANT",
                null,
                null,
                mode(90, 0, 2700),
                mode(300, 86_401, 2700),
                mode(300, 0, 30),
                null,
                "bad numbers");

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        String detail = refused.getResponse().getContentAsString();
        assertThat(detail).contains("VALIDATION_FAILED");
        assertThat(detail).contains("DELIVERY atRiskBeforeSeconds");
        assertThat(detail).contains("PICKUP lateAfterSeconds");
        assertThat(detail).contains("DINE_IN noPromiseFallbackSeconds");
        assertThat(read("scopeType=TENANT").get("isPlatformDefault").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("a body missing a boxed field is a validation error naming it, not a malformed body")
    void aMissingGraceIsAValidationErrorNotAMalformedBody() throws Exception {
        String missingGrace = "{\"scopeType\":\"TENANT\","
                + "\"delivery\":{\"atRiskBeforeSeconds\":null,\"noPromiseFallbackSeconds\":2700},"
                + "\"pickup\":" + mode(300, 0, 2700) + ",\"dineIn\":" + mode(300, 0, 2700) + ","
                + "\"expectedVersion\":null,\"reason\":\"x\"}";

        MvcResult refused = send(missingGrace);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .doesNotContain("MALFORMED_BODY")
                .contains("lateAfterSeconds");
    }

    @Test
    @DisplayName("the console's real JSON -- explicit nulls for an unset window and a first version -- is accepted")
    void theConsolesRealBodyIsAccepted() throws Exception {
        String consolesBody = "{\"scopeType\":\"BRAND\",\"brandId\":\"" + BRAND + "\",\"locationId\":null,"
                + "\"delivery\":{\"atRiskBeforeSeconds\":null,\"lateAfterSeconds\":0,\"noPromiseFallbackSeconds\":2700},"
                + "\"pickup\":{\"atRiskBeforeSeconds\":null,\"lateAfterSeconds\":0,\"noPromiseFallbackSeconds\":2700},"
                + "\"dineIn\":{\"atRiskBeforeSeconds\":null,\"lateAfterSeconds\":0,\"noPromiseFallbackSeconds\":2700},"
                + "\"expectedVersion\":null,\"reason\":\"just confirming the defaults\"}";

        MvcResult accepted = send(consolesBody);

        assertThat(accepted.getResponse().getStatus())
                .as(accepted.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a blank reason is refused: a user action carries a why")
    void aBlankReasonIsRefused() throws Exception {
        MvcResult refused =
                attempt("TENANT", null, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "   ");

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("PLATFORM scope is refused, and BRAND or LOCATION without their ids are validation errors")
    void scopeMustBeOneATenantMayWrite() throws Exception {
        assertThat(attempt(
                                "PLATFORM",
                                null,
                                null,
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                null,
                                "x")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(attempt("BRAND", null, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "x")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(attempt(
                                "LOCATION",
                                BRAND,
                                null,
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                null,
                                "x")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(mvc.perform(get(EDITOR + "?scopeType=PLATFORM").with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("another tenant's brand is a 404, never written under this tenant")
    void aBrandOfAnotherTenantIsNotFound() throws Exception {
        MvcResult refused = attempt(
                "BRAND", OTHER_BRAND, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "x");

        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.policies")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    // ----------------------------------------------------------- authorisation

    @Test
    @DisplayName("without the tenant configuration capabilities both read and write are 403")
    void aCallerWithoutTheCapabilityIsRefused() throws Exception {
        MvcResult read = mvc.perform(get(EDITOR + "?scopeType=TENANT").with(tokenFor(STRANGER)))
                .andReturn();
        MvcResult write = mvc.perform(post(EDITOR)
                        .with(tokenFor(STRANGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "stranger-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(
                                "TENANT",
                                null,
                                null,
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                null,
                                "x")))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.policies")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("an owner of another tenant cannot read or write this tenant's document")
    void anotherTenantsOwnerIsRefused() throws Exception {
        MvcResult read = mvc.perform(get(EDITOR + "?scopeType=TENANT").with(tokenFor(OTHER_TENANTS_OWNER)))
                .andReturn();
        MvcResult write = mvc.perform(post(EDITOR)
                        .with(tokenFor(OTHER_TENANTS_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "other-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(
                                "TENANT",
                                null,
                                null,
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                mode(300, 0, 2700),
                                null,
                                "x")))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ audit

    @Test
    @DisplayName("the publication is audited: who, where, why, and the numbers that moved")
    void thePublicationLeavesAFieldLevelAuditFact() throws Exception {
        publish("TENANT", null, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "defaults");
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        publish("TENANT", null, null, mode(300, 0, 2700), mode(300, 120, 2700), mode(300, 0, 2700), 1, "pickup grace");

        List<String> facts = jdbc.sql("""
                        SELECT row_to_json(e)::text FROM (
                            SELECT actor_subject, scope_type, target_type, target_version, reason,
                                   change_document::text AS change_document
                            FROM audit.audit_events
                            WHERE action_code = 'ordering.lateness-policy.authored'
                        ) e
                        """).query(String.class).list();
        assertThat(facts).hasSize(1);
        JsonNode fact = JSON.readTree(facts.getFirst());
        assertThat(fact.get("actor_subject").asText()).isEqualTo(OWNER);
        assertThat(fact.get("scope_type").asText()).isEqualTo("TENANT");
        assertThat(fact.get("target_type").asText()).isEqualTo("ordering.lateness-policy");
        assertThat(fact.get("target_version").asLong()).isEqualTo(2L);
        assertThat(fact.get("reason").asText()).isEqualTo("pickup grace");
        JsonNode change = JSON.readTree(fact.get("change_document").asText());
        assertThat(change.get("pickup.lateAfterSeconds").get("before").asInt()).isZero();
        assertThat(change.get("pickup.lateAfterSeconds").get("after").asInt()).isEqualTo(120);
        assertThat(change.get("delivery.lateAfterSeconds").get("after").asInt()).isZero();
        assertThat(change.get("policyVersion").get("before").asInt()).isEqualTo(1);
        assertThat(change.get("policyVersion").get("after").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------- who changed it, and when (row X.1)

    @Test
    @DisplayName("the trace names, per authored level, the version, who approved it and when it took effect")
    void theTraceCarriesWhoApprovedEachAuthoredLevelAndWhen() throws Exception {
        Instant before = Instant.now().minusSeconds(5);
        publish("TENANT", null, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "defaults");
        publish("TENANT", null, null, mode(300, 0, 2700), mode(300, 90, 2700), mode(300, 0, 2700), 1, "pickup grace");

        JsonNode atBrand = read("scopeType=BRAND&brandId=" + BRAND);

        JsonNode brandRung = atBrand.get("inspectedLevels").get(0);
        assertThat(brandRung.get("scopeType").asText()).isEqualTo("BRAND");
        assertThat(brandRung.get("outcome").asText()).isEqualTo("NOT_SET");
        assertThat(absent(brandRung, "version"))
                .as("nothing is stored at the brand, so there is nobody to name and no date to show")
                .isTrue();
        assertThat(absent(brandRung, "approvedByName")).isTrue();
        assertThat(absent(brandRung, "validFrom")).isTrue();
        JsonNode tenantRung = atBrand.get("inspectedLevels").get(1);
        assertThat(tenantRung.get("scopeType").asText()).isEqualTo("TENANT");
        assertThat(tenantRung.get("version").asInt())
                .as("the version in force at that exact scope, the second publication")
                .isEqualTo(2);
        assertThat(tenantRung.get("approvedByName").asText()).isEqualTo(OWNER_NAME);
        assertThat(Instant.parse(tenantRung.get("validFrom").asText())).isAfter(before);
    }

    @Test
    @DisplayName("the trace holds the approver's name only: the subject id is never in the response (ADR 0029)")
    void theResponseNeverCarriesThePrincipalsId() throws Exception {
        publish("TENANT", null, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "defaults");

        MvcResult result = mvc.perform(
                        get(EDITOR + "?scopeType=BRAND&brandId=" + BRAND).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(OWNER)
                .contains(OWNER_NAME);
    }

    @Test
    @DisplayName(
            "the reply to a publish is stored encrypted: the approver's name is in no clear column, and a retry returns it")
    void theStoredReplyDoesNotHoldTheApproversName() throws Exception {
        // The reply names who approved each rung, from StaffDirectory. The idempotency record keeps
        // that reply for a day, and an unclassified one is kept as plain text (ADR 0029 over ADR 0031).
        String key = "lateness-named-" + UUID.randomUUID();
        String request =
                body("TENANT", null, null, mode(300, 0, 2700), mode(300, 0, 2700), mode(300, 0, 2700), null, "named");

        MvcResult first = mvc.perform(post(EDITOR)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(first.getResponse().getContentAsString())
                .as("the caller is entitled to the name")
                .contains(OWNER_NAME);

        List<String> stored =
                jdbc.sql("""
                SELECT coalesce(response_body, '')
                  FROM platform.idempotency_records
                 WHERE tenant_id = :t
                """).param("t", TENANT).query(String.class).list();
        assertThat(stored)
                .as("the publish is @Idempotent, so a record was written and there is something to inspect")
                .isNotEmpty();
        assertThat(stored)
                .as("a staff member's name exists nowhere in clear (ADR 0029, ADR 0139)")
                .noneSatisfy(body -> assertThat(body).contains(OWNER_NAME));
        assertThat(jdbc.sql("""
                SELECT response_body_protected
                  FROM platform.idempotency_records
                 WHERE tenant_id = :t AND response_body IS NOT NULL
                """).param("t", TENANT).query(Boolean.class).list())
                .as("the record says it holds an envelope, so a body merely dropped would not pass")
                .isNotEmpty()
                .containsOnly(true);

        MvcResult replay = mvc.perform(post(EDITOR)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();
        assertThat(replay.getResponse().getHeader(IdempotencyInterceptor.REPLAYED_HEADER))
                .as("the retry is answered from the record, not by a second publication")
                .isNotNull();
        assertThat(replay.getResponse().getContentAsString())
                .as("and the encrypted reply still decrypts to what the first call returned")
                .contains(OWNER_NAME);
    }

    @Test
    @DisplayName("an approver the tenant has no member record for has a version and a date but no name")
    void anApproverWithNoMemberRecordIsLeftUnnamed() throws Exception {
        jdbc.sql("""
                INSERT INTO tenant.policies
                    (id, key_code, scope_type, tenant_id, version, status, document, document_hash, valid_from,
                     created_by, approved_by)
                VALUES (:id, 'ordering.lateness', 'TENANT', :tenantId, 1, 'ACTIVE',
                        CAST(:document AS jsonb), :hash, now(), 'a-support-session', 'a-support-session')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param(
                        "document",
                        "{\"delivery\":{\"lateAfterSeconds\":0,\"noPromiseFallbackSeconds\":2700},"
                                + "\"pickup\":{\"lateAfterSeconds\":0,\"noPromiseFallbackSeconds\":2700},"
                                + "\"dineIn\":{\"lateAfterSeconds\":0,\"noPromiseFallbackSeconds\":2700}}")
                .param("hash", "0".repeat(64))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.policy_current
                    (key_code, scope_type, tenant_id, policy_id, policy_version, activated_by)
                SELECT key_code, scope_type, tenant_id, id, version, 'a-support-session'
                  FROM tenant.policies WHERE key_code = 'ordering.lateness' AND tenant_id = :tenantId
                """).param("tenantId", TENANT).update();
        policyCache.evict("ordering.lateness", ResourceScope.tenant(TENANT));

        JsonNode tenantRung = read("scopeType=TENANT").get("inspectedLevels").get(0);

        assertThat(tenantRung.get("version").asInt()).isEqualTo(1);
        assertThat(absent(tenantRung, "validFrom")).isFalse();
        assertThat(absent(tenantRung, "approvedByName"))
                .as("the support session is not one of this tenant's people")
                .isTrue();
    }

    @Test
    @DisplayName("a scalar setting's trace names who set each level and when, from the same operations read")
    void aScalarsTraceCarriesWhoSetEachLevelAndWhen() throws Exception {
        setScalarMinutes(7);

        MvcResult result = mvc.perform(get(CONFIG + "/keys/" + AT_RISK + "/resolution?scopeType=BRAND&brandId=" + BRAND)
                        .with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode levels =
                JSON.readTree(result.getResponse().getContentAsString()).get("inspectedLevels");
        assertThat(levels.get(0).get("scopeType").asText()).isEqualTo("BRAND");
        assertThat(absent(levels.get(0), "changedByName")).isTrue();
        assertThat(levels.get(1).get("scopeType").asText()).isEqualTo("TENANT");
        assertThat(levels.get(1).get("outcome").asText()).isEqualTo("VALUE");
        assertThat(levels.get(1).get("version").asLong()).isZero();
        assertThat(levels.get(1).get("changedByName").asText()).isEqualTo(OWNER_NAME);
        assertThat(absent(levels.get(1), "changedAt")).isFalse();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(OWNER);
    }

    // ---------------------------------------------------------------- helpers

    /** A field the wire omits or sends as null: the console treats both as «nothing to show». */
    private static boolean absent(JsonNode node, String field) {
        return !node.has(field) || node.get(field).isNull();
    }

    private static String mode(
            @Nullable Integer atRiskSeconds, int lateAfterSeconds, @Nullable Integer fallbackSeconds) {
        return "{\"atRiskBeforeSeconds\":" + atRiskSeconds + ",\"lateAfterSeconds\":" + lateAfterSeconds
                + ",\"noPromiseFallbackSeconds\":" + fallbackSeconds + "}";
    }

    private static String body(
            String scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String delivery,
            String pickup,
            String dineIn,
            @Nullable Integer expectedVersion,
            String reason) {
        return "{\"scopeType\":\"" + scopeType + "\","
                + "\"brandId\":" + (brandId == null ? "null" : "\"" + brandId + "\"") + ","
                + "\"locationId\":" + (locationId == null ? "null" : "\"" + locationId + "\"") + ","
                + "\"delivery\":" + delivery + ",\"pickup\":" + pickup + ",\"dineIn\":" + dineIn + ","
                + "\"expectedVersion\":" + expectedVersion + ","
                + "\"reason\":\"" + reason + "\"}";
    }

    private MvcResult attempt(
            String scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String delivery,
            String pickup,
            String dineIn,
            @Nullable Integer expectedVersion,
            String reason)
            throws Exception {
        return send(body(scopeType, brandId, locationId, delivery, pickup, dineIn, expectedVersion, reason));
    }

    private MvcResult send(String body) throws Exception {
        return mvc.perform(post(EDITOR)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "lateness-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult publish(
            String scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String delivery,
            String pickup,
            String dineIn,
            @Nullable Integer expectedVersion,
            String reason)
            throws Exception {
        MvcResult result = attempt(scopeType, brandId, locationId, delivery, pickup, dineIn, expectedVersion, reason);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result;
    }

    private JsonNode read(String scopeQuery) throws Exception {
        MvcResult result = mvc.perform(get(EDITOR + "?" + scopeQuery).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** What the boards poll: {@code GET .../orders/lateness-policy}, the resolved thresholds for one location. */
    private JsonNode boardPolicy(UUID location) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/"
                                + location + "/orders/lateness-policy")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private void setScalarMinutes(int minutes) throws Exception {
        setTenantScalar(AT_RISK, minutes);
    }

    private void setLateOrderThreshold(int minutes) throws Exception {
        setTenantScalar(LATE_THRESHOLD, minutes);
    }

    private void setTenantScalar(String code, int minutes) throws Exception {
        MvcResult result = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "scalar-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"TENANT\",\"integerValue\":" + minutes
                                + ",\"explicitNull\":false,\"reason\":\"the shared default\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void insertTenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    private void insertLocation(UUID locationId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'lateness editor endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
                .param("scopeId", tenantId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        /**
         * The tenant's own record of who the owner is. Tenant-scoped like the real one: it answers for
         * this tenant's owner and for nobody else, so a name can never be resolved across tenants.
         */
        @Bean
        @Primary
        StaffDirectory staffDirectory() {
            return new StaffDirectory() {
                @Override
                public @Nullable String nameOf(UUID tenantId, String subject) {
                    return TENANT.equals(tenantId) && OWNER.equals(subject) ? OWNER_NAME : null;
                }

                @Override
                public Map<String, String> namesOf(UUID tenantId, Collection<String> subjects) {
                    return subjects.stream()
                            .filter(subject -> nameOf(tenantId, subject) != null)
                            .collect(Collectors.toMap(subject -> subject, subject -> OWNER_NAME));
                }

                @Override
                public Optional<UUID> memberIdOf(UUID tenantId, String subject) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
