package uz.horecaos.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.ProvisionalRetentionGuard;
import uz.horecaos.platform.observability.RetentionWindowController.RetentionWindowResponse;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.application.CartRetentionSweeper;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.SettlementCalendarPort;
import uz.horecaos.platform.telemetry.api.TelemetryConfigurationKeys;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.TrackRetentionSweeper;
import uz.horecaos.platform.telemetry.infrastructure.persistence.TrackRetentionWindowProvider;

/**
 * What the retention card is told (Settings 10.11, ADR 0109): per retention setting, the tenant's own value, the window the job
 * really applies, the floor, whether the window is the tenant's alone, and whether the number is still provisional.
 *
 * <p>The numbers come from the jobs that do the deleting. The courier-track case stores a value below the platform default and
 * asserts the effective window did <em>not</em> follow it (a shared daily partition cannot be dropped per tenant), so a card
 * that merely echoed the stored value fails it.
 */
class RetentionWindowControllerTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-8000-7000-8000-0000000000a1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

    /** ADR 0042's pilot calendar, which ADR 0045 derives the track floor from: a 7-day period and a 7-day dispute window. */
    private static final SettlementCalendarPort PILOT_CALENDAR = new SettlementCalendarPort() {
        @Override
        public int settlementPeriodDays() {
            return 7;
        }

        @Override
        public int statementDisputeDays() {
            return 7;
        }
    };

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;

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
        jdbc.sql("DELETE FROM tenant.configuration_values").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'retention-windows', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private RetentionWindowController controller(String confirmed, String reference) {
        CartRetentionSweeper carts = new CartRetentionSweeper(new JdbcCartStore(jdbc), jdbc, CLOCK, 90, 500, false);
        TrackRetentionSweeper sweeper = new TrackRetentionSweeper(jdbc, new JdbcTelemetryStore(jdbc), CLOCK, false, 30);
        ProvisionalRetentionGuard guard =
                new ProvisionalRetentionGuard(new MockEnvironment(), confirmed, reference, "AUTO");
        return new RetentionWindowController(
                List.of(carts, new TrackRetentionWindowProvider(jdbc, sweeper, PILOT_CALENDAR)), guard);
    }

    private static RetentionWindowResponse window(Map<String, RetentionWindowResponse> windows, String code) {
        return Objects.requireNonNull(windows.get(code), code);
    }

    private static Map<String, RetentionWindowResponse> byCode(List<RetentionWindowResponse> windows) {
        return windows.stream().collect(Collectors.toMap(RetentionWindowResponse::code, window -> window));
    }

    private void storeTenantValue(String code, int value) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id, value_type, integer_value, set_by)
                VALUES (:id, :key, 'TENANT', :tenantId, 'INTEGER', :value, 'a-test')
                """)
                .param("id", UUID.randomUUID())
                .param("key", code)
                .param("tenantId", TENANT)
                .param("value", (long) value)
                .update();
    }

    @Test
    @DisplayName("a tenant that stored nothing sees the platform defaults, the floors and which windows are its own")
    void defaultsAndFloors() {
        Map<String, RetentionWindowResponse> windows = byCode(controller("", "").windows(TENANT));

        assertThat(windows)
                .containsOnlyKeys(
                        OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE,
                        TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE);

        RetentionWindowResponse carts = window(windows, OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE);
        assertThat(carts.unit()).isEqualTo("DAYS");
        assertThat(carts.configured()).isNull();
        assertThat(carts.effective()).isEqualTo(90);
        assertThat(carts.floor()).isEqualTo(OrderingConfigurationKeys.CART_RETENTION_FLOOR_DAYS);
        assertThat(carts.appliesTo()).isEqualTo("PER_TENANT");
        assertThat(carts.enforcedBy()).isEqualTo("CartRetentionSweeper");

        RetentionWindowResponse tracks = window(windows, TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE);
        assertThat(tracks.effective()).isEqualTo(30);
        assertThat(tracks.floor())
                .as("the settlement period plus the statement dispute window")
                .isEqualTo(14);
        assertThat(tracks.appliesTo())
                .as("a daily partition holds every tenant's rows, so it cannot be dropped per tenant")
                .isEqualTo("ALL_TENANTS");
        assertThat(tracks.enforcedBy()).isEqualTo("TrackRetentionSweeper");
    }

    @Test
    @DisplayName(
            "a shorter cart value is the effective one; a shorter courier-track value is not, and the card says so")
    void aShorterValueIsHonouredWhereItCanBe() {
        storeTenantValue(OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE, 30);
        storeTenantValue(TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE, 14);

        Map<String, RetentionWindowResponse> windows = byCode(controller("", "").windows(TENANT));

        RetentionWindowResponse carts = window(windows, OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE);
        assertThat(carts.configured()).isEqualTo(30);
        assertThat(carts.effective()).isEqualTo(30);

        RetentionWindowResponse tracks = window(windows, TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE);
        assertThat(tracks.configured()).isEqualTo(14);
        assertThat(tracks.effective())
                .as("the partitions are dropped at the longest window anyone needs, not at this tenant's shorter one")
                .isEqualTo(30);
        assertThat(tracks.appliesTo()).isEqualTo("ALL_TENANTS");
    }

    @Test
    @DisplayName("a longer courier-track value is the effective window for everyone, because the partition is shared")
    void aLongerTrackValueLengthensTheSharedWindow() {
        storeTenantValue(TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE, 60);

        RetentionWindowResponse tracks = window(
                byCode(controller("", "").windows(TENANT)), TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE);

        assertThat(tracks.effective()).isEqualTo(60);
    }

    @Test
    @DisplayName("each setting says whether it is still provisional: until legal's answer is recorded with a reference")
    void provisionalUntilConfirmed() {
        assertThat(controller("", "").windows(TENANT))
                .as("nothing recorded")
                .allSatisfy(window -> assertThat(window.provisional()).isTrue());
        assertThat(controller("all", "").windows(TENANT))
                .as("a confirmation nobody can point at is not one")
                .allSatisfy(window -> assertThat(window.provisional()).isTrue());

        Map<String, RetentionWindowResponse> partial =
                byCode(controller(OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE, "LEGAL-2026-117")
                        .windows(TENANT));
        assertThat(window(partial, OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE)
                        .provisional())
                .isFalse();
        assertThat(window(partial, TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE)
                        .provisional())
                .isTrue();

        assertThat(controller("all", "LEGAL-2026-117").windows(TENANT))
                .allSatisfy(window -> assertThat(window.provisional()).isFalse());
    }
}
