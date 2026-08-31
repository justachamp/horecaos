package uz.horecaos.platform.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.application.GrantAuditListener;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0025 grant management.
 *
 * <p>The escalation tests are the reason this is a service rather than a plain
 * insert: a grant API that can confer more than its caller holds is a
 * privilege-escalation path wearing an audit trail.
 */
class GrantManagementServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121201");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121202");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121203");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121204");
    private static final String OWNER = "owner-1";

    /**
     * A second tenant that has defined a role of its own, and the role.
     *
     * <p>{@code iam.roles.tenant_id} is nullable by design — {@code
     * ck_role_ownership} says a platform role is owned by nobody and a
     * tenant-defined role must name its tenant — and the comment on that CHECK
     * claims "one tenant can never see another's custom role". These two
     * constants exist to ask whether that is true.
     */
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1212f1");

    private static final UUID OTHER_TENANTS_ROLE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1212f2");
    private static final String OTHER_TENANTS_ROLE_CODE = "their-closer";

    /** A role this tenant defined for itself, which it may legitimately grant. */
    private static final UUID OWN_ROLE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1212f3");

    private static final String OWN_ROLE_CODE = "our-closer";

    /**
     * The service reads through a fixed clock, so fixtures must use the same
     * instant. Using the database's now() puts a grant outside its own validity
     * window whenever wall-clock time differs from the test clock.
     */
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-08-20T10:00:00Z");

    private static TestDatabase.Handle db;

    private static final String PLATFORM_GRANTER = "platform-granter-1";

    private JdbcClient jdbc;
    private JdbcAuthorizationService authorization;
    private GrantManagementService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        // Deliberately not the caching proxy: these tests are about the grant
        // rules, and a cache would hide a grant that was written but not yet
        // visible, turning a rule failure into a flake.
        authorization = new JdbcAuthorizationService(
                jdbc, clock, () -> new AuthenticatedActor("no-request-actor-in-fixture", Set.of(), Map.of())) {
            @Override
            public void evictGrants(String subject, @Nullable UUID tenantId) {
                // no cache in this fixture
            }
        };
        // Stands in for Spring's BEFORE_COMMIT dispatch, so the published event
        // still reaches the audit listener that records it in production.
        GrantAuditListener auditListener = new GrantAuditListener(
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()));
        service = new GrantManagementService(
                jdbc,
                authorization,
                authorization,
                event -> {
                    if (event instanceof uz.horecaos.platform.iam.api.GrantChanged change) {
                        auditListener.onGrantChanged(change);
                    }
                },
                clock);

        new RoleRegistrySynchronizer(jdbc).synchronize();
        insertHierarchy();
        insertGrant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        insertPlatformGrant(PLATFORM_GRANTER, PlatformRole.PLATFORM_ADMIN);
    }

    @Test
    void anOwnerGrantsALocationRoleWithinItsTenant() {
        UUID grantId = service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1",
                        "location-staff",
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        "New hire at Chilonzor",
                        null),
                OWNER);

        assertThat(grantId).isNotNull();
        assertThat(authorization.has(
                        "staff-1", Capability.ORDER_APPROVE, ResourceScope.location(TENANT, BRAND, LOCATION)))
                .isTrue();
    }

    @Test
    void aGrantIsAuditedAsASecurityFact() {
        service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", "location-staff", ResourceScope.location(TENANT, BRAND, LOCATION), "New hire", null),
                OWNER);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'iam.grant.granted' AND audit_class = 'SECURITY'
                   AND actor_subject = 'owner-1'
                """).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aGranterCannotConferMoreThanItHolds() {
        insertGrant("manager-1", PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
        String manager = "manager-1";

        assertThatThrownBy(() -> service.grant(
                        new GrantManagementService.GrantCommand(
                                "accomplice", "tenant-owner", ResourceScope.tenant(TENANT), "escalation attempt", null),
                        manager))
                .as("a grant API that can confer more than its caller holds is an escalation path")
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }

    @Test
    void aGranterCannotReachASiblingBrand() {
        insertGrant("brand-manager-1", PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        String brandManager = "brand-manager-1";

        assertThatThrownBy(() -> service.grant(
                        new GrantManagementService.GrantCommand(
                                "someone",
                                "brand-manager",
                                ResourceScope.brand(TENANT, OTHER_BRAND),
                                "sideways attempt",
                                null),
                        brandManager))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }

    @Test
    void platformAdminIsNeverGrantableThroughThisApi() {
        assertThatThrownBy(() -> service.grant(
                        new GrantManagementService.GrantCommand(
                                "someone", "platform-admin", ResourceScope.tenant(TENANT), "escalation attempt", null),
                        OWNER))
                .as("a tenant-facing API conferring platform.admin makes the tenant boundary decorative")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Keycloak");
    }

    @Test
    void revokingTakesEffectImmediately() {
        UUID grantId = service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", "location-staff", ResourceScope.location(TENANT, BRAND, LOCATION), "New hire", null),
                OWNER);
        assertThat(authorization.has(
                        "staff-1", Capability.ORDER_APPROVE, ResourceScope.location(TENANT, BRAND, LOCATION)))
                .isTrue();

        assertThat(service.revoke(TENANT, grantId, OWNER, "Left the company")).isTrue();

        assertThat(authorization.has(
                        "staff-1", Capability.ORDER_APPROVE, ResourceScope.location(TENANT, BRAND, LOCATION)))
                .as("a revoked grant must stop working now, not when a cache expires")
                .isFalse();
    }

    @Test
    void revokingAnAlreadyRevokedGrantReportsNoChange() {
        UUID grantId = service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", "location-staff", ResourceScope.location(TENANT, BRAND, LOCATION), "New hire", null),
                OWNER);
        service.revoke(TENANT, grantId, OWNER, "Left");

        assertThat(service.revoke(TENANT, grantId, OWNER, "Left again")).isFalse();
    }

    @Test
    void aGrantCannotBeRevokedThroughAnotherTenant() {
        UUID grantId = service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", "location-staff", ResourceScope.location(TENANT, BRAND, LOCATION), "New hire", null),
                OWNER);

        // The attacker holds IAM_GRANT_MANAGE in their own tenant and knows the
        // victim's grant id, which is an opaque UUID that travels through support
        // tickets, exports and logs. The capability check passes -- it authorises
        // the attacker's own tenant -- so the tenant predicate on the statement is
        // the only thing standing between them and the victim's staff.
        UUID attackerTenant = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1212ff");

        assertThat(service.revoke(attackerTenant, grantId, OWNER, "not mine to revoke"))
                .as("a grant id from another tenant must not be revocable")
                .isFalse();

        assertThat(authorization.has(
                        "staff-1", Capability.ORDER_APPROVE, ResourceScope.location(TENANT, BRAND, LOCATION)))
                .as("the victim's grant must still work")
                .isTrue();
    }

    @Test
    void anUnknownRoleIsRejected() {
        assertThatThrownBy(() -> service.grant(
                        new GrantManagementService.GrantCommand(
                                "someone", "wizard", ResourceScope.tenant(TENANT), "typo", null),
                        OWNER))
                .isInstanceOf(GrantManagementService.NoSuchRoleException.class)
                .hasMessage("No such role");
    }

    /**
     * The hole, closed where it actually was: the database.
     *
     * <p>{@code iam.grants.fk_grant_role} references {@code iam.roles (id)} on
     * the id alone, so nothing in the schema related the role's owner to the
     * grant's tenant. This test writes the row the way the paths that do not go
     * through {@link GrantManagementService} write it — a repair script, a
     * background job, a second write path nobody has written yet — because a rule
     * that lives only in a service holds only until the second writer.
     *
     * <p>Before V0086 this insert was accepted and
     * {@code authorization.has("intruder", ORDER_CANCEL, tenant(TENANT))} then
     * answered {@code true}: one tenant's private role conferring capability
     * inside another.
     */
    @Test
    void aGrantCannotNameAnotherTenantsCustomRole() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform,
                     scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, 'intruder', :roleId, false, 'TENANT', :scopeId,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                        .param("id", UUID.randomUUID())
                        .param("tenantId", TENANT)
                        .param("roleId", OTHER_TENANTS_ROLE)
                        .param("scopeId", TENANT)
                        .param("validFrom", CLOCK_INSTANT.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                        .update())
                .as("a grant is the authorization primitive; it must not name a role "
                        + "the database says belongs to somebody else")
                .isInstanceOf(DataIntegrityViolationException.class)
                // Named, because role_is_platform is NOT NULL and omitting it would
                // also throw DataIntegrityViolationException — and this test would
                // then pass having proved nothing about whose role it is. It
                // declares `false` above for the same reason: the claim has to be
                // made before the key can refuse it.
                .hasMessageContaining("fk_grant_role");

        assertThat(authorization.has("intruder", Capability.ORDER_CANCEL, ResourceScope.tenant(TENANT)))
                .as("another tenant's private role must not confer capability here")
                .isFalse();
    }

    /**
     * The same refusal through the service, which is the path a caller can reach.
     *
     * <p>The other tenant's role code is a real, active role — this test would
     * pass just as well if the resolution rule refused every code, so
     * {@link #aTenantMayGrantARoleItDefinedItself} is what makes it mean
     * something: the same shape of code, in the caller's own tenant, is granted.
     */
    @Test
    void theServiceRefusesToGrantAnotherTenantsCustomRole() {
        assertThatThrownBy(() -> service.grant(
                        new GrantManagementService.GrantCommand(
                                "staff-1",
                                OTHER_TENANTS_ROLE_CODE,
                                ResourceScope.tenant(TENANT),
                                "borrowing their role",
                                null),
                        OWNER))
                .isInstanceOf(GrantManagementService.NoSuchRoleException.class);

        assertThat(authorization.has("staff-1", Capability.ORDER_CANCEL, ResourceScope.tenant(TENANT)))
                .isFalse();
    }

    /**
     * The refusal must be the one a caller gets for a role that does not exist.
     *
     * <p>Any difference at all — status, code, message, exception type — turns
     * this endpoint into an oracle for another tenant's private role names, which
     * is the reason {@code requireRealScope} answers 404 rather than 403.
     */
    @Test
    void refusingAnotherTenantsRoleIsIndistinguishableFromNoSuchRole() {
        Throwable foreign = catchThrowable(() -> service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", OTHER_TENANTS_ROLE_CODE, ResourceScope.tenant(TENANT), "borrowing their role", null),
                OWNER));
        Throwable absent = catchThrowable(() -> service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", "no-such-role-anywhere", ResourceScope.tenant(TENANT), "typo", null),
                OWNER));

        assertThat(foreign).isInstanceOf(ApiException.class);
        assertThat(absent).isInstanceOf(ApiException.class);
        assertThat(foreign.getClass()).isEqualTo(absent.getClass());
        assertThat(foreign.getMessage())
                .as("a different message is a different answer, and two answers are an oracle")
                .isEqualTo(absent.getMessage());
        assertThat(((ApiException) foreign).errorCode())
                .isEqualTo(((ApiException) absent).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** The other half of the disjunction a composite key could not express. */
    @Test
    void aPlatformRoleIsStillGrantable() {
        UUID grantId = service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", "location-staff", ResourceScope.location(TENANT, BRAND, LOCATION), "New hire", null),
                OWNER);

        assertThat(grantId).isNotNull();
        assertThat(authorization.has(
                        "staff-1", Capability.ORDER_APPROVE, ResourceScope.location(TENANT, BRAND, LOCATION)))
                .as("a platform role has tenant_id NULL and every tenant may name it")
                .isTrue();
    }

    @Test
    void aTenantMayGrantARoleItDefinedItself() {
        UUID grantId = service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", OWN_ROLE_CODE, ResourceScope.tenant(TENANT), "Our own bundle", null),
                OWNER);

        assertThat(grantId).isNotNull();
        assertThat(jdbc.sql("SELECT role_id FROM iam.grants WHERE id = :id")
                        .param("id", grantId)
                        .query(UUID.class)
                        .single())
                .as("the grant names the tenant's own role row, not a platform one")
                .isEqualTo(OWN_ROLE);
        assertThat(authorization.has("staff-1", Capability.ORDER_CANCEL, ResourceScope.tenant(TENANT)))
                .as("a role the tenant defined confers the capabilities it lists")
                .isTrue();
    }

    /**
     * A tenant-defined role is subject to the escalation rule too.
     *
     * <p>The resolution rule and the "confer only what you hold" rule compose:
     * a role being nameable is not a role being grantable.
     */
    @Test
    void aTenantsOwnRoleStillCannotConferMoreThanTheGranterHolds() {
        insertCustomRole(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1212f4"), TENANT, "our-auditor");
        jdbc.sql("""
                INSERT INTO iam.role_capabilities (role_id, capability_code)
                VALUES (:roleId, :capability)
                """)
                .param("roleId", UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1212f4"))
                .param("capability", Capability.COURIER_TRACK_REVEAL.code())
                .update();

        assertThatThrownBy(() -> service.grant(
                        new GrantManagementService.GrantCommand(
                                "staff-1",
                                "our-auditor",
                                ResourceScope.tenant(TENANT),
                                "escalation attempt wearing a custom role",
                                null),
                        OWNER))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }

    /**
     * The role cannot be moved out from under a grant either.
     *
     * <p>A write-time check on {@code iam.grants} alone would let the invariant
     * lapse without a write to {@code iam.grants} at all.
     */
    @Test
    void aRoleCannotBeReparentedOutFromUnderItsGrants() {
        service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", OWN_ROLE_CODE, ResourceScope.tenant(TENANT), "Our own bundle", null),
                OWNER);

        assertThatThrownBy(() -> jdbc.sql("UPDATE iam.roles SET tenant_id = :other WHERE id = :id")
                        .param("other", OTHER_TENANT)
                        .param("id", OWN_ROLE)
                        .update())
                .as("re-parenting the role would strand a grant in a tenant that no "
                        + "longer owns it, which is the same breach by another route")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listingShowsOnlyActiveGrantsForTheTenant() {
        UUID kept = service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-1", "location-staff", ResourceScope.location(TENANT, BRAND, LOCATION), "Kept", null),
                OWNER);
        UUID revoked = service.grant(
                new GrantManagementService.GrantCommand(
                        "staff-2", "location-staff", ResourceScope.location(TENANT, BRAND, LOCATION), "Revoked", null),
                OWNER);
        service.revoke(TENANT, revoked, OWNER, "Left");

        assertThat(service.listForTenant(TENANT))
                .extracting(GrantManagementService.GrantView::id)
                .contains(kept)
                .doesNotContain(revoked);
    }

    // ----------------------------------------------------------------- Gap A: grant()/revoke() already work at
    // PLATFORM scope

    /**
     * {@code grant} needs no change for {@code PLATFORM} scope: {@code
     * resolveRole} resolves a {@link PlatformRole} without a tenant, and a
     * {@code PLATFORM}-scope grant already covers itself. What Gap A actually
     * adds sits in {@code audit.application.PlatformGrantService} (ADR 0027's
     * maker-checker) and {@code audit.web.PlatformGrantController} (the HTTP
     * surface) — see {@code PlatformGrantServiceTests} for the approval-gated
     * behaviour this file deliberately does not re-test.
     */
    @Test
    void grantAlreadySupportsPlatformScope() {
        UUID grantId = service.grant(
                new GrantManagementService.GrantCommand(
                        "new-support-1",
                        "platform-support",
                        ResourceScope.platform(),
                        "onboarding a support agent",
                        null),
                PLATFORM_GRANTER);

        assertThat(grantId).isNotNull();
        assertThat(authorization.has("new-support-1", Capability.TENANT_READ, ResourceScope.platform()))
                .isTrue();
    }

    @Test
    void aNonPlatformAdminCannotGrantAtPlatformScope() {
        assertThatThrownBy(() -> service.grant(
                        new GrantManagementService.GrantCommand(
                                "someone", "platform-support", ResourceScope.platform(), "escalation attempt", null),
                        OWNER))
                .as("OWNER holds only a TENANT-scope grant; PLATFORM is a different scope entirely")
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }

    @Test
    void platformAdminIsStillNeverGrantableAtPlatformScope() {
        assertThatThrownBy(() -> service.grant(
                        new GrantManagementService.GrantCommand(
                                "someone", "platform-admin", ResourceScope.platform(), "escalation attempt", null),
                        PLATFORM_GRANTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Keycloak");
    }

    /**
     * {@code revoke}'s {@code IS NOT DISTINCT FROM} fix, proven directly: a
     * {@code null} tenantId now finds and revokes a {@code PLATFORM} grant,
     * where {@code = :tenantId} matched nothing before.
     */
    @Test
    void revokeNowReachesAPlatformScopeGrant() {
        UUID grantId = service.grant(
                new GrantManagementService.GrantCommand(
                        "new-support-2", "platform-support", ResourceScope.platform(), "onboarding", null),
                PLATFORM_GRANTER);

        assertThat(service.revoke(null, grantId, PLATFORM_GRANTER, "role no longer needed"))
                .isTrue();
        assertThat(authorization.has("new-support-2", Capability.TENANT_READ, ResourceScope.platform()))
                .isFalse();
    }

    private void insertPlatformGrant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform,
                     scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("validFrom", CLOCK_INSTANT.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .update();
    }

    private void insertGrant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
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

    private void insertHierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-grants', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-grants-other', 'Other', 'Other', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();

        insertCustomRole(OTHER_TENANTS_ROLE, OTHER_TENANT, OTHER_TENANTS_ROLE_CODE);
        insertCustomRole(OWN_ROLE, TENANT, OWN_ROLE_CODE);
    }

    /** A tenant-defined role: {@code is_platform_defined = false}, and it names its tenant. */
    private void insertCustomRole(UUID roleId, UUID tenantId, String code) {
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :tenantId, :code, :code, 'TENANT', 'ACTIVE', false)
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
                .param("capability", Capability.ORDER_CANCEL.code())
                .update();
    }
}
