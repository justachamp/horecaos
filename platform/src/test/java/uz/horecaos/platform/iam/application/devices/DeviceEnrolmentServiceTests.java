package uz.horecaos.platform.iam.application.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
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
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.ApproveEnrolment;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.BeginEnrolment;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.EnrolmentPollResult;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.EnrolmentStatus;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * ADR 0079: the pairing-code enrolment handshake, against a real PostgreSQL.
 *
 * <p>Every property under test here is a property of the database or of the
 * grant model, not of a mock agreeing with itself: whether a claimed
 * credential is handed out exactly once is a conditional {@code UPDATE}'s row
 * count; whether an enrolled device holds exactly {@code kitchen.ticket.read}
 * and {@code kitchen.ticket.advance} and nothing else is answered by the same
 * {@link JdbcAuthorizationService} every staff principal is checked against;
 * whether a sibling location is reachable is the same composite foreign key
 * {@code kitchen.stations} already carries.
 */
class DeviceEnrolmentServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac130001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac130002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac130003");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac130004");

    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TickingClock clock;
    private JdbcAuthorizationService authorization;
    private FakeDeviceClientProvisioner provisioner;
    private DeviceEnrolmentService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for device enrolment tests");
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
        jdbc.sql("TRUNCATE TABLE iam.device_enrolment_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.device_principals CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.roles CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new TickingClock(NOW);

        // Deliberately not the caching proxy, the identical reasoning
        // GrantManagementServiceTests gives: these tests are about the grant
        // rules a device ends up with, and a cache would turn a written-but-
        // not-yet-visible grant into a flake.
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
        GrantManagementService grants = new GrantManagementService(
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

        provisioner = new FakeDeviceClientProvisioner();
        service = new DeviceEnrolmentService(jdbc, provisioner, grants, new InProcessRateLimiter(clock), clock);

        insertHierarchy();
    }

    @Test
    void theHandshakeGrantsExactlyTheDeviceRoleAtLocationScopeAndNothingWider() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, "line-1"), "caller-a");

        DevicePrincipalView device = service.approve(
                new ApproveEnrolment(
                        begin.userCode(),
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        PlatformRole.KITCHEN_DEVICE.code(),
                        "Line 1 KDS"),
                "manager-1");

        EnrolmentPollResult claimed = service.poll(begin.deviceCode(), "caller-a");
        assertThat(claimed.status()).isEqualTo(EnrolmentStatus.APPROVED);
        var credential = Objects.requireNonNull(claimed.credential());
        assertThat(credential.clientId()).isEqualTo("kds-device-fake-1");

        String deviceSubject = "fake-device-subject-1";
        ResourceScope atLocation = ResourceScope.location(TENANT, BRAND, LOCATION);

        assertThat(authorization.has(deviceSubject, Capability.KITCHEN_TICKET_READ, atLocation))
                .isTrue();
        assertThat(authorization.has(deviceSubject, Capability.KITCHEN_TICKET_ADVANCE, atLocation))
                .isTrue();

        assertThat(authorization.has(deviceSubject, Capability.KITCHEN_TICKET_RECALL, atLocation))
                .as("a device never holds more than kitchen.ticket.read and kitchen.ticket.advance")
                .isFalse();
        assertThat(authorization.has(deviceSubject, Capability.KITCHEN_TICKET_RELEASE, atLocation))
                .isFalse();
        assertThat(authorization.has(deviceSubject, Capability.KITCHEN_STATION_MANAGE, atLocation))
                .isFalse();
        assertThat(authorization.has(deviceSubject, Capability.IAM_GRANT_MANAGE, ResourceScope.tenant(TENANT)))
                .as(
                        "a device that could grant capabilities to itself is the escalation this model refuses everywhere else")
                .isFalse();
        assertThat(authorization.has(
                        deviceSubject,
                        Capability.KITCHEN_TICKET_READ,
                        ResourceScope.location(TENANT, BRAND, SIBLING_LOCATION)))
                .as("a device's grant does not reach a sibling location")
                .isFalse();

        assertThat(device.locationId()).isEqualTo(LOCATION);
        assertThat(device.deviceClass()).isEqualTo(DevicePrincipalClass.KITCHEN_KDS);
        assertThat(device.status()).isEqualTo("ACTIVE");
    }

    @Test
    void aClaimedCredentialIsHandedOutExactlyOnce() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-a");
        service.approve(
                new ApproveEnrolment(
                        begin.userCode(),
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        PlatformRole.KITCHEN_DEVICE.code(),
                        "Line 1"),
                "manager-1");

        EnrolmentPollResult first = service.poll(begin.deviceCode(), "caller-a");
        assertThat(first.status()).isEqualTo(EnrolmentStatus.APPROVED);
        assertThat(first.credential()).isNotNull();

        EnrolmentPollResult second = service.poll(begin.deviceCode(), "caller-a");
        assertThat(second.status())
                .as("a second poll of an already-claimed code must never see APPROVED again")
                .isEqualTo(EnrolmentStatus.EXPIRED);
        assertThat(second.credential()).isNull();
    }

    @Test
    void pendingReadsPendingUntilApproved() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-a");

        EnrolmentPollResult beforeApproval = service.poll(begin.deviceCode(), "caller-a");

        assertThat(beforeApproval.status()).isEqualTo(EnrolmentStatus.PENDING);
        assertThat(beforeApproval.credential()).isNull();
    }

    @Test
    void pollingAnUnknownDeviceCodeReadsAsExpiredRatherThanAnError() {
        EnrolmentPollResult result = service.poll("not-a-real-device-code", "caller-a");

        assertThat(result.status())
                .as("an unrecognised code must answer exactly like an expired one, or a code becomes an "
                        + "oracle for which codes were ever issued")
                .isEqualTo(EnrolmentStatus.EXPIRED);
    }

    @Test
    void anExpiredPendingRequestCannotBeApproved() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-a");

        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> service.approve(
                        new ApproveEnrolment(
                                begin.userCode(),
                                ResourceScope.location(TENANT, BRAND, LOCATION),
                                PlatformRole.KITCHEN_DEVICE.code(),
                                "Line 1"),
                        "manager-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(service.poll(begin.deviceCode(), "caller-a").status()).isEqualTo(EnrolmentStatus.EXPIRED);
    }

    @Test
    void approvingAnUnknownUserCodeIsRefused() {
        assertThatThrownBy(() -> service.approve(
                        new ApproveEnrolment(
                                "GARBAGE1",
                                ResourceScope.location(TENANT, BRAND, LOCATION),
                                PlatformRole.KITCHEN_DEVICE.code(),
                                "Line 1"),
                        "manager-1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aDevicePrincipalMayOnlyBeApprovedAtLocationScope() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-a");

        assertThatThrownBy(() -> service.approve(
                        new ApproveEnrolment(
                                begin.userCode(),
                                ResourceScope.tenant(TENANT),
                                PlatformRole.KITCHEN_DEVICE.code(),
                                "Line 1"),
                        "manager-1"))
                .as("ADR 0079: a device principal is always LOCATION-scoped")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokingTakesEffectImmediately() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-a");
        DevicePrincipalView device = service.approve(
                new ApproveEnrolment(
                        begin.userCode(),
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        PlatformRole.KITCHEN_DEVICE.code(),
                        "Line 1"),
                "manager-1");
        service.poll(begin.deviceCode(), "caller-a");

        String deviceSubject = "fake-device-subject-1";
        ResourceScope atLocation = ResourceScope.location(TENANT, BRAND, LOCATION);
        assertThat(authorization.has(deviceSubject, Capability.KITCHEN_TICKET_ADVANCE, atLocation))
                .isTrue();

        boolean revoked = service.revoke(device.id(), "manager-1", "Screen replaced");

        assertThat(revoked).isTrue();
        assertThat(authorization.has(deviceSubject, Capability.KITCHEN_TICKET_ADVANCE, atLocation))
                .as("revocation takes effect on the very next request, not at the token's expiry")
                .isFalse();
        assertThat(provisioner.isDisabled(provisioner.lastCreatedInternalId())).isTrue();
    }

    @Test
    void revokingAnAlreadyRevokedDeviceIsANoOp() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-a");
        DevicePrincipalView device = service.approve(
                new ApproveEnrolment(
                        begin.userCode(),
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        PlatformRole.KITCHEN_DEVICE.code(),
                        "Line 1"),
                "manager-1");

        assertThat(service.revoke(device.id(), "manager-1", "first")).isTrue();
        assertThat(service.revoke(device.id(), "manager-1", "second"))
                .as("a manager pressing revoke twice on an already-dark screen is not a second security event")
                .isFalse();
    }

    @Test
    void listReturnsOnlyThisLocationsDevices() {
        var beginA = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-a");
        service.approve(
                new ApproveEnrolment(
                        beginA.userCode(),
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        PlatformRole.KITCHEN_DEVICE.code(),
                        "Line 1"),
                "manager-1");

        var beginB = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-b");
        service.approve(
                new ApproveEnrolment(
                        beginB.userCode(),
                        ResourceScope.location(TENANT, BRAND, SIBLING_LOCATION),
                        PlatformRole.KITCHEN_DEVICE.code(),
                        "Sibling KDS"),
                "manager-2");

        assertThat(service.list(TENANT, LOCATION))
                .hasSize(1)
                .allSatisfy(view -> assertThat(view.locationId()).isEqualTo(LOCATION));
        assertThat(service.list(TENANT, SIBLING_LOCATION)).hasSize(1);
    }

    @Test
    void anEnrolmentIsAuditedAsASecurityFactThroughTheOrdinaryGrantPath() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-a");
        service.approve(
                new ApproveEnrolment(
                        begin.userCode(),
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        PlatformRole.KITCHEN_DEVICE.code(),
                        "Line 1"),
                "manager-1");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_events
                         WHERE action_code = 'iam.grant.granted' AND audit_class = 'SECURITY'
                        """).query(Long.class).single()).isEqualTo(1L);
    }

    private void insertHierarchy() {
        jdbc.sql("""
                        INSERT INTO tenant.tenants
                            (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                        VALUES (:id, 'tenant-devices', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                        """).param("id", TENANT).update();
        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                        VALUES (:id, :tenantId, 'BRAND_A', 'brand-a', 'Brand A', 'ACTIVE', 0)
                        """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                        INSERT INTO tenant.locations
                            (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                        VALUES (:id, :tenantId, :brandId, 'LOC_A', 'loc-a', 'Location A', 'Asia/Tashkent', 'ACTIVE', 0)
                        """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                        INSERT INTO tenant.locations
                            (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                        VALUES (:id, :tenantId, :brandId, 'LOC_B', 'loc-b', 'Location B', 'Asia/Tashkent', 'ACTIVE', 0)
                        """)
                .param("id", SIBLING_LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    /** A clock a test can move forward, for the expiry window ADR 0079 gives an enrolment code. */
    private static final class TickingClock extends Clock {

        private Instant now;

        private TickingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
