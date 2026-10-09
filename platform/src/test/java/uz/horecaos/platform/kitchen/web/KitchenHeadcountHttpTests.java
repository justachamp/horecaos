package uz.horecaos.platform.kitchen.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * ADR 0163 through the real HTTP stack: the cook-headcount plan read and the productivity policy that
 * feeds it. The arithmetic is proved on its own in {@code HeadcountPlannerTests}, the fact's rules in {@code
 * KitchenExecutionTests.StationLoad}; this proves the doors in front of them (capabilities, scope, tenant
 * isolation, {@code If-Match}, replace-not-merge) and that a plan is drawn from the station-load rows and the
 * tenant's own figure and from nothing else.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenHeadcountHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static final UUID TENANT = UUID.fromString("018fe300-5000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fe300-5000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fe300-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe300-5000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fe300-5000-7000-8000-0000000000c2");
    private static final UUID GRILL = UUID.fromString("018fe300-5000-7000-8000-0000000000d1");
    private static final UUID HOT = UUID.fromString("018fe300-5000-7000-8000-0000000000d2");
    private static final UUID PASS = UUID.fromString("018fe300-5000-7000-8000-0000000000d3");
    private static final UUID OTHER_GRILL = UUID.fromString("018fe300-5000-7000-8000-0000000000d4");

    /** LOCATION_MANAGER at LOCATION: holds kitchen.headcount.read and kitchen.productivity.manage there. */
    private static final String MANAGER = "headcount-manager";

    private static final String OWNER = "headcount-owner";

    /** A tenant-defined role with the ticket read only: a line cook. */
    private static final String LINE = "headcount-line";

    private static final String OTHER_OWNER = "headcount-other-owner";
    private static final String OTHER_BRANCH_MANAGER = "headcount-other-branch";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the headcount HTTP test");
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

    /** The weekday under test, and the four most recent dates of it, newest first. */
    private int weekday;

    private List<LocalDate> dates;

    @BeforeEach
    void reset() {
        Cache policyCurrent = cacheManager.getCache("tenant.policy_current");
        if (policyCurrent != null) {
            policyCurrent.clear();
        }
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE reporting.fact_station_load").update();
        jdbc.sql("TRUNCATE TABLE kitchen.station_capacity, kitchen.stations CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        LocalDate today = LocalDate.now(TASHKENT);
        weekday = today.getDayOfWeek().getValue();
        dates = List.of(today.minusDays(7), today.minusDays(14), today.minusDays(21), today.minusDays(28));

        seedTenancy();
        roleRegistry.synchronize();
        grant(MANAGER, roleId(PlatformRole.LOCATION_MANAGER), true, "LOCATION", LOCATION, TENANT);
        grant(OWNER, roleId(PlatformRole.TENANT_OWNER), true, "TENANT", TENANT, TENANT);
        grant(OTHER_OWNER, roleId(PlatformRole.TENANT_OWNER), true, "TENANT", OTHER_TENANT, OTHER_TENANT);
        grant(OTHER_BRANCH_MANAGER, roleId(PlatformRole.LOCATION_MANAGER), true, "LOCATION", OTHER_LOCATION, TENANT);
        UUID line = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :t, 'LINE_COOK', 'Line cook', 'LOCATION', 'ACTIVE', false)
                """).param("id", line).param("t", TENANT).update();
        jdbc.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:r, 'kitchen.ticket.read')")
                .param("r", line)
                .update();
        grant(LINE, line, false, "LOCATION", LOCATION, TENANT);
        seedStations();
    }

    // ------------------------------------------------------------------ the plan

    @Test
    @DisplayName("a plan is the fact's same-weekday history divided by the tenant's own figure, per station and hour")
    void thePlanComesFromTheFactAndTheTenantsFigure() throws Exception {
        seedFridays();
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20,"HOT":30},"reason":"stated by the kitchen manager"}""");

        JsonNode plan = plan(MANAGER, LOCATION, "weekday=" + weekday);

        assertThat(plan.path("status").asText()).isEqualTo("OK");
        assertThat(plan.path("targetDate").asText())
                .as("today counts when today is still that weekday")
                .isEqualTo(LocalDate.now(TASHKENT).toString());
        assertThat(plan.path("sampleDates")).hasSize(4);
        assertThat(plan.path("generatedFrom").path("metricId").asText()).isEqualTo("kitchen_station_portions");
        assertThat(plan.path("generatedFrom").path("calculationVersion").asInt())
                .isEqualTo(1);

        JsonNode grillNoon = rowOf(plan, "GRILL-1", 12);
        assertThat(grillNoon.path("status").asText()).isEqualTo("OK");
        assertThat(decimal(grillNoon, "expectedPortions")).isEqualByComparingTo("50.000");
        assertThat(decimal(grillNoon, "lowPortions")).isEqualByComparingTo("39.536");
        assertThat(decimal(grillNoon, "highPortions")).isEqualByComparingTo("60.464");
        assertThat(grillNoon.path("portionsPerCookHour").asInt()).isEqualTo(20);
        assertThat(grillNoon.path("cooksExpected").asInt()).isEqualTo(3);
        assertThat(grillNoon.path("cooksAtPeakOfBand").asInt()).isEqualTo(4);
        assertThat(grillNoon.path("stationCeiling").isNull()).isTrue();

        assertThat(plan.path("peak").path("hour").asInt()).isEqualTo(19);
        assertThat(plan.path("peak").path("cooksExpected").asInt())
                .as("grill 4 + hot 2")
                .isEqualTo(6);
        assertThat(plan.path("stationsWithoutFigure")).isEmpty();
    }

    @Test
    @DisplayName("the pass is never planned, though its load is in the fact")
    void expoIsNeverPlanned() throws Exception {
        seedFridays();
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20,"HOT":30},"reason":"x"}""");

        JsonNode plan = plan(MANAGER, LOCATION, "weekday=" + weekday);

        assertThat(plan.path("rows"))
                .isNotEmpty()
                .noneMatch(row -> row.path("stationCode").asText().equals("PASS"));
        assertThat(plan.path("stationsWithoutFigure"))
                .noneMatch(code -> code.asText().equals("PASS"));
    }

    @Test
    @DisplayName("nothing published means NO_POLICY for every station and no number: the platform supplies none")
    void noPolicyIsNoNumber() throws Exception {
        seedFridays();

        JsonNode plan = plan(MANAGER, LOCATION, "weekday=" + weekday);

        assertThat(plan.path("rows")).isNotEmpty().allSatisfy(row -> {
            assertThat(row.path("status").asText()).isEqualTo("NO_POLICY");
            assertThat(row.path("cooksExpected").isNull()).isTrue();
            assertThat(row.path("expectedPortions").isNull()).isTrue();
            assertThat(row.path("portionsPerCookHour").isNull()).isTrue();
        });
        assertThat(plan.path("stationsWithoutFigure"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("GRILL-1", "HOT-1");
        assertThat(plan.path("peak").isNull()).isTrue();
    }

    @Test
    @DisplayName("a station's own figure beats its role's")
    void aStationOverrideBeatsItsRole() throws Exception {
        seedFridays();
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20,"HOT":30},"stations":{"GRILL-1":40},"reason":"the big stove"}""");

        JsonNode grillNoon = rowOf(plan(MANAGER, LOCATION, "weekday=" + weekday), "GRILL-1", 12);

        assertThat(grillNoon.path("portionsPerCookHour").asInt()).isEqualTo(40);
        assertThat(grillNoon.path("cooksExpected").asInt()).as("ceil(50 / 40)").isEqualTo(2);
    }

    @Test
    @DisplayName("a brand's document replaces the tenant's rather than merging: the stations it omits have no figure")
    void aBrandDocumentReplacesTheTenants() throws Exception {
        seedFridays();
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20,"HOT":30},"reason":"tenant default"}""");
        publish(OWNER, BRAND, null, "0", """
                {"schema":1,"roles":{"GRILL":25},"reason":"this cuisine is slower on the hot line"}""");

        JsonNode plan = plan(MANAGER, LOCATION, "weekday=" + weekday);

        assertThat(rowOf(plan, "GRILL-1", 12).path("portionsPerCookHour").asInt())
                .isEqualTo(25);
        assertThat(rowOf(plan, "HOT-1", 12).path("status").asText())
                .as("the tenant's HOT figure is not inherited into a document that does not name it")
                .isEqualTo("NO_POLICY");
        assertThat(plan.path("stationsWithoutFigure"))
                .extracting(JsonNode::asText)
                .containsExactly("HOT-1");
    }

    @Test
    @DisplayName("fewer than three qualifying dates is TOO_FEW_DATES: no number, and the raw per-date portions")
    void tooFewDatesRefuses() throws Exception {
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20},"reason":"x"}""");
        fact(dates.get(0), LOCATION, GRILL, "GRILL-1", "GRILL", 12, "50");
        fact(dates.get(1), LOCATION, GRILL, "GRILL-1", "GRILL", 12, "60");

        JsonNode plan = plan(MANAGER, LOCATION, "weekday=" + weekday);

        assertThat(plan.path("status").asText()).isEqualTo("TOO_FEW_DATES");
        assertThat(plan.path("rows")).isNotEmpty().allSatisfy(row -> {
            assertThat(row.path("cooksExpected").isNull()).isTrue();
            assertThat(row.path("expectedPortions").isNull()).isTrue();
        });
        assertThat(plan.path("history")).hasSize(2);
        assertThat(plan.path("peak").isNull()).isTrue();
    }

    @Test
    @DisplayName("only the branch's own rows are read: another branch's heavy Friday does not move this plan")
    void anotherBranchesLoadIsNotRead() throws Exception {
        seedFridays();
        for (LocalDate date : dates) {
            fact(date, OTHER_LOCATION, OTHER_GRILL, "GRILL-1", "GRILL", 12, "9000");
        }
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20,"HOT":30},"reason":"x"}""");

        JsonNode grillNoon = rowOf(plan(MANAGER, LOCATION, "weekday=" + weekday), "GRILL-1", 12);

        assertThat(decimal(grillNoon, "expectedPortions")).isEqualByComparingTo("50.000");
    }

    @Test
    @DisplayName("another branch's dates do not qualify this one: two of its own Fridays stay TOO_FEW_DATES")
    void anotherBranchesDatesDoNotQualify() throws Exception {
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20},"reason":"x"}""");
        fact(dates.get(0), LOCATION, GRILL, "GRILL-1", "GRILL", 12, "50");
        fact(dates.get(1), LOCATION, GRILL, "GRILL-1", "GRILL", 12, "60");
        for (LocalDate date : dates) {
            fact(date, OTHER_LOCATION, OTHER_GRILL, "GRILL-1", "GRILL", 12, "70");
        }

        JsonNode plan = plan(MANAGER, LOCATION, "weekday=" + weekday);

        assertThat(plan.path("status").asText()).isEqualTo("TOO_FEW_DATES");
        assertThat(plan.path("sampleDates")).hasSize(2);
    }

    @Test
    @DisplayName("plantLimited is set when the station's own ceiling for the hour is below the top of the band")
    void plantLimitedWhenTheCeilingIsBelowTheBand() throws Exception {
        seedFridays();
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20,"HOT":30},"reason":"x"}""");
        jdbc.sql("""
                INSERT INTO kitchen.station_capacity (id, tenant_id, brand_id, location_id, station_id, weekday,
                    window_start, window_end, portions_per_hour)
                VALUES (:id, :t, :b, :loc, :station, :weekday, '11:00', '13:00', 55)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("station", GRILL)
                .param("weekday", weekday)
                .update();

        JsonNode plan = plan(MANAGER, LOCATION, "weekday=" + weekday);

        JsonNode noon = rowOf(plan, "GRILL-1", 12);
        assertThat(noon.path("stationCeiling").asInt()).isEqualTo(55);
        assertThat(noon.path("plantLimited").asBoolean()).as("60.464 > 55").isTrue();
        JsonNode evening = rowOf(plan, "GRILL-1", 19);
        assertThat(evening.path("stationCeiling").isNull())
                .as("no window covers 19:00")
                .isTrue();
        assertThat(evening.path("plantLimited").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("holidayMode=WEIGHT counts a flagged date at half; INCLUDE, the default, does not")
    void holidayModeChangesTheMean() throws Exception {
        seedFridays();
        publish(OWNER, null, null, "0", """
                {"schema":1,"roles":{"GRILL":20,"HOT":30},"reason":"x"}""");
        // A country with no seeded calendar, so no recurring holiday can land on a sample date by accident.
        jdbc.sql("UPDATE tenant.tenants SET country_code = 'ZZ' WHERE id = :id")
                .param("id", TENANT)
                .update();
        UUID holidayRow = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.public_holidays (id, country_code, name, holiday_date, created_by)
                VALUES (:id, 'ZZ', 'A day off', :date, 'test')
                """).param("id", holidayRow).param("date", dates.get(1)).update();
        try {
            JsonNode included = rowOf(plan(MANAGER, LOCATION, "weekday=" + weekday), "GRILL-1", 12);
            JsonNode weighted =
                    rowOf(plan(MANAGER, LOCATION, "weekday=" + weekday + "&holidayMode=WEIGHT"), "GRILL-1", 12);

            assertThat(decimal(included, "expectedPortions")).isEqualByComparingTo("50.000");
            assertThat(decimal(weighted, "expectedPortions"))
                    .as("(50 + 60 * 0.5 + 50 + 40) / 3.5 for the flagged second-newest date")
                    .isEqualByComparingTo("48.571");
        } finally {
            jdbc.sql("DELETE FROM tenant.public_holidays WHERE id = :id")
                    .param("id", holidayRow)
                    .update();
        }
    }

    @Test
    @DisplayName("a weekday outside 1..7, a sample under three and an unknown location are refused with stable codes")
    void badRequestsAreRefused() throws Exception {
        assertThat(getPlan(MANAGER, LOCATION, "weekday=0").status()).isEqualTo(400);
        assertThat(getPlan(MANAGER, LOCATION, "weekday=8").status()).isEqualTo(400);
        Response thin = getPlan(MANAGER, LOCATION, "weekday=" + weekday + "&sampleSize=2");
        assertThat(thin.status()).isEqualTo(400);
        assertThat(thin.json().path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(getPlan(MANAGER, LOCATION, "weekday=" + weekday + "&holidayMode=BOGUS")
                        .status())
                .isEqualTo(400);

        Response unknown = getPlan(OWNER, UUID.randomUUID(), "weekday=" + weekday);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.json().path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("the plan is refused to a line cook, to another branch's manager and to another tenant's owner")
    void theReadIsScopedAndCapabilityGated() throws Exception {
        seedFridays();

        assertThat(getPlan(LINE, LOCATION, "weekday=" + weekday).status())
                .as("a location-scoped line cook holds kitchen.ticket.read, not kitchen.headcount.read")
                .isEqualTo(403);
        assertThat(getPlan(OTHER_BRANCH_MANAGER, LOCATION, "weekday=" + weekday).status())
                .as("a manager of a sibling branch")
                .isEqualTo(403);
        assertThat(getPlan(OTHER_OWNER, LOCATION, "weekday=" + weekday).status())
                .as("another tenant's owner")
                .isEqualTo(403);
        assertThat(getPlan(MANAGER, LOCATION, "weekday=" + weekday).status()).isEqualTo(200);
        assertThat(getPlan(OWNER, LOCATION, "weekday=" + weekday).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ the productivity policy

    @Test
    @DisplayName("nothing published reads as the empty document, and says it is the default")
    void theDefaultIsTheEmptyDocument() throws Exception {
        Response read = fetch(policyPath(null, null), OWNER);

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().path("isDefault").asBoolean()).isTrue();
        assertThat(read.json().path("roles").size()).isZero();
        assertThat(read.json().path("stations").size()).isZero();
        assertThat(read.json().path("versionAtScope").asInt()).isZero();
        assertThat(read.etag()).isEqualTo("W/\"0\"");
    }

    @Test
    @DisplayName("publishing is whole-document under If-Match, versioned, audited, and a stale version is refused")
    void publishingIsVersionedAndAudited() throws Exception {
        long before = audited("kitchen.station_productivity.published");

        Response first = send(policyPath(null, null), OWNER, "p1", "0", """
                {"schema":1,"roles":{"GRILL":20},"reason":"first figure"}""");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.etag()).isEqualTo("W/\"1\"");
        assertThat(first.json().path("roles").path("GRILL").asInt()).isEqualTo(20);

        Response second = send(policyPath(null, null), OWNER, "p2", "1", """
                {"schema":1,"roles":{"GRILL":24,"HOT":30},"reason":"measured"}""");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.etag()).isEqualTo("W/\"2\"");

        Response stale = send(policyPath(null, null), OWNER, "p3", "1", """
                {"schema":1,"roles":{"GRILL":99},"reason":"stale"}""");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json().path("code").asText()).isEqualTo("STALE_VERSION");

        assertThat(audited("kitchen.station_productivity.published") - before).isEqualTo(2);
        assertThat(fetch(policyPath(null, null), OWNER)
                        .json()
                        .path("roles")
                        .path("GRILL")
                        .asInt())
                .as("the refused write changed nothing")
                .isEqualTo(24);
    }

    @Test
    @DisplayName("a figure outside 1..1000, EXPO, an unknown role and a malformed station code are refused")
    void invalidDocumentsAreRefused() throws Exception {
        for (String body : List.of(
                document("\"roles\":{\"GRILL\":0}"),
                document("\"roles\":{\"GRILL\":1001}"),
                document("\"roles\":{\"EXPO\":10}"),
                document("\"roles\":{\"SORCERER\":10}"),
                document("\"stations\":{\"grill 1\":10}"))) {
            Response refused = send(policyPath(null, null), OWNER, "bad-" + body.hashCode(), "0", body);
            assertThat(refused.status()).as(body).isEqualTo(400);
            assertThat(refused.json().path("code").asText()).as(body).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(fetch(policyPath(null, null), OWNER).json().path("isDefault").asBoolean())
                .as("nothing refused was published")
                .isTrue();
    }

    @Test
    @DisplayName("writing needs kitchen.productivity.manage at the scope; reading needs kitchen.headcount.read")
    void theEditorIsScopedAndCapabilityGated() throws Exception {
        String body = """
                {"schema":1,"roles":{"GRILL":20},"reason":"x"}""";

        assertThat(send(policyPath(null, null), LINE, "c1", "0", body).status())
                .as("a line cook")
                .isEqualTo(403);
        assertThat(send(policyPath(null, null), OTHER_OWNER, "c2", "0", body).status())
                .as("another tenant's owner")
                .isEqualTo(403);
        assertThat(send(policyPath(null, null), MANAGER, "c3", "0", body).status())
                .as("a location manager cannot publish at the tenant")
                .isEqualTo(403);
        assertThat(send(policyPath(BRAND, LOCATION), OTHER_BRANCH_MANAGER, "c4", "0", body)
                        .status())
                .as("a manager of another branch")
                .isEqualTo(403);
        assertThat(send(policyPath(BRAND, LOCATION), MANAGER, "c5", "0", body).status())
                .as("a location manager at their own location")
                .isEqualTo(200);
        assertThat(fetch(policyPath(null, null), LINE).status())
                .as("a line cook may not read it")
                .isEqualTo(403);
        assertThat(fetch(policyPath(BRAND, LOCATION), MANAGER).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the same Idempotency-Key replays the stored answer and publishes once")
    void aReplayedWriteIsPublishedOnce() throws Exception {
        long before = audited("kitchen.station_productivity.published");
        String body = """
                {"schema":1,"roles":{"GRILL":20},"reason":"once"}""";

        Response first = send(policyPath(null, null), OWNER, "same-key", "0", body);
        Response replay = send(policyPath(null, null), OWNER, "same-key", "0", body);

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.raw()).isEqualTo(first.raw());
        assertThat(audited("kitchen.station_productivity.published") - before).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private record Response(
            int status, String raw, @Nullable String etag) {
        JsonNode json() throws Exception {
            return JSON.readTree(raw);
        }
    }

    /** A write body carrying one part of the document, so each refusal is about exactly that part. */
    private static String document(String part) {
        return "{\"schema\":1," + part + ",\"reason\":\"x\"}";
    }

    private static String planPath(UUID location) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location + "/kitchen/headcount-plan";
    }

    private static String policyPath(@Nullable UUID brandId, @Nullable UUID locationId) {
        String query = "";
        if (brandId != null) {
            query = "?brandId=" + brandId + (locationId == null ? "" : "&locationId=" + locationId);
        }
        return "/api/v1/operations/tenants/" + TENANT + "/kitchen-productivity" + query;
    }

    private JsonNode plan(String subject, UUID location, String query) throws Exception {
        Response response = getPlan(subject, location, query);
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        return response.json();
    }

    private Response getPlan(String subject, UUID location, String query) throws Exception {
        return fetch(planPath(location) + "?" + query, subject);
    }

    private Response fetch(String path, String subject) throws Exception {
        MvcResult result = mvc.perform(get(path).with(tokenFor(subject))).andReturn();
        return new Response(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString(),
                result.getResponse().getHeader(HttpHeaders.ETAG));
    }

    private Response send(String path, String subject, String key, String ifMatch, String body) throws Exception {
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

    private void publish(String subject, @Nullable UUID brandId, @Nullable UUID locationId, String ifMatch, String body)
            throws Exception {
        Response response = send(policyPath(brandId, locationId), subject, "pub-" + UUID.randomUUID(), ifMatch, body);
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
    }

    private static JsonNode rowOf(JsonNode plan, String stationCode, int hour) {
        for (JsonNode row : plan.path("rows")) {
            if (row.path("stationCode").asText().equals(stationCode)
                    && row.path("hour").asInt() == hour) {
                return row;
            }
        }
        throw new AssertionError("No row for %s at hour %d in %s".formatted(stationCode, hour, plan));
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        return row.path(field).decimalValue();
    }

    private long audited(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :a")
                .param("a", action)
                .query(Long.class)
                .single();
    }

    /**
     * Four same-weekday dates, newest first. The grill at 12:00 fires 50, 60, 50, 40 (mean 50, band 39.536 to
     * 60.464); the hot line fires 10. At 19:00 the grill fires 80 and the hot line 60, and the pass 100 (which
     * must never be planned).
     */
    private void seedFridays() {
        int[] grillNoon = {50, 60, 50, 40};
        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            fact(date, LOCATION, GRILL, "GRILL-1", "GRILL", 12, String.valueOf(grillNoon[i]));
            fact(date, LOCATION, HOT, "HOT-1", "HOT", 12, "10");
            fact(date, LOCATION, GRILL, "GRILL-1", "GRILL", 19, "80");
            fact(date, LOCATION, HOT, "HOT-1", "HOT", 19, "60");
            fact(date, LOCATION, PASS, "PASS", "EXPO", 19, "100");
        }
    }

    private void fact(
            LocalDate date, UUID location, UUID station, String code, String role, int hour, String portions) {
        jdbc.sql("""
                INSERT INTO reporting.fact_station_load (tenant_id, business_date, location_id, station_id,
                    station_code, station_role, operating_hour, portions, tickets, boundary_version,
                    metric_calculation_version)
                VALUES (:t, :date, :loc, :station, :code, :role, :hour, :portions, 1, 1, 1)
                """)
                .param("t", TENANT)
                .param("date", date)
                .param("loc", location)
                .param("station", station)
                .param("code", code)
                .param("role", role)
                .param("hour", hour)
                .param("portions", new BigDecimal(portions))
                .update();
    }

    private void seedStations() {
        station(GRILL, LOCATION, "GRILL-1", "GRILL", 1, false);
        station(HOT, LOCATION, "HOT-1", "HOT", 2, false);
        station(PASS, LOCATION, "PASS", "EXPO", 3, true);
        station(OTHER_GRILL, OTHER_LOCATION, "GRILL-1", "GRILL", 1, false);
    }

    private void station(UUID id, UUID location, String code, String role, int order, boolean fallback) {
        jdbc.sql("""
                INSERT INTO kitchen.stations (id, tenant_id, brand_id, location_id, code, role, display_name_ru,
                    display_name_uz, display_name_en, sort_order, is_fallback, status)
                VALUES (:id, :t, :b, :loc, :code, :role, :code, :code, :code, :order, :fallback, 'ACTIVE')
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", location)
                .param("code", code)
                .param("role", role)
                .param("order", order)
                .param("fallback", fallback)
                .update();
    }

    private void seedTenancy() {
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenant)
                    .param("slug", "headcount-" + tenant)
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
                        'ACTIVE', 'test-fixture', 'headcount http test', :validFrom)
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
