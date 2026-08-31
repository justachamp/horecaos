package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.domain.channel.ServiceMode;
import uz.horecaos.platform.tenancy.domain.channel.WeeklySchedule;

/**
 * Everything the serviceability resolver reads, and everything operations writes
 * to change it (ADR 0036).
 *
 * <p>The reads are deliberately small and separate rather than one wide join. The
 * resolver evaluates eight rules in a fixed order and stops at the first failure,
 * so it must be able to answer rule 1 without having loaded a timetable, and the
 * reason it returns has to be the rule that actually failed rather than whichever
 * branch of a join came back empty.
 */
@Repository
public class JdbcServiceabilityStore {

    private final JdbcClient jdbc;

    public JdbcServiceabilityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------- reads

    /** The location's IANA zone, which is what every local time here is resolved against. */
    public Optional<ZoneId> timezoneOf(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT timezone FROM tenant.locations
                WHERE tenant_id = :tenantId AND id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(String.class)
                .optional()
                .map(ZoneId::of);
    }

    /** Resolver rules 1 and 2, in one round trip because they share one answer. */
    public ChannelAtLocation channelAtLocation(UUID tenantId, UUID channelId, UUID locationId) {
        return jdbc.sql("""
                SELECT c.status = 'ACTIVE' AS channel_active,
                       c.code AS channel_code,
                       EXISTS (
                           SELECT 1 FROM tenant.sales_channel_locations l
                           WHERE l.tenant_id = :tenantId AND l.channel_id = c.id
                             AND l.location_id = :locationId AND l.status = 'ACTIVE'
                       ) AS enabled_here
                FROM tenant.sales_channels c
                WHERE c.tenant_id = :tenantId AND c.id = :channelId
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("locationId", locationId)
                .query((row, number) -> new ChannelAtLocation(
                        true,
                        row.getBoolean("channel_active"),
                        row.getBoolean("enabled_here"),
                        row.getString("channel_code")))
                .optional()
                // A channel id that names no row of this tenant is not an error to
                // throw: it is exactly the CHANNEL_NOT_ENABLED answer, and treating
                // it as an exception would turn a customer following a stale link
                // into a 500.
                .orElse(new ChannelAtLocation(false, false, false, null));
    }

    /**
     * Resolver rule 3.
     *
     * <p>Absent means unavailable. A channel with no matrix rows sells nothing
     * rather than everything, so a half-configured channel is visibly broken
     * instead of quietly permissive.
     */
    public boolean fulfillmentModeEnabled(UUID tenantId, UUID channelId, FulfillmentMode mode) {
        return jdbc.sql("""
                SELECT count(*) FROM tenant.channel_fulfillment_modes
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                  AND fulfillment_mode = :mode AND enabled
                """)
                        .param("tenantId", tenantId)
                        .param("channelId", channelId)
                        .param("mode", mode.name())
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Resolver rule 4 and rule 8's ceiling. Absent means FOLLOW_SCHEDULE, uncapped. */
    public ServiceState serviceState(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT mode, reason_code, effective_until, max_concurrent_orders, version
                FROM tenant.location_service_state
                WHERE tenant_id = :tenantId AND location_id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((row, number) -> new ServiceState(
                        ServiceMode.valueOf(row.getString("mode")),
                        row.getString("reason_code"),
                        instant(row.getObject("effective_until", OffsetDateTime.class)),
                        (Integer) row.getObject("max_concurrent_orders"),
                        row.getInt("version")))
                .optional()
                .orElse(ServiceState.followingSchedule());
    }

    /** The timetable bound to one fulfilment mode at one location, if any. */
    public Optional<BoundSchedule> scheduleFor(UUID tenantId, UUID locationId, FulfillmentMode mode) {
        Optional<ScheduleHeader> header = jdbc.sql("""
                SELECT s.id, s.accepts_scheduled_orders
                FROM tenant.location_service_bindings b
                JOIN tenant.service_schedules s
                  ON s.id = b.schedule_id AND s.tenant_id = b.tenant_id
                WHERE b.tenant_id = :tenantId AND b.location_id = :locationId
                  AND b.fulfillment_mode = :mode
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("mode", mode.name())
                .query((row, number) ->
                        new ScheduleHeader(row.getObject("id", UUID.class), row.getBoolean("accepts_scheduled_orders")))
                .optional();

        return header.map(found -> new BoundSchedule(
                found.scheduleId(),
                new WeeklySchedule(
                        rulesOf(found.scheduleId()),
                        exceptionsOf(found.scheduleId()),
                        found.acceptsScheduledOrders())));
    }

    /** Resolver rule 7. Reads the publication by channel code, per the ADR 0016 correction. */
    public boolean hasLivePublication(UUID tenantId, UUID brandId, String channelCode) {
        return jdbc.sql("""
                SELECT count(*) FROM catalog.publications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND channel = :channel AND status = 'PUBLISHED'
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("channel", channelCode)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * The preparation band covering one local moment.
     *
     * <p>Every tiebreak is explicit — priority, then the narrower row, then the id
     * — because the promised time must not depend on which row the planner emitted
     * first. Two runs of the same order quoting different times is the defect this
     * ordering exists to prevent.
     */
    public Optional<Integer> preparationMinutes(
            UUID tenantId, UUID locationId, FulfillmentMode mode, int dayOfWeek, LocalTime localTime) {
        return jdbc.sql("""
                SELECT duration_minutes
                FROM tenant.preparation_bands
                WHERE tenant_id = :tenantId AND location_id = :locationId
                  AND (fulfillment_mode IS NULL OR fulfillment_mode = :mode)
                  AND (day_of_week IS NULL OR day_of_week = :dayOfWeek)
                  AND starts_at <= :localTime AND ends_at > :localTime
                ORDER BY priority DESC,
                         (fulfillment_mode IS NOT NULL) DESC,
                         (day_of_week IS NOT NULL) DESC,
                         id
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("mode", mode.name())
                .param("dayOfWeek", dayOfWeek)
                .param("localTime", localTime)
                .query(Integer.class)
                .optional();
    }

    public long openCapacityHolds(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT count(*) FROM tenant.location_capacity_holds
                WHERE tenant_id = :tenantId AND location_id = :locationId AND released_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------ writes

    public void insertSchedule(
            UUID id, UUID tenantId, UUID brandId, String name, boolean acceptsScheduledOrders, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.service_schedules (
                    id, tenant_id, brand_id, name, accepts_scheduled_orders, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :name, :accepts, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("name", name)
                .param("accepts", acceptsScheduledOrders)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Whether this timetable belongs to this brand.
     *
     * <p>{@code service_schedule_rules} and {@code service_schedule_exceptions}
     * are keyed on {@code schedule_id} alone and carry no {@code tenant_id} — the
     * timetable above them is the only place ownership is recorded. So a write
     * that trusts a caller-supplied schedule id is a write into whichever tenant
     * owns it, and the database will not object: the foreign key is
     * single-column, unlike the composite one {@code location_service_bindings}
     * uses in the same migration.
     */
    public boolean scheduleBelongsToBrand(UUID tenantId, UUID brandId, UUID scheduleId) {
        return jdbc.sql("""
                SELECT 1 FROM tenant.service_schedules
                 WHERE id = :scheduleId AND tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("scheduleId", scheduleId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public void replaceRules(UUID scheduleId, List<WeeklySchedule.Rule> rules) {
        jdbc.sql("DELETE FROM tenant.service_schedule_rules WHERE schedule_id = :scheduleId")
                .param("scheduleId", scheduleId)
                .update();
        int sequence = 0;
        for (WeeklySchedule.Rule rule : rules) {
            jdbc.sql("""
                    INSERT INTO tenant.service_schedule_rules (
                        schedule_id, sequence, day_of_week, opens_at, closes_at)
                    VALUES (:scheduleId, :sequence, :dayOfWeek, :opensAt, :closesAt)
                    """)
                    .param("scheduleId", scheduleId)
                    .param("sequence", sequence++)
                    .param("dayOfWeek", (short) rule.dayOfWeek())
                    .param("opensAt", rule.opensAt())
                    .param("closesAt", rule.closesAt())
                    .update();
        }
    }

    public void upsertException(
            UUID scheduleId,
            LocalDate date,
            boolean closedAllDay,
            @Nullable LocalTime opensAt,
            @Nullable LocalTime closesAt,
            String label,
            String reason,
            @Nullable UUID actorId) {
        jdbc.sql("""
                INSERT INTO tenant.service_schedule_exceptions (
                    id, schedule_id, exception_date, closed_all_day, opens_at, closes_at,
                    label, created_by, reason)
                VALUES (:id, :scheduleId, :date, :closedAllDay, :opensAt, :closesAt,
                    :label, :actorId, :reason)
                ON CONFLICT (schedule_id, exception_date) DO UPDATE SET
                    closed_all_day = EXCLUDED.closed_all_day,
                    opens_at = EXCLUDED.opens_at,
                    closes_at = EXCLUDED.closes_at,
                    label = EXCLUDED.label,
                    created_by = EXCLUDED.created_by,
                    reason = EXCLUDED.reason
                """)
                .param("id", UUID.randomUUID())
                .param("scheduleId", scheduleId)
                .param("date", date)
                .param("closedAllDay", closedAllDay)
                .param("opensAt", opensAt)
                .param("closesAt", closesAt)
                .param("label", label)
                .param("actorId", actorId)
                .param("reason", reason)
                .update();
    }

    public void bindSchedule(
            UUID tenantId, UUID brandId, UUID locationId, FulfillmentMode mode, UUID scheduleId, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.location_service_bindings (
                    tenant_id, brand_id, location_id, fulfillment_mode, schedule_id,
                    created_at, updated_at)
                VALUES (:tenantId, :brandId, :locationId, :mode, :scheduleId, :now, :now)
                ON CONFLICT (location_id, fulfillment_mode) DO UPDATE SET
                    schedule_id = EXCLUDED.schedule_id,
                    version = tenant.location_service_bindings.version + 1,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("mode", mode.name())
                .param("scheduleId", scheduleId)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Writes the manual override.
     *
     * <p>{@code changed_by}, {@code reason_code} and {@code changed_at} move
     * together with the mode, so a state row always answers "who, why, and when"
     * rather than only "closed". The mandatory reason is enforced by
     * {@code ck_location_service_reason} in V0020 as well as here.
     */
    public void upsertServiceState(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            ServiceMode mode,
            @Nullable String reasonCode,
            @Nullable String note,
            @Nullable Instant effectiveUntil,
            @Nullable UUID actorId,
            Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.location_service_state (
                    location_id, tenant_id, brand_id, mode, reason_code, note,
                    effective_until, changed_by, changed_at)
                VALUES (:locationId, :tenantId, :brandId, :mode, :reasonCode, :note,
                    :effectiveUntil, :actorId, :now)
                ON CONFLICT (location_id) DO UPDATE SET
                    mode = EXCLUDED.mode,
                    reason_code = EXCLUDED.reason_code,
                    note = EXCLUDED.note,
                    effective_until = EXCLUDED.effective_until,
                    changed_by = EXCLUDED.changed_by,
                    changed_at = EXCLUDED.changed_at,
                    version = tenant.location_service_state.version + 1
                """)
                .param("locationId", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("mode", mode.name())
                .param("reasonCode", reasonCode)
                .param("note", note)
                .param("effectiveUntil", effectiveUntil == null ? null : timestamp(effectiveUntil))
                .param("actorId", actorId)
                .param("now", timestamp(now))
                .update();
    }

    /** Sets the ceiling without touching the open/closed override or its reason. */
    public void setCapacity(UUID tenantId, UUID brandId, UUID locationId, Integer maxConcurrentOrders, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.location_service_state (
                    location_id, tenant_id, brand_id, mode, max_concurrent_orders, changed_at)
                VALUES (:locationId, :tenantId, :brandId, 'FOLLOW_SCHEDULE', :cap, :now)
                ON CONFLICT (location_id) DO UPDATE SET
                    max_concurrent_orders = EXCLUDED.max_concurrent_orders,
                    version = tenant.location_service_state.version + 1
                """)
                .param("locationId", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("cap", maxConcurrentOrders)
                .param("now", timestamp(now))
                .update();
    }

    public void replacePreparationBands(UUID tenantId, UUID brandId, UUID locationId, List<Band> bands, Instant now) {
        jdbc.sql("DELETE FROM tenant.preparation_bands " + "WHERE tenant_id = :tenantId AND location_id = :locationId")
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .update();
        for (Band band : bands) {
            jdbc.sql("""
                    INSERT INTO tenant.preparation_bands (
                        id, tenant_id, brand_id, location_id, fulfillment_mode, day_of_week,
                        starts_at, ends_at, duration_minutes, priority, created_at, updated_at)
                    VALUES (:id, :tenantId, :brandId, :locationId, :mode, :dayOfWeek,
                        :startsAt, :endsAt, :duration, :priority, :now, :now)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("locationId", locationId)
                    .param("mode", band.mode() == null ? null : band.mode().name())
                    .param(
                            "dayOfWeek",
                            band.dayOfWeek() == null ? null : band.dayOfWeek().shortValue())
                    .param("startsAt", band.startsAt())
                    .param("endsAt", band.endsAt())
                    .param("duration", band.durationMinutes())
                    .param("priority", band.priority())
                    .param("now", timestamp(now))
                    .update();
        }
    }

    // ---------------------------------------------------------------- capacity

    /**
     * Serialises two checkouts racing for the last free slot.
     *
     * <p>Takes a row lock on the location's service state and returns the ceiling.
     * The loser blocks here until the winner commits, and — because each statement
     * under READ COMMITTED takes a fresh snapshot — the count that follows sees the
     * winner's hold. That is what settles the race in the database rather than by
     * comparing numbers two transactions each read a second earlier.
     *
     * <p>Empty when the location has no ceiling, in which case there is nothing to
     * serialise on and nothing to refuse.
     */
    public Optional<Integer> lockCapacityCeiling(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT max_concurrent_orders FROM tenant.location_service_state
                WHERE tenant_id = :tenantId AND location_id = :locationId
                  AND max_concurrent_orders IS NOT NULL
                FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(Integer.class)
                .optional();
    }

    /**
     * Claims one slot.
     *
     * <p>Keyed by the cart or order id, so a retried checkout re-claims the slot it
     * already holds instead of consuming a second one and reporting the kitchen
     * busier than it is.
     */
    public void claimCapacity(UUID holdId, UUID tenantId, UUID brandId, UUID locationId, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.location_capacity_holds (
                    id, tenant_id, brand_id, location_id, held_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :now)
                ON CONFLICT (id) DO UPDATE SET released_at = NULL
                """)
                .param("id", holdId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("now", timestamp(now))
                .update();
    }

    public boolean releaseCapacity(UUID holdId, UUID tenantId, Instant now) {
        return jdbc.sql("""
                UPDATE tenant.location_capacity_holds SET released_at = :now
                WHERE id = :id AND tenant_id = :tenantId AND released_at IS NULL
                """)
                        .param("id", holdId)
                        .param("tenantId", tenantId)
                        .param("now", timestamp(now))
                        .update()
                == 1;
    }

    public boolean holdsCapacity(UUID holdId, UUID tenantId) {
        return jdbc.sql("""
                SELECT count(*) FROM tenant.location_capacity_holds
                WHERE id = :id AND tenant_id = :tenantId AND released_at IS NULL
                """)
                        .param("id", holdId)
                        .param("tenantId", tenantId)
                        .query(Long.class)
                        .single()
                == 1;
    }

    // --------------------------------------------------------------- row types

    public record ChannelAtLocation(
            boolean exists, boolean active, boolean enabledAtLocation, @Nullable String channelCode) {}

    public record ServiceState(
            ServiceMode mode,
            @Nullable String reasonCode,
            @Nullable Instant effectiveUntil,
            @Nullable Integer maxConcurrentOrders,
            int version) {

        static ServiceState followingSchedule() {
            return new ServiceState(ServiceMode.FOLLOW_SCHEDULE, null, null, null, 1);
        }

        /**
         * The mode as it reads now.
         *
         * <p>An elapsed {@code effective_until} returns the location to
         * FOLLOW_SCHEDULE by being read as elapsed. No job computes it, because a
         * job that failed would leave a network closed with a cause
         * indistinguishable from an outage.
         */
        public ServiceMode effectiveMode(Instant at) {
            if (mode == ServiceMode.FOLLOW_SCHEDULE) {
                return ServiceMode.FOLLOW_SCHEDULE;
            }
            return effectiveUntil != null && !effectiveUntil.isAfter(at) ? ServiceMode.FOLLOW_SCHEDULE : mode;
        }
    }

    public record BoundSchedule(UUID scheduleId, WeeklySchedule schedule) {}

    public record Band(
            @Nullable FulfillmentMode mode,
            @Nullable Integer dayOfWeek,
            LocalTime startsAt,
            LocalTime endsAt,
            int durationMinutes,
            int priority) {}

    private record ScheduleHeader(UUID scheduleId, boolean acceptsScheduledOrders) {}

    private List<WeeklySchedule.Rule> rulesOf(UUID scheduleId) {
        return jdbc.sql("""
                SELECT day_of_week, opens_at, closes_at FROM tenant.service_schedule_rules
                WHERE schedule_id = :scheduleId ORDER BY sequence
                """)
                .param("scheduleId", scheduleId)
                .query((row, number) -> new WeeklySchedule.Rule(
                        row.getShort("day_of_week"),
                        row.getObject("opens_at", LocalTime.class),
                        row.getObject("closes_at", LocalTime.class)))
                .list();
    }

    private Map<LocalDate, WeeklySchedule.DatedException> exceptionsOf(UUID scheduleId) {
        Map<LocalDate, WeeklySchedule.DatedException> exceptions = new HashMap<>();
        jdbc.sql("""
                SELECT exception_date, closed_all_day, opens_at, closes_at
                FROM tenant.service_schedule_exceptions
                WHERE schedule_id = :scheduleId
                """)
                .param("scheduleId", scheduleId)
                .query((row, number) -> Map.entry(
                        row.getObject("exception_date", LocalDate.class),
                        row.getBoolean("closed_all_day")
                                ? WeeklySchedule.DatedException.closed()
                                : WeeklySchedule.DatedException.open(
                                        row.getObject("opens_at", LocalTime.class),
                                        row.getObject("closes_at", LocalTime.class))))
                .list()
                .forEach(entry -> exceptions.put(entry.getKey(), entry.getValue()));
        return exceptions;
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
