package uz.horecaos.platform.kitchen.application;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.application.GrantAuditListener;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.BeginEnrolment;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.application.devices.DeviceEnrolmentService;
import uz.horecaos.platform.iam.application.devices.FakeDeviceClientProvisioner;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.kitchen.application.KitchenDeviceDisplayService.Display;
import uz.horecaos.platform.kitchen.application.KitchenDeviceDisplayService.WallCaller;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenDeviceDisplayStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * ADR 0151: what the kitchen holds about a wall display, against a real PostgreSQL -- the display's
 * configuration, who may change it, and the wall's own read.
 *
 * <p>The properties under test are the database's and the service's, not a mock agreeing with itself:
 * that a station of another branch cannot be a wall's filter is a composite foreign key; that a wall's
 * read never makes a manager's open edit stale is a column the read does not touch; that "which station
 * does this caller see" is answered from the enrolled device's own row and not from the request.
 */
class KitchenDeviceDisplayServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac150001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac150002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac150003");
    private static final UUID SIBLING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac150004");
    private static final UUID GRILL = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac150011");
    private static final UUID BAR = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac150012");
    private static final UUID OLD_SIDE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac150013");
    private static final UUID SIBLINGS_STATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac150014");

    private static final Instant START = Instant.parse("2026-10-07T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovingClock clock;
    private DeviceEnrolmentPort enrolment;
    private KitchenDeviceService devices;
    private KitchenDeviceDisplayService displays;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for kitchen display tests");
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

        clock = new MovingClock(START);
        var authorization =
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
        GrantAuditListener grantAudit = new GrantAuditListener(
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()));
        GrantManagementService grants = new GrantManagementService(
                jdbc,
                authorization,
                authorization,
                event -> {
                    if (event instanceof uz.horecaos.platform.iam.api.GrantChanged change) {
                        grantAudit.onGrantChanged(change);
                    }
                },
                clock);
        new RoleRegistrySynchronizer(jdbc).synchronize();

        enrolment = new DeviceEnrolmentService(
                jdbc, new FakeDeviceClientProvisioner(), grants, new InProcessRateLimiter(clock), clock);
        JdbcAuditRecorder audit =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        displays = new KitchenDeviceDisplayService(
                new JdbcKitchenDeviceDisplayStore(jdbc),
                new JdbcKitchenStore(jdbc),
                enrolment,
                new FakeConfigurationResolver(),
                audit,
                clock);
        devices = new KitchenDeviceService(enrolment, displays, audit, clock);

        insertHierarchy();
    }

    // -------------------------------------------------------------- provisioning

    @Test
    @DisplayName("approving a wall display gives it a configuration row (the whole branch), and a touch KDS none")
    void aWallGetsARowAndATouchDisplayDoesNot() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);
        DevicePrincipalView touch = enrol(DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_KDS);

        assertThat(displays.displaysOf(TENANT, BRAND, LOCATION))
                .containsOnlyKeys(wall.id())
                .doesNotContainKey(touch.id());
        Display row = displayOf(wall);
        assertThat(row.station())
                .as("the whole branch until a manager picks one")
                .isNull();
        assertThat(row.version()).isEqualTo(1);
        assertThat(row.lastReadAt()).isNull();
    }

    @Test
    @DisplayName("a tablet approved as a wall is a wall: it gets the row and the wall's role")
    void aTouchRequestApprovedAsAWallIsAWall() {
        DevicePrincipalView device = enrol(DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_VDU);

        assertThat(displays.displaysOf(TENANT, BRAND, LOCATION)).containsKey(device.id());
        assertThat(jdbc.sql("""
                        SELECT r.code FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.principal_subject = :subject
                        """)
                        .param("subject", subjectOf(device))
                        .query(String.class)
                        .list())
                .containsExactly("kitchen-vdu-device");
        assertThat(jdbc.sql("""
                        SELECT change_document -> 'deviceClass' ->> 'after',
                               change_document -> 'requestedClass' ->> 'after'
                          FROM audit.audit_events WHERE action_code = 'kitchen.device.enrolled'
                        """)
                        .query((rs, n) -> rs.getString(1) + "<-" + rs.getString(2))
                        .single())
                .as("the audit fact records the class approved and the class requested")
                .isEqualTo("KITCHEN_VDU<-KITCHEN_KDS");
    }

    @Test
    @DisplayName("the class-to-role map is closed: each class has exactly one role and no two share it")
    void eachClassHasItsOwnRole() {
        Set<String> roles = new java.util.HashSet<>();
        for (DevicePrincipalClass deviceClass : DevicePrincipalClass.values()) {
            assertThat(roles.add(KitchenDeviceService.roleOf(deviceClass).code()))
                    .as("%s must not share a role with another class", deviceClass)
                    .isTrue();
        }
    }

    // ---------------------------------------------------------------- configuring

    @Test
    @DisplayName("a manager points a wall at a station, then back at the whole branch; every change is audited")
    void configuringAStationIsVersionedAndAudited() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);

        Display first = displays.configure(TENANT, BRAND, LOCATION, wall.id(), GRILL, 1, "manager-1");
        assertThat(stationOf(first).code()).isEqualTo("GRILL");
        assertThat(first.version()).isEqualTo(2);

        Display second = displays.configure(TENANT, BRAND, LOCATION, wall.id(), BAR, 2, "manager-1");
        assertThat(stationOf(second).code()).isEqualTo("BAR");

        Display third = displays.configure(TENANT, BRAND, LOCATION, wall.id(), null, 3, "manager-1");
        assertThat(third.station()).isNull();
        assertThat(third.version()).isEqualTo(4);

        assertThat(jdbc.sql("""
                        SELECT coalesce(change_document -> 'station' ->> 'before', 'whole branch') || ' -> '
                            || coalesce(change_document -> 'station' ->> 'after', 'whole branch')
                          FROM audit.audit_events
                         WHERE action_code = 'kitchen.device.display_configured'
                         ORDER BY occurred_at, id
                        """).query(String.class).list())
                .as("the audit fact names the station before and after, by its stable code")
                .containsExactlyInAnyOrder("whole branch -> GRILL", "GRILL -> BAR", "BAR -> whole branch");
    }

    @Test
    @DisplayName("a second manager's save from a stale form is refused, naming both versions")
    void aStaleVersionIsRefused() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);
        displays.configure(TENANT, BRAND, LOCATION, wall.id(), GRILL, 1, "manager-1");

        assertThatThrownBy(() -> displays.configure(TENANT, BRAND, LOCATION, wall.id(), BAR, 1, "manager-2"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(stationOf(displayOf(wall)).code()).isEqualTo("GRILL");
    }

    @Test
    @DisplayName(
            "only an active wall display has a configuration: a touch KDS, a revoked wall and a sibling's device are refused")
    void onlyAnActiveWallCanBeConfigured() {
        DevicePrincipalView touch = enrol(DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_KDS);
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);

        assertThatThrownBy(() -> displays.configure(TENANT, BRAND, LOCATION, touch.id(), GRILL, 1, "manager-1"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE));
        assertThatThrownBy(() -> displays.configure(TENANT, BRAND, SIBLING, wall.id(), GRILL, 1, "manager-1"))
                .as("a device of this branch is not found from the sibling's side")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        devices.revoke(TENANT, BRAND, LOCATION, wall.id(), "TV removed", "manager-1");
        assertThatThrownBy(() -> displays.configure(TENANT, BRAND, LOCATION, wall.id(), GRILL, 1, "manager-1"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE));
    }

    @Test
    @DisplayName(
            "a station must be an active station of this branch: another branch's, an archived one and an unknown one are refused")
    void aStationMustBeAnActiveStationOfThisBranch() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);

        for (UUID bad : new UUID[] {SIBLINGS_STATION, OLD_SIDE, UUID.randomUUID()}) {
            assertThatThrownBy(() -> displays.configure(TENANT, BRAND, LOCATION, wall.id(), bad, 1, "manager-1"))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(displayOf(wall).version()).as("nothing was written").isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the database itself refuses a display pointed at another branch's station, or placed at another branch")
    void theCompositeKeysSayWhatTheServiceSays() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);

        assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE kitchen.device_displays SET station_id = :station
                                 WHERE tenant_id = :tenant AND device_id = :device
                                """)
                        .param("station", SIBLINGS_STATION)
                        .param("tenant", TENANT)
                        .param("device", wall.id())
                        .update())
                .hasMessageContaining("fk_device_display_station");
        assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE kitchen.device_displays SET location_id = :location
                                 WHERE tenant_id = :tenant AND device_id = :device
                                """)
                        .param("location", SIBLING)
                        .param("tenant", TENANT)
                        .param("device", wall.id())
                        .update())
                .hasMessageContaining("fk_device_display_device");
        assertThat(jdbc.sql("""
                        SELECT has_table_privilege('horecaos_application', 'kitchen.device_displays', 'SELECT,INSERT,UPDATE')
                        """).query(Boolean.class).single())
                .as("the application role can reach the table (a missing GRANT is invisible until production)")
                .isTrue();
    }

    // ------------------------------------------------------------------- the wall

    @Test
    @DisplayName(
            "the caller's own wall is found from its subject, with the station the server holds; nobody else is a wall")
    void theWallCallerIsTheEnrolledWallAndNobodyElse() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);
        DevicePrincipalView touch = enrol(DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_KDS);
        displays.configure(TENANT, BRAND, LOCATION, wall.id(), GRILL, 1, "manager-1");

        assertThat(displays.wallCaller(subjectOf(wall), TENANT, LOCATION)).hasValueSatisfying(caller -> {
            assertThat(caller.deviceId()).isEqualTo(wall.id());
            assertThat(caller.stationId()).isEqualTo(GRILL);
        });
        assertThat(displays.wallCaller(subjectOf(touch), TENANT, LOCATION))
                .as("a touch KDS is not a wall: its request's station is honoured as it always was")
                .isEmpty();
        assertThat(displays.wallCaller("a-staff-subject", TENANT, LOCATION)).isEmpty();
        assertThat(displays.wallCaller(subjectOf(wall), TENANT, SIBLING))
                .as("a wall at another branch is not this branch's wall")
                .isEmpty();

        devices.revoke(TENANT, BRAND, LOCATION, wall.id(), "TV removed", "manager-1");
        assertThat(displays.wallCaller(subjectOf(wall), TENANT, LOCATION)).isEmpty();
    }

    @Test
    @DisplayName(
            "a wall whose configuration row is gone is still a wall: the whole branch, never the request's station")
    void aWallWithoutARowIsStillAWall() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);
        jdbc.sql("DELETE FROM kitchen.device_displays WHERE device_id = :id")
                .param("id", wall.id())
                .update();

        WallCaller caller =
                displays.wallCaller(subjectOf(wall), TENANT, LOCATION).orElseThrow();

        assertThat(caller.deviceId()).isEqualTo(wall.id());
        assertThat(caller.stationId())
                .as("null means the whole branch; the controller applies it instead of the request's")
                .isNull();
        displays.recordWallRead(caller, TENANT);
    }

    @Test
    @DisplayName("a read stamps last_read_at at most once a minute and never moves the version a manager edits against")
    void theWallsReadIsStampedAtMostOncePerMinuteWithoutMovingTheVersion() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);
        WallCaller caller =
                displays.wallCaller(subjectOf(wall), TENANT, LOCATION).orElseThrow();

        displays.recordWallRead(caller, TENANT);
        Instant firstStamp = lastRead(wall);
        assertThat(firstStamp).isEqualTo(START);

        clock.advance(Duration.ofSeconds(10));
        displays.recordWallRead(caller, TENANT);
        assertThat(lastRead(wall))
                .as("ten seconds later: the wall polls every ten, the row is written every minute")
                .isEqualTo(firstStamp);

        clock.advance(Duration.ofSeconds(55));
        displays.recordWallRead(caller, TENANT);
        assertThat(lastRead(wall)).as("sixty-five seconds after the stamp").isEqualTo(START.plusSeconds(65));

        assertThat(displayOf(wall).version())
                .as("three reads and the manager's open form is still at version 1")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a wall that has not read for the configured period is 'not seen'; one that read a moment ago is not")
    void notSeenFollowsTheLastReadAndTheConfiguredPeriod() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);
        Display neverRead = displayOf(wall);
        assertThat(neverRead.notSeenAfter()).isEqualTo(Duration.ofMinutes(5));

        assertThat(neverRead.notSeen(wall.enrolledAt(), START.plus(Duration.ofMinutes(4))))
                .isFalse();
        assertThat(neverRead.notSeen(wall.enrolledAt(), START.plus(Duration.ofMinutes(6))))
                .as("never read, counted from its enrolment")
                .isTrue();

        displays.recordWallRead(
                displays.wallCaller(subjectOf(wall), TENANT, LOCATION).orElseThrow(), TENANT);
        Display justRead = displayOf(wall);
        assertThat(justRead.notSeen(wall.enrolledAt(), START.plus(Duration.ofMinutes(4))))
                .isFalse();
        assertThat(justRead.notSeen(wall.enrolledAt(), START.plus(Duration.ofMinutes(6))))
                .isTrue();

        KitchenDeviceDisplayService custom = new KitchenDeviceDisplayService(
                new JdbcKitchenDeviceDisplayStore(jdbc),
                new JdbcKitchenStore(jdbc),
                enrolment,
                new FakeConfigurationResolver(Map.of("kitchen.display.not_seen_after_minutes", 20)),
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                clock);
        assertThat(Objects.requireNonNull(
                                custom.displaysOf(TENANT, BRAND, LOCATION).get(wall.id()))
                        .notSeenAfter())
                .as("the period is configuration, not a constant")
                .isEqualTo(Duration.ofMinutes(20));
    }

    // -------------------------------------------------------------- who am I

    @Test
    @DisplayName("a wall reads its own record: where it is, the branch's own zone and the station with its three names")
    void aWallReadsItsOwnRecord() {
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);
        displays.configure(TENANT, BRAND, LOCATION, wall.id(), GRILL, 1, "manager-1");

        var self = displays.selfOf(subjectOf(wall)).orElseThrow();

        assertThat(self.device().id()).isEqualTo(wall.id());
        assertThat(self.device().deviceClass()).isEqualTo(DevicePrincipalClass.KITCHEN_VDU);
        assertThat(self.device().tenantId()).isEqualTo(TENANT);
        assertThat(self.device().brandId()).isEqualTo(BRAND);
        assertThat(self.device().locationId()).isEqualTo(LOCATION);
        assertThat(self.location().displayName()).isEqualTo("Location A");
        assertThat(self.location().timezone()).isEqualTo("Asia/Tashkent");
        assertThat(Objects.requireNonNull(self.station()).displayNameRu()).isEqualTo("Гриль");
        assertThat(Objects.requireNonNull(self.station()).displayNameUz()).isEqualTo("Gril");
        assertThat(Objects.requireNonNull(self.station()).displayNameEn()).isEqualTo("Grill");
    }

    @Test
    @DisplayName("a touch KDS reads its record without a station; a person and a revoked device read nothing")
    void onlyAnActiveKitchenDeviceReadsItsRecord() {
        DevicePrincipalView touch = enrol(DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_KDS);
        DevicePrincipalView wall = enrol(DevicePrincipalClass.KITCHEN_VDU, DevicePrincipalClass.KITCHEN_VDU);

        assertThat(displays.selfOf(subjectOf(touch)))
                .hasValueSatisfying(self -> assertThat(self.station()).isNull());
        assertThat(displays.selfOf("a-staff-subject")).isEmpty();

        devices.revoke(TENANT, BRAND, LOCATION, wall.id(), "TV removed", "manager-1");
        assertThat(displays.selfOf(subjectOf(wall))).isEmpty();
    }

    // ----------------------------------------------------------------- helpers

    private DevicePrincipalView enrol(DevicePrincipalClass requested, DevicePrincipalClass approved) {
        String userCode = enrolment
                .beginEnrolment(new BeginEnrolment(requested, "TV"), "caller-" + UUID.randomUUID())
                .userCode();
        return devices.approve(TENANT, BRAND, LOCATION, userCode, "Device " + approved, approved, "manager-1");
    }

    private Display displayOf(DevicePrincipalView device) {
        return Objects.requireNonNull(
                displays.displaysOf(TENANT, BRAND, LOCATION).get(device.id()));
    }

    private static JdbcKitchenStore.StationRow stationOf(Display display) {
        return Objects.requireNonNull(display.station());
    }

    private String subjectOf(DevicePrincipalView device) {
        return jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", device.id())
                .query(String.class)
                .single();
    }

    private Instant lastRead(DevicePrincipalView device) {
        return jdbc.sql("SELECT last_read_at FROM kitchen.device_displays WHERE device_id = :id")
                .param("id", device.id())
                .query((rs, n) -> rs.getObject("last_read_at", java.time.OffsetDateTime.class)
                        .toInstant())
                .single();
    }

    private void insertHierarchy() {
        jdbc.sql("""
                        INSERT INTO tenant.tenants
                            (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                        VALUES (:id, 'tenant-kitchen-displays', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                        """).param("id", TENANT).update();
        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                        VALUES (:id, :tenantId, 'BRAND_A', 'brand-a', 'Brand A', 'ACTIVE', 0)
                        """).param("id", BRAND).param("tenantId", TENANT).update();
        for (Object[] location :
                new Object[][] {{LOCATION, "LOC_A", "loc-a", "Location A"}, {SIBLING, "LOC_B", "loc-b", "Location B"}
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
        station(GRILL, LOCATION, "GRILL", "GRILL", "Гриль", "Gril", "Grill", "ACTIVE");
        station(BAR, LOCATION, "BAR", "BAR", "Бар", "Bar", "Bar", "ACTIVE");
        station(OLD_SIDE, LOCATION, "OLD_SIDE", "HOT", "Старая линия", "Eski liniya", "Old side", "ARCHIVED");
        station(SIBLINGS_STATION, SIBLING, "GRILL", "GRILL", "Гриль", "Gril", "Grill", "ACTIVE");
    }

    private void station(
            UUID id, UUID location, String code, String role, String ru, String uz, String en, String status) {
        jdbc.sql("""
                        INSERT INTO kitchen.stations
                            (id, tenant_id, brand_id, location_id, code, role, display_name_ru, display_name_uz,
                             display_name_en, sort_order, is_fallback, status, version)
                        VALUES (:id, :tenantId, :brandId, :locationId, :code, :role, :ru, :uz, :en, 0, false, :status, 1)
                        """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("code", code)
                .param("role", role)
                .param("ru", ru)
                .param("uz", uz)
                .param("en", en)
                .param("status", status)
                .update();
    }

    /** A clock a test can move: the wall's once-a-minute stamp is a duration, asserted against one. */
    private static final class MovingClock extends Clock {

        private Instant now;

        private MovingClock(Instant now) {
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
