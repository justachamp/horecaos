package uz.horecaos.platform.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.application.GrantAuditListener;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.EntitlementGate;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.application.AccessCheckService.AccessCheckAnswer;
import uz.horecaos.platform.iam.application.AccessCheckService.Verdict;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Staff 9.5's "can she do this, and why" (ADR 0109).
 *
 * <p>The properties under test are the ones the brief names directly: the
 * yes/no answer must be the same call a real request's own enforcement makes
 * ({@link AuthorizationService#has}); a negative answer names every other
 * scope the subject holds the capability at, so it can teach the scope rule
 * rather than say a bare no; a caller's own grant must cover the scope being
 * asked about, so a narrower-than-tenant grant of {@code iam.grant.manage}
 * cannot probe a sibling scope; and the entitlement branch is distinct from,
 * and checked only after, the capability branch.
 */
class AccessCheckServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122002");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122003");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122004");

    private static final Instant CLOCK_INSTANT = Instant.parse("2026-09-12T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcAuthorizationService authorization;
    private GrantManagementService grants;
    private AccessCheckService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.roles CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
        authorization =
                new JdbcAuthorizationService(
                        jdbc,
                        clock,
                        () -> new AuthenticatedActor("no-request-actor-in-fixture", Set.of(), Map.of()),
                        tenantId -> uz.horecaos.platform.iam.api.TenantAvailability.OPERATING) {
                    @Override
                    public void evictGrants(String subject, @Nullable UUID tenantId) {
                        // no cache in this fixture
                    }
                };
        GrantAuditListener auditListener = new GrantAuditListener(
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()));
        grants = new GrantManagementService(
                jdbc,
                authorization,
                authorization,
                event -> {
                    if (event instanceof uz.horecaos.platform.iam.api.GrantChanged change) {
                        auditListener.onGrantChanged(change);
                    }
                },
                clock);
        service = new AccessCheckService(authorization, grants, List.of(), jdbc);

        new RoleRegistrySynchronizer(jdbc).synchronize();
        insertHierarchy();
    }

    @Test
    @DisplayName("ALLOWED is exactly what AuthorizationService.has answers for the same question")
    void allowedMatchesAuthorizationServiceHas() {
        insertGrant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        insertGrant("aziza", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);

        AccessCheckAnswer answer = service.check(
                "owner-1", "aziza", Capability.ORDER_APPROVE, ResourceScope.location(TENANT, BRAND, LOCATION), null);

        assertThat(answer.verdict()).isEqualTo(Verdict.ALLOWED);
        assertThat(authorization.has(
                        "aziza", Capability.ORDER_APPROVE, ResourceScope.location(TENANT, BRAND, LOCATION)))
                .as("this answer must never disagree with real enforcement")
                .isTrue();
    }

    @Test
    @DisplayName("a negative answer names the grant that almost worked, at the scope it actually covers")
    void insufficientCapabilityNamesTheGrantHeldElsewhere() {
        insertGrant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        insertGrant("aziza", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);

        // Her location grant does not reach up to the brand it belongs to —
        // "rights are given down, not sideways" is the exact rule staff-and-
        // access.md's own worked example teaches from this shape of answer.
        AccessCheckAnswer answer =
                service.check("owner-1", "aziza", Capability.ORDER_APPROVE, ResourceScope.brand(TENANT, BRAND), null);

        assertThat(answer.verdict()).isEqualTo(Verdict.INSUFFICIENT_CAPABILITY);
        assertThat(answer.heldElsewhere())
                .as("she is not powerless — the console can point at where this capability does work")
                .extracting(GrantManagementService.GrantView::scopeType)
                .containsExactly("LOCATION");
        assertThat(answer.heldElsewhere().getFirst().scopeId()).isEqualTo(LOCATION);
    }

    @Test
    @DisplayName("no grant anywhere answers INSUFFICIENT_CAPABILITY with an empty reason chain, not an error")
    void noGrantAtAllIsAnHonestNo() {
        insertGrant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT);

        AccessCheckAnswer answer =
                service.check("owner-1", "nobody", Capability.ORDER_APPROVE, ResourceScope.tenant(TENANT), null);

        assertThat(answer.verdict()).isEqualTo(Verdict.INSUFFICIENT_CAPABILITY);
        assertThat(answer.heldElsewhere()).isEmpty();
    }

    @Test
    @DisplayName("a caller holding the checking capability only at one brand cannot probe a sibling brand")
    void scopeContainmentRefusesACrossBrandProbe() {
        insertCustomRole(UUID.randomUUID(), TENANT, "brand-grant-manager", Capability.IAM_GRANT_MANAGE);
        insertGrant("brand-manager-1", "brand-grant-manager", "BRAND", BRAND);
        insertGrant("aziza", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);

        // Her own brand: allowed to ask.
        AccessCheckAnswer withinHerBrand = service.check(
                "brand-manager-1", "aziza", Capability.ORDER_APPROVE, ResourceScope.brand(TENANT, BRAND), null);
        assertThat(withinHerBrand.verdict()).isEqualTo(Verdict.INSUFFICIENT_CAPABILITY);

        // The sibling brand: her iam.grant.manage grant does not cover it, so
        // the question itself is refused before anything about "aziza" is read —
        // exactly the leak debugAccess's own PLATFORM_ADMIN guard exists to stop.
        assertThatThrownBy(() -> service.check(
                        "brand-manager-1",
                        "aziza",
                        Capability.ORDER_APPROVE,
                        ResourceScope.brand(TENANT, OTHER_BRAND),
                        null))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }

    @Test
    @DisplayName("heldElsewhere is filtered to scopes the caller's own iam.grant.manage authority covers")
    void heldElsewhereNeverLeaksAGrantTheCallerCannotSee() {
        // W03 adversarial-review finding: GrantManagementService.grantsCarrying
        // is scoped only by tenant, so before this filter existed, a brand
        // manager legitimately asking about her own brand (the "within her
        // brand" question passes requireScopeContainment, exactly like
        // scopeContainmentRefusesACrossBrandProbe's own within-brand case)
        // still received the subject's grants at every OTHER brand in the
        // tenant too -- a leak that test never exercised, because it never
        // gave the subject a second grant somewhere the caller cannot see.
        insertCustomRole(UUID.randomUUID(), TENANT, "brand-a-grant-manager", Capability.IAM_GRANT_MANAGE);
        insertGrant("brand-manager-1", "brand-a-grant-manager", "BRAND", BRAND);

        // aziza holds ORDER_APPROVE at both brands: one the caller manages,
        // one she does not.
        insertGrant("aziza", PlatformRole.LOCATION_STAFF, "BRAND", BRAND);
        insertGrant("aziza", PlatformRole.LOCATION_STAFF, "BRAND", OTHER_BRAND);

        AccessCheckAnswer answer = service.check(
                "brand-manager-1", "aziza", Capability.ORDER_APPROVE, ResourceScope.brand(TENANT, BRAND), null);

        assertThat(answer.verdict()).isEqualTo(Verdict.ALLOWED);
        assertThat(answer.heldElsewhere())
                .as("the OTHER_BRAND grant must never reach a caller who cannot manage OTHER_BRAND")
                .extracting(GrantManagementService.GrantView::scopeType)
                .containsExactly("BRAND");
        assertThat(answer.heldElsewhere().getFirst().scopeId())
                .as("the one row surfaced must be the caller's own brand, not the sibling one")
                .isEqualTo(BRAND);
    }

    @Test
    @DisplayName("a caller with no iam.grant.manage grant at all is refused outright")
    void aCallerWithNoGrantManageIsRefused() {
        insertGrant("aziza", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);

        assertThatThrownBy(() -> service.check(
                        "some-cashier", "aziza", Capability.ORDER_APPROVE, ResourceScope.tenant(TENANT), null))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }

    @Test
    @DisplayName("entitlement is checked only once the capability answer is ALLOWED, and folds into a third verdict")
    void entitlementRequiredIsADistinctThirdAnswer() {
        insertGrant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        insertGrant("aziza", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);
        AccessCheckService gatedService =
                new AccessCheckService(authorization, grants, List.of(new StubEntitlementGate(false)), jdbc);

        AccessCheckAnswer allowedButNotEntitled = gatedService.check(
                "owner-1",
                "aziza",
                Capability.ORDER_APPROVE,
                ResourceScope.location(TENANT, BRAND, LOCATION),
                "delivery.partner_integrations.enabled");

        assertThat(allowedButNotEntitled.verdict()).isEqualTo(Verdict.ENTITLEMENT_REQUIRED);
        assertThat(allowedButNotEntitled.entitlement()).isNotNull();

        // The capability check still runs first: a subject who never had the
        // capability is INSUFFICIENT_CAPABILITY, never ENTITLEMENT_REQUIRED,
        // matching a real request that would 403 before an entitlement is ever
        // consulted.
        AccessCheckAnswer neverHadCapability = gatedService.check(
                "owner-1",
                "nobody",
                Capability.ORDER_APPROVE,
                ResourceScope.tenant(TENANT),
                "delivery.partner_integrations.enabled");
        assertThat(neverHadCapability.verdict()).isEqualTo(Verdict.INSUFFICIENT_CAPABILITY);
    }

    @Test
    @DisplayName("with no EntitlementGate bean registered, an entitlement key is silently not checked")
    void noEntitlementGateMeansNoEntitlementBranch() {
        insertGrant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        insertGrant("aziza", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);

        AccessCheckAnswer answer = service.check(
                "owner-1",
                "aziza",
                Capability.ORDER_APPROVE,
                ResourceScope.location(TENANT, BRAND, LOCATION),
                "delivery.partner_integrations.enabled");

        assertThat(answer.verdict())
                .as("iam has no dependency on commercial; an absent gate must never turn ALLOWED into a refusal")
                .isEqualTo(Verdict.ALLOWED);
    }

    // ------------------------------------------------------------------- fixture

    private record StubEntitlementGate(boolean entitled) implements EntitlementGate {
        @Override
        public Optional<Answer> checkFeature(UUID tenantId, String entitlementKeyCode) {
            return Optional.of(new Answer(entitled, "A test feature", "/api/v1/control-plane/plans"));
        }
    }

    private void insertGrant(String subject, PlatformRole role, String scopeType, @Nullable UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform,
                     scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("validFrom", CLOCK_INSTANT.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .update();
    }

    private void insertGrant(String subject, String tenantDefinedRoleCode, String scopeType, UUID scopeId) {
        UUID roleId = jdbc.sql("SELECT id FROM iam.roles WHERE tenant_id = :t AND code = :code")
                .param("t", TENANT)
                .param("code", tenantDefinedRoleCode)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform,
                     scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, false, :scopeType, :scopeId,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("validFrom", CLOCK_INSTANT.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", roleId)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .update();
    }

    private void insertCustomRole(UUID roleId, UUID tenantId, String code, Capability capability) {
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :tenantId, :code, :code, 'BRAND', 'ACTIVE', false)
                """)
                .param("id", roleId)
                .param("tenantId", tenantId)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO iam.role_capabilities (role_id, capability_code)
                VALUES (:roleId, :capability)
                """)
                .param("roleId", roleId)
                .param("capability", capability.code())
                .update();
    }

    private void insertHierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-access-check', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND_A', 'brand-a', 'Brand A', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND_B', 'brand-b', 'Brand B', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC_A', 'loc-a', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }
}
