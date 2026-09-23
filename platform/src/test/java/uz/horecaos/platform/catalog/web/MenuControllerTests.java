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
 * {@link MenuController}'s create/copy/bulk-add-by-filter/bind endpoints,
 * exercised through the real HTTP stack — mirroring {@code
 * CommentPresetControllerTests}' own style: the capability gate, the
 * idempotency requirement, and the request bodies the operations console
 * actually sends, all proven at the wire rather than by calling {@link
 * uz.horecaos.platform.catalog.application.MenuAuthoringService} directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MenuControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String LOCALE = "uz";

    /** Holds {@code catalog.read} only, at BRAND scope — the capability the sibling GET accepts. */
    private static final String READ_ONLY_STAFF = "menu-http-read-only";
    /** Holds {@code catalog.author} (and {@code catalog.read}) at BRAND scope. */
    private static final String BRAND_AUTHOR = "menu-http-brand-author";
    /** Holds {@code catalog.author} at LOCATION scope only — what bind/unbind require. */
    private static final String BRANCH_AUTHOR = "menu-http-branch-author";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the menu HTTP test");
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

    private UUID burgerVariantId;
    private UUID hotCategoryId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE catalog.branch_menu_bindings, catalog.menu_items, catalog.menus, "
                        + "catalog.translations, catalog.category_products, catalog.categories, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'menu-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :b, 'MAIN01', 'main-01', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();

        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        hotCategoryId = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 0);
        var burger = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "BURGER",
                "Burger",
                null,
                LOCALE,
                "SKU-BURGER",
                "PIECE",
                FiscalClassification.unclassified(),
                null);
        burgerVariantId = burger.defaultVariantId();
        authoring.placeProductInCategory(TENANT, BRAND, hotCategoryId, burger.productId(), 0);

        roleRegistry.synchronize();
        grant(READ_ONLY_STAFF, PlatformRole.LOCATION_STAFF, "BRAND", BRAND);
        grant(BRAND_AUTHOR, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(BRANCH_AUTHOR, PlatformRole.BRAND_MANAGER, "LOCATION", LOCATION);
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("POST refuses a caller holding only catalog.read, not catalog.author")
    void createRefusesACallerWithoutCatalogAuthor() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "menu-create-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Main menu"}"""))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
        assertThat(menuCount()).isZero();
    }

    @Test
    @DisplayName("POST refuses a request with no Idempotency-Key header")
    void createRefusesAMissingIdempotencyKey() throws Exception {
        MvcResult attempt = mvc.perform(post(path())
                        .with(tokenFor(BRAND_AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Main menu"}"""))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("mutating = true on MenuController.create requires Idempotency-Key")
                .isEqualTo(400);
        assertThat(menuCount()).isZero();
    }

    @Test
    @DisplayName("POST creates a DRAFT menu with no membership")
    void createSucceeds() throws Exception {
        MvcResult result = mvc.perform(post(path())
                        .with(tokenFor(BRAND_AUTHOR))
                        .header("Idempotency-Key", "menu-create-200-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Main menu"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"name\":\"Main menu\"")
                .contains("\"status\":\"DRAFT\"");
        assertThat(menuCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------- copy

    @Test
    @DisplayName("POST .../copy over HTTP produces a new menu carrying the source's membership")
    void copyOverHttpCarriesMembership() throws Exception {
        UUID sourceId = createMenuDirectly("Source menu");
        addItemDirectly(sourceId, burgerVariantId, "AVAILABLE");

        MvcResult result = mvc.perform(post(path() + "/" + sourceId + "/copy")
                        .with(tokenFor(BRAND_AUTHOR))
                        .header("Idempotency-Key", "menu-copy-200-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Branch 2 menu"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"name\":\"Branch 2 menu\"");
        UUID copyId = jdbc.sql("SELECT id FROM catalog.menus WHERE tenant_id = :t AND name = 'Branch 2 menu'")
                .param("t", TENANT)
                .query(UUID.class)
                .single();
        assertThat(copyId).isNotEqualTo(sourceId);

        MvcResult items = mvc.perform(get(path() + "/" + copyId + "/items").with(tokenFor(READ_ONLY_STAFF)))
                .andReturn();
        assertThat(items.getResponse().getContentAsString()).contains(burgerVariantId.toString());
    }

    @Test
    @DisplayName("POST .../copy for an unknown source menu is a 404, not a 500")
    void copyOfUnknownMenuIs404() throws Exception {
        MvcResult attempt = mvc.perform(post(path() + "/" + UUID.randomUUID() + "/copy")
                        .with(tokenFor(BRAND_AUTHOR))
                        .header("Idempotency-Key", "menu-copy-404-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Copy of nothing"}"""))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------- bulk-add-by-filter

    @Test
    @DisplayName("POST .../items/bulk-add-by-filter over HTTP adds every matching variant in one call")
    void bulkAddByFilterOverHttp() throws Exception {
        UUID menuId = createMenuDirectly("Main menu");

        MvcResult result = mvc.perform(post(path() + "/" + menuId + "/items/bulk-add-by-filter")
                        .with(tokenFor(BRAND_AUTHOR))
                        .header("Idempotency-Key", "menu-bulk-add-200-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":"%s","search":null,"availabilityDefault":"AVAILABLE","locale":"uz"}""".formatted(hotCategoryId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"added\":1");

        MvcResult items = mvc.perform(get(path() + "/" + menuId + "/items").with(tokenFor(READ_ONLY_STAFF)))
                .andReturn();
        assertThat(items.getResponse().getContentAsString()).contains(burgerVariantId.toString());
    }

    @Test
    @DisplayName("POST .../items/bulk-add-by-filter refuses a caller holding only catalog.read")
    void bulkAddByFilterRefusesReadOnlyStaff() throws Exception {
        UUID menuId = createMenuDirectly("Main menu");

        MvcResult attempt = mvc.perform(post(path() + "/" + menuId + "/items/bulk-add-by-filter")
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "menu-bulk-add-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":null,"search":null,"availabilityDefault":"AVAILABLE","locale":"uz"}"""))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
    }

    // -------------------------------------------------------------------- bind

    @Test
    @DisplayName("PUT .../bindings/locations/{locationId} over HTTP binds a menu as the branch's default")
    void bindOverHttp() throws Exception {
        UUID menuId = createMenuDirectly("Main menu", "ACTIVE");

        MvcResult result = mvc.perform(put(path() + "/bindings/locations/" + LOCATION)
                        .with(tokenFor(BRANCH_AUTHOR))
                        .header("Idempotency-Key", "menu-bind-204-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuId":"%s","channelId":null}""".formatted(menuId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(boundMenuId(LOCATION)).isEqualTo(menuId);
    }

    @Test
    @DisplayName("PUT .../bindings/locations/{locationId} refuses a still-DRAFT menu")
    void bindOfADraftMenuIsRefused() throws Exception {
        UUID menuId = createMenuDirectly("Unfinished menu", "DRAFT");

        MvcResult attempt = mvc.perform(put(path() + "/bindings/locations/" + LOCATION)
                        .with(tokenFor(BRANCH_AUTHOR))
                        .header("Idempotency-Key", "menu-bind-422-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuId":"%s","channelId":null}""".formatted(menuId)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(422);
        assertThat(boundMenuId(LOCATION)).isNull();
    }

    @Test
    @DisplayName("PUT .../bindings/locations/{locationId} refuses a caller holding no catalog.author at all")
    void bindRefusesACallerWithoutCatalogAuthor() throws Exception {
        UUID menuId = createMenuDirectly("Main menu");

        // READ_ONLY_STAFF holds catalog.read only -- never catalog.author, at
        // any scope -- so ResourceScope.covers's "grants downwards" rule
        // (a BRAND grant would satisfy a LOCATION request within that brand)
        // is not what is under test here; the missing capability itself is.
        MvcResult attempt = mvc.perform(put(path() + "/bindings/locations/" + LOCATION)
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "menu-bind-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuId":"%s","channelId":null}""".formatted(menuId)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
        assertThat(boundMenuId(LOCATION)).isNull();
    }

    @Test
    @DisplayName("PUT .../bindings/locations/{locationId} for an unknown menu is a 404, not a raw constraint violation")
    void bindOfUnknownMenuIs404() throws Exception {
        MvcResult attempt = mvc.perform(put(path() + "/bindings/locations/" + LOCATION)
                        .with(tokenFor(BRANCH_AUTHOR))
                        .header("Idempotency-Key", "menu-bind-404-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuId":"%s","channelId":null}""".formatted(UUID.randomUUID())))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------------ fixture

    private static String path() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog/menus";
    }

    private UUID createMenuDirectly(String name) {
        return createMenuDirectly(name, "DRAFT");
    }

    /** bindOverHttp needs an ACTIVE menu -- bindToBranch refuses anything else. */
    private UUID createMenuDirectly(String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO catalog.menus (id, tenant_id, brand_id, name, status, version)
                        VALUES (:id, :t, :b, :name, :status, 1)
                        """)
                .param("id", id)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("name", name)
                .param("status", status)
                .update();
        return id;
    }

    private void addItemDirectly(UUID menuId, UUID variantId, String availability) {
        jdbc.sql("""
                        INSERT INTO catalog.menu_items (
                            id, tenant_id, brand_id, menu_id, variant_id, sort_order, availability_default, version)
                        VALUES (:id, :t, :b, :menuId, :variantId, 0, :availability, 1)
                        """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("menuId", menuId)
                .param("variantId", variantId)
                .param("availability", availability)
                .update();
    }

    private long menuCount() {
        return jdbc.sql("SELECT count(*) FROM catalog.menus WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }

    private @org.jspecify.annotations.Nullable UUID boundMenuId(UUID locationId) {
        return jdbc.sql("SELECT menu_id FROM catalog.branch_menu_bindings "
                        + "WHERE tenant_id = :t AND location_id = :loc AND channel_id IS NULL")
                .param("t", TENANT)
                .param("loc", locationId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'menu http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeType).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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
