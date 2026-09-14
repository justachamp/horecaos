package uz.horecaos.platform.marketing.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * T18: {@code AttributionLinkController} and {@code CourierBroadcastController}
 * had no HTTP-level test at all before this fix — the only file exercising
 * either, {@code CourierBroadcastAndAttributionLinkTests}, instantiates the
 * application services directly and never goes through MockMvc, the
 * capability filter, or either controller class. That left both untested at
 * exactly the two properties an HTTP caller actually depends on: refusal
 * without the endpoint's own capability, and archive/click/send's inline
 * {@code link.brandId().equals(brandId)} / {@code
 * broadcast.brandId().equals(brandId)} check — the only guard standing
 * between a caller holding a grant on their own brand and a sibling brand's
 * link or broadcast, given only a leaked or guessed id. Mirrors {@code
 * ServiceScheduleControllerEndpointTests}'s own pattern.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttributionLinkAndCourierBroadcastControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-c000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-c000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-c000-7000-8000-0000000000a3");

    // FULL is granted at BOTH brands, deliberately: a cross-brand test must
    // fail at the controller's own inline check, not at the capability filter
    // upstream of it, or it would prove nothing about the guard under test.
    //
    // UUID-shaped, not a slug: both controllers' own actorId() parses the JWT
    // subject as a UUID for mint/archive/draft/send's createdBy/actorId, the
    // same reason OperationsMarketingCrudEndpointTests' OWNER/ADMINISTRATOR
    // are UUID-shaped rather than plain names.
    private static final String FULL = "018f9b20-c000-7000-8000-0000000000a4";
    private static final String UNGRANTED = "018f9b20-c000-7000-8000-0000000000a5";

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
        jdbc.sql("TRUNCATE TABLE marketing.courier_broadcasts, marketing.attribution_links CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        insertFixtures();
        roleRegistry.synchronize();
        // BRAND_MANAGER carries MARKETING_LINK_MANAGE, COURIER_DISPATCHER
        // carries COURIER_BROADCAST_MANAGE — the real roles each endpoint is
        // built for, granted at both brands so the brand check under test is
        // the one thing standing between FULL and a sibling brand's row.
        grant(FULL, PlatformRole.BRAND_MANAGER, BRAND);
        grant(FULL, PlatformRole.BRAND_MANAGER, OTHER_BRAND);
        grant(FULL, PlatformRole.COURIER_DISPATCHER, BRAND);
        grant(FULL, PlatformRole.COURIER_DISPATCHER, OTHER_BRAND);
        // UNGRANTED gets nothing at all.
    }

    // ------------------------------------------------------------ attribution links

    @Test
    void mintIsRefusedWithoutMarketingLinkManage() throws Exception {
        MvcResult result = mvc.perform(post(attributionLinksPath(BRAND))
                        .with(tokenFor(UNGRANTED))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "mint-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Summer\",\"channel\":\"WEB\",\"destinationType\":\"STOREFRONT_HOME\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.MARKETING_LINK_MANAGE.code());
    }

    @Test
    void archiveRefusesALinkBelongingToAnotherBrand() throws Exception {
        UUID linkId = insertAttributionLink(BRAND);

        MvcResult result = mvc.perform(post(attributionLinksPath(OTHER_BRAND) + "/" + linkId + "/archives")
                        .with(tokenFor(FULL))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-cross-brand-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("FULL holds MARKETING_LINK_MANAGE on OTHER_BRAND too, so only the controller's "
                        + "own brandId check can be refusing this")
                .isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    void clickRefusesALinkBelongingToAnotherBrand() throws Exception {
        UUID linkId = insertAttributionLink(BRAND);

        MvcResult result = mvc.perform(post(attributionLinksPath(OTHER_BRAND) + "/" + linkId + "/clicks")
                        .with(tokenFor(FULL))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "click-cross-brand-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    void archiveOfThisBrandsOwnLinkSucceeds() throws Exception {
        UUID linkId = insertAttributionLink(BRAND);

        MvcResult result = mvc.perform(post(attributionLinksPath(BRAND) + "/" + linkId + "/archives")
                        .with(tokenFor(FULL))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-own-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
    }

    // ------------------------------------------------------------ courier broadcasts

    @Test
    void draftIsRefusedWithoutCourierBroadcastManage() throws Exception {
        MvcResult result = mvc.perform(post(courierBroadcastsPath(BRAND))
                        .with(tokenFor(UNGRANTED))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "draft-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetKind\":\"ALL_ACTIVE\",\"message\":\"Shift change\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COURIER_BROADCAST_MANAGE.code());
    }

    @Test
    void sendRefusesABroadcastBelongingToAnotherBrand() throws Exception {
        UUID broadcastId = insertCourierBroadcast(BRAND);

        MvcResult result = mvc.perform(post(courierBroadcastsPath(OTHER_BRAND) + "/" + broadcastId + "/sends")
                        .with(tokenFor(FULL))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "send-cross-brand-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("FULL holds COURIER_BROADCAST_MANAGE on OTHER_BRAND too, so only the "
                        + "controller's own brandId check can be refusing this")
                .isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------- fixtures

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'attribution-broadcast-endpoint', 'Legal', 'Display',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        insertBrand(BRAND, "MAIN");
        insertBrand(OTHER_BRAND, "OTHER");
    }

    private void insertBrand(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :displayName, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .param("displayName", code)
                .update();
    }

    private UUID insertAttributionLink(UUID brandId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.attribution_links (
                    id, tenant_id, brand_id, label, token, channel, destination_type,
                    status, valid_from, created_by)
                VALUES (:id, :tenantId, :brandId, 'Fixture link', :token, 'WEB', 'STOREFRONT_HOME',
                    'ACTIVE', now(), :createdBy)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("token", "tok" + id.toString().substring(0, 8))
                .param("createdBy", UUID.randomUUID())
                .update();
        return id;
    }

    private UUID insertCourierBroadcast(UUID brandId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.courier_broadcasts (
                    id, tenant_id, brand_id, target_kind, message, status, created_by)
                VALUES (:id, :tenantId, :brandId, 'ALL_ACTIVE', 'Fixture broadcast', 'DRAFT', :createdBy)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("createdBy", UUID.randomUUID())
                .update();
        return id;
    }

    private void grant(String subject, PlatformRole role, UUID brandId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'BRAND', :scopeId,
                        'ACTIVE', 'test-fixture', 'attribution/broadcast endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + brandId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeId", brandId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static String attributionLinksPath(UUID brandId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + brandId + "/marketing/attribution-links";
    }

    private static String courierBroadcastsPath(UUID brandId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + brandId + "/marketing/courier-broadcasts";
    }

    /** Same {@code platform-admin} bypass as {@code OperationsBrandControllerEndpointTests}; see that class's own doc. */
    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of("platform-admin")))));
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
