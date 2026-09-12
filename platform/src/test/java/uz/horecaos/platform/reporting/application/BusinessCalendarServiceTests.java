package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcBusinessCalendarStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * 10.10b against PostgreSQL: a tenant's own weekend, its own holidays, and the
 * boundary editor {@code BusinessDayService.setBoundary} had no caller for.
 *
 * <p>{@code aTenantsWeekendRoundTripsThroughPostgres} is the case that matters
 * most in this file: {@code tenant.business_calendars.weekend_days} is a
 * {@code smallint[]} column and {@link JdbcBusinessCalendarStore#setWeekendDays}
 * binds a boxed {@code Integer[]} straight through {@code JdbcClient}. This
 * codebase has exactly one other array-column writer
 * ({@code JdbcAudienceStore.savePredicates}) and it deliberately avoids a raw
 * array parameter, going through a JSON string and
 * {@code jsonb_array_elements_text} instead — so this test exists to prove the
 * simpler binding this store chose actually reaches PostgreSQL as an array,
 * rather than trusting that it does.
 */
class BusinessCalendarServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3700-7000-8000-0000000000d1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3700-7000-8000-0000000000d2");
    private static final ActorRef ACTOR = ActorRef.user("owner-1", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private BusinessCalendarService calendars;

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

        jdbc.sql("TRUNCATE TABLE reporting.business_day_policies").update();
        jdbc.sql("TRUNCATE TABLE tenant.business_calendar_holidays").update();
        jdbc.sql("TRUNCATE TABLE tenant.business_calendars").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        for (UUID tenantId : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("DELETE FROM tenant.tenants WHERE id = :id")
                    .param("id", tenantId)
                    .update();
            jdbc.sql("""
                            INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                                default_timezone, status, version)
                            VALUES (:id, :slug, 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                            """)
                    .param("id", tenantId)
                    .param("slug", "pilot-" + tenantId)
                    .update();
        }

        Clock clock = Clock.fixed(Instant.parse("2026-09-12T05:00:00Z"), ZoneOffset.UTC);
        JdbcAuditRecorder audit =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        ApprovalService approvals = new JdbcApprovalService(
                jdbc,
                audit,
                clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                JsonMapper.builder().build());
        BusinessDayService businessDays = new BusinessDayService(new JdbcReportingStore(jdbc));
        calendars =
                new BusinessCalendarService(new JdbcBusinessCalendarStore(jdbc), businessDays, approvals, audit, clock);
    }

    @Test
    void aTenantsWeekendRoundTripsThroughPostgres() {
        assertThat(calendars.calendar(TENANT).weekendDays())
                .as("a tenant that never opened this screen trades every day")
                .isEmpty();

        calendars.setWeekendDays(TENANT, List.of(6, 7), ACTOR, "Friday and Saturday off");

        assertThat(calendars.calendar(TENANT).weekendDays())
                .as("the smallint[] column must round-trip the exact days written, not an empty "
                        + "array a silently-failed bind would leave behind")
                .containsExactly(6, 7);

        // Overwrite: the second write replaces rather than accumulates.
        calendars.setWeekendDays(TENANT, List.of(7), ACTOR, "back to one day");
        assertThat(calendars.calendar(TENANT).weekendDays()).containsExactly(7);
    }

    @Test
    void aWeekdayOutsideOneToSevenIsRefused() {
        assertThatThrownBy(() -> calendars.setWeekendDays(TENANT, List.of(0), ACTOR, "typo"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> calendars.setWeekendDays(TENANT, List.of(8), ACTOR, "typo"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aTenantsWeekendIsInvisibleToAnotherTenant() {
        calendars.setWeekendDays(TENANT, List.of(6, 7), ACTOR, "our weekend");

        assertThat(calendars.calendar(OTHER_TENANT).weekendDays())
                .as("tenant isolation: a sibling never inherits or sees this tenant's weekend")
                .isEmpty();
    }

    @Test
    void aRecurringHolidayAndADatedHolidayBothRoundTrip() {
        UUID navruz = calendars.addHoliday(TENANT, "Navruz", 3, 21, null, ACTOR, "national holiday");
        UUID oneOff = calendars.addHoliday(
                TENANT, "Lease renewal closure", null, null, LocalDate.of(2026, 11, 5), ACTOR, "landlord work");

        List<JdbcBusinessCalendarStore.TenantHoliday> holidays =
                calendars.calendar(TENANT).holidays();
        assertThat(holidays).hasSize(2);
        assertThat(holidays)
                .anySatisfy(h -> {
                    assertThat(h.id()).isEqualTo(navruz);
                    assertThat(h.month()).isEqualTo(3);
                    assertThat(h.day()).isEqualTo(21);
                    assertThat(h.date()).isNull();
                })
                .anySatisfy(h -> {
                    assertThat(h.id()).isEqualTo(oneOff);
                    assertThat(h.date()).isEqualTo(LocalDate.of(2026, 11, 5));
                });
    }

    @Test
    void aHolidayNeedsEitherAMonthAndDayOrADateNeverBoth() {
        assertThatThrownBy(() -> calendars.addHoliday(TENANT, "Broken", 3, 21, LocalDate.of(2026, 3, 21), ACTOR, "x"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> calendars.addHoliday(TENANT, "Broken", null, null, null, ACTOR, "x"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theSameRecurringDateTwiceIsAConflictNotADuplicateRow() {
        calendars.addHoliday(TENANT, "Navruz", 3, 21, null, ACTOR, "first");

        assertThatThrownBy(() -> calendars.addHoliday(TENANT, "Navruz again", 3, 21, null, ACTOR, "second"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void removingAHolidayTakesItOffTheCalendar() {
        UUID id = calendars.addHoliday(TENANT, "Navruz", 3, 21, null, ACTOR, "national holiday");

        calendars.removeHoliday(TENANT, id, ACTOR, "no longer observed");

        assertThat(calendars.calendar(TENANT).holidays()).isEmpty();
    }

    @Test
    void aBoundaryChangeProceedsImmediatelyUnderThePermissiveDefaultAndMarksARecutOutstanding() {
        // ApprovalAction.REPORTING_BUSINESS_DAY_BOUNDARY_CHANGE is
        // ALLOW_WITHOUT_APPROVAL and no tenant has authored a policy in this
        // test, so the very first call both raises and satisfies the
        // approval — unlike TENANT_COUNTRY_CHANGE, which waits.
        BusinessCalendarService.BoundaryChange change =
                calendars.changeBoundary(TENANT, LocalTime.of(9, 0), ACTOR, "we close well after midnight");

        assertThat(change.status()).isEqualTo(BusinessCalendarService.BoundaryChange.CHANGED);

        BusinessCalendarService.Calendar calendar = calendars.calendar(TENANT);
        assertThat(calendar.businessDayStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(calendar.boundaryVersion()).isEqualTo(2);
        assertThat(calendar.recutCompletedThrough())
                .as("a boundary change marks a recut outstanding rather than performing one inline")
                .isNull();
    }

    @Test
    void movingToTheSameBoundaryIsANoOp() {
        calendars.changeBoundary(TENANT, LocalTime.of(9, 0), ACTOR, "first move");

        BusinessCalendarService.BoundaryChange again =
                calendars.changeBoundary(TENANT, LocalTime.of(9, 0), ACTOR, "asked again");

        assertThat(again.status()).isEqualTo(BusinessCalendarService.BoundaryChange.UNCHANGED);
        assertThat(calendars.calendar(TENANT).boundaryVersion())
                .as("a no-op never bumps the version a real change would")
                .isEqualTo(2);
    }
}
