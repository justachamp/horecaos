package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0174's registries over HTTP, with the console's JSON: the capability each route declares, the
 * {@code Idempotency-Key} and {@code If-Match} it demands, the refusal codes the console branches on, and the
 * tenant boundary (a tenant owner cannot reach another tenant's vocabulary, and cannot assign its tags).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class VocabularyEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fd100-4000-7000-8000-0000000000d1");
    private static final UUID BRAND = UUID.fromString("018fd100-4000-7000-8000-0000000000d2");
    private static final UUID OTHER_TENANT = UUID.fromString("018fd100-4000-7000-8000-0000000000d3");
    private static final UUID OTHER_BRAND = UUID.fromString("018fd100-4000-7000-8000-0000000000d4");
    private static final UUID VARIANT = UUID.fromString("018fd100-4000-7000-8000-000000000d01");
    private static final UUID OTHER_VARIANT = UUID.fromString("018fd100-4000-7000-8000-000000000d02");
    private static final UUID PRODUCT = UUID.nameUUIDFromBytes(("product-" + VARIANT).getBytes(UTF_8));
    private static final UUID OTHER_PRODUCT = UUID.nameUUIDFromBytes(("product-" + OTHER_VARIANT).getBytes(UTF_8));
    private static final String OWNER = "vocab-owner";
    private static final String OTHER_OWNER = "vocab-other-owner";
    private static final String NO_GRANT = "vocab-no-grant";
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
        jdbc.sql("TRUNCATE TABLE catalog.product_tags, catalog.product_ingredients, catalog.tag_translations, "
                        + "catalog.ingredient_translations, catalog.tags, catalog.ingredients, "
                        + "catalog.translations, catalog.variants, catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "vocab-endpoint");
        insertBrand(TENANT, BRAND, "vocab-brand");
        insertTenant(OTHER_TENANT, "vocab-other");
        insertBrand(OTHER_TENANT, OTHER_BRAND, "vocab-other-brand");
        insertVariant(TENANT, BRAND, VARIANT, "PLOV");
        insertVariant(OTHER_TENANT, OTHER_BRAND, OTHER_VARIANT, "THEIRS");
        grant(OWNER, TENANT);
        grant(OTHER_OWNER, OTHER_TENANT);
    }

    @Test
    @DisplayName(
            "the console's body registers a tag: omitted switches default off, the ETag is the version, the wording is returned")
    void registersATagFromTheConsolesBody() throws Exception {
        MvcResult created = post(tagsPath(), OWNER, """
                {"code":"spicy","iconKey":"FLAME","showAsBadge":true,
                 "names":{"ru":"Острое","uz-Latn":"Achchiq","en":"Spicy"}}""");

        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        String body = created.getResponse().getContentAsString();
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("spicy");
        assertThat(JsonPath.<Boolean>read(body, "$.showAsFilter"))
                .as("omitted, so off")
                .isFalse();
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<String>read(body, "$.names.uz-Latn")).isEqualTo("Achchiq");

        String list = read(tagsPath(), OWNER);
        assertThat(JsonPath.<List<String>>read(list, "$[*].code")).containsExactly("spicy");
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.tag.created'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an ingredient is just a code and a wording: the tag-only fields are neither required nor kept")
    void registersAnIngredient() throws Exception {
        MvcResult created =
                post(ingredientsPath(), OWNER, "{\"code\":\"rice\",\"names\":{\"ru\":\"Рис\",\"uz-Latn\":\"Guruch\"}}");

        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        String body = created.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("rice");
        assertThat((Object) JsonPath.read(body, "$.iconKey")).isNull();
        assertThat(JsonPath.<Boolean>read(body, "$.showAsBadge")).isFalse();
    }

    @Test
    @DisplayName("the refusals carry the stable code the console branches on, with the right status")
    void refusalsAreStable() throws Exception {
        assertRefusal(post(tagsPath(), OWNER, tagBody("Bad Code", "FLAME")), 400, "VOCABULARY_CODE_INVALID");
        assertRefusal(post(tagsPath(), OWNER, tagBody("spicy", "SKULL")), 400, "TAG_ICON_UNKNOWN");
        assertRefusal(
                post(tagsPath(), OWNER, "{\"code\":\"spicy\",\"names\":{\"en\":\"Spicy\"},\"showAsBadge\":true}"),
                400,
                "VOCABULARY_DEFAULT_NAME_MISSING");
        assertRefusal(
                post(tagsPath(), OWNER, "{\"code\":\"spicy\",\"names\":{\"ru\":\"Острое\"}}"), 400, "TAG_NOT_VISIBLE");
        assertThat(post(tagsPath(), OWNER, tagBody("spicy", "FLAME"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertRefusal(post(tagsPath(), OWNER, tagBody("spicy", "LEAF")), 409, "VOCABULARY_CODE_TAKEN");
    }

    @Test
    @DisplayName("an edit needs the version it read; a stale one is refused and a missing one is not guessed")
    void editsAreVersioned() throws Exception {
        MvcResult created = post(tagsPath(), OWNER, tagBody("spicy", "FLAME"));
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));

        MvcResult edited = put(tagsPath() + "/" + id, OWNER, "W/\"1\"", "{\"sortOrder\":4,\"names\":{\"en\":\"Hot\"}}");
        assertThat(edited.getResponse().getStatus())
                .as(edited.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(edited.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        assertThat(JsonPath.<String>read(edited.getResponse().getContentAsString(), "$.names.en"))
                .isEqualTo("Hot");
        assertThat(JsonPath.<String>read(edited.getResponse().getContentAsString(), "$.names.ru"))
                .as("a locale the edit did not name is kept")
                .isEqualTo("Острое");

        assertThat(put(tagsPath() + "/" + id, OWNER, "W/\"1\"", "{\"sortOrder\":5}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
        assertThat(put(tagsPath() + "/" + id, OWNER, null, "{\"sortOrder\":5}")
                        .getResponse()
                        .getStatus())
                .isIn(400, 428);

        MvcResult archived = postWithMatch(tagsPath() + "/" + id + "/archive", OWNER, "W/\"2\"");
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(archived.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("ARCHIVED");
        assertThat(postWithMatch(tagsPath() + "/" + id + "/archive", OWNER, "W/\"2\"")
                        .getResponse()
                        .getStatus())
                .as("archiving twice from the same read is stale")
                .isEqualTo(409);
    }

    @Test
    @DisplayName(
            "a product's set is replaced in order, bounded, and cannot name another tenant's tag or another brand's product")
    void assignsTagsToAProduct() throws Exception {
        UUID a = createTag("a");
        UUID b = createTag("b");
        UUID theirs = UUID.fromString(JsonPath.read(
                post(tagsPath(OTHER_TENANT), OTHER_OWNER, tagBody("theirs", "LEAF"))
                        .getResponse()
                        .getContentAsString(),
                "$.id"));

        MvcResult set = putJson(productTagsPath(), OWNER, "{\"ids\":[\"" + b + "\",\"" + a + "\"]}");
        assertThat(set.getResponse().getStatus())
                .as(set.getResponse().getContentAsString())
                .isEqualTo(200);
        String order = read(productTagsPath(), OWNER);
        assertThat(JsonPath.<List<String>>read(order, "$.items[*].code")).containsExactly("b", "a");

        assertRefusal(
                putJson(productTagsPath(), OWNER, "{\"ids\":[\"" + a + "\",\"" + theirs + "\"]}"),
                422,
                "VOCABULARY_ENTRY_UNKNOWN");
        assertRefusal(
                putJson(productTagsPath(), OWNER, "{\"ids\":[\"" + a + "\",\"" + a + "\"]}"),
                400,
                "VOCABULARY_ENTRY_DUPLICATED");
        assertThat(JsonPath.<List<String>>read(read(productTagsPath(), OWNER), "$.items[*].code"))
                .as("neither refusal changed the set")
                .containsExactly("b", "a");
        assertThat(putJson(productTagsPath(OTHER_PRODUCT), OWNER, "{\"ids\":[]}")
                        .getResponse()
                        .getStatus())
                .as("a product of another tenant is not found, not forbidden-in-detail")
                .isIn(403, 404);
        assertThat(count("catalog.product_tags WHERE tenant_id = '" + OTHER_TENANT + "'"))
                .isZero();
    }

    @Test
    @DisplayName("only a tenant's own author reads or writes its vocabulary; anyone else is refused")
    void theTenantBoundaryHolds() throws Exception {
        post(tagsPath(), OWNER, tagBody("spicy", "FLAME"));

        for (String outsider : List.of(NO_GRANT, OTHER_OWNER)) {
            assertThat(mvc.perform(get(tagsPath()).with(tokenFor(outsider)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as("list as %s", outsider)
                    .isEqualTo(403);
            assertThat(post(tagsPath(), outsider, tagBody("sneaky", "FLAME"))
                            .getResponse()
                            .getStatus())
                    .as("create as %s", outsider)
                    .isEqualTo(403);
            assertThat(mvc.perform(get(productTagsPath()).with(tokenFor(outsider)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(403);
        }
        assertThat(count("catalog.tags")).isEqualTo(1);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(tagsPath())
                                .with(tokenFor(OWNER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(tagBody("keyless", "FLAME")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a mutation without an Idempotency-Key is refused")
                .isIn(400, 428);
    }

    // ---------------------------------------------------------------------------- helpers

    private UUID createTag(String code) throws Exception {
        MvcResult created = post(tagsPath(), OWNER, tagBody(code, "FLAME"));
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
    }

    private static String tagBody(String code, String icon) {
        return "{\"code\":\"" + code + "\",\"iconKey\":\"" + icon
                + "\",\"showAsBadge\":true,\"names\":{\"ru\":\"Острое\",\"uz-Latn\":\"Achchiq\"}}";
    }

    private void assertRefusal(MvcResult result, int status, String findingCode) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(status);
        assertThat(result.getResponse().getContentAsString()).contains(findingCode);
    }

    private static String tagsPath() {
        return tagsPath(TENANT);
    }

    private static String tagsPath(UUID tenant) {
        return "/api/v1/control-plane/tenants/" + tenant + "/tags";
    }

    private static String ingredientsPath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/ingredients";
    }

    private static String productTagsPath() {
        return productTagsPath(PRODUCT);
    }

    private static String productTagsPath(UUID product) {
        return "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/products/" + product + "/tags";
    }

    private MvcResult post(String path, String subject, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .with(tokenFor(subject))
                        .header(IDEMPOTENCY_HEADER, "vocab-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult postWithMatch(String path, String subject, String ifMatch) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .with(tokenFor(subject))
                        .header(IDEMPOTENCY_HEADER, "vocab-" + requests++)
                        .header("If-Match", ifMatch))
                .andReturn();
    }

    private MvcResult put(String path, String subject, @Nullable String ifMatch, String body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .with(tokenFor(subject))
                .header(IDEMPOTENCY_HEADER, "vocab-" + requests++)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult putJson(String path, String subject, String body) throws Exception {
        return put(path, subject, null, body);
    }

    private String read(String path, String subject) throws Exception {
        MvcResult result = mvc.perform(get(path).with(tokenFor(subject))).andReturn();
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

    private void insertVariant(UUID tenantId, UUID brandId, UUID variantId, String code) {
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
    }

    private void grant(String subject, UUID tenant) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'vocabulary endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + PlatformRole.TENANT_OWNER.code()).getBytes(UTF_8)))
                .param("tenantId", tenant)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.TENANT_OWNER))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
