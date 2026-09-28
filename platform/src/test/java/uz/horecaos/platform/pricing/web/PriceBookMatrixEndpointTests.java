package uz.horecaos.platform.pricing.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Gap map row {@code 4.8a}'s book matrix read: {@code GET
 * .../price-books/{id}/matrix} — before this a book's variant prices could
 * only be seen one product at a time, through {@code PUT
 * .../variant-prices/{id}} in a loop with no way to compare against what the
 * brand actually charges today. Over real HTTP, against a real database,
 * because tenant scoping, cursor pagination and the delta arithmetic are
 * exactly the kind of thing that is honest in a unit test and wrong in
 * production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class PriceBookMatrixEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000d1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000d2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000d3");

    private static final String OWNER = "matrix-owner";
    private static final String DISPATCHER = "matrix-dispatcher";

    private static final String PRICING = "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/pricing";
    private static final String OTHER_PRICING =
            "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/pricing";

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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE pricing.prices, pricing.price_book_assignments, pricing.price_books CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.category_products, catalog.categories, catalog.translations, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenantAndBrands();
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER, "BRAND", BRAND);
    }

    @Test
    void readingWithoutPricingReadIsRefused() throws Exception {
        UUID book = draftBook(BRAND);

        MvcResult refused = mvc.perform(
                        get(PRICING + "/price-books/" + book + "/matrix").with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains(Capability.PRICING_READ.code());
    }

    @Test
    void everyVariantAppearsEvenWithNoPriceInThisBookYet() throws Exception {
        UUID category = category(BRAND, "MAINS");
        UUID variant = product(BRAND, category, "BURGER", "Burger");

        MvcResult result = mvc.perform(get(PRICING + "/price-books/" + draftBook(BRAND) + "/matrix")
                        .with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode row = items(result).get(0);
        assertThat(row.path("variantId").asString()).isEqualTo(variant.toString());
        assertThat(row.path("bookPriceMinor").isNull()).isTrue();
        assertThat(row.path("basePriceMinor").isNull()).isTrue();
        assertThat(row.path("deltaMinor").isNull()).isTrue();
        assertThat(row.path("bookPriceVersion").asLong()).isZero();
        assertThat(row.path("categoryId").asString()).isEqualTo(category.toString());
    }

    @Test
    void deltaIsTheBookPriceMinusTheBrandsLiveBasePriceInMinorUnits() throws Exception {
        UUID variant = product(BRAND, null, "BURGER", "Burger");
        UUID baseBook = liveBrandBook(BRAND, variant, 50_000L);
        UUID draft = draftBook(BRAND);
        setPrice(draft, variant, 55_000L, null);

        MvcResult result = mvc.perform(
                        get(PRICING + "/price-books/" + draft + "/matrix").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode row = items(result).get(0);
        assertThat(row.path("bookPriceMinor").asLong()).isEqualTo(55_000L);
        assertThat(row.path("basePriceMinor").asLong()).isEqualTo(50_000L);
        assertThat(row.path("deltaMinor").asLong()).isEqualTo(5_000L);
        assertThat(row.path("currency").asString()).isEqualTo("UZS");
        assertThat(baseBook).isNotNull();
    }

    @Test
    void differsFromBaseKeepsOnlyRowsThatDoNotMatch() throws Exception {
        UUID same = product(BRAND, null, "SAME", "Same price");
        UUID different = product(BRAND, null, "DIFF", "Different price");
        UUID unpriced = product(BRAND, null, "UNPRICED", "Only in base");

        liveBrandBook(BRAND, same, 10_000L);
        UUID draft = draftBook(BRAND);
        setPrice(draft, same, 10_000L, null);
        setPrice(draft, different, 12_000L, null);
        // `unpriced` is never set in `draft`, only implicitly absent — the base
        // book above only prices `same`, so this row has neither a book price
        // nor a base price and must not count as "differs".

        MvcResult result = mvc.perform(get(PRICING + "/price-books/" + draft + "/matrix")
                        .param("differsFromBase", "true")
                        .with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(variantIds(result)).containsExactly(different.toString());
        assertThat(unpriced).isNotNull();
    }

    @Test
    void categoryFilterNarrowsToProductsCarryingThatCategory() throws Exception {
        UUID mains = category(BRAND, "MAINS");
        UUID drinks = category(BRAND, "DRINKS");
        UUID burger = product(BRAND, mains, "BURGER", "Burger");
        product(BRAND, drinks, "COLA", "Cola");

        MvcResult result = mvc.perform(get(PRICING + "/price-books/" + draftBook(BRAND) + "/matrix")
                        .param("categoryId", mains.toString())
                        .with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(variantIds(result)).containsExactly(burger.toString());
    }

    @Test
    void pagesByCursorWithoutRepeatingOrDroppingRows() throws Exception {
        UUID book = draftBook(BRAND);
        Set<String> seeded = Set.of(
                product(BRAND, null, "A", "A").toString(),
                product(BRAND, null, "B", "B").toString(),
                product(BRAND, null, "C", "C").toString());

        MvcResult first = mvc.perform(get(PRICING + "/price-books/" + book + "/matrix")
                        .param("limit", "1")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstJson = json(first);
        List<String> firstPage = variantIds(firstJson);
        String cursor = firstJson.path("nextCursor").asString(null);
        assertThat(firstPage).hasSize(1);
        assertThat(cursor).isNotNull();

        MvcResult rest = mvc.perform(get(PRICING + "/price-books/" + book + "/matrix")
                        .param("limit", "10")
                        .param("cursor", cursor)
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(rest.getResponse().getStatus()).isEqualTo(200);
        JsonNode restJson = json(rest);
        List<String> restPage = variantIds(restJson);
        assertThat(restJson.path("nextCursor").isNull()).isTrue();

        assertThat(restPage).doesNotContainAnyElementsOf(firstPage);
        Set<String> combined = new HashSet<>(firstPage);
        combined.addAll(restPage);
        assertThat(combined).isEqualTo(seeded);
    }

    @Test
    void aVariantFromAnotherBrandNeverAppears() throws Exception {
        product(BRAND, null, "MINE", "Mine");
        product(OTHER_BRAND, null, "THEIRS", "Theirs");

        MvcResult result = mvc.perform(get(PRICING + "/price-books/" + draftBook(BRAND) + "/matrix")
                        .with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> names = StreamSupport.stream(items(result).spliterator(), false)
                .map(row -> row.path("displayName").asString())
                .toList();
        assertThat(names).containsExactly("Mine");
    }

    @Test
    void aBooksMatrixIsUnreachableFromAnotherBrandsPricingPath() throws Exception {
        UUID book = draftBook(BRAND);

        MvcResult result = mvc.perform(
                        get(OTHER_PRICING + "/price-books/" + book + "/matrix").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // -------------------------------------------------------- inline edit / If-Match

    @Test
    void theInlineEditRejectsAStaleIfMatchOnASpecificRow() throws Exception {
        UUID variant = product(BRAND, null, "BURGER", "Burger");
        UUID book = draftBook(BRAND);
        setPrice(book, variant, 40_000L, null);

        MvcResult stale = mvc.perform(put(PRICING + "/price-books/" + book + "/variant-prices/" + variant)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "matrix-edit-stale")
                        .header("If-Match", "W/\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":45000}
                                """))
                .andReturn();

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
    }

    @Test
    void theInlineEditAcceptsZeroAsIfMatchForAVariantNeverPricedInThisBook() throws Exception {
        UUID variant = product(BRAND, null, "BURGER", "Burger");
        UUID book = draftBook(BRAND);

        MvcResult created = mvc.perform(put(PRICING + "/price-books/" + book + "/variant-prices/" + variant)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "matrix-edit-first")
                        .header("If-Match", "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":45000}
                                """))
                .andReturn();

        assertThat(created.getResponse().getStatus()).isEqualTo(200);

        MvcResult matrix = mvc.perform(
                        get(PRICING + "/price-books/" + book + "/matrix").with(tokenFor(OWNER)))
                .andReturn();
        JsonNode row = items(matrix).get(0);
        assertThat(row.path("bookPriceMinor").asLong()).isEqualTo(45_000L);
        assertThat(row.path("bookPriceVersion").asLong()).isEqualTo(1L);
    }

    @Test
    void aSecondEditCanUseTheVersionTheFirstEditLeftBehind() throws Exception {
        UUID variant = product(BRAND, null, "BURGER", "Burger");
        UUID book = draftBook(BRAND);

        mvc.perform(put(PRICING + "/price-books/" + book + "/variant-prices/" + variant)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "matrix-edit-seq-1")
                .header("If-Match", "W/\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amountMinor":45000}
                        """));

        MvcResult afterFirst = mvc.perform(
                        get(PRICING + "/price-books/" + book + "/matrix").with(tokenFor(OWNER)))
                .andReturn();
        long versionAfterFirst =
                items(afterFirst).get(0).path("bookPriceVersion").asLong();

        // A second operator still holding version 0 (read before the first
        // edit landed) is rejected — proving the row-level check survives a
        // real edit rather than resetting to a value every later editor's
        // stale read would also satisfy.
        MvcResult staleSecond = mvc.perform(put(PRICING + "/price-books/" + book + "/variant-prices/" + variant)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "matrix-edit-seq-stale")
                        .header("If-Match", "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":46000}
                                """))
                .andReturn();
        assertThat(staleSecond.getResponse().getStatus()).isEqualTo(409);

        // The version the matrix actually showed after the first edit is
        // accepted.
        MvcResult secondEdit = mvc.perform(put(PRICING + "/price-books/" + book + "/variant-prices/" + variant)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "matrix-edit-seq-2")
                        .header("If-Match", "W/\"" + versionAfterFirst + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":47000}
                                """))
                .andReturn();
        assertThat(secondEdit.getResponse().getStatus()).isEqualTo(200);

        MvcResult afterSecond = mvc.perform(
                        get(PRICING + "/price-books/" + book + "/matrix").with(tokenFor(OWNER)))
                .andReturn();
        JsonNode row = items(afterSecond).get(0);
        assertThat(row.path("bookPriceMinor").asLong()).isEqualTo(47_000L);
        assertThat(row.path("bookPriceVersion").asLong()).isGreaterThan(versionAfterFirst);
    }

    @Test
    void theInlineEditWithNoIfMatchStillWritesUnconditionally() throws Exception {
        UUID variant = product(BRAND, null, "BURGER", "Burger");
        UUID book = draftBook(BRAND);
        setPrice(book, variant, 40_000L, null);

        MvcResult overwritten = mvc.perform(put(PRICING + "/price-books/" + book + "/variant-prices/" + variant)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "matrix-edit-unconditional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":48000}
                                """))
                .andReturn();

        assertThat(overwritten.getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ response reading

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode items(MvcResult result) throws Exception {
        return json(result).path("items");
    }

    private static List<String> variantIds(MvcResult result) throws Exception {
        return variantIds(json(result));
    }

    private static List<String> variantIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.path("items").forEach(row -> ids.add(row.path("variantId").asString()));
        return ids;
    }

    // ------------------------------------------------------------------ fixtures

    private UUID draftBook(UUID brandId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status,
                    valid_from, priority, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, 'Draft book', 'UZS', 'DRAFT', now(), 0, 1, now(), now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .update();
        return id;
    }

    /** An ACTIVE book with a live BRAND-scope assignment, pricing one variant — the matrix's own "base price". */
    private UUID liveBrandBook(UUID brandId, UUID variantId, long amountMinor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status,
                    valid_from, priority, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, 'Base book', 'UZS', 'ACTIVE', now(), 0, 1, now(), now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id,
                    scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :bookId, 'BRAND', NULL, now(), 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("bookId", id)
                .update();
        setPrice(id, variantId, amountMinor, brandId);
        return id;
    }

    private void setPrice(UUID bookId, UUID variantId, long amountMinor, @Nullable UUID brandIdOrNull) {
        UUID brandId = brandIdOrNull != null ? brandIdOrNull : BRAND;
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from, version)
                VALUES (:id, :tenantId, :brandId, :bookId, 'VARIANT', :variantId, :amount, now(), 1)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("bookId", bookId)
                .param("variantId", variantId)
                .param("amount", amountMinor)
                .update();
    }

    private UUID category(UUID brandId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.categories (id, tenant_id, brand_id, catalog_id, code, status, version)
                SELECT :id, :tenantId, :brandId, c.id, :code, 'ACTIVE', 1
                FROM catalog.catalogs c WHERE c.tenant_id = :tenantId AND c.brand_id = :brandId
                LIMIT 1
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        return id;
    }

    private UUID product(UUID brandId, @Nullable UUID categoryId, String code, String name) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("sku", "SKU-" + brandId + "-" + code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', :name)
                """)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("name", name)
                .update();
        if (categoryId != null) {
            jdbc.sql("""
                    INSERT INTO catalog.category_products (tenant_id, brand_id, category_id, product_id)
                    VALUES (:tenantId, :brandId, :categoryId, :productId)
                    """)
                    .param("tenantId", TENANT)
                    .param("brandId", brandId)
                    .param("categoryId", categoryId)
                    .param("productId", productId)
                    .update();
        }
        return variantId;
    }

    private void insertTenantAndBrands() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'matrix-endpoint', 'Matrix', 'Matrix', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Other brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        UUID otherCatalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", otherCatalogId)
                .param("tenantId", TENANT)
                .param("brandId", OTHER_BRAND)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'price book matrix endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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
