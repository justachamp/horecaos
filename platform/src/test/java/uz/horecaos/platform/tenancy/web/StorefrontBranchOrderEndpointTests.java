package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The order a brand's storefront lists its branches in, and what the public pickup search does with it (ADR 0183 D17, row
 * 6.8; ADR 0157 Release 4).
 *
 * <p>The search is read through its real endpoint, so what is asserted is what a customer's phone is sent: nearest first
 * when a point is shared and the brand's own order breaks a tie in distance; the brand's order alone when no point is
 * shared, with an honest {@code distanceKnown=false} and no invented zero; a branch the brand never placed last.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontBranchOrderEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID TENANT = UUID.fromString("018f9a30-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9a30-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9a30-4000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANTS_BRAND = UUID.fromString("018f9a30-4000-7000-8000-0000000000c2");

    private static final UUID ALPHA = UUID.fromString("018f9a30-4000-7000-8000-0000000000d1");
    private static final UUID BRAVO = UUID.fromString("018f9a30-4000-7000-8000-0000000000d2");
    private static final UUID CHARLIE = UUID.fromString("018f9a30-4000-7000-8000-0000000000d3");
    private static final UUID DELTA = UUID.fromString("018f9a30-4000-7000-8000-0000000000d4");
    private static final UUID DUP_ONE = UUID.fromString("018f9a30-4000-7000-8000-0000000000d5");
    private static final UUID DUP_TWO = UUID.fromString("018f9a30-4000-7000-8000-0000000000d6");
    private static final UUID FOREIGN_LOCATION = UUID.fromString("018f9a30-4000-7000-8000-0000000000d7");
    private static final UUID YAK = UUID.fromString("018f9a30-4000-7000-8000-0000000000d8");
    private static final UUID ZED = UUID.fromString("018f9a30-4000-7000-8000-0000000000d9");

    private static final String OWNER = "branch-order-owner";
    private static final String READER = "branch-order-reader";

    private static final String IDEMPOTENCY_HEADER = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

    /** Where a customer standing at Alpha is. */
    private static final String AT_ALPHA = "{\"point\": {\"latitude\": 41.3110, \"longitude\": 69.2400}}";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront branch order endpoint test");
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
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        // The menu sits in another module's tables, which a tenant truncate does not reach.
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "branch-order");
        insertTenant(OTHER_TENANT, "branch-order-other");
        insertBrand(TENANT, BRAND, "MAIN");
        insertBrand(OTHER_TENANT, OTHER_TENANTS_BRAND, "THEIRS");
        UUID channel = UUID.fromString("018f9a30-4000-7000-8000-0000000000b1");
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE', 1)
                """).param("id", channel).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, 'PICKUP', true)
                """).param("tenantId", TENANT).param("channelId", channel).update();
        insertLocation(ALPHA, "Alpha", 41.3110, 69.2400, channel);
        insertLocation(BRAVO, "Bravo", 41.3300, 69.3000, channel);
        insertLocation(CHARLIE, "Charlie", 41.3112, 69.2402, channel);
        insertLocation(DELTA, "Delta", null, null, channel);
        insertLocation(DUP_ONE, "Dup 1", 41.3500, 69.3500, channel);
        insertLocation(DUP_TWO, "Dup 2", 41.3500, 69.3500, channel);
        insertLocation(OTHER_TENANT, OTHER_TENANTS_BRAND, FOREIGN_LOCATION, "Foreign", 41.0, 69.0, null);
        publishMenu();
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(READER, PlatformRole.TENANT_FINANCE, TENANT);
    }

    private String path(UUID tenantId, UUID brandId) {
        return "/api/v1/operations/tenants/" + tenantId + "/brands/" + brandId + "/storefront-branch-order";
    }

    private MvcResult setOrder(String key, UUID... ids) throws Exception {
        return setOrderAs(OWNER, TENANT, BRAND, key, ids);
    }

    /** A save made the way the console makes it: against the version of the order the subject reads just before. */
    private MvcResult setOrderAs(String subject, UUID tenantId, UUID brandId, String key, UUID... ids)
            throws Exception {
        return setOrderAgainst(subject, tenantId, brandId, key, currentVersion(subject, tenantId, brandId), ids);
    }

    private MvcResult setOrderAgainst(
            String subject, UUID tenantId, UUID brandId, String key, String ifMatch, UUID... ids) throws Exception {
        List<String> quoted = new ArrayList<>();
        for (UUID id : ids) {
            quoted.add("\"" + id + "\"");
        }
        return mvc.perform(put(path(tenantId, brandId))
                        .with(tokenFor(subject))
                        .header(IDEMPOTENCY_HEADER, key)
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationIds\":[" + String.join(",", quoted) + "]}"))
                .andReturn();
    }

    /** The ETag of the order as the subject reads it now; {@code W/"0"} for a subject who cannot read one. */
    private String currentVersion(String subject, UUID tenantId, UUID brandId) throws Exception {
        MvcResult result = mvc.perform(get(path(tenantId, brandId)).with(tokenFor(subject)))
                .andReturn();
        String etag = result.getResponse().getHeader("ETag");
        return etag == null ? "W/\"0\"" : etag;
    }

    private JsonNode read() throws Exception {
        MvcResult result =
                mvc.perform(get(path(TENANT, BRAND)).with(tokenFor(OWNER))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** What a customer's phone is sent, in the order it is sent, as the branch names. */
    private List<String> search(String requestBody) throws Exception {
        return searchResult(requestBody).findValuesAsString("locationName");
    }

    private JsonNode searchResult(String requestBody) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/storefront/pickup-locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("nothing set: the branches are listed by name and every one reports no storefront position")
    void nothingSetListsByName() throws Exception {
        JsonNode branches = read();

        assertThat(branches.findValuesAsString("displayName"))
                .containsExactly("Alpha", "Bravo", "Charlie", "Delta", "Dup 1", "Dup 2");
        branches.forEach(branch -> assertThat(branch.get("storefrontSortOrder").isNull())
                .as(branch.get("displayName").asString())
                .isTrue());
    }

    @Test
    @DisplayName("the order is set first to last, and a branch left out goes back to unset and sorts last")
    void anOrderReplacesTheOneBeforeIt() throws Exception {
        assertThat(setOrder("order-1", CHARLIE, ALPHA).getResponse().getStatus())
                .isEqualTo(200);

        JsonNode afterFirst = read();
        assertThat(afterFirst.findValuesAsString("displayName"))
                .containsExactly("Charlie", "Alpha", "Bravo", "Delta", "Dup 1", "Dup 2");
        assertThat(afterFirst.get(0).get("storefrontSortOrder").asInt()).isEqualTo(10);
        assertThat(afterFirst.get(1).get("storefrontSortOrder").asInt()).isEqualTo(20);

        // Bravo only: Charlie and Alpha are left out, so they are unset again rather than quietly keeping their places.
        assertThat(setOrder("order-2", BRAVO).getResponse().getStatus()).isEqualTo(200);

        JsonNode afterSecond = read();
        assertThat(afterSecond.findValuesAsString("displayName"))
                .containsExactly("Bravo", "Alpha", "Charlie", "Delta", "Dup 1", "Dup 2");
        assertThat(afterSecond.get(0).get("storefrontSortOrder").asInt()).isEqualTo(10);
        assertThat(afterSecond.get(1).get("storefrontSortOrder").isNull()).isTrue();
        assertThat(afterSecond.get(2).get("storefrontSortOrder").isNull()).isTrue();
    }

    @Test
    @DisplayName("with no point the customer is sent the brand's order, and no distance is claimed")
    void noPointUsesTheBrandsOrder() throws Exception {
        assertThat(search("{\"limit\": 20}"))
                .as("nothing set: by name, the way the list read before this existed")
                .containsExactly("Alpha", "Bravo", "Charlie", "Delta", "Dup 1", "Dup 2");

        setOrder("order-1", DELTA, CHARLIE, DUP_TWO);

        JsonNode unlocated = searchResult("{\"limit\": 20}");
        assertThat(unlocated.get("locations").findValuesAsString("locationName"))
                .containsExactly("Delta", "Charlie", "Dup 2", "Alpha", "Bravo", "Dup 1");
        unlocated.get("locations").forEach(branch -> {
            assertThat(branch.get("distanceKnown").asBoolean()).isFalse();
            assertThat(branch.get("distanceMeters").asLong())
                    .as("an unknown distance is reported as nothing, not as a branch that is zero metres away")
                    .isZero();
        });
    }

    @Test
    @DisplayName(
            "with no point and no brand, every brand's own order is kept and the brands are interleaved by rank, not blended by their numbers")
    void noPointAcrossBrandsInterleavesEachBrandsOwnOrder() throws Exception {
        openSecondStorefront();
        grant(OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
        setOrder("order-main", CHARLIE, ALPHA);
        setOrderAs(OWNER, OTHER_TENANT, OTHER_TENANTS_BRAND, "order-theirs", YAK);

        // MAIN placed Charlie at 10 and Alpha at 20; THEIRS placed Yak at 10 and left Zed unset. Sorting by the raw
        // numbers
        // would put THEIRS' unplaced Zed behind every one of MAIN's branches, placed or not; ranking inside each brand
        // puts
        // each brand's second branch (Alpha, Zed) ahead of any brand's third.
        assertThat(search("{\"limit\": 4}")).containsExactly("Charlie", "Yak", "Alpha", "Zed");
        assertThat(search("{\"limit\": 20}"))
                .containsExactly("Charlie", "Yak", "Alpha", "Zed", "Bravo", "Delta", "Dup 1", "Dup 2");
    }

    @Test
    @DisplayName("naming a brand lists that brand's branches alone, in the order that brand set")
    void aBrandNamedInTheSearchGetsItsOwnOrderAlone() throws Exception {
        openSecondStorefront();
        grant(OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
        setOrder("order-main", DELTA, CHARLIE);
        setOrderAs(OWNER, OTHER_TENANT, OTHER_TENANTS_BRAND, "order-theirs", ZED, YAK);

        assertThat(search("{\"brandId\": \"" + BRAND + "\", \"limit\": 20}"))
                .containsExactly("Delta", "Charlie", "Alpha", "Bravo", "Dup 1", "Dup 2");
        assertThat(search("{\"brandId\": \"" + OTHER_TENANTS_BRAND + "\", \"limit\": 20}"))
                .containsExactly("Zed", "Yak");
        assertThat(search("{\"brandId\": \"" + OTHER_TENANTS_BRAND + "\", \"point\": {\"latitude\": 41.3110, "
                        + "\"longitude\": 69.2400}}"))
                .as("a point narrows nothing beyond the brand: the other brand's branches stay out")
                .containsExactlyInAnyOrder("Yak", "Zed");
        assertThat(search("{\"brandId\": \"" + UUID.randomUUID() + "\"}"))
                .as("a brand with no published storefront has nothing to list")
                .isEmpty();
    }

    @Test
    @DisplayName("with a point, nearest comes first whatever the order says, and the order breaks a tie in distance")
    void aPointStillWinsAndTheOrderBreaksTies() throws Exception {
        // Dup 1 and Dup 2 stand at the same coordinate; without an order the id decides.
        assertThat(search(AT_ALPHA)).containsExactly("Alpha", "Charlie", "Bravo", "Dup 1", "Dup 2");

        // The brand puts Bravo first and Dup 2 before Dup 1: Bravo must not leap over Alpha and Charlie, but Dup 2 may
        // leap over Dup 1 because their distance is identical.
        setOrder("order-1", BRAVO, DUP_TWO, DUP_ONE);

        JsonNode located = searchResult(AT_ALPHA);
        assertThat(located.get("locations").findValuesAsString("locationName"))
                .containsExactly("Alpha", "Charlie", "Bravo", "Dup 2", "Dup 1");
        located.get("locations")
                .forEach(branch -> assertThat(branch.get("distanceKnown").asBoolean())
                        .as(branch.get("locationName").asString())
                        .isTrue());
        assertThat(located.get("locations").get(0).get("distanceMeters").asLong())
                .isZero();
        assertThat(located.get("locations").get(1).get("distanceMeters").asLong())
                .isPositive();
    }

    @Test
    @DisplayName("a branch with no coordinate is offered when there is no point and is left out when there is one")
    void aBranchWithoutACoordinateOnlyAppearsWithoutAPoint() throws Exception {
        assertThat(search("{\"limit\": 20}")).contains("Delta");
        assertThat(search(AT_ALPHA)).doesNotContain("Delta");
    }

    @Test
    @DisplayName("a save with no version is refused: a replacement cannot be sent blind")
    void aSaveWithoutAVersionIsRefused() throws Exception {
        MvcResult blind = mvc.perform(put(path(TENANT, BRAND))
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "blind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationIds\":[\"" + ALPHA + "\"]}"))
                .andReturn();

        assertThat(blind.getResponse().getStatus()).isEqualTo(400);
        read().forEach(branch -> assertThat(branch.get("storefrontSortOrder").isNull())
                .as(branch.get("displayName").asString())
                .isTrue());
    }

    @Test
    @DisplayName("a console that read the order before a colleague changed it cannot unplace the colleague's branch")
    void aStaleConsoleCannotUnplaceAColleaguesBranch() throws Exception {
        setOrder("order-0", ALPHA, BRAVO);
        String openedByA = currentVersion(OWNER, TENANT, BRAND);

        // B opens the screen, places Charlie and saves.
        assertThat(setOrder("order-b", ALPHA, BRAVO, CHARLIE).getResponse().getStatus())
                .isEqualTo(200);

        // A's screen still shows Alpha and Bravo only. A reverses them: the list does not name Charlie, so an unguarded
        // replace-all would quietly return Charlie to unset.
        MvcResult stale = setOrderAgainst(OWNER, TENANT, BRAND, "order-a", openedByA, BRAVO, ALPHA);

        assertThat(stale.getResponse().getStatus())
                .as(stale.getResponse().getContentAsString())
                .isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        JsonNode now = read();
        assertThat(now.findValuesAsString("displayName"))
                .containsExactly("Alpha", "Bravo", "Charlie", "Delta", "Dup 1", "Dup 2");
        assertThat(now.get(2).get("storefrontSortOrder").asInt())
                .as("Charlie keeps the place B gave it")
                .isEqualTo(30);

        // A reads again, sees Charlie, and the same change is saved.
        assertThat(setOrder("order-a2", BRAVO, ALPHA, CHARLIE).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(read().findValuesAsString("displayName"))
                .containsExactly("Bravo", "Alpha", "Charlie", "Delta", "Dup 1", "Dup 2");
    }

    @Test
    @DisplayName("two saves against one version cannot both win")
    void twoSavesAgainstOneVersionCannotBothWin() throws Exception {
        setOrder("order-0", ALPHA, BRAVO);
        String shared = currentVersion(OWNER, TENANT, BRAND);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Integer> first = pool.submit(() -> {
                go.await();
                return setOrderAgainst(OWNER, TENANT, BRAND, "race-1", shared, BRAVO, ALPHA)
                        .getResponse()
                        .getStatus();
            });
            java.util.concurrent.Future<Integer> second = pool.submit(() -> {
                go.await();
                return setOrderAgainst(OWNER, TENANT, BRAND, "race-2", shared, ALPHA, BRAVO, CHARLIE)
                        .getResponse()
                        .getStatus();
            });
            go.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName(
            "the version is the order: a save answers with the version a read then reports, and a rename leaves it alone")
    void theVersionIsTheOrder() throws Exception {
        String empty = currentVersion(OWNER, TENANT, BRAND);

        MvcResult saved = setOrder("order-1", CHARLIE, ALPHA);
        String returned = saved.getResponse().getHeader("ETag");

        assertThat(returned).isNotNull().isNotEqualTo(empty).isEqualTo(currentVersion(OWNER, TENANT, BRAND));

        jdbc.sql("UPDATE tenant.locations SET display_name = 'Bravo II' WHERE id = :id")
                .param("id", BRAVO)
                .update();
        assertThat(currentVersion(OWNER, TENANT, BRAND))
                .as("renaming a branch nobody placed does not change the order")
                .isEqualTo(returned);

        setOrder("order-2", ALPHA, CHARLIE);
        assertThat(currentVersion(OWNER, TENANT, BRAND))
                .as("moving a branch does")
                .isNotEqualTo(returned);
    }

    @Test
    @DisplayName("a branch that is not this brand's, or one named twice, is refused and nothing changes")
    void invalidOrdersAreRefused() throws Exception {
        setOrder("order-0", ALPHA);

        MvcResult foreign = setOrder("order-foreign", BRAVO, FOREIGN_LOCATION);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(400);
        MvcResult twice = setOrder("order-twice", BRAVO, BRAVO);
        assertThat(twice.getResponse().getStatus()).isEqualTo(400);
        MvcResult unknown = setOrder("order-unknown", UUID.fromString("018f9a30-4000-7000-8000-0000000000ff"));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);

        JsonNode branches = read();
        assertThat(branches.get(0).get("displayName").asString()).isEqualTo("Alpha");
        assertThat(branches.get(0).get("storefrontSortOrder").asInt()).isEqualTo(10);
        assertThat(branches.get(1).get("storefrontSortOrder").isNull())
                .as("the refused orders left no half-applied position behind")
                .isTrue();
    }

    @Test
    @DisplayName("another tenant's brand is not found, whoever asks, and its branches are never touched")
    void anotherTenantsBrandIsNotReachable() throws Exception {
        grant(OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);

        MvcResult asTheirOwner = setOrderAs(OWNER, OTHER_TENANT, OTHER_TENANTS_BRAND, "theirs", FOREIGN_LOCATION);
        assertThat(asTheirOwner.getResponse().getStatus()).isEqualTo(200);

        // The owner of TENANT names the foreign brand under their own tenant id: the brand is not theirs.
        MvcResult crossed = setOrderAs(OWNER, TENANT, OTHER_TENANTS_BRAND, "crossed", FOREIGN_LOCATION);
        assertThat(crossed.getResponse().getStatus()).isEqualTo(404);

        Integer foreignPosition = jdbc.sql("SELECT storefront_sort_order FROM tenant.locations WHERE id = :id")
                .param("id", FOREIGN_LOCATION)
                .query(Integer.class)
                .single();
        assertThat(foreignPosition).isEqualTo(10);
    }

    @Test
    @DisplayName("someone who can read locations but not write them can see the order and cannot change it")
    void writingNeedsTheWriteCapability() throws Exception {
        MvcResult read =
                mvc.perform(get(path(TENANT, BRAND)).with(tokenFor(READER))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);

        MvcResult write = setOrderAs(READER, TENANT, BRAND, "reader", ALPHA);
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(read().get(0).get("storefrontSortOrder").isNull()).isTrue();

        MvcResult noToken = mvc.perform(put(path(TENANT, BRAND))
                        .header(IDEMPOTENCY_HEADER, "anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationIds\":[]}"))
                .andReturn();
        assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("every change is audited with the positions it moved, in the same transaction")
    void everyChangeIsAudited() throws Exception {
        setOrder("order-1", DUP_TWO, ALPHA);

        List<String> documents = jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                        WHERE action_code = 'brand.storefront_branch_order.changed'
                        """).query(String.class).list();

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst())
                .contains("branch." + DUP_TWO)
                .contains("branch." + ALPHA)
                .doesNotContain("branch." + BRAVO);
    }

    @Test
    @DisplayName("naming no branches clears the order, and the public search needs no point to be answered")
    void anEmptyOrderClearsEverything() throws Exception {
        setOrder("order-1", CHARLIE, ALPHA);
        assertThat(setOrder("order-2").getResponse().getStatus()).isEqualTo(200);

        read().forEach(branch -> assertThat(branch.get("storefrontSortOrder").isNull())
                .as(branch.get("displayName").asString())
                .isTrue());
        assertThat(search("{}")).startsWith("Alpha");
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID tenantId, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
    }

    private void insertLocation(
            UUID locationId,
            String name,
            @Nullable Double latitude,
            @Nullable Double longitude,
            @Nullable UUID channelId) {
        insertLocation(TENANT, BRAND, locationId, name, latitude, longitude, channelId);
    }

    private void insertLocation(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String name,
            @Nullable Double latitude,
            @Nullable Double longitude,
            @Nullable UUID channelId) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status,
                    version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0,
                    CAST(:latitude AS double precision), CAST(:longitude AS double precision),
                    CASE WHEN CAST(:latitude AS double precision) IS NULL THEN 'NOT_GEOCODED' ELSE 'MERCHANT_PIN' END)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", "L" + locationId.toString().substring(30).toUpperCase(java.util.Locale.ROOT))
                .param("slug", "l" + locationId.toString().substring(30))
                .param("name", name)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .update();
        if (channelId != null) {
            jdbc.sql("""
                    INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                    VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                    """)
                    .param("tenantId", tenantId)
                    .param("channelId", channelId)
                    .param("locationId", locationId)
                    .update();
        }
    }

    /** A second tenant whose brand also has a published storefront menu and two branches that take pickup. */
    private void openSecondStorefront() {
        UUID channel = UUID.fromString("018f9a30-4000-7000-8000-0000000000b2");
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE', 1)
                """).param("id", channel).param("tenantId", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, 'PICKUP', true)
                """)
                .param("tenantId", OTHER_TENANT)
                .param("channelId", channel)
                .update();
        insertLocation(OTHER_TENANT, OTHER_TENANTS_BRAND, YAK, "Yak", 41.0, 69.0, channel);
        insertLocation(OTHER_TENANT, OTHER_TENANTS_BRAND, ZED, "Zed", 41.0001, 69.0001, channel);
        UUID catalog = UUID.fromString("018f9a30-4000-7000-8000-0000000000e3");
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MENU', 'Menu', 'ACTIVE')
                """)
                .param("id", catalog)
                .param("tenantId", OTHER_TENANT)
                .param("brandId", OTHER_TENANTS_BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status, content_hash,
                    activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.fromString("018f9a30-4000-7000-8000-0000000000e4"))
                .param("tenantId", OTHER_TENANT)
                .param("brandId", OTHER_TENANTS_BRAND)
                .param("catalogId", catalog)
                .update();
    }

    private void publishMenu() {
        UUID catalog = UUID.fromString("018f9a30-4000-7000-8000-0000000000e1");
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MENU', 'Menu', 'ACTIVE')
                """)
                .param("id", catalog)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status, content_hash,
                    activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.fromString("018f9a30-4000-7000-8000-0000000000e2"))
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalog)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'storefront branch order endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", tenantId)
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
