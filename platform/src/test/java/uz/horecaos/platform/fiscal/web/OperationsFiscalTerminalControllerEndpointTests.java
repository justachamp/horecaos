package uz.horecaos.platform.fiscal.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * ADR 0038 lines 503-513's fiscal terminal registry, over HTTP.
 *
 * <p>Proves the two facts the rest of this wave depends on: a terminal cannot
 * claim {@code IssueFiscalReceipt} without a provider binding (the database
 * constraint, reported as Problem Details rather than a 500), and once one is
 * registered {@code JdbcFiscalTerminalStore.hasCapableTerminal} — the query
 * {@code CheckoutSettlementPlanner} reads — turns true for that location.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsFiscalTerminalControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a10-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9a10-3000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9a10-3000-7000-8000-0000000000c1");
    private static final UUID LEGAL_ENTITY = UUID.fromString("018f9a10-3000-7000-8000-0000000000d1");
    private static final UUID INSTALLATION = UUID.fromString("018f9a10-3000-7000-8000-0000000000e1");
    private static final UUID BINDING = UUID.fromString("018f9a10-3000-7000-8000-0000000000f1");

    private static final String OWNER = "terminal-owner";
    private static final String BRAND_MANAGER = "terminal-brand-manager";

    private static final String TERMINALS =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/fiscal-terminals";

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

    @Autowired
    private uz.horecaos.platform.fiscal.api.FiscalTerminalDirectory directory;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        roleRegistry.synchronize();
        insertFixtures();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER);
    }

    @Test
    void aTerminalCannotClaimIssueFiscalReceiptWithoutABinding() throws Exception {
        MvcResult refused = mvc.perform(post(TERMINALS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-unbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("KASSA-1", null, true)))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("FiscalTerminal's own constructor refuses this before any INSERT is attempted, "
                        + "the same domain-invariant style LegalEntity.applyVatRegistration uses; "
                        + "GlobalApiErrorHandler maps IllegalArgumentException to INVALID_REQUEST "
                        + "rather than a 500")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("INVALID_REQUEST");
    }

    @Test
    void anOwnerRegistersAndTheLocationBecomesFiscallyCapable() throws Exception {
        assertThat(directory.hasCapableTerminal(TENANT, LOCATION))
                .as("no terminal registered yet")
                .isFalse();

        MvcResult registered = mvc.perform(post(TERMINALS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("KASSA-1", BINDING, true)))
                .andReturn();

        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        assertThat(registered.getResponse().getContentAsString())
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"capable\":true");

        assertThat(directory.hasCapableTerminal(TENANT, LOCATION))
                .as("CheckoutSettlementPlanner reads exactly this query")
                .isTrue();
    }

    @Test
    void aBrandManagerCannotRegisterATerminal() throws Exception {
        MvcResult refused = mvc.perform(post(TERMINALS)
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("KASSA-2", BINDING, true)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains(Capability.FISCAL_TERMINAL_MANAGE.code());
    }

    @Test
    void healthCheckAndSuspendChangeWhatTheDirectorySees() throws Exception {
        MvcResult registered = mvc.perform(post(TERMINALS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("KASSA-3", BINDING, true)))
                .andReturn();
        UUID terminalId = terminalId("KASSA-3");

        MvcResult healthChecked = mvc.perform(post(TERMINALS + "/" + terminalId + "/health-checks")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "health-1")
                        .queryParam("expectedVersion", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome":"HEALTHY"}
                                """))
                .andReturn();
        assertThat(healthChecked.getResponse().getStatus()).isEqualTo(200);
        assertThat(healthChecked.getResponse().getContentAsString()).contains("\"lastHealthStatus\":\"HEALTHY\"");

        assertThat(directory.hasCapableTerminal(TENANT, LOCATION)).isTrue();

        MvcResult suspended = mvc.perform(post(TERMINALS + "/" + terminalId + "/suspend")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "suspend-1")
                        .queryParam("expectedVersion", "2"))
                .andReturn();
        assertThat(suspended.getResponse().getStatus()).isEqualTo(200);
        assertThat(suspended.getResponse().getContentAsString()).contains("\"status\":\"SUSPENDED\"");

        assertThat(directory.hasCapableTerminal(TENANT, LOCATION))
                .as("a suspended terminal must stop counting toward the activation precondition")
                .isFalse();

        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void listOrdersFailingHealthFirst() throws Exception {
        mvc.perform(post(TERMINALS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "list-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("KASSA-A", BINDING, false)));
        mvc.perform(post(TERMINALS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "list-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("KASSA-B", BINDING, false)));
        UUID failing = terminalId("KASSA-B");
        mvc.perform(post(TERMINALS + "/" + failing + "/health-checks")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "health-2")
                .queryParam("expectedVersion", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"outcome":"UNHEALTHY"}
                        """));

        MvcResult listed = mvc.perform(get(TERMINALS).with(tokenFor(OWNER))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        String body = listed.getResponse().getContentAsString();
        assertThat(body.indexOf("KASSA-B"))
                .as("failing health sorts ahead of never-checked")
                .isLessThan(body.indexOf("KASSA-A"))
                .isGreaterThanOrEqualTo(0);
    }

    private UUID terminalId(String reference) {
        return jdbc.sql(
                        "SELECT id FROM fiscal.fiscal_terminals WHERE tenant_id = :tenantId AND terminal_reference = :reference")
                .param("tenantId", TENANT)
                .param("reference", reference)
                .query(UUID.class)
                .single();
    }

    private static String registerBody(String reference, @Nullable UUID bindingId, boolean issuesReceipts) {
        String binding = bindingId == null ? "null" : "\"" + bindingId + "\"";
        String snapshot = issuesReceipts ? "{\"IssueFiscalReceipt\":true}" : "{}";
        return """
                {"locationId":"%s","legalEntityId":"%s","kind":"POS","providerBindingId":%s,
                 "terminalReference":"%s","capabilitySnapshot":%s}
                """.formatted(LOCATION, LEGAL_ENTITY, binding, reference, snapshot);
    }

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'fiscal-terminal-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                INSERT INTO tenant.legal_entities
                    (id, tenant_id, code, legal_name, tin, vat_registered, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'Rayhon LLC', '123456789', false, 'ACTIVE', 1)
                """).param("id", LEGAL_ENTITY).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('click-sandbox-fiscal-terminal', 'PAYMENT', 'CLICK', 'https://api.click.uz/v2/merchant',
                    false, 'api.click.uz')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status)
                VALUES (:id, :tenantId, 'PAYMENT', 'CLICK', 'click-sandbox-fiscal-terminal', 'Click', 'ACTIVE')
                """).param("id", INSTALLATION).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("tenantId", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'fiscal terminal endpoint test', :validFrom)
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
