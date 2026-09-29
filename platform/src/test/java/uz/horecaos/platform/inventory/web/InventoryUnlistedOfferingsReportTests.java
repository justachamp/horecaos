package uz.horecaos.platform.inventory.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.api.UnlistedOfferingsPort;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The stock page's "unlisted offered dishes" report ({@code GET
 * .../inventory/unlisted-offerings}) and the bulk "list all" action behind it
 * ({@code POST .../inventory/listing-backfill}), driven over HTTP so the
 * capability guard, the ADR 0031 {@code Idempotency-Key} rule and the tenant
 * filter are all in play — {@code OfferingListingBackfillServiceTests} calls
 * the service directly and touches none of them.
 *
 * <p>Two tenants each carry an {@code AVAILABLE}, never-listed offering. The
 * assertion that matters for isolation is not "the other tenant's row is
 * absent" (an empty fixture would satisfy that) but that the other tenant's
 * row <em>exists in the same table</em> and still never appears.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryUnlistedOfferingsReportTests {

    private static final UUID TENANT = UUID.fromString("018f9f20-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f20-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f20-4000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f20-4000-7000-8000-0000000000c2");

    private static final UUID OTHER_TENANT = UUID.fromString("018f9f20-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f20-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_TENANT_LOCATION = UUID.fromString("018f9f20-4000-7000-8000-0000000000c3");

    private static final String OWNER = "unlisted-report-owner";
    private static final String NO_GRANT = "unlisted-report-no-grant";

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
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private UnlistedOfferingsPort unlisted;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.translations, catalog.location_offerings, catalog.variants, "
                        + "catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "unlisted-report-a", BRAND, LOCATION);
        insertLocation(OTHER_LOCATION, TENANT, BRAND, "SEC", "second");
        insertTenant(OTHER_TENANT, "unlisted-report-b", OTHER_BRAND, OTHER_TENANT_LOCATION);
        // The owner is a TENANT_OWNER of TENANT only — never of OTHER_TENANT.
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
    }

    @Test
    @DisplayName("the report names exactly this location's AVAILABLE, never-listed offerings")
    void reportsOnlyAvailableUnlistedOfferingsAtThisLocation() throws Exception {
        UUID plov = offer(TENANT, BRAND, LOCATION, "PLOV", "Plov", "AVAILABLE");
        UUID listed = offer(TENANT, BRAND, LOCATION, "SAMSA", "Samsa", "AVAILABLE");
        offer(TENANT, BRAND, LOCATION, "HIDDEN-ITEM", "Hidden dish", "HIDDEN");
        offer(TENANT, BRAND, LOCATION, "PAUSED", "Paused dish", "UNAVAILABLE");
        // A different branch of the same brand: its own backlog must not leak in.
        offer(TENANT, BRAND, OTHER_LOCATION, "LAGMAN", "Lagman", "AVAILABLE");
        listVariant(TENANT, BRAND, LOCATION, listed);

        JsonNode report = read(TENANT, BRAND, LOCATION, OWNER, "");

        assertThat(report.get("totalCount").asInt()).isEqualTo(1);
        assertThat(report.get("hasMore").asBoolean()).isFalse();
        assertThat(report.get("items")).hasSize(1);
        assertThat(report.get("items").get(0).get("variantId").asString()).isEqualTo(plov.toString());
        assertThat(report.get("items").get(0).get("productName").asString()).isEqualTo("Plov");
        assertThat(report.get("items").get(0).get("sku").asString()).isEqualTo("SKU-PLOV");
    }

    @Test
    @DisplayName("another tenant's unlisted offerings never appear, even when their ids are mixed into the path")
    void neverLeaksAnotherTenantsOfferings() throws Exception {
        offer(TENANT, BRAND, LOCATION, "PLOV", "Plov", "AVAILABLE");
        UUID foreign = offer(OTHER_TENANT, OTHER_BRAND, OTHER_TENANT_LOCATION, "SECRET", "Secret dish", "AVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.location_offerings WHERE tenant_id = :t")
                        .param("t", OTHER_TENANT)
                        .query(Long.class)
                        .single())
                .as("the other tenant's offering really is in the same table")
                .isEqualTo(1L);

        // Own tenant, own location: only own rows.
        String own = read(TENANT, BRAND, LOCATION, OWNER, "").toString();
        assertThat(own).doesNotContain(foreign.toString()).doesNotContain("Secret dish");

        // Own tenant in the path but the OTHER tenant's location id: the web
        // guard refuses a location that is not this tenant's and brand's...
        MvcResult mixed = mvc.perform(
                        get(reportPath(TENANT, BRAND, OTHER_TENANT_LOCATION)).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(mixed.getResponse().getStatus()).isEqualTo(404);
        assertThat(mixed.getResponse().getContentAsString())
                .doesNotContain(foreign.toString())
                .doesNotContain("Secret dish");

        // ...and the SQL does not lean on that guard: asked directly with this
        // tenant's id and the other tenant's location, the read is empty.
        UnlistedOfferingsPort.UnlistedOfferings direct =
                unlisted.describeUnlistedAvailableAtLocation(TENANT, BRAND, OTHER_TENANT_LOCATION, "uz", 10);
        assertThat(direct.totalCount()).isZero();
        assertThat(direct.items()).isEmpty();
        assertThat(unlisted.describeUnlistedAvailableAtLocation(TENANT, OTHER_BRAND, LOCATION, "uz", 10)
                        .totalCount())
                .as("a brand that is not the location's own sees nothing either")
                .isZero();

        // The other tenant's own path, with this tenant's owner: refused outright.
        MvcResult refused = mvc.perform(get(reportPath(OTHER_TENANT, OTHER_BRAND, OTHER_TENANT_LOCATION))
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("Secret dish");
    }

    @Test
    @DisplayName("a caller without inventory.read is refused, naming the capability")
    void refusedWithoutInventoryRead() throws Exception {
        MvcResult refused = mvc.perform(get(reportPath(TENANT, BRAND, LOCATION)).with(tokenFor(NO_GRANT)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.INVENTORY_READ.code());
    }

    @Test
    @DisplayName("limit trims the listed items but totalCount stays the exact backlog")
    void limitTrimsItemsButNotTheTotal() throws Exception {
        offer(TENANT, BRAND, LOCATION, "A-ITEM", "Alpha", "AVAILABLE");
        offer(TENANT, BRAND, LOCATION, "B-ITEM", "Bravo", "AVAILABLE");
        offer(TENANT, BRAND, LOCATION, "C-ITEM", "Charlie", "AVAILABLE");

        JsonNode report = read(TENANT, BRAND, LOCATION, OWNER, "?limit=2");

        assertThat(report.get("totalCount").asInt()).isEqualTo(3);
        assertThat(report.get("items")).hasSize(2);
        assertThat(report.get("hasMore").asBoolean()).isTrue();
        assertThat(report.get("items").get(0).get("productName").asString()).isEqualTo("Alpha");
        assertThat(report.get("items").get(1).get("productName").asString()).isEqualTo("Bravo");

        for (String invalid : List.of("?limit=0", "?limit=201", "?locale=not%20a%20locale")) {
            assertThat(mvc.perform(get(reportPath(TENANT, BRAND, LOCATION) + invalid)
                                    .with(tokenFor(OWNER)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as(invalid)
                    .isEqualTo(400);
        }
    }

    @Test
    @DisplayName("a product with no name in the asked locale falls back to another locale, then to its code")
    void fallsBackWhenTheAskedLocaleHasNoName() throws Exception {
        UUID variant = offer(TENANT, BRAND, LOCATION, "NO-UZ", "Only English name", "AVAILABLE", "en");
        UUID nameless = offer(TENANT, BRAND, LOCATION, "NAMELESS", null, "AVAILABLE");

        JsonNode report = read(TENANT, BRAND, LOCATION, OWNER, "?locale=uz");

        assertThat(nameOf(report, variant)).isEqualTo("Only English name");
        assertThat(nameOf(report, nameless)).isEqualTo("NAMELESS");
    }

    @Test
    @DisplayName("the report count equals what the bulk list-all lists, and the second run is a no-op")
    void reportCountEqualsWhatTheBackfillLists() throws Exception {
        offer(TENANT, BRAND, LOCATION, "A-ITEM", "Alpha", "AVAILABLE");
        offer(TENANT, BRAND, LOCATION, "B-ITEM", "Bravo", "AVAILABLE");
        offer(TENANT, BRAND, LOCATION, "HIDDEN-ITEM", "Hidden dish", "HIDDEN");
        offer(TENANT, BRAND, OTHER_LOCATION, "ELSEWHERE", "Elsewhere", "AVAILABLE");

        int dryRun = read(TENANT, BRAND, LOCATION, OWNER, "?limit=1")
                .get("totalCount")
                .asInt();
        assertThat(dryRun).isEqualTo(2);

        MvcResult first = backfill(TENANT, BRAND, LOCATION, OWNER, "list-all-1");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstBody = JSON.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("candidateCount").asInt()).isEqualTo(dryRun);
        assertThat(firstBody.get("listedCount").asInt()).isEqualTo(dryRun);
        assertThat(firstBody.get("mayHaveMore").asBoolean()).isFalse();

        assertThat(read(TENANT, BRAND, LOCATION, OWNER, "").get("totalCount").asInt())
                .as("everything at this branch is now listed")
                .isZero();
        assertThat(read(TENANT, BRAND, OTHER_LOCATION, OWNER, "")
                        .get("totalCount")
                        .asInt())
                .as("the other branch's backlog is untouched")
                .isEqualTo(1);

        // Same intent retried (a lost response): replayed, not re-run.
        MvcResult replay = backfill(TENANT, BRAND, LOCATION, OWNER, "list-all-1");
        assertThat(replay.getResponse().getHeader(IdempotencyInterceptor.REPLAYED_HEADER))
                .isEqualTo("true");
        assertThat(JSON.readTree(replay.getResponse().getContentAsString())
                        .get("candidateCount")
                        .asInt())
                .isEqualTo(dryRun);

        // A new intent: nothing left, so nothing listed.
        JsonNode second = JSON.readTree(backfill(TENANT, BRAND, LOCATION, OWNER, "list-all-2")
                .getResponse()
                .getContentAsString());
        assertThat(second.get("candidateCount").asInt()).isZero();
        assertThat(second.get("listedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("list-all without an Idempotency-Key is refused, and without inventory.adjust is forbidden")
    void listAllNeedsAnIdempotencyKeyAndTheAdjustCapability() throws Exception {
        offer(TENANT, BRAND, LOCATION, "A-ITEM", "Alpha", "AVAILABLE");

        MvcResult noKey = mvc.perform(post(backfillPath(TENANT, BRAND, LOCATION))
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");

        MvcResult forbidden = backfill(TENANT, BRAND, LOCATION, NO_GRANT, "list-all-forbidden");
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(forbidden.getResponse().getContentAsString()).contains(Capability.INVENTORY_ADJUST.code());

        assertThat(read(TENANT, BRAND, LOCATION, OWNER, "").get("totalCount").asInt())
                .as("neither refused call listed anything")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the runbook's own SQL and curl commands hit real endpoints, and its checks hold")
    void runbookCommandsAreRealAndItsChecksHold() throws Exception {
        // Tenant A: two branches with a backlog. Tenant B: one, that must stay
        // untouched because the runbook does one tenant at a time.
        offer(TENANT, BRAND, LOCATION, "A-1", "Alpha", "AVAILABLE");
        offer(TENANT, BRAND, LOCATION, "A-2", "Bravo", "AVAILABLE");
        offer(TENANT, BRAND, LOCATION, "A-3", "Charlie", "AVAILABLE");
        offer(TENANT, BRAND, OTHER_LOCATION, "A-4", "Delta", "AVAILABLE");
        offer(OTHER_TENANT, OTHER_BRAND, OTHER_TENANT_LOCATION, "B-1", "Echo", "AVAILABLE");
        offer(OTHER_TENANT, OTHER_BRAND, OTHER_TENANT_LOCATION, "B-2", "Foxtrot", "AVAILABLE");

        String runbook = Files.readString(RUNBOOK, UTF_8);
        List<String> sql = sqlBlocks(runbook);
        assertThat(sql).as("step 1's query and step 4's confirmation query").hasSize(2);

        // Step 1: run verbatim -> what `psql -At -F','` would write to backlog.csv.
        List<List<String>> backlog = runSql(sql.get(0));
        assertThat(backlog).as("both tenants carry a backlog").hasSize(3);
        // `grep "^${TENANT},"` -- one tenant at a time.
        List<List<String>> mine = backlog.stream()
                .filter(row -> row.get(0).equals(TENANT.toString()))
                .toList();
        assertThat(mine).extracting(row -> row.get(3)).containsExactlyInAnyOrder("3", "1");

        List<RunbookCurl> curls = curlCommands(runbook);
        assertThat(curls)
                .as("step 2's dry run, then step 3's list-all, in the order the runbook runs them")
                .extracting(RunbookCurl::method)
                .containsExactly("GET", "POST");
        RunbookCurl dryRun = curls.get(0);
        RunbookCurl listAll = curls.get(1);
        assertThat(listAll.headers())
                .as("a mutating call needs its intent key (ADR 0031)")
                .containsKey("Idempotency-Key");
        assertThat(listAll.headers()).containsKey("Authorization");

        // Step 2: totalCount equals that location's unlisted_count from step 1.
        for (List<String> row : mine) {
            JsonNode dry = dispatch(dryRun, row);
            assertThat(dry.get("totalCount").asInt()).isEqualTo(Integer.parseInt(row.get(3)));
            assertThat(dry.has("hasMore")).isTrue();
            assertThat(dry.get("items").get(0).has("productName")).isTrue();
        }

        // Step 3: candidateCount == listedCount, and one call was the whole backlog.
        for (List<String> row : mine) {
            JsonNode result = dispatch(listAll, row);
            assertThat(result.get("candidateCount").asInt()).isEqualTo(Integer.parseInt(row.get(3)));
            assertThat(result.get("listedCount").asInt())
                    .isEqualTo(result.get("candidateCount").asInt());
            assertThat(result.get("mayHaveMore").asBoolean()).isFalse();
        }

        // Step 4: the dry run says zero, and the query has no row for this tenant...
        for (List<String> row : mine) {
            assertThat(dispatch(dryRun, row).get("totalCount").asInt()).isZero();
        }
        List<List<String>> remaining = runSql(sql.get(1));
        assertThat(remaining).extracting(row -> row.get(0)).containsOnly(OTHER_TENANT.toString());
        // ...while the tenant the operator has not reached yet is exactly as it was.
        assertThat(remaining).extracting(row -> row.get(2)).containsExactly("2");
    }

    // ------------------------------------------------------------------ helpers

    // -------------------------------------------------------- runbook parsing

    private static final Path RUNBOOK = Path.of("docs/runbooks/catalog-offering-listing-backfill.md");

    /** One {@code curl} the runbook tells an operator to run, as far as the API can tell. */
    private record RunbookCurl(String method, String urlTemplate, Map<String, String> headers) {}

    /** Every {@code <<'SQL' ... SQL} heredoc body, in document order. */
    private static List<String> sqlBlocks(String runbook) {
        Matcher matcher = Pattern.compile("<<'SQL'[^\\n]*\\n(.*?)\\nSQL\\n", Pattern.DOTALL)
                .matcher(runbook);
        List<String> blocks = new ArrayList<>();
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        return blocks;
    }

    private List<List<String>> runSql(String sql) {
        return jdbc.sql(sql)
                .query((row, number) -> {
                    List<String> cells = new ArrayList<>();
                    for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                        cells.add(row.getString(column));
                    }
                    return cells;
                })
                .list();
    }

    /**
     * The runbook's {@code curl} commands, tokenised the way a shell would read
     * their flags: {@code -X METHOD}, every {@code -H "Name: value"}, and the
     * first other quoted word as the URL. Backslash continuations are joined
     * first. Prose that merely mentions curl is ignored — a real command is
     * {@code curl} followed by a flag.
     */
    private static List<RunbookCurl> curlCommands(String runbook) {
        String flat = runbook.replaceAll("\\\\\\R\\s*", " ");
        Matcher command = Pattern.compile("curl\\s+(-[^\\n]*)").matcher(flat);
        List<RunbookCurl> commands = new ArrayList<>();
        while (command.find()) {
            Matcher token = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(command.group(1));
            String method = "GET";
            String url = null;
            Map<String, String> headers = new LinkedHashMap<>();
            boolean expectMethod = false;
            boolean expectHeader = false;
            while (token.find()) {
                String quoted = token.group(1);
                String word = quoted != null ? quoted : token.group(2);
                if (expectMethod) {
                    method = word;
                    expectMethod = false;
                } else if (expectHeader) {
                    int colon = word.indexOf(':');
                    headers.put(
                            word.substring(0, colon).trim(),
                            word.substring(colon + 1).trim());
                    expectHeader = false;
                } else if (quoted == null && word.equals("-X")) {
                    expectMethod = true;
                } else if (quoted == null && word.equals("-H")) {
                    expectHeader = true;
                } else if (quoted != null && url == null && word.contains("${")) {
                    url = word;
                }
            }
            if (url == null) {
                throw new AssertionError("no URL in: curl " + command.group(1));
            }
            commands.add(new RunbookCurl(method, url, headers));
        }
        return commands;
    }

    /**
     * Resolves the runbook's own shell variables ({@code API=...}, {@code
     * base=...}) with the row a loop iteration is on, then sends the command
     * to the real controllers with exactly the headers it names — a fresh
     * {@code Idempotency-Key} each time, as {@code $(uuidgen)} would mint.
     */
    private JsonNode dispatch(RunbookCurl curl, List<String> row) throws Exception {
        Map<String, String> vars = new LinkedHashMap<>();
        Matcher assignment =
                Pattern.compile("(?m)^\\s*(\\w+)=\"([^\"]*)\"\\s*$").matcher(runbookText());
        while (assignment.find()) {
            vars.put(assignment.group(1), assignment.group(2));
        }
        vars.put("HOST", "api.test.invalid");
        vars.put("tenant", row.get(0));
        vars.put("brand", row.get(1));
        vars.put("location", row.get(2));

        String url = curl.urlTemplate();
        for (int pass = 0; pass < 6 && url.contains("${"); pass++) {
            for (Map.Entry<String, String> variable : vars.entrySet()) {
                url = url.replace("${" + variable.getKey() + "}", variable.getValue());
            }
        }
        assertThat(url)
                .as("every variable in the URL is one the runbook defines")
                .doesNotContain("${");
        String path = url.replaceFirst("^https://[^/]+", "");

        MockHttpServletRequestBuilder request =
                request(HttpMethod.valueOf(curl.method()), path).with(tokenFor(OWNER));
        for (Map.Entry<String, String> header : curl.headers().entrySet()) {
            assertThat(header.getKey())
                    .as("a header the API understands")
                    .isIn("Authorization", "Accept", "Idempotency-Key", "Content-Type");
            if (header.getKey().equals("Idempotency-Key")) {
                request.header("Idempotency-Key", UUID.randomUUID().toString());
            } else if (!header.getKey().equals("Authorization")) {
                request.header(header.getKey(), header.getValue());
            }
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s %s -> %s", curl.method(), path, result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String runbookText() throws IOException {
        return Files.readString(RUNBOOK, UTF_8);
    }

    private JsonNode read(UUID tenant, UUID brand, UUID location, String subject, String query) throws Exception {
        MvcResult result = mvc.perform(
                        get(reportPath(tenant, brand, location) + query).with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult backfill(UUID tenant, UUID brand, UUID location, String subject, String key) throws Exception {
        return mvc.perform(post(backfillPath(tenant, brand, location))
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private void listVariant(UUID tenant, UUID brand, UUID location, UUID variant) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/tenants/" + tenant + "/brands/" + brand + "/locations/" + location
                                + "/inventory/stock-items")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "list-" + variant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"%s","trackingMode":"BINARY"}
                                """.formatted(variant)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private static String nameOf(JsonNode report, UUID variant) {
        for (JsonNode item : report.get("items")) {
            if (variant.toString().equals(item.get("variantId").asString())) {
                return item.get("productName").asString();
            }
        }
        throw new AssertionError("variant " + variant + " is not in the report: " + report);
    }

    private static String reportPath(UUID tenant, UUID brand, UUID location) {
        return "/api/v1/tenants/" + tenant + "/brands/" + brand + "/locations/" + location
                + "/inventory/unlisted-offerings";
    }

    private static String backfillPath(UUID tenant, UUID brand, UUID location) {
        return "/api/v1/tenants/" + tenant + "/brands/" + brand + "/locations/" + location
                + "/inventory/listing-backfill";
    }

    private UUID offer(UUID tenant, UUID brand, UUID location, String code, @Nullable String name, String status) {
        return offer(tenant, brand, location, code, name, status, "uz");
    }

    /** Writes straight to the tables — the pre-auto-listing shape every pre-existing tenant is already in. */
    private UUID offer(
            UUID tenant,
            UUID brand,
            UUID location,
            String code,
            @Nullable String name,
            String status,
            String nameLocale) {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", product)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, true, 'ACTIVE')
                """)
                .param("id", variant)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("productId", product)
                .param("sku", "SKU-" + code)
                .update();
        if (name != null) {
            jdbc.sql("""
                    INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                    VALUES (:tenantId, :brandId, 'PRODUCT', :entityId, :locale, :name)
                    """)
                    .param("tenantId", tenant)
                    .param("brandId", brand)
                    .param("entityId", product)
                    .param("locale", nameLocale)
                    .param("name", name)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, :status)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("locationId", location)
                .param("variantId", variant)
                .param("status", status)
                .update();
        return variant;
    }

    private void insertTenant(UUID tenant, String slug, UUID brand, UUID location) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brand).param("tenantId", tenant).update();
        insertLocation(location, tenant, brand, "CHI", "chilonzor");
    }

    private void insertLocation(UUID location, UUID tenant, UUID brand, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :slug, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", location)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenant) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'unlisted offerings report test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenant).getBytes(UTF_8)))
                .param("tenantId", tenant)
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
