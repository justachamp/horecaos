package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Row 10.5's static pages, over HTTP — the publish side. Mirrors {@code
 * OperationsTermsControllerEndpointTests}'s shape and, like {@code
 * ChannelSetupControllerEndpointTests}, needs no brand fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChannelPagesControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a21-3000-7000-8000-0000000000a1");
    private static final UUID CHANNEL = UUID.fromString("018f9a21-3000-7000-8000-0000000000b1");
    private static final String OWNER = "channel-pages-owner";

    private static final String PAGES =
            "/api/v1/control-plane/tenants/" + TENANT + "/sales-channels/" + CHANNEL + "/setup/pages";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the channel pages endpoint test");
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'channel-pages-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :tenantId, 'WEB1', 'WEB', 'Website', 'ACTIVE', 1)
                """).param("id", CHANNEL).param("tenantId", TENANT).update();
        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    @Test
    void aPageThatNeverPublishedAnswersUnpublished() throws Exception {
        MvcResult current =
                mvc.perform(get(PAGES + "/about/current").with(tokenFor(OWNER))).andReturn();

        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        assertThat(current.getResponse().getContentAsString())
                .contains("\"published\":false")
                .contains("\"contentsByLocale\":{}");
    }

    @Test
    void anOwnerCanPublishAndReadBackWhatWasPublished() throws Exception {
        MvcResult published = mvc.perform(post(PAGES + "/about")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "page-publish-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentsByLocale":{"en":"About Tandir House.","ru":"\\u041e \\u043d\\u0430\\u0441."}}
                                """))
                .andReturn();

        assertThat(published.getResponse().getStatus()).isEqualTo(201);
        assertThat(published.getResponse().getHeader("Location")).contains(PAGES + "/about/1");
        assertThat(published.getResponse().getContentAsString())
                .contains("\"published\":true")
                .contains("\"version\":1")
                .contains("About Tandir House.");

        MvcResult current =
                mvc.perform(get(PAGES + "/about/current").with(tokenFor(OWNER))).andReturn();
        assertThat(current.getResponse().getContentAsString()).contains("\"publishedBy\":\"" + OWNER + "\"");
    }

    @Test
    void publishingASecondVersionLeavesTheFirstReadableUnchanged() throws Exception {
        mvc.perform(post(PAGES + "/contacts")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "page-2a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contentsByLocale":{"en":"Version one."}}
                        """));

        mvc.perform(post(PAGES + "/contacts")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "page-2b")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contentsByLocale":{"en":"Version two, completely different."}}
                        """));

        MvcResult versionOne =
                mvc.perform(get(PAGES + "/contacts/1").with(tokenFor(OWNER))).andReturn();
        assertThat(versionOne.getResponse().getContentAsString())
                .contains("Version one.")
                .doesNotContain("completely different");
    }

    @Test
    void anUnknownSlugIsRejected() throws Exception {
        MvcResult rejected = mvc.perform(get(PAGES + "/not-a-real-page/current").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(rejected.getResponse().getStatus())
                .as(
                        "the slug vocabulary is closed (ChannelPageSlug) -- an unknown one is a client mistake, not a missing resource")
                .isEqualTo(400);
        assertThat(rejected.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    void publishingWithNoLanguageIsRejected() throws Exception {
        MvcResult rejected = mvc.perform(post(PAGES + "/delivery-terms")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "page-empty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentsByLocale":{}}
                                """))
                .andReturn();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'channel pages endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(StandardCharsets.UTF_8)))
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
