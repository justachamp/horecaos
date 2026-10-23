package uz.horecaos.platform.iam.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.application.GrantAuditListener;
import uz.horecaos.platform.audit.application.RoleAuditListener;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.GrantChanged;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.RoleChanged;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * One tenant with a brand and two branches, a second tenant, and the people who matter to a
 * company defining its own jobs (ADR 0186): an owner and an administrator who may define them, a
 * branch manager and a cook who may not.
 *
 * <p>Built the way {@code GrantManagementServiceTests} builds its world and for the same reason:
 * by hand, on the table owner's connection, with a fixed clock and no grants cache, so a rule
 * failure is a rule failure and not a flake. Row-level security is proved separately, as the
 * application role, in {@code IamRolesRowLevelSecurityTests}.
 */
abstract class TenantJobFixture {

    static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131201");
    static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131202");
    static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131204");
    static final UUID SIBLING_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131205");
    static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1312f1");

    static final String OWNER = "owner-1";
    static final String ADMIN = "admin-1";
    static final String OTHER_OWNER = "other-owner-1";
    static final String BRANCH_MANAGER = "branch-manager-1";
    static final String COOK = "cook-1";

    static final Instant CLOCK_INSTANT = Instant.parse("2026-10-14T10:00:00Z");

    static TestDatabase.Handle db;

    JdbcClient jdbc;
    JdbcAuthorizationService authorization;
    GrantManagementService grants;
    TenantRoleService jobs;

    /** Every event the services published, in order, for a test that asserts on what was announced. */
    final List<Object> published = new ArrayList<>();

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
    void buildTheWorld() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.roles CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        published.clear();

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
        JdbcAuditRecorder recorder =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        GrantAuditListener grantAudit = new GrantAuditListener(recorder);
        RoleAuditListener roleAudit = new RoleAuditListener(recorder);
        // Stands in for Spring's BEFORE_COMMIT dispatch, so each event still reaches the audit
        // listener that records it in production.
        org.springframework.context.ApplicationEventPublisher events = event -> {
            published.add(event);
            if (event instanceof GrantChanged change) {
                grantAudit.onGrantChanged(change);
            } else if (event instanceof RoleChanged change) {
                roleAudit.onRoleChanged(change);
            }
        };
        grants = new GrantManagementService(
                jdbc, authorization, authorization, events, clock, TenantRlsSession.notEnforced());
        jobs = new TenantRoleService(jdbc, authorization, authorization, events, clock, TenantRlsSession.notEnforced());

        new RoleRegistrySynchronizer(jdbc).synchronize();
        insertHierarchy();
        insertGrant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        insertGrant(ADMIN, PlatformRole.TENANT_ADMIN, "TENANT", TENANT, TENANT);
        insertGrant(OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT, OTHER_TENANT);
        insertGrant(BRANCH_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION, TENANT);
        insertGrant(COOK, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
    }

    void insertGrant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform,
                     scope_type, scope_id, status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("validFrom", CLOCK_INSTANT.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("tenantId", tenantId)
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
                VALUES (:id, 'tenant-jobs', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-jobs-other', 'Other', 'Other', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND_A', 'brand-a', 'Brand A', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        for (Object[] location : new Object[][] {
            {LOCATION, "LOC_A", "loc-a", "Location"},
            {SIBLING_LOCATION, "LOC_S", "loc-s", "Sibling"}
        }) {
            jdbc.sql("""
                    INSERT INTO tenant.locations
                        (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location[0])
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", location[1])
                    .param("slug", location[2])
                    .param("name", location[3])
                    .update();
        }
    }
}
