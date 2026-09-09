package uz.horecaos.platform.pos.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.pos.domain.ScheduleCadence;

/**
 * {@code integration.pos_sync_schedules} (ADR 0012, V0037).
 *
 * <p>Persistence only, deliberately, matching {@link JdbcPosSyncStore} and
 * {@link JdbcPosApplyStore}: no outbox call and no {@code @Transactional} lives
 * here. {@link #claimDue} takes a row lock with {@code FOR UPDATE SKIP LOCKED},
 * and a lock taken outside a real transaction releases before the caller has
 * read the row — see {@code OnboardingScheduler}'s own class doc for the exact
 * shape of that mistake and why it went unnoticed. Whoever calls this class is
 * responsible for opening the transaction; {@code PosSyncSchedulingService}
 * does, and it is also the one place that then calls {@code PosSyncRequester}.
 */
@Repository
public class JdbcPosScheduleStore {

    private final JdbcClient jdbc;

    public JdbcPosScheduleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Candidate schedules to attempt this tick, cheaply.
     *
     * <p>No {@code FOR UPDATE}: this is a plain read establishing a bounded
     * working set for the tick, not a claim. Naming the set upfront is what
     * keeps one schedule that fails every time it is tried from starving every
     * other due schedule for the rest of the tick — each id in the returned list
     * is attempted exactly once by {@link #claimDue}, whatever happens to the
     * ones before it.
     */
    public List<UUID> dueScheduleIds(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id
                  FROM integration.pos_sync_schedules
                 WHERE enabled AND next_run_at <= :now
                 ORDER BY next_run_at
                 LIMIT :limit
                """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * Takes this one schedule if it is still due and nobody else already holds
     * it.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} rather than the {@code version} column:
     * the two settle the same race, but a compare-and-set on {@code version}
     * would still let a second replica read the row, compute a next occurrence,
     * and lose the write — wasted work every time two replicas overlap. {@code
     * SKIP LOCKED} means the loser does no work at all; it sees nothing to claim
     * and moves on. The {@code version} column is kept for what optimistic
     * locking is actually for here — {@code ck_pos_schedule_version} and a
     * caller that read a row outside this claim path (a future management
     * endpoint editing {@code local_time}) racing a concurrent edit — not for
     * this contended, high-frequency poll.
     *
     * <p>Must be called inside an open transaction. Empty means either another
     * replica already holds the row this tick, or it stopped being due between
     * {@link #dueScheduleIds} listing it and this call (disabled, or already
     * advanced by a claim that has since committed) — both are the ordinary,
     * silent "somebody else got it" outcome, not an error.
     */
    public Optional<ClaimedSchedule> claimDue(UUID scheduleId, Instant now) {
        return jdbc.sql("""
                SELECT id, tenant_id, binding_id, timezone, local_time
                  FROM integration.pos_sync_schedules
                 WHERE id = :id AND enabled AND next_run_at <= :now
                 FOR UPDATE SKIP LOCKED
                """)
                .param("id", scheduleId)
                .param("now", utc(now))
                .query((row, number) -> new ClaimedSchedule(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("binding_id", UUID.class),
                        row.getString("timezone"),
                        row.getObject("local_time", LocalTime.class)))
                .optional();
    }

    /**
     * Records that a claimed occurrence fired and arms the next one.
     *
     * <p>No {@code WHERE next_run_at = ...} guard beyond the primary key: safe
     * only because the caller holds this row's lock from {@link #claimDue} in
     * the same transaction, so nothing else could have moved it since.
     */
    public void advance(UUID tenantId, UUID scheduleId, Instant now, Instant nextRunAt) {
        jdbc.sql("""
                UPDATE integration.pos_sync_schedules
                   SET last_run_at = :now, next_run_at = :nextRunAt,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("now", utc(now))
                .param("nextRunAt", utc(nextRunAt))
                .param("tenantId", tenantId)
                .param("id", scheduleId)
                .update();
    }

    /**
     * Creates or replaces the one schedule a binding may have.
     *
     * <p>No control-plane endpoint calls this yet (ADR 0012's own status says
     * so); it exists for the scheduler's own tests and as the seam a future
     * management endpoint would call. {@code next_run_at} is computed here
     * rather than left null-and-armed-later, so a freshly enabled schedule
     * satisfies {@code ck_pos_schedule_armed} the instant this call returns.
     *
     * @return the schedule's id, which is a fresh id on first creation and the
     *         existing row's id on every later call for the same binding
     */
    public UUID upsert(
            UUID tenantId, UUID bindingId, String timezone, LocalTime localTime, boolean enabled, Instant now) {
        UUID id = Ids.newId();
        Instant nextRunAt = enabled ? ScheduleCadence.nextOccurrenceAfter(now, timezone, localTime) : null;

        return jdbc.sql("""
                INSERT INTO integration.pos_sync_schedules
                    (id, tenant_id, binding_id, timezone, local_time, enabled, next_run_at,
                     created_at, updated_at)
                VALUES (:id, :tenantId, :bindingId, :timezone, :localTime, :enabled, :nextRunAt, :now, :now)
                ON CONFLICT (tenant_id, binding_id) DO UPDATE
                   SET timezone = EXCLUDED.timezone, local_time = EXCLUDED.local_time,
                       enabled = EXCLUDED.enabled, next_run_at = EXCLUDED.next_run_at,
                       updated_at = EXCLUDED.updated_at,
                       version = integration.pos_sync_schedules.version + 1
                RETURNING id
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("timezone", timezone)
                .param("localTime", localTime)
                .param("enabled", enabled)
                .param("nextRunAt", nextRunAt == null ? null : utc(nextRunAt))
                .param("now", utc(now))
                .query(UUID.class)
                .single();
    }

    public Optional<ScheduleView> find(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                SELECT id, timezone, local_time, enabled, next_run_at, last_run_at, version
                  FROM integration.pos_sync_schedules
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) -> new ScheduleView(
                        row.getObject("id", UUID.class),
                        row.getString("timezone"),
                        row.getObject("local_time", LocalTime.class),
                        row.getBoolean("enabled"),
                        instant(row.getObject("next_run_at", OffsetDateTime.class)),
                        instant(row.getObject("last_run_at", OffsetDateTime.class)),
                        row.getLong("version")))
                .optional();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** One row {@link #claimDue} locked, enough to compute and write the next occurrence. */
    public record ClaimedSchedule(UUID id, UUID tenantId, UUID bindingId, String timezone, LocalTime localTime) {}

    public record ScheduleView(
            UUID id,
            String timezone,
            LocalTime localTime,
            boolean enabled,
            @Nullable Instant nextRunAt,
            @Nullable Instant lastRunAt,
            long version) {}
}
