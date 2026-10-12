package uz.horecaos.platform.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.workforce.WorkforceKit.Person;

/**
 * ADR 0156, Physical model: the invariants the services rely on are the database's, not the code's. Each
 * test writes SQL the service would never write and expects the constraint to refuse it -- including the
 * composite keys that keep one tenant's person and branch out of another's rows.
 */
class WorkforceConstraintTests {

    private static TestDatabase.Handle db;

    private WorkforceKit kit;
    private Person cook;

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
        kit = WorkforceKit.over(db, Instant.parse("2026-10-08T04:00:00Z"));
        kit.reset();
        cook = kit.cook("cook");
    }

    // ---------------------------------------------------------------------------- the rota

    @Test
    @DisplayName(
            "two live roster entries of one person cannot overlap, wherever they are; adjacent ones and a cancelled one can")
    void theRosterExclusion() {
        entry(cook, "DRAFT", "2026-10-08T03:00:00Z", "2026-10-08T07:00:00Z");

        assertThatThrownBy(() -> entry(cook, "PUBLISHED", "2026-10-08T06:00:00Z", "2026-10-08T10:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_staff_roster_no_overlap");
        entry(cook, "DRAFT", "2026-10-08T07:00:00Z", "2026-10-08T10:00:00Z");
        entryCancelled(cook, "2026-10-08T04:00:00Z", "2026-10-08T05:00:00Z");
        assertThat(count("SELECT count(*) FROM workforce.staff_roster_entries")).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "an entry may not last past sixteen hours, carry a break over four, or be PUBLISHED without a publish stamp")
    void theRosterChecks() {
        assertThatThrownBy(() -> entry(cook, "DRAFT", "2026-10-08T00:00:00Z", "2026-10-08T16:00:01Z"))
                .hasMessageContaining("ck_staff_roster_window");
        assertThatThrownBy(() -> kit.jdbc
                        .sql("""
                                INSERT INTO workforce.staff_roster_entries
                                    (id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start,
                                     planned_end, planned_break_minutes, created_by)
                                VALUES (:id, :t, :b, :l, :m, 'DRAFT', '2026-10-08T03:00:00Z', '2026-10-08T07:00:00Z', 241, 'x')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", WorkforceKit.TENANT)
                        .param("b", WorkforceKit.BRAND)
                        .param("l", WorkforceKit.LOCATION)
                        .param("m", cook.memberId())
                        .update())
                .hasMessageContaining("ck_staff_roster_break");
        assertThatThrownBy(() -> kit.jdbc
                        .sql("""
                                INSERT INTO workforce.staff_roster_entries
                                    (id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start,
                                     planned_end, created_by)
                                VALUES (:id, :t, :b, :l, :m, 'PUBLISHED', '2026-10-08T03:00:00Z', '2026-10-08T07:00:00Z', 'x')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", WorkforceKit.TENANT)
                        .param("b", WorkforceKit.BRAND)
                        .param("l", WorkforceKit.LOCATION)
                        .param("m", cook.memberId())
                        .update())
                .hasMessageContaining("ck_staff_roster_needs_publish");
    }

    @Test
    @DisplayName("an entry cannot name a person or a branch of another tenant: the composite keys refuse it")
    void theRosterIsTenantScoped() {
        Person foreign = kit.person(WorkforceKit.OTHER_TENANT, "foreign", null, "ACTIVE");

        assertThatThrownBy(() -> kit.jdbc
                        .sql("""
                                INSERT INTO workforce.staff_roster_entries
                                    (id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start,
                                     planned_end, created_by)
                                VALUES (:id, :t, :b, :l, :m, 'DRAFT', '2026-10-08T03:00:00Z', '2026-10-08T07:00:00Z', 'x')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", WorkforceKit.TENANT)
                        .param("b", WorkforceKit.BRAND)
                        .param("l", WorkforceKit.LOCATION)
                        .param("m", foreign.memberId())
                        .update())
                .hasMessageContaining("fk_staff_roster_member");
        assertThatThrownBy(() -> kit.jdbc
                        .sql("""
                                INSERT INTO workforce.staff_roster_entries
                                    (id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start,
                                     planned_end, created_by)
                                VALUES (:id, :t, :b, :l, :m, 'DRAFT', '2026-10-08T03:00:00Z', '2026-10-08T07:00:00Z', 'x')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", WorkforceKit.TENANT)
                        .param("b", WorkforceKit.OTHER_BRAND)
                        .param("l", WorkforceKit.OTHER_LOCATION)
                        .param("m", cook.memberId())
                        .update())
                .hasMessageContaining("fk_staff_roster_location");
    }

    // ---------------------------------------------------------------------------- the timesheet

    @Test
    @DisplayName("a person has at most one open shift, whatever the start times")
    void oneOpenShiftPerPerson() {
        shift(cook, "OPEN", "2026-10-08T03:00:00Z", null);

        assertThatThrownBy(() -> shift(cook, "OPEN", "2026-10-08T10:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("SELECT count(*) FROM workforce.staff_shifts WHERE status = 'OPEN'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "two live shifts of one person cannot overlap; a voided one does not count; back-to-back shifts are fine")
    void theShiftExclusion() {
        shift(cook, "CLOSED", "2026-10-08T03:00:00Z", "2026-10-08T07:00:00Z");

        assertThatThrownBy(() -> shift(cook, "CLOSED", "2026-10-08T06:00:00Z", "2026-10-08T09:00:00Z"))
                .hasMessageContaining("ex_staff_shift_no_overlap");
        shift(cook, "CLOSED", "2026-10-08T07:00:00Z", "2026-10-08T09:00:00Z");
        voidedShift(cook, "2026-10-08T03:30:00Z", "2026-10-08T06:30:00Z");
        assertThat(count("SELECT count(*) FROM workforce.staff_shifts")).isEqualTo(3);
    }

    @Test
    @DisplayName("an open shift blocks a later one that would overlap its unbounded end")
    void anOpenShiftHasNoEnd() {
        shift(cook, "OPEN", "2026-10-08T03:00:00Z", null);

        assertThatThrownBy(() -> shift(cook, "CLOSED", "2026-10-08T05:00:00Z", "2026-10-08T06:00:00Z"))
                .hasMessageContaining("ex_staff_shift_no_overlap");
    }

    @Test
    @DisplayName(
            "a settled shift needs its end and its seconds; a shift longer than sixteen hours or with a break over its length is refused")
    void theShiftChecks() {
        assertThatThrownBy(() -> kit.jdbc
                        .sql("""
                                INSERT INTO workforce.staff_shifts
                                    (id, tenant_id, brand_id, location_id, staff_member_id, status, started_at,
                                     open_source, business_date, created_by)
                                VALUES (:id, :t, :b, :l, :m, 'CLOSED', '2026-10-08T03:00:00Z', 'CONSOLE_SELF',
                                        '2026-10-08', 'x')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", WorkforceKit.TENANT)
                        .param("b", WorkforceKit.BRAND)
                        .param("l", WorkforceKit.LOCATION)
                        .param("m", cook.memberId())
                        .update())
                .hasMessageContaining("ck_staff_shift_settled");
        assertThatThrownBy(
                        () -> shiftSql(cook, "CLOSED", "2026-10-08T00:00:00Z", "2026-10-08T16:00:01Z", 0, 57601, 57601))
                .hasMessageContaining("ck_staff_shift_window");
        assertThatThrownBy(
                        () -> shiftSql(cook, "CLOSED", "2026-10-08T03:00:00Z", "2026-10-08T04:00:00Z", 3601, 3600, 0))
                .hasMessageContaining("ck_staff_shift_break");
    }

    @Test
    @DisplayName(
            "a voided shift needs a reason, a voider and a time: voiding without one is refused, and nothing deletes a shift")
    void theVoidRule() {
        assertThatThrownBy(() -> kit.jdbc
                        .sql("""
                                INSERT INTO workforce.staff_shifts
                                    (id, tenant_id, brand_id, location_id, staff_member_id, status, started_at, ended_at,
                                     open_source, close_source, worked_seconds, paid_seconds, business_date, created_by)
                                VALUES (:id, :t, :b, :l, :m, 'VOIDED', '2026-10-08T03:00:00Z', '2026-10-08T04:00:00Z',
                                        'CONSOLE_SELF', 'CONSOLE_SELF', 3600, 3600, '2026-10-08', 'x')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", WorkforceKit.TENANT)
                        .param("b", WorkforceKit.BRAND)
                        .param("l", WorkforceKit.LOCATION)
                        .param("m", cook.memberId())
                        .update())
                .hasMessageContaining("ck_staff_shift_void");
    }

    @Test
    @DisplayName("the application role can read, add and change the rota and the timesheet and can delete neither")
    void nothingIsDeleted() {
        for (String table : new String[] {"workforce.staff_shifts", "workforce.staff_roster_entries"}) {
            assertThat(privilege(table, "SELECT")).as(table + " select").isTrue();
            assertThat(privilege(table, "INSERT")).as(table + " insert").isTrue();
            assertThat(privilege(table, "UPDATE")).as(table + " update").isTrue();
            assertThat(privilege(table, "DELETE"))
                    .as(table + " delete: a mistaken shift is voided, a dropped entry cancelled, nothing is removed")
                    .isFalse();
        }
        assertThat(privilege("reporting.fact_staff_shift", "DELETE"))
                .as("the fact is rebuildable and cleared with its day, so the close job may delete it")
                .isTrue();
    }

    @Test
    @DisplayName("a shift cannot belong to a person or a branch of another tenant")
    void theShiftIsTenantScoped() {
        Person foreign = kit.person(WorkforceKit.OTHER_TENANT, "foreign", null, "ACTIVE");

        assertThatThrownBy(() -> kit.jdbc
                        .sql("""
                                INSERT INTO workforce.staff_shifts
                                    (id, tenant_id, brand_id, location_id, staff_member_id, status, started_at,
                                     open_source, business_date, created_by)
                                VALUES (:id, :t, :b, :l, :m, 'OPEN', '2026-10-08T03:00:00Z', 'CONSOLE_SELF',
                                        '2026-10-08', 'x')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", WorkforceKit.TENANT)
                        .param("b", WorkforceKit.BRAND)
                        .param("l", WorkforceKit.LOCATION)
                        .param("m", foreign.memberId())
                        .update())
                .hasMessageContaining("fk_staff_shift_member");
    }

    @Test
    @DisplayName("the only sources a shift admits are the two console ones: TERMINAL is not a value anything can write")
    void noTerminalSourceYet() {
        assertThatThrownBy(() -> kit.jdbc
                        .sql("""
                                INSERT INTO workforce.staff_shifts
                                    (id, tenant_id, brand_id, location_id, staff_member_id, status, started_at,
                                     open_source, business_date, created_by)
                                VALUES (:id, :t, :b, :l, :m, 'OPEN', '2026-10-08T03:00:00Z', 'TERMINAL',
                                        '2026-10-08', 'x')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", WorkforceKit.TENANT)
                        .param("b", WorkforceKit.BRAND)
                        .param("l", WorkforceKit.LOCATION)
                        .param("m", cook.memberId())
                        .update())
                .hasMessageContaining("ck_staff_shift_sources");
    }

    // ==================================================================================== helpers

    private void entry(Person person, String status, String start, String end) {
        kit.jdbc
                .sql("""
                        INSERT INTO workforce.staff_roster_entries
                            (id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start, planned_end,
                             created_by, published_at, published_by)
                        VALUES (:id, :t, :b, :l, :m, :status, :start, :end, 'x',
                                CASE WHEN :status = 'DRAFT' THEN NULL ELSE now() END,
                                CASE WHEN :status = 'DRAFT' THEN NULL ELSE 'x' END)
                        """)
                .param("id", UUID.randomUUID())
                .param("t", WorkforceKit.TENANT)
                .param("b", WorkforceKit.BRAND)
                .param("l", WorkforceKit.LOCATION)
                .param("m", person.memberId())
                .param("status", status)
                .param("start", Instant.parse(start).atOffset(ZoneOffset.UTC))
                .param("end", Instant.parse(end).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void entryCancelled(Person person, String start, String end) {
        kit.jdbc
                .sql("""
                        INSERT INTO workforce.staff_roster_entries
                            (id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start, planned_end,
                             created_by, cancel_reason_code)
                        VALUES (:id, :t, :b, :l, :m, 'CANCELLED', :start, :end, 'x', 'SCHEDULE_CHANGED')
                        """)
                .param("id", UUID.randomUUID())
                .param("t", WorkforceKit.TENANT)
                .param("b", WorkforceKit.BRAND)
                .param("l", WorkforceKit.LOCATION)
                .param("m", person.memberId())
                .param("start", Instant.parse(start).atOffset(ZoneOffset.UTC))
                .param("end", Instant.parse(end).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void shift(Person person, String status, String start, @Nullable String end) {
        long seconds = end == null
                ? 0
                : Instant.parse(end).getEpochSecond() - Instant.parse(start).getEpochSecond();
        shiftSql(person, status, start, end, 0, seconds, seconds);
    }

    private void shiftSql(
            Person person,
            String status,
            String start,
            @Nullable String end,
            long breakSeconds,
            long worked,
            long paid) {
        kit.jdbc
                .sql("""
                        INSERT INTO workforce.staff_shifts
                            (id, tenant_id, brand_id, location_id, staff_member_id, status, started_at, ended_at,
                             open_source, close_source, break_seconds, worked_seconds, paid_seconds, business_date,
                             created_by)
                        VALUES (:id, :t, :b, :l, :m, :status, :start, :end, 'CONSOLE_SELF',
                                CASE WHEN CAST(:end AS timestamptz) IS NULL THEN NULL ELSE 'CONSOLE_SELF' END,
                                :break, CASE WHEN :status = 'OPEN' THEN NULL ELSE CAST(:worked AS bigint) END,
                                CASE WHEN :status = 'OPEN' THEN NULL ELSE CAST(:paid AS bigint) END, '2026-10-08', 'x')
                        """)
                .param("id", UUID.randomUUID())
                .param("t", WorkforceKit.TENANT)
                .param("b", WorkforceKit.BRAND)
                .param("l", WorkforceKit.LOCATION)
                .param("m", person.memberId())
                .param("status", status)
                .param("start", Instant.parse(start).atOffset(ZoneOffset.UTC))
                .param("end", end == null ? null : Instant.parse(end).atOffset(ZoneOffset.UTC))
                .param("break", breakSeconds)
                .param("worked", worked)
                .param("paid", paid)
                .update();
    }

    private void voidedShift(Person person, String start, String end) {
        kit.jdbc
                .sql("""
                        INSERT INTO workforce.staff_shifts
                            (id, tenant_id, brand_id, location_id, staff_member_id, status, started_at, ended_at,
                             open_source, close_source, worked_seconds, paid_seconds, business_date, created_by,
                             void_reason_code, voided_by, voided_at)
                        VALUES (:id, :t, :b, :l, :m, 'VOIDED', :start, :end, 'CONSOLE_SELF', 'CONSOLE_SELF',
                                10800, 10800, '2026-10-08', 'x', 'ENTERED_IN_ERROR', 'x', now())
                        """)
                .param("id", UUID.randomUUID())
                .param("t", WorkforceKit.TENANT)
                .param("b", WorkforceKit.BRAND)
                .param("l", WorkforceKit.LOCATION)
                .param("m", person.memberId())
                .param("start", Instant.parse(start).atOffset(ZoneOffset.UTC))
                .param("end", Instant.parse(end).atOffset(ZoneOffset.UTC))
                .update();
    }

    private boolean privilege(String table, String privilege) {
        return Boolean.TRUE.equals(kit.jdbc
                .sql("SELECT has_table_privilege(:role, :table, :privilege)")
                .param("role", TestDatabase.APPLICATION_ROLE)
                .param("table", table)
                .param("privilege", privilege)
                .query(Boolean.class)
                .single());
    }

    private int count(String sql) {
        return kit.jdbc.sql(sql).query(Integer.class).single();
    }
}
