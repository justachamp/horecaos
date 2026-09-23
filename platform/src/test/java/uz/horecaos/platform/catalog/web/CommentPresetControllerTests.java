package uz.horecaos.platform.catalog.web;

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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
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
class CommentPresetControllerTests {

    private static final UUID TENANT = UUID.randomUUID();

    /** Holds {@code catalog.read} only — the capability the sibling GET accepts, not what POST/PUT require. */
    private static final String READ_ONLY_STAFF = "comment-preset-http-read-only";
    /** Holds neither {@code catalog.read} nor {@code catalog.author}. */
    private static final String NO_CATALOG_ACCESS = "comment-preset-http-no-catalog";
    /** Holds {@code catalog.author} (and {@code catalog.read}) — what POST/PUT actually require. */
    private static final String AUTHOR = "comment-preset-http-author";

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
