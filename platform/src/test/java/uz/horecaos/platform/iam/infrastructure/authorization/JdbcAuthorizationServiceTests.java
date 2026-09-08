package uz.horecaos.platform.iam.infrastructure.authorization;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.TenantAvailability;
import uz.horecaos.platform.support.TestDatabase;

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

    private static JdbcClient jdbc;
    private MutableClock clock;
    private JdbcAuthorizationService authorization;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();

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
    private static final class SettableActor implements uz.horecaos.platform.iam.api.CurrentActor {
        // A real, inert actor rather than left uninitialized: CurrentActor#get()
        // promises @NonNull (its one production implementation, JwtCurrentActor,
        // never returns null — it throws instead), and every test here that
        // reaches a path depending on the value calls set() first. This default
        // is never observed; it exists so the field has an honest initial value
        // rather than a null the interface does not allow.
        private uz.horecaos.platform.iam.api.AuthenticatedActor value =
                new uz.horecaos.platform.iam.api.AuthenticatedActor(
                        "no-actor-set-in-fixture", java.util.Set.of(), java.util.Map.of());

        void set(String subject, java.util.Set<String> globalRoles) {
            value = new uz.horecaos.platform.iam.api.AuthenticatedActor(subject, globalRoles, java.util.Map.of());
        }

        @Override
        public uz.horecaos.platform.iam.api.AuthenticatedActor get() {
            return value;
        }
    }

    /**
     * How reachable each tenant is, as {@code JdbcTenantSuspensionLookup} would
     * answer it. A mutable map rather than a fixed value so a test can suspend or
     * archive a tenant mid-test and see the same grants stop applying.
     */
    private java.util.Map<java.util.UUID, TenantAvailability> availability;

    @BeforeEach
    void setUp() {
        // Only what a test can change. Grants are what every test writes; the role
        // registry and the hierarchy above it are not.
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        actor = new SettableActor();
        availability = new java.util.HashMap<>();
        authorization = new JdbcAuthorizationService(
                jdbc, clock, actor, tenantId -> availability.getOrDefault(tenantId, TenantAvailability.OPERATING));
    }

    @Test
    @DisplayName("a suspended tenant may look and may not touch")
    void suspensionWithdrawsTheWriteGrantsAndLeavesTheReadOnes() {
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);

        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, ResourceScope.tenant(TENANT)))
                .isTrue();

        availability.put(TENANT, TenantAvailability.READ_ONLY);

        // Nothing about the grants changed -- they are still ACTIVE rows on
        // ACTIVE roles inside their validity window, which is why SELECT_GRANTS
        // could never see this and why suspending a tenant used to stop nobody.
        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, ResourceScope.tenant(TENANT)))
                .as("approving an order is a change, and a suspended tenant makes none")
                .isFalse();
        assertThat(authorization.has("staff-1", Capability.ORDER_APPROVE, locationScope()))
                .as("a location grant is inside the suspended tenant too -- or every location "
                        + "employee would keep working")
                .isFalse();
        assertThat(authorization.has("owner-1", Capability.ORDER_READ, ResourceScope.tenant(TENANT)))
                .as("the owner's answer of 2026-09-08: a suspended tenant keeps read access, so it "
                        + "can still see the orders it took before the lights went out")
                .isTrue();
        assertThatThrownBy(() -> authorization.require("owner-1", Capability.ORDER_APPROVE, locationScope()))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }

    @Test
    @DisplayName("reading is not the same as taking: a suspended tenant may not unmask a customer")
    void suspensionWithdrawsTheCapabilitiesThatTakeDataOutEvenThoughTheyOnlyRead() {
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        availability.put(TENANT, TenantAvailability.READ_ONLY);

        assertThat(authorization.has("owner-1", Capability.CUSTOMER_READ, ResourceScope.tenant(TENANT)))
                .as("the masked projection is a read")
                .isTrue();
        assertThat(authorization.has("owner-1", Capability.COURIER_TRACK_REVEAL, ResourceScope.tenant(TENANT)))
                .as("revealing a phone number is an export wearing a read's clothes, and the action "
                        + "segment is 'track.reveal' rather than a '.read' for exactly this reason")
                .isFalse();
    }

    @Test
    @DisplayName("an archived tenant keeps nothing, not even a read")
    void archivingWithdrawsEveryTenantScopedGrantIncludingTheReadOnes() {
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        availability.put(TENANT, TenantAvailability.CLOSED);

        assertThat(authorization.has("owner-1", Capability.ORDER_READ, ResourceScope.tenant(TENANT)))
                .as("the owner's answer of 2026-09-08: archived means no read access at all -- this "
                        + "is the assertion that separates CLOSED from READ_ONLY")
                .isFalse();
        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, ResourceScope.tenant(TENANT)))
                .isFalse();
    }

    @Test
    @DisplayName("neither state is a one-way door: a platform grant still reaches both")
    void aPlatformGrantSurvivesSuspensionAndArchival() {
        platformGrant("platform-1");
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);

        for (TenantAvailability state :
                new TenantAvailability[] {TenantAvailability.READ_ONLY, TenantAvailability.CLOSED}) {
            availability.put(TENANT, state);
            assertThat(authorization.has("platform-1", Capability.TENANT_WRITE, ResourceScope.tenant(TENANT)))
                    .as(
                            "whoever lifts a %s has to be able to act on the tenant first; if this is "
                                    + "false the platform has locked itself out of its own customer",
                            state)
                    .isTrue();
            assertThat(authorization.has("owner-1", Capability.TENANT_WRITE, ResourceScope.tenant(TENANT)))
                    .as("the same capability, at the same scope, from inside the tenant, under %s", state)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("another tenant is unaffected")
    void suspendingOneTenantLeavesAnotherAlone() {
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        availability.put(java.util.UUID.randomUUID(), TenantAvailability.READ_ONLY);

        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, ResourceScope.tenant(TENANT)))
                .as("a suspension must name the tenant it suspends")
                .isTrue();
    }

    @Test
    @DisplayName("the capability view offers what the tenant may still do, and no more")
    void theViewOfASuspendedTenantOffersItsReadsAndNoneOfItsWrites() {
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        assertThat(authorization.viewFor("owner-1", TENANT).capabilities()).contains(Capability.ORDER_APPROVE);

        availability.put(TENANT, TenantAvailability.READ_ONLY);

        var offered = authorization.viewFor("owner-1", TENANT).capabilities();
        assertThat(offered)
                .as("a frontend that renders a write here offers an action the platform then refuses")
                .doesNotContain(Capability.ORDER_APPROVE)
                .contains(Capability.ORDER_READ);
        assertThat(offered).allMatch(Capability::isRead);

        availability.put(TENANT, TenantAvailability.CLOSED);
        assertThat(authorization.viewFor("owner-1", TENANT).capabilities())
                .as("an archived tenant is offered nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("the read capabilities are pinned, so a new one cannot quietly become readable")
    void theReadCapabilitiesArePinned() {
        // The rule in Capability.isRead() is a suffix test, and a suffix test is
        // exactly how a capability that takes data out ends up classified as a
        // look. This list is the review gate: adding a capability whose action
        // ends in read fails here until somebody writes it down on purpose.
        java.util.Set<Capability> pinned = java.util.EnumSet.of(
                Capability.TENANT_READ,
                Capability.BRAND_READ,
                Capability.LOCATION_READ,
                Capability.LEGAL_ENTITY_READ,
                Capability.CHANNEL_READ,
                Capability.CATALOG_READ,
                Capability.MEDIA_READ,
                Capability.INVENTORY_READ,
                Capability.PRICING_READ,
                Capability.ORDER_READ,
                Capability.REVIEW_READ,
                Capability.PAYMENT_READ,
                Capability.FISCAL_DOCUMENT_READ,
                Capability.DELIVERY_PLAN_READ,
                Capability.DELIVERY_ZONE_READ,
                Capability.DELIVERY_TARIFF_READ,
                Capability.DELIVERY_FEE_EVIDENCE_READ,
                Capability.COURIER_POSITION_READ,
                Capability.KITCHEN_TICKET_READ,
                Capability.RESERVATION_READ,
                Capability.DINEIN_SESSION_READ,
                Capability.MARKETPLACE_LIVENESS_READ,
                Capability.CUSTOMER_READ,
                Capability.POS_SYNC_READ,
                Capability.POS_EXPORT_READ,
                Capability.INTEGRATION_FAILURE_READ,
                Capability.NOTIFICATION_READ,
                Capability.AUDIENCE_READ,
                Capability.COMMERCIAL_PLAN_READ,
                Capability.COMMERCIAL_USAGE_READ,
                Capability.LOYALTY_READ,
                Capability.REFERRAL_READ,
                Capability.REPORTING_READ,
                Capability.AUDIT_READ,
                Capability.MIGRATION_READ,
                Capability.COURIER_SHIFT_READ,
                Capability.COURIER_READ,
                Capability.COURIER_RATECARD_READ,
                Capability.COURIER_LEDGER_READ,
                Capability.DELIVERY_COST_READ,
                Capability.COURIER_CASH_READ,
                Capability.COURIER_SETTLEMENT_READ,
                Capability.PARTNER_INVOICE_READ,
                Capability.TERMS_READ,
                Capability.VOICE_PRESENCE_READ,
                Capability.VOICE_SCREEN_POP_READ,
                Capability.VOICE_CALL_LOG_READ);

        java.util.Set<Capability> classified = java.util.Arrays.stream(Capability.values())
                .filter(Capability::isRead)
                .collect(java.util.stream.Collectors.toCollection(() -> java.util.EnumSet.noneOf(Capability.class)));

        assertThat(classified)
                .as("a capability became a read, or stopped being one, without anybody deciding that "
                        + "a suspended tenant may exercise it")
                .isEqualTo(pinned);
    }

    @Test
    void aTenantGrantReachesEveryBrandAndLocationBeneathIt() {
        grant("owner-1", PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);

        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, locationScope()))
                .isTrue();
        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, brandScope()))
                .isTrue();
        assertThat(authorization.has("owner-1", Capability.ORDER_APPROVE, ResourceScope.tenant(TENANT)))
                .isTrue();
    }

    @Test
    void aLocationGrantReachesOnlyThatLocation() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);

        assertThat(authorization.has("staff-1", Capability.ORDER_APPROVE, locationScope()))
                .isTrue();
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

        assertThat(authorization.has("manager-1", Capability.CATALOG_PUBLISH, brandScope()))
                .isTrue();
        assertThat(authorization.has("manager-1", Capability.CATALOG_PUBLISH, ResourceScope.brand(TENANT, OTHER_BRAND)))
                .isFalse();
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

        assertThat(authorization.has("staff-1", Capability.ORDER_APPROVE, locationScope()))
                .isTrue();
        assertThat(authorization.has("staff-1", Capability.REFUND_EXECUTE, locationScope()))
                .isFalse();
        assertThat(authorization.has("staff-1", Capability.CATALOG_PUBLISH, locationScope()))
                .isFalse();
    }

    @Test
    void requireThrowsNamingTheCapabilityAndScopeOnly() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);

        assertThatThrownBy(() -> authorization.require("staff-1", Capability.REFUND_EXECUTE, locationScope()))
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

        assertThat(authorization.has("founder-1", Capability.IAM_GRANT_MANAGE, ResourceScope.tenant(TENANT)))
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

        assertThat(authorization.has("someone-else", Capability.IAM_GRANT_MANAGE, ResourceScope.tenant(TENANT)))
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

        assertThat(authorization.has("founder-1", Capability.IAM_GRANT_MANAGE, ResourceScope.tenant(TENANT)))
                .isTrue();

        assertThat(authorization.has("founder-1", Capability.TENANT_WRITE, ResourceScope.tenant(TENANT)))
                .as("everything other than grant management still needs a grant")
                .isFalse();
    }

    @Test
    void anOrdinaryPrincipalIsUnaffectedByTheBypass() {
        actor.set("staff-1", java.util.Set.of("tenant-admin"));

        assertThat(authorization.has("staff-1", Capability.IAM_GRANT_MANAGE, ResourceScope.tenant(TENANT)))
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
        assertThat(authorization.has("stranger", Capability.ORDER_READ, locationScope()))
                .isFalse();
    }

    @Test
    void aRevokedGrantStopsApplyingImmediately() {
        grant("staff-1", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        // V0127's ck_grant_revocation_pair requires the trio whenever status is REVOKED.
        jdbc.sql("""
                UPDATE iam.grants
                   SET status = 'REVOKED', revoked_at = now(), revoked_by = 'test', revoked_reason = 'test'
                 WHERE principal_subject = 'staff-1'
                """).update();

        assertThat(authorization.has("staff-1", Capability.ORDER_APPROVE, locationScope()))
                .isFalse();
    }

    @Test
    void aTimeBoundedGrantExpiresWithoutHumanAction() {
        UUID grantId = grant("temp-1", PlatformRole.SUPPORT_AGENT, "TENANT", TENANT, TENANT);
        jdbc.sql("UPDATE iam.grants SET valid_until = :until WHERE id = :id")
                .param("until", clock.instant().plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC))
                .param("id", grantId)
                .update();

        assertThat(authorization.has("temp-1", Capability.ORDER_READ, ResourceScope.tenant(TENANT)))
                .isTrue();

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
                        .query(Long.class)
                        .single())
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
        assertThat(authorization.has("staff-1", Capability.REFUND_EXECUTE, locationScope()))
                .isTrue();

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

    /**
     * A PLATFORM-scoped grant, which the shared helper cannot express: a
     * platform grant carries no tenant and no scope id, and
     * {@code aPlatformScopedGrantCannotCarryATenant} is the constraint that
     * says so.
     */
    private void platformGrant(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test', 'test grant', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN))
                .param("validFrom", clock.instant().minusSeconds(60).atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    private UUID grant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        if ("LOCATION".equals(scopeType)) {
            Long owned = jdbc.sql("SELECT count(*) FROM tenant.locations WHERE tenant_id = :t AND id = :id")
                    .param("t", tenantId)
                    .param("id", scopeId)
                    .query(Long.class)
                    .single();
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
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private static void insertLocation(UUID id, UUID tenantId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
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
