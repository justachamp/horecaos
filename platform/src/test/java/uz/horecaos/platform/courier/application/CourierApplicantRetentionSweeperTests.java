package uz.horecaos.platform.courier.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.configuration.RetentionWindow;
import uz.horecaos.platform.courier.api.CourierConfigurationKeys;
import uz.horecaos.platform.courier.domain.EngagementStatus;
import uz.horecaos.platform.courier.domain.RegistrationWarningState;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0092 as decided on 2026-09-11: a courier application nobody verified is
 * erased twelve months after it was last touched, and a verified courier is
 * not an applicant.
 *
 * <p>Couriers are written through the store at the real present, as
 * registration writes them, and the sweep's clock is moved past them.
 */
class CourierApplicantRetentionSweeperTests {

    private static final String KEY = CourierConfigurationKeys.APPLICANT_RETENTION_MONTHS_CODE;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCourierStore couriers;
    private FieldProtection protection;
    private List<AuditFact> facts;
    private UUID tenantId;
    private UUID typeId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        couriers = new JdbcCourierStore(jdbc);
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                        Clock.systemUTC()),
                "local"));
        facts = new ArrayList<>();

        tenantId = UUID.randomUUID();
        typeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'applicants', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'FOOT', 'On foot', 'FOOT')
                """).param("id", typeId).param("t", tenantId).update();
    }

    @Test
    @DisplayName("an application nobody verified for twelve months is erased, archived and audited")
    void anUnverifiedApplicationIsErasedAfterTwelveMonths() {
        UUID applicant = register("K-001", "Alisher Navoiy", false);

        assertThat(sweeperAt(Instant.now().plus(360, ChronoUnit.DAYS)).runOnce())
                .as("under twelve months, nothing")
                .isZero();
        assertThat(sweeperAt(Instant.now().plus(370, ChronoUnit.DAYS)).runOnce())
                .isEqualTo(1);

        Map<String, Object> row = jdbc.sql("""
                SELECT c.status, c.protected_full_name, e.status AS engagement_status,
                       e.protected_registration_ref
                  FROM fulfillment.couriers c
                  JOIN fulfillment.courier_engagements e ON e.tenant_id = c.tenant_id AND e.courier_id = c.id
                 WHERE c.id = :id
                """).param("id", applicant).query().singleRow();
        assertThat(row).containsEntry("status", "ARCHIVED").containsEntry("engagement_status", "ENDED");
        assertThat(row.get("protected_registration_ref")).isNull();
        assertThat(reveal(applicant, java.util.Objects.requireNonNull((String) row.get("protected_full_name"))))
                .as("the name decrypts to a tombstone, not to the person")
                .isEqualTo("ERASED");
        assertThat(facts).extracting(AuditFact::actionCode).containsExactly("courier.applicant.erased");
    }

    @Test
    @DisplayName("a verified courier is not an applicant, however long since anyone touched them")
    void aVerifiedCourierIsKept() {
        UUID courier = register("K-002", "Zahiriddin Bobur", true);

        assertThat(sweeperAt(Instant.now().plus(800, ChronoUnit.DAYS)).runOnce())
                .isZero();
        assertThat(jdbc.sql("SELECT status FROM fulfillment.couriers WHERE id = :id")
                        .param("id", courier)
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName(
            "a tenant that sets a shorter window has its dead applications erased at that window, and its neighbour keeps twelve months")
    void aShorterTenantWindowIsHonoured() {
        UUID neighbourTenant = seedTenant("neighbour");
        UUID neighbourType = typeOf(neighbourTenant);
        storeTenantMonths(tenantId, 6);
        UUID shortWindow = registerIn(tenantId, typeId, "K-010", "Short Window", false);
        UUID defaultWindow = registerIn(neighbourTenant, neighbourType, "K-011", "Default Window", false);

        assertThat(sweeperAt(Instant.now().plus(200, ChronoUnit.DAYS)).runOnce())
                .as("this tenant asked for six months; before batch 21 the sweep took the longer of twelve and the "
                        + "largest stored value and kept this application for six months more without saying so")
                .isEqualTo(1);
        assertThat(statusOf(shortWindow)).isEqualTo("ARCHIVED");
        assertThat(statusOf(defaultWindow))
                .as("the neighbour asked for nothing and keeps the platform's twelve months")
                .isEqualTo("ACTIVE");

        assertThat(sweeperAt(Instant.now().plus(370, ChronoUnit.DAYS)).runOnce())
                .isEqualTo(1);
        assertThat(statusOf(defaultWindow)).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("a longer tenant window is honoured too, and a value below the floor is raised to it")
    void aLongerWindowAndTheFloor() {
        storeTenantMonths(tenantId, 24);
        UUID applicant = register("K-012", "Long Window", false);
        assertThat(sweeperAt(Instant.now().plus(400, ChronoUnit.DAYS)).runOnce())
                .as("twenty-four months were asked for")
                .isZero();
        assertThat(sweeperAt(Instant.now().plus(740, ChronoUnit.DAYS)).runOnce())
                .isEqualTo(1);
        assertThat(statusOf(applicant)).isEqualTo("ARCHIVED");

        jdbc.sql("UPDATE tenant.configuration_values SET integer_value = 0 WHERE key_code = :keyCode")
                .param("keyCode", KEY)
                .update();
        UUID fresh = register("K-013", "Floor", false);
        assertThat(sweeperAt(Instant.now().plus(10, ChronoUnit.DAYS)).runOnce())
                .as("a stored zero is raised to the one-month floor, so an application touched ten days ago is safe")
                .isZero();
        assertThat(statusOf(fresh)).isEqualTo("ACTIVE");
        assertThat(sweeperAt(Instant.now().plus(40, ChronoUnit.DAYS)).runOnce()).isEqualTo(1);
    }

    @Test
    @DisplayName("the window the card shows is the window the sweep uses")
    void theWindowForATenantIsWhatTheSweepUses() {
        RetentionWindow inherited = sweeperAt(Instant.now()).windowFor(tenantId);
        assertThat(inherited.code()).isEqualTo(KEY);
        assertThat(inherited.unit()).isEqualTo(RetentionWindow.Unit.MONTHS);
        assertThat(inherited.configured()).isNull();
        assertThat(inherited.effective()).isEqualTo(12);
        assertThat(inherited.floor()).isEqualTo(CourierConfigurationKeys.APPLICANT_RETENTION_FLOOR_MONTHS);
        assertThat(inherited.scope()).isEqualTo(RetentionWindow.Scope.PER_TENANT);

        storeTenantMonths(tenantId, 6);
        RetentionWindow own = sweeperAt(Instant.now()).windowFor(tenantId);
        assertThat(own.configured()).isEqualTo(6);
        assertThat(own.effective())
                .as("a shorter value is the effective one now")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("report-only mode counts what it would erase and erases nothing")
    void reportOnlyErasesNothing() {
        UUID applicant = register("K-014", "Report Only", false);
        Instant later = Instant.now().plus(400, ChronoUnit.DAYS);

        assertThat(reportOnlyAt(later).runOnce()).isZero();
        assertThat(statusOf(applicant)).isEqualTo("ACTIVE");
        assertThat(couriers.countUnverifiedApplicants(later, 12, 1)).isEqualTo(1);
        assertThat(sweeperAt(later).runOnce()).isEqualTo(1);
    }

    // ------------------------------------------------------------- fixtures

    private CourierApplicantRetentionSweeper sweeperAt(Instant now) {
        return new CourierApplicantRetentionSweeper(
                new CourierApplicantRetentionSweeper.Eraser(couriers, protection, facts::add),
                jdbc,
                couriers,
                Clock.fixed(now, ZoneOffset.UTC),
                12,
                100,
                false);
    }

    private CourierApplicantRetentionSweeper reportOnlyAt(Instant now) {
        return new CourierApplicantRetentionSweeper(
                new CourierApplicantRetentionSweeper.Eraser(couriers, protection, facts::add),
                jdbc,
                couriers,
                Clock.fixed(now, ZoneOffset.UTC),
                12,
                100,
                true);
    }

    private void storeTenantMonths(UUID tenant, int months) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id,
                    value_type, integer_value, set_by)
                VALUES (:id, :keyCode, 'TENANT', :tenantId, 'INTEGER', :months, 'a-test')
                """)
                .param("id", UUID.randomUUID())
                .param("keyCode", KEY)
                .param("tenantId", tenant)
                .param("months", (long) months)
                .update();
    }

    private UUID seedTenant(String slug) {
        UUID tenant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", slug).update();
        return tenant;
    }

    private UUID typeOf(UUID tenant) {
        UUID type = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'FOOT', 'On foot', 'FOOT')
                """).param("id", type).param("t", tenant).update();
        return type;
    }

    private String statusOf(UUID courier) {
        return jdbc.sql("SELECT status FROM fulfillment.couriers WHERE id = :id")
                .param("id", courier)
                .query(String.class)
                .single();
    }

    private UUID register(String reference, String fullName, boolean verified) {
        return registerIn(tenantId, typeId, reference, fullName, verified);
    }

    private UUID registerIn(UUID tenantId, UUID typeId, String reference, String fullName, boolean verified) {
        UUID courierId = UUID.randomUUID();
        String protectedName = protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef("fulfillment.couriers", "protected_full_name", courierId),
                        fullName)
                .serialize();
        couriers.insertCourier(new CourierRow(
                courierId, tenantId, typeId, "subject-" + reference, reference, protectedName, "ACTIVE", 1));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        couriers.insertEngagement(new EngagementRow(
                UUID.randomUUID(),
                tenantId,
                courierId,
                verified ? EngagementStatus.ACTIVE : EngagementStatus.PENDING_VERIFICATION,
                today,
                null,
                verified ? "protected-registration-ref" : null,
                verified ? today.plusYears(2) : null,
                verified ? Instant.now() : null,
                verified ? "verifier" : null,
                verified ? VerificationMethod.MANUAL_ATTESTATION : null,
                null,
                verified ? today.plusMonths(6) : null,
                RegistrationWarningState.VALID,
                null,
                1));
        return courierId;
    }

    private String reveal(UUID courierId, String stored) {
        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(stored),
                new FieldProtection.RecordRef("fulfillment.couriers", "protected_full_name", courierId),
                "test.assert-erased");
    }
}
