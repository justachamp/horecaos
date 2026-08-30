package uz.qoida.platform.iam.infrastructure.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import uz.qoida.platform.support.TestDatabase;
import uz.qoida.platform.iam.api.AuthorizationService;
import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.CapabilityView;
import uz.qoida.platform.iam.api.PlatformRole;
import uz.qoida.platform.iam.api.ResourceScope;

/**
 * ADR 0025 at the SQL boundary.
 *
 * <p>The scope-isolation tests are the reason this ADR exists: today
 * organization membership alone authorises reading every location's orders and
 * customers in a tenant.
 */
class JdbcAuthorizationServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b02");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b03");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b04");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b05");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b06");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private static JdbcClient jdbc;
    private MutableClock clock;
    private JdbcAuthorizationService authorization;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();

        // The role registry and the tenant hierarchy are built once, here, rather
        // than per test. They were in @BeforeEach, and RoleRegistrySynchronizer
        // issues roughly four hundred and fifty single statements over a
        // DriverManagerDataSource that opens a physical connection for every one —
        // 1.37 ms a connection measured against this image, so about six tenths of
        // a second per test spent reconnecting alone. Across twenty tests that was
        // a ~2.4 s floor each and some forty-five seconds of the build rebuilding a
        // byte-identical fixture nineteen extra times.
        //
        // Safe because none of it is test-mutable. No test writes tenant.tenants,
        // tenant.brands or tenant.locations. One test does insert into
        // iam.role_capabilities — removingACapabilityFromABundleRevokesItOnResynchronise
        // — and then re-synchronises as the very assertion it makes, so it restores
        // the canonical registry itself. What IS per test is iam.grants, and that is
        // still emptied in @BeforeEach.
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.roles CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        new RoleRegistrySynchronizer(jdbc).synchronize();
        insertHierarchy();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    private SettableActor actor;

    /** A current actor the test can move, so the platform-admin path is reachable. */
    private static final class SettableActor implements uz.qoida.platform.iam.api.CurrentActor {
        private uz.qoida.platform.iam.api.AuthenticatedActor value;

        void set(String subject, java.util.Set<String> globalRoles) {
            value = new uz.qoida.platform.iam.api.AuthenticatedActor(
                    subject, globalRoles, java.util.Map.of());
        }

        @Override
        public uz.qoida.platform.iam.api.AuthenticatedActor get() {
            return value;
        }
    }

    @BeforeEach
    void setUp() {
        // Only what a test can change. Grants are what every test writes; the role
        // registry and the hierarchy above it are not.
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        actor = new SettableActor();
        authorization = new JdbcAuthorizationService(jdbc, clock, actor);
    }

    @Test
    void aTenantGrantReachesEveryBrandAndLocationBeneathIt() {
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);

        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, locationScope())).isTrue();
        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, brandScope())).isTrue();
        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, ResourceScope.tenant(TENANT))).isTrue();
    }

    @Test
    void aLocationGrantReachesOnlyThatLocation() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);

        assertThat(authorization.has("staff-1", Capability.ORDER_APPROVE, locationScope())).isTrue();
        assertThat(authorization.has("staff-1", Capability.ORDER_APPROVE, siblingLocationScope()))
                .as("a grant at one location must never reach a sibling location")
                .isFalse();
        assertThat(authorization.has("staff-1", Capability.ORDER_READ, ResourceScope.tenant(TENANT)))
                .as("this is the gap ADR 0025 closes: a location employee must not read the whole tenant")
                .isFalse();
    }

    @Test
    void aBrandGrantNeverReachesASiblingBrand() {
        grant("manager-1", PlatformRole.BRAND_MANAGER, "BRAND", BRAND, TENANT);

        assertThat(authorization.has("manager-1", Capability.CATALOG_PUBLISH, brandScope())).isTrue();
        assertThat(authorization.has("manager-1", Capability.CATALOG_PUBLISH,
                ResourceScope.brand(TENANT, OTHER_BRAND))).isFalse();
    }

    @Test
    void aGrantInOneTenantIsInvisibleInAnother() {
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);

        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, ResourceScope.tenant(OTHER_TENANT)))
                .as("a multi-tenant principal resolves grants independently per tenant")
                .isFalse();
    }

    @Test
    void aRoleGrantsOnlyItsOwnCapabilities() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);

        assertThat(authorization.has("staff-1", Capability.ORDER_APPROVE, locationScope())).isTrue();
        assertThat(authorization.has("staff-1", Capability.REFUND_EXECUTE, locationScope())).isFalse();
        assertThat(authorization.has("staff-1", Capability.CATALOG_PUBLISH, locationScope())).isFalse();
    }

    @Test
    void requireThrowsNamingTheCapabilityAndScopeOnly() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);

        assertThatThrownBy(() ->
                authorization.require("staff-1", Capability.REFUND_EXECUTE, locationScope()))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class)
                .hasMessageContaining("refund.execute")
                .hasMessageContaining("LOCATION")
                .as("the denial must not disclose the grants or policy behind the decision")
                .hasMessageNotContaining("grant");
    }

    @Test
    void aPlatformAdminActsOnAFreshDeploymentWithNoGrants() {
        // The bootstrap case, and the reason this bypass exists. iam.grants is
        // empty -- GrantManagementService is its only writer and itself demands a
        // grant -- so without this the control plane of a fresh install stays
        // shut until somebody inserts a row by hand.
        actor.set("founder-1", java.util.Set.of("platform-admin"));

        assertThat(authorization.has("founder-1", Capability.IAM_GRANT_MANAGE,
                ResourceScope.tenant(TENANT)))
                .as("a platform admin can issue the first grant")
                .isTrue();
    }

    @Test
    void aPlatformAdminCannotAnswerForSomebodyElse() {
        // The bypass reads the CALLING actor. Asking whether another subject may
        // act is a question about grants, and only grants may answer it --
        // otherwise one admin's session would silently authorise every request
        // the platform makes on anyone's behalf.
        actor.set("founder-1", java.util.Set.of("platform-admin"));

        assertThat(authorization.has("someone-else", Capability.IAM_GRANT_MANAGE,
                ResourceScope.tenant(TENANT)))
                .as("the admin's role must not vouch for a different subject")
                .isFalse();
    }

    @Test
    void thePlatformAdminBypassConfersNothingButGrantManagement() {
        // The bypass exists to end a bootstrap deadlock, not to be a standing key
        // to the estate. A platform admin creates the first grant and then grants
        // themselves what they need, which is auditable and revocable; conferring
        // every capability here would restore exactly the state ADR 0025 ended,
        // where the realm role opened everything and the capability declaration
        // decided nothing.
        actor.set("founder-1", java.util.Set.of("platform-admin"));

        assertThat(authorization.has("founder-1", Capability.IAM_GRANT_MANAGE,
                ResourceScope.tenant(TENANT))).isTrue();

        assertThat(authorization.has("founder-1", Capability.TENANT_WRITE,
                ResourceScope.tenant(TENANT)))
                .as("everything other than grant management still needs a grant")
                .isFalse();
    }

    @Test
    void anOrdinaryPrincipalIsUnaffectedByTheBypass() {
        actor.set("staff-1", java.util.Set.of("tenant-admin"));

        assertThat(authorization.has("staff-1", Capability.IAM_GRANT_MANAGE,
                ResourceScope.tenant(TENANT)))
                .as("a realm role that is not platform-admin grants nothing")
                .isFalse();
    }

    @Test
    void theCapabilityViewDoesNotReportTheBypass() {
        actor.set("founder-1", java.util.Set.of("platform-admin"));

        // The view is a projection of grants for a frontend to render. Reporting
        // capabilities that no grant confers would make the view disagree with
        // the table it claims to describe, and a frontend would then render
        // controls whose authority cannot be audited or revoked here.
        assertThat(authorization.viewFor("founder-1", TENANT).capabilities())
                .as("a platform admin holding no grants has no grants to show")
                .isEmpty();
    }

    @Test
    void aPrincipalWithNoGrantsHasNothing() {
        assertThat(authorization.has("stranger", Capability.ORDER_READ, locationScope())).isFalse();
    }

    @Test
    void aRevokedGrantStopsApplyingImmediately() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        jdbc.sql("UPDATE iam.grants SET status = 'REVOKED' WHERE principal_subject = 'staff-1'").update();

        assertThat(authorization.has("staff-1", Capability.ORDER_APPROVE, locationScope())).isFalse();
    }

    @Test
    void aTimeBoundedGrantExpiresWithoutHumanAction() {
        UUID grantId = grant("temp-1", PlatformRole.SUPPORT_AGENT, "TENANT", TENANT, TENANT);
        jdbc.sql("UPDATE iam.grants SET valid_until = :until WHERE id = :id")
                .param("until", clock.instant().plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC))
                .param("id", grantId)
                .update();

        assertThat(authorization.has("temp-1", Capability.ORDER_READ, ResourceScope.tenant(TENANT))).isTrue();

        clock.advance(Duration.ofHours(3));

        assertThat(authorization.has("temp-1", Capability.ORDER_READ, ResourceScope.tenant(TENANT)))
                .as("support access should lapse on its own, not wait for someone to remember")
                .isFalse();
    }

    @Test
    void theCapabilityViewMatchesServerEnforcement() {
        grant("manager-1", PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION, TENANT);

        CapabilityView view = authorization.viewFor("manager-1", TENANT);

        assertThat(view.capabilities()).isNotEmpty();
        for (Capability capability : Capability.values()) {
            assertThat(authorization.has("manager-1", capability, locationScope()))
                    .as("the frontend view and server enforcement must agree on %s", capability.code())
                    .isEqualTo(view.capabilities().contains(capability));
        }
    }

    @Test
    void aGrantCannotReferenceALocationInAnotherTenant() {
        assertThatThrownBy(() -> grant("attacker", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, OTHER_TENANT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPlatformScopedGrantCannotCarryATenant() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id, status, granted_by, reason)
                VALUES (:id, :tenantId, 'x', :roleId, true, 'PLATFORM', NULL, 'ACTIVE', 'test', 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN))
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameGrantCannotBeIssuedTwiceWhileActive() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);

        assertThatThrownBy(() -> grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyCodeOwnedCapabilityIsProjectedForReporting() {
        assertThat(jdbc.sql("SELECT count(*) FROM iam.capability_registry_snapshot")
                .query(Long.class).single())
                .isEqualTo(Capability.values().length);
    }

    @Test
    void removingACapabilityFromABundleRevokesItOnResynchronise() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        jdbc.sql("""
                INSERT INTO iam.role_capabilities (role_id, capability_code)
                VALUES (:roleId, 'refund.execute')
                """)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.LOCATION_STAFF))
                .update();
        assertThat(authorization.has("staff-1", Capability.REFUND_EXECUTE, locationScope())).isTrue();

        new RoleRegistrySynchronizer(jdbc).synchronize();

        assertThat(authorization.has("staff-1", Capability.REFUND_EXECUTE, locationScope()))
                .as("code is the authority: a capability absent from the bundle must not survive in the database")
                .isFalse();
    }

    private ResourceScope locationScope() {
        return ResourceScope.location(TENANT, BRAND, LOCATION);
    }

    private ResourceScope siblingLocationScope() {
        return ResourceScope.location(TENANT, BRAND, SIBLING_LOCATION);
    }

    private ResourceScope brandScope() {
        return ResourceScope.brand(TENANT, BRAND);
    }

    private UUID grant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        if ("LOCATION".equals(scopeType)) {
            Long owned = jdbc.sql("SELECT count(*) FROM tenant.locations WHERE tenant_id = :t AND id = :id")
                    .param("t", tenantId).param("id", scopeId).query(Long.class).single();
            if (owned == 0) {
                throw new IllegalStateException("Location %s is not in tenant %s".formatted(scopeId, tenantId));
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test', 'test grant', :validFrom)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", clock.instant().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private static void insertHierarchy() {
        insertTenant(TENANT, "tenant-authz");
        insertTenant(OTHER_TENANT, "tenant-authz-other");
        insertBrand(BRAND, TENANT, "BRAND_A", "brand-a");
        insertBrand(OTHER_BRAND, TENANT, "BRAND_B", "brand-b");
        insertLocation(LOCATION, TENANT, BRAND, "LOC_A", "loc-a");
        insertLocation(SIBLING_LOCATION, TENANT, BRAND, "LOC_B", "loc-b");
    }

    private static void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private static void insertBrand(UUID id, UUID tenantId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """).param("id", id).param("tenantId", tenantId).param("code", code).param("slug", slug).update();
    }

    private static void insertLocation(UUID id, UUID tenantId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("tenantId", tenantId).param("brandId", brandId)
                .param("code", code).param("slug", slug).update();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
