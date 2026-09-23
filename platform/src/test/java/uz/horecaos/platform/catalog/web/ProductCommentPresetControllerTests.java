package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link ProductCommentPresetController}'s GET/POST/DELETE endpoints,
 * exercised through the real HTTP stack — mirroring {@code
 * KitchenStationControllerTests}' own style for the analogous gap.
 *
 * <p>batch8-review2-findings.json, c-kitchen-catalog #2 (major): {@code
 * CommentPresetTests} calls {@link uz.horecaos.platform.catalog.application.CommentPresetService}
 * directly and never goes through Spring MVC, Jackson, the security filter
 * chain, or the idempotency interceptor. This class proves, at the wire, that
 * {@code @RequiresCapability(CATALOG_AUTHOR, BRAND, mutating = true)} on
 * attach/detach actually refuses a caller who only holds {@code
 * CATALOG_READ}, that a missing {@code Idempotency-Key} is refused, that
 * {@code AttachPresetRequest.sortOrder} being a primitive {@code int}
 * matches what {@code product-comment-presets-api.ts} (the product editor's
 * own picker, added alongside these tests for finding #1 of this same
 * review) actually sends, and that attaching an unknown preset is a 404, not
 * a 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductCommentPresetControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String LOCALE = "uz";

    /** Holds {@code catalog.read} only, at BRAND scope — the capability the sibling GET accepts. */
    private static final String READ_ONLY_STAFF = "product-comment-preset-http-read-only";
    /** Holds {@code catalog.author} (and {@code catalog.read}) at BRAND scope — what POST/DELETE require. */
    private static final String AUTHOR = "product-comment-preset-http-author";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the product comment preset HTTP test");
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
    private CatalogAuthoringService authoring;

    private UUID productId;
    private UUID presetId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE catalog.product_comment_presets, catalog.comment_presets, "
                        + "catalog.translations, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'product-comment-preset-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        productId = authoring
                .createProduct(
                        TENANT,
                        BRAND,
                        catalogId,
                        "PLOV-001",
                        "Osh",
                        null,
                        LOCALE,
                        "SKU-PLOV",
                        "PORTION",
                        FiscalClassification.unclassified(),
                        null)
                .productId();

        presetId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO catalog.comment_presets
                            (id, tenant_id, code, label_ru, label_uz, label_en, sort_order, status, version)
                        VALUES (:id, :t, 'NO_ONION', 'Без лука', 'Piyozsiz', 'No onion', 0, 'ACTIVE', 1)
                        """).param("id", presetId).param("t", TENANT).update();

        roleRegistry.synchronize();
        grant(READ_ONLY_STAFF, PlatformRole.LOCATION_STAFF);
        grant(AUTHOR, PlatformRole.BRAND_MANAGER);
    }

    // ------------------------------------------------------------------- GET

    @Test
    @DisplayName("GET refuses a caller holding no BRAND-scope catalog.read")
    void listRefusesACallerWithoutCatalogRead() throws Exception {
        MvcResult result = mvc.perform(get(path()).with(tokenFor("nobody-granted-anything")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("GET answers every preset attached to the product, for a caller holding only catalog.read")
    void listSucceedsForReadOnlyStaff() throws Exception {
        attachDirectly(presetId, 0);

        MvcResult result =
                mvc.perform(get(path()).with(tokenFor(READ_ONLY_STAFF))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("NO_ONION");
    }

    // ------------------------------------------------------------------ POST

    @Test
    @DisplayName("POST (attach) refuses a caller holding only catalog.read, not catalog.author")
    void attachRefusesACallerWithoutCatalogAuthor() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "product-comment-preset-attach-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachBody(presetId, 0)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("READ_ONLY_STAFF holds catalog.read, not catalog.author")
                .isEqualTo(403);
        assertThat(attachedCount()).isZero();
    }

    @Test
    @DisplayName("POST (attach) refuses a request with no Idempotency-Key header")
    void attachRefusesAMissingIdempotencyKey() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachBody(presetId, 0)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("mutating = true on ProductCommentPresetController.attach requires Idempotency-Key")
                .isEqualTo(400);
        assertThat(attachedCount()).isZero();
    }

    @Test
    @DisplayName("POST (attach) refuses a body missing sortOrder -- Jackson 3 refuses an omitted primitive, not a 500")
    void attachRefusesABodyMissingSortOrder() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "product-comment-preset-attach-400-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presetId\":\"" + presetId + "\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("AttachPresetRequest.sortOrder is a primitive int; Jackson 3 refuses an omitted value "
                        + "as MALFORMED_BODY rather than silently defaulting to 0")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("POST (attach) refuses a body missing presetId")
    void attachRefusesABodyMissingPresetId() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "product-comment-preset-attach-400-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":0}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("presetId is @NotNull on AttachPresetRequest")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("POST (attach) with the real product-editor body attaches the preset and answers 200")
    void attachWithTheConsolesRealBodySucceeds() throws Exception {
        // Exactly the shape product-comment-presets-api.ts's attach() builds
        // (added alongside these tests for finding #1 of this same review):
        // { presetId, sortOrder }, sortOrder always a number.
        MvcResult result = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "product-comment-preset-attach-200-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachBody(presetId, 2)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"code\":\"NO_ONION\"")
                .contains("\"sortOrder\":2");
        assertThat(attachedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST (attach) with an unknown presetId is a 404, not a 500")
    void attachRefusesAnUnknownPreset() throws Exception {
        UUID unknownPreset = UUID.randomUUID();

        MvcResult result = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "product-comment-preset-attach-404-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachBody(unknownPreset, 0)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST (attach) against an already-attached preset re-sorts it rather than duplicating the row")
    void attachTwiceResortsInsteadOfDuplicating() throws Exception {
        mvc.perform(post(path())
                .with(tokenFor(AUTHOR))
                .header("Idempotency-Key", "product-comment-preset-attach-resort-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(attachBody(presetId, 0)));

        MvcResult second = mvc.perform(post(path())
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "product-comment-preset-attach-resort-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachBody(presetId, 5)))
                .andReturn();

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(attachedCount())
                .as("one row per (product, preset), never a second")
                .isEqualTo(1);
        assertThat(second.getResponse().getContentAsString()).contains("\"sortOrder\":5");
    }

    // ---------------------------------------------------------------- DELETE

    @Test
    @DisplayName("DELETE (detach) refuses a caller holding only catalog.read, not catalog.author")
    void detachRefusesACallerWithoutCatalogAuthor() throws Exception {
        attachDirectly(presetId, 0);

        MvcResult attempt = mvc.perform(delete(path() + "/" + presetId)
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "product-comment-preset-detach-403-1"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
        assertThat(attachedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE (detach) refuses a request with no Idempotency-Key header")
    void detachRefusesAMissingIdempotencyKey() throws Exception {
        attachDirectly(presetId, 0);

        MvcResult attempt = mvc.perform(delete(path() + "/" + presetId).with(tokenFor(AUTHOR)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(400);
        assertThat(attachedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE (detach) removes the attachment for a caller holding catalog.author")
    void detachSucceedsForAuthor() throws Exception {
        attachDirectly(presetId, 0);

        MvcResult result = mvc.perform(delete(path() + "/" + presetId)
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "product-comment-preset-detach-200-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(attachedCount()).isZero();
    }

    @Test
    @DisplayName("DELETE (detach) is idempotent: detaching a pair that is already gone still answers 204")
    void detachIsIdempotent() throws Exception {
        MvcResult result = mvc.perform(delete(path() + "/" + presetId)
                        .with(tokenFor(AUTHOR))
                        .header("Idempotency-Key", "product-comment-preset-detach-idempotent-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
    }

    // ------------------------------------------------------------------ fixtures

    /** productId is set by {@code reset()} before any {@code @Test} method runs. */
    private String path() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/products/" + productId
                + "/comment-presets";
    }

    private static String attachBody(UUID presetId, int sortOrder) {
        return "{\"presetId\":\"" + presetId + "\",\"sortOrder\":" + sortOrder + "}";
    }

    private void attachDirectly(UUID presetId, int sortOrder) {
        jdbc.sql("""
                        INSERT INTO catalog.product_comment_presets
                            (id, tenant_id, brand_id, product_id, preset_id, sort_order, version)
                        VALUES (:id, :t, :b, :p, :preset, :sortOrder, 1)
                        """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("p", productId)
                .param("preset", presetId)
                .param("sortOrder", sortOrder)
                .update();
    }

    private long attachedCount() {
        return jdbc.sql("SELECT count(*) FROM catalog.product_comment_presets WHERE product_id = :p")
                .param("p", productId)
                .query(Long.class)
                .single();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'BRAND', :brandId,
                        'ACTIVE', 'test-fixture', 'product comment preset http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("brandId", BRAND)
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
