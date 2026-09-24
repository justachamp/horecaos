package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Row 10.5's channel setup hub, over HTTP (ADR 0025, ADR 0031).
 *
 * <p>Mirrors {@code OperationsTermsControllerEndpointTests}'s shape.
 * Fixtures need no brand: {@code tenant.sales_channels} is a tenant-level
 * table (ADR 0036), unlike {@code legal.terms_versions}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChannelSetupControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a20-3000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9a20-3000-7000-8000-0000000000a2");
    private static final UUID CHANNEL = UUID.fromString("018f9a20-3000-7000-8000-0000000000b1");
    private static final UUID OTHER_CHANNEL = UUID.fromString("018f9a20-3000-7000-8000-0000000000b2");

    private static final String OWNER = "channel-setup-owner";
    private static final String READ_ONLY = "channel-setup-reader";

    private static final String IDEMPOTENCY_HEADER = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the channel setup endpoint test");
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
        insertTenant(TENANT, "channel-setup-endpoint");
        insertTenant(OTHER_TENANT, "channel-setup-endpoint-other");
        insertChannel(TENANT, CHANNEL, "WEB1", "WEB");
        insertChannel(OTHER_TENANT, OTHER_CHANNEL, "WEB1", "WEB");
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
        grant(READ_ONLY, PlatformRole.BRAND_MANAGER, TENANT);
    }

    private String setupPath(UUID tenantId, UUID channelId) {
        return "/api/v1/control-plane/tenants/" + tenantId + "/sales-channels/" + channelId + "/setup";
    }

    // ------------------------------------------------------------ hostname

    @Test
    void anUnconfiguredChannelAnswersNotConfigured() throws Exception {
        MvcResult result = mvc.perform(
                        get(setupPath(TENANT, CHANNEL) + "/hostname").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"configured\":false");
    }

    @Test
    void anOwnerCanClaimASubdomainAndItIsVerifiedImmediately() throws Exception {
        MvcResult claimed = mvc.perform(put(setupPath(TENANT, CHANNEL) + "/hostname/subdomain")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "subdomain-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", "1")
                        .content("""
                                {"slug":"tandir-house"}
                                """))
                .andReturn();

        assertThat(claimed.getResponse().getStatus()).isEqualTo(200);
        assertThat(claimed.getResponse().getContentAsString())
                .contains("\"hostname\":\"tandir-house.stores.horecaos.uz\"")
                .contains("\"verified\":true");
    }

    @Test
    void aReservedSlugIsRejected() throws Exception {
        MvcResult rejected = mvc.perform(put(setupPath(TENANT, CHANNEL) + "/hostname/subdomain")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "subdomain-reserved")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", "1")
                        .content("""
                                {"slug":"admin"}
                                """))
                .andReturn();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(rejected.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    void aCustomHostnameStartsUnverifiedAndCanBeMarkedVerified() throws Exception {
        MvcResult claimed = mvc.perform(put(setupPath(TENANT, CHANNEL) + "/hostname/custom")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "custom-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", "1")
                        .content("""
                                {"hostname":"orders.tandir-house.uz"}
                                """))
                .andReturn();
        assertThat(claimed.getResponse().getStatus()).isEqualTo(200);
        assertThat(claimed.getResponse().getContentAsString())
                .contains("\"hostname\":\"orders.tandir-house.uz\"")
                .contains("\"verified\":false");

        MvcResult verified = mvc.perform(post(setupPath(TENANT, CHANNEL) + "/hostname/verify")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "custom-verify-1")
                        .param("expectedVersion", "2"))
                .andReturn();
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        assertThat(verified.getResponse().getContentAsString()).contains("\"verified\":true");
    }

    @Test
    void twoChannelsCannotClaimTheSameHostname() throws Exception {
        mvc.perform(put(setupPath(TENANT, CHANNEL) + "/hostname/custom")
                .with(tokenFor(OWNER))
                .header(IDEMPOTENCY_HEADER, "collide-1")
                .contentType(MediaType.APPLICATION_JSON)
                .param("expectedVersion", "1")
                .content("""
                        {"hostname":"shared.example.uz"}
                        """));

        MvcResult collision = mvc.perform(put(setupPath(OTHER_TENANT, OTHER_CHANNEL) + "/hostname/custom")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "collide-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", "1")
                        .content("""
                                {"hostname":"shared.example.uz"}
                                """))
                .andReturn();

        assertThat(collision.getResponse().getStatus())
                .as("hostname is globally unique (V0403), not merely per tenant")
                .isEqualTo(409);
        assertThat(collision.getResponse().getContentAsString()).contains("RESOURCE_CONFLICT");
    }

    @Test
    void aStaleVersionIsRejectedWithConflict() throws Exception {
        MvcResult stale = mvc.perform(put(setupPath(TENANT, CHANNEL) + "/hostname/subdomain")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "stale-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", "99")
                        .content("""
                                {"slug":"whatever"}
                                """))
                .andReturn();

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aReaderCannotClaimAHostname() throws Exception {
        MvcResult refused = mvc.perform(put(setupPath(TENANT, CHANNEL) + "/hostname/subdomain")
                        .with(tokenFor(READ_ONLY))
                        .header(IDEMPOTENCY_HEADER, "refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", "1")
                        .content("""
                                {"slug":"tandir-house"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains(Capability.CHANNEL_MANAGE.code());
    }

    @Test
    void aTenantCannotReadAnotherTenantsChannelSetup() throws Exception {
        MvcResult result = mvc.perform(
                        get(setupPath(TENANT, OTHER_CHANNEL) + "/hostname").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("OTHER_CHANNEL belongs to OTHER_TENANT, so scoping by TENANT's own path must not find it")
                .isEqualTo(404);
    }

    // --------------------------------------------------------- presentation

    @Test
    void presentationRoundTrips() throws Exception {
        UUID ogImage = UUID.randomUUID();
        MvcResult saved = mvc.perform(put(setupPath(TENANT, CHANNEL) + "/presentation")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "presentation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", "1")
                        .content("""
                                {"seoTitle":"Tandir House — order online","seoDescription":"Fresh non bread, delivered.","ogImageAssetId":"%s"}
                                """.formatted(ogImage)))
                .andReturn();

        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        assertThat(saved.getResponse().getContentAsString())
                .contains("Tandir House")
                .contains(ogImage.toString());

        MvcResult read = mvc.perform(
                        get(setupPath(TENANT, CHANNEL) + "/presentation").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(read.getResponse().getContentAsString()).contains("Tandir House");
    }

    // -------------------------------------------------------------- helpers

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertChannel(UUID tenantId, UUID channelId, String code, String systemType) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :tenantId, :code, :systemType, 'Website', 'ACTIVE', 1)
                """)
                .param("id", channelId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("systemType", systemType)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'channel setup endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", tenantId)
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
