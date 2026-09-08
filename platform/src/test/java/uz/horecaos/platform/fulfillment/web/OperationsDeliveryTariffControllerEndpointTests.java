package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
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
 * ADR 0037's delivery rate tables, reachable from a tenant's own operations
 * surface. The mirror of {@link OperationsServiceZoneControllerEndpointTests}
 * for tariffs — see that class's own doc for the shared rationale.
 *
 * <p>The one difference worth a comment: {@code TENANT_FINANCE} holds {@code
 * DELIVERY_TARIFF_ACTIVATE} but not {@code DELIVERY_TARIFF_MANAGE} — "activating
 * a rate table is money; drawing one is not" — so this suite proves finance can
 * activate a draft it did not author, and cannot draft one of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsDeliveryTariffControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9c10-2000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9c10-2000-7000-8000-0000000000b1");

    private static final UUID OTHER_TENANT = UUID.fromString("018f9c10-2000-7000-8000-0000000000a2");

    // UUID-shaped, not human-readable: draftVersion and activate write these
    // subjects onto delivery_tariff_versions.created_by/activated_by, both
    // `uuid NOT NULL` columns, exactly as a real Keycloak `sub` claim would be.
    private static final String OWNER = "018f9c10-3000-7000-8000-0000000000f1";
    private static final String FINANCE = "018f9c10-3000-7000-8000-0000000000f2";
    private static final String OTHER_TENANT_OWNER = "018f9c10-3000-7000-8000-0000000000f3";

    private static String tariffsPath(UUID tenantId) {
        return "/api/v1/operations/tenants/" + tenantId + "/brands/" + BRAND + "/delivery-tariffs";
    }

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the tariff endpoint test");
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
        jdbc.sql("TRUNCATE TABLE fulfillment.location_tariff_bindings, fulfillment.delivery_tariff_bands, "
                        + "fulfillment.delivery_tariff_time_rules, fulfillment.delivery_tariff_discounts, "
                        + "fulfillment.delivery_tariff_versions, fulfillment.delivery_tariffs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        insertTenantAndBrand(TENANT, BRAND);
        insertTenantAndBrand(OTHER_TENANT, UUID.randomUUID());
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(FINANCE, PlatformRole.TENANT_FINANCE, TENANT);
        grant(OTHER_TENANT_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
    }

    @Test
    void anOwnerRegistersDraftsAndActivatesATariff() throws Exception {
        UUID tariffId = registerTariff(OWNER);
        draftVersion(tariffId, OWNER, "draft-1");

        MvcResult activated = mvc.perform(post(tariffsPath(TENANT) + "/" + tariffId + "/versions/1/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-1"))
                .andReturn();
        assertThat(activated.getResponse().getStatus()).isEqualTo(200);
        assertThat(activated.getResponse().getContentAsString()).contains("\"status\":\"ACTIVE\"");

        MvcResult detail = mvc.perform(get(tariffsPath(TENANT) + "/" + tariffId).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(detail.getResponse().getContentAsString()).contains("\"activeVersion\":");

        assertThat(auditActionCounts())
                .containsEntry("delivery.tariff.registered", 1L)
                .containsEntry("delivery.tariff.version.drafted", 1L)
                .containsEntry("delivery.tariff.version.activated", 1L);
    }

    @Test
    void financeActivatesADraftItDidNotAuthorButCannotDraftItsOwn() throws Exception {
        UUID tariffId = registerTariff(OWNER);
        draftVersion(tariffId, OWNER, "draft-2");

        MvcResult activated = mvc.perform(post(tariffsPath(TENANT) + "/" + tariffId + "/versions/1/activate")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-2"))
                .andReturn();
        assertThat(activated.getResponse().getStatus())
                .as("finance activates a rate table without having authored it")
                .isEqualTo(200);

        MvcResult refused = mvc.perform(post(tariffsPath(TENANT))
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-by-finance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"FIN1","name":"Finance-drawn","brandDefault":false}
                                """))
                .andReturn();
        assertThat(refused.getResponse().getStatus())
                .as("drawing a rate table is not money; activating it is, so finance may only do the latter")
                .isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains(Capability.DELIVERY_TARIFF_MANAGE.code());
    }

    @Test
    void aGrantScopedToOneTenantCannotReadOrWriteAnotherTenantsTariff() throws Exception {
        UUID tariffId = registerTariff(OWNER);

        MvcResult readRefused = mvc.perform(
                        get(tariffsPath(TENANT) + "/" + tariffId).with(tokenFor(OTHER_TENANT_OWNER)))
                .andReturn();
        assertThat(readRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(readRefused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");

        MvcResult writeRefused = mvc.perform(post(tariffsPath(TENANT))
                        .with(tokenFor(OTHER_TENANT_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cross-tenant-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"HACK","name":"Should not be created","brandDefault":false}
                                """))
                .andReturn();
        assertThat(writeRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.delivery_tariffs WHERE code = 'HACK'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private UUID registerTariff(String subject) throws Exception {
        String code = "T"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        MvcResult created = mvc.perform(post(tariffsPath(TENANT))
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Standard","brandDefault":false}
                                """.formatted(code)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        return jdbc.sql("SELECT id FROM fulfillment.delivery_tariffs WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", TENANT)
                .param("code", code)
                .query(UUID.class)
                .single();
    }

    private void draftVersion(UUID tariffId, String subject, String idempotencyKey) throws Exception {
        MvcResult drafted = mvc.perform(post(tariffsPath(TENANT) + "/" + tariffId + "/versions")
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"UZS","feeSource":"TARIFF","distanceMode":"RADIUS",
                                 "roadFactorBasisPoints":10000,"maxDistanceMeters":5000,
                                 "minFeeMinor":0,
                                 "bands":[{"fromMeters":0,"toMeters":5000,"baseMinor":10000,"perKmMinor":0}]}
                                """))
                .andReturn();
        assertThat(drafted.getResponse().getStatus()).isEqualTo(200);
        assertThat(drafted.getResponse().getContentAsString()).contains("\"status\":\"DRAFT\"");
    }

    private Map<String, Long> auditActionCounts() {
        return jdbc
                .sql("SELECT action_code, count(*) c FROM audit.audit_events GROUP BY action_code")
                .query((rs, n) -> Map.entry(rs.getString("action_code"), rs.getLong("c")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void insertTenantAndBrand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "tariff-endpoint-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'tariff endpoint test', :validFrom)
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
