package uz.horecaos.platform.pos.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.staff.StaffMemberRegistry;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map row 9.2c, whole: an operator writes a till-operator id against a named
 * colleague in the mapping pane, and the next order that colleague accepts carries
 * it (ADR 0139, ADR 0026).
 *
 * <p>The row had a reader and no author: {@code PosOrderExportService} looked up
 * entity type {@code OPERATOR}, and the pane's enum had no such value. These tests
 * run the two ends together -- the real pane over HTTP, the real staff directory,
 * the real mapping lookup -- so neither end can be right by itself and wrong in
 * combination. The mapping is stored under the staff member's id, never under the
 * Keycloak subject that is on the order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PosOperatorMappingEndToEndTests {

    private static final UUID TENANT = UUID.fromString("018fb600-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb600-4000-7000-8000-0000000000b1");
    private static final UUID INSTALLATION = UUID.fromString("018fb600-4000-7000-8000-0000000000d1");
    private static final UUID BINDING = UUID.fromString("018fb600-4000-7000-8000-0000000000e1");

    private static final UUID OTHER_TENANT = UUID.fromString("018fb600-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fb600-4000-7000-8000-0000000000b2");

    private static final String OWNER = "pos-owner";
    private static final String AZIZA = "pos-aziza";
    private static final String BOBUR = "pos-bobur";
    private static final String LEAVER = "pos-leaver";
    private static final String OTHER = "pos-other-tenant-member";
    private static final String LOCATION_STAFF = "pos-line-cook";

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
        // The staff member record seals names and phones under ADR 0029; a context
        // that serves it needs the platform key-encryption key, as every other
        // suite that reaches FieldProtection supplies.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
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
    private StaffMemberRegistry registry;

    @Autowired
    @SuppressWarnings("NullAway")
    private TransactionTemplate tx;

    @Autowired
    @SuppressWarnings("NullAway")
    private PosOrderExportService exportService;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE integration.provider_entity_mappings").update();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("DELETE FROM integration.bindings").update();
        jdbc.sql("DELETE FROM integration.installations").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        seedTenant(TENANT, BRAND, "pos-operator-a");
        seedTenant(OTHER_TENANT, OTHER_BRAND, "pos-operator-b");
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'POS', :providerType, 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """)
                .param("id", INSTALLATION)
                .param("t", TENANT)
                .param("providerType", FakePosAdapter.PROVIDER_TYPE)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :i, :b, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("i", INSTALLATION)
                .param("b", BRAND)
                .update();

        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(LOCATION_STAFF, PlatformRole.LOCATION_STAFF, "TENANT", TENANT);
        member(TENANT, AZIZA, "Aziza", "Karimova");
        member(TENANT, BOBUR, "Bobur", "Yusupov");
        member(TENANT, LEAVER, "Leaving", "Employee");
        member(OTHER_TENANT, OTHER, "Other", "Tenant");
        jdbc.sql(
                        "UPDATE iam.staff_members SET employment_status = 'ENDED', employed_until = '2026-09-01' WHERE principal_subject = :s")
                .param("s", LEAVER)
                .update();
    }

    @Test
    @DisplayName("the pane lists the tenant's people by name, not yet paired, and leaves out an ended colleague")
    void thePaneOffersPeopleByName() throws Exception {
        MvcResult unmapped = get(unmappedPath(), OWNER);

        assertThat(unmapped.getResponse().getStatus()).isEqualTo(200);
        String body = unmapped.getResponse().getContentAsString(UTF_8);
        assertThat(body)
                .contains("Aziza Karimova", "Bobur Yusupov")
                .doesNotContain("Leaving Employee", "Other Tenant")
                .contains("\"sourced\":false");
    }

    @Test
    @DisplayName(
            "mapping a till operator id to a named colleague is read by the export on the next order that colleague accepts")
    void theMappingResolvesOnTheNextAcceptedOrder() throws Exception {
        UUID aziza = memberId(TENANT, AZIZA);

        MvcResult created = post(
                "{\"bindingId\":\"%s\",\"entityType\":\"OPERATOR\",\"horecaosEntityId\":\"%s\",\"externalEntityId\":\"till-17\"}"
                        .formatted(BINDING, aziza));
        assertThat(created.getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.sql(
                                "SELECT horecaos_entity_id FROM integration.provider_entity_mappings WHERE entity_type = 'OPERATOR'")
                        .query(UUID.class)
                        .single())
                .as("keyed by the staff member id, the way VARIANT and COURIER key on their own HorecaOS entity")
                .isEqualTo(aziza);
        assertThat(exportService.resolveOperatorExternalId(TENANT, BINDING, "USER", AZIZA))
                .isEqualTo("till-17");
        assertThat(exportService.resolveOperatorExternalId(TENANT, BINDING, "USER", BOBUR))
                .as("an order accepted by a member with no mapping resolves to nothing")
                .isNull();
        assertThat(exportService.resolveOperatorExternalId(OTHER_TENANT, BINDING, "USER", AZIZA))
                .as("and the same subject on another tenant's order is no operator there")
                .isNull();

        MvcResult listed = get(listPath(), OWNER);
        assertThat(listed.getResponse().getContentAsString(UTF_8))
                .as("the linked-pairs table shows the colleague's name beside the till's id")
                .contains("Aziza Karimova", "till-17");
        assertThat(get(unmappedPath(), OWNER).getResponse().getContentAsString(UTF_8))
                .as("and a paired colleague is no longer offered")
                .doesNotContain("Aziza Karimova")
                .contains("Bobur Yusupov");
    }

    @Test
    @DisplayName("a colleague can be paired once, and a till id once: a second claim on either side is a conflict")
    void eachSideIsClaimedOnce() throws Exception {
        UUID aziza = memberId(TENANT, AZIZA);
        UUID bobur = memberId(TENANT, BOBUR);
        assertThat(post(create(aziza, "till-17")).getResponse().getStatus()).isEqualTo(200);

        assertThat(post(create(aziza, "till-18")).getResponse().getStatus()).isEqualTo(409);
        assertThat(post(create(bobur, "till-17")).getResponse().getStatus()).isEqualTo(409);
        assertThat(post(create(bobur, "till-19")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a member of another tenant, and an id that is nobody's, are refused with the same answer")
    void aForeignMemberIsNoOracle() throws Exception {
        MvcResult foreign = post(create(memberId(OTHER_TENANT, OTHER), "till-99"));
        MvcResult nobody = post(create(UUID.randomUUID(), "till-98"));

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(nobody.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM integration.provider_entity_mappings")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("only holders of pos.sync.manage write a mapping; a line cook reads nothing of the pane")
    void theCapabilitiesAreThePanesOwn() throws Exception {
        MvcResult refused = mvc.perform(MockMvcRequestBuilders.post(basePath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create(memberId(TENANT, AZIZA), "till-1"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(LOCATION_STAFF)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(get(unmappedPath(), LOCATION_STAFF).getResponse().getStatus())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------- fixtures

    private static String basePath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/pos-mappings";
    }

    private static String unmappedPath() {
        return basePath() + "/unmapped?bindingId=" + BINDING + "&entityType=OPERATOR";
    }

    private static String listPath() {
        return basePath() + "?bindingId=" + BINDING + "&entityType=OPERATOR";
    }

    private static String create(UUID memberId, String till) {
        return "{\"bindingId\":\"%s\",\"entityType\":\"OPERATOR\",\"horecaosEntityId\":\"%s\",\"externalEntityId\":\"%s\"}"
                .formatted(BINDING, memberId, till);
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult post(String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(basePath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(OWNER)))
                .andReturn();
    }

    private UUID memberId(UUID tenantId, String subject) {
        return jdbc.sql("SELECT id FROM iam.staff_members WHERE tenant_id = :t AND principal_subject = :s")
                .param("t", tenantId)
                .param("s", subject)
                .query(UUID.class)
                .single();
    }

    private void member(UUID tenantId, String subject, String first, String last) {
        tx.executeWithoutResult(status -> {
            registry.registerInvited(tenantId, subject, first, last, null, "fixture-inviter", "corr");
            registry.activate(tenantId, subject, first, last, "corr");
        });
    }

    private void seedTenant(UUID tenantId, UUID brandId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'pos operator mapping test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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
