package uz.horecaos.platform.dinein.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.dinein.application.port.SessionOrderSource;
import uz.horecaos.platform.dinein.application.port.SessionOrderSource.OrderForSession;
import uz.horecaos.platform.dinein.application.port.SessionOrderSource.SessionBill;
import uz.horecaos.platform.dinein.domain.DineInStateMachine;
import uz.horecaos.platform.dinein.domain.ReservationStatus;
import uz.horecaos.platform.dinein.domain.SessionStatus;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.ReservationRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SettingsRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The table session: the thing that accumulates an evening (ADR 0047).
 *
 * <p>A session is not an order and holds no lines. Several people place several
 * orders across an evening and the bill is over the session, so each round is a
 * normal, immutable ADR 0019 order — priced, reserved and fired independently —
 * and this aggregate owns only the occupancy, the running balance, and the single
 * act of paying.
 *
 * <p>The balance is a query, never a column. {@link SessionOrderSource#bill} sums
 * the member orders, and nothing here recomputes a price from rules: those orders
 * were priced, fiscalised and in some cases already exported, and a second answer
 * derived here would be the one that disagrees.
 *
 * <p>That is also what keeps ADR 0046's split tender a feature rather than a
 * redesign. Money is not attached to the session; the session names a set of
 * orders, and a split adds tenders that address the same set rather than unpicking
 * a total baked into one column.
 */
@Service
public class TableSessionService {

    private final JdbcDineInStore store;
    private final FloorPlanService floorPlan;
    private final SessionOrderSource orders;
    private final AuditRecorder audit;
    private final Clock clock;

    public TableSessionService(
            JdbcDineInStore store,
            FloorPlanService floorPlan,
            SessionOrderSource orders,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.floorPlan = floorPlan;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * A request to seat a party at one or more tables, with or without a booking.
     *
     * @param reservationId null for a walk-in, which is most covers. A reservation
     *                      and an occupancy are different facts, and this is the
     *                      column where they meet
     */
    public record OpenSession(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID reservationId,
            List<UUID> tableIds,
            Integer partySize,
            String currency,
            String openedBy) {}

    /**
     * Seats a party.
     *
     * <p>Opening a session is the moment a booking stops being a claim on the
     * future and becomes an occupancy, which is why seating moves the reservation
     * in the same transaction. A walk-in takes the same path with no reservation
     * to move.
     */
    @Transactional
    public SessionRow open(OpenSession request, String reason) {
        if (request.tableIds() == null || request.tableIds().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A session sits at at least one table");
        }

        SettingsRow settings = floorPlan.settings(request.tenantId(), request.brandId(), request.locationId());

        Instant now = clock.instant();
        UUID sessionId = UUID.randomUUID();

        SessionRow session = new SessionRow(
                sessionId,
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                request.reservationId(),
                request.partySize(),
                businessDate(request.tenantId(), request.locationId(), now),
                request.openedBy(),
                now,
                SessionStatus.OPEN,
                settings.serviceChargeRateBp(),
                request.currency(),
                null,
                null,
                null,
                1);

        if (request.reservationId() != null) {
            ReservationRow reservation = store.findReservation(request.tenantId(), request.reservationId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such booking"));
            if (reservation.status() != ReservationStatus.CONFIRMED) {
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "Only a confirmed booking can be seated; this one is " + reservation.status());
            }
            if (!store.moveReservation(
                    request.tenantId(),
                    request.reservationId(),
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.SEATED,
                    reservation.version(),
                    now)) {
                throw new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This booking has just been changed by somebody else");
            }
        }

        try {
            store.insertSession(session, now);
        } catch (DuplicateKeyException alreadySeated) {
            // The partial unique index on (tenant_id, reservation_id). A booking
            // seated twice is two parties charged for one reservation, and the
            // second party is sitting at somebody else's table.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This booking has already been seated");
        }

        // A party seated at several tables reads them in the order they were
        // joined (OrderTablesPort's contract; the chip on the ticket, the order
        // board and the order detail all follow it). session_tables carries one
        // timestamp per table and nothing else that orders them, and every table
        // of one request shares one clock reading -- so the readers' ORDER BY
        // joined_at ties and falls to the code string, which sorts T10 before T2.
        // Each table is therefore stamped a microsecond (the resolution timestamptz
        // keeps) after the one before it, counting back from the request's own
        // reading: the last table keeps that reading (to the microsecond), so no
        // table is ever stamped after a close at the same instant
        // (ck_session_table_window).
        Instant lastJoinedAt = now.truncatedTo(ChronoUnit.MICROS);
        int joinedAfterThisOne = request.tableIds().size();
        for (UUID tableId : request.tableIds()) {
            joinedAfterThisOne--;
            TableRow table = store.findTable(request.tenantId(), tableId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such table"));
            if (!table.locationId().equals(request.locationId())) {
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST, "Table %s is at another branch".formatted(table.code()));
            }
            try {
                store.occupyTable(
                        sessionId,
                        tableId,
                        request.tenantId(),
                        request.locationId(),
                        lastJoinedAt.minus(joinedAfterThisOne, ChronoUnit.MICROS));
            } catch (DataIntegrityViolationException occupied) {
                if (isTableOccupied(occupied)) {
                    throw new ApiException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "Table %s already has a party sitting at it".formatted(table.code()),
                            Map.of("conflict", "TABLE_OCCUPIED"));
                }
                throw occupied;
            }
        }

        audit.record(AuditFact.of("dinein.session.opened", AuditClass.BUSINESS)
                .by(ActorRef.user(request.openedBy(), null))
                .at(ResourceScope.location(request.tenantId(), request.brandId(), request.locationId()))
                .target("dinein.table_session", sessionId)
                .targetVersion(1L)
                .because(reason)
                // Staff 9.3a: a freshly inserted session has no prior state.
                .changed(ChangeDocuments.created(Map.of(
                        "tables", request.tableIds().size(),
                        "walkIn", request.reservationId() == null,
                        "businessDate", session.businessDate().toString())))
                .usingCapability("dinein.session.manage")
                .correlatedBy(sessionId.toString())
                .occurredAt(now)
                .build());

        return session;
    }

    /**
     * Attaches a round to the session.
     *
     * <p>The order is not created here and is not modified here. Checkout made it,
     * ADR 0019 priced it, and this records that it belongs to this table's evening.
     * The unique key on {@code (tenant_id, order_id)} is what stops one meal
     * appearing on two bills.
     *
     * @param requireOwnerAccountId null for an operator's own capability-gated
     *                              write ({@code TableSessionController}), which
     *                              may attach any order a manager can see. Non-null
     *                              for the guest's own device ({@code
     *                              QrEntryController}), which holds a table-scoped
     *                              token and no ADR 0025 capability at all -- the
     *                              token alone proves "this device is at this
     *                              table", never "this order is this device's own".
     *                              Checkout does not bind a cart to a table (ADR
     *                              0047's own "what was not built"), so the only
     *                              fact this method can still check is the one
     *                              checkout always records: which signed-in
     *                              customer placed the order. A mismatch answers
     *                              exactly like a non-existent order -- the same
     *                              {@link ErrorCode#RESOURCE_NOT_FOUND} the lookup
     *                              two lines below throws -- so a guest fishing for
     *                              another table's order id learns nothing from the
     *                              difference, and so an order that really does not
     *                              exist and one that exists but is not theirs read
     *                              identically to every caller that is not its owner.
     */
    @Transactional
    public int addRound(
            UUID tenantId,
            UUID sessionId,
            UUID orderId,
            @Nullable UUID requireOwnerAccountId,
            String actorSubject,
            String reason) {

        SessionRow session = require(tenantId, sessionId);
        if (!session.status().live()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST, "A %s session takes no more rounds".formatted(session.status()));
        }

        OrderForSession order = orders.find(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        if (requireOwnerAccountId != null && !requireOwnerAccountId.equals(order.customerAccountId())) {
            // Same refusal, same message, as the lookup above: a guest holding a
            // valid token for table A and a real order id from table B must not be
            // able to tell "wrong table" apart from "no such order" by the answer
            // it gets back -- that difference is exactly what let table A's guest
            // reach table B's bill.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }

        if (!"DINE_IN".equals(order.fulfillmentMode())) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "A %s order is not eaten at a table, so it is not a round of one"
                            .formatted(order.fulfillmentMode()));
        }
        if (!order.locationId().equals(session.locationId())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "That order was placed at another branch");
        }
        if (session.currency() != null && !session.currency().equals(order.currency())) {
            // Two currencies in one bill has no correct total, and the wrong one
            // would be arrived at silently by whichever sum ran first.
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "This session bills in %s and that round is in %s".formatted(session.currency(), order.currency()));
        }

        Instant now = clock.instant();
        int sequence;
        try {
            sequence = store.addOrder(sessionId, orderId, tenantId, now);
        } catch (DuplicateKeyException already) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "That order is already on a bill",
                    Map.of("conflict", "ORDER_ALREADY_BILLED"));
        }

        audit.record(AuditFact.of("dinein.session.round-added", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, session.brandId(), session.locationId()))
                .target("dinein.table_session", sessionId)
                .targetVersion((long) session.version())
                .because(reason)
                // Staff 9.3a: a freshly added round has no prior state.
                .changed(ChangeDocuments.created(Map.of("orderId", orderId.toString(), "sequence", sequence)))
                .usingCapability("dinein.session.manage")
                .correlatedBy(sessionId.toString())
                .occurredAt(now)
                .build());

        return sequence;
    }

    /** The running bill: what table seven owes, right now. */
    public SessionBill bill(UUID tenantId, UUID sessionId) {
        require(tenantId, sessionId);
        return orders.bill(tenantId, sessionId);
    }

    public SessionRow find(UUID tenantId, UUID sessionId) {
        return require(tenantId, sessionId);
    }

    public List<SessionRow> live(UUID tenantId, UUID locationId) {
        return store.listLiveSessions(tenantId, locationId);
    }

    public List<UUID> rounds(UUID tenantId, UUID sessionId) {
        return store.ordersInSession(tenantId, sessionId);
    }

    /**
     * Moves a session along its lifecycle.
     *
     * <p>Two transitions are special and neither is special in the state machine.
     * Reaching {@link SessionStatus#CLOSED} settles: the bill is summed once and
     * written onto the row, so a report reading a closed evening does not re-add
     * it. Reaching {@link SessionStatus#FORCE_CLOSED} is the walkout, and it needs
     * a reason code and its own capability — an unpaid table that quietly
     * disappears is how a shift's cash shortfall becomes unattributable.
     */
    @Transactional
    public SessionRow move(
            UUID tenantId,
            UUID sessionId,
            SessionStatus to,
            int expectedVersion,
            @Nullable String closeReasonCode,
            String actorSubject,
            String reason) {

        SessionRow session = require(tenantId, sessionId);

        if (!DineInStateMachine.permits(session.status(), to)) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "A %s session cannot become %s. Permitted: %s"
                            .formatted(session.status(), to, DineInStateMachine.nextFor(session.status())),
                    Map.of("currentStatus", session.status().name(), "requestedStatus", to.name()));
        }

        SessionBill bill = orders.bill(tenantId, sessionId);
        Instant now = clock.instant();
        Instant closedAt = to.terminal() ? now : null;
        Long settledTotal = null;

        if (to == SessionStatus.CLOSED) {
            // An ordinary close is either "paid" or "opened in error and owes
            // nothing". Both are legitimate; a close with money still on the table
            // is not, and it has its own transition and its own grant.
            settledTotal = bill.totalMinor();
        } else if (to == SessionStatus.FORCE_CLOSED) {
            if (closeReasonCode == null || closeReasonCode.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A force-close names a reason code");
            }
            settledTotal = 0L;
        }

        if (!store.moveSession(
                tenantId,
                sessionId,
                session.status(),
                to,
                expectedVersion,
                closedAt,
                settledTotal,
                closeReasonCode,
                now)) {
            throw ApiException.staleVersion(expectedVersion, session.version());
        }

        Map<String, Object> after = new HashMap<>();
        after.put("status", to.name());
        after.put("rounds", bill.roundCount());
        after.put("billTotalMinor", bill.totalMinor());
        after.put("currency", bill.currency() == null ? session.currency() : bill.currency());
        if (to == SessionStatus.FORCE_CLOSED) {
            // The unpaid amount, recorded where a shift report can group by it.
            // This is the number a manager is answering for.
            after.put("unsettledMinor", bill.totalMinor());
            after.put("closeReasonCode", closeReasonCode);
        }

        audit.record(AuditFact.of(
                        "dinein.session." + to.name().toLowerCase(Locale.ROOT).replace('_', '-'), AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, session.brandId(), session.locationId()))
                .target("dinein.table_session", sessionId)
                .targetVersion((long) expectedVersion + 1)
                .because(reason)
                // Staff 9.3a: "status" genuinely moves from the session's
                // prior status to the requested one; every other key is this
                // transition's own billing snapshot, with no prior value to
                // diff against.
                .changed(ChangeDocuments.diff(Map.of("status", session.status().name()), after))
                .usingCapability(
                        to == SessionStatus.FORCE_CLOSED ? "dinein.session.force_close" : "dinein.session.manage")
                .correlatedBy(sessionId.toString())
                .occurredAt(now)
                .build());

        // Closing a session ends its guests' access as well as its occupancy. The
        // occupancy is V0034's trigger; the tokens are here, because a table token
        // is a table's and not a session's, and only the sessions minted from it
        // die with the evening.
        if (to.terminal()) {
            for (UUID tableId : store.tablesForSession(tenantId, sessionId)) {
                store.revokeGuestSessionsForTable(tenantId, tableId, "SESSION_CLOSED", now);
            }
        }

        return store.findSession(tenantId, sessionId).orElseThrow();
    }

    private SessionRow require(UUID tenantId, UUID sessionId) {
        return store.findSession(tenantId, sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such session"));
    }

    /**
     * The trading day this evening belongs to, in the branch's own timezone.
     *
     * <p>Not UTC and not the server's zone. A session that opens at 22:00 in
     * Tashkent and closes at 01:00 belongs to one service, and keying it on the
     * calendar day of an instant in Greenwich puts a restaurant's takings on two
     * days with nothing to say which. ADR 0043 owns the platform's business-day
     * boundary; this column is the fact that rule needs to exist before there is
     * history to re-key, so it is snapshotted here rather than derived later.
     */
    private LocalDate businessDate(UUID tenantId, UUID locationId, Instant now) {
        String zone = store.locationTimeZone(tenantId, locationId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "This branch has no timezone, so its trading day cannot be decided"));
        return LocalDate.ofInstant(now, ZoneId.of(zone));
    }

    /** Matched on the index name V0034 gives the one-party-per-table guarantee. */
    static boolean isTableOccupied(DataIntegrityViolationException conflict) {
        Throwable cursor = conflict;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains(JdbcDineInStore.TABLE_OCCUPIED_INDEX)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
