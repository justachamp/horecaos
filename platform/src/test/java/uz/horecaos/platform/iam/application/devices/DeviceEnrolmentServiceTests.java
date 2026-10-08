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
                        DevicePrincipalClass.KITCHEN_KDS,
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
                        DevicePrincipalClass.KITCHEN_KDS,
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
                                DevicePrincipalClass.KITCHEN_KDS,
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
                                DevicePrincipalClass.KITCHEN_KDS,
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
                                DevicePrincipalClass.KITCHEN_KDS,
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
                        DevicePrincipalClass.KITCHEN_KDS,
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
                        DevicePrincipalClass.KITCHEN_KDS,
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
                        DevicePrincipalClass.KITCHEN_KDS,
                        "Line 1"),
                "manager-1");

        var beginB = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, null), "caller-b");
        service.approve(
                new ApproveEnrolment(
                        beginB.userCode(),
                        ResourceScope.location(TENANT, BRAND, SIBLING_LOCATION),
                        DevicePrincipalClass.KITCHEN_KDS,
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
                        DevicePrincipalClass.KITCHEN_KDS,
                        "Line 1"),
                "manager-1");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_events
                         WHERE action_code = 'iam.grant.granted' AND audit_class = 'SECURITY'
                        """).query(Long.class).single()).isEqualTo(1L);
    }

    // ------------------------------------------------ ADR 0151: a class an approval may narrow

    private DevicePrincipalView approveAs(DevicePrincipalClass requested, DevicePrincipalClass approved) {
        var begin = service.beginEnrolment(new BeginEnrolment(requested, "wall"), "caller-" + requested);
        return service.approve(
                new ApproveEnrolment(begin.userCode(), ResourceScope.location(TENANT, BRAND, LOCATION), approved, "TV"),
                "manager-1");
    }

    @Test
    void aTouchRequestMayBeApprovedAsAWallDisplayAndTheGrantFollowsTheApprovedClass() {
        DevicePrincipalView device = approveAs(DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_VDU);

        assertThat(device.deviceClass()).isEqualTo(DevicePrincipalClass.KITCHEN_VDU);
        assertThat(device.requestedClass())
                .as("what it asked for is kept beside what it was approved as")
                .isEqualTo(DevicePrincipalClass.KITCHEN_KDS);
        assertThat(jdbc.sql("""
                        SELECT r.code FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.principal_subject = (SELECT principal_subject FROM iam.device_principals WHERE id = :id)
                        """).param("id", device.id()).query(String.class).list())
                .as("read back from iam.grants: the wall holds exactly the wall's role")
                .containsExactly("kitchen-vdu-device");
        assertThat(service.list(TENANT, LOCATION)).singleElement().satisfies(view -> {
            assertThat(view.deviceClass()).isEqualTo(DevicePrincipalClass.KITCHEN_VDU);
            assertThat(view.requestedClass()).isEqualTo(DevicePrincipalClass.KITCHEN_KDS);
        });
    }

    @Test
    void aWallRequestCannotBeApprovedAsATouchDisplayAndTheRefusalLeavesNothingBehind() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_VDU, "wall"), "caller-w");

        assertThatThrownBy(() -> service.approve(
                        new ApproveEnrolment(
                                begin.userCode(),
                                ResourceScope.location(TENANT, BRAND, LOCATION),
                                DevicePrincipalClass.KITCHEN_KDS,
                                "TV"),
                        "manager-1"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode())
                                .isEqualTo(uz.horecaos.platform.web.api.ErrorCode.VALIDATION_FAILED));

        assertThat(service.list(TENANT, LOCATION)).isEmpty();
        assertThat(provisioner.lastCreatedInternalId())
                .as("refused before a Keycloak client was minted")
                .isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM iam.grants").query(Long.class).single())
                .isZero();
        assertThat(service.pendingEnrolment(begin.userCode()))
                .as("the request is still pending: the manager can approve it as what it asked for")
                .isPresent();

        DevicePrincipalView approvedProperly = service.approve(
                new ApproveEnrolment(
                        begin.userCode(),
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        DevicePrincipalClass.KITCHEN_VDU,
                        "TV"),
                "manager-1");
        assertThat(approvedProperly.deviceClass()).isEqualTo(DevicePrincipalClass.KITCHEN_VDU);
    }

    @Test
    void theDatabaseAcceptsBothClassesAndRejectsAnythingElseInBothTables() {
        for (String table : new String[] {"iam.device_enrolment_requests", "iam.device_principals"}) {
            assertThat(jdbc.sql("""
                            SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
                             WHERE c.conrelid = CAST(:table AS regclass) AND c.conname IN ('ck_device_principal_class', 'ck_device_enrolment_class')
                            """).param("table", table).query(String.class).single())
                    .as(table)
                    .contains("KITCHEN_KDS")
                    .contains("KITCHEN_VDU")
                    .contains("KIOSK");
        }
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO iam.device_enrolment_requests
                            (id, device_code_hash, user_code, requested_class, status, expires_at)
                        VALUES (:id, 'hash-x', 'ZZZZ-0001', 'KITCHEN_EXPO', 'PENDING', now() + interval '10 minutes')
                        """).param("id", UUID.randomUUID()).update())
                .as("an unknown class is refused by the CHECK, not only by the enum")
                .hasMessageContaining("ck_device_enrolment_class");
        assertThat(jdbc.sql("""
                        INSERT INTO iam.device_enrolment_requests
                            (id, device_code_hash, user_code, requested_class, status, expires_at)
                        VALUES (:id, 'hash-y', 'ZZZZ-0002', 'KITCHEN_VDU', 'PENDING', now() + interval '10 minutes')
                        """).param("id", UUID.randomUUID()).update()).isEqualTo(1);
    }

    @Test
    void anActiveDeviceIsFoundBySubjectAndAStaffSubjectOrARevokedDeviceIsNot() {
        DevicePrincipalView device = approveAs(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);
        String subject = jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", device.id())
                .query(String.class)
                .single();

        assertThat(service.activeDeviceOf(subject))
                .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(device.id()));
        assertThat(service.activeDeviceOf("a-staff-subject")).isEmpty();

        service.revoke(device.id(), "manager-1", "TV replaced");
        assertThat(service.activeDeviceOf(subject))
                .as("a token that outlives the revocation still stops here: the row is what revocation changes")
                .isEmpty();
    }

    @Test
    void aPendingCodeAnswersWithTheClassItClaimsAndAnExpiredOrUnknownOneAnswersTheSame() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_VDU, "wall"), "caller-w");

        assertThat(service.pendingEnrolment(begin.userCode()))
                .hasValueSatisfying(
                        pending -> assertThat(pending.requestedClass()).isEqualTo(DevicePrincipalClass.KITCHEN_VDU));
        assertThat(service.pendingEnrolment("NOPE-0000")).isEmpty();

        clock.advance(Duration.ofMinutes(11));
        assertThat(service.pendingEnrolment(begin.userCode())).isEmpty();
    }

    // ---------------------------------------------------------------- ADR 0162: the kiosk class

    @Test
    void aKioskRequestIsApprovedAsAKioskAndHoldsExactlyTheKioskRoleAndNothingItCouldOrderWith() {
        DevicePrincipalView kiosk = approveAs(DevicePrincipalClass.KIOSK, DevicePrincipalClass.KIOSK);

        assertThat(kiosk.deviceClass()).isEqualTo(DevicePrincipalClass.KIOSK);
        String subject = jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", kiosk.id())
                .query(String.class)
                .single();
        assertThat(jdbc.sql("""
                        SELECT r.code FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.principal_subject = :subject
                        """).param("subject", subject).query(String.class).list())
                .as("read back from iam.grants: a kiosk holds the kiosk's role and no other")
                .containsExactly("kiosk-device");

        ResourceScope atLocation = ResourceScope.location(TENANT, BRAND, LOCATION);
        assertThat(authorization.has(subject, Capability.KIOSK_DEVICE_READ, atLocation))
                .isTrue();
        for (Capability refused : new Capability[] {
            Capability.KITCHEN_TICKET_READ,
            Capability.KITCHEN_TICKET_ADVANCE,
            Capability.KITCHEN_DISPLAY_READ,
            Capability.ORDER_READ,
            Capability.ORDER_PLACE,
            Capability.CUSTOMER_READ,
            Capability.KIOSK_DEVICE_MANAGE,
            Capability.TERMINAL_UNLOCK
        }) {
            assertThat(authorization.has(subject, refused, atLocation))
                    .as("a customer-facing device is granted nothing it cannot yet use: %s", refused.code())
                    .isFalse();
        }
        assertThat(authorization.has(
                        subject, Capability.KIOSK_DEVICE_READ, ResourceScope.location(TENANT, BRAND, SIBLING_LOCATION)))
                .as("its grant does not reach a sibling branch")
                .isFalse();
    }

    @Test
    void aKiosksCodeIsNeverApprovedAsAKitchenDisplayAndAKitchenCodeNeverAsAKioskWithNothingLeftBehind() {
        var kioskCode = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KIOSK, "lobby"), "caller-k");
        for (DevicePrincipalClass other :
                new DevicePrincipalClass[] {DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_VDU}) {
            assertThatThrownBy(() -> service.approve(
                            new ApproveEnrolment(
                                    kioskCode.userCode(),
                                    ResourceScope.location(TENANT, BRAND, LOCATION),
                                    other,
                                    "Lobby"),
                            "manager-1"))
                    .isInstanceOfSatisfying(ApiException.class, refusal -> {
                        assertThat(refusal.errorCode())
                                .isEqualTo(uz.horecaos.platform.web.api.ErrorCode.VALIDATION_FAILED);
                        assertThat(refusal.getMessage()).contains("KIOSK").contains(other.name());
                    });
        }
        for (DevicePrincipalClass asked :
                new DevicePrincipalClass[] {DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_VDU}) {
            var kitchenCode = service.beginEnrolment(new BeginEnrolment(asked, "pass"), "caller-" + asked);
            assertThatThrownBy(() -> service.approve(
                            new ApproveEnrolment(
                                    kitchenCode.userCode(),
                                    ResourceScope.location(TENANT, BRAND, LOCATION),
                                    DevicePrincipalClass.KIOSK,
                                    "Pass"),
                            "manager-1"))
                    .isInstanceOfSatisfying(ApiException.class, refusal -> {
                        assertThat(refusal.errorCode())
                                .isEqualTo(uz.horecaos.platform.web.api.ErrorCode.VALIDATION_FAILED);
                        assertThat(refusal.getMessage()).contains(asked.name()).contains("KIOSK");
                    });
        }

        assertThat(service.list(TENANT, LOCATION)).isEmpty();
        assertThat(provisioner.lastCreatedInternalId())
                .as("refused before a Keycloak client was minted")
                .isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM iam.grants").query(Long.class).single())
                .isZero();
        assertThat(service.pendingEnrolment(kioskCode.userCode()))
                .as("the kiosk's request is still pending, to be approved as what it asked for")
                .isPresent();
    }

    @Test
    void theClassToRoleMapIsClosedEachClassHasItsOwnRoleAtLocationScope() {
        java.util.Set<String> roles = new java.util.HashSet<>();
        for (DevicePrincipalClass deviceClass : DevicePrincipalClass.values()) {
            PlatformRole role = DeviceEnrolmentService.roleOf(deviceClass);
            assertThat(role.scopeType()).as(deviceClass.name()).isEqualTo(ResourceScope.ScopeType.LOCATION);
            assertThat(roles.add(role.code()))
                    .as("%s must not share a role with another class", deviceClass)
                    .isTrue();
        }
        assertThat(roles).containsExactlyInAnyOrder("kitchen-device", "kitchen-vdu-device", "kiosk-device");
    }

    @Test
    void lookingAtWhatACodeClaimsIsRateLimitedPerApproverWhetherOrNotTheCodeExists() {
        var begin = service.beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KIOSK, "lobby"), "caller-k");

        assertThat(service.peek(begin.userCode(), "manager-peek"))
                .hasValueSatisfying(
                        pending -> assertThat(pending.requestedClass()).isEqualTo(DevicePrincipalClass.KIOSK));
        for (int guess = 1; guess < 30; guess++) {
            assertThat(service.peek("NOPE%04d".formatted(guess), "manager-peek"))
                    .isEmpty();
        }
        assertThatThrownBy(() -> service.peek(begin.userCode(), "manager-peek"))
                .as("the 31st look in a minute is refused even though this code exists")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode())
                                .isEqualTo(uz.horecaos.platform.web.api.ErrorCode.RATE_LIMIT_EXCEEDED));
        assertThatThrownBy(() -> service.peek("NOPE-9999", "manager-peek"))
                .as("and a code that does not exist is refused the same way, so the budget is no oracle")
                .isInstanceOf(ApiException.class);
        assertThat(service.peek(begin.userCode(), "another-manager"))
                .as("the budget is the approver's own")
                .isPresent();

        clock.advance(Duration.ofMinutes(2));
        assertThat(service.peek(begin.userCode(), "manager-peek")).isPresent();
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

    @Test
    void theDatabaseNamesTheTwoClassesAndNoThirdOnTheDevice() {
        // The CHECK is evaluated before the foreign keys, so a class it refuses fails on the class and a
        // class it allows gets as far as the (deliberately absent) tenant: the row is never written.
        assertThatThrownBy(() -> insertDevicePrincipalOfClass("KITCHEN_EXPO"))
                .hasMessageContaining("ck_device_principal_class");
        assertThatThrownBy(() -> insertDevicePrincipalOfClass("KITCHEN_VDU"))
                .hasMessageContaining("fk_device_principal_location")
                .hasMessageNotContaining("ck_device_principal_class");
        assertThatThrownBy(() -> insertDevicePrincipalOfClass("KITCHEN_KDS"))
                .hasMessageContaining("fk_device_principal_location")
                .hasMessageNotContaining("ck_device_principal_class");
    }

    @Test
    void theDatabaseNamesTheTwoClassesAndNoThirdOnTheRequest() {
        assertThatThrownBy(() -> insertPendingRequestOfClass("KITCHEN_EXPO", "AAAA-1111"))
                .hasMessageContaining("ck_device_enrolment_class");
        insertPendingRequestOfClass("KITCHEN_VDU", "AAAA-2222");
        insertPendingRequestOfClass("KITCHEN_KDS", "AAAA-3333");

        assertThat(jdbc.sql("SELECT count(*) FROM iam.device_enrolment_requests WHERE user_code LIKE 'AAAA-%'")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    private void insertDevicePrincipalOfClass(String deviceClass) {
        jdbc.sql("""
                INSERT INTO iam.device_principals (id, tenant_id, brand_id, location_id, device_class, display_name,
                    keycloak_client_internal_id, keycloak_client_id, principal_subject, grant_id, enrolled_by)
                VALUES (:id, :tenant, :brand, :location, :deviceClass, 'check probe',
                    'internal-probe', 'client-probe', 'subject-probe', :grant, 'probe')
                """)
                .param("id", UUID.randomUUID())
                .param("tenant", UUID.randomUUID())
                .param("brand", UUID.randomUUID())
                .param("location", UUID.randomUUID())
                .param("deviceClass", deviceClass)
                .param("grant", UUID.randomUUID())
                .update();
    }

    private void insertPendingRequestOfClass(String requestedClass, String userCode) {
        jdbc.sql("""
                INSERT INTO iam.device_enrolment_requests (id, device_code_hash, user_code, requested_class, expires_at)
                VALUES (:id, :hash, :userCode, :requestedClass, :expires)
                """)
                .param("id", UUID.randomUUID())
                .param("hash", "probe-" + userCode)
                .param("userCode", userCode)
                .param("requestedClass", requestedClass)
                .param("expires", java.sql.Timestamp.from(NOW.plus(Duration.ofMinutes(10))))
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
