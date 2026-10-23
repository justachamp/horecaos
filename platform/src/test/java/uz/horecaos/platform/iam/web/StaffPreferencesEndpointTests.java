package uz.horecaos.platform.iam.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.staff.StaffPhotos;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0160 over the real HTTP stack: a person's own interface preferences, and only theirs.
 *
 * <p>The spine is the exit criterion. A cook hides «Курьер», adds «Принял» and saves a view; the row that
 * holds it is a tenant, a subject, a sealed document and versions; a colleague, the owner and an
 * administrator have no route that reads it; another tenant cannot reach it; and a second device writing from
 * the version it read learns it is stale.
 *
 * <p>Bodies are the console's own JSON -- explicit nulls for what it did not touch, omitted keys for what it
 * did not set -- because Jackson 3 refuses an omitted primitive and a test written in the server's idiom would
 * not notice.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffPreferencesEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb200-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb200-4000-7000-8000-0000000000b1");
    private static final UUID LOC_1 = UUID.fromString("018fb200-4000-7000-8000-0000000000c1");
    private static final UUID LOC_2 = UUID.fromString("018fb200-4000-7000-8000-0000000000c2");

    private static final UUID OTHER_TENANT = UUID.fromString("018fb200-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fb200-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOC = UUID.fromString("018fb200-4000-7000-8000-0000000000c3");

    private static final String OWNER = "pref-http-owner";
    private static final String ADMIN = "pref-http-admin";
    private static final String COOK_1 = "pref-http-cook-1";
    private static final String COOK_2 = "pref-http-cook-2";
    private static final String BOTH = "pref-http-both-tenants";
    private static final String NO_MEMBER = "pref-http-no-member-row";
    private static final String DEVICE = "pref-http-kitchen-device";
    /** Only the rate-limit test writes as this person: the limiter's window outlives a test method. */
    private static final String LIMITED = "pref-http-rate-limited";

    private static final String OTHER_OWNER = "pref-http-other-owner";

    private static final String VIEW_ID = "0198a3c0-0000-7000-8000-000000000001";
    private static final String NAME_LABEL = "Jasur Karimov";

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
    private StaffMemberService members;

    @Autowired
    @SuppressWarnings("NullAway")
    private TransactionTemplate tx;

    @Autowired
    @SuppressWarnings("NullAway")
    private org.springframework.cache.CacheManager caches;

    @Autowired
    @SuppressWarnings("NullAway")
    private StaffPreferencesController controller;

    @Autowired
    @SuppressWarnings("NullAway")
    private uz.horecaos.platform.web.idempotency.ResponseBodyProtection responseProtection;

    @MockitoBean
    @SuppressWarnings("NullAway")
    private StaffPhotos photos;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        for (String name : caches.getCacheNames()) {
            org.springframework.cache.Cache cache = caches.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }

        seedTenancy(TENANT, BRAND, LOC_1, "pref-http-a");
        seedLocation(TENANT, BRAND, LOC_2, "L2");
        seedTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOC, "pref-http-b");

        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        grant(ADMIN, PlatformRole.TENANT_ADMIN, "TENANT", TENANT, TENANT);
        grant(COOK_1, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);
        grant(COOK_2, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);
        grant(BOTH, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);
        grant(BOTH, PlatformRole.LOCATION_STAFF, "LOCATION", OTHER_LOC, OTHER_TENANT);
        grant(NO_MEMBER, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);
        grant(LIMITED, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);
        grant(DEVICE, PlatformRole.KITCHEN_DEVICE, "LOCATION", LOC_1, TENANT);
        grant(OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT, OTHER_TENANT);

        member(TENANT, OWNER, "Olim", "Owner");
        member(TENANT, ADMIN, "Anvar", "Admin");
        member(TENANT, COOK_1, "Shahlo", "Tursunova");
        member(TENANT, COOK_2, "Rustam", "Qodirov");
        member(TENANT, LIMITED, "Lola", "Limited");
        member(TENANT, BOTH, "Jasur", "Nazarov");
        member(OTHER_TENANT, BOTH, "Jasur", "Nazarov");
        member(OTHER_TENANT, OTHER_OWNER, "Otabek", "Other");
    }

    // ================================================================ the exit criterion

    @Test
    @DisplayName(
            "a cook hides Курьер, adds Принял and saves a view; a second device reads the same, and a colleague reads none of it")
    void aCookSavesTheirBoardAndNobodyElseReadsIt() throws Exception {
        MvcResult empty = get(prefs(), COOK_1);
        assertThat(empty.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(empty).get("surfaces").size())
                .as("nothing saved yet: the code's default applies")
                .isZero();

        MvcResult saved = put(prefs("ORDER_BOARD"), COOK_1, boardBody(NAME_LABEL), null);

        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        assertThat(saved.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        JsonNode body = json(saved);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("documentSchema").asInt()).isEqualTo(2);
        assertThat(body.get("document").get("columns").get("show").get(0).asString())
                .isEqualTo("acceptedBy");
        assertThat(body.get("document").get("columns").get("hide").get(0).asString())
                .isEqualTo("courier");

        // The same person on another machine.
        JsonNode second = json(get(prefs(), COOK_1)).get("surfaces").get("ORDER_BOARD");
        assertThat(second.get("version").asInt()).isEqualTo(1);
        assertThat(second.get("document").get("savedViews").get(0).get("label").asString())
                .isEqualTo(NAME_LABEL);
        assertThat(second.get("document")
                        .get("savedViews")
                        .get(0)
                        .get("filters")
                        .get("lateOnly")
                        .asBoolean())
                .isTrue();

        // A colleague on the same floor, the owner and a tenant administrator have no route to it.
        for (String other : List.of(COOK_2, OWNER, ADMIN)) {
            MvcResult theirs = get(prefs(), other);
            assertThat(theirs.getResponse().getStatus()).as(other).isEqualTo(200);
            assertThat(theirs.getResponse().getContentAsString(UTF_8))
                    .as("%s reads their own, which is empty, and never the cook's", other)
                    .doesNotContain("Karimov", "ORDER_BOARD", "acceptedBy");
        }
    }

    @Test
    @DisplayName("the stored row is a tenant, a subject, a sealed document and versions -- the label is in no column")
    void theStoredRowHoldsNothingReadable() throws Exception {
        put(prefs("ORDER_BOARD"), COOK_1, boardBody(NAME_LABEL), null);

        Map<String, Object> row =
                jdbc.sql("SELECT * FROM iam.staff_preferences").query().singleRow();

        assertThat(row.get("tenant_id")).isEqualTo(TENANT);
        assertThat(row.get("principal_subject")).isEqualTo(COOK_1);
        assertThat(row.get("surface")).isEqualTo("ORDER_BOARD");
        assertThat(((Number) java.util.Objects.requireNonNull(row.get("version"))).intValue())
                .isEqualTo(1);
        assertThat(((Number) java.util.Objects.requireNonNull(row.get("document_schema"))).intValue())
                .as("a document written after batch 21 is stamped with the order board's schema 2")
                .isEqualTo(2);
        assertThat(row.values().stream().map(String::valueOf).collect(Collectors.joining(" | ")))
                .as("neither the typed label nor a key of the vocabulary is readable anywhere in the row")
                .doesNotContain("Jasur", "Karimov", "acceptedBy", "courier", "savedViews", "attention", VIEW_ID);
    }

    @Test
    @DisplayName("a write is not an audit fact, and a read and a write leave nothing readable in the log")
    void nothingIsAuditedAndNothingIsLogged() throws Exception {
        ListAppender<ILoggingEvent> appender = captureAllLogs();
        int auditBefore = auditCount();
        try {
            put(prefs("ORDER_BOARD"), COOK_1, boardBody(NAME_LABEL), null);
            get(prefs(), COOK_1);
            put(prefs("ORDER_BOARD"), COOK_1, boardBody("Updated " + NAME_LABEL), 1);
            put(prefs("ORDER_BOARD"), COOK_1, boardBody("998901234567"), 2);
            delete(prefs("ORDER_BOARD"), COOK_1);
        } finally {
            releaseAllLogs(appender);
        }

        assertThat(auditCount())
                .as("ADR 0160 decision 6: a preference write is neither a business act nor a security one")
                .isEqualTo(auditBefore);
        List<ILoggingEvent> events = snapshot(appender);
        assertThat(events).isNotEmpty();
        List<String> leaking = events.stream()
                .map(event -> event.getLoggerName() + ": " + event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                                ? ""
                                : ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        + java.util.Arrays.toString(event.getArgumentArray()))
                .filter(text -> text.contains("Jasur") || text.contains("Karimov") || text.contains("998901234567"))
                .map(text -> text.substring(0, Math.min(text.length(), 240)))
                .toList();
        // A field path (document.savedViews[0].label) is a vocabulary key; what a person typed is not.
        assertThat(leaking).as("log lines that carry what a person typed").isEmpty();
    }

    // ===================================================================== versions

    @Test
    @DisplayName(
            "the first write has no If-Match, a later one carries the version, and a stale or a second first write is a 409")
    void versionsSerialiseTwoDevices() throws Exception {
        assertThat(put(prefs("ORDER_BOARD"), COOK_1, boardBody("One"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult secondFirst = put(prefs("ORDER_BOARD"), COOK_1, boardBody("Other device"), null);
        assertThat(secondFirst.getResponse().getStatus()).isEqualTo(409);
        assertThat(secondFirst.getResponse().getContentAsString(UTF_8)).contains("STALE_VERSION");

        MvcResult updated = put(prefs("ORDER_BOARD"), COOK_1, boardBody("Two"), 1);
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(updated.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");

        MvcResult stale = put(prefs("ORDER_BOARD"), COOK_1, boardBody("Three"), 1);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString(UTF_8))
                .contains("STALE_VERSION", "\"currentVersion\":2", "\"expectedVersion\":1");

        assertThat(json(get(prefs(), COOK_1))
                        .get("surfaces")
                        .get("ORDER_BOARD")
                        .get("document")
                        .get("savedViews")
                        .get(0)
                        .get("label")
                        .asString())
                .as("the loser changed nothing")
                .isEqualTo("Two");
        assertThat(put(prefs("SHELL"), COOK_1, "{\"document\":{}}", 4)
                        .getResponse()
                        .getStatus())
                .as("a version for a surface never saved")
                .isEqualTo(409);
    }

    @Test
    @DisplayName(
            "a replay of the same Idempotency-Key is the same answer and not a second version, and the key is required")
    void aWriteIsIdempotentAndRequiresItsKey() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = mvc.perform(MockMvcRequestBuilders.put(prefs("ORDER_BOARD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(boardBody("Once"))
                        .header("Idempotency-Key", key)
                        .with(tokenFor(COOK_1)))
                .andReturn();
        MvcResult replay = mvc.perform(MockMvcRequestBuilders.put(prefs("ORDER_BOARD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(boardBody("Once"))
                        .header("Idempotency-Key", key)
                        .with(tokenFor(COOK_1)))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT version FROM iam.staff_preferences")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);

        MvcResult noKey = mvc.perform(MockMvcRequestBuilders.put(prefs("ORDER_BOARD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(boardBody("Twice"))
                        .header("If-Match", "W/\"1\"")
                        .with(tokenFor(COOK_1)))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString(UTF_8)).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName(
            "the reply an idempotent write keeps for a day is sealed under the tenant's key, not the document in clear")
    void theStoredReplyIsSealed() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = mvc.perform(MockMvcRequestBuilders.put(prefs("ORDER_BOARD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(boardBody(NAME_LABEL))
                        .header("Idempotency-Key", key)
                        .with(tokenFor(COOK_1)))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        List<String> kept = jdbc.sql("SELECT response_body FROM platform.idempotency_records")
                .query(String.class)
                .list();
        assertThat(kept).as("the write left a record to replay from").isNotEmpty();
        assertThat(kept)
                .as(
                        "ADR 0029 over ADR 0031: a typed label is personal data, and a table that keeps replies must not keep it readable")
                .allSatisfy(body -> assertThat(body).doesNotContain("Jasur", "Karimov", "savedViews", "acceptedBy"));

        MvcResult replay = mvc.perform(MockMvcRequestBuilders.put(prefs("ORDER_BOARD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(boardBody(NAME_LABEL))
                        .header("Idempotency-Key", key)
                        .with(tokenFor(COOK_1)))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString(UTF_8))
                .as("and the owner of the key still gets exactly the reply the first call produced")
                .isEqualTo(first.getResponse().getContentAsString(UTF_8));
    }

    @Test
    @DisplayName(
            "every route that answers with a document is classified PERSONAL, so no one can quietly make it readable")
    void everyDocumentReplyIsClassified() {
        for (String name : List.of("saveMyPreference", "readMyPreferences")) {
            java.lang.reflect.Method handler = java.util.Arrays.stream(
                            StaffPreferencesController.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
            assertThat(responseProtection.classificationOf(
                            new org.springframework.web.method.HandlerMethod(controller, handler)))
                    .as(name)
                    .contains(uz.horecaos.platform.iam.api.protection.DataClass.PERSONAL);
        }
    }

    // ===================================================================== bodies

    @Test
    @DisplayName(
            "the console's real JSON is accepted: explicit nulls for what it did not touch, omitted keys for what it did not set")
    void theConsolesRealJsonIsAccepted() throws Exception {
        String consoleBody = """
                {"document":{"columns":{"show":["createdBy","courierType"],"hide":null},
                 "savedViews":[{"id":"%s","label":"Late deliveries","tab":"delivering",
                   "filters":{"allBranches":false,"channelCode":null,"origin":null,"fulfillmentMode":"DELIVERY",
                              "paymentMethodCode":null,"paymentStatus":null,"fiscalStatus":null,"lateOnly":true,
                              "problemOnly":false,"callbackRequested":false,"mineOnly":false,"dateRange":"TODAY"}}]}}
                """.formatted(VIEW_ID);

        MvcResult saved = put(prefs("ORDER_BOARD"), COOK_1, consoleBody, null);

        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        JsonNode filters = json(saved).get("document").get("savedViews").get(0).get("filters");
        assertThat(filters.propertyNames())
                .as("a null and a false are the default and are not stored; what remains is the change")
                .containsExactlyInAnyOrder("lateOnly", "fulfillmentMode", "dateRange");
        assertThat(put(prefs("SHELL"), COOK_1, "{\"document\":{\"defaultLocationId\":null}}", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(put(prefs("SHELL"), COOK_1, "{\"document\":{}}", 1)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "a body that is not JSON, or has no document, or has the wrong kind of one, is a 400 that names what is wrong")
    void malformedBodies() throws Exception {
        MvcResult notJson = put(prefs("ORDER_BOARD"), COOK_1, "{\"document\":", null);
        assertThat(notJson.getResponse().getStatus()).isEqualTo(400);
        assertThat(notJson.getResponse().getContentAsString(UTF_8)).contains("MALFORMED_BODY");

        for (String body :
                List.of("{}", "{\"document\":null}", "{\"document\":\"x\"}", "{\"document\":[]}", "{\"document\":7}")) {
            MvcResult refused = put(prefs("ORDER_BOARD"), COOK_1, body, null);
            assertThat(refused.getResponse().getStatus()).as(body).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString(UTF_8))
                    .as(body)
                    .contains("VALIDATION_FAILED", "\"field\":\"document\"");
        }
    }

    @Test
    @DisplayName("a document the schema refuses is a 400 naming the field, and nothing is stored")
    void aRefusedDocumentNamesTheFieldAndStoresNothing() throws Exception {
        Map<String, String> refusals = Map.of(
                "{\"document\":{\"theme\":\"dark\"}}",
                "document.theme",
                "{\"document\":{\"columns\":{\"hide\":[\"number\"]}}}",
                "document.columns.hide",
                "{\"document\":{\"columns\":{\"show\":[\"courier_eta\"]}}}",
                "document.columns.show",
                "{\"document\":{\"columns\":{\"hide\":[\"courierEta\"]}}}",
                "document.columns.hide",
                "{\"document\":{\"savedViews\":[{\"id\":\"" + VIEW_ID
                        + "\",\"label\":\"+998 90 123 45 67\",\"tab\":\"all\",\"filters\":{}}]}}",
                "document.savedViews[0].label",
                "{\"document\":{\"savedViews\":[{\"id\":\"" + VIEW_ID
                        + "\",\"label\":\"x\",\"tab\":\"all\",\"filters\":{\"reference\":\"1042\"}}]}}",
                "document.savedViews[0].filters.reference");
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            MvcResult refused = put(prefs("ORDER_BOARD"), COOK_1, refusal.getKey(), null);
            assertThat(refused.getResponse().getStatus()).as(refusal.getKey()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString(UTF_8))
                    .contains("VALIDATION_FAILED", refusal.getValue())
                    .doesNotContain("998 90 123 45 67", "1042");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM iam.staff_preferences")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("an unknown surface is a 404 on every verb, and a surface is spelled exactly")
    void anUnknownSurfaceIsNotFound() throws Exception {
        for (String surface : List.of("THEME", "order_board", "ORDER-BOARD", "SHELL2")) {
            assertThat(put(prefs(surface), COOK_1, "{\"document\":{}}", null)
                            .getResponse()
                            .getStatus())
                    .as("PUT " + surface)
                    .isEqualTo(404);
            assertThat(delete(prefs(surface), COOK_1).getResponse().getStatus())
                    .as("DELETE " + surface)
                    .isEqualTo(404);
        }
    }

    // ==================================================================== isolation

    @Test
    @DisplayName("the subject comes from the token and never from the body")
    void theSubjectIsTheTokens() throws Exception {
        MvcResult saved = put(
                prefs("ORDER_BOARD"),
                COOK_1,
                "{\"principalSubject\":\"" + COOK_2 + "\",\"subject\":\"" + COOK_2 + "\",\"memberId\":\""
                        + UUID.randomUUID() + "\",\"document\":{\"columns\":{\"hide\":[\"items\"]}}}",
                null);

        assertThat(saved.getResponse().getStatus()).isIn(200, 400);
        assertThat(jdbc.sql("SELECT principal_subject FROM iam.staff_preferences")
                        .query(String.class)
                        .list())
                .as("whatever the body said, the row is the caller's or there is no row")
                .allMatch(COOK_1::equals);
        assertThat(json(get(prefs(), COOK_2)).get("surfaces").size()).isZero();
    }

    @Test
    @DisplayName(
            "another tenant's owner is refused this tenant's route, and one person in two tenants keeps two separate documents")
    void tenantsAreIsolated() throws Exception {
        put(prefs("ORDER_BOARD"), BOTH, boardBody("In A"), null);

        MvcResult outsider = get(prefs(), OTHER_OWNER);
        assertThat(outsider.getResponse().getStatus()).isEqualTo(403);
        assertThat(put(prefs("ORDER_BOARD"), OTHER_OWNER, boardBody("Intruder"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(delete(prefs("ORDER_BOARD"), OTHER_OWNER).getResponse().getStatus())
                .isEqualTo(403);

        assertThat(json(get(prefs(OTHER_TENANT), BOTH)).get("surfaces").size())
                .as("the same account in the other tenant starts empty")
                .isZero();
        put(prefs(OTHER_TENANT, "ORDER_BOARD"), BOTH, boardBody("In B"), null);
        assertThat(label(get(prefs(), BOTH))).isEqualTo("In A");
        assertThat(label(get(prefs(OTHER_TENANT), BOTH))).isEqualTo("In B");
        assertThat(jdbc.sql("SELECT count(*) FROM iam.staff_preferences")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an account with no member row answers 404, a device with no self-service capability answers 403")
    void noMemberRowNoPreferences() throws Exception {
        for (MvcResult answer : List.of(
                get(prefs(), NO_MEMBER),
                put(prefs("ORDER_BOARD"), NO_MEMBER, boardBody("x"), null),
                delete(prefs("ORDER_BOARD"), NO_MEMBER))) {
            assertThat(answer.getResponse().getStatus()).isEqualTo(404);
            assertThat(answer.getResponse().getContentAsString(UTF_8)).contains("RESOURCE_NOT_FOUND");
        }
        assertThat(get(prefs(), DEVICE).getResponse().getStatus()).isEqualTo(403);
        assertThat(put(prefs("ORDER_BOARD"), DEVICE, boardBody("x"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.staff_preferences")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    // ===================================================================== default branch

    @Test
    @DisplayName("a default branch must be one the person works at: the cook's own is saved, a sibling branch is a 400")
    void theDefaultBranch() throws Exception {
        MvcResult own = put(prefs("SHELL"), COOK_1, "{\"document\":{\"defaultLocationId\":\"" + LOC_1 + "\"}}", null);
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(own).get("document").get("defaultLocationId").asString())
                .isEqualTo(LOC_1.toString());

        MvcResult sibling = put(prefs("SHELL"), COOK_1, "{\"document\":{\"defaultLocationId\":\"" + LOC_2 + "\"}}", 1);
        assertThat(sibling.getResponse().getStatus()).isEqualTo(400);
        assertThat(sibling.getResponse().getContentAsString(UTF_8))
                .contains("VALIDATION_FAILED", "document.defaultLocationId");

        MvcResult foreign =
                put(prefs("SHELL"), OWNER, "{\"document\":{\"defaultLocationId\":\"" + OTHER_LOC + "\"}}", null);
        assertThat(foreign.getResponse().getStatus())
                .as("a branch of another tenant, even for a tenant-wide owner")
                .isEqualTo(400);
        assertThat(put(prefs("SHELL"), OWNER, "{\"document\":{\"defaultLocationId\":\"" + LOC_2 + "\"}}", null)
                        .getResponse()
                        .getStatus())
                .as("a tenant-wide owner works at every branch of the tenant")
                .isEqualTo(200);
    }

    // ======================================================================= reset

    @Test
    @DisplayName("DELETE resets a surface to its default; resetting what was never saved is the same 204")
    void aResetDeletes() throws Exception {
        put(prefs("ORDER_BOARD"), COOK_1, boardBody("Gone"), null);
        put(prefs("SHELL"), COOK_1, "{\"document\":{\"defaultLocationId\":\"" + LOC_1 + "\"}}", null);

        assertThat(delete(prefs("ORDER_BOARD"), COOK_1).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(delete(prefs("ORDER_BOARD"), COOK_1).getResponse().getStatus())
                .isEqualTo(204);

        JsonNode surfaces = json(get(prefs(), COOK_1)).get("surfaces");
        assertThat(surfaces.has("ORDER_BOARD")).isFalse();
        assertThat(surfaces.has("SHELL")).as("only that surface is reset").isTrue();
        assertThat(put(prefs("ORDER_BOARD"), COOK_1, boardBody("Again"), null)
                        .getResponse()
                        .getStatus())
                .as("after a reset the next write is a first write")
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "a reset does not take the version back: a tablet that read before it is told 409 once the surface is saved again")
    void aStaleDeviceCannotWriteOverADocumentSavedAfterAReset() throws Exception {
        put(prefs("ORDER_BOARD"), COOK_1, boardBody("Read by the old tablet"), null);
        put(prefs("ORDER_BOARD"), COOK_1, boardBody("Edited on the laptop"), 1);
        assertThat(delete(prefs("ORDER_BOARD"), COOK_1).getResponse().getStatus())
                .isEqualTo(204);

        MvcResult fresh = put(prefs("ORDER_BOARD"), COOK_1, boardBody("Fresh start"), null);
        assertThat(fresh.getResponse().getStatus()).isEqualTo(200);
        assertThat(fresh.getResponse().getHeader("ETag"))
                .as("the reset was a version of its own, and the document saved after it is the next")
                .isEqualTo("W/\"4\"");

        MvcResult late = put(prefs("ORDER_BOARD"), COOK_1, boardBody("The old tablet's late edit"), 1);
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(late.getResponse().getContentAsString(UTF_8)).contains("STALE_VERSION");
        assertThat(label(get(prefs(), COOK_1))).isEqualTo("Fresh start");
        assertThat(put(prefs("ORDER_BOARD"), COOK_1, boardBody("Next edit"), 4)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    // ===================================================================== rate limit

    @Test
    @DisplayName("sixty changes a minute are allowed to a person and the sixty-first is a 429 that says when to retry")
    void writesAreRateLimitedPerPerson() throws Exception {
        // A token bucket: sixty to spend, one more back every second. The sixty are spent in a few seconds,
        // so a loop that keeps going finds the wall well before a minute of refilling could hide it.
        int allowed = 0;
        MvcResult limited = null;
        for (int attempt = 0; attempt < 400 && limited == null; attempt++) {
            MvcResult answer = delete(prefs("ORDER_BOARD"), LIMITED);
            if (answer.getResponse().getStatus() == 429) {
                limited = answer;
            } else {
                assertThat(answer.getResponse().getStatus()).isEqualTo(204);
                allowed++;
            }
        }

        MvcResult wall = java.util.Objects.requireNonNull(limited, "a loop of changes meets the wall");
        assertThat(allowed).as("sixty are allowed before it").isGreaterThanOrEqualTo(60);
        assertThat(wall.getResponse().getContentAsString(UTF_8)).contains("RATE_LIMIT_EXCEEDED", "retryAfterSeconds");
        assertThat(put(prefs("ORDER_BOARD"), COOK_2, boardBody("A colleague is not limited"), null)
                        .getResponse()
                        .getStatus())
                .as("the budget is per person")
                .isEqualTo(200);
        assertThat(get(prefs(), LIMITED).getResponse().getStatus())
                .as("reads are not rationed")
                .isEqualTo(200);
    }

    // ===================================================================== fixtures

    private static String prefs() {
        return prefs(TENANT);
    }

    private static String prefs(UUID tenant) {
        return "/api/v1/operations/tenants/" + tenant + "/staff/me/preferences";
    }

    private static String prefs(String surface) {
        return prefs() + "/" + surface;
    }

    private static String prefs(UUID tenant, String surface) {
        return prefs(tenant) + "/" + surface;
    }

    /** What the board sends: Принял on, Курьер off, one saved view -- the exit criterion's own choices. */
    private static String boardBody(String label) {
        return """
                {"document":{"columns":{"show":["acceptedBy"],"hide":["courier"]},
                 "savedViews":[{"id":"%s","label":"%s","tab":"attention","filters":{"lateOnly":true}}]}}
                """.formatted(VIEW_ID, label);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private String label(MvcResult result) throws Exception {
        return json(result)
                .get("surfaces")
                .get("ORDER_BOARD")
                .get("document")
                .get("savedViews")
                .get(0)
                .get("label")
                .asString();
    }

    private int auditCount() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events")
                .query(Integer.class)
                .single();
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult put(String path, String subject, String json, @Nullable Integer ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.PUT, path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(tokenFor(subject));
        if (ifMatch != null) {
            request.header("If-Match", "W/\"" + ifMatch + "\"");
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult delete(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete(path)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(subject)))
                .andReturn();
    }

    /** A member the way the invitation flow makes one: registered, then activated with the typed name. */
    private void member(UUID tenantId, String subject, String first, String last) {
        tx.executeWithoutResult(status -> {
            members.registerInvited(tenantId, subject, first, last, null, "fixture-inviter", "corr-fixture");
            members.activate(tenantId, subject, first, last, "corr-fixture");
        });
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'staff preferences endpoint test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
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
        seedLocation(tenantId, brandId, locationId, "L1");
    }

    private void seedLocation(UUID tenantId, UUID brandId, UUID locationId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    private static ch.qos.logback.classic.@Nullable Level previousRootLevel;

    /** At {@code Level.ALL}: what an operator chasing a bug with tracing on would see. */
    private static ListAppender<ILoggingEvent> captureAllLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previousRootLevel = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.ALL);
        root.addAppender(appender);
        return appender;
    }

    private static void releaseAllLogs(ListAppender<ILoggingEvent> appender) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        root.setLevel(previousRootLevel);
        appender.stop();
    }

    /** Logback's list appender synchronizes on itself, so a copy under that lock is stable. */
    private static List<ILoggingEvent> snapshot(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
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
