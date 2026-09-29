package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link CommentPresetController}'s GET/POST/PUT endpoints, exercised through
 * the real HTTP stack — mirroring {@code KitchenStationControllerTests}' own
 * style for the analogous gap.
 *
 * <p>batch8-review2-findings.json, c-kitchen-catalog #2 (major): {@code
 * CommentPresetTests} calls {@link uz.horecaos.platform.catalog.application.CommentPresetService}
 * directly and never goes through Spring MVC, Jackson, the security filter
 * chain, or the idempotency interceptor. This class proves, at the wire, that
 * {@code @RequiresCapability(CATALOG_AUTHOR, mutating = true)} actually
 * refuses a caller who only holds {@code CATALOG_READ}, that a missing
 * {@code Idempotency-Key} is refused, and that the request bodies {@code
 * comment-presets-page.ts} actually sends (posModifierCode explicit, never
 * omitted; sortOrder always a number) bind and round-trip correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class CommentPresetControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Holds {@code catalog.read} only — the capability the sibling GET accepts, not what POST/PUT require. */
    private static final String READ_ONLY_STAFF = "comment-preset-http-read-only";
    /** Holds neither {@code catalog.read} nor {@code catalog.author}. */
    private static final String NO_CATALOG_ACCESS = "comment-preset-http-no-catalog";
    /** Holds {@code catalog.author} (and {@code catalog.read}) — what POST/PUT actually require. */
    private static final String AUTHOR = "comment-preset-http-author";
    /** The other tenant's owner: the isolation tests' second party. */
    private static final String OTHER_TENANT_OWNER = "comment-preset-http-other-owner";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the comment preset HTTP test");
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
    private JdbcCommentPresetStore presetStore;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE catalog.product_comment_presets, catalog.comment_presets CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'comment-preset-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'comment-preset-http-other', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();

        roleRegistry.synchronize();
        // LOCATION_MANAGER's own ScopeType is LOCATION, but a grant's stored
        // scope_type/scope_id is what CapabilityEnforcementInterceptor
        // actually checks -- ReportingControllerCapabilityHttpTests grants
        // the identical role at TENANT scope for the same reason: the fixture
        // needs "holds CATALOG_READ, not CATALOG_AUTHOR", not the role's own
        // native scope.
        grant(READ_ONLY_STAFF, PlatformRole.LOCATION_MANAGER);
        grant(NO_CATALOG_ACCESS, PlatformRole.COURIER_DISPATCHER);
        grant(AUTHOR, PlatformRole.TENANT_OWNER);
        grantAt(OTHER_TENANT_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
    }

    // ------------------------------------------------------------------- GET

    @Test
    @DisplayName("GET refuses a caller holding neither catalog.read nor catalog.author")
    void listRefusesACallerWithoutCatalogRead() throws Exception {
        MvcResult result =
                mvc.perform(get(path()).with(tokenFor(NO_CATALOG_ACCESS))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("GET succeeds for a caller holding only catalog.read")
    void listSucceedsForReadOnlyStaff() throws Exception {
        seedPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion");

        MvcResult result =
                mvc.perform(get(path()).with(tokenFor(READ_ONLY_STAFF))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("NO_ONION")
                .contains("No onion");
    }

    // ------------------------------------------------------------------ POST

    @Test
    @DisplayName("POST refuses a caller holding only catalog.read, not catalog.author")
    void createRefusesACallerWithoutCatalogAuthor() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "comment-preset-create-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newPresetBody("NO_ONION", null)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("READ_ONLY_STAFF holds catalog.read, not catalog.author")
                .isEqualTo(403);
        assertThat(presetCount()).isZero();
    }

    @Test
    @DisplayName("POST refuses a request with no Idempotency-Key header")
    void createRefusesAMissingIdempotencyKey() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newPresetBody("NO_ONION", null)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("mutating = true on CommentPresetController.create requires Idempotency-Key")
                .isEqualTo(400);
        assertThat(presetCount()).isZero();
    }

    @Test
    @DisplayName("POST with the console's real body registers the preset and answers 200")
    void createWithTheConsolesRealBodyRegisters() throws Exception {
        // Exactly the shape comment-presets-page.ts's createPreset() builds:
        // posModifierCode explicit (null when the operator left it blank,
        // never omitted), sortOrder always a number.
        MvcResult result = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-create-200-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NO_ONION","labelRu":"Без лука","labelUz":"Piyozsiz",\
                                "labelEn":"No onion","posModifierCode":null,"sortOrder":0}\
                                """))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"NO_ONION\"").contains("\"version\":1");
        assertThat(presetCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST with posModifierCode filled round-trips it")
    void createWithPosModifierCodeRoundTrips() throws Exception {
        MvcResult result = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-create-200-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newPresetBody("EXTRA_SPICY", "MOD-SPICY")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"posModifierCode\":\"MOD-SPICY\"");
    }

    @Test
    @DisplayName("POST refuses a body missing the required code field")
    void createRefusesABodyMissingCode() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-create-400-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"labelRu":"Без лука","labelUz":"Piyozsiz","labelEn":"No onion",\
                                "posModifierCode":null,"sortOrder":0}\
                                """))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("code is @NotBlank on NewPresetRequest")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("A second POST with the same code is refused as a conflict, not a 500")
    void createRefusesADuplicateCode() throws Exception {
        mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-create-dup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newPresetBody("NO_ONION", null)))
                .andReturn();

        MvcResult second = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-create-dup-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newPresetBody("NO_ONION", null)))
                .andReturn();

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
    }

    // -------------------------------------------------------------------- PUT

    @Test
    @DisplayName("PUT refuses a caller holding only catalog.read, not catalog.author")
    void updateRefusesACallerWithoutCatalogAuthor() throws Exception {
        UUID presetId = seedPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion");

        MvcResult attempt = mvc.perform(put(path() + "/" + presetId)
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "comment-preset-update-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Без лука!", "Piyozsiz!", "No onion!", null, 0, "ACTIVE", 1)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
        assertThat(presetLabelEn(presetId)).isEqualTo("No onion");
    }

    @Test
    @DisplayName("PUT refuses a body missing expectedVersion")
    void updateRefusesABodyMissingExpectedVersion() throws Exception {
        UUID presetId = seedPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion");

        MvcResult attempt = mvc.perform(put(path() + "/" + presetId)
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-update-400-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"labelRu":"Без лука!","labelUz":"Piyozsiz!","labelEn":"No onion!",\
                                "posModifierCode":null,"sortOrder":0,"status":"ACTIVE"}\
                                """))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("expectedVersion is @NotNull on UpdatePresetRequest")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("PUT refuses a body missing sortOrder -- Jackson 3 refuses an omitted primitive, not a 500")
    void updateRefusesABodyMissingSortOrder() throws Exception {
        UUID presetId = seedPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion");

        MvcResult attempt = mvc.perform(put(path() + "/" + presetId)
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-update-400-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"labelRu":"Без лука!","labelUz":"Piyozsiz!","labelEn":"No onion!",\
                                "posModifierCode":null,"status":"ACTIVE","expectedVersion":1}\
                                """))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("UpdatePresetRequest.sortOrder is a primitive int; Jackson 3 refuses an omitted value "
                        + "as MALFORMED_BODY rather than silently defaulting to 0")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("PUT with the console's real body corrects the preset for a caller holding catalog.author")
    void updateSucceedsForAuthor() throws Exception {
        UUID presetId = seedPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion");

        MvcResult result = mvc.perform(put(path() + "/" + presetId)
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-update-200-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Без лука!", "Piyozsiz!", "No onion!", "MOD-9", 3, "ACTIVE", 1)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(presetLabelEn(presetId)).isEqualTo("No onion!");
        assertThat(presetVersion(presetId)).isEqualTo(2);
    }

    @Test
    @DisplayName("PUT with status=ARCHIVED archives the preset rather than deleting it")
    void updateArchivesRatherThanDeletes() throws Exception {
        UUID presetId = seedPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion");

        MvcResult result = mvc.perform(put(path() + "/" + presetId)
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-update-archive-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Без лука", "Piyozsiz", "No onion", null, 0, "ARCHIVED", 1)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(presetStatus(presetId)).isEqualTo("ARCHIVED");
        assertThat(presetCount())
                .as("archiving is a status change, never a delete")
                .isEqualTo(1);
    }

    // ------------------------------------------------- row 10.12: per-locale wording

    @Test
    @DisplayName("POST with per-locale labels writes the platform columns AND the translations table")
    void createWithLabelsWritesBothHomes() throws Exception {
        // The tenant has no brand, so its default is the platform's ru. ru and en are
        // supplied; uz-Latn is not, and its NOT NULL column takes the default wording.
        MvcResult result = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-labels-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                                 "labels":{"ru":"Без лука","en":"No onion"}}
                                """))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        UUID presetId = presetIdByCode("NO_ONION");
        assertThat(jdbc.sql(
                                "SELECT label_ru || '|' || label_uz || '|' || label_en FROM catalog.comment_presets WHERE id = :id")
                        .param("id", presetId)
                        .query(String.class)
                        .single())
                .as("the uz-Latn column is NOT NULL, so it takes the tenant's default (ru) wording")
                .isEqualTo("Без лука|Без лука|No onion");
        assertThat(translationRows(presetId))
                .as("only what the caller supplied is mirrored -- no invented uz-Latn translation")
                .containsOnly(Map.entry("ru", "Без лука"), Map.entry("en", "No onion"));
    }

    @Test
    @DisplayName("a locale outside the platform triple lives in the table and round-trips through the list")
    void aLocaleOutsideTheTripleRoundTrips() throws Exception {
        mvc.perform(post(path())
                .with(tokenFor(AUTHOR))
                .header("Idempotency-Key", "comment-preset-kaa-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                         "labels":{"ru":"Без лука","uz-Latn":"Piyozsiz","en":"No onion","kaa":"Piyazsiz"}}
                        """));

        Map<String, Object> listed =
                onlyPresetOf(mvc.perform(get(path()).with(tokenFor(AUTHOR))).andReturn());

        assertThat(labelsOf(listed))
                .containsExactly(
                        Map.entry("ru", "Без лука"),
                        Map.entry("uz-Latn", "Piyozsiz"),
                        Map.entry("en", "No onion"),
                        Map.entry("kaa", "Piyazsiz"));
        assertThat(listed).containsEntry("labelRu", "Без лука").doesNotContainKey("labelKaa");
    }

    @Test
    @DisplayName("a stale mirror row never shadows the column, and no locale is reported twice")
    void aStaleMirrorNeverShadowsTheColumn() throws Exception {
        UUID presetId = seedPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion");
        jdbc.sql("""
                INSERT INTO catalog.comment_preset_translations (tenant_id, preset_id, locale, label)
                VALUES (:t, :p, 'ru', 'STALE MIRROR'), (:t, :p, 'kaa', 'Piyazsiz')
                """).param("t", TENANT).param("p", presetId).update();

        Map<String, Object> listed =
                onlyPresetOf(mvc.perform(get(path()).with(tokenFor(AUTHOR))).andReturn());

        assertThat(labelsOf(listed))
                .as("the column is the source for a triple locale; kaa comes from the table; each once")
                .containsExactly(
                        Map.entry("ru", "Без лука"),
                        Map.entry("uz-Latn", "Piyozsiz"),
                        Map.entry("en", "No onion"),
                        Map.entry("kaa", "Piyazsiz"));
    }

    @Test
    @DisplayName("an edit that names one locale never deletes or rewrites the ones it does not name")
    void anEditNeverDeletesAHiddenLocale() throws Exception {
        // The editor shows only the locales the tenant supports; the preset also carries
        // kaa and uz-Latn wording the editor does not show. Editing en must leave them.
        mvc.perform(post(path())
                .with(tokenFor(AUTHOR))
                .header("Idempotency-Key", "comment-preset-hidden-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                         "labels":{"ru":"Без лука","uz-Latn":"Piyozsiz","en":"No onion","kaa":"Piyazsiz"}}
                        """));
        UUID presetId = presetIdByCode("NO_ONION");

        MvcResult edited = mvc.perform(put(path() + "/" + presetId)
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-hidden-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"posModifierCode":null,"sortOrder":2,"status":"ACTIVE","expectedVersion":1,
                                 "labels":{"en":"Hold the onion"}}
                                """))
                .andReturn();

        assertThat(edited.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> answer = JSON.readValue(edited.getResponse().getContentAsString(), Map.class);
        assertThat(labelsOf(answer))
                .containsExactly(
                        Map.entry("ru", "Без лука"),
                        Map.entry("uz-Latn", "Piyozsiz"),
                        Map.entry("en", "Hold the onion"),
                        Map.entry("kaa", "Piyazsiz"));
        assertThat(jdbc.sql(
                                "SELECT label_ru || '|' || label_uz || '|' || label_en FROM catalog.comment_presets WHERE id = :id")
                        .param("id", presetId)
                        .query(String.class)
                        .single())
                .as("the columns of the locales the edit did not name are untouched, not blanked")
                .isEqualTo("Без лука|Piyozsiz|Hold the onion");
        assertThat(translationRows(presetId)).contains(Map.entry("kaa", "Piyazsiz"), Map.entry("uz-Latn", "Piyozsiz"));
    }

    @Test
    @DisplayName("a create without the tenant's default-language wording is refused, and writes nothing")
    void aCreateWithoutTheDefaultLanguageIsRefused() throws Exception {
        MvcResult refused = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-nodefault-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                                 "labels":{"en":"No onion"}}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("(ru)");
        assertThat(presetCount()).isZero();
    }

    @Test
    @DisplayName("a bare 'uz' and a malformed locale are refused before they reach the table's CHECK")
    void aBareUzAndAMalformedLocaleAreRefused() throws Exception {
        for (String bad : List.of("uz", "RU_ru")) {
            MvcResult refused = mvc.perform(post(path())
                            .with(tokenFor(AUTHOR))
                            .header("Idempotency-Key", "comment-preset-badlocale-" + bad)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                                     "labels":{"ru":"Без лука","%s":"x"}}
                                    """.formatted(bad)))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).as("locale %s", bad).isEqualTo(400);
        }
        assertThat(presetCount()).isZero();
    }

    @Test
    @DisplayName("the locale set is the union of the tenant's brands, the first brand's default first")
    void theLocaleSetIsTheUnionWithTheFirstBrandsDefault() throws Exception {
        // 'Alpha' sorts before 'Beta' in the console's brand order, so Alpha's default wins.
        UUID alpha = insertBrand(TENANT, "ALPHA", "Alpha");
        UUID beta = insertBrand(TENANT, "BETA", "Beta");
        setBrandLocales(TENANT, alpha, "uz-Latn", "en");
        setBrandLocales(TENANT, beta, "ru", "en");
        // The other tenant's brand must not widen this tenant's set.
        UUID foreign = insertBrand(OTHER_TENANT, "FOREIGN", "Aaa");
        setBrandLocales(OTHER_TENANT, foreign, "kaa");

        MvcResult result = mvc.perform(get(path() + "/locale-set").with(tokenFor(READ_ONLY_STAFF)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> set = JSON.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(set)
                .containsEntry("locales", List.of("uz-Latn", "ru", "en"))
                .containsEntry("defaultLocale", "uz-Latn")
                .containsEntry("configured", true);
    }

    @Test
    @DisplayName(
            "with the default set by the first brand, a create needs that language, and fills the other columns from it")
    void theFirstBrandsDefaultIsWhatACreateRequires() throws Exception {
        UUID alpha = insertBrand(TENANT, "ALPHA", "Alpha");
        setBrandLocales(TENANT, alpha, "uz-Latn");

        MvcResult withoutIt = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-uzdefault-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                                 "labels":{"ru":"Без лука"}}
                                """))
                .andReturn();
        assertThat(withoutIt.getResponse().getStatus())
                .as("uz-Latn is this tenant's default and was not supplied")
                .isEqualTo(400);

        MvcResult withIt = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "comment-preset-uzdefault-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                                 "labels":{"uz-Latn":"Piyozsiz"}}
                                """))
                .andReturn();
        assertThat(withIt.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql(
                                "SELECT label_ru || '|' || label_uz || '|' || label_en FROM catalog.comment_presets WHERE code = 'NO_ONION'")
                        .query(String.class)
                        .single())
                .isEqualTo("Piyozsiz|Piyozsiz|Piyozsiz");
    }

    @Test
    @DisplayName("GET locale-set refuses a caller holding neither catalog.read nor catalog.author")
    void theLocaleSetRefusesACallerWithoutCatalogRead() throws Exception {
        MvcResult refused = mvc.perform(get(path() + "/locale-set").with(tokenFor(NO_CATALOG_ACCESS)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("another tenant can neither read nor rewrite this tenant's preset translations")
    void translationsAreTenantIsolatedOverHttp() throws Exception {
        mvc.perform(post(path())
                .with(tokenFor(AUTHOR))
                .header("Idempotency-Key", "comment-preset-iso-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                         "labels":{"ru":"Без лука","kaa":"Piyazsiz"}}
                        """));
        UUID presetId = presetIdByCode("NO_ONION");

        // The other tenant's own list shows nothing of it.
        MvcResult foreignList = mvc.perform(get(pathOf(OTHER_TENANT)).with(tokenFor(OTHER_TENANT_OWNER)))
                .andReturn();
        assertThat(foreignList.getResponse().getStatus()).isEqualTo(200);
        assertThat(foreignList.getResponse().getContentAsString()).isEqualTo("[]");

        // Authorised for their own tenant's path, naming this tenant's preset id: not found.
        MvcResult foreignWrite = mvc.perform(put(pathOf(OTHER_TENANT) + "/" + presetId)
                        .with(tokenFor(OTHER_TENANT_OWNER))
                        .header("Idempotency-Key", "comment-preset-iso-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"posModifierCode":null,"sortOrder":0,"status":"ACTIVE","expectedVersion":1,
                                 "labels":{"ru":"HIJACKED","kaa":"HIJACKED"}}
                                """))
                .andReturn();
        assertThat(foreignWrite.getResponse().getStatus()).isEqualTo(404);

        // And this tenant's path is closed to the other tenant's owner.
        MvcResult foreignPath =
                mvc.perform(get(path()).with(tokenFor(OTHER_TENANT_OWNER))).andReturn();
        assertThat(foreignPath.getResponse().getStatus()).isEqualTo(403);

        assertThat(translationRows(presetId)).containsOnly(Map.entry("ru", "Без лука"), Map.entry("kaa", "Piyazsiz"));
    }

    @Test
    @DisplayName("the store's upsert cannot be turned on another tenant's row: it neither rewrites nor inserts")
    void theUpsertCannotCrossTenants() throws Exception {
        mvc.perform(post(path())
                .with(tokenFor(AUTHOR))
                .header("Idempotency-Key", "comment-preset-upsert-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"NO_ONION","posModifierCode":null,"sortOrder":0,
                         "labels":{"ru":"Без лука"}}
                        """));
        UUID presetId = presetIdByCode("NO_ONION");

        // The conflict target (preset_id, locale) names no tenant. Without the tenant
        // condition on DO UPDATE this rewrites the victim's ru wording.
        presetStore.upsertTranslations(OTHER_TENANT, presetId, Map.of("ru", "HIJACKED"), Instant.now());
        assertThat(translationRows(presetId))
                .as("the existing row was left alone, not taken over")
                .containsOnly(Map.entry("ru", "Без лука"));

        // A locale with no row takes the INSERT path, where the composite foreign key
        // (preset_id, tenant_id) refuses a preset that is not the other tenant's.
        Throwable failure = catchThrowable(
                () -> presetStore.upsertTranslations(OTHER_TENANT, presetId, Map.of("kaa", "x"), Instant.now()));
        assertThat(failure).isNotNull();
        assertThat(translationRows(presetId)).doesNotContainKey("kaa");
    }

    // ------------------------------------------------------------------ fixtures

    private static String path() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/comment-presets";
    }

    private static String newPresetBody(String code, @Nullable String posModifierCode) {
        String pos = posModifierCode == null ? "null" : "\"" + posModifierCode + "\"";
        return """
                {"code":"%s","labelRu":"Без лука","labelUz":"Piyozsiz","labelEn":"No onion",\
                "posModifierCode":%s,"sortOrder":0}\
                """.formatted(code, pos);
    }

    private static String updateBody(
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            String status,
            int expectedVersion) {
        String pos = posModifierCode == null ? "null" : "\"" + posModifierCode + "\"";
        return """
                {"labelRu":"%s","labelUz":"%s","labelEn":"%s","posModifierCode":%s,\
                "sortOrder":%d,"status":"%s","expectedVersion":%d}\
                """.formatted(labelRu, labelUz, labelEn, pos, sortOrder, status, expectedVersion);
    }

    private UUID seedPreset(String code, String labelRu, String labelUz, String labelEn) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO catalog.comment_presets
                            (id, tenant_id, code, label_ru, label_uz, label_en, sort_order, status, version)
                        VALUES (:id, :tenantId, :code, :labelRu, :labelUz, :labelEn, 0, 'ACTIVE', 1)
                        """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("labelRu", labelRu)
                .param("labelUz", labelUz)
                .param("labelEn", labelEn)
                .update();
        return id;
    }

    private long presetCount() {
        return jdbc.sql("SELECT count(*) FROM catalog.comment_presets WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }

    private String presetLabelEn(UUID presetId) {
        return jdbc.sql("SELECT label_en FROM catalog.comment_presets WHERE id = :id")
                .param("id", presetId)
                .query(String.class)
                .single();
    }

    private String presetStatus(UUID presetId) {
        return jdbc.sql("SELECT status FROM catalog.comment_presets WHERE id = :id")
                .param("id", presetId)
                .query(String.class)
                .single();
    }

    private int presetVersion(UUID presetId) {
        return jdbc.sql("SELECT version FROM catalog.comment_presets WHERE id = :id")
                .param("id", presetId)
                .query(Integer.class)
                .single();
    }

    private UUID presetIdByCode(String code) {
        return jdbc.sql("SELECT id FROM catalog.comment_presets WHERE tenant_id = :t AND code = :c")
                .param("t", TENANT)
                .param("c", code)
                .query(UUID.class)
                .single();
    }

    private Map<String, String> translationRows(UUID presetId) {
        Map<String, String> rows = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT locale, label FROM catalog.comment_preset_translations WHERE preset_id = :p ORDER BY locale")
                .param("p", presetId)
                .query((rs, n) -> rows.put(rs.getString("locale"), rs.getString("label")))
                .list();
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyPresetOf(MvcResult listResult) throws Exception {
        assertThat(listResult.getResponse().getStatus()).isEqualTo(200);
        List<Map<String, Object>> presets =
                JSON.readValue(listResult.getResponse().getContentAsString(), List.class);
        assertThat(presets).hasSize(1);
        return presets.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labelsOf(Map<String, Object> preset) {
        return java.util.Objects.requireNonNull((Map<String, String>) preset.get("labels"));
    }

    private static String pathOf(UUID tenantId) {
        return "/api/v1/control-plane/tenants/" + tenantId + "/comment-presets";
    }

    private UUID insertBrand(UUID tenantId, String code, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :displayName, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .param("displayName", displayName)
                .update();
        return id;
    }

    /** The first locale is the brand's default. */
    private void setBrandLocales(UUID tenantId, UUID brandId, String... locales) {
        for (int i = 0; i < locales.length; i++) {
            jdbc.sql("""
                    INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                    VALUES (:tenantId, :brandId, :locale, :isDefault)
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("locale", locales[i])
                    .param("isDefault", i == 0)
                    .update();
        }
    }

    private void grantAt(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'comment preset http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'comment preset http test', :validFrom)
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
