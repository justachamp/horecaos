package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code GET}/{@code PUT .../payment-window} through the real HTTP stack (ADR 0142 Decision 7, ADR 0019).
 *
 * <p>The window is {@code ordering}'s policy shown beside the dispatch rules. What the sweep does with it is
 * the checkout suite's: these tests own the document, its version, its bounds, the refusal of
 * {@code CANCEL}, and who may write it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsPaymentWindowEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fd600-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fd600-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fd600-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd600-4000-7000-8000-0000000000c1");

    private static final String ADMIN = "payment-window-admin";
    private static final String NOBODY = "payment-window-nobody";
    private static final String OTHER_ADMIN = "payment-window-other-admin";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the payment window endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        // The schedulers poll the very tables reset() truncates; left running they deadlock with it now
        // and then, and nothing here depends on either of them.
        registry.add("horecaos.fulfillment.sourcing.enabled", () -> "false");
        registry.add("horecaos.ordering.workers.enabled", () -> "false");
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
    private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        Cache policyCurrent = cacheManager.getCache("tenant.policy_current");
        if (policyCurrent != null) {
            policyCurrent.clear();
        }
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        // The audit log is not tenant-owned by foreign key, so the line above does not clear it.
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        tenant(TENANT, "payment-window-endpoint");
        tenant(OTHER_TENANT, "payment-window-endpoint-other");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();

        roleRegistry.synchronize();
        grant(ADMIN, PlatformRole.TENANT_ADMIN, TENANT);
        grant(OTHER_ADMIN, PlatformRole.TENANT_ADMIN, OTHER_TENANT);
    }

    @Test
    @DisplayName("before anything is published the window is the deployment's own threshold, flagged only")
    void theDeployThresholdIsTheDefault() throws Exception {
        MvcResult result = mvc.perform(get(path()).with(tokenFor(ADMIN))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.path("isDefault").asBoolean()).isTrue();
        assertThat(body.path("windowMinutes").asInt())
                .as("horecaos.ordering.workers.payment.stale-after")
                .isEqualTo(30);
        assertThat(body.path("action").asText()).isEqualTo("FLAG_ONLY");
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"0\"");
    }

    @Test
    @DisplayName("publishing a window is versioned, audited, and replaces the default at that scope")
    void publishingARoundTrips() throws Exception {
        MvcResult written = put(ADMIN, "window-1", path(), 0, body(45, "FLAG_ONLY"));

        assertThat(written.getResponse().getStatus()).isEqualTo(200);
        assertThat(written.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("W/\"1\"");
        assertThat(json(written).path("windowMinutes").asInt()).isEqualTo(45);
        assertThat(json(written).path("isDefault").asBoolean()).isFalse();
        assertThat(json(mvc.perform(get(path()).with(tokenFor(ADMIN))).andReturn())
                        .path("windowMinutes")
                        .asInt())
                .isEqualTo(45);
        assertThat(jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                        WHERE action_code = 'ordering.payment-window.authored' AND tenant_id = :t
                        """).param("t", TENANT).query(String.class).single())
                .contains("windowMinutes")
                .contains("45");
    }

    @Test
    @DisplayName("a location-scope window is independent of the tenant's")
    void aLocationWindowIsIndependent() throws Exception {
        put(ADMIN, "window-tenant", path(), 0, body(60, "FLAG_ONLY"));

        MvcResult location = put(
                ADMIN,
                "window-location",
                path() + "?brandId=" + BRAND + "&locationId=" + LOCATION,
                0,
                body(10, "FLAG_ONLY"));

        assertThat(location.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(location).path("winningScope").asText()).isEqualTo("LOCATION");
        assertThat(json(mvc.perform(get(path()).with(tokenFor(ADMIN))).andReturn())
                        .path("windowMinutes")
                        .asInt())
                .as("the tenant's own window is untouched")
                .isEqualTo(60);
    }

    @Test
    @DisplayName("CANCEL is refused with VALIDATION_FAILED until product answers ADR 0019, and nothing is published")
    void cancelIsRefused() throws Exception {
        MvcResult attempt = put(ADMIN, "window-cancel", path(), 0, body(30, "CANCEL"));

        assertThat(attempt.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(attempt).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(json(attempt).path("detail").asText()).contains("ADR 0019");
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("a window of zero minutes or more than a day is refused")
    void theWindowIsBounded() throws Exception {
        assertThat(put(ADMIN, "window-zero", path(), 0, body(0, "FLAG_ONLY"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(put(ADMIN, "window-huge", path(), 0, body(1_441, "FLAG_ONLY"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("a stale form is refused with STALE_VERSION, and a write without If-Match is refused outright")
    void versionsAreChecked() throws Exception {
        put(ADMIN, "window-a", path(), 0, body(20, "FLAG_ONLY"));

        MvcResult stale = put(ADMIN, "window-b", path(), 0, body(25, "FLAG_ONLY"));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(stale).path("code").asText()).isEqualTo("STALE_VERSION");

        MvcResult unconditional = mvc.perform(MockMvcRequestBuilders.put(path())
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", "window-c")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(25, "FLAG_ONLY")))
                .andReturn();
        assertThat(unconditional.getResponse().getStatus()).isEqualTo(400);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "writing needs order.payment-window.manage and reading needs configuration read; another tenant's admin gets neither")
    void authorization() throws Exception {
        assertThat(put(NOBODY, "window-nobody", path(), 0, body(20, "FLAG_ONLY"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get(path()).with(tokenFor(NOBODY)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get(path()).with(tokenFor(OTHER_ADMIN)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(put(OTHER_ADMIN, "window-other", path(), 0, body(20, "FLAG_ONLY"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(count()).isZero();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'ordering.payment_window'")
                .query(Long.class)
                .single();
    }

    private static String body(int minutes, String action) {
        return """
                { "windowMinutes": %d, "action": "%s", "reason": "Test" }
                """.formatted(minutes, action);
    }

    private MvcResult put(String subject, String key, String path, int ifMatch, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(path)
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .header(HttpHeaders.IF_MATCH, "\"" + ifMatch + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String path() {
        return "/api/v1/operations/tenants/" + TENANT + "/payment-window";
    }

    private void tenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'payment window endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
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
