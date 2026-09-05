package uz.horecaos.platform.kitchen.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.domain.RoutingLevel;
import uz.horecaos.platform.kitchen.domain.StationRole;
import uz.horecaos.platform.kitchen.domain.TicketItemStatus;
import uz.horecaos.platform.kitchen.domain.TicketStatus;

/**
 * Kitchen persistence (ADR 0041).
 *
 * <p>Three rules run through every statement.
 *
 * <p>The tenant predicate is always inside the query. A ticket id is a UUID that
 * arrives from a device on a counter in a restaurant, and a lookup matching on it
 * alone would put another tenant's service on the screen.
 *
 * <p>Every state change is a conditional UPDATE naming the status it expects, and
 * the row count decides who won. Two devices marking the same item ready, three
 * stations finishing in the same second, and an offline client replaying a queued
 * advance all reduce to the same question, answered by PostgreSQL rather than by
 * whichever request arrived first. The loser is never an error: a cook cannot
 * interpret one, and a screen that errors on a second tap is a screen that gets
 * tapped a third time.
 *
 * <p>Nothing here writes {@code ordering.orders}. The two reads that cross into
 * ordering live in {@code JdbcKitchenOrderSource} and are reads.
 */
@Repository
public class JdbcKitchenStore {

    private final JdbcClient jdbc;

    public JdbcKitchenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ stations

    public void insertStation(StationRow station) {
        jdbc.sql("""
                INSERT INTO kitchen.stations (
                    id, tenant_id, brand_id, location_id, code, role,
                    display_name_ru, display_name_uz, display_name_en,
                    sort_order, is_fallback, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :code, :role,
                    :nameRu, :nameUz, :nameEn,
                    :sortOrder, :isFallback, :status, 1, :now, :now)
                """)
                .param("id", station.id())
                .param("tenantId", station.tenantId())
                .param("brandId", station.brandId())
                .param("locationId", station.locationId())
                .param("code", station.code())
                .param("role", station.role().name())
                .param("nameRu", station.displayNameRu())
                .param("nameUz", station.displayNameUz())
                .param("nameEn", station.displayNameEn())
                .param("sortOrder", station.sortOrder())
                .param("isFallback", station.fallback())
                .param("status", station.status())
                .param("now", utc(station.createdAt()))
                .update();
    }

    public List<StationRow> listStations(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_STATION + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY sort_order, code
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcKitchenStore::mapStation)
                .list();
    }

    public Optional<StationRow> findStation(UUID tenantId, UUID stationId) {
        return jdbc.sql(SELECT_STATION + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", stationId)
                .query(JdbcKitchenStore::mapStation)
                .optional();
    }

    // ---------------------------------------------------------- station capacity

    public void insertStationCapacity(StationCapacityRow row) {
        jdbc.sql("""
                INSERT INTO kitchen.station_capacity (
                    id, tenant_id, brand_id, location_id, station_id,
                    weekday, window_start, window_end, portions_per_hour,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :stationId,
                    :weekday, :windowStart, :windowEnd, :portionsPerHour,
                    1, :now, :now)
                """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("brandId", row.brandId())
                .param("locationId", row.locationId())
                .param("stationId", row.stationId())
                .param("weekday", row.weekday())
                .param("windowStart", row.windowStart())
                .param("windowEnd", row.windowEnd())
                .param("portionsPerHour", row.portionsPerHour())
                .param("now", utc(row.createdAt()))
                .update();
    }

    public List<StationCapacityRow> listStationCapacity(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_STATION_CAPACITY + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY station_id, weekday, window_start
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcKitchenStore::mapStationCapacity)
                .list();
    }

    /**
     * Whether a proposed window would overlap one already stored for this station
     * and weekday.
     *
     * <p>Checked by the service before every insert rather than by a database
     * exclusion constraint — see V0144's own comment for why a `time`-typed window
     * does not get one in this release. A concurrent double-submit can still race
     * past this check; {@code uq_station_capacity_window} at the database is the
     * backstop for the one shape of that race an exact retry produces.
     */
    public boolean overlapsExisting(UUID tenantId, UUID stationId, int weekday, LocalTime start, LocalTime end) {
        Boolean exists = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM kitchen.station_capacity
                     WHERE tenant_id = :tenantId AND station_id = :stationId AND weekday = :weekday
                       AND window_start < :end AND window_end > :start
                )
                """)
                .param("tenantId", tenantId)
                .param("stationId", stationId)
                .param("weekday", weekday)
                .param("start", start)
                .param("end", end)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    // ------------------------------------------------------------ routing rules

    /**
     * Writes one brand-layer or location-layer routing rule.
     *
     * @param locationId  null for a brand rule
     * @param variantId   set together with exactly one of {@code productId} and
     *                    {@code categoryId} left null, per the rule's addressed node
     * @param productId   see {@code variantId}
     * @param categoryId  see {@code variantId}
     * @param stationRole null for a location rule, which names a station directly
     * @param stationId   null for a brand rule, which names a role the location
     *                    resolves for itself
     */
    public void insertRoutingRule(
            UUID id,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable UUID variantId,
            @Nullable UUID productId,
            @Nullable UUID categoryId,
            @Nullable StationRole stationRole,
            @Nullable UUID stationId,
            Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("locationId", locationId);
        params.put("variantId", variantId);
        params.put("productId", productId);
        params.put("categoryId", categoryId);
        params.put("role", stationRole == null ? null : stationRole.name());
        params.put("stationId", stationId);
        params.put("now", utc(now));

        if (stationId == null) {
            jdbc.sql("""
                    INSERT INTO kitchen.brand_routing_rules (
                        id, tenant_id, brand_id, variant_id, product_id, category_id,
                        station_role, version, created_at, updated_at)
                    VALUES (:id, :tenantId, :brandId, :variantId, :productId, :categoryId,
                        :role, 1, :now, :now)
                    """).params(params).update();
        } else {
            jdbc.sql("""
                    INSERT INTO kitchen.location_routing_rules (
                        id, tenant_id, brand_id, location_id, variant_id, product_id,
                        category_id, station_id, version, created_at, updated_at)
                    VALUES (:id, :tenantId, :brandId, :locationId, :variantId, :productId,
                        :categoryId, :stationId, 1, :now, :now)
                    """).params(params).update();
        }
    }

    /**
     * ADR 0041's five resolution levels, first match wins, in one statement.
     *
     * <p>One statement rather than five round trips, and one statement rather than
     * five methods a caller could get the order of wrong: the precedence <em>is</em>
     * the design, and putting it in the {@code ORDER BY} of a single query is the
     * only place it cannot drift from the ADR by editing one branch of an
     * if-chain.
     *
     * <p>Every level joins {@code kitchen.stations} on {@code status = 'ACTIVE'},
     * including the levels that name a station directly. An override pointing at a
     * station somebody archived last week must not win: it would resolve to a
     * screen nobody is watching, which is indistinguishable from losing the dish.
     * Falling through to the next level instead puts it somewhere a cook will see
     * it.
     *
     * <p>A product may sit in several categories, so the category levels are tied
     * by the category's own sort order and then by station id. Without a
     * tie-break, two services would route one dish two ways and nobody could say
     * why.
     *
     * @return empty when nothing matched, which the caller answers with the
     *         fallback station and {@code KitchenRoutingUnresolved}
     */
    public Optional<ResolvedStation> resolveStation(
            UUID tenantId, UUID brandId, UUID locationId, UUID variantId, @Nullable UUID productId) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("locationId", locationId);
        params.put("variantId", variantId);
        params.put("productId", productId);

        return jdbc.sql("""
                WITH product_categories AS (
                    SELECT cp.category_id, c.sort_order
                    FROM catalog.category_products cp
                    JOIN catalog.categories c
                      ON c.id = cp.category_id
                     AND c.tenant_id = cp.tenant_id
                     AND c.brand_id = cp.brand_id
                    WHERE cp.tenant_id = :tenantId
                      AND cp.brand_id = :brandId
                      AND cp.product_id = CAST(:productId AS uuid)
                ),
                candidates AS (
                    SELECT 1 AS level, r.station_id, NULL::varchar AS station_role, 0 AS tie
                    FROM kitchen.location_routing_rules r
                    WHERE r.tenant_id = :tenantId AND r.location_id = :locationId
                      AND r.variant_id = CAST(:variantId AS uuid)
                    UNION ALL
                    SELECT 2, r.station_id, NULL::varchar, 0
                    FROM kitchen.location_routing_rules r
                    WHERE r.tenant_id = :tenantId AND r.location_id = :locationId
                      AND r.product_id = CAST(:productId AS uuid)
                    UNION ALL
                    SELECT 3, r.station_id, NULL::varchar, pc.sort_order
                    FROM kitchen.location_routing_rules r
                    JOIN product_categories pc ON pc.category_id = r.category_id
                    WHERE r.tenant_id = :tenantId AND r.location_id = :locationId
                    UNION ALL
                    SELECT 4, NULL::uuid, r.station_role, 0
                    FROM kitchen.brand_routing_rules r
                    WHERE r.tenant_id = :tenantId AND r.brand_id = :brandId
                      AND r.variant_id = CAST(:variantId AS uuid)
                    UNION ALL
                    SELECT 5, NULL::uuid, r.station_role, 0
                    FROM kitchen.brand_routing_rules r
                    WHERE r.tenant_id = :tenantId AND r.brand_id = :brandId
                      AND r.product_id = CAST(:productId AS uuid)
                    UNION ALL
                    SELECT 6, NULL::uuid, r.station_role, pc.sort_order
                    FROM kitchen.brand_routing_rules r
                    JOIN product_categories pc ON pc.category_id = r.category_id
                    WHERE r.tenant_id = :tenantId AND r.brand_id = :brandId
                )
                SELECT c.level AS level, s.id AS station_id
                FROM candidates c
                JOIN kitchen.stations s
                  ON s.tenant_id = :tenantId
                 AND s.location_id = :locationId
                 AND s.status = 'ACTIVE'
                 AND ((c.station_id IS NOT NULL AND s.id = c.station_id)
                   OR (c.station_id IS NULL AND s.role = c.station_role))
                ORDER BY c.level, c.tie, s.id
                LIMIT 1
                """)
                .params(params)
                .query((row, number) ->
                        new ResolvedStation(row.getObject("station_id", UUID.class), levelOf(row.getInt("level"))))
                .optional();
    }

    private static RoutingLevel levelOf(int level) {
        return switch (level) {
            case 1 -> RoutingLevel.LOCATION_VARIANT;
            case 2 -> RoutingLevel.LOCATION_PRODUCT;
            case 3 -> RoutingLevel.LOCATION_CATEGORY;
            // Four, five and six are the brand layer's own variant, product and
            // category order. They collapse to one level here because the answer
            // an operator needs is "the brand mapped this, not your branch"; which
            // of the brand's three rules matched is the brand rule table's to
            // answer, and putting it on every ticket item would triple a
            // vocabulary the kitchen never reads.
            default -> RoutingLevel.BRAND_ROLE;
        };
    }

    /** The location's fallback station, which routing lands on when nothing matched. */
    public Optional<UUID> findFallbackStation(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT id FROM kitchen.stations
                WHERE tenant_id = :tenantId AND location_id = :locationId AND is_fallback
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(UUID.class)
                .optional();
    }

    // -------------------------------------------------------------------- tickets

    public void insertTicket(TicketRow ticket) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", ticket.id());
        params.put("tenantId", ticket.tenantId());
        params.put("brandId", ticket.brandId());
        params.put("locationId", ticket.locationId());
        params.put("orderId", ticket.orderId());
        params.put("label", ticket.sequenceLabel());
        params.put("mode", ticket.fulfilmentMode());
        params.put("channel", ticket.channelCode());
        params.put("status", ticket.status().name());
        params.put("releaseMode", ticket.releaseMode().name());
        params.put("releaseAt", nullableUtc(ticket.releaseAt()));
        params.put("releasedAt", nullableUtc(ticket.releasedAt()));
        params.put("prepSeconds", ticket.prepEstimateSeconds());
        params.put("targetReadyAt", nullableUtc(ticket.targetReadyAt()));
        params.put("routingVersion", ticket.routingVersion());
        params.put("now", utc(ticket.createdAt()));

        jdbc.sql("""
                INSERT INTO kitchen.tickets (
                    id, tenant_id, brand_id, location_id, order_id, sequence_label,
                    fulfilment_mode, channel_code, status, release_mode, release_at,
                    released_at, prep_estimate_seconds, target_ready_at, routing_version,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :label,
                    :mode, :channel, :status, :releaseMode, :releaseAt,
                    :releasedAt, :prepSeconds, :targetReadyAt, :routingVersion,
                    1, :now, :now)
                """).params(params).update();
    }

    public Optional<TicketRow> findTicket(UUID tenantId, UUID ticketId) {
        return jdbc.sql(SELECT_TICKET + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", ticketId)
                .query(JdbcKitchenStore::mapTicket)
                .optional();
    }

    public Optional<TicketRow> findTicketByOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql(SELECT_TICKET + " WHERE tenant_id = :tenantId AND order_id = :orderId")
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcKitchenStore::mapTicket)
                .optional();
    }

    /**
     * The board: what is live at this branch.
     *
     * <p>Ordered by the instant the food is due rather than by when the ticket
     * arrived, because the card a cook must deal with first is the one closest to
     * being late. Tickets with no target sort last: a ticket nobody promised
     * anything for cannot be overdue, and putting it at the top would push a
     * genuinely late one off the first screen.
     */
    public List<TicketRow> board(UUID tenantId, UUID locationId, List<String> statuses, int limit) {
        return jdbc.sql(SELECT_TICKET + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                   AND status IN (:statuses)
                 ORDER BY target_ready_at ASC NULLS LAST, created_at ASC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("statuses", statuses)
                .param("limit", limit)
                .query(JdbcKitchenStore::mapTicket)
                .list();
    }

    /**
     * Claims tickets whose scheduled release has come due.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}, like every other durable timer in this
     * codebase, so two application nodes share the buffer rather than firing every
     * preorder twice. The claim only selects; the caller applies each release
     * through the same conditional update a human release goes through, so a cook
     * pressing "release now" in the same instant still settles at one outcome.
     */
    public List<TicketRow> claimDueForRelease(Instant now, int batchSize) {
        // No tenant predicate, and deliberately: this is a system sweep across
        // every tenant's buffer, like ordering's approval-deadline claim. The
        // tenant travels back on each row and every statement the caller runs
        // afterwards carries it.
        return jdbc.sql(SELECT_TICKET + """
                 WHERE status = 'HELD' AND release_at IS NOT NULL AND release_at <= :now
                 ORDER BY release_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT :batchSize
                """)
                .param("now", utc(now))
                .param("batchSize", batchSize)
                .query(JdbcKitchenStore::mapTicket)
                .list();
    }

    /**
     * Moves a ticket, conditional on it still being where the caller last saw it.
     *
     * @return the new version, or empty when somebody else moved it first
     */
    public Optional<Integer> transitionTicket(
            UUID tenantId, UUID ticketId, TicketStatus from, TicketStatus to, Instant now) {
        return jdbc.sql("""
                UPDATE kitchen.tickets
                SET status = :to,
                    version = version + 1,
                    updated_at = :now,
                    released_at = CASE WHEN :to = 'FIRED' AND released_at IS NULL
                                       THEN :now ELSE released_at END,
                    started_at = CASE WHEN :to = 'IN_PRODUCTION' AND started_at IS NULL
                                      THEN :now ELSE started_at END,
                    -- A recall clears the readiness it undoes. Leaving it would
                    -- make the pass report a ticket that was ready at a time it
                    -- demonstrably was not, and every lateness figure derived from
                    -- it would be wrong in the kitchen's favour.
                    ready_at = CASE WHEN :to = 'READY' THEN :now
                                    WHEN :to = 'IN_PRODUCTION' THEN NULL
                                    ELSE ready_at END,
                    handed_over_at = CASE WHEN :to = 'HANDED_OVER' THEN :now
                                          ELSE handed_over_at END
                WHERE tenant_id = :tenantId AND id = :id AND status = :from
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", ticketId)
                .param("from", from.name())
                .param("to", to.name())
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    /**
     * Changes a held ticket's fire time, or its mode.
     *
     * <p>Conditional on {@code HELD}: re-timing a ticket the kitchen has already
     * started is a fire time for food that is already cooking.
     */
    public Optional<Integer> rescheduleRelease(
            UUID tenantId, UUID ticketId, ReleaseMode mode, Instant releaseAt, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", ticketId);
        params.put("mode", mode.name());
        params.put("releaseAt", nullableUtc(releaseAt));
        params.put("now", utc(now));

        return jdbc.sql("""
                UPDATE kitchen.tickets
                SET release_mode = :mode,
                    release_at = :releaseAt,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'HELD'
                RETURNING version
                """).params(params).query(Integer.class).optional();
    }

    // ---------------------------------------------------------------- ticket items

    public void insertItem(TicketItemRow item) {
        jdbc.sql("""
                INSERT INTO kitchen.ticket_items (
                    id, tenant_id, ticket_id, location_id, order_line_id, station_id,
                    quantity, routed_by, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :ticketId, :locationId, :lineId, :stationId,
                    :quantity, :routedBy, :status, 1, :now, :now)
                """)
                .param("id", item.id())
                .param("tenantId", item.tenantId())
                .param("ticketId", item.ticketId())
                .param("locationId", item.locationId())
                .param("lineId", item.orderLineId())
                .param("stationId", item.stationId())
                .param("quantity", item.quantity())
                .param("routedBy", item.routedBy().name())
                .param("status", item.status().name())
                .param("now", utc(item.createdAt()))
                .update();
    }

    public List<TicketItemRow> itemsOf(UUID tenantId, UUID ticketId) {
        return jdbc.sql(SELECT_ITEM + """
                 WHERE tenant_id = :tenantId AND ticket_id = :ticketId
                 ORDER BY created_at, id
                """)
                .param("tenantId", tenantId)
                .param("ticketId", ticketId)
                .query(JdbcKitchenStore::mapItem)
                .list();
    }

    public Optional<TicketItemRow> findItem(UUID tenantId, UUID itemId) {
        return jdbc.sql(SELECT_ITEM + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", itemId)
                .query(JdbcKitchenStore::mapItem)
                .optional();
    }

    /**
     * Moves one item, conditional on its current status.
     *
     * <p>The whole of ADR 0041's "two devices marking the same item ready settle
     * once" is this {@code WHERE} clause. The second device's update matches no
     * row, the caller reads the settled state back, and the cook sees the item
     * ready — which is what they were asking for — rather than an error.
     */
    public Optional<Integer> transitionItem(
            UUID tenantId, UUID itemId, TicketItemStatus from, TicketItemStatus to, Instant now) {
        return jdbc.sql("""
                UPDATE kitchen.ticket_items
                SET status = :to,
                    version = version + 1,
                    updated_at = :now,
                    started_at = CASE WHEN :to = 'STARTED' AND started_at IS NULL
                                      THEN :now ELSE started_at END,
                    ready_at = CASE WHEN :to = 'READY' THEN :now
                                    WHEN :to = 'STARTED' THEN NULL
                                    ELSE ready_at END,
                    cancelled_at = CASE WHEN :to = 'CANCELLED' THEN :now
                                        ELSE cancelled_at END
                WHERE tenant_id = :tenantId AND id = :id AND status = :from
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", itemId)
                .param("from", from.name())
                .param("to", to.name())
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    // -------------------------------------------------------------------- events

    public void recordEvent(
            UUID tenantId,
            UUID ticketId,
            @Nullable UUID ticketItemId,
            @Nullable String fromStatus,
            String toStatus,
            String trigger,
            String actorType,
            String actorId,
            @Nullable String reasonCode,
            @Nullable String correlationId,
            Instant occurredAt) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("tenantId", tenantId);
        params.put("ticketId", ticketId);
        params.put("itemId", ticketItemId);
        params.put("fromStatus", fromStatus);
        params.put("toStatus", toStatus);
        params.put("trigger", trigger);
        params.put("actorType", actorType);
        params.put("actorId", actorId);
        params.put("reasonCode", reasonCode);
        params.put("correlationId", correlationId);
        params.put("occurredAt", utc(occurredAt));

        jdbc.sql("""
                INSERT INTO kitchen.ticket_events (
                    id, tenant_id, ticket_id, ticket_item_id, from_status, to_status,
                    trigger, actor_type, actor_id, reason_code, occurred_at, correlation_id)
                VALUES (:id, :tenantId, :ticketId, :itemId, :fromStatus, :toStatus,
                    :trigger, :actorType, :actorId, :reasonCode, :occurredAt, :correlationId)
                """).params(params).update();
    }

    public List<TicketEventRow> eventsOf(UUID tenantId, UUID ticketId) {
        return jdbc.sql("""
                SELECT id, tenant_id, ticket_id, ticket_item_id, from_status, to_status,
                       trigger, actor_type, actor_id, reason_code, occurred_at, correlation_id
                FROM kitchen.ticket_events
                WHERE tenant_id = :tenantId AND ticket_id = :ticketId
                ORDER BY occurred_at, id
                """)
                .param("tenantId", tenantId)
                .param("ticketId", ticketId)
                .query((row, number) -> new TicketEventRow(
                        row.getObject("id", UUID.class),
                        row.getObject("ticket_id", UUID.class),
                        row.getObject("ticket_item_id", UUID.class),
                        row.getString("from_status"),
                        row.getString("to_status"),
                        row.getString("trigger"),
                        row.getString("actor_type"),
                        row.getString("actor_id"),
                        row.getString("reason_code"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    // ---------------------------------------------------------------- projections

    private static final String SELECT_STATION = """
            SELECT id, tenant_id, brand_id, location_id, code, role,
                   display_name_ru, display_name_uz, display_name_en,
                   sort_order, is_fallback, status, version, created_at
            FROM kitchen.stations
            """;

    private static final String SELECT_STATION_CAPACITY = """
            SELECT id, tenant_id, brand_id, location_id, station_id,
                   weekday, window_start, window_end, portions_per_hour,
                   version, created_at
            FROM kitchen.station_capacity
            """;

    private static final String SELECT_TICKET = """
            SELECT id, tenant_id, brand_id, location_id, order_id, sequence_label,
                   fulfilment_mode, channel_code, status, release_mode, release_at,
                   released_at, prep_estimate_seconds, target_ready_at, started_at,
                   ready_at, handed_over_at, routing_version, version, created_at
            FROM kitchen.tickets
            """;

    private static final String SELECT_ITEM = """
            SELECT id, tenant_id, ticket_id, location_id, order_line_id, station_id,
                   quantity, routed_by, status, started_at, ready_at, cancelled_at,
                   version, created_at
            FROM kitchen.ticket_items
            """;

    private static StationRow mapStation(ResultSet row, int number) throws SQLException {
        return new StationRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("code"),
                StationRole.require(row.getString("role")),
                row.getString("display_name_ru"),
                row.getString("display_name_uz"),
                row.getString("display_name_en"),
                row.getInt("sort_order"),
                row.getBoolean("is_fallback"),
                row.getString("status"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static TicketRow mapTicket(ResultSet row, int number) throws SQLException {
        return new TicketRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getString("sequence_label"),
                row.getString("fulfilment_mode"),
                row.getString("channel_code"),
                TicketStatus.valueOf(row.getString("status")),
                ReleaseMode.valueOf(row.getString("release_mode")),
                instant(row, "release_at"),
                instant(row, "released_at"),
                // getInt would answer 0 for a ticket with no estimate, and a zero
                // prep estimate is a ticket that is late the moment it is created.
                row.getObject("prep_estimate_seconds", Integer.class),
                instant(row, "target_ready_at"),
                instant(row, "started_at"),
                instant(row, "ready_at"),
                instant(row, "handed_over_at"),
                row.getInt("routing_version"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static TicketItemRow mapItem(ResultSet row, int number) throws SQLException {
        return new TicketItemRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("ticket_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("order_line_id", UUID.class),
                row.getObject("station_id", UUID.class),
                row.getInt("quantity"),
                RoutingLevel.valueOf(row.getString("routed_by")),
                TicketItemStatus.valueOf(row.getString("status")),
                instant(row, "started_at"),
                instant(row, "ready_at"),
                instant(row, "cancelled_at"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static StationCapacityRow mapStationCapacity(ResultSet row, int number) throws SQLException {
        return new StationCapacityRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("station_id", UUID.class),
                row.getInt("weekday"),
                row.getObject("window_start", LocalTime.class),
                row.getObject("window_end", LocalTime.class),
                row.getInt("portions_per_hour"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static @Nullable OffsetDateTime nullableUtc(@Nullable Instant instant) {
        return instant == null ? null : utc(instant);
    }

    // ------------------------------------------------------------------- records

    public record StationRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String code,
            StationRole role,
            String displayNameRu,
            String displayNameUz,
            String displayNameEn,
            int sortOrder,
            boolean fallback,
            String status,
            int version,
            Instant createdAt) {}

    /** One station's throughput ceiling for one weekday and one local time window (ADR 0041, IA §2.6). */
    public record StationCapacityRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID stationId,
            int weekday,
            LocalTime windowStart,
            LocalTime windowEnd,
            int portionsPerHour,
            int version,
            Instant createdAt) {}

    public record TicketRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            String sequenceLabel,
            String fulfilmentMode,
            String channelCode,
            TicketStatus status,
            ReleaseMode releaseMode,
            @Nullable Instant releaseAt,
            @Nullable Instant releasedAt,
            @Nullable Integer prepEstimateSeconds,
            @Nullable Instant targetReadyAt,
            @Nullable Instant startedAt,
            @Nullable Instant readyAt,
            @Nullable Instant handedOverAt,
            int routingVersion,
            int version,
            Instant createdAt) {}

    public record TicketItemRow(
            UUID id,
            UUID tenantId,
            UUID ticketId,
            UUID locationId,
            UUID orderLineId,
            UUID stationId,
            int quantity,
            RoutingLevel routedBy,
            TicketItemStatus status,
            @Nullable Instant startedAt,
            @Nullable Instant readyAt,
            @Nullable Instant cancelledAt,
            int version,
            Instant createdAt) {}

    public record TicketEventRow(
            UUID id,
            UUID ticketId,
            @Nullable UUID ticketItemId,
            @Nullable String fromStatus,
            String toStatus,
            String trigger,
            String actorType,
            String actorId,
            @Nullable String reasonCode,
            Instant occurredAt) {}

    /** Which station a line routes to, and which of the five levels decided it. */
    public record ResolvedStation(UUID stationId, RoutingLevel level) {}
}
