package uz.horecaos.platform.pricing.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0136's per-variant price map, written over HTTP: a {@code COMBO_COMPONENT}
 * price is a price like the other three, set on a price book under {@code
 * pricing.author} and keyed to the pairing of a combo group with a variant.
 *
 * <p>The behaviours proved are the ones that make it a price and not a field: the
 * capability, tenant and brand isolation of the id it names, the close-and-open write
 * that gives a single current row, the row-level {@code If-Match}, and the fact that
 * the bulk route takes the new type without a line of code of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComboComponentPriceEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-6000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-6000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-6000-7000-8000-0000000000b2");

    private static final UUID BOOK = UUID.fromString("018f9f10-6000-7000-8000-0000000000e1");
    private static final UUID CONTAINER = UUID.fromString("018f9f10-6000-7000-8000-0000000000d1");
    private static final UUID DISH = UUID.fromString("018f9f10-6000-7000-8000-0000000000d2");
    private static final UUID COMPONENT = UUID.fromString("018f9f10-6000-7000-8000-0000000000f1");
    private static final UUID ARCHIVED_COMPONENT = UUID.fromString("018f9f10-6000-7000-8000-0000000000f2");
    private static final UUID FOREIGN_COMPONENT = UUID.fromString("018f9f10-6000-7000-8000-0000000000f3");

    private static final String OWNER = "combo-price-owner";
    private static final String READER = "combo-price-reader";
    private static final String OTHER_OWNER = "combo-price-other-owner";

    private static final String PRICING = "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/pricing";

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
        jdbc.sql("TRUNCATE TABLE pricing.prices, pricing.price_books CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.combo_components, catalog.combo_groups, catalog.variants, "
                        + "catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertFixtures();
        grant(OWNER, TENANT, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(READER, TENANT, PlatformRole.COURIER_DISPATCHER, "BRAND", BRAND);
        grant(OTHER_OWNER, OTHER_TENANT, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT);
    }

    @Test
    void settingAComponentPriceNeedsPricingAuthor() throws Exception {
        MvcResult refused = mvc.perform(put(path(COMPONENT))
                        .with(tokenFor(READER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":3000}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.PRICING_AUTHOR.code());
        assertThat(priceRows()).isZero();
    }

    @Test
    void aComponentPriceIsWrittenKeyedToThePairingAndNotTheVariant() throws Exception {
        MvcResult set = mvc.perform(put(path(COMPONENT))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "set-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":3000}"))
                .andReturn();

        assertThat(set.getResponse().getStatus())
                .as(set.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(set.getResponse().getHeader("ETag"))
                .as("the book's new version")
                .isNotNull();
        assertThat(jdbc.sql("""
                                SELECT priceable_type || ':' || priceable_id || ':' || amount_minor
                                FROM pricing.prices WHERE valid_until IS NULL
                                """).query(String.class).single()).isEqualTo("COMBO_COMPONENT:" + COMPONENT + ":3000");
    }

    @Test
    void zeroIsAPriceAndNegativeIsNot() throws Exception {
        MvcResult free = mvc.perform(put(path(COMPONENT))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "free")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":0}"))
                .andReturn();
        assertThat(free.getResponse().getStatus())
                .as("free with the family box")
                .isEqualTo(200);

        MvcResult negative = mvc.perform(put(path(COMPONENT))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "negative")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":-1}"))
                .andReturn();
        assertThat(negative.getResponse().getStatus())
                .as("a discount is not a price")
                .isEqualTo(400);
    }

    @Test
    void aSecondWriteClosesTheFirstSoThereIsOneCurrentPrice() throws Exception {
        putPrice(COMPONENT, 3000, "first");
        putPrice(COMPONENT, 3500, "second");

        assertThat(jdbc.sql("SELECT count(*) FROM pricing.prices WHERE valid_until IS NULL")
                        .query(Long.class)
                        .single())
                .as("ux_price_current: one current price per thing per book")
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT amount_minor FROM pricing.prices WHERE valid_until IS NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(3500L);
        assertThat(priceRows()).as("the first stays as history").isEqualTo(2L);
    }

    @Test
    void anUnknownComponentIsNotFoundAndWritesNothing() throws Exception {
        MvcResult missing = putPrice(UUID.fromString("018f9f10-6000-7000-8000-0000000000ff"), 3000, "unknown");

        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(priceRows()).isZero();
    }

    @Test
    void anArchivedComponentCannotBePriced() throws Exception {
        MvcResult archived = putPrice(ARCHIVED_COMPONENT, 3000, "archived");

        assertThat(archived.getResponse().getStatus())
                .as("pricing something withdrawn is always a mistake")
                .isEqualTo(404);
    }

    @Test
    void aComponentOfAnotherBrandCannotBePriced() throws Exception {
        MvcResult foreign = putPrice(FOREIGN_COMPONENT, 3000, "foreign");

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(priceRows()).isZero();
    }

    @Test
    void anotherTenantsOwnerCannotPriceThisBrandsComponent() throws Exception {
        MvcResult intruder = mvc.perform(put(path(COMPONENT))
                        .with(tokenFor(OTHER_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "intruder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1}"))
                .andReturn();

        assertThat(intruder.getResponse().getStatus()).isEqualTo(403);
        assertThat(priceRows()).isZero();
    }

    @Test
    void theRowLevelVersionGuardsAComponentPrice() throws Exception {
        MvcResult firstWrite = mvc.perform(put(path(COMPONENT))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "v0")
                        .header("If-Match", "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":3000}"))
                .andReturn();
        assertThat(firstWrite.getResponse().getStatus())
                .as("0 is the version of a component not yet priced in this book")
                .isEqualTo(200);

        MvcResult stale = mvc.perform(put(path(COMPONENT))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "v0-again")
                        .header("If-Match", "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":4000}"))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(jdbc.sql("SELECT amount_minor FROM pricing.prices WHERE valid_until IS NULL")
                        .query(Long.class)
                        .single())
                .as("a stale write changed nothing")
                .isEqualTo(3000L);
    }

    @Test
    void aWriteWithoutAnIdempotencyKeyIsRefused() throws Exception {
        MvcResult keyless = mvc.perform(put(path(COMPONENT))
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":3000}"))
                .andReturn();

        assertThat(keyless.getResponse().getStatus()).isEqualTo(400);
        assertThat(keyless.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void theBulkRouteTakesTheNewTypeWithNoCodeOfItsOwn() throws Exception {
        MvcResult bulk = mvc.perform(post(PRICING + "/price-books/" + BOOK + "/prices/bulk-apply")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bulk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dryRun":false,"items":[
                                  {"priceableType":"COMBO_COMPONENT","priceableId":"%s","amountMinor":2500},
                                  {"priceableType":"COMBO_COMPONENT","priceableId":"%s","amountMinor":2500}]}
                                """.formatted(COMPONENT, FOREIGN_COMPONENT)))
                .andReturn();

        assertThat(bulk.getResponse().getStatus())
                .as(bulk.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(bulk.getResponse().getContentAsString())
                .contains("\"appliedCount\":1")
                .contains("\"failedCount\":1")
                .contains("UNKNOWN_PRICEABLE");
    }

    // ---------------------------------------------------------------- fixtures

    private MvcResult putPrice(UUID component, long amount, String key) throws Exception {
        return mvc.perform(put(path(component))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":" + amount + "}"))
                .andReturn();
    }

    private static String path(UUID component) {
        return PRICING + "/price-books/" + BOOK + "/combo-component-prices/" + component;
    }

    private long priceRows() {
        return jdbc.sql("SELECT count(*) FROM pricing.prices").query(Long.class).single();
    }

    private void insertFixtures() {
        for (UUID[] pair : new UUID[][] {{TENANT, BRAND}, {OTHER_TENANT, OTHER_BRAND}}) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", pair[0])
                    .param("slug", "combo-price-" + pair[0].toString().substring(30))
                    .update();
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                    """).param("id", pair[1]).param("tenantId", pair[0]).update();
        }
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status, valid_from)
                VALUES (:id, :tenantId, :brandId, 'Menu', 'UZS', 'DRAFT', :from)
                """)
                .param("id", BOOK)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", Instant.now().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();

        UUID containerProduct = insertProduct("LUNCH", TENANT, BRAND);
        insertVariant(CONTAINER, containerProduct, TENANT, BRAND);
        UUID dishProduct = insertProduct("DISH", TENANT, BRAND);
        insertVariant(DISH, dishProduct, TENANT, BRAND);
        UUID group = insertGroup(CONTAINER, TENANT, BRAND);
        insertComponent(COMPONENT, group, DISH, "ACTIVE", TENANT, BRAND);
        insertComponent(ARCHIVED_COMPONENT, group, insertSecondDish(), "ARCHIVED", TENANT, BRAND);

        UUID foreignContainerProduct = insertProduct("F-LUNCH", OTHER_TENANT, OTHER_BRAND);
        UUID foreignContainer = UUID.randomUUID();
        insertVariant(foreignContainer, foreignContainerProduct, OTHER_TENANT, OTHER_BRAND);
        UUID foreignDishProduct = insertProduct("F-DISH", OTHER_TENANT, OTHER_BRAND);
        UUID foreignDish = UUID.randomUUID();
        insertVariant(foreignDish, foreignDishProduct, OTHER_TENANT, OTHER_BRAND);
        UUID foreignGroup = insertGroup(foreignContainer, OTHER_TENANT, OTHER_BRAND);
        insertComponent(FOREIGN_COMPONENT, foreignGroup, foreignDish, "ACTIVE", OTHER_TENANT, OTHER_BRAND);
    }

    private UUID insertSecondDish() {
        UUID product = insertProduct("DISH-2", TENANT, BRAND);
        UUID variant = UUID.randomUUID();
        insertVariant(variant, product, TENANT, BRAND);
        return variant;
    }

    private UUID insertProduct(String code, UUID tenantId, UUID brandId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        return id;
    }

    private void insertVariant(UUID id, UUID productId, UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
    }

    private UUID insertGroup(UUID container, UUID tenantId, UUID brandId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.combo_groups (id, tenant_id, brand_id, container_variant_id, code)
                VALUES (:id, :tenantId, :brandId, :container, 'MAIN')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("container", container)
                .update();
        return id;
    }

    private void insertComponent(UUID id, UUID group, UUID variant, String status, UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO catalog.combo_components (id, tenant_id, brand_id, combo_group_id,
                    component_variant_id, status)
                VALUES (:id, :tenantId, :brandId, :group, :variant, :status)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("group", group)
                .param("variant", variant)
                .param("status", status)
                .update();
    }

    private void grant(String subject, UUID tenantId, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'combo price endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
