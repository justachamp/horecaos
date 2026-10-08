package uz.horecaos.platform.workforce.infrastructure.persistence;

import static uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.instant;
import static uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.nullableInstant;
import static uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.workforce.domain.ShiftSource;
import uz.horecaos.platform.workforce.domain.ShiftStatus;

/**
 * {@code workforce.staff_shifts} (ADR 0156, V0529): the timesheet. A shift is never deleted -- a
 * mistaken one is voided with a reason -- and every write is a conditional write on the row's
 * version. The database holds the invariants the service relies on: one open shift per person
 * ({@code ux_staff_shift_one_open}), no two live shifts of a person overlapping ({@code
 * ex_staff_shift_no_overlap}), the sixteen-hour sanity bound and the settled-shift shape.
 *
 * <p>Stores no name, phone, position, network address or device identifier.
 */
@Repository
public class JdbcStaffShiftStore {

    private static final String COLUMNS = """
            id, tenant_id, brand_id, location_id, staff_member_id, roster_entry_id, status, started_at,
            ended_at, open_source, close_source, break_seconds, worked_seconds, paid_seconds, planned_seconds,
            variance_seconds, variance_threshold_minutes, business_date, edit_reason_code, last_edited_by,
            approved_by, approved_at, approval_reason_code, voided_by, voided_at, void_reason_code, version, created_by,
            created_at, updated_at""";

    private final JdbcClient jdbc;

    public JdbcStaffShiftStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ShiftRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID memberId,
            @Nullable UUID rosterEntryId,
            ShiftStatus status,
            Instant startedAt,
            @Nullable Instant endedAt,
            ShiftSource openSource,
            @Nullable ShiftSource closeSource,
            int breakSeconds,
            @Nullable Long workedSeconds,
            @Nullable Long paidSeconds,
            @Nullable Long plannedSeconds,
            @Nullable Long varianceSeconds,
            @Nullable Integer varianceThresholdMinutes,
            LocalDate businessDate,
            @Nullable String editReasonCode,
            @Nullable String lastEditedBy,
            @Nullable String approvedBy,
            @Nullable Instant approvedAt,
            @Nullable String approvalReasonCode,
            @Nullable String voidedBy,
            @Nullable Instant voidedAt,
            @Nullable String voidReasonCode,
            int version,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    /** The keyset position of a page: shifts are listed newest start first. */
    public record Cursor(Instant startedAt, UUID id) {}

    public void insert(ShiftRow shift) {
        jdbc.sql("""
                        INSERT INTO workforce.staff_shifts (
                            id, tenant_id, brand_id, location_id, staff_member_id, roster_entry_id, status,
                            started_at, ended_at, open_source, close_source, break_seconds, worked_seconds,
                            paid_seconds, planned_seconds, variance_seconds, variance_threshold_minutes,
                            business_date, edit_reason_code, last_edited_by, created_by)
                        VALUES (
                            :id, :tenantId, :brandId, :locationId, :memberId, :rosterEntryId, :status,
                            :startedAt, :endedAt, :openSource, :closeSource, :breakSeconds, :worked,
                            :paid, :planned, :variance, :threshold,
                            :businessDate, :editReason, :lastEditedBy, :createdBy)
                        """)
                .param("id", shift.id())
                .param("tenantId", shift.tenantId())
                .param("brandId", shift.brandId())
                .param("locationId", shift.locationId())
                .param("memberId", shift.memberId())
                .param("rosterEntryId", shift.rosterEntryId())
                .param("status", shift.status().name())
                .param("startedAt", utc(shift.startedAt()))
                .param("endedAt", shift.endedAt() == null ? null : utc(shift.endedAt()))
                .param("openSource", shift.openSource().name())
                .param(
                        "closeSource",
                        shift.closeSource() == null ? null : shift.closeSource().name())
                .param("breakSeconds", shift.breakSeconds())
                .param("worked", shift.workedSeconds())
                .param("paid", shift.paidSeconds())
                .param("planned", shift.plannedSeconds())
                .param("variance", shift.varianceSeconds())
                .param("threshold", shift.varianceThresholdMinutes())
                .param("businessDate", shift.businessDate())
                .param("editReason", shift.editReasonCode())
                .param("lastEditedBy", shift.lastEditedBy())
                .param("createdBy", shift.createdBy())
                .update();
    }

    /**
     * Writes every mutable column of {@code next}, conditional on {@code expectedVersion}.
     *
     * @return rows changed: 0 means the row moved on (or is not this tenant's), so the caller says
     *     stale and nothing was overwritten
     */
    public int update(ShiftRow next, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE workforce.staff_shifts
                           SET roster_entry_id = :rosterEntryId, status = :status, started_at = :startedAt,
                               ended_at = :endedAt, close_source = :closeSource, break_seconds = :breakSeconds,
                               worked_seconds = :worked, paid_seconds = :paid, planned_seconds = :planned,
                               variance_seconds = :variance, variance_threshold_minutes = :threshold,
                               business_date = :businessDate, edit_reason_code = :editReason,
                               last_edited_by = :lastEditedBy, approved_by = :approvedBy, approved_at = :approvedAt,
                               approval_reason_code = :approvalReason, voided_by = :voidedBy,
                               voided_at = :voidedAt, void_reason_code = :voidReason,
                               version = version + 1, updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("rosterEntryId", next.rosterEntryId())
                .param("status", next.status().name())
                .param("startedAt", utc(next.startedAt()))
                .param("endedAt", next.endedAt() == null ? null : utc(next.endedAt()))
                .param(
                        "closeSource",
                        next.closeSource() == null ? null : next.closeSource().name())
                .param("breakSeconds", next.breakSeconds())
                .param("worked", next.workedSeconds())
                .param("paid", next.paidSeconds())
                .param("planned", next.plannedSeconds())
                .param("variance", next.varianceSeconds())
                .param("threshold", next.varianceThresholdMinutes())
                .param("businessDate", next.businessDate())
                .param("editReason", next.editReasonCode())
                .param("lastEditedBy", next.lastEditedBy())
                .param("approvedBy", next.approvedBy())
                .param("approvedAt", next.approvedAt() == null ? null : utc(next.approvedAt()))
                .param("approvalReason", next.approvalReasonCode())
                .param("voidedBy", next.voidedBy())
                .param("voidedAt", next.voidedAt() == null ? null : utc(next.voidedAt()))
                .param("voidReason", next.voidReasonCode())
                .param("tenantId", next.tenantId())
                .param("id", next.id())
                .param("version", expectedVersion)
                .update();
    }

    public Optional<ShiftRow> find(UUID tenantId, UUID shiftId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workforce.staff_shifts WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", shiftId)
                .query(JdbcStaffShiftStore::map)
                .optional();
    }

    public Optional<ShiftRow> findAtLocation(UUID tenantId, UUID locationId, UUID shiftId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_shifts
                        WHERE tenant_id = :tenantId AND location_id = :locationId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("id", shiftId)
                .query(JdbcStaffShiftStore::map)
                .optional();
    }

    public Optional<ShiftRow> findOpenFor(UUID tenantId, UUID memberId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_shifts
                        WHERE tenant_id = :tenantId AND staff_member_id = :memberId AND status = 'OPEN'
                        """)
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .query(JdbcStaffShiftStore::map)
                .optional();
    }

    /**
     * Shifts of one reach, newest start first, one page. {@code brandId} and {@code locationId} narrow
     * (both null: the whole tenant); a voided shift is listed, because voiding is a recorded fact and
     * not a deletion.
     */
    public List<ShiftRow> list(
            UUID tenantId,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            @Nullable UUID memberId,
            @Nullable LocalDate fromDate,
            @Nullable LocalDate toDate,
            @Nullable Cursor after,
            int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_shifts
                        WHERE tenant_id = :tenantId
                          AND (CAST(:brandId AS uuid) IS NULL OR brand_id = :brandId)
                          AND (CAST(:locationId AS uuid) IS NULL OR location_id = :locationId)
                          AND (CAST(:memberId AS uuid) IS NULL OR staff_member_id = :memberId)
                          AND (CAST(:fromDate AS date) IS NULL OR business_date >= :fromDate)
                          AND (CAST(:toDate AS date) IS NULL OR business_date <= :toDate)
                          AND (CAST(:afterStart AS timestamptz) IS NULL
                               OR (started_at, id) < (CAST(:afterStart AS timestamptz), CAST(:afterId AS uuid)))
                        ORDER BY started_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("memberId", memberId)
                .param("fromDate", fromDate)
                .param("toDate", toDate)
                .param("afterStart", after == null ? null : utc(after.startedAt()))
                .param("afterId", after == null ? null : after.id())
                .param("limit", limit)
                .query(JdbcStaffShiftStore::map)
                .list();
    }

    /**
     * What «Сегодня» reads at one branch: every shift started in {@code [from, to)} and every shift
     * still open or awaiting approval, whenever it started, so a shift left open from yesterday does
     * not vanish from the screen that exists to flag it.
     */
    public List<ShiftRow> forToday(UUID tenantId, UUID locationId, Instant from, Instant to) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_shifts
                        WHERE tenant_id = :tenantId AND location_id = :locationId AND status <> 'VOIDED'
                          AND ((started_at >= :from AND started_at < :to)
                               OR status IN ('OPEN', 'AWAITING_APPROVAL'))
                        ORDER BY started_at, id
                        LIMIT 2000
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(JdbcStaffShiftStore::map)
                .list();
    }

    /** Closed shifts whose stored business date is {@code businessDate}: the day close's source. */
    public List<ShiftRow> closedOn(UUID tenantId, LocalDate businessDate) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_shifts
                        WHERE tenant_id = :tenantId AND business_date = :businessDate AND status = 'CLOSED'
                        ORDER BY location_id, started_at, id
                        """)
                .param("tenantId", tenantId)
                .param("businessDate", businessDate)
                .query(JdbcStaffShiftStore::map)
                .list();
    }

    /** The shifts a payroll range holds (voided ones are never payroll), in a stable order. */
    public List<ShiftRow> inRange(
            UUID tenantId,
            LocalDate fromDate,
            LocalDate toDate,
            List<UUID> locationIds,
            boolean includeUnresolved,
            int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_shifts
                        WHERE tenant_id = :tenantId AND business_date BETWEEN :fromDate AND :toDate
                          AND (:allLocations OR location_id IN (:locationIds))
                          AND (status = 'CLOSED' OR (:includeUnresolved AND status IN ('OPEN', 'AWAITING_APPROVAL')))
                        ORDER BY business_date, location_id, started_at, id
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("fromDate", fromDate)
                .param("toDate", toDate)
                .param("allLocations", locationIds.isEmpty())
                .param("locationIds", locationIds.isEmpty() ? List.of(new UUID(0, 0)) : locationIds)
                .param("includeUnresolved", includeUnresolved)
                .param("limit", limit)
                .query(JdbcStaffShiftStore::map)
                .list();
    }

    /** Shifts in range that are still OPEN or AWAITING_APPROVAL: what makes a payroll file unsettled. */
    public int countUnresolved(UUID tenantId, LocalDate fromDate, LocalDate toDate, List<UUID> locationIds) {
        return jdbc.sql("""
                        SELECT count(*) FROM workforce.staff_shifts
                         WHERE tenant_id = :tenantId AND business_date BETWEEN :fromDate AND :toDate
                           AND (:allLocations OR location_id IN (:locationIds))
                           AND status IN ('OPEN', 'AWAITING_APPROVAL')
                        """)
                .param("tenantId", tenantId)
                .param("fromDate", fromDate)
                .param("toDate", toDate)
                .param("allLocations", locationIds.isEmpty())
                .param("locationIds", locationIds.isEmpty() ? List.of(new UUID(0, 0)) : locationIds)
                .query(Integer.class)
                .single();
    }

    /** Rows a payroll file would hold: closed shifts, plus the unresolved ones when asked. */
    public int countExportable(
            UUID tenantId, LocalDate fromDate, LocalDate toDate, List<UUID> locationIds, boolean includeUnresolved) {
        return jdbc.sql("""
                        SELECT count(*) FROM workforce.staff_shifts
                         WHERE tenant_id = :tenantId AND business_date BETWEEN :fromDate AND :toDate
                           AND (:allLocations OR location_id IN (:locationIds))
                           AND (status = 'CLOSED' OR (:includeUnresolved AND status IN ('OPEN', 'AWAITING_APPROVAL')))
                        """)
                .param("tenantId", tenantId)
                .param("fromDate", fromDate)
                .param("toDate", toDate)
                .param("allLocations", locationIds.isEmpty())
                .param("locationIds", locationIds.isEmpty() ? List.of(new UUID(0, 0)) : locationIds)
                .param("includeUnresolved", includeUnresolved)
                .query(Integer.class)
                .single();
    }

    /** Open shifts older than {@code cutoff}, for the open-too-long gauge. */
    public int countOpenBefore(Instant cutoff) {
        return jdbc.sql("SELECT count(*) FROM workforce.staff_shifts WHERE status = 'OPEN' AND started_at < :cutoff")
                .param("cutoff", utc(cutoff))
                .query(Integer.class)
                .single();
    }

    public int countAwaitingApproval() {
        return jdbc.sql("SELECT count(*) FROM workforce.staff_shifts WHERE status = 'AWAITING_APPROVAL'")
                .query(Integer.class)
                .single();
    }

    /**
     * The non-personal display references («S-0142») of these members. One column of {@code
     * iam.staff_members}, read by id and tenant: a name is never read here, and a member of another
     * tenant is simply absent from the answer.
     */
    public java.util.Map<UUID, String> displayReferences(UUID tenantId, java.util.Collection<UUID> memberIds) {
        java.util.Map<UUID, String> references = new java.util.LinkedHashMap<>();
        if (memberIds.isEmpty()) {
            return references;
        }
        record Reference(UUID id, String value) {}
        jdbc.sql("SELECT id, display_reference FROM iam.staff_members WHERE tenant_id = :tenantId AND id IN (:ids)")
                .param("tenantId", tenantId)
                .param("ids", memberIds)
                .query((rs, n) -> new Reference(rs.getObject("id", UUID.class), rs.getString("display_reference")))
                .list()
                .forEach(reference -> references.put(reference.id(), reference.value()));
        return references;
    }

    /** The branch's IANA timezone, which a payroll system needs to compute night and holiday premiums. */
    public Optional<String> timezoneOf(UUID tenantId, UUID locationId) {
        return jdbc.sql("SELECT timezone FROM tenant.locations WHERE tenant_id = :tenantId AND id = :locationId")
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(String.class)
                .optional();
    }

    /** The brand a branch belongs to in this tenant; empty for a branch of another tenant or none. */
    public Optional<UUID> brandOf(UUID tenantId, UUID locationId) {
        return jdbc.sql("SELECT brand_id FROM tenant.locations WHERE tenant_id = :tenantId AND id = :locationId")
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(UUID.class)
                .optional();
    }

    private static ShiftRow map(ResultSet rs, int row) throws SQLException {
        String closeSource = rs.getString("close_source");
        return new ShiftRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("staff_member_id", UUID.class),
                rs.getObject("roster_entry_id", UUID.class),
                ShiftStatus.valueOf(rs.getString("status")),
                instant(rs, "started_at"),
                nullableInstant(rs, "ended_at"),
                ShiftSource.valueOf(rs.getString("open_source")),
                closeSource == null ? null : ShiftSource.valueOf(closeSource),
                rs.getInt("break_seconds"),
                nullableLong(rs, "worked_seconds"),
                nullableLong(rs, "paid_seconds"),
                nullableLong(rs, "planned_seconds"),
                nullableLong(rs, "variance_seconds"),
                nullableInt(rs, "variance_threshold_minutes"),
                rs.getObject("business_date", LocalDate.class),
                rs.getString("edit_reason_code"),
                rs.getString("last_edited_by"),
                rs.getString("approved_by"),
                nullableInstant(rs, "approved_at"),
                rs.getString("approval_reason_code"),
                rs.getString("voided_by"),
                nullableInstant(rs, "voided_at"),
                rs.getString("void_reason_code"),
                rs.getInt("version"),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static @Nullable Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
