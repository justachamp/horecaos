package uz.horecaos.platform.partner.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
 * {@code GET .../marketplace/liveness} through the real HTTP stack (gap map
 * row {@code 10.8c}).
 *
 * <p>{@code MarketplaceLivenessService.matrix} and its SQL never filtered by
 * provider category — only {@code partner.JdbcPartnerStore} ever wrote a
 * watermark row, for {@code MARKETPLACE} bindings alone, so the panel only
 * ever had a marketplace row to show. {@code ProviderActivityRecorder} (this
 * wave) gives {@code pos}, {@code payments} (fiscal) and the notification
 * transport their own write side against the identical table. This is the
 * proof, over the endpoint the Integrations health screen actually calls,
 * that a POS, a fiscal and a notification watermark surface beside a
 * marketplace one without any change to the controller, the query or the
 * screen — exactly what {@link uz.horecaos.platform.integration.api.provider.ProviderActivityRecorder}'s
 * own class doc claims.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplaceLivenessHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb400-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb400-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb400-5000-7000-8000-0000000000c1");

    /** Holds {@code MARKETPLACE_LIVENESS_READ} at {@code TENANT} scope, via {@code TENANT_ADMIN}. */
    private static final String READER = "liveness-http-reader";

    /** Holds nothing at all. */
    private static final String STRANGER = "liveness-http-stranger";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the liveness HTTP test");
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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE integration.provider_activity_watermarks, "
                        + "integration.bindings, integration.installations, "
                        + "integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(READER, PlatformRole.TENANT_ADMIN);
    }

    @Test
    @DisplayName("a POS, a fiscal and a notification watermark surface beside a marketplace one, "
            + "on the one endpoint and query that never filtered by provider category")
    void everyProviderCategorysWatermarkSurfaces() throws Exception {
        UUID marketplaceBinding = seedBindingAndWatermark("MARKETPLACE", "Uzum Tezkor", "INBOUND", "UZ-9001");
        UUID posBinding = seedBindingAndWatermark("POS", "iiko Till", "OUTBOUND", "pos-export-77");
        UUID paymentBinding = seedBindingAndWatermark("PAYMENT", "Click Fiscal", "OUTBOUND", "fiscal-receipt-42");
        UUID notificationBinding = seedBindingAndWatermark("NOTIFICATION", "Telegram Bot", "OUTBOUND", "tg-msg-13");

        MvcResult result =
                mvc.perform(get(livenessPath()).with(tokenFor(READER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();

        assertThat(body)
                .as("all four bindings' watermarks are in one response, none filtered out by " + "provider category")
                .contains("\"bindingId\":\"" + marketplaceBinding + "\"")
                .contains("\"bindingId\":\"" + posBinding + "\"")
                .contains("\"bindingId\":\"" + paymentBinding + "\"")
                .contains("\"bindingId\":\"" + notificationBinding + "\"")
                .contains("\"providerName\":\"Uzum Tezkor\"")
                .contains("\"providerName\":\"iiko Till\"")
                .contains("\"providerName\":\"Click Fiscal\"")
                .contains("\"providerName\":\"Telegram Bot\"")
                .contains("\"lastSuccessReference\":\"pos-export-77\"")
                .contains("\"lastSuccessReference\":\"fiscal-receipt-42\"")
                .contains("\"lastSuccessReference\":\"tg-msg-13\"")
                .contains("\"alertState\":\"HEALTHY\"");
    }

    @Test
    @DisplayName("MARKETPLACE_LIVENESS_READ is required — a principal with no grant at all is refused")
    void withoutTheCapabilityTheReadIsRefused() throws Exception {
        seedBindingAndWatermark("MARKETPLACE", "Uzum Tezkor", "INBOUND", "UZ-9002");

        MvcResult result =
                mvc.perform(get(livenessPath()).with(tokenFor(STRANGER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ fixtures

    private String livenessPath() {
        return "/api/v1/operations/tenants/" + TENANT + "/marketplace/liveness";
    }

    /**
     * One installation, one binding scoped to {@link #LOCATION}, and one
     * already-healthy watermark row — the identical shape {@code
     * ProviderActivityRecorder.recordSuccess} writes, inserted directly so
     * this test does not have to stand up a POS adapter, a fiscal submission
     * or a Camel route to prove the read side.
     */
    private UUID seedBindingAndWatermark(
            String providerCategory, String displayName, String direction, String reference) {
        String envCode = "env-" + providerCategory.toLowerCase(java.util.Locale.ROOT);
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, :category, 'GENERIC', 'https://provider.example', false, 'provider.example')
                ON CONFLICT DO NOTHING
                """).param("code", envCode).param("category", providerCategory).update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :tenantId, :category, 'GENERIC', :envCode, :displayName, 'ACTIVE')
                """)
                .param("id", installationId)
                .param("tenantId", TENANT)
                .param("category", providerCategory)
                .param("envCode", envCode)
                .param("displayName", displayName)
                .update();

        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, location_id, status, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', :from)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("from", Instant.now().minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO integration.provider_activity_watermarks
                    (tenant_id, binding_id, location_id, direction, last_success_at,
                     last_success_reference, stale_after_seconds, alert_state, updated_at)
                VALUES (:tenantId, :bindingId, :locationId, :direction, now(), :reference, 43200, 'HEALTHY', now())
                """)
                .param("tenantId", TENANT)
                .param("bindingId", bindingId)
                .param("locationId", LOCATION)
                .param("direction", direction)
                .param("reference", reference)
                .update();

        return bindingId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'liveness-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :scopeId,
                        'ACTIVE', 'test-fixture', 'liveness http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + TENANT).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", TENANT)
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
