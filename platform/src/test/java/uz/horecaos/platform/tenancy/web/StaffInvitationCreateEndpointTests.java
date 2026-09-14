package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
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
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0031 over ADR 0116: a double-submit of {@code POST .../staff/invitations}
 * under one {@code Idempotency-Key} returns the first outcome and creates
 * exactly one account, rather than two invitations for one typed request.
 *
 * <p>{@link StaffInvitationService} runs for real here -- unlike {@link
 * StaffInvitationControllerTests}, which mocks the owner side of this same
 * controller to prove the MVC chain alone -- because the property under test
 * is what {@link IdempotencyInterceptor} does in front of the real handler:
 * a mock would answer the same body on both calls regardless of whether the
 * interceptor replayed it or the handler ran twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffInvitationCreateEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a30-2000-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018f9a30-2000-7000-8000-0000000000c2");
    private static final String OWNER = "invitations-endpoint-owner-1";
    private static final String ORGANIZATION_ID = "org-endpoint-1";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // The idempotency record for this endpoint's response is envelope-encrypted
        // (inviteLink is @Classified) -- this context carries no kek outside the
        // "local" profile, the same reason CustomerControllerEndpointTests sets it.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private StubBeans.FakeStaffAccounts accounts;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE tenant.staff_invitations").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        roleRegistry.synchronize();
        accounts.created.clear();

        tenant();
        grantOwner();
    }

    @Test
    @DisplayName("a double-submit under one Idempotency-Key returns the first outcome and creates one account")
    void aDoubleSubmitCreatesExactlyOneAccount() throws Exception {
        String body = """
                {"firstName":"Aziza","lastName":"Karimova","phone":"+998901234567",
                 "roleCode":"location-staff","brandId":"%s","locationId":"%s","reason":"new hire"}
                """.formatted(BRAND, BRAND);

        MvcResult first = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/staff/invitations")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "invite-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult second = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/staff/invitations")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "invite-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getHeader(IdempotencyInterceptor.REPLAYED_HEADER))
                .as("the second call was answered from the record, never re-run")
                .isEqualTo("true");
        assertThat(second.getResponse().getContentAsString())
                .as("the same invitation id comes back both times")
                .isEqualTo(first.getResponse().getContentAsString());

        assertThat(accounts.created).as("the handler itself ran exactly once").hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.staff_invitations WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.grants WHERE tenant_id = :t AND principal_subject <> :owner")
                        .param("t", TENANT)
                        .param("owner", OWNER)
                        .query(Long.class)
                        .single())
                .as("one grant, not two")
                .isEqualTo(1L);
    }

    private void tenant() {
        jdbc.sql("""
                        INSERT INTO tenant.tenants
                            (id, slug, legal_name, display_name, default_currency, default_timezone,
                             status, keycloak_organization_id, version)
                        VALUES (:id, 'invite-endpoint-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                                'ACTIVE', :orgId, 0)
                        """).param("id", TENANT).param("orgId", ORGANIZATION_ID).update();
        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                        VALUES (:id, :tenantId, 'BRAND_A', 'brand-a', 'Brand A', 'ACTIVE', 0)
                        """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                        INSERT INTO tenant.locations
                            (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                        VALUES (:id, :tenantId, :brandId, 'LOC_A', 'loc-a', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                        """)
                .param("id", BRAND)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private void grantOwner() {
        jdbc.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform,
                             scope_type, scope_id, status, granted_by, reason, valid_from)
                        VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                                'ACTIVE', 'fixture', 'fixture', :validFrom)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("subject", OWNER)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.TENANT_OWNER))
                .param("validFrom", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
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
        OrganizationProvisioner stubOrganizations() {
            return new OrganizationProvisioner() {
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
                    return new MembershipRef(
                            command.organizationId(),
                            java.util.Objects.requireNonNull(command.existingSubjectId()),
                            false);
                }

                @Override
                public void setOrganizationEnabled(String organizationId, boolean enabled) {
                    throw new UnsupportedOperationException("not part of this fixture");
                }
            };
        }

        @Bean
        @Primary
        PlatformMailer stubMailer() {
            return new PlatformMailer() {
                @Override
                public MailOutcome send(OutgoingMail mail) {
                    return new MailOutcome.NotConfigured();
                }

                @Override
                public boolean configured() {
                    return false;
                }
            };
        }

        /** In-memory; no real Keycloak. Package-visible so the test can inspect {@link #created}. */
        static class FakeStaffAccounts implements StaffAccounts {

            final Map<String, StaffAccount> byId = new HashMap<>();
            final List<String> created = new ArrayList<>();

            @Override
            public Optional<StaffAccount> find(String subjectId) {
                return Optional.ofNullable(byId.get(subjectId));
            }

            @Override
            public StaffAccount create(String firstName, String lastName, String phone, @Nullable String email) {
                String subjectId = "endpoint-staff-" + UUID.randomUUID();
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
                StaffAccount current = byId.get(subjectId);
                if (current != null) {
                    byId.put(
                            subjectId,
                            new StaffAccount(current.subjectId(), current.email(), true, true, current.username()));
                }
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
