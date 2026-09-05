package uz.horecaos.platform.legal.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * ADR 0068's authoring surface, over HTTP (ADR 0025, ADR 0031).
 *
 * <p>Mirrors {@code LegalEntityControllerEndpointTests}'s shape for the same
 * reason: {@code TERMS_MANAGE} is held by {@code tenant-owner} alone among the
 * tenant bundles, on the same argument — the words a customer accepts before
 * ordering are a legal decision, not one a tenant administrator makes on the
 * owner's behalf.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsTermsControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a10-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9a10-3000-7000-8000-0000000000b1");

    private static final String OWNER = "terms-owner";
    private static final String ADMINISTRATOR = "terms-administrator";

    private static final String DOCUMENTS =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/terms-documents";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the terms endpoint test");
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
        // CASCADE reaches iam.grants and, through tenant.brands, legal.terms_versions.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenantAndBrand();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void aBrandThatNeverPublishedAnswersUnpublished() throws Exception {
        MvcResult current =
                mvc.perform(get(DOCUMENTS + "/current").with(tokenFor(OWNER))).andReturn();

        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        assertThat(current.getResponse().getContentAsString())
                .contains("\"published\":false")
                .contains("\"contentsByLocale\":{}");
    }

    @Test
    void anOwnerCanPublishAndReadBackWhatWasPublished() throws Exception {
        MvcResult published = mvc.perform(post(DOCUMENTS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "publish-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentsByLocale":{"en":"Our own terms of service."},"note":"First publish"}
                                """))
                .andReturn();

        assertThat(published.getResponse().getStatus()).isEqualTo(201);
        assertThat(published.getResponse().getHeader("Location")).contains(DOCUMENTS + "/1");
        assertThat(published.getResponse().getContentAsString())
                .contains("\"published\":true")
                .contains("\"version\":1")
                .contains("Our own terms of service.");

        MvcResult current =
                mvc.perform(get(DOCUMENTS + "/current").with(tokenFor(OWNER))).andReturn();
        assertThat(current.getResponse().getContentAsString()).contains("\"publishedBy\":\"" + OWNER + "\"");

        MvcResult history = mvc.perform(get(DOCUMENTS).with(tokenFor(OWNER))).andReturn();
        assertThat(history.getResponse().getContentAsString()).contains("\"version\":1");
    }

    @Test
    void publishingASecondVersionLeavesTheFirstReadableUnchanged() throws Exception {
        mvc.perform(post(DOCUMENTS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "publish-2a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contentsByLocale":{"en":"Version one."}}
                        """));

        mvc.perform(post(DOCUMENTS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "publish-2b")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contentsByLocale":{"en":"Version two, completely different."}}
                        """));

        MvcResult versionOne =
                mvc.perform(get(DOCUMENTS + "/1").with(tokenFor(OWNER))).andReturn();
        assertThat(versionOne.getResponse().getContentAsString())
                .as("re-reading version 1 after version 2 exists must still show version 1's own words")
                .contains("Version one.")
                .doesNotContain("completely different");
    }

    @Test
    void anAdministratorCannotPublish() throws Exception {
        MvcResult refused = mvc.perform(post(DOCUMENTS)
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "publish-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentsByLocale":{"en":"Should not land."}}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.TERMS_MANAGE.code());
        assertThat(versionCount()).isZero();
    }

    @Test
    void anAdministratorCannotReadThemEither() throws Exception {
        mvc.perform(post(DOCUMENTS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "publish-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contentsByLocale":{"en":"Not the administrator's to read."}}
                        """));

        MvcResult read = mvc.perform(get(DOCUMENTS + "/current").with(tokenFor(ADMINISTRATOR)))
                .andReturn();
        assertThat(read.getResponse().getStatus())
                .as("TERMS_READ, like TERMS_MANAGE, is granted to tenant-owner alone — see PlatformRole")
                .isEqualTo(403);
    }

    @Test
    void publishingWithNoLanguageIsRejected() throws Exception {
        MvcResult rejected = mvc.perform(post(DOCUMENTS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "publish-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentsByLocale":{}}
                                """))
                .andReturn();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(versionCount()).isZero();
    }

    @Test
    void anUnknownVersionIsNotFound() throws Exception {
        MvcResult result =
                mvc.perform(get(DOCUMENTS + "/7").with(tokenFor(OWNER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    private long versionCount() {
        return jdbc.sql("SELECT count(*) FROM legal.terms_versions WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
    }

    private void insertTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'terms-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'terms endpoint test')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
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
