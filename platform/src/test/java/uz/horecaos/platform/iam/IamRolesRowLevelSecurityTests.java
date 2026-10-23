package uz.horecaos.platform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.TenantAvailability;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantableRole;
import uz.horecaos.platform.iam.application.TenantJob;
import uz.horecaos.platform.iam.application.TenantRoleService;
import uz.horecaos.platform.iam.application.TenantRoleService.Draft;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.support.AppRoleProbe;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStaffInvitationStore;

/**
 * ADR 0186 / V0693: a tenant's own jobs are isolated in the database, not only by the service
 * that happens to remember to filter.
 *
 * <p>Everything here runs as a login role holding nothing but {@code horecaos_application},
 * because PostgreSQL exempts a table's owner from its own policies and a probe from the
 * migrator's connection would prove nothing. Two kinds of claim:
 *
 * <ol>
 *   <li>the policies themselves - what an unbound session, a bound tenant and the exempt role
 *       can read and write on {@code iam.roles} and {@code iam.role_capabilities};
 *   <li>that every reader of a grant's role binds the tenant. A reader that forgets loses a
 *       tenant job's holders from its answer, silently, while the platform's eight jobs keep
 *       working - so each reader is run here, through the same transaction advice Spring
 *       applies, once bound and once not, and the difference is asserted. The "not" half is
 *       what makes the "bound" half evidence rather than a test that passes on an empty table.
 * </ol>
 */
class IamRolesRowLevelSecurityTests {

    private static final String PROBE_ROLE = "iam_roles_rls_probe";

    private static final UUID TENANT_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac141201");
    private static final UUID BRAND_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac141202");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac141204");
    private static final UUID TENANT_B = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1412f1");

    private static final UUID JOB_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac141211");
    private static final UUID JOB_B = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac141212");
    private static final String JOB_A_CODE = "job-aaaaaaaaaa";
    private static final String JOB_B_CODE = "job-bbbbbbbbbb";

    private static final String OWNER_A = "owner-a";
    private static final String CASHIER_A = "cashier-a";

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final ResourceScope BRANCH_A = ResourceScope.location(TENANT_A, BRAND_A, LOCATION_A);

    private static TestDatabase.Handle db;
    private static JdbcClient owner;
    private static AppRoleProbe app;
    private static Clock clock;

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the row-level security probes");
        db = TestDatabase.migrated();
        owner = JdbcClient.create(db.dataSource());
        app = AppRoleProbe.open(db, PROBE_ROLE);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        new RoleRegistrySynchronizer(owner).synchronize();
        for (Object[] tenant : new Object[][] {{TENANT_A, "rls-a"}, {TENANT_B, "rls-b"}}) {
            owner.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant[0]).param("slug", tenant[1]).update();
        }
        owner.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND_A', 'brand-a', 'Brand A', 'ACTIVE', 0)
                """).param("id", BRAND_A).param("tenantId", TENANT_A).update();
        owner.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC_A', 'loc-a', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION_A)
                .param("tenantId", TENANT_A)
                .param("brandId", BRAND_A)
                .update();

        insertJob(JOB_A, TENANT_A, JOB_A_CODE, "Старший кассир", "LOCATION");
        insertJob(JOB_B, TENANT_B, JOB_B_CODE, "Чужой", "LOCATION");
        grant(OWNER_A, PlatformRole.TENANT_OWNER, true, "TENANT", TENANT_A);
        grantCustom(CASHIER_A, JOB_A, "LOCATION", LOCATION_A);
    }

    @AfterAll
    static void stop() {
        if (db != null) {
            db.close();
        }
        if (app != null) {
            app.close();
        }
    }

    // ------------------------------------------------------------ the policies

    @Test
    @DisplayName("an unbound session reads the platform's jobs and no tenant's")
    void anUnboundSessionSeesOnlyThePlatformsJobs() {
        List<String> codes = app.tx.execute(status -> app.application
                .sql("SELECT code FROM iam.roles")
                .query(String.class)
                .list());

        assertThat(codes).contains("tenant-owner", "location-staff").doesNotContain(JOB_A_CODE, JOB_B_CODE);
        assertThat(count("SELECT count(*) FROM iam.roles WHERE tenant_id IS NOT NULL"))
                .as("the owner's connection, which the table's owner is exempt from, sees the tenants' rows")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("a bound session reads its own jobs and never another tenant's, with no WHERE clause to help it")
    void aBoundSessionSeesItsOwnJobsAndNeverAnotherTenants() {
        List<String> codes = app.tx.execute(status -> {
            app.rls.bindTenant(TENANT_A);
            return app.application
                    .sql("SELECT code FROM iam.roles")
                    .query(String.class)
                    .list();
        });

        assertThat(codes).contains("tenant-owner", JOB_A_CODE).doesNotContain(JOB_B_CODE);

        List<String> forB = app.tx.execute(status -> {
            app.rls.bindTenant(TENANT_B);
            return app.application
                    .sql("SELECT code FROM iam.roles")
                    .query(String.class)
                    .list();
        });
        assertThat(forB).contains(JOB_B_CODE).doesNotContain(JOB_A_CODE);
    }

    @Test
    @DisplayName("a tenant session cannot write another tenant's job, a platform job, or move its own away")
    void aTenantSessionWritesOnlyItsOwnRows() {
        Integer otherTenants = app.tx.execute(status -> {
            app.rls.bindTenant(TENANT_A);
            return app.application
                    .sql("UPDATE iam.roles SET name = 'hijacked' WHERE id = :id")
                    .param("id", JOB_B)
                    .update();
        });
        assertThat(otherTenants)
                .as("another tenant's job is not even visible to update")
                .isZero();

        Integer platform = app.tx.execute(status -> {
            app.rls.bindTenant(TENANT_A);
            return app.application
                    .sql("UPDATE iam.roles SET name = 'hijacked' WHERE code = 'tenant-owner'")
                    .update();
        });
        assertThat(platform)
                .as("the platform's jobs are readable by a tenant and writable by none")
                .isZero();

        assertThatThrownBy(() -> app.tx.executeWithoutResult(status -> {
                    app.rls.bindTenant(TENANT_A);
                    app.application
                            .sql("UPDATE iam.roles SET tenant_id = :other WHERE id = :id")
                            .param("other", TENANT_B)
                            .param("id", JOB_A)
                            .update();
                }))
                .as("WITH CHECK: a job cannot be re-parented to a tenant the session is not bound to")
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> app.tx.executeWithoutResult(status -> {
                    app.rls.bindTenant(TENANT_A);
                    insertJobAs(UUID.randomUUID(), TENANT_B, "job-cccccccccc");
                }))
                .as("a session bound to A cannot create a job for B")
                .isInstanceOf(DataAccessException.class);

        assertThat(count("SELECT count(*) FROM iam.roles WHERE name = 'hijacked' OR code = 'job-cccccccccc'"))
                .isZero();
    }

    @Test
    @DisplayName("an unbound session cannot create a tenant job at all")
    void anUnboundSessionCannotCreateOne() {
        assertThatThrownBy(() -> app.tx.executeWithoutResult(
                        status -> insertJobAs(UUID.randomUUID(), TENANT_A, "job-dddddddddd")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a role's capabilities are visible and writable exactly as the role is")
    void roleCapabilitiesFollowTheirRole() {
        Long seenByA = app.tx.execute(status -> {
            app.rls.bindTenant(TENANT_A);
            return app.application
                    .sql("SELECT count(*) FROM iam.role_capabilities WHERE role_id IN (:a, :b)")
                    .param("a", JOB_A)
                    .param("b", JOB_B)
                    .query(Long.class)
                    .single();
        });
        assertThat(seenByA)
                .as("A sees its job's two capabilities and none of B's")
                .isEqualTo(2L);

        Long unbound = app.tx.execute(status -> app.application
                .sql("SELECT count(*) FROM iam.role_capabilities WHERE role_id IN (:a, :b)")
                .param("a", JOB_A)
                .param("b", JOB_B)
                .query(Long.class)
                .single());
        assertThat(unbound).isZero();

        assertThatThrownBy(() -> app.tx.executeWithoutResult(status -> {
                    app.rls.bindTenant(TENANT_A);
                    app.application
                            .sql(
                                    "INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:id, 'order.read')")
                            .param("id", JOB_B)
                            .update();
                }))
                .as("a capability cannot be put into another tenant's job")
                .isInstanceOf(DataAccessException.class);

        Integer deleted = app.tx.execute(status -> {
            app.rls.bindTenant(TENANT_A);
            return app.application
                    .sql("DELETE FROM iam.role_capabilities WHERE role_id = :id")
                    .param("id", JOB_B)
                    .update();
        });
        assertThat(deleted).isZero();

        Integer platformCaps = app.tx.execute(status -> {
            app.rls.bindTenant(TENANT_A);
            return app.application
                    .sql("DELETE FROM iam.role_capabilities WHERE role_id = :id")
                    .param("id", RoleRegistrySynchronizer.platformRoleId(PlatformRole.LOCATION_STAFF))
                    .update();
        });
        assertThat(platformCaps).as("a tenant cannot strip a platform job").isZero();
        assertThat(count("SELECT count(*) FROM iam.role_capabilities WHERE role_id = '" + JOB_B + "'"))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("the platform's jobs are written under the exempt role, and are not writable without it")
    void thePlatformsJobsAreWrittenUnderTheExemptRole() {
        app.tx.executeWithoutResult(status -> new RoleRegistrySynchronizer(app.application, app.rls).synchronize());

        assertThatThrownBy(() -> app.tx.executeWithoutResult(
                        status -> new RoleRegistrySynchronizer(app.application).synchronize()))
                .as("without bindPlatform the synchronizer is just another unbound session")
                .isInstanceOf(DataAccessException.class);

        assertThat(owner.sql("SELECT code FROM iam.roles WHERE id IN (:a, :b) ORDER BY code")
                        .param("a", JOB_A)
                        .param("b", JOB_B)
                        .query(String.class)
                        .list())
                .as("and the synchronizer, under the exempt role, still leaves the tenants' rows alone")
                .containsExactly(JOB_A_CODE, JOB_B_CODE);
        assertThat(count("SELECT count(*) FROM iam.role_capabilities WHERE role_id IN ('" + JOB_A + "', '" + JOB_B
                        + "')"))
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("the catalog agrees: row-level security is on, not forced, with exactly the policies written")
    void theCatalogAgrees() {
        for (String table : List.of("roles", "role_capabilities")) {
            Map<String, Object> flags =
                    owner.sql("""
                            SELECT c.relrowsecurity AS enabled, c.relforcerowsecurity AS forced
                              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                             WHERE n.nspname = 'iam' AND c.relname = :table
                            """).param("table", table).query().singleRow();
            assertThat(flags.get("enabled")).as("iam.%s enabled", table).isEqualTo(true);
            assertThat(flags.get("forced"))
                    .as("iam.%s is not FORCEd: the owner's maintenance paths stay exempt (V0162)", table)
                    .isEqualTo(false);
        }
        assertThat(owner.sql("""
                                SELECT p.polname FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
                                  JOIN pg_namespace n ON n.oid = c.relnamespace
                                 WHERE n.nspname = 'iam' AND c.relname = 'roles' ORDER BY p.polname
                                """).query(String.class).list())
                .containsExactly("roles_insert_own", "roles_update_own", "roles_visible");
        assertThat(owner.sql("""
                                SELECT p.polname FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
                                  JOIN pg_namespace n ON n.oid = c.relnamespace
                                 WHERE n.nspname = 'iam' AND c.relname = 'role_capabilities' ORDER BY p.polname
                                """).query(String.class).list())
                .containsExactly(
                        "role_capabilities_delete_own", "role_capabilities_insert_own", "role_capabilities_visible");
    }

    // ----------------------------------------------------- every reader binds

    @Test
    @DisplayName(
            "JdbcAuthorizationService: a holder of a tenant job is authorised only because the read binds the tenant")
    void theAuthorizationReadBindsTheTenant() {
        AuthorizationService bound = authorization(true);
        AuthorizationService unbound = authorization(false);

        assertThat(bound.has(CASHIER_A, Capability.ORDER_CANCEL, BRANCH_A)).isTrue();
        assertThat(bound.held(CASHIER_A, BRANCH_A))
                .containsExactlyInAnyOrder(Capability.ORDER_READ, Capability.ORDER_CANCEL);
        assertThat(bound.has(OWNER_A, Capability.ORDER_CANCEL, BRANCH_A))
                .as("the platform's jobs authorise whether or not a tenant is bound")
                .isTrue();

        assertThat(unbound.has(CASHIER_A, Capability.ORDER_CANCEL, BRANCH_A))
                .as("the failure the binding exists to prevent: the holder silently loses the job")
                .isFalse();
        assertThat(unbound.has(OWNER_A, Capability.ORDER_CANCEL, BRANCH_A)).isTrue();
    }

    @Test
    @DisplayName(
            "GrantManagementService: its tenant reads bind, so a tenant job's grants are listed and its jobs offered")
    void theGrantServiceReadsBindTheTenant() {
        GrantManagementService bound = grantService(true);
        GrantManagementService unbound = grantService(false);

        assertThat(bound.listForTenant(TENANT_A, false))
                .extracting(GrantManagementService.GrantView::roleCode)
                .contains(JOB_A_CODE);
        assertThat(bound.listWithin(ResourceScope.brand(TENANT_A, BRAND_A), false))
                .extracting(GrantManagementService.GrantView::roleCode)
                .contains(JOB_A_CODE);
        assertThat(bound.grantsCarrying(TENANT_A, CASHIER_A, Capability.ORDER_CANCEL))
                .hasSize(1);
        assertThat(bound.grantableRoles(ResourceScope.tenant(TENANT_A), OWNER_A))
                .filteredOn(GrantableRole::custom)
                .extracting(GrantableRole::code)
                .containsExactly(JOB_A_CODE);

        assertThat(unbound.listForTenant(TENANT_A, false))
                .extracting(GrantManagementService.GrantView::roleCode)
                .doesNotContain(JOB_A_CODE);
        assertThat(unbound.grantsCarrying(TENANT_A, CASHIER_A, Capability.ORDER_CANCEL))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "TenantRoleService run as the application role: define, edit, give, close - and a second tenant sees none of it")
    void theWholeLifecycleWorksAsTheApplicationRole() {
        TenantRoleService jobs = app.transactional(new TenantRoleService(
                app.application, authorization(true), authorizationCache(), event -> {}, clock, app.rls));
        GrantManagementService grants = grantService(true);

        TenantJob created = jobs.create(
                TENANT_A,
                new Draft("Бариста", ScopeType.LOCATION, List.of("order.read"), null),
                "Hiring baristas",
                OWNER_A);
        TenantJob edited = jobs.update(
                TENANT_A,
                created.code(),
                new Draft("Бариста", null, List.of("order.read", "order.cancel"), null),
                created.version(),
                "Baristas void their own mistakes",
                OWNER_A);
        assertThat(edited.capabilities()).containsExactly("order.cancel", "order.read");

        UUID grantId = app.transactional(grants)
                .grant(
                        new GrantManagementService.GrantCommand("barista-1", created.code(), BRANCH_A, "Hired", null),
                        OWNER_A);
        assertThat(authorization(true).has("barista-1", Capability.ORDER_CANCEL, BRANCH_A))
                .isTrue();

        assertThat(jobs.list(TENANT_B, true).stream().map(TenantJob::code))
                .as("tenant B's view of the same table")
                .doesNotContain(created.code(), JOB_A_CODE);
        assertThatThrownBy(() -> jobs.get(TENANT_B, created.code()))
                .isInstanceOf(TenantRoleService.NoSuchJobException.class);

        app.transactional(grants).revoke(TENANT_A, grantId, OWNER_A, "Left");
        assertThat(jobs.retire(TENANT_A, created.code(), edited.version(), "Closing", OWNER_A)
                        .status())
                .isEqualTo("RETIRED");
    }

    @Test
    @DisplayName(
            "the staff stores: who holds which job in a tenant, and the person-level reads, bind the tenant (or the exempt role)")
    void theStaffStoresBind() {
        JdbcStaffMemberStore bound = app.transactional(new JdbcStaffMemberStore(app.application, app.rls));
        JdbcStaffMemberStore unbound = app.transactional(new JdbcStaffMemberStore(app.application));

        assertThat(bound.activeGrantPlaces(TENANT_A, List.of(CASHIER_A), NOW, Set.of()))
                .containsKey(CASHIER_A);
        assertThat(bound.activeGrantsOf(TENANT_A, CASHIER_A, NOW, Set.of())).hasSize(1);
        assertThat(bound.hasEverHeldStaffJob(TENANT_A, CASHIER_A, Set.of())).isTrue();
        assertThat(bound.countUnbackedActiveSubjects(NOW, Set.of()))
                .as("the reconcilers span tenants, under the exempt role")
                .isGreaterThanOrEqualTo(2L);
        assertThat(bound.unbackedActiveSubjects(NOW, Set.of(), 100, null))
                .extracting(JdbcStaffMemberStore.SubjectRef::principalSubject)
                .contains(CASHIER_A, OWNER_A);
        bound.countEndedWithAccess(NOW, Set.of());

        assertThat(unbound.activeGrantPlaces(TENANT_A, List.of(CASHIER_A), NOW, Set.of()))
                .as("unbound, the tenant job's holder drops out of the join")
                .doesNotContainKey(CASHIER_A);
        assertThat(unbound.hasEverHeldStaffJob(TENANT_A, CASHIER_A, Set.of())).isFalse();
    }

    @Test
    @DisplayName("the invitation store: a grant's job code and a custom job's name are read with the tenant bound")
    void theInvitationStoreBinds() {
        UUID grantId = owner.sql("SELECT id FROM iam.grants WHERE principal_subject = :s")
                .param("s", CASHIER_A)
                .query(UUID.class)
                .single();
        JdbcStaffInvitationStore bound = app.transactional(new JdbcStaffInvitationStore(app.application, app.rls));
        JdbcStaffInvitationStore unbound = app.transactional(new JdbcStaffInvitationStore(app.application));

        assertThat(bound.roleCodeOfGrant(TENANT_A, grantId)).contains(JOB_A_CODE);
        assertThat(bound.customJobName(TENANT_A, JOB_A_CODE)).contains("Старший кассир");
        assertThat(bound.customJobName(TENANT_B, JOB_A_CODE))
                .as("another tenant's code")
                .isEmpty();
        assertThat(bound.customJobName(TENANT_A, "tenant-owner"))
                .as("a code-owned job has a translated name")
                .isEmpty();
        assertThat(unbound.roleCodeOfGrant(TENANT_A, grantId)).isEmpty();
    }

    // ----------------------------------------------------------------- helpers

    private static AuthorizationService authorization(boolean binds) {
        return authorizationCache(binds);
    }

    private static JdbcAuthorizationService authorizationCache() {
        return authorizationCache(true);
    }

    private static JdbcAuthorizationService authorizationCache(boolean binds) {
        var actor = (uz.horecaos.platform.iam.api.CurrentActor)
                () -> new AuthenticatedActor("no-request-actor-in-fixture", Set.of(), Map.of());
        return binds
                ? new JdbcAuthorizationService(
                        app.application, clock, actor, tenantId -> TenantAvailability.OPERATING, app.rls, app.tx)
                : new JdbcAuthorizationService(app.application, clock, actor, tenantId -> TenantAvailability.OPERATING);
    }

    private static GrantManagementService grantService(boolean binds) {
        JdbcAuthorizationService authorization = authorizationCache(binds);
        return binds
                ? app.transactional(new GrantManagementService(
                        app.application, authorization, authorization, event -> {}, clock, app.rls))
                : app.transactional(
                        new GrantManagementService(app.application, authorization, authorization, event -> {}, clock));
    }

    private static void insertJob(UUID id, UUID tenantId, String code, String name, String level) {
        owner.sql("""
                INSERT INTO iam.roles
                    (id, tenant_id, code, name, scope_type, status, is_platform_defined, version, created_by, updated_by)
                VALUES (:id, :tenantId, :code, :name, :level, 'ACTIVE', false, 1, 'fixture', 'fixture')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("name", name)
                .param("level", level)
                .update();
        for (String capability : List.of("order.read", "order.cancel")) {
            owner.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:id, :capability)")
                    .param("id", id)
                    .param("capability", capability)
                    .update();
        }
    }

    private static void insertJobAs(UUID id, UUID tenantId, String code) {
        app.application
                .sql("""
                        INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                        VALUES (:id, :tenantId, :code, 'x', 'LOCATION', 'ACTIVE', false)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .update();
    }

    private static void grant(
            String subject, PlatformRole role, boolean platformRole, String scopeType, UUID tenantId) {
        owner.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, :platform, :scopeType, :scopeId,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("platform", platformRole)
                .param("scopeType", scopeType)
                .param("scopeId", tenantId)
                .param("validFrom", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static void grantCustom(String subject, UUID roleId, String scopeType, UUID scopeId) {
        owner.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, false, :scopeType, :scopeId,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT_A)
                .param("subject", subject)
                .param("roleId", roleId)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static long count(String sql) {
        return owner.sql(sql).query(Long.class).single();
    }
}
