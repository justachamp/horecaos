package uz.horecaos.platform.courier.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import org.springframework.context.annotation.Primary;
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
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code POST .../couriers} provisions the courier's own identity-provider
 * account instead of taking a Keycloak subject the operator typed (gap map
 * row {@code 3.3}) — exercised through the real HTTP stack, the same shape
 * {@code StaffInvitationCreateEndpointTests} uses to prove ADR 0116's
 * identical account-creation path for a colleague.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsCourierAccountProvisioningEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fd700-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd700-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd700-4000-7000-8000-0000000000c1");
    private static final UUID COURIER_TYPE = UUID.fromString("018fd700-4000-7000-8000-0000000000d1");
    private static final String ORGANIZATION_ID = "org-courier-provisioning-1";
    private static final String MANAGER = "courier-provisioning-manager";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the courier account provisioning endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // CourierEngagementService.register envelope-protects the courier's
        // full name (ADR 0029) -- this context carries no kek outside the
        // "local" profile, the same reason StaffInvitationCreateEndpointTests
        // sets it.
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
    private StubBeans.FakeStaffAccounts accounts;

    @Autowired
    @SuppressWarnings("NullAway")
    private StubBeans.RecordingOrganizations organizations;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        accounts.byId.clear();
        accounts.created.clear();
        organizations.ensured.clear();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, keycloak_organization_id, version)
                VALUES (:id, 'courier-provisioning', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', :orgId, 0)
                """).param("id", TENANT).param("orgId", ORGANIZATION_ID).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", COURIER_TYPE).param("t", TENANT).update();

        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.TENANT_ADMIN, TENANT);
    }

    @Test
    @DisplayName("registering a courier creates the identity-provider account and stores the account's own "
            + "subject as principalSubject — never a value the request body could name")
    void registeringACourierProvisionsTheAccount() throws Exception {
        String body = registrationBody("+998901112233", "azamat@example.uz");

        MvcResult result = mvc.perform(post(registerPath())
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "courier-register-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode response = json(result);
        UUID courierId = UUID.fromString(response.path("courierId").asText());

        assertThat(accounts.created)
                .as("exactly one identity-provider account was created")
                .hasSize(1);
        String subjectId = accounts.created.getFirst();

        String storedSubject = jdbc.sql("SELECT principal_subject FROM fulfillment.couriers WHERE id = :id")
                .param("id", courierId)
                .query(String.class)
                .single();
        assertThat(storedSubject)
                .as("the courier row carries the provisioned account's own subject, not a client-typed value")
                .isEqualTo(subjectId);

        assertThat(organizations.ensured)
                .as("the account was linked into the tenant's own organization so its token would "
                        + "carry the org claim ADR 0003 requires")
                .containsExactly(new StubBeans.EnsuredMembership(ORGANIZATION_ID, subjectId));
    }

    @Test
    @DisplayName("registering a second courier on a phone that already has an account reuses that identity "
            + "rather than creating a duplicate")
    void reregisteringOnAnExistingPhoneReusesTheAccount() throws Exception {
        StaffAccounts.StaffAccount existing = accounts.create("Existing", "Person", "+998901112233", null);
        accounts.created.clear();

        MvcResult result = mvc.perform(post(registerPath())
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "courier-register-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("+998901112233", null)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(accounts.created)
                .as("no new account was created for a phone that already has one")
                .isEmpty();

        UUID courierId = UUID.fromString(json(result).path("courierId").asText());
        String storedSubject = jdbc.sql("SELECT principal_subject FROM fulfillment.couriers WHERE id = :id")
                .param("id", courierId)
                .query(String.class)
                .single();
        assertThat(storedSubject).isEqualTo(existing.subjectId());
    }

    @Test
    @DisplayName("registration has no principalSubject field left to type at all — an extra one is ignored, "
            + "not trusted")
    void aClientSuppliedPrincipalSubjectIsIgnored() throws Exception {
        String body = """
                {"courierTypeId":"%s","firstName":"Bek","lastName":"Yusupov","phone":"+998901112244",
                 "principalSubject":"whatever-the-operator-typed",
                 "displayReference":"C-1002","engagedFrom":"2026-09-25","reason":"onboarding"}
                """.formatted(COURIER_TYPE);

        MvcResult result = mvc.perform(post(registerPath())
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "courier-register-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        UUID courierId = UUID.fromString(json(result).path("courierId").asText());
        String storedSubject = jdbc.sql("SELECT principal_subject FROM fulfillment.couriers WHERE id = :id")
                .param("id", courierId)
                .query(String.class)
                .single();
        assertThat(storedSubject)
                .as("an unknown JSON field is simply dropped by Jackson, never trusted as the identity")
                .isNotEqualTo("whatever-the-operator-typed");
        assertThat(accounts.created).hasSize(1);
        assertThat(storedSubject).isEqualTo(accounts.created.getFirst());
    }

    private static String registrationBody(String phone, @Nullable String email) {
        return """
                {"courierTypeId":"%s","firstName":"Malika","lastName":"Tosheva","phone":"%s",%s
                 "displayReference":"C-1001","engagedFrom":"2026-09-25","reason":"onboarding"}
                """.formatted(COURIER_TYPE, phone, email == null ? "" : "\"email\":\"" + email + "\",");
    }

    private static String registerPath() {
        return "/api/v1/operations/tenants/" + TENANT + "/couriers";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'courier provisioning endpoint test', :validFrom)
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
    static class StubBeans {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        @Bean
        @Primary
        FakeStaffAccounts stubStaffAccounts() {
            return new FakeStaffAccounts();
        }

        @Bean
        @Primary
        RecordingOrganizations stubOrganizations() {
            return new RecordingOrganizations();
        }

        record EnsuredMembership(String organizationId, String subjectId) {}

        /** Records every membership it links, so a test can assert the org claim was actually wired. */
        static class RecordingOrganizations implements OrganizationProvisioner {

            final List<EnsuredMembership> ensured = new ArrayList<>();

            @Override
            public OrganizationRef ensureOrganization(EnsureOrganization command) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public MembershipRef ensureMembership(EnsureMembership command) {
                String subjectId = java.util.Objects.requireNonNull(command.existingSubjectId());
                ensured.add(new EnsuredMembership(command.organizationId(), subjectId));
                return new MembershipRef(command.organizationId(), subjectId, false);
            }

            @Override
            public void setOrganizationEnabled(String organizationId, boolean enabled) {
                throw new UnsupportedOperationException("not part of this fixture");
            }
        }

        /** In-memory; no real Keycloak — mirrors {@code StaffInvitationCreateEndpointTests}'s own fake. */
        static class FakeStaffAccounts implements StaffAccounts {

            final Map<String, StaffAccount> byId = new HashMap<>();
            final List<String> created = new ArrayList<>();

            @Override
            public Optional<StaffAccount> find(String subjectId) {
                return Optional.ofNullable(byId.get(subjectId));
            }

            @Override
            public StaffAccount create(String firstName, String lastName, String phone, @Nullable String email) {
                String subjectId = "endpoint-courier-" + UUID.randomUUID();
                StaffAccount account = new StaffAccount(subjectId, email, false, false, phone);
                byId.put(subjectId, account);
                created.add(subjectId);
                return account;
            }

            @Override
            public Optional<StaffAccount> findByPhone(String phone) {
                return byId.values().stream()
                        .filter(a -> phone.equals(a.username()))
                        .findFirst();
            }

            @Override
            public void completeSetup(String subjectId, String firstName, String lastName, String password) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public void setPassword(String subjectId, String password) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public void logoutEverywhere(String subjectId) {
                throw new UnsupportedOperationException("not part of this fixture");
            }
        }
    }
}
