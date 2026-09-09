package uz.horecaos.platform.kitchen.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * The kitchen half of ADR 0079's device enrolment: the audit trail an
 * approval and a revocation leave, and that a branch's device list is scoped
 * to its own branch.
 *
 * <p>The Keycloak half is a fake here for the same reason it is in {@code
 * DeviceEnrolmentServiceTests} — this class proves what {@code kitchen} owns
 * (the audit fact and the capability gate's own role choice), not what {@code
 * iam}'s Keycloak adapter does, which is proven separately against a real
 * realm.
 */
class KitchenDeviceServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac140001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac140002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac140003");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac140004");

    private static final Instant NOW = Instant.parse("2026-09-09T11:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private DeviceEnrolmentPort enrolment;
    private KitchenDeviceService devices;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for kitchen device tests");
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

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

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
        devices = new KitchenDeviceService(
                enrolment, new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()), clock);

        insertHierarchy();
    }

    @Test
    void approvingWritesASecurityAuditFactNamingTheManagerAndTheDevice() {
        String userCode = begin();

        DevicePrincipalView device = devices.approve(TENANT, BRAND, LOCATION, userCode, "Line 1 KDS", "manager-1");

        assertThat(jdbc.sql("""
                        SELECT actor_subject, target_type, target_id
                          FROM audit.audit_events
                         WHERE action_code = 'kitchen.device.enrolled'
                        """)
                        .query((rs, n) -> Map.of(
                                "actor", rs.getString("actor_subject"),
                                "targetType", rs.getString("target_type"),
                                "targetId", rs.getObject("target_id", UUID.class)))
                        .list())
                .as("the enrolment audit fact names the human approver and the device, not the other way round")
                .containsExactly(
                        Map.of("actor", "manager-1", "targetType", "iam.device_principal", "targetId", device.id()));
    }

    @Test
    void revokingWritesASecurityAuditFactAndASecondRevokeWritesNone() {
        String userCode = begin();
        DevicePrincipalView device = devices.approve(TENANT, BRAND, LOCATION, userCode, "Line 1 KDS", "manager-1");

        boolean first = devices.revoke(TENANT, BRAND, LOCATION, device.id(), "Screen replaced", "manager-1");
        boolean second = devices.revoke(TENANT, BRAND, LOCATION, device.id(), "Pressed again", "manager-1");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'kitchen.device.revoked'")
                        .query(Long.class)
                        .single())
                .as("revoking an already-revoked device is not a second security event")
                .isEqualTo(1L);
    }

    @Test
    void aBranchsDeviceListNeverIncludesASiblingBranchsDevice() {
        String userCode = begin();
        devices.approve(TENANT, BRAND, LOCATION, userCode, "Line 1 KDS", "manager-1");

        assertThat(devices.list(TENANT, LOCATION)).hasSize(1);
        assertThat(devices.list(TENANT, SIBLING_LOCATION)).isEmpty();
    }

    private String begin() {
        return enrolment
                .beginEnrolment(new BeginEnrolment(DevicePrincipalClass.KITCHEN_KDS, "line-1"), "caller-a")
                .userCode();
    }

    private void insertHierarchy() {
        jdbc.sql("""
                        INSERT INTO tenant.tenants
                            (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                        VALUES (:id, 'tenant-kitchen-devices', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
}
