package uz.horecaos.platform.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.customers.api.CustomerDirectoryExportPort;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.ordering.api.OrderCrmLogExportPort;
import uz.horecaos.platform.reporting.application.DayCloseService;
import uz.horecaos.platform.reporting.application.ReportExportService;
import uz.horecaos.platform.reporting.application.ReportExportService.ExportStatusView;
import uz.horecaos.platform.reporting.application.ReportQueryService;
import uz.horecaos.platform.reporting.application.SubjectPseudonym;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportExportStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.workforce.WorkforceKit.Person;
import uz.horecaos.platform.workforce.domain.ReasonCode;

/**
 * ADR 0156, Facts, metrics and the payroll export, through the real day close and the real export
 * centre: the file's total is the fact's total, a range that holds unsettled hours or is over its
 * ceiling is refused before it is queued, the same checks run again when the job does, the identity
 * columns follow {@code staff.shift.export} and the history redacts by the viewed report's own
 * capability.
 */
class StaffAttendanceExportTests {

    private static final String MANAGER = "export-manager";
    private static final String FINANCE = "finance-clerk";
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-31T00:00:00Z");
    private static final String KEY = "STAFF_ATTENDANCE";

    private static TestDatabase.Handle db;

    private WorkforceKit kit;
    private ReportExportService exports;
    private RecordingStorage storage;
    private JdbcReportExportStore exportStore;
    private DayCloseService close;

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
        kit.grants.allow(
                MANAGER, Capability.STAFF_SHIFT_MANAGE, Capability.STAFF_SHIFT_READ, Capability.STAFF_SHIFT_APPROVE);
        var mapper = JsonMapper.builder().build();
        storage = new RecordingStorage();
        exportStore = new JdbcReportExportStore(kit.jdbc, mapper);
        var queries = new ReportQueryService(
                kit.reportingStore,
                new uz.horecaos.platform.reporting.application.BusinessDayService(kit.reportingStore),
                kit.clock);
        exports = new ReportExportService(
                exportStore,
                mock(CustomerDirectoryExportPort.class),
                mock(OrderCrmLogExportPort.class),
                queries,
                mock(FieldProtection.class),
                kit.auditRecorder,
                storage,
                new TransactionTemplate(new DataSourceTransactionManager(kit.dataSource)),
                mapper,
                kit.clock,
                "test-exports-bucket",
                kit.exportPort);
        close = new DayCloseService(
                kit.reportingStore,
                new uz.horecaos.platform.reporting.application.BusinessDayService(kit.reportingStore),
                new SubjectPseudonym(mock(FieldProtection.class)),
                kit.clock,
                null,
                kit.factSource);
        new uz.horecaos.platform.reporting.application.MetricDefinitionSynchronizer(kit.reportingStore)
                .synchronizeAll();
    }

    @Test
    @DisplayName(
            "the file's total equals the fact's total for the same range, and both equal the closed shifts' paid seconds")
    void theExportTotalIsTheFactTotal() throws Exception {
        Person cook = kit.cook("cook");
        Person waiter = kit.cook("waiter");
        worked(cook, "2026-10-08T04:00:00Z", 8);
        worked(waiter, "2026-10-08T05:00:00Z", 6);
        worked(cook, "2026-10-09T04:00:00Z", 7);
        close.close(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8));
        close.close(WorkforceKit.TENANT, LocalDate.of(2026, 10, 9));

        UUID id = request(allColumns(), Set.of(Capability.STAFF_SHIFT_EXPORT), null);
        assertThat(exports.processNextQueued()).isTrue();

        List<CSVRecord> rows = csv(id);
        long fileTotal = rows.stream()
                .mapToLong(row -> Long.parseLong(row.get("paidSeconds")))
                .sum();
        long factTotal = kit.reportingStore.readStaffShiftPaidSeconds(
                WorkforceKit.TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), List.of());
        assertThat(rows).hasSize(3);
        assertThat(fileTotal).isEqualTo((8 + 6 + 7) * 3_600L);
        assertThat(factTotal).as("the fact built at day close").isEqualTo(fileTotal);
        assertThat(kit.hours.paidSeconds(
                        WorkforceKit.TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), List.of()))
                .as("the one query owner both read")
                .isEqualTo(fileTotal);
        ExportStatusView view = exports.status(WorkforceKit.TENANT, id, Set.of(Capability.STAFF_SHIFT_EXPORT))
                .orElseThrow();
        assertThat(view.status()).isEqualTo("COMPLETE");
        assertThat(view.truncated())
                .as("a payroll export never completes with truncated=true")
                .isFalse();
        assertThat(view.rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "a range holding an open shift is refused before it is queued, with the count; asked to include it, the file carries its status and no paid seconds")
    void anUnsettledShiftRefusesTheRange() throws Exception {
        Person cook = kit.cook("cook");
        Person waiter = kit.cook("waiter");
        worked(cook, "2026-10-08T04:00:00Z", 8);
        kit.clock.set(Instant.parse("2026-10-08T14:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, waiter.subject(), WorkforceKit.LOCATION, "c");

        assertThatThrownBy(() -> request(allColumns(), Set.of(), null))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.ATTENDANCE_UNRESOLVED_SHIFTS);
                    assertThat(refused.properties()).containsEntry("count", 1);
                });
        assertThat(count("SELECT count(*) FROM reporting.report_exports"))
                .as("nothing was queued")
                .isZero();

        UUID id = request(allColumns(), Set.of(), true);
        assertThat(exports.processNextQueued()).isTrue();

        List<CSVRecord> rows = csv(id);
        assertThat(rows).hasSize(2);
        CSVRecord open = rows.stream()
                .filter(row -> row.get("approvalStatus").equals("OPEN"))
                .findFirst()
                .orElseThrow();
        assertThat(open.get("paidSeconds")).isEmpty();
        assertThat(open.get("endedAt")).isEmpty();
    }

    @Test
    @DisplayName("a shift awaiting approval also makes the range unsettled until a person approves it")
    void anAwaitingShiftRefusesTheRange() {
        Person cook = kit.cook("cook");
        UUID entry = plan(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z");
        kit.clock.set(Instant.parse("2026-10-08T04:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T14:00:00Z"));
        var waiting =
                kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2").shift();
        assertThat(entry).isEqualTo(waiting.rosterEntryId());

        assertThatThrownBy(() -> request(allColumns(), Set.of(), null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ATTENDANCE_UNRESOLVED_SHIFTS));

        kit.shifts.approve(
                WorkforceKit.branch(), waiting.id(), waiting.version(), ReasonCode.WORKED_AS_RECORDED, MANAGER, "a");
        assertThat(request(allColumns(), Set.of(), null)).isNotNull();
    }

    @Test
    @DisplayName(
            "a range over the 20,000-row ceiling is refused with the count and the ceiling and queues nothing; exactly 20,000 is accepted")
    void theCeilingRefusesAndNeverTruncates() {
        Person cook = kit.cook("cook");
        bulkClosedShifts(cook, 20_001);

        assertThatThrownBy(() -> request(allColumns(), Set.of(), null))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.ATTENDANCE_EXPORT_TOO_LARGE);
                    assertThat(refused.properties())
                            .containsEntry("rowCount", 20_001)
                            .containsEntry("rowCeiling", 20_000);
                });
        assertThat(count("SELECT count(*) FROM reporting.report_exports")).isZero();

        kit.jdbc.sql("""
                        DELETE FROM workforce.staff_shifts WHERE id = (
                            SELECT id FROM workforce.staff_shifts ORDER BY started_at LIMIT 1)
                        """).update();
        assertThat(request(allColumns(), Set.of(), null))
                .as("exactly at the ceiling")
                .isNotNull();
    }

    @Test
    @DisplayName(
            "a range that grows past the ceiling between the request and the run ends the job FAILED with the same code and writes no object")
    void theWorkerChecksAgain() {
        Person cook = kit.cook("cook");
        bulkClosedShifts(cook, 20_000);
        UUID id = request(allColumns(), Set.of(), null);
        bulkClosedShiftsAfter(cook, 3, "2026-10-08T20:00:00Z");

        assertThat(exports.processNextQueued()).isTrue();

        ExportStatusView view =
                exports.status(WorkforceKit.TENANT, id, Set.of()).orElseThrow();
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.failureReason()).isEqualTo("ATTENDANCE_EXPORT_TOO_LARGE");
        assertThat(storage.puts).as("no object was written").isEmpty();
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'report.export.completed'"))
                .isZero();
    }

    @Test
    @DisplayName(
            "a shift opened between the request and the run ends the job FAILED with ATTENDANCE_UNRESOLVED_SHIFTS and writes no object")
    void theWorkerNoticesAnUnsettledShift() {
        Person cook = kit.cook("cook");
        Person waiter = kit.cook("waiter");
        worked(cook, "2026-10-08T04:00:00Z", 8);
        UUID id = request(allColumns(), Set.of(), null);
        kit.clock.set(Instant.parse("2026-10-08T15:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, waiter.subject(), WorkforceKit.LOCATION, "c");

        assertThat(exports.processNextQueued()).isTrue();

        ExportStatusView view =
                exports.status(WorkforceKit.TENANT, id, Set.of()).orElseThrow();
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.failureReason()).isEqualTo("ATTENDANCE_UNRESOLVED_SHIFTS");
        assertThat(storage.puts).isEmpty();
    }

    @Test
    @DisplayName(
            "the identity columns follow staff.shift.export and only it: customer.pii.export alone gets the hours and the reference")
    void identityFollowsTheStaffCapability() throws Exception {
        Person cook = kit.cook("Aziza Karimova");
        worked(cook, "2026-10-08T04:00:00Z", 8);

        UUID withIdentity = request(allColumns(), Set.of(Capability.STAFF_SHIFT_EXPORT), null);
        UUID withoutIdentity = request(allColumns(), Set.of(Capability.CUSTOMER_PII_EXPORT), null);
        exports.processNextQueued();
        exports.processNextQueued();

        CSVRecord whole = csv(withIdentity).getFirst();
        assertThat(whole.get("staffName")).isEqualTo("Aziza Karimova");
        assertThat(whole.get("employeeNumber")).isEqualTo("E-" + cook.reference());
        CSVRecord redacted = csv(withoutIdentity).getFirst();
        assertThat(redacted.isMapped("staffName")).isFalse();
        assertThat(redacted.isMapped("employeeNumber")).isFalse();
        assertThat(redacted.get("staffDisplayReference")).isEqualTo(cook.reference());
        assertThat(redacted.get("paidSeconds")).isEqualTo("28800");
        assertThat(String.join(",", csvHeader(withoutIdentity))).doesNotContain("Aziza");
    }

    @Test
    @DisplayName("the file carries hours, instants and the branch's zone, and computes no wage, overtime or premium")
    void hoursNeverPay() throws Exception {
        Person cook = kit.cook("cook");
        worked(cook, "2026-10-08T04:00:00Z", 8);
        UUID id = request(allColumns(), Set.of(Capability.STAFF_SHIFT_EXPORT), null);
        exports.processNextQueued();

        CSVRecord row = csv(id).getFirst();

        assertThat(csvHeader(id))
                .containsExactly(
                        "staffDisplayReference",
                        "businessDate",
                        "locationId",
                        "plannedSeconds",
                        "workedSeconds",
                        "breakSeconds",
                        "paidSeconds",
                        "varianceSeconds",
                        "approvalStatus",
                        "startedAt",
                        "endedAt",
                        "timezone",
                        "employeeNumber",
                        "staffName");
        assertThat(row.get("timezone")).isEqualTo("Asia/Tashkent");
        assertThat(row.get("startedAt")).isEqualTo("2026-10-08T04:00:00Z");
        assertThat(row.get("endedAt")).isEqualTo("2026-10-08T12:00:00Z");
        assertThat(row.get("plannedSeconds")).as("no plan: empty, not zero").isEmpty();
        assertThat(row.get("approvalStatus")).isEqualTo("WITHIN_THRESHOLD");
    }

    @Test
    @DisplayName("with attendance off the report is an unknown key, and nothing is queued")
    void offIsUnknown() {
        kit.settings.set("workforce.attendance.enabled", false);

        assertThatThrownBy(() -> request(allColumns(), Set.of(), null))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(refused.getMessage()).isEqualTo("Unknown report key");
                });
        assertThat(count("SELECT count(*) FROM reporting.report_exports")).isZero();
    }

    @Test
    @DisplayName("a payroll export needs its range, as the other ranged reports do")
    void aRangeIsRequired() {
        assertThatThrownBy(() -> exports.requestExport(
                        WorkforceKit.TENANT,
                        KEY,
                        allColumns(),
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        "payroll",
                        FINANCE,
                        Set.of(),
                        null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName(
            "the history redacts by the viewed report's own capability: the finance clerk sees her payroll file whole, a customer.pii.export holder alone does not, and the customer reports behave as before")
    void historyRedactsByTheReportsOwnCapability() {
        Person cook = kit.cook("cook");
        worked(cook, "2026-10-08T04:00:00Z", 8);
        UUID payroll = request(allColumns(), Set.of(Capability.STAFF_SHIFT_EXPORT), null);
        exports.processNextQueued();
        UUID customers = UUID.randomUUID();
        exportStore.insertQueued(
                customers,
                WorkforceKit.TENANT,
                "CUSTOMER_DIRECTORY",
                "someone",
                "customers",
                "{}",
                null,
                List.of("accountId", "phone"),
                List.of("accountId", "phone"),
                true,
                2_000,
                kit.clock.instant());
        Set<Capability> financeClerk = Set.of(Capability.STAFF_SHIFT_EXPORT);
        Set<Capability> customerPiiOnly = Set.of(Capability.CUSTOMER_PII_EXPORT);

        ExportStatusView hers =
                exports.status(WorkforceKit.TENANT, payroll, financeClerk).orElseThrow();
        ExportStatusView theirs =
                exports.status(WorkforceKit.TENANT, payroll, customerPiiOnly).orElseThrow();

        assertThat(hers.includesPiiColumns()).isTrue();
        assertThat(hers.effectiveColumns()).contains("staffName", "employeeNumber");
        assertThat(hers.downloadUrl()).isNotNull();
        assertThat(theirs.includesPiiColumns()).isFalse();
        assertThat(theirs.effectiveColumns()).doesNotContain("staffName", "employeeNumber");
        assertThat(theirs.downloadUrl())
                .as("no download URL for an identity group the viewer cannot see")
                .isNull();

        for (var history : List.of(
                exports.recentExports(WorkforceKit.TENANT, 50, customerPiiOnly),
                exports.recentExports(WorkforceKit.TENANT, 50, financeClerk))) {
            ExportStatusView payrollRow = history.stream()
                    .filter(row -> row.id().equals(payroll))
                    .findFirst()
                    .orElseThrow();
            ExportStatusView customerRow = history.stream()
                    .filter(row -> row.id().equals(customers))
                    .findFirst()
                    .orElseThrow();
            assertThat(payrollRow.reportKey()).isEqualTo(KEY);
            assertThat(customerRow.reportKey()).isEqualTo("CUSTOMER_DIRECTORY");
        }
        assertThat(exports.recentExports(WorkforceKit.TENANT, 50, customerPiiOnly).stream()
                        .filter(row -> row.id().equals(customers))
                        .findFirst()
                        .orElseThrow()
                        .effectiveColumns())
                .as("an existing report's behaviour is unchanged for its own capability")
                .contains("phone");
        assertThat(exports.recentExports(WorkforceKit.TENANT, 50, financeClerk).stream()
                        .filter(row -> row.id().equals(customers))
                        .findFirst()
                        .orElseThrow()
                        .effectiveColumns())
                .as("and is redacted for a viewer who holds only the staff capability")
                .doesNotContain("phone");
        assertThat(exports.recentExports(WorkforceKit.TENANT, 50, customerPiiOnly).stream()
                        .filter(row -> row.id().equals(payroll))
                        .findFirst()
                        .orElseThrow()
                        .downloadUrl())
                .isNull();
    }

    @Test
    @DisplayName("a recut after a post-close edit raises a divergence and leaves the stored fact alone")
    void aRecutReportsAPostCloseEdit() {
        Person cook = kit.cook("cook");
        worked(cook, "2026-10-08T04:00:00Z", 8);
        close.close(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8));
        assertThat(close.recut(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8)).divergences())
                .isEmpty();

        var shift = kit.shiftStore
                .list(WorkforceKit.TENANT, null, null, cook.memberId(), null, null, null, 5)
                .getFirst();
        kit.clock.set(Instant.parse("2026-10-08T15:00:00Z"));
        kit.shifts.correct(
                WorkforceKit.branch(),
                shift.id(),
                shift.version(),
                null,
                Instant.parse("2026-10-08T13:00:00Z"),
                null,
                ReasonCode.FORGOT_CLOCK_OUT,
                MANAGER,
                "m");

        var result = close.recut(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8));

        assertThat(result.divergences())
                .extracting(DayCloseService.Divergence::metricName)
                .contains("staff_hours.worked", "staff_hours.paid");
        assertThat(result.divergences())
                .filteredOn(divergence -> divergence.metricName().equals("staff_hours.paid"))
                .singleElement()
                .satisfies(divergence -> {
                    assertThat(divergence.storedValue()).isEqualTo(28_800L);
                    assertThat(divergence.recutValue()).isEqualTo(32_400L);
                });
        assertThat(kit.reportingStore.readStaffShiftPaidSeconds(
                        WorkforceKit.TENANT, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8), List.of()))
                .as("the stored fact is what the day was closed with")
                .isEqualTo(28_800L);
    }

    @Test
    @DisplayName(
            "a shift voided after its day closed leaves the fact in place until the next close, and the recut says so")
    void aVoidAfterTheCloseIsAlsoADivergence() {
        Person cook = kit.cook("cook");
        worked(cook, "2026-10-08T04:00:00Z", 8);
        close.close(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8));
        var shift = kit.shiftStore
                .list(WorkforceKit.TENANT, null, null, cook.memberId(), null, null, null, 5)
                .getFirst();

        kit.shifts.voidShift(WorkforceKit.branch(), shift.id(), null, ReasonCode.ENTERED_IN_ERROR, MANAGER, "v");

        assertThat(close.recut(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8)).divergences())
                .extracting(DayCloseService.Divergence::metricName)
                .contains("staff_hours.shifts");
        close.close(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8));
        assertThat(count("SELECT count(*) FROM reporting.fact_staff_shift"))
                .as("closing again drops it")
                .isZero();
    }

    @Test
    @DisplayName("an edit that moves a shift to another business date moves its fact too: no row is counted twice")
    void aMovedShiftIsNotCountedTwice() {
        Person cook = kit.cook("cook");
        worked(cook, "2026-10-08T04:00:00Z", 8);
        close.close(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8));
        var shift = kit.shiftStore
                .list(WorkforceKit.TENANT, null, null, cook.memberId(), null, null, null, 5)
                .getFirst();
        kit.clock.set(Instant.parse("2026-10-09T08:00:00Z"));
        kit.shifts.correct(
                WorkforceKit.branch(),
                shift.id(),
                shift.version(),
                Instant.parse("2026-10-09T03:00:00Z"),
                Instant.parse("2026-10-09T07:00:00Z"),
                null,
                ReasonCode.PLAN_CHANGED,
                MANAGER,
                "m");

        close.close(WorkforceKit.TENANT, LocalDate.of(2026, 10, 9));

        assertThat(kit.jdbc
                        .sql("SELECT business_date::text FROM reporting.fact_staff_shift")
                        .query(String.class)
                        .list())
                .containsExactly("2026-10-09");
    }

    @Test
    @DisplayName("the three staff_hours metrics are defined, provisional and say that no working-time rule is applied")
    void theMetricsAreRegistered() {
        for (String code : List.of("staff_hours.paid.v1", "staff_hours.planned.v1", "staff_hours.variance.v1")) {
            var definition = uz.horecaos.platform.reporting.domain.MetricRegistry.require(code);
            assertThat(definition.sourceAvailable()).isTrue();
            assertThat(definition.openQuestion()).containsIgnoringCase("working-time rule");
            assertThat(definition.sourceFact()).contains("fact_staff_shift");
        }
    }

    // ==================================================================================== helpers

    private UUID request(List<String> columns, Set<Capability> held, @Nullable Boolean includeUnresolved) {
        return exports.requestExport(
                WorkforceKit.TENANT,
                KEY,
                columns,
                null,
                null,
                FROM,
                TO,
                List.of(),
                "payroll for October",
                FINANCE,
                held,
                includeUnresolved);
    }

    private static List<String> allColumns() {
        return List.of(
                "staffDisplayReference",
                "businessDate",
                "locationId",
                "plannedSeconds",
                "workedSeconds",
                "breakSeconds",
                "paidSeconds",
                "varianceSeconds",
                "approvalStatus",
                "startedAt",
                "endedAt",
                "timezone",
                "employeeNumber",
                "staffName");
    }

    /** A closed shift made by the real path: clock in, move the clock, clock out. */
    private void worked(Person person, String start, int hours) {
        kit.clock.set(Instant.parse(start));
        kit.shifts.clockIn(WorkforceKit.TENANT, person.subject(), WorkforceKit.LOCATION, "in");
        kit.clock.advance(Duration.ofHours(hours));
        kit.shifts.clockOut(WorkforceKit.TENANT, person.subject(), "out");
    }

    private UUID plan(Person person, String start, String end) {
        var entry = kit.rota.create(
                WorkforceKit.branch(),
                new uz.horecaos.platform.workforce.application.StaffRosterService.CreateEntry(
                        person.memberId(), Instant.parse(start), Instant.parse(end), 0),
                MANAGER,
                "c");
        kit.rotaPublish(Instant.parse(start));
        return entry.id();
    }

    private void bulkClosedShifts(Person person, int rows) {
        bulkClosedShiftsAfter(person, rows, "2026-10-08T00:00:00Z");
    }

    /** One-second shifts two seconds apart: legal, non-overlapping, and cheap to insert by the thousand. */
    private void bulkClosedShiftsAfter(Person person, int rows, String firstStart) {
        kit.jdbc
                .sql("""
                        INSERT INTO workforce.staff_shifts
                            (id, tenant_id, brand_id, location_id, staff_member_id, status, started_at, ended_at,
                             open_source, close_source, break_seconds, worked_seconds, paid_seconds, business_date,
                             created_by)
                        SELECT gen_random_uuid(), :t, :b, :l, :m, 'CLOSED',
                               CAST(:first AS timestamptz) + (n * interval '2 seconds'),
                               CAST(:first AS timestamptz) + (n * interval '2 seconds') + interval '1 second',
                               'CONSOLE_SELF', 'CONSOLE_SELF', 0, 1, 1, CAST('2026-10-08' AS date), 'bulk'
                          FROM generate_series(0, :last) AS n
                        """)
                .param("t", WorkforceKit.TENANT)
                .param("b", WorkforceKit.BRAND)
                .param("l", WorkforceKit.LOCATION)
                .param("m", person.memberId())
                .param("first", firstStart)
                .param("last", rows - 1)
                .update();
    }

    private List<String> csvHeader(UUID exportId) {
        try {
            return CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build()
                    .parse(new StringReader(storedCsv(exportId)))
                    .getHeaderNames();
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private List<CSVRecord> csv(UUID exportId) throws java.io.IOException {
        return new ArrayList<>(CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(storedCsv(exportId)))
                .getRecords());
    }

    private String storedCsv(UUID exportId) {
        String key = "tenants/%s/report-exports/%s.csv".formatted(WorkforceKit.TENANT, exportId);
        return storage.puts.stream()
                .filter(put -> put.key().equals(key))
                .findFirst()
                .map(put -> new String(put.content(), StandardCharsets.UTF_8))
                .orElseThrow(() -> new AssertionError("no object was written for " + exportId));
    }

    private int count(String sql) {
        return kit.jdbc.sql(sql).query(Integer.class).single();
    }

    private record Put(String bucket, String key, byte[] content) {}

    private static final class RecordingStorage implements ObjectStorage {
        private final List<Put> puts = new ArrayList<>();

        @Override
        public PresignedUpload presignUpload(
                String bucket, String key, String contentType, long maxSizeBytes, Duration validFor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI presignDownload(String bucket, String key, Duration validFor) {
            return URI.create("https://example.invalid/" + bucket + "/" + key);
        }

        @Override
        public Optional<StoredObject> head(String bucket, String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void put(String bucket, String key, String contentType, byte[] content) {
            puts.add(new Put(bucket, key, content));
        }

        @Override
        public void delete(String bucket, String key) {
            throw new UnsupportedOperationException();
        }
    }
}
