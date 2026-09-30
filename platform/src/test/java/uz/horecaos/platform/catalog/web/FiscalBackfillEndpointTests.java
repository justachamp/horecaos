package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Gap map row {@code 10.7c}, the fiscalization tab's ИКПУ / package-code
 * backfill, over HTTP (ADR 0025, ADR 0031): what the editor reads —
 * {@code GET .../catalog/fiscal-coverage}, now carrying what each node already
 * holds and a per-category default — and what it writes —
 * {@code PUT .../catalog/fiscal-classifications/bulk} in {@code MERGE} mode.
 *
 * <p>The bodies below are the JSON the console sends, not a record built in
 * Java: an item names its node and the two codes and nothing else, so a
 * primitive the server insisted on (Jackson 3 refuses a body that omits one)
 * would show up here as a 400 and not in production.
 *
 * <p>The fixture menu has two categories. BURGERS holds three classified
 * dishes (two agree on one pair of codes, one on another) and two that are not:
 * one with no classification row at all, one with an ИКПУ, a unit and a fiscal
 * name but no package code — the half-finished row a replace-style write would
 * flatten. DRINKS holds one unclassified dish and nothing to copy from.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class FiscalBackfillEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9f30-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f30-3000-7000-8000-0000000000b1");
    private static final UUID CATALOG = UUID.fromString("018f9f30-3000-7000-8000-0000000000c1");
    private static final UUID BURGERS = UUID.fromString("018f9f30-3000-7000-8000-0000000000c2");
    private static final UUID DRINKS = UUID.fromString("018f9f30-3000-7000-8000-0000000000c3");

    private static final UUID OTHER_TENANT = UUID.fromString("018f9f30-3000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f30-3000-7000-8000-0000000000b2");

    /** No classification row at all. */
    private static final UUID BARE = UUID.fromString("018f9f30-3000-7000-8000-000000000101");
    /** An ИКПУ, a unit and a fiscal name; no package code. */
    private static final UUID HALF = UUID.fromString("018f9f30-3000-7000-8000-000000000102");
    /** An unclassified drink, in the category with nothing to copy from. */
    private static final UUID COLA = UUID.fromString("018f9f30-3000-7000-8000-000000000103");
    /** In no category at all. */
    private static final UUID LOOSE = UUID.fromString("018f9f30-3000-7000-8000-000000000104");

    private static final UUID CLASSIFIED_A1 = UUID.fromString("018f9f30-3000-7000-8000-000000000111");
    private static final UUID CLASSIFIED_A2 = UUID.fromString("018f9f30-3000-7000-8000-000000000112");
    private static final UUID CLASSIFIED_B = UUID.fromString("018f9f30-3000-7000-8000-000000000113");
    /** Another tenant's unclassified dish: a node id this brand must not be able to write to. */
    private static final UUID FOREIGN = UUID.fromString("018f9f30-3000-7000-8000-000000000199");

    private static final String MXIK_A = "10706001001000000";
    private static final String PACKAGE_A = "1500316";
    private static final String MXIK_B = "10101001001000000";
    private static final String PACKAGE_B = "1500175";
    private static final String MXIK_NEW = "10202002002000000";
    private static final String PACKAGE_NEW = "1500999";

    private static final String OWNER = "fiscal-backfill-owner";
    private static final String NO_GRANT = "fiscal-backfill-no-grant";

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

    @BeforeEach
    void reset() {
        requests = 0;
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE catalog.fiscal_classifications, catalog.fees, catalog.category_products, "
                        + "catalog.categories, catalog.catalogs, catalog.translations, catalog.location_offerings, "
                        + "catalog.variants, catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        insertTenant(TENANT, "fiscal-backfill");
        insertBrand(TENANT, BRAND, "fiscal-backfill-brand");
        insertTenant(OTHER_TENANT, "fiscal-backfill-other");
        insertBrand(OTHER_TENANT, OTHER_BRAND, "fiscal-backfill-other-brand");
        insertCatalog(TENANT, BRAND, CATALOG);
        insertCategory(BURGERS, "BURGERS", "Burgers");
        insertCategory(DRINKS, "DRINKS", "Drinks");

        insertVariant(TENANT, BRAND, CLASSIFIED_A1, "A1", BURGERS);
        insertVariant(TENANT, BRAND, CLASSIFIED_A2, "A2", BURGERS);
        insertVariant(TENANT, BRAND, CLASSIFIED_B, "B1", BURGERS);
        insertVariant(TENANT, BRAND, BARE, "BARE", BURGERS);
        insertVariant(TENANT, BRAND, HALF, "HALF", BURGERS);
        insertVariant(TENANT, BRAND, COLA, "COLA", DRINKS);
        insertVariant(TENANT, BRAND, LOOSE, "LOOSE", null);
        insertVariant(OTHER_TENANT, OTHER_BRAND, FOREIGN, "FOREIGN", null);

        classify(CLASSIFIED_A1, MXIK_A, PACKAGE_A, 15, "Burger A1");
        classify(CLASSIFIED_A2, MXIK_A, PACKAGE_A, 15, "Burger A2");
        classify(CLASSIFIED_B, MXIK_B, PACKAGE_B, 15, "Burger B1");
        classify(HALF, MXIK_B, null, 15, "Half burger");

        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    // ------------------------------------------------------------------ the read

    @Test
    @DisplayName("the coverage read lists every variant short of a complete classification, with what it already holds")
    void coverageListsUnclassifiedVariantsWithTheirCurrentCodes() throws Exception {
        String body = coverage();

        assertThat(nodeIds(body, "VARIANT"))
                .as("classified dishes are not the backfill's business; every other variant is")
                .containsExactlyInAnyOrder(BARE.toString(), HALF.toString(), COLA.toString(), LOOSE.toString());
        assertThat(node(body, BARE, "mxikCode")).isNull();
        assertThat(node(body, BARE, "packageCode")).isNull();
        assertThat(node(body, BARE, "categoryId")).isEqualTo(BURGERS.toString());
        assertThat(node(body, BARE, "categoryName")).isEqualTo("Burgers");
        assertThat(node(body, HALF, "mxikCode"))
                .as("a half-classified row shows the code it already has, so the editor can complete it")
                .isEqualTo(MXIK_B);
        assertThat(node(body, HALF, "packageCode")).isNull();
        assertThat(node(body, LOOSE, "categoryId"))
                .as("a dish on no category has nothing to copy a default from")
                .isNull();
        assertThat(JsonPath.<Integer>read(body, "$.totalNodes"))
                .as("seven variants and the delivery fee the read vivifies")
                .isEqualTo(8);
    }

    @Test
    @DisplayName(
            "a category's default is the pair most of its classified dishes carry, and a category without one has none")
    void coverageOffersACategoryDefaultDrawnFromClassifiedSiblings() throws Exception {
        String body = coverage();

        List<Map<String, Object>> defaults = JsonPath.read(body, "$.categoryDefaults");
        assertThat(defaults)
                .as("BURGERS has classified dishes to copy from; DRINKS has none, and gets no guess")
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("categoryId")).isEqualTo(BURGERS.toString());
                    assertThat(entry.get("categoryName")).isEqualTo("Burgers");
                    assertThat(entry.get("mxikCode")).isEqualTo(MXIK_A);
                    assertThat(entry.get("packageCode")).isEqualTo(PACKAGE_A);
                    assertThat(entry.get("agreeingCount")).isEqualTo(2);
                    assertThat(entry.get("sampleSize")).isEqualTo(3);
                });
    }

    @Test
    @DisplayName("a tie between two pairs goes to the lowest code, so the answer does not move between reads")
    void aTieBreaksDeterministically() throws Exception {
        UUID second = UUID.fromString("018f9f30-3000-7000-8000-000000000114");
        insertVariant(TENANT, BRAND, second, "B2", BURGERS);
        classify(second, MXIK_B, PACKAGE_B, 15, "Burger B2");

        // A: two dishes, B: two dishes. MXIK_B sorts below MXIK_A.
        List<Map<String, Object>> first = JsonPath.read(coverage(), "$.categoryDefaults");
        List<Map<String, Object>> again = JsonPath.read(coverage(), "$.categoryDefaults");

        assertThat(first).singleElement().satisfies(entry -> {
            assertThat(entry.get("mxikCode")).isEqualTo(MXIK_B);
            assertThat(entry.get("packageCode")).isEqualTo(PACKAGE_B);
            assertThat(entry.get("agreeingCount")).isEqualTo(2);
            assertThat(entry.get("sampleSize")).isEqualTo(4);
        });
        assertThat(again).isEqualTo(first);
    }

    @Test
    @DisplayName(
            "only a variant with both codes votes; a dish with an ИКПУ alone leaves its category without a default")
    void aHalfClassifiedVariantDoesNotVote() throws Exception {
        jdbc.sql("DELETE FROM catalog.fiscal_classifications WHERE variant_id IN (:a1, :a2, :b)")
                .param("a1", CLASSIFIED_A1)
                .param("a2", CLASSIFIED_A2)
                .param("b", CLASSIFIED_B)
                .update();

        assertThat(JsonPath.<List<Object>>read(coverage(), "$.categoryDefaults"))
                .as("the only classification left is HALF's, and it has no package code to offer")
                .isEmpty();
    }

    @Test
    @DisplayName("another tenant's menu never appears in this brand's coverage")
    void coverageNeverShowsAnotherTenantsNodes() throws Exception {
        assertThat(coverage()).doesNotContain(FOREIGN.toString());
    }

    // ----------------------------------------------------------------- the write

    @Test
    @DisplayName("a MERGE batch fills the codes of a bare variant and of a half-classified one, and reports each row")
    void mergeFillsCodesAndReportsEveryRow() throws Exception {
        UUID unknown = UUID.fromString("018f9f30-3000-7000-8000-0000000009ff");
        String body = bulk(
                "MERGE",
                item(BARE, MXIK_NEW, PACKAGE_NEW),
                item(HALF, null, PACKAGE_NEW),
                """
                {"nodeType":"VARIANT","nodeId":"%s","fiscal":{"mxikCode":"%s","packageCode":"%s"}}
                """.formatted(unknown, MXIK_NEW, PACKAGE_NEW),
                """
                {"nodeType":"VARIANT","nodeId":"%s","fiscal":{"mxikCode":"%s","packageCode":"%s"}}
                """.formatted(FOREIGN, MXIK_NEW, PACKAGE_NEW),
                """
                {"nodeType":"VARIANT","nodeId":"%s"}
                """.formatted(COLA));

        assertThat(statuses(body))
                .as("one outcome per row, in the order sent; a bad row does not stop the others")
                .containsExactly("CLASSIFIED", "CLASSIFIED", "NOT_FOUND", "NOT_FOUND", "SKIPPED_EMPTY");
        assertThat(JsonPath.<List<String>>read(body, "$.outcomes[*].nodeId"))
                .containsExactly(
                        BARE.toString(), HALF.toString(), unknown.toString(), FOREIGN.toString(), COLA.toString());

        Map<String, Object> bare = row(BARE);
        assertThat(bare.get("mxik_code")).isEqualTo(MXIK_NEW);
        assertThat(bare.get("package_code")).isEqualTo(PACKAGE_NEW);
        assertThat(bare.get("source")).isEqualTo("MANUAL");

        Map<String, Object> half = row(HALF);
        assertThat(half.get("package_code")).as("the gap was filled").isEqualTo(PACKAGE_NEW);
        assertThat(half.get("mxik_code"))
                .as("the code the item did not send is kept")
                .isEqualTo(MXIK_B);
        assertThat(half.get("fiscal_unit_code"))
                .as("the unit and the fiscal name someone entered earlier survive a backfill")
                .isEqualTo(15);
        assertThat(half.get("fiscal_name")).isEqualTo("Half burger");

        assertThat(count("catalog.fiscal_classifications WHERE variant_id = '" + FOREIGN + "'"))
                .as("a node of another tenant is never written to, whatever id the batch carries")
                .isZero();
        assertThat(count("catalog.fiscal_classifications WHERE variant_id = '" + COLA + "'"))
                .as("an empty item writes no row")
                .isZero();
    }

    @Test
    @DisplayName("a MERGE that would change nothing is reported UNCHANGED and writes nothing")
    void aRepeatedMergeIsQuiet() throws Exception {
        bulk("MERGE", item(HALF, null, PACKAGE_NEW));
        Object versionAfterFirst = row(HALF).get("version");
        long auditAfterFirst = count("audit.audit_events WHERE action_code = 'catalog.fiscalClassification.bulkSet'");

        String again = bulk("MERGE", item(HALF, null, PACKAGE_NEW), item(HALF, MXIK_B, null));

        assertThat(statuses(again)).containsExactly("UNCHANGED", "UNCHANGED");
        assertThat(row(HALF).get("version")).isEqualTo(versionAfterFirst);
        assertThat(auditAfterFirst).isEqualTo(1);
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.fiscalClassification.bulkSet'"))
                .as("a batch that changed nothing leaves no fact")
                .isEqualTo(auditAfterFirst);
    }

    @Test
    @DisplayName("a MERGE batch that names a node twice applies the two in sequence")
    void aNodeNamedTwiceIsMergedInSequence() throws Exception {
        String body = bulk("MERGE", item(BARE, MXIK_NEW, null), item(BARE, null, PACKAGE_NEW));

        assertThat(statuses(body)).containsExactly("CLASSIFIED", "CLASSIFIED");
        Map<String, Object> bare = row(BARE);
        assertThat(bare.get("mxik_code")).isEqualTo(MXIK_NEW);
        assertThat(bare.get("package_code")).isEqualTo(PACKAGE_NEW);
    }

    @Test
    @DisplayName("a batch with no mode still replaces, as the endpoint always did")
    void withoutAModeTheBatchReplaces() throws Exception {
        String body = bulk(null, item(HALF, MXIK_NEW, PACKAGE_NEW));

        assertThat(statuses(body)).containsExactly("CLASSIFIED");
        Map<String, Object> half = row(HALF);
        assertThat(half.get("mxik_code")).isEqualTo(MXIK_NEW);
        assertThat(half.get("fiscal_unit_code"))
                .as("the default is unchanged, so the fiscal workbench that relies on it keeps its behaviour")
                .isNull();
        assertThat(half.get("fiscal_name")).isNull();
    }

    @Test
    @DisplayName("a MERGE batch that tries to set marking is refused whole, before anything is written")
    void mergeRefusesAnItemThatSetsAConstraint() throws Exception {
        MvcResult refused = mvc.perform(put(bulkPath(TENANT, BRAND))
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "backfill-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"MERGE","items":[
                                  %s,
                                  {"nodeType":"VARIANT","nodeId":"%s","fiscal":{"mxikCode":"%s","markingRequired":true}}
                                ]}
                                """.formatted(item(BARE, MXIK_NEW, PACKAGE_NEW), HALF, MXIK_NEW)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        assertThat(count("catalog.fiscal_classifications WHERE variant_id = '" + BARE + "'"))
                .as("the valid first row was not applied either")
                .isZero();
    }

    @Test
    @DisplayName("a merge keeps the marking and age constraints a node already carries")
    void mergeLeavesTheConstraintsAlone() throws Exception {
        jdbc.sql("""
                UPDATE catalog.fiscal_classifications
                   SET marking_required = true, marking_scheme = 'DATA_MATRIX', age_restriction_years = 18
                 WHERE variant_id = :id
                """).param("id", HALF).update();

        bulk("MERGE", item(HALF, null, PACKAGE_NEW));

        Map<String, Object> half = row(HALF);
        assertThat(half.get("package_code")).isEqualTo(PACKAGE_NEW);
        assertThat(half.get("marking_required")).isEqualTo(true);
        assertThat(half.get("marking_scheme")).isEqualTo("DATA_MATRIX");
        assertThat(half.get("age_restriction_years")).isEqualTo(18);
    }

    @Test
    @DisplayName("one audit fact covers the batch, with what each node held before and after")
    void theBatchIsAuditedOnce() throws Exception {
        bulk("MERGE", item(BARE, MXIK_NEW, PACKAGE_NEW), item(HALF, null, PACKAGE_NEW));

        List<String> facts = jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                         WHERE action_code = 'catalog.fiscalClassification.bulkSet'
                        """).query(String.class).list();
        assertThat(facts).singleElement().satisfies(document -> {
            Map<String, Object> bareBefore = JsonPath.read(document, "$['" + BARE + "'].before");
            Map<String, Object> bareAfter = JsonPath.read(document, "$['" + BARE + "'].after");
            Map<String, Object> halfBefore = JsonPath.read(document, "$['" + HALF + "'].before");
            Map<String, Object> halfAfter = JsonPath.read(document, "$['" + HALF + "'].after");
            assertThat(bareBefore.get("mxikCode"))
                    .as("nothing before: the node had no classification")
                    .isNull();
            assertThat(bareAfter.get("mxikCode")).isEqualTo(MXIK_NEW);
            assertThat(bareAfter.get("packageCode")).isEqualTo(PACKAGE_NEW);
            assertThat(halfBefore.get("mxikCode")).isEqualTo(MXIK_B);
            assertThat(halfBefore.get("packageCode")).isNull();
            assertThat(halfAfter.get("packageCode")).isEqualTo(PACKAGE_NEW);
            assertThat(halfAfter.get("fiscalName"))
                    .as("the after side is the merged row, not only the field that changed")
                    .isEqualTo("Half burger");
        });
        assertThat(jdbc.sql("""
                                SELECT capability_used || '|' || scope_type || '|' || scope_id::text
                                  FROM audit.audit_events
                                 WHERE action_code = 'catalog.fiscalClassification.bulkSet'
                                """).query(String.class).single())
                .isEqualTo(Capability.CATALOG_AUTHOR.code() + "|BRAND|" + BRAND);
    }

    @Test
    @DisplayName("a caller with no grant is refused both the read and the write, naming the capability")
    void bothEndpointsRefuseACallerWithNoGrant() throws Exception {
        MvcResult read = mvc.perform(get(coveragePath(TENANT, BRAND)).with(tokenFor(NO_GRANT)))
                .andReturn();
        MvcResult write = mvc.perform(put(bulkPath(TENANT, BRAND))
                        .with(tokenFor(NO_GRANT))
                        .header(IDEMPOTENCY_HEADER, "backfill-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[%s]}".formatted(item(BARE, MXIK_NEW, PACKAGE_NEW))))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(read.getResponse().getContentAsString()).contains(Capability.CATALOG_READ.code());
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(write.getResponse().getContentAsString()).contains(Capability.CATALOG_AUTHOR.code());
        assertThat(count("catalog.fiscal_classifications WHERE variant_id = '" + BARE + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a tenant owner cannot read or write another tenant's brand")
    void anotherTenantsBrandIsOutOfScope() throws Exception {
        MvcResult read = mvc.perform(
                        get(coveragePath(OTHER_TENANT, OTHER_BRAND)).with(tokenFor(OWNER)))
                .andReturn();
        MvcResult write = mvc.perform(put(bulkPath(OTHER_TENANT, OTHER_BRAND))
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "backfill-cross-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"MERGE\",\"items\":[%s]}".formatted(item(FOREIGN, MXIK_NEW, PACKAGE_NEW))))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("catalog.fiscal_classifications WHERE variant_id = '" + FOREIGN + "'"))
                .isZero();
    }

    // ------------------------------------------------------------------ helpers

    private static String coveragePath(UUID tenantId, UUID brandId) {
        return "/api/v1/control-plane/tenants/" + tenantId + "/brands/" + brandId + "/catalog/fiscal-coverage";
    }

    private static String bulkPath(UUID tenantId, UUID brandId) {
        return "/api/v1/control-plane/tenants/" + tenantId + "/brands/" + brandId
                + "/catalog/fiscal-classifications/bulk";
    }

    private String coverage() throws Exception {
        MvcResult result = mvc.perform(get(coveragePath(TENANT, BRAND)).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    /** {@code mode} null omits the field, the way a client written before it existed does. */
    private String bulk(@Nullable String mode, String... items) throws Exception {
        String body = "{" + (mode == null ? "" : "\"mode\":\"" + mode + "\",") + "\"items\":[" + String.join(",", items)
                + "]}";
        MvcResult result = mvc.perform(put(bulkPath(TENANT, BRAND))
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "backfill-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    /** One item exactly as the console sends it: the node, and only the codes it wants written. */
    private static String item(UUID variantId, @Nullable String mxik, @Nullable String packageCode) {
        StringBuilder fiscal = new StringBuilder();
        if (mxik != null) {
            fiscal.append("\"mxikCode\":\"").append(mxik).append('"');
        }
        if (packageCode != null) {
            fiscal.append(fiscal.isEmpty() ? "" : ",")
                    .append("\"packageCode\":\"")
                    .append(packageCode)
                    .append('"');
        }
        return "{\"nodeType\":\"VARIANT\",\"nodeId\":\"%s\",\"fiscal\":{%s}}".formatted(variantId, fiscal);
    }

    private static List<String> statuses(String body) {
        return JsonPath.read(body, "$.outcomes[*].status");
    }

    private static List<String> nodeIds(String body, String nodeType) {
        return JsonPath.read(body, "$.nodes[?(@.nodeType=='" + nodeType + "')].nodeId");
    }

    private static @Nullable Object node(String body, UUID nodeId, String field) {
        List<Object> values = JsonPath.read(body, "$.nodes[?(@.nodeId=='" + nodeId + "')]." + field);
        return values.isEmpty() ? null : values.get(0);
    }

    private Map<String, Object> row(UUID variantId) {
        return jdbc.sql("""
                        SELECT mxik_code, package_code, fiscal_unit_code, fiscal_name, source, version,
                               marking_required, marking_scheme, age_restriction_years
                          FROM catalog.fiscal_classifications WHERE variant_id = :id
                        """).param("id", variantId).query().singleRow();
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Long.class)
                .single();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID tenantId, UUID brandId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", slug)
                .update();
    }

    private void insertCatalog(UUID tenantId, UUID brandId, UUID catalogId) {
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MENU', 'Menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
    }

    private void insertCategory(UUID categoryId, String code, String name) {
        jdbc.sql("""
                INSERT INTO catalog.categories (id, tenant_id, brand_id, catalog_id, code, status)
                VALUES (:id, :tenantId, :brandId, :catalogId, :code, 'ACTIVE')
                """)
                .param("id", categoryId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", CATALOG)
                .param("code", code)
                .update();
        translate("CATEGORY", categoryId, name);
    }

    private void insertVariant(UUID tenantId, UUID brandId, UUID variantId, String code, @Nullable UUID categoryId) {
        UUID productId = UUID.nameUUIDFromBytes(("product-" + variantId).getBytes(UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
        if (categoryId != null) {
            jdbc.sql("""
                    INSERT INTO catalog.category_products (tenant_id, brand_id, category_id, product_id)
                    VALUES (:tenantId, :brandId, :categoryId, :productId)
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("categoryId", categoryId)
                    .param("productId", productId)
                    .update();
        }
        translateFor(tenantId, brandId, "PRODUCT", productId, code);
    }

    private void translate(String entityType, UUID entityId, String name) {
        translateFor(TENANT, BRAND, entityType, entityId, name);
    }

    private void translateFor(UUID tenantId, UUID brandId, String entityType, UUID entityId, String name) {
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                VALUES (:tenantId, :brandId, :type, :id, 'uz', :name)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("type", entityType)
                .param("id", entityId)
                .param("name", name)
                .update();
    }

    private void classify(UUID variantId, String mxik, @Nullable String packageCode, int unit, String fiscalName) {
        jdbc.sql("""
                INSERT INTO catalog.fiscal_classifications
                    (id, tenant_id, brand_id, variant_id, mxik_code, package_code, fiscal_unit_code, fiscal_name, source)
                VALUES (:id, :tenantId, :brandId, :variantId, :mxik, :packageCode, :unit, :name, 'MANUAL')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("variantId", variantId)
                .param("mxik", mxik)
                .param("packageCode", packageCode)
                .param("unit", unit)
                .param("name", fiscalName)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'fiscal backfill endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
