package uz.horecaos.platform.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.application.GrantAuditListener;
import uz.horecaos.platform.audit.application.SupportSessionAuditListener;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.GrantChanged;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.SupportSessionChanged;
import uz.horecaos.platform.iam.application.SupportSessionService.Access;
import uz.horecaos.platform.iam.application.SupportSessionService.SupportSession;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0081 against PostgreSQL: a support session is a grant that ends by
 * itself, opened with a reason, and nothing else.
 *
 * <p>Every assertion about access goes through the same {@code has} the
 * request path uses, with the clock moved rather than the row edited: the
 * question is whether the deadline stops the grant on the ordinary path, not
 * whether a column says it should.
 */
class SupportSessionServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1281a1");
    private static final UUID ARCHIVED = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1281a2");
    private static final String STAFF = "support-staff-1";
    private static final String OWNER = "owner-1";
    private static final Instant START = Instant.parse("2026-09-11T02:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableClock clock;
    private JdbcAuthorizationService authorization;
    private GrantManagementService grants;
    private SupportSessionService sessions;

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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE iam.support_sessions").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.roles CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(START);
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
        SupportSessionAuditListener sessionAudit = new SupportSessionAuditListener(recorder);
        org.springframework.context.ApplicationEventPublisher events = event -> {
            if (event instanceof GrantChanged change) {
                grantAudit.onGrantChanged(change);
            } else if (event instanceof SupportSessionChanged change) {
                sessionAudit.onSupportSessionChanged(change);
            }
        };
        grants = new GrantManagementService(jdbc, authorization, authorization, events, clock);
        sessions = new SupportSessionService(jdbc, grants, authorization, events, clock);

        new RoleRegistrySynchronizer(jdbc).synchronize();
        insertTenant(TENANT, "support-target", "ACTIVE");
        insertTenant(ARCHIVED, "support-archived", "ARCHIVED");
        insertGrant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        insertPlatformGrant(STAFF, PlatformRole.PLATFORM_SUPPORT);
    }

    @Test
    void aViewSessionLetsItsHolderLookUntilTheDeadlineAndNotAMinuteAfter() {
        assertThat(authorization.has(STAFF, Capability.ORDER_CANCEL, ResourceScope.tenant(TENANT)))
                .as("platform support is read-only until a session says otherwise")
                .isFalse();

        SupportSession session = sessions.open(
                TENANT, STAFF, Access.VIEW, "Owner reports missing orders", "T-4812", Duration.ofMinutes(60));

        assertThat(session.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(60)));
        assertThat(authorization.has(STAFF, Capability.KITCHEN_TICKET_READ, ResourceScope.tenant(TENANT)))
                .as("the session's own reads, which platform support alone does not hold")
                .isTrue();
        assertThat(authorization.has(STAFF, Capability.ORDER_CANCEL, ResourceScope.tenant(TENANT)))
                .as("looking is all VIEW does")
                .isFalse();

        clock.advance(Duration.ofMinutes(61));
        assertThat(authorization.has(STAFF, Capability.KITCHEN_TICKET_READ, ResourceScope.tenant(TENANT)))
                .as("the deadline ends the grant on the ordinary path, with nothing run in between")
                .isFalse();
    }

    @Test
    void assistWorksTheFloorButNeverMovesMoneyRevealsACustomerOrChangesAccess() {
        sessions.open(TENANT, STAFF, Access.ASSIST, "Stuck order at Chilonzor", null, Duration.ofMinutes(30));

        ResourceScope tenant = ResourceScope.tenant(TENANT);
        assertThat(authorization.has(STAFF, Capability.ORDER_CANCEL, tenant)).isTrue();
        assertThat(authorization.has(STAFF, Capability.OFFERING_MANAGE, tenant)).isTrue();
        assertThat(authorization.has(STAFF, Capability.REFUND_REQUEST, tenant)).isFalse();
        assertThat(authorization.has(STAFF, Capability.CUSTOMER_PII_REVEAL, tenant))
                .isFalse();
        assertThat(authorization.has(STAFF, Capability.IAM_GRANT_MANAGE, tenant))
                .isFalse();
        assertThat(authorization.has(STAFF, Capability.CATALOG_PUBLISH, tenant)).isFalse();
    }

    @Test
    void openingIsAuditedInTheTenantsOwnLogWithTheReason() {
        sessions.open(TENANT, STAFF, Access.VIEW, "Owner reports missing orders", "T-4812", Duration.ofMinutes(60));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'iam.support_session.opened' AND audit_class = 'SECURITY'
                   AND actor_subject = :staff AND tenant_id = :tenant
                   AND reason = 'Owner reports missing orders'
                """)
                        .param("staff", STAFF)
                        .param("tenant", TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void oneOpenSessionPerPersonAndTenantAndANewOneOnceItHasLapsed() {
        SupportSession first = sessions.open(TENANT, STAFF, Access.VIEW, "First look", null, Duration.ofMinutes(15));

        assertThatThrownBy(() -> sessions.open(TENANT, STAFF, Access.VIEW, "Again", null, Duration.ofMinutes(15)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

        clock.advance(Duration.ofMinutes(20));
        SupportSession second =
                sessions.open(TENANT, STAFF, Access.VIEW, "Back for the follow-up", null, Duration.ofMinutes(15));

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(jdbc.sql("SELECT status FROM iam.grants WHERE id = :id")
                        .param("id", first.grantId())
                        .query(String.class)
                        .single())
                .as("the lapsed grant is retired, not reused as if it had never expired")
                .isEqualTo("REVOKED");
        assertThat(authorization.has(STAFF, Capability.KITCHEN_TICKET_READ, ResourceScope.tenant(TENANT)))
                .isTrue();
    }

    @Test
    void theTenantsOwnAdministratorMayEndASessionAtOnceAndAStrangerMayNot() {
        SupportSession session =
                sessions.open(TENANT, STAFF, Access.ASSIST, "Stuck order", null, Duration.ofMinutes(120));

        assertThatThrownBy(() -> sessions.end(TENANT, session.id(), "someone-else", "no business here"))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);

        SupportSession ended = sessions.end(TENANT, session.id(), OWNER, "We fixed it ourselves");

        assertThat(ended.endedBy()).isEqualTo(OWNER);
        assertThat(sessions.isOpen(ended)).isFalse();
        assertThat(authorization.has(STAFF, Capability.ORDER_CANCEL, ResourceScope.tenant(TENANT)))
                .as("ending revokes the grant now, not at the deadline")
                .isFalse();
        assertThat(sessions.end(TENANT, session.id(), STAFF, "twice").endedBy())
                .as("ending an ended session changes nothing")
                .isEqualTo(OWNER);
    }

    @Test
    void aSupportRoleIsNeverGrantedByHand() {
        insertPlatformGrant("platform-admin-1", PlatformRole.PLATFORM_ADMIN);

        assertThatThrownBy(() -> grants.grant(
                        new GrantManagementService.GrantCommand(
                                "staff-2",
                                PlatformRole.SUPPORT_SESSION_ASSIST.code(),
                                ResourceScope.tenant(TENANT),
                                "a standing support grant",
                                null),
                        "platform-admin-1"))
                .as("a support role without a session is standing access with nobody's name on the reason")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void theWindowAndTheReasonAreBoundedAndAnArchivedTenantIsNotEntered() {
        assertThatThrownBy(() -> sessions.open(TENANT, STAFF, Access.VIEW, "quick", null, Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sessions.open(TENANT, STAFF, Access.VIEW, "all day", null, Duration.ofHours(5)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sessions.open(TENANT, STAFF, Access.VIEW, "  ", null, Duration.ofMinutes(30)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sessions.open(ARCHIVED, STAFF, Access.VIEW, "look", null, Duration.ofMinutes(30)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.grants WHERE principal_subject = :s AND tenant_id IS NOT NULL")
                        .param("s", STAFF)
                        .query(Long.class)
                        .single())
                .as("a refused session leaves no grant behind")
                .isZero();
    }

    @Test
    void aSupportPersonSeesTheirOwnOpenSessionsAndTheTenantSeesEveryVisit() {
        sessions.open(TENANT, STAFF, Access.VIEW, "Look", null, Duration.ofMinutes(30));

        assertThat(sessions.openFor(STAFF)).singleElement().satisfies(open -> {
            assertThat(open.tenantId()).isEqualTo(TENANT);
            assertThat(open.access()).isEqualTo(Access.VIEW);
        });
        assertThat(sessions.currentFor(STAFF, TENANT)).isPresent();
        assertThat(sessions.listForTenant(TENANT, 50)).hasSize(1);

        clock.advance(Duration.ofMinutes(31));
        assertThat(sessions.openFor(STAFF)).as("a lapsed session is not open").isEmpty();
        assertThat(sessions.listForTenant(TENANT, 50))
                .as("but the tenant's record of the visit stays")
                .hasSize(1);
    }

    private void insertTenant(UUID id, String slug, String status) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', :status, 0)
                """)
                .param("id", id)
                .param("slug", slug)
                .param("status", status)
                .update();
    }

    private void insertPlatformGrant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL, 'ACTIVE', 'fixture', 'fixture',
                        :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("validFrom", START.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .update();
    }

    private void insertGrant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId, 'ACTIVE', 'fixture',
                        'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("validFrom", START.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .update();
    }

    /** A clock the test moves, so a deadline is asserted as a duration and not an instant. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
