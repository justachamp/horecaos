package uz.horecaos.platform.courier.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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

/**
 * {@code GET}/{@code PUT .../courier-policy}, exercised through the real HTTP
 * stack (gap map row {@code 10.13}, wave P38).
 *
 * <p>Before this wave the read existed with no writer reachable from any
 * controller — {@code PolicyAuthor} had exactly two other consumers — so
 * every field below, including the five/six couriers.md §16 always named and
 * this wave added to the document (the GPS master toggle and its two radii,
 * kitchen-ready-only, reveal timing, the post-delivery payment check), had
 * never been proven to round-trip through ADR 0030 at the HTTP layer at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CourierPolicyEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fd600-4000-7000-8000-0000000000a1");

    /** Holds {@code DELIVERY_POLICY_READ}/{@code DELIVERY_POLICY_WRITE} at TENANT (TENANT_ADMIN's own bundle). */
    private static final String MANAGER = "courier-policy-manager";

    /** No grant anywhere — the capability-refused negative case for both the read and the write. */
    private static final String NOBODY = "courier-policy-nobody";

    private static final String FULL_POLICY_BODY = """
            {
              "reverificationDays": 90,
              "warningDays": 14,
              "settlementPeriodDays": 7,
              "cashCeilingMinor": 2000000,
              "penaltyApprovalThresholdMinor": 100000,
              "shiftEnforcement": "ENFORCED",
              "graceSeconds": 180,
              "confirmationPointRetentionDays": 45,
              "gpsVerificationEnabled": true,
              "gpsAcceptRadiusMeters": 400,
              "gpsStatusChangeRadiusMeters": 60,
              "kitchenReadyOnly": true,
              "revealCustomerLocationTiming": "BEFORE_ACCEPT",
              "postDeliveryPaymentCheckRequired": true,
              "reason": "Tightened for the pilot branch"
            }
            """;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the courier policy endpoint test");
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
    private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();

        // tenant.policy_current (ADR 0033) is a singleton in-process cache
        // shared by every test method in this class's one Spring context. Two
        // test methods resolve the exact same (keyCode, scope) pair — the
        // fixed TENANT constant never changes — so a value one method
        // published and then read (populating the cache) would otherwise
        // still answer a later method that truncated the database and
        // expected to see nothing configured.
        Cache policyCurrent = cacheManager.getCache("tenant.policy_current");
        if (policyCurrent != null) {
            policyCurrent.clear();
        }

        // tenant.policies/policy_current and iam.grants all chain back to
        // tenant.tenants by foreign key, so TRUNCATE ... CASCADE from the root
        // clears every one of them in one statement.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'courier-policy-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.TENANT_ADMIN);
    }

    @Test
    @DisplayName("GET .../courier-policy before any write resolves ADR 0042's provisional defaults, "
            + "including all six new fields")
    void defaultsResolveWhenNothingHasBeenAuthored() throws Exception {
        MvcResult result =
                mvc.perform(get(policyPath()).with(tokenFor(MANAGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.path("gpsVerificationEnabled").asBoolean()).isFalse();
        assertThat(body.path("gpsAcceptRadiusMeters").asInt()).isEqualTo(1000);
        assertThat(body.path("gpsStatusChangeRadiusMeters").asInt()).isEqualTo(150);
        assertThat(body.path("kitchenReadyOnly").asBoolean()).isFalse();
        assertThat(body.path("revealCustomerLocationTiming").asText()).isEqualTo("AFTER_ACCEPT");
        assertThat(body.path("postDeliveryPaymentCheckRequired").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("PUT .../courier-policy publishes a new version, and GET reflects exactly what was written")
    void writingPublishesANewVersionAndTheReadReflectsIt() throws Exception {
        MvcResult written = mvc.perform(put(policyPath())
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "courier-policy-write-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_POLICY_BODY))
                .andReturn();

        assertThat(written.getResponse().getStatus()).isEqualTo(200);
        JsonNode writtenBody = json(written);
        assertThat(writtenBody.path("policyVersion").asInt()).isEqualTo(1);
        assertThat(writtenBody.path("winningScope").asText()).isEqualTo("TENANT");
        assertNewFieldsMatchTheFullPolicyBody(writtenBody);

        MvcResult read = mvc.perform(get(policyPath()).with(tokenFor(MANAGER))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertNewFieldsMatchTheFullPolicyBody(json(read));
    }

    @Test
    @DisplayName("a second write publishes version 2 without touching version 1 — ADR 0030 never edits in place")
    void aSecondWriteAdvancesTheVersion() throws Exception {
        mvc.perform(put(policyPath())
                .with(tokenFor(MANAGER))
                .header("Idempotency-Key", "courier-policy-write-2a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(FULL_POLICY_BODY));

        MvcResult secondWrite = mvc.perform(put(policyPath())
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "courier-policy-write-2b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_POLICY_BODY.replace(
                                "\"gpsAcceptRadiusMeters\": 400", "\"gpsAcceptRadiusMeters\": 800")))
                .andReturn();

        assertThat(secondWrite.getResponse().getStatus()).isEqualTo(200);
        JsonNode secondBody = json(secondWrite);
        assertThat(secondBody.path("policyVersion").asInt()).isEqualTo(2);
        assertThat(secondBody.path("gpsAcceptRadiusMeters").asInt()).isEqualTo(800);

        long storedVersions = jdbc.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'courier.compensation'")
                .query(Long.class)
                .single();
        assertThat(storedVersions).as("both versions remain as immutable rows").isEqualTo(2);
    }

    @Test
    @DisplayName("PUT .../courier-policy is refused for a caller holding no capability")
    void writeIsRefusedWithoutCapability() throws Exception {
        MvcResult attempt = mvc.perform(put(policyPath())
                        .with(tokenFor(NOBODY))
                        .header("Idempotency-Key", "courier-policy-write-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_POLICY_BODY))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("GET .../courier-policy is refused for a caller holding no capability")
    void readIsRefusedWithoutCapability() throws Exception {
        MvcResult attempt =
                mvc.perform(get(policyPath()).with(tokenFor(NOBODY))).andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ fixtures

    private static void assertNewFieldsMatchTheFullPolicyBody(JsonNode body) {
        assertThat(body.path("gpsVerificationEnabled").asBoolean()).isTrue();
        assertThat(body.path("gpsAcceptRadiusMeters").asInt()).isEqualTo(400);
        assertThat(body.path("gpsStatusChangeRadiusMeters").asInt()).isEqualTo(60);
        assertThat(body.path("kitchenReadyOnly").asBoolean()).isTrue();
        assertThat(body.path("revealCustomerLocationTiming").asText()).isEqualTo("BEFORE_ACCEPT");
        assertThat(body.path("postDeliveryPaymentCheckRequired").asBoolean()).isTrue();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String policyPath() {
        return "/api/v1/operations/tenants/" + TENANT + "/courier-policy";
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'courier policy endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + TENANT).getBytes(UTF_8)))
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
