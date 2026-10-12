package uz.horecaos.platform.workforce.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.workforce.domain.RosterStatus;

/**
 * {@code workforce.staff_roster_entries} (ADR 0156, V0529): the rota. Explicit SQL; every statement
 * names the tenant, and an update is a conditional write on the entry's version, so a stale screen
 * is told so rather than overwriting a colleague's change.
 *
 * <p>Holds no name, phone or position: an entry is a member id, a branch and a window.
 */
@Repository
public class JdbcStaffRosterStore {

    private static final String COLUMNS = """
            id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start, planned_end,
            planned_break_minutes, created_by, published_at, published_by, cancel_reason_code, version,
            created_at, updated_at""";

    private final JdbcClient jdbc;

    public JdbcStaffRosterStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record EntryRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID memberId,
            RosterStatus status,
            Instant plannedStart,
            Instant plannedEnd,
            int plannedBreakMinutes,
            String createdBy,
            @Nullable Instant publishedAt,
            @Nullable String publishedBy,
            @Nullable String cancelReasonCode,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** What a publication changed: enough to name the people in the audit fact by reference, not by name. */
    public record Published(UUID entryId, UUID memberId) {}

    public void insert(EntryRow entry) {
        jdbc.sql("""
                        INSERT INTO workforce.staff_roster_entries (
                            id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start,
                            planned_end, planned_break_minutes, created_by)
                        VALUES (:id, :tenantId, :brandId, :locationId, :memberId, 'DRAFT', :start, :end,
                                :break, :createdBy)
                        """)
                .param("id", entry.id())
                .param("tenantId", entry.tenantId())
                .param("brandId", entry.brandId())
                .param("locationId", entry.locationId())
                .param("memberId", entry.memberId())
                .param("start", utc(entry.plannedStart()))
                .param("end", utc(entry.plannedEnd()))
                .param("break", entry.plannedBreakMinutes())
                .param("createdBy", entry.createdBy())
                .update();
    }

    /**
     * As {@link #insert}, but an entry that would overlap a live one of the same person is not an error:
     * it is not written and the answer is {@code false}.
     *
     * <p>The overlap is the exclusion constraint {@code ex_staff_roster_no_overlap}. A statement that
     * fails inside a transaction aborts it, so a caller that tried an insert, caught the violation and
     * went on would find every later statement refused ({@code 25P02}); {@code ON CONFLICT DO NOTHING}
     * (no target: it covers exclusion constraints too) lets the database say "not written" without an
     * error, and the transaction stays usable. A caller that wants the overlap as an error uses {@link
     * #insert}.
     *
     * @return {@code true} when the entry was written
     */
    public boolean insertIfFree(EntryRow entry) {
        return jdbc.sql("""
                        INSERT INTO workforce.staff_roster_entries (
                            id, tenant_id, brand_id, location_id, staff_member_id, status, planned_start,
                            planned_end, planned_break_minutes, created_by)
                        VALUES (:id, :tenantId, :brandId, :locationId, :memberId, 'DRAFT', :start, :end,
                                :break, :createdBy)
                        ON CONFLICT DO NOTHING
                        """)
                        .param("id", entry.id())
                        .param("tenantId", entry.tenantId())
                        .param("brandId", entry.brandId())
                        .param("locationId", entry.locationId())
                        .param("memberId", entry.memberId())
                        .param("start", utc(entry.plannedStart()))
                        .param("end", utc(entry.plannedEnd()))
                        .param("break", entry.plannedBreakMinutes())
                        .param("createdBy", entry.createdBy())
                        .update()
                == 1;
    }

    public Optional<EntryRow> find(UUID tenantId, UUID locationId, UUID entryId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_roster_entries
                        WHERE tenant_id = :tenantId AND location_id = :locationId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("id", entryId)
                .query(JdbcStaffRosterStore::map)
                .optional();
    }

    /** Entries overlapping {@code [from, to)} at one branch, earliest first; cancelled entries are not on the grid. */
    public List<EntryRow> overlapping(UUID tenantId, UUID locationId, Instant from, Instant to) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_roster_entries
                        WHERE tenant_id = :tenantId AND location_id = :locationId
                          AND status <> 'CANCELLED'
                          AND planned_start < :to AND planned_end > :from
                        ORDER BY planned_start, id
                        LIMIT 2000
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(JdbcStaffRosterStore::map)
                .list();
    }

    /** As {@link #overlapping}, across a brand (when {@code brandId} is set) or the whole tenant. */
    public List<EntryRow> overlappingWider(UUID tenantId, @Nullable UUID brandId, Instant from, Instant to, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_roster_entries
                        WHERE tenant_id = :tenantId
                          AND (CAST(:brandId AS uuid) IS NULL OR brand_id = :brandId)
                          AND status <> 'CANCELLED'
                          AND planned_start < :to AND planned_end > :from
                        ORDER BY planned_start, id
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("from", utc(from))
                .param("to", utc(to))
                .param("limit", limit)
                .query(JdbcStaffRosterStore::map)
                .list();
    }

    /** A DRAFT or PUBLISHED entry's window, conditional on its version. @return rows changed (0: stale or not editable) */
    public int updateWindow(
            UUID tenantId, UUID entryId, int expectedVersion, Instant start, Instant end, int plannedBreakMinutes) {
        return jdbc.sql("""
                        UPDATE workforce.staff_roster_entries
                           SET planned_start = :start, planned_end = :end, planned_break_minutes = :break,
                               version = version + 1, updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id AND version = :version
                           AND status IN ('DRAFT', 'PUBLISHED')
                        """)
                .param("start", utc(start))
                .param("end", utc(end))
                .param("break", plannedBreakMinutes)
                .param("tenantId", tenantId)
                .param("id", entryId)
                .param("version", expectedVersion)
                .update();
    }

    public int cancel(UUID tenantId, UUID entryId, int expectedVersion, String reasonCode) {
        return jdbc.sql("""
                        UPDATE workforce.staff_roster_entries
                           SET status = 'CANCELLED', cancel_reason_code = :reason,
                               version = version + 1, updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id AND version = :version
                           AND status IN ('DRAFT', 'PUBLISHED')
                        """)
                .param("reason", reasonCode)
                .param("tenantId", tenantId)
                .param("id", entryId)
                .param("version", expectedVersion)
                .update();
    }

    /** DRAFT -> PUBLISHED for entries starting in {@code [from, to)} at one branch. */
    public List<Published> publishDrafts(
            UUID tenantId, UUID locationId, Instant from, Instant to, String publishedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE workforce.staff_roster_entries
                           SET status = 'PUBLISHED', published_at = :now, published_by = :by,
                               version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND location_id = :locationId AND status = 'DRAFT'
                           AND planned_start >= :from AND planned_start < :to
                        RETURNING id, staff_member_id
                        """)
                .param("now", utc(now))
                .param("by", publishedBy)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((rs, n) ->
                        new Published(rs.getObject("id", UUID.class), rs.getObject("staff_member_id", UUID.class)))
                .list();
    }

    /** How many drafts a publication of {@code [from, to)} would publish, for the confirm. */
    public List<UUID> draftMembers(UUID tenantId, UUID locationId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT staff_member_id FROM workforce.staff_roster_entries
                         WHERE tenant_id = :tenantId AND location_id = :locationId AND status = 'DRAFT'
                           AND planned_start >= :from AND planned_start < :to
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(UUID.class)
                .list();
    }

    /**
     * The entry of this person and branch whose start is nearest {@code startedAt} within {@code
     * windowMinutes} and that no shift has covered yet (ADR 0156, Lifecycle and arithmetic).
     *
     * <p>"Not covered yet" is {@code PUBLISHED} <em>or {@code MISSED}</em>. The sweeper marks an entry
     * {@code MISSED} minutes after its end, so hours a manager records after the fact -- the usual way
     * a forgotten clock-in is put right -- meet an entry that is already {@code MISSED}. Leaving it out
     * would give the shift no plan, no variance and so no approval however many hours were recorded,
     * and would leave a coverage miss standing beside the shift that covers it.
     */
    public Optional<EntryRow> nearestUncovered(
            UUID tenantId, UUID locationId, UUID memberId, Instant startedAt, int windowMinutes) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_roster_entries
                        WHERE tenant_id = :tenantId AND location_id = :locationId AND staff_member_id = :memberId
                          AND status IN ('PUBLISHED', 'MISSED')
                          AND planned_start BETWEEN :from AND :to
                        ORDER BY abs(extract(epoch FROM (planned_start - CAST(:at AS timestamptz)))), id
                        LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("memberId", memberId)
                .param("from", utc(startedAt.minusSeconds(windowMinutes * 60L)))
                .param("to", utc(startedAt.plusSeconds(windowMinutes * 60L)))
                .param("at", utc(startedAt))
                .query(JdbcStaffRosterStore::map)
                .optional();
    }

    public Optional<EntryRow> findById(UUID tenantId, UUID entryId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_roster_entries WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("id", entryId)
                .query(JdbcStaffRosterStore::map)
                .optional();
    }

    /** A person's published entries from {@code from} on, across branches, soonest first: their own «Моя работа» card. */
    public List<EntryRow> nextPublishedFor(UUID tenantId, UUID memberId, Instant from, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workforce.staff_roster_entries
                        WHERE tenant_id = :tenantId AND staff_member_id = :memberId
                          AND status = 'PUBLISHED' AND planned_end > :from
                        ORDER BY planned_start, id
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .param("from", utc(from))
                .param("limit", limit)
                .query(JdbcStaffRosterStore::map)
                .list();
    }

    /**
     * Set when a shift opens against the entry; conditional on it not being covered yet, which is
     * PUBLISHED or, for hours recorded after the sweeper ran, MISSED.
     */
    public int markConsumed(UUID tenantId, UUID entryId) {
        return jdbc.sql("""
                        UPDATE workforce.staff_roster_entries
                           SET status = 'CONSUMED', version = version + 1, updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id AND status IN ('PUBLISHED', 'MISSED')
                        """).param("tenantId", tenantId).param("id", entryId).update();
    }

    /** An entry whose only shift was voided is published again, so the coverage fact stays true. */
    public int reopenConsumed(UUID tenantId, UUID entryId) {
        return jdbc.sql("""
                        UPDATE workforce.staff_roster_entries
                           SET status = 'PUBLISHED', version = version + 1, updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id AND status = 'CONSUMED'
                        """).param("tenantId", tenantId).param("id", entryId).update();
    }

    /**
     * PUBLISHED -> MISSED for entries that ended before {@code now} with no shift: a coverage fact, set
     * by the sweeper, never a disciplinary one. @return how many entries were marked
     */
    public int markMissed(Instant now, int batchSize) {
        return jdbc.sql("""
                        UPDATE workforce.staff_roster_entries
                           SET status = 'MISSED', version = version + 1, updated_at = :now
                         WHERE id IN (
                               SELECT id FROM workforce.staff_roster_entries
                                WHERE status = 'PUBLISHED' AND planned_end < :now
                                ORDER BY planned_end
                                LIMIT :batch)
                        """).param("now", utc(now)).param("batch", batchSize).update();
    }

    public int countMissedBefore(Instant now) {
        return jdbc.sql(
                        "SELECT count(*) FROM workforce.staff_roster_entries WHERE status = 'PUBLISHED' AND planned_end < :now")
                .param("now", utc(now))
                .query(Integer.class)
                .single();
    }

    private static EntryRow map(ResultSet rs, int row) throws SQLException {
        return new EntryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("staff_member_id", UUID.class),
                RosterStatus.valueOf(rs.getString("status")),
                instant(rs, "planned_start"),
                instant(rs, "planned_end"),
                rs.getInt("planned_break_minutes"),
                rs.getString("created_by"),
                nullableInstant(rs, "published_at"),
                rs.getString("published_by"),
                rs.getString("cancel_reason_code"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
