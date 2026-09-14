package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Fix4 kitchen-catalog finding P16/P47/P45: the wave's eight-plus new
 * endpoints (stop-list counts, per-item sale schedule, cross-sell
 * recommendations, channel-exclusion writes/reads) had zero HTTP-level test
 * coverage — every test written for the new backend logic
 * (ChannelOfferingExclusionWriteTests, ItemSaleWindowAuthoringTests,
 * ProductRecommendationTests) calls {@link CatalogAuthoringService} directly
 * and never goes through {@code @RequiresCapability}. This suite is that
 * missing HTTP layer, mirroring {@code GrantControllerEndpointTests}/{@code
 * OperationsFiscalTerminalControllerEndpointTests}'s own pattern: a caller
 * with no grant at all is refused 403/INSUFFICIENT_CAPABILITY naming the
 * declared capability, and a caller with the real grant reaches the real
 * logic (200/204), so a future refactor that drops or mis-scopes one of these
 * annotations fails a test rather than shipping unnoticed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogAuthoringControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-3000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-3000-7000-8000-0000000000c1");
    private static final UUID PRODUCT = UUID.fromString("018f9f10-3000-7000-8000-0000000000d1");
    private static final UUID VARIANT = UUID.fromString("018f9f10-3000-7000-8000-0000000000d2");
    private static final UUID TARGET_PRODUCT = UUID.fromString("018f9f10-3000-7000-8000-0000000000e1");
    private static final UUID TARGET_VARIANT = UUID.fromString("018f9f10-3000-7000-8000-0000000000e2");
    private static final UUID CHANNEL = UUID.fromString("018f9f10-3000-7000-8000-0000000000f1");

    private static final String OWNER = "catalog-endpoint-owner";
    private static final String NO_GRANT = "catalog-endpoint-no-grant";

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
        // catalog.products/variants carry a bare tenant_id column, not a
        // foreign key to tenant.tenants (V0016 never added one), so they
        // survive a `TRUNCATE tenant.tenants CASCADE` on their own — the same
        // reason ChannelOfferingExclusionWriteTests/ProductRecommendationTests
        // truncate this schema explicitly rather than relying on cascade.
        jdbc.sql("TRUNCATE TABLE catalog.product_recommendations, catalog.channel_offering_exclusions, "
                        + "catalog.item_sale_windows, catalog.location_offerings, catalog.translations, "
                        + "catalog.variants, catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertFixtures();
        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    // ----------------------------------------------------------- row 2.5: stop-list counts

    @Test
    void availabilityCountsRefusedWithoutInventoryRead() throws Exception {
        assertRefused(
                get(catalogPath() + "/locations/" + LOCATION + "/variants/availability-counts")
                        .with(tokenFor(NO_GRANT)),
                Capability.INVENTORY_READ);
    }

    @Test
    void availabilityCountsAllowedWithInventoryRead() throws Exception {
        MvcResult result = mvc.perform(get(catalogPath() + "/locations/" + LOCATION + "/variants/availability-counts")
                        .with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"total\"");
    }

    // ----------------------------------------------------------- row 4.2g: sale schedule

    @Test
    void saleScheduleGetRefusedWithoutCatalogRead() throws Exception {
        assertRefused(get(saleSchedulePath()).with(tokenFor(NO_GRANT)), Capability.CATALOG_READ);
    }

    @Test
    void saleScheduleReplaceRefusedWithoutCatalogAuthor() throws Exception {
        assertRefused(
                put(saleSchedulePath())
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"windows":[]}
                                """),
                Capability.CATALOG_AUTHOR);
    }

    @Test
    void saleScheduleRoundTripsWithTheRealGrant() throws Exception {
        MvcResult replaced = mvc.perform(put(saleSchedulePath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "sale-schedule-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"windows":[{"dayOfWeek":1,"opensAt":"06:00:00","closesAt":"11:00:00"}]}
                                """))
                .andReturn();
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(replaced.getResponse().getContentAsString()).contains("\"dayOfWeek\":1");

        MvcResult read =
                mvc.perform(get(saleSchedulePath()).with(tokenFor(OWNER))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(read.getResponse().getContentAsString()).contains("\"dayOfWeek\":1");
    }

    // ----------------------------------------------------------- row 4.2h: recommendations

    @Test
    void recommendationsListRefusedWithoutCatalogRead() throws Exception {
        assertRefused(get(recommendationsPath()).with(tokenFor(NO_GRANT)), Capability.CATALOG_READ);
    }

    @Test
    void attachRecommendationRefusedWithoutCatalogAuthor() throws Exception {
        assertRefused(
                post(recommendationsPath())
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetVariantId":"%s","sortOrder":0}
                                """.formatted(TARGET_VARIANT)),
                Capability.CATALOG_AUTHOR);
    }

    @Test
    void detachRecommendationRefusedWithoutCatalogAuthor() throws Exception {
        assertRefused(
                delete(recommendationsPath() + "/" + TARGET_VARIANT).with(tokenFor(NO_GRANT)),
                Capability.CATALOG_AUTHOR);
    }

    @Test
    void effectiveRecommendationsRefusedWithoutCatalogRead() throws Exception {
        assertRefused(
                get(recommendationsPath() + "/effective")
                        .with(tokenFor(NO_GRANT))
                        .queryParam("locationId", LOCATION.toString()),
                Capability.CATALOG_READ);
    }

    @Test
    void attachRecommendationRoundTripsWithTheRealGrant() throws Exception {
        MvcResult attached = mvc.perform(post(recommendationsPath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "attach-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetVariantId":"%s","sortOrder":0}
                                """.formatted(TARGET_VARIANT)))
                .andReturn();
        assertThat(attached.getResponse().getStatus()).isEqualTo(200);

        MvcResult listed =
                mvc.perform(get(recommendationsPath()).with(tokenFor(OWNER))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString()).contains(TARGET_VARIANT.toString());

        assertThat(jdbc.sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'catalog.recommendation.attached'")
                        .query(Long.class)
                        .single())
                .as("ADR 0027: attaching a recommendation is audited")
                .isEqualTo(1L);
    }

    // ----------------------------------------------------------- row 4.4b: channel exclusions

    @Test
    void setChannelOfferingRefusedWithoutCatalogAuthor() throws Exception {
        assertRefused(
                put(exclusionsPath() + "/variants/" + VARIANT)
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"offered":false,"reasonCode":"SEASONAL"}
                                """),
                Capability.CATALOG_AUTHOR);
    }

    @Test
    void bulkSetChannelOfferingRefusedWithoutCatalogAuthor() throws Exception {
        assertRefused(
                post(exclusionsPath() + "/bulk")
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s"],"offered":false,"reasonCode":"SEASONAL"}
                                """.formatted(VARIANT)),
                Capability.CATALOG_AUTHOR);
    }

    @Test
    void channelExclusionsGetRefusedWithoutCatalogRead() throws Exception {
        assertRefused(
                get(exclusionsPath()).with(tokenFor(NO_GRANT)).queryParam("locationId", LOCATION.toString()),
                Capability.CATALOG_READ);
    }

    @Test
    void setChannelOfferingRoundTripsWithTheRealGrant() throws Exception {
        MvcResult excluded = mvc.perform(put(exclusionsPath() + "/variants/" + VARIANT)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "exclude-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"offered":false,"reasonCode":"SEASONAL"}
                                """))
                .andReturn();
        assertThat(excluded.getResponse().getStatus()).isEqualTo(204);

        MvcResult read = mvc.perform(
                        get(exclusionsPath()).with(tokenFor(OWNER)).queryParam("locationId", LOCATION.toString()))
                .andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(read.getResponse().getContentAsString()).contains(VARIANT.toString());
    }

    // ----------------------------------------------------------------- fixtures

    private static String catalogPath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog";
    }

    private static String saleSchedulePath() {
        return catalogPath() + "/variants/" + VARIANT + "/location-offerings/" + LOCATION + "/sale-schedule";
    }

    private static String recommendationsPath() {
        return catalogPath() + "/products/" + PRODUCT + "/recommendations";
    }

    private static String exclusionsPath() {
        return catalogPath() + "/channels/" + CHANNEL + "/exclusions";
    }

    private void assertRefused(MockHttpServletRequestBuilder request, Capability expected) throws Exception {
        MvcResult refused = mvc.perform(request).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(expected.code());
    }

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'catalog-authoring-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'UZUM_TEZKOR', 'AGGREGATOR', 'Uzum Tezkor', 'ACTIVE')
                """).param("id", CHANNEL).param("tenantId", TENANT).update();

        insertProductAndVariant(PRODUCT, VARIANT, "BURGER");
        insertProductAndVariant(TARGET_PRODUCT, TARGET_VARIANT, "FRIES");
    }

    private void insertProductAndVariant(UUID productId, UUID variantId, String code) {
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'catalog authoring endpoint test', :validFrom)
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
