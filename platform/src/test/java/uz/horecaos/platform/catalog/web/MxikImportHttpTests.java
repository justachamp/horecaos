package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.catalog.application.MxikReferenceImportService;
import uz.horecaos.platform.catalog.domain.MxikValidity;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0173 over HTTP, with the console's JSON, against a real database: a platform administrator uploads the
 * official file, reads a dry run that changed nothing, and applies it; a full snapshot dates a withdrawal, a
 * partial one does not, a returning code is reinstated; a run that would empty a tenth of the list is held until
 * the administrator names the exact count; a wrong file stops with a reason; a failed apply leaves the list as it
 * was.
 *
 * <p>The object store is an in-memory double: the raw file is kept there under its content hash, and the
 * double lets a test see what was kept and where. The fixture file is invented (see
 * {@code src/test/resources/mxik/README.md}); the real file is an external fact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({StubJwtIssuer.class, MxikImportHttpTests.InMemoryStore.class})
class MxikImportHttpTests {

    private static final String ADMIN = "mxik-platform-admin";
    private static final String TENANT_OWNER = "mxik-tenant-owner";
    private static final UUID TENANT = UUID.fromString("018fd100-4000-7000-8000-0000000000e1");
    private static final UUID BRAND = UUID.fromString("018fd100-4000-7000-8000-0000000000f1");
    private static final UUID VARIANT = UUID.fromString("018fd100-4000-7000-8000-000000000e01");
    private static final String IMPORTS = "/api/v1/control-plane/fiscal-reference/mxik/imports";
    private static final String STATUS = "/api/v1/control-plane/fiscal-reference/mxik/status";
    private static final String POTATO = "01101001001000000";
    private static final String IDEMPOTENCY_HEADER = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private int requests;

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

    @Autowired
    private InMemoryObjectStore store;

    @Autowired
    private MxikReferenceImportService imports;

    @Autowired
    private JdbcCatalogStore catalogStore;

    @Autowired
    private JdbcMxikImportStore importStore;

    @BeforeEach
    void reset() {
        requests = 0;
        store.clear();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE notifications.control_plane_alerts").update();
        jdbc.sql("TRUNCATE TABLE catalog.mxik_import_run_rows, catalog.mxik_import_run_findings, "
                        + "catalog.mxik_import_runs, catalog.mxik_reference_state, catalog.mxik_reference CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.fiscal_classifications, catalog.fees, catalog.translations, "
                        + "catalog.variants, catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        grantPlatformAdmin(ADMIN);
        grantTenantOwner(TENANT_OWNER);
    }

    // ------------------------------------------------------------------ dry run, then apply

    @Test
    @DisplayName("an upload is a dry run that changes nothing; apply is a second act that loads the list")
    void aDryRunChangesNothingAndApplyLoadsTheList() throws Exception {
        MvcResult dryRun = upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-01");

        assertThat(dryRun.getResponse().getStatus())
                .as(dryRun.getResponse().getContentAsString())
                .isEqualTo(200);
        String body = dryRun.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("VALIDATED");
        assertThat(JsonPath.<Integer>read(body, "$.rowsTotal")).isEqualTo(7);
        assertThat(JsonPath.<Integer>read(body, "$.rowsNew")).isEqualTo(7);
        assertThat(JsonPath.<Integer>read(body, "$.labelsBorrowed"))
                .as("the juice has no Uzbek label and is counted, not dropped")
                .isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.codeLengthHistogram.17"))
                .as("the distribution is shown to the human instead of asserting a format")
                .isEqualTo(7);
        assertThat(JsonPath.<String>read(body, "$.mappingProfile")).isEqualTo("TASNIF_CSV_V1");
        assertThat(dryRun.getResponse().getHeader("ETag")).isNotBlank();

        assertThat(count("catalog.mxik_reference"))
                .as("nothing is written to the list before the second, explicit act")
                .isZero();
        assertThat(count("catalog.mxik_reference_state")).isZero();
        assertThat(count("catalog.mxik_import_run_rows"))
                .as("what the dry run showed is what apply reads")
                .isEqualTo(7);
        assertThat(JsonPath.<Boolean>read(read(STATUS), "$.loaded")).isFalse();

        String sha = JsonPath.read(body, "$.sourceSha256");
        assertThat(store.keys())
                .as("the raw file is kept under its content hash")
                .contains("mxik-imports/" + sha + ".csv");

        Map<String, Object> fact = jdbc.sql("""
                        SELECT capability_used, scope_type, change_document::text AS document
                          FROM audit.audit_events WHERE action_code = 'catalog.mxikReference.import-validated'
                        """).query().singleRow();
        assertThat(fact.get("scope_type")).isEqualTo("PLATFORM");
        assertThat((String) fact.get("document"))
                .contains(sha)
                .as("counts and a hash; never a file body or a row")
                .doesNotContain("Картофель")
                .doesNotContain("Kartoshka");

        MvcResult applied = apply(dryRun, "{}");
        assertThat(applied.getResponse().getStatus())
                .as(applied.getResponse().getContentAsString())
                .isEqualTo(200);
        String appliedBody = applied.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(appliedBody, "$.status")).isEqualTo("APPLIED");
        assertThat(JsonPath.<Integer>read(appliedBody, "$.datasetVersion")).isEqualTo(1);

        assertThat(count("catalog.mxik_reference")).isEqualTo(7);
        assertThat(count("catalog.mxik_import_run_rows"))
                .as("the staging copy is cleared once applied")
                .isZero();
        assertThat(jdbc.sql("SELECT parent_code FROM catalog.mxik_reference WHERE code = :c")
                        .param("c", POTATO)
                        .query(String.class)
                        .single())
                .isEqualTo("01100000000000000");

        String status = read(STATUS);
        assertThat(JsonPath.<Boolean>read(status, "$.loaded")).isTrue();
        assertThat(JsonPath.<Integer>read(status, "$.datasetVersion")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(status, "$.validRows")).isEqualTo(7);
        assertThat(JsonPath.<String>read(status, "$.sourcePublishedOn")).isEqualTo("2026-10-01");
        assertThat(JsonPath.<Integer>read(status, "$.ageDays")).isZero();
        assertThat(JsonPath.<Boolean>read(status, "$.stale")).isFalse();

        String found = read("/api/v1/control-plane/fiscal-reference/mxik?query=картофель");
        assertThat(JsonPath.<List<String>>read(found, "$.items[*].code")).containsExactly(POTATO);
        assertThat(JsonPath.<List<String>>read(found, "$.items[0].defaultPackageCodes"))
                .containsExactly("1001", "1002");
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.mxikReference.import-applied'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("applying the same file again writes no duplicate and counts every row unchanged")
    void theSameFileTwiceIsIdempotent() throws Exception {
        apply(upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-01"), "{}");

        MvcResult again = upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-01");
        String body = again.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.rowsNew")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.rowsUnchanged")).isEqualTo(7);
        assertThat(JsonPath.<Integer>read(body, "$.rowsWithdrawn")).isZero();
        apply(again, "{}");

        assertThat(count("catalog.mxik_reference")).isEqualTo(7);
        assertThat(JsonPath.<Integer>read(read(STATUS), "$.datasetVersion")).isEqualTo(2);
    }

    @Test
    @DisplayName("a changed label and package list is a changed row, and the finding says the defaults moved")
    void aChangedRowIsCountedAndItsPackageDefaultsFlagged() throws Exception {
        apply(upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-01"), "{}");
        String changed = new String(fixture(), UTF_8)
                .replace(
                        "Картофель свежий,Yangi kartoshka,Fresh potato,1001;1002",
                        "Картофель,Yangi kartoshka,Fresh potato,1001;1003");

        MvcResult dryRun = upload("tasnif.csv", changed.getBytes(UTF_8), "FULL_SNAPSHOT", "2026-10-02");

        String body = dryRun.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.rowsChanged")).isEqualTo(1);
        String runId = JsonPath.read(body, "$.runId");
        String findings = read(IMPORTS + "/" + runId + "/findings");
        assertThat(JsonPath.<List<String>>read(findings, "$.items[?(@.kind=='PACKAGE_DEFAULTS_CHANGED')].code"))
                .containsExactly(POTATO);
    }

    // ----------------------------------------------------------------------- the wrong file

    @Test
    @DisplayName(
            "a file with a column the profile does not know stops with its code, keeps the evidence and loads nothing")
    void aWrongFileStopsAndLeavesEvidence() throws Exception {
        byte[] wrong = "code,label ru,label uz,mystery column\n01,Картофель,Kartoshka,x\n".getBytes(UTF_8);

        MvcResult refused = upload("wrong.csv", wrong, "FULL_SNAPSHOT", null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        String body = refused.getResponse().getContentAsString();
        assertThat(body).contains("UNKNOWN_HEADER").contains("mystery column");
        assertThat(count("catalog.mxik_reference")).isZero();
        assertThat(jdbc.sql("SELECT status || ':' || failure_reason FROM catalog.mxik_import_runs")
                        .query(String.class)
                        .single())
                .as("the failed run outlives the refusal, so the hash and the stored file exist")
                .isEqualTo("FAILED:UNKNOWN_HEADER");
        assertThat(store.keys()).hasSize(1);
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.mxikReference.import-failed'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an empty file, an unreadable base64 body and a body naming both a file and a key are refused")
    void unusableBodiesAreRefused() throws Exception {
        assertThat(upload("empty.csv", new byte[0], "FULL_SNAPSHOT", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(422);
        assertThat(rawUpload("{\"fileName\":\"x.csv\",\"contentBase64\":\"***\",\"mode\":\"FULL_SNAPSHOT\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(rawUpload("{\"fileName\":\"x.csv\",\"contentBase64\":\"QQ==\",\"objectKey\":\"mxik-uploads/x\","
                                + "\"mode\":\"FULL_SNAPSHOT\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(upload("x.csv", fixture(), "SOMETIMES", null).getResponse().getStatus())
                .as("mode is FULL_SNAPSHOT or PARTIAL")
                .isEqualTo(422);
        assertThat(count("catalog.mxik_reference")).isZero();
    }

    // ------------------------------------------------------------ withdrawal, reinstatement

    @Test
    @DisplayName("a full snapshot dates a withdrawal, a returning code is reinstated, and nothing is ever deleted")
    void aFullSnapshotWithdrawsAndAReturningCodeIsReinstated() throws Exception {
        apply(upload("all.csv", bigList(100, Set.of()), "FULL_SNAPSHOT", "2026-09-01"), "{}");

        MvcResult withoutOne = upload("less.csv", bigList(100, Set.of(7)), "FULL_SNAPSHOT", "2026-10-01");
        String body = withoutOne.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.status"))
                .as("one of a hundred is 1%, inside the 2% valve")
                .isEqualTo("VALIDATED");
        assertThat(JsonPath.<Integer>read(body, "$.rowsWithdrawn")).isEqualTo(1);
        apply(withoutOne, "{}");

        assertThat(count("catalog.mxik_reference"))
                .as("the withdrawn code is dated, not deleted")
                .isEqualTo(100);
        LocalDate until = jdbc.sql("SELECT valid_until FROM catalog.mxik_reference WHERE code = :c")
                .param("c", code(7))
                .query(LocalDate.class)
                .single();
        assertThat(until).as("dated with the file's own date").isEqualTo(LocalDate.parse("2026-10-01"));
        String status = read(STATUS);
        assertThat(JsonPath.<Integer>read(status, "$.validRows")).isEqualTo(99);
        assertThat(JsonPath.<Integer>read(status, "$.withdrawnRows")).isEqualTo(1);

        String today = read("/api/v1/control-plane/fiscal-reference/mxik?query=" + code(7));
        assertThat(JsonPath.<List<String>>read(today, "$.items[*].code"))
                .as("a withdrawn code is not offered for a new receipt")
                .isEmpty();
        String before = read("/api/v1/control-plane/fiscal-reference/mxik?query=" + code(7) + "&asOf=2026-09-15");
        assertThat(JsonPath.<Boolean>read(before, "$.items[0].withdrawn"))
                .as("judged on a date before the withdrawal, it is still valid")
                .isFalse();
        String after = read("/api/v1/control-plane/fiscal-reference/mxik?query=" + code(7) + "&asOf=2026-10-01");
        assertThat(JsonPath.<Boolean>read(after, "$.items[0].withdrawn")).isTrue();

        MvcResult back = upload("all.csv", bigList(100, Set.of()), "FULL_SNAPSHOT", "2026-10-10");
        assertThat(JsonPath.<Integer>read(back.getResponse().getContentAsString(), "$.rowsReinstated"))
                .isEqualTo(1);
        apply(back, "{}");
        assertThat(jdbc.sql("SELECT valid_until IS NULL FROM catalog.mxik_reference WHERE code = :c")
                        .param("c", code(7))
                        .query(Boolean.class)
                        .single())
                .as("a code that returns has its date cleared")
                .isTrue();
        assertThat(JsonPath.<Integer>read(read(STATUS), "$.validRows")).isEqualTo(100);
    }

    @Test
    @DisplayName("a partial file inserts and updates and withdraws nothing")
    void aPartialRunWithdrawsNothing() throws Exception {
        apply(upload("all.csv", bigList(100, Set.of()), "FULL_SNAPSHOT", "2026-09-01"), "{}");

        MvcResult partial = upload("delta.csv", bigList(3, Set.of()), "PARTIAL", "2026-10-01");

        assertThat(JsonPath.<Integer>read(partial.getResponse().getContentAsString(), "$.rowsWithdrawn"))
                .as("97 codes are absent from the delta; a delta does not say they are gone")
                .isZero();
        apply(partial, "{}");
        assertThat(JsonPath.<Integer>read(read(STATUS), "$.validRows")).isEqualTo(100);
        assertThat(JsonPath.<Integer>read(read(STATUS), "$.withdrawnRows")).isZero();
    }

    @Test
    @DisplayName("a delta may add a child to a parent the list already holds")
    void aDeltaMayNameAParentAlreadyInTheList() throws Exception {
        apply(upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-01"), "{}");
        byte[] delta = ("code,parent code,label ru,label uz\n01101003001000000,01100000000000000,Морковь,Sabzi\n")
                .getBytes(UTF_8);

        MvcResult partial = upload("delta.csv", delta, "PARTIAL", null);
        assertThat(JsonPath.<Integer>read(partial.getResponse().getContentAsString(), "$.rowsNew"))
                .isEqualTo(1);
        apply(partial, "{}");

        assertThat(jdbc.sql("SELECT parent_code FROM catalog.mxik_reference WHERE code = '01101003001000000'")
                        .query(String.class)
                        .single())
                .isEqualTo("01100000000000000");
    }

    // ------------------------------------------------------------------------ the safety valve

    @Test
    @DisplayName("a run that would withdraw more than the threshold is held and applies only with the exact count")
    void aHeldRunNeedsTheExactAcknowledgement() throws Exception {
        apply(upload("all.csv", bigList(100, Set.of()), "FULL_SNAPSHOT", "2026-09-01"), "{}");

        MvcResult half = upload("half.csv", bigList(50, Set.of()), "FULL_SNAPSHOT", "2026-10-01");
        String body = half.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("HELD_FOR_REVIEW");
        assertThat(JsonPath.<Integer>read(body, "$.rowsWithdrawn")).isEqualTo(50);
        String runId = JsonPath.read(body, "$.runId");
        String findings = read(IMPORTS + "/" + runId + "/findings?limit=200");
        assertThat(JsonPath.<List<String>>read(findings, "$.items[?(@.kind=='WITHDRAWN')].code"))
                .as("the held run lists the codes it would withdraw")
                .hasSize(50);

        MvcResult none = apply(half, "{}");
        assertThat(none.getResponse().getStatus()).isEqualTo(422);
        assertThat(none.getResponse().getContentAsString()).contains("WITHDRAWAL_ACKNOWLEDGEMENT_REQUIRED");
        MvcResult wrong = apply(half, "{\"acknowledgedWithdrawals\":49}");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(422);
        assertThat(JsonPath.<Integer>read(read(STATUS), "$.validRows"))
                .as("neither refusal touched the list")
                .isEqualTo(100);

        MvcResult right = apply(half, "{\"acknowledgedWithdrawals\":50}");
        assertThat(right.getResponse().getStatus())
                .as(right.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(JsonPath.<Integer>read(right.getResponse().getContentAsString(), "$.acknowledgedWithdrawals"))
                .isEqualTo(50);
        assertThat(count("catalog.mxik_reference")).as("held is not deleted").isEqualTo(100);
        assertThat(JsonPath.<Integer>read(read(STATUS), "$.validRows")).isEqualTo(50);
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.mxikReference.import-held'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("rejecting a held run keeps it as a record, clears its staging rows and leaves the list alone")
    void rejectingARunLeavesTheListAlone() throws Exception {
        apply(upload("all.csv", bigList(100, Set.of()), "FULL_SNAPSHOT", "2026-09-01"), "{}");
        MvcResult half = upload("half.csv", bigList(50, Set.of()), "FULL_SNAPSHOT", "2026-10-01");

        MvcResult rejected = mvc.perform(post(IMPORTS + "/" + runId(half) + "/reject")
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY_HEADER, "reject-" + requests++)
                        .header("If-Match", etagOf(half)))
                .andReturn();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(rejected.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("REJECTED");
        assertThat(count("catalog.mxik_import_run_rows")).isZero();
        assertThat(JsonPath.<Integer>read(read(STATUS), "$.validRows")).isEqualTo(100);
        MvcResult appliedAfter = apply(rejected, "{\"acknowledgedWithdrawals\":50}");
        assertThat(appliedAfter.getResponse().getStatus())
                .as("a rejected run is not applicable")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("a stale version is refused, and so is a second run validated before the list moved")
    void aStaleRunIsRefused() throws Exception {
        MvcResult first = upload("a.csv", fixture(), "FULL_SNAPSHOT", null);
        MvcResult second = upload("b.csv", bigList(5, Set.of()), "FULL_SNAPSHOT", null);

        MvcResult stale = mvc.perform(post(IMPORTS + "/" + runId(first) + "/apply")
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY_HEADER, "apply-" + requests++)
                        .header("If-Match", "W/\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);

        MvcResult noPrecondition = mvc.perform(post(IMPORTS + "/" + runId(first) + "/apply")
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY_HEADER, "apply-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(noPrecondition.getResponse().getStatus()).isIn(400, 428);

        assertThat(apply(first, "{}").getResponse().getStatus()).isEqualTo(200);
        MvcResult behindTheList = apply(second, "{}");
        assertThat(behindTheList.getResponse().getStatus()).isEqualTo(422);
        assertThat(behindTheList.getResponse().getContentAsString()).contains("RUN_IS_STALE");
        assertThat(count("catalog.mxik_reference"))
                .as("only the first run landed")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("a failed apply leaves the list and the state exactly as they were, and the run says it failed")
    void aFailedApplyLeavesTheListAsItWas() throws Exception {
        apply(upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-01"), "{}");
        MvcResult next = upload("next.csv", bigList(5, Set.of()), "PARTIAL", "2026-10-02");
        // The failure is injected where production cannot reach it: a staged row naming a parent that does not
        // exist, which the table's non-deferrable foreign key refuses halfway through the apply.
        jdbc.sql("UPDATE catalog.mxik_import_run_rows SET parent_code = 'NO-SUCH-PARENT' WHERE ordinal = 4")
                .update();

        MvcResult failed = apply(next, "{}");

        assertThat(failed.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(count("catalog.mxik_reference"))
                .as("the rows before the failing one were rolled back with it")
                .isEqualTo(7);
        assertThat(jdbc.sql("SELECT dataset_version FROM catalog.mxik_reference_state")
                        .query(Integer.class)
                        .single())
                .as("the state row still names the previous dataset")
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ':' || failure_reason FROM catalog.mxik_import_runs WHERE id = :id")
                        .param("id", UUID.fromString(runId(next)))
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED:APPLY_FAILED");
    }

    // ------------------------------------------------------------------------- authorization

    @Test
    @DisplayName(
            "only a platform administrator reaches the runs; a tenant owner reads the same national status and nothing else")
    void onlyAPlatformAdminReachesTheRuns() throws Exception {
        for (RequestPostProcessor outsider : List.of(tokenFor(TENANT_OWNER), tokenFor("nobody"))) {
            assertThat(mvc.perform(post(IMPORTS)
                                    .with(outsider)
                                    .header(IDEMPOTENCY_HEADER, "outsider-" + requests++)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body("tasnif.csv", fixture(), "FULL_SNAPSHOT", null)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(403);
            assertThat(mvc.perform(get(IMPORTS).with(outsider))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(403);
            assertThat(mvc.perform(get(STATUS).with(outsider))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as("the platform status is a platform read")
                    .isEqualTo(403);
        }
        assertThat(count("catalog.mxik_import_runs")).isZero();

        apply(upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-01"), "{}");
        String alias =
                "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog/fiscal-reference/mxik";
        MvcResult tenantStatus =
                mvc.perform(get(alias + "/status").with(tokenFor(TENANT_OWNER))).andReturn();
        assertThat(tenantStatus.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(tenantStatus.getResponse().getContentAsString(), "$.loaded"))
                .isTrue();
        assertThat(JsonPath.<Integer>read(tenantStatus.getResponse().getContentAsString(), "$.validRows"))
                .isEqualTo(7);
        assertThat(mvc.perform(get(alias + "/imports").with(tokenFor(TENANT_OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the tenant alias cannot reach the runs")
                .isEqualTo(404);
        String search = mvc.perform(get(alias + "?query=картофель").with(tokenFor(TENANT_OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(search, "$.items[*].code"))
                .as("the tenant's search is the same national list")
                .containsExactly(POTATO);
    }

    // ------------------------------------------------------------------------ large files

    @Test
    @DisplayName("a file the body cannot carry goes through a signed upload and is read back once, bounded")
    void aLargeFileGoesThroughTheObjectStore() throws Exception {
        MvcResult ticket = mvc.perform(post(IMPORTS + "/uploads")
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY_HEADER, "ticket-" + requests++))
                .andReturn();
        assertThat(ticket.getResponse().getStatus()).isEqualTo(200);
        String key = JsonPath.read(ticket.getResponse().getContentAsString(), "$.objectKey");
        assertThat(key).startsWith("mxik-uploads/");
        assertThat(JsonPath.<Number>read(ticket.getResponse().getContentAsString(), "$.maxBytes")
                        .longValue())
                .isEqualTo(33_554_432L);

        store.put("bucket", key, "text/csv", fixture());
        MvcResult dryRun = mvc.perform(post(IMPORTS)
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY_HEADER, "key-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"tasnif.csv\",\"objectKey\":\"" + key
                                + "\",\"mode\":\"FULL_SNAPSHOT\"}"))
                .andReturn();

        assertThat(dryRun.getResponse().getStatus())
                .as(dryRun.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(JsonPath.<Integer>read(dryRun.getResponse().getContentAsString(), "$.rowsNew"))
                .isEqualTo(7);
        assertThat(store.keys())
                .as("the staging copy is gone; the raw file is kept under its hash")
                .doesNotContain(key)
                .anyMatch(kept -> kept.startsWith("mxik-imports/"));
    }

    @Test
    @DisplayName("an upload key that is not from a ticket, one that holds nothing and one over the bound are refused")
    void badUploadKeysAreRefused() throws Exception {
        assertThat(keyed("mxik-imports/other.csv").getResponse().getContentAsString())
                .contains("UPLOAD_KEY_INVALID");
        assertThat(keyed("mxik-uploads/../etc").getResponse().getContentAsString())
                .contains("UPLOAD_KEY_INVALID");
        assertThat(keyed("mxik-uploads/nothing-here").getResponse().getContentAsString())
                .contains("UPLOAD_NOT_FOUND");

        store.declare("mxik-uploads/huge", 40L * 1024 * 1024);
        assertThat(keyed("mxik-uploads/huge").getResponse().getContentAsString())
                .contains("FILE_TOO_LARGE");
        assertThat(count("catalog.mxik_import_runs")).isZero();
    }

    // ------------------------------------------------------- one definition of valid, everywhere

    @Test
    @DisplayName(
            "the search, the validator's lookup and the Java predicate agree at both boundaries of a validity window")
    void oneDefinitionOfValid() {
        jdbc.sql("""
                INSERT INTO catalog.mxik_reference (code, label_ru, label_uz, valid_from, valid_until)
                VALUES ('WINDOW', 'Окно', 'Oyna', DATE '2026-03-01', DATE '2026-06-01')
                """).update();
        LocalDate lastDayBefore = LocalDate.parse("2026-02-28");
        LocalDate firstDay = LocalDate.parse("2026-03-01");
        LocalDate lastDay = LocalDate.parse("2026-05-31");
        LocalDate withdrawalDay = LocalDate.parse("2026-06-01");

        for (LocalDate day : List.of(lastDayBefore, firstDay, lastDay, withdrawalDay)) {
            boolean expected = MxikValidity.isValidOn(firstDay, withdrawalDay, day);
            assertThat(catalogStore.knownMxikCodes(Set.of("WINDOW"), day).contains("WINDOW"))
                    .as("knownMxikCodes on %s", day)
                    .isEqualTo(expected);
            assertThat(jdbc.sql("SELECT " + MxikValidity.SQL_VALID_ON
                                    + " FROM catalog.mxik_reference WHERE code = 'WINDOW'")
                            .param("on", day)
                            .query(Boolean.class)
                            .single())
                    .as("the SQL form on %s", day)
                    .isEqualTo(expected);
            boolean listed = catalogStore.searchMxikReference("WINDOW", 5, day).stream()
                    .anyMatch(row -> !Boolean.TRUE.equals(row.withdrawn()));
            assertThat(listed).as("search not-withdrawn on %s", day).isEqualTo(expected);
        }
        assertThat(MxikValidity.isValidOn(firstDay, withdrawalDay, firstDay))
                .as("valid_from is inclusive")
                .isTrue();
        assertThat(MxikValidity.isValidOn(firstDay, withdrawalDay, withdrawalDay))
                .as("valid_until is the first day it is no longer valid")
                .isFalse();
        assertThat(catalogStore.searchMxikReference("WINDOW", 5, lastDayBefore))
                .as("a code not yet valid on the date is not listed at all")
                .isEmpty();
    }

    // -------------------------------------------------------------- the coverage and the sweep

    @Test
    @DisplayName(
            "the coverage read counts a classified node whose code was withdrawn, apart from the unclassified ones")
    void theCoverageCountsWithdrawnCodes() throws Exception {
        apply(upload("all.csv", bigList(100, Set.of()), "FULL_SNAPSHOT", "2026-09-01"), "{}");
        apply(upload("less.csv", bigList(100, Set.of(7)), "FULL_SNAPSHOT", "2026-10-01"), "{}");
        insertVariantClassified(VARIANT, code(7));

        String coverage = mvc.perform(
                        get("/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog/fiscal-coverage")
                                .with(tokenFor(TENANT_OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<Integer>read(coverage, "$.withdrawnCount")).isEqualTo(1);
        assertThat(JsonPath.<String>read(coverage, "$.withdrawnNodes[0].mxikCode"))
                .isEqualTo(code(7));
        assertThat(JsonPath.<String>read(coverage, "$.withdrawnNodes[0].mxikWithdrawnOn"))
                .isEqualTo("2026-10-01");
        assertThat(JsonPath.<List<String>>read(coverage, "$.nodes[*].nodeType"))
                .as("the dish is fully classified, so only the brand's delivery fee is unclassified; "
                        + "the withdrawn count is a separate matter")
                .containsExactly("FEE");
        assertThat(catalogStore.mxikFacts(Set.of(code(7), "UNLISTED")).keySet())
                .as("a code the list never held has no facts")
                .containsExactly(code(7));
    }

    @Test
    @DisplayName("a withdrawn code is replaced only by the successor the list itself names, on a person's batch, and "
            + "nothing else on the node moves")
    void aWithdrawnCodeIsReplacedOnlyBySuccessorTheListNames() throws Exception {
        apply(upload("all.csv", bigList(100, Set.of()), "FULL_SNAPSHOT", "2026-09-01"), "{}");
        apply(upload("less.csv", bigList(100, Set.of(7)), "FULL_SNAPSHOT", "2026-10-01"), "{}");
        insertVariantClassified(VARIANT, code(7));

        assertThat(successor(code(8)))
                .as("the list names no successor for the withdrawn code, so the batch is not honoured")
                .isEqualTo("CONFLICT");
        assertThat(storedMxik()).isEqualTo(code(7));

        jdbc.sql("UPDATE catalog.mxik_reference SET replaced_by_code = :next WHERE code = :old")
                .param("next", code(8))
                .param("old", code(7))
                .update();
        assertThat(successor(code(9)))
                .as("a code the list does not name for it is refused too, however valid it is")
                .isEqualTo("CONFLICT");
        assertThat(storedMxik()).isEqualTo(code(7));

        assertThat(successor(code(8))).isEqualTo("CLASSIFIED");
        assertThat(storedMxik()).isEqualTo(code(8));
        assertThat(jdbc.sql("SELECT package_code || '/' || fiscal_unit_code || '/' || fiscal_name "
                                + "FROM catalog.fiscal_classifications WHERE variant_id = :v")
                        .param("v", VARIANT)
                        .query(String.class)
                        .single())
                .as("only the ИКПУ moves; the package code, the unit and the fiscal name stay the tenant's")
                .isEqualTo("1001/166/Dish");
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.fiscalClassification.bulkSet'"))
                .as("one audited fact for the batch")
                .isEqualTo(1);
        assertThat(successor(code(8)))
                .as("the same batch again changes nothing")
                .isEqualTo("UNCHANGED");

        MvcResult mixed = mvc.perform(put(BULK)
                        .with(tokenFor(TENANT_OWNER))
                        .header(IDEMPOTENCY_HEADER, "successor-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SUCCESSOR\",\"items\":[{\"nodeType\":\"VARIANT\",\"nodeId\":\""
                                + VARIANT + "\",\"fiscal\":{\"mxikCode\":\"" + code(8)
                                + "\",\"packageCode\":\"2002\"}}]}"))
                .andReturn();
        assertThat(mixed.getResponse().getStatus())
                .as("a SUCCESSOR batch carries the replacement code and nothing else")
                .isEqualTo(400);
    }

    @Test
    @DisplayName(
            "an absent list raises one incident per class while a tenant holds a classified node, and an import resolves it")
    void anAbsentOrStaleListRaisesOneIncidentAndAnImportResolvesIt() throws Exception {
        assertThat(imports.checkFreshness(false))
                .as("nothing classified, nothing to protect")
                .isEmpty();
        assertThat(count("notifications.control_plane_alerts")).isZero();

        assertThat(importStore.anyTenantHasClassifiedNode()).isFalse();
        insertVariantClassified(VARIANT, "SOME-CODE");
        assertThat(importStore.anyTenantHasClassifiedNode())
                .as("the sweeper asks this before it raises")
                .isTrue();
        assertThat(imports.checkFreshness(true)).contains("FISCAL_REFERENCE_NOT_LOADED");
        assertThat(imports.checkFreshness(true)).contains("FISCAL_REFERENCE_NOT_LOADED");
        assertThat(count("notifications.control_plane_alerts WHERE status = 'OPEN'"))
                .as("raised once per class and subject; the second sweep counts an occurrence")
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT occurrences FROM notifications.control_plane_alerts")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT variables::text FROM notifications.control_plane_alerts")
                        .query(String.class)
                        .single())
                .as("no tenant, no code")
                .doesNotContain("SOME-CODE")
                .doesNotContain(TENANT.toString());

        apply(upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-01"), "{}");
        assertThat(count("notifications.control_plane_alerts WHERE status = 'RESOLVED'"))
                .as("a successful import ends 'not loaded'")
                .isEqualTo(1);
        assertThat(imports.checkFreshness(true)).isEmpty();

        jdbc.sql("UPDATE catalog.mxik_reference_state SET imported_at = now() - interval '60 days'")
                .update();
        assertThat(imports.checkFreshness(true)).contains("FISCAL_REFERENCE_STALE");
        assertThat(JsonPath.<Boolean>read(read(STATUS), "$.stale")).isTrue();
        assertThat(JsonPath.<Integer>read(read(STATUS), "$.maxAgeDays")).isEqualTo(45);

        apply(upload("tasnif.csv", fixture(), "FULL_SNAPSHOT", "2026-10-02"), "{}");
        assertThat(count("notifications.control_plane_alerts WHERE status = 'RESOLVED'"))
                .as("a fresh import resolves the stale incident too")
                .isEqualTo(2);
        assertThat(count("notifications.control_plane_alerts WHERE status <> 'RESOLVED'"))
                .isZero();
    }

    @Test
    @DisplayName("the application role cannot delete from the national list or its state")
    void theApplicationRoleCannotDeleteTheList() {
        assertThat(jdbc.sql("SELECT has_table_privilege('horecaos_application', 'catalog.mxik_reference', 'DELETE')")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("SELECT has_table_privilege('horecaos_application', 'catalog.mxik_reference', 'UPDATE')")
                        .query(Boolean.class)
                        .single())
                .as("the import still updates it")
                .isTrue();
    }

    // ---------------------------------------------------------------------------- helpers

    private static final String BULK =
            "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog/fiscal-classifications/bulk";

    /** Sends the batch the console's "replace with" button sends for the one dish; returns its outcome. */
    private String successor(String mxikCode) throws Exception {
        MvcResult result = mvc.perform(put(BULK)
                        .with(tokenFor(TENANT_OWNER))
                        .header(IDEMPOTENCY_HEADER, "successor-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SUCCESSOR\",\"items\":[{\"nodeType\":\"VARIANT\",\"nodeId\":\"" + VARIANT
                                + "\",\"fiscal\":{\"mxikCode\":\"" + mxikCode + "\"}}]}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.outcomes[0].status");
    }

    private String storedMxik() {
        return jdbc.sql("SELECT mxik_code FROM catalog.fiscal_classifications WHERE variant_id = :v")
                .param("v", VARIANT)
                .query(String.class)
                .single();
    }

    private MvcResult upload(String fileName, byte[] content, String mode, @Nullable String publishedOn)
            throws Exception {
        return rawUpload(body(fileName, content, mode, publishedOn));
    }

    private MvcResult rawUpload(String body) throws Exception {
        return mvc.perform(post(IMPORTS)
                        .with(tokenFor(ADMIN))
                        .header(IDEMPOTENCY_HEADER, "import-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult keyed(String objectKey) throws Exception {
        return rawUpload("{\"fileName\":\"x.csv\",\"objectKey\":\"" + objectKey + "\",\"mode\":\"FULL_SNAPSHOT\"}");
    }

    private static String body(String fileName, byte[] content, String mode, @Nullable String publishedOn) {
        return "{\"fileName\":\"" + fileName + "\",\"contentBase64\":\""
                + Base64.getEncoder().encodeToString(content) + "\",\"mode\":\"" + mode + "\""
                + (publishedOn == null ? "" : ",\"sourcePublishedOn\":\"" + publishedOn + "\"")
                + "}";
    }

    private MvcResult apply(MvcResult run, String body) throws Exception {
        String etag = run.getResponse().getHeader("ETag");
        var request = post(IMPORTS + "/" + runId(run) + "/apply")
                .with(tokenFor(ADMIN))
                .header(IDEMPOTENCY_HEADER, "apply-" + requests++)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (etag != null) {
            request.header("If-Match", etag);
        }
        return mvc.perform(request).andReturn();
    }

    private static String etagOf(MvcResult result) {
        return java.util.Objects.requireNonNull(result.getResponse().getHeader("ETag"));
    }

    private static String runId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.runId");
        // A refused upload names the run it recorded in the problem's details.
        return id;
    }

    private String read(String path) throws Exception {
        MvcResult result = mvc.perform(get(path).with(tokenFor(ADMIN))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(path + " " + result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Long.class)
                .single();
    }

    private static String code(int n) {
        return "1" + "%016d".formatted(n);
    }

    private static byte[] bigList(int rows, Set<Integer> skip) {
        StringBuilder csv = new StringBuilder("code,label ru,label uz\n");
        IntStream.rangeClosed(1, rows)
                .filter(n -> !skip.contains(n))
                .forEach(n -> csv.append(code(n))
                        .append(",Товар ")
                        .append(n)
                        .append(",Mahsulot ")
                        .append(n)
                        .append('\n'));
        return csv.toString().getBytes(UTF_8);
    }

    private static byte[] fixture() throws IOException {
        try (var stream = MxikImportHttpTests.class.getResourceAsStream("/mxik/tasnif-fixture.csv")) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        }
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'mxik-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'mxik-brand', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void insertVariantClassified(UUID variantId, String mxikCode) {
        UUID productId = UUID.nameUUIDFromBytes(("product-" + variantId).getBytes(UTF_8));
        jdbc.sql("INSERT INTO catalog.products (id, tenant_id, brand_id, code, status) "
                        + "VALUES (:id, :t, :b, 'DISH', 'ACTIVE')")
                .param("id", productId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        jdbc.sql("INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status) "
                        + "VALUES (:id, :t, :b, :p, true, 'ACTIVE')")
                .param("id", variantId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("p", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.fiscal_classifications
                    (id, tenant_id, brand_id, variant_id, mxik_code, package_code, fiscal_unit_code, fiscal_name, source)
                VALUES (:id, :t, :b, :v, :code, '1001', 166, 'Dish', 'MANUAL')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("v", variantId)
                .param("code", mxikCode)
                .update();
    }

    private void grantPlatformAdmin(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test-fixture', 'mxik import endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + PlatformRole.PLATFORM_ADMIN.code()).getBytes(UTF_8)))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantTenantOwner(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'mxik import endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + PlatformRole.TENANT_OWNER.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.TENANT_OWNER))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // ----------------------------------------------------------------- the in-memory object store

    /** The one {@link ObjectStorage} of this test's context: bytes in a map, with the sizes a test can declare. */
    static final class InMemoryObjectStore implements ObjectStorage {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final Map<String, Long> declared = new ConcurrentHashMap<>();

        void clear() {
            objects.clear();
            declared.clear();
        }

        Set<String> keys() {
            return objects.keySet().stream().collect(Collectors.toUnmodifiableSet());
        }

        void declare(String key, long size) {
            declared.put(key, size);
        }

        @Override
        public PresignedUpload presignUpload(
                String bucket, String key, String contentType, long maxSizeBytes, Duration validFor) {
            return new PresignedUpload(
                    URI.create("http://object-store.invalid/" + bucket + "/" + key),
                    Map.of("Content-Type", contentType),
                    Instant.now().plus(validFor));
        }

        @Override
        public URI presignDownload(String bucket, String key, Duration validFor) {
            return URI.create("http://object-store.invalid/" + bucket + "/" + key);
        }

        @Override
        public Optional<StoredObject> head(String bucket, String key) {
            if (declared.containsKey(key)) {
                return Optional.of(
                        new StoredObject(declared.get(key), "application/octet-stream", Optional.empty(), "e"));
            }
            byte[] content = objects.get(key);
            return content == null
                    ? Optional.empty()
                    : Optional.of(new StoredObject(content.length, "application/octet-stream", Optional.empty(), "e"));
        }

        @Override
        public byte[] readPrefix(String bucket, String key, int maxBytes) {
            byte[] content = objects.get(key);
            return content == null ? new byte[0] : java.util.Arrays.copyOf(content, Math.min(content.length, maxBytes));
        }

        @Override
        public void put(String bucket, String key, String contentType, byte[] content) {
            objects.put(key, content);
        }

        @Override
        public void delete(String bucket, String key) {
            objects.remove(key);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryStore {

        @Bean
        @Primary
        InMemoryObjectStore objectStorage() {
            return new InMemoryObjectStore();
        }
    }
}
