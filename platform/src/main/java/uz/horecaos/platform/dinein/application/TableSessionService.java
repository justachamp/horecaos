package uz.horecaos.platform.dinein.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
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
import uz.horecaos.platform.dinein.domain.RoundStatuses;
import uz.horecaos.platform.dinein.domain.SessionOrigin;
import uz.horecaos.platform.dinein.domain.SessionStatus;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.ReservationRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.RoundRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionTableRow;
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

    /** The {@code close_reason_code} of a claim that lapsed (ADR 0143). */
    public static final String CLAIM_LAPSED = "CLAIM_LAPSED";

    private final JdbcDineInStore store;
    private final FloorPlanService floorPlan;
    private final SessionOrderSource orders;
    private final AuditRecorder audit;
    private final Clock clock;
    private final ClaimMetrics metrics;

    /** Wired by hand (tests, tools): the claim counters go nowhere. */
    public TableSessionService(
            JdbcDineInStore store,
            FloorPlanService floorPlan,
            SessionOrderSource orders,
            AuditRecorder audit,
            Clock clock) {
        this(store, floorPlan, orders, audit, clock, ClaimMetrics.none());
    }

    @Autowired
    public TableSessionService(
            JdbcDineInStore store,
            FloorPlanService floorPlan,
            SessionOrderSource orders,
            AuditRecorder audit,
            Clock clock,
            ClaimMetrics metrics) {
        this.store = store;
        this.floorPlan = floorPlan;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * A request to seat a party at one or more tables, with or without a booking.
     *
     * @param reservationId null for a walk-in, which is most covers. A reservation
     *                      and an occupancy are different facts, and this is the
     *                      column where they meet
     * @param claim         non-null only for a session a guest opened for themselves
     *                      (ADR 0143): who, and when the claim lapses. Null is the
     *                      staff path, which is unchanged
     */
    public record OpenSession(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID reservationId,
            List<UUID> tableIds,
            Integer partySize,
            String currency,
            String openedBy,
            @Nullable Claim claim) {

        /** The staff path: no claim. */
        public OpenSession(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                @Nullable UUID reservationId,
                List<UUID> tableIds,
                Integer partySize,
                String currency,
                String openedBy) {
            this(tenantId, brandId, locationId, reservationId, tableIds, partySize, currency, openedBy, null);
        }
    }

    /**
     * A guest's provisional occupancy (ADR 0143): the claimant and the instant the
     * table goes back to the room if nothing the restaurant accepted is on it.
     */
    public record Claim(UUID accountId, Instant expiresAt) {}

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

        // Every table the party sits at is locked first, in id order (ADR 0143,
        // Decision 5). A host confirming or amending a booking over one of these
        // takes the same lock, so the two serialize instead of both succeeding on
        // stale reads; and the hold and capacity facts read below are read under it.
        List<TableRow> locked = store.lockTables(request.tenantId(), request.tableIds());
        Map<UUID, TableRow> lockedById = new HashMap<>();
        for (TableRow row : locked) {
            lockedById.put(row.id(), row);
        }

        SettingsRow settings = floorPlan.settings(request.tenantId(), request.brandId(), request.locationId());

        Instant now = clock.instant();
        UUID sessionId = UUID.randomUUID();
        Claim claim = request.claim();

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
                1,
                claim == null ? SessionOrigin.STAFF : SessionOrigin.GUEST_QR,
                claim == null ? null : claim.accountId(),
                claim == null ? null : claim.expiresAt(),
                null,
                null);

        if (request.reservationId() != null) {
            // At this branch, not merely in this tenant: seating moves the booking to
            // SEATED, and a booking of another branch is not this branch's to seat.
            ReservationRow reservation = store.findReservationAtLocation(
                            request.tenantId(), request.locationId(), request.reservationId())
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
        } catch (DuplicateKeyException refused) {
            if (isClaimAccountViolation(refused)) {
                // ux_claim_account_branch: one live unconfirmed claim per account per
                // branch, whatever the application read. Answers exactly as a reached
                // cap does -- no reason (ADR 0143, Eligibility).
                throw tableNotAvailable();
            }
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
        int seats = 0;
        boolean bookedOver = false;
        for (UUID tableId : request.tableIds()) {
            joinedAfterThisOne--;
            TableRow table = lockedById.get(tableId);
            if (table == null) {
                table = store.findTable(request.tenantId(), tableId)
                        .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such table"));
            }
            if (!table.locationId().equals(request.locationId())) {
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST, "Table %s is at another branch".formatted(table.code()));
            }
            seats += table.seats();
            if (claim == null && request.reservationId() == null && !bookedOver) {
                // The staff walk-in is the host's judgement and stays so; what changes is
                // that the audit fact now says when that judgement went over a hold
                // (ADR 0143, Decision 8). A seated booking is excluded: its own hold
                // is what put the party here, and the exclusion constraint keeps every
                // other hold clear of it.
                bookedOver = store.tableHeldByConfirmedBooking(
                        request.tenantId(),
                        tableId,
                        now,
                        now.plus(java.time.Duration.ofMinutes(settings.walkIn().horizonMinutes())));
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
        boolean overCapacity = request.partySize() != null && request.partySize() > seats;

        Map<String, Object> opened = new HashMap<>();
        opened.put("tables", request.tableIds().size());
        opened.put("walkIn", request.reservationId() == null);
        opened.put("origin", session.origin().name());
        opened.put("businessDate", session.businessDate().toString());
        if (claim == null) {
            opened.put("bookedOver", bookedOver);
            opened.put("overCapacity", overCapacity);
        } else {
            opened.put("claimExpiresAt", claim.expiresAt().toString());
        }

        AuditFact.Builder fact = AuditFact.of("dinein.session.opened", AuditClass.BUSINESS)
                .by(ActorRef.user(request.openedBy(), null))
                .at(ResourceScope.location(request.tenantId(), request.brandId(), request.locationId()))
                .target("dinein.table_session", sessionId)
                .targetVersion(1L)
                .because(reason)
                // Staff 9.3a: a freshly inserted session has no prior state.
                .changed(ChangeDocuments.created(opened))
                .correlatedBy(sessionId.toString())
                .occurredAt(now);
        if (claim == null) {
            // A guest holds no ADR 0025 capability; the table's token and the
            // customer's session are what authorised the claim.
            fact.usingCapability("dinein.session.manage");
        }
        audit.record(fact.build());

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
     *                              A cart bound to a table reaches this method
     *                              from checkout itself ({@code
     *                              TableBindingPortAdapter}); an unbound one from
     *                              the guest's own call afterwards. Either way the
     *                              fact this method can check is the one checkout
     *                              always records: which signed-in customer placed
     *                              the order. A mismatch answers
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

        // Locked, not merely read: a claim's sweeper and an attach to it can disagree
        // about the claim's fate on this very row, and serialized on it they have one
        // outcome (JdbcDineInStore#lockSession).
        SessionRow session = store.lockSession(tenantId, sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such session"));

        // A retry of a write that already landed is not a second attach. The New
        // Order screen places the order and then calls this; a dropped response
        // leaves the operator not knowing whether the round is on the bill, and
        // the only safe answer to "attach it again" is the sequence it already
        // has. Read before the insert rather than recovered from the duplicate-key
        // refusal below, because that refusal aborts the transaction and nothing
        // after it may query. The ownership check still runs first: a guest's
        // token names a table, never an order, and this branch must not confirm
        // that an order id sits on a bill to somebody who does not own the order.
        Optional<RoundRow> existing = store.findRoundOfOrder(tenantId, orderId);
        if (existing.isPresent() && existing.get().sessionId().equals(sessionId)) {
            if (requireOwnerAccountId != null) {
                OrderForSession own = orders.find(tenantId, orderId)
                        .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
                if (!requireOwnerAccountId.equals(own.customerAccountId())) {
                    throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
                }
            }
            return existing.get().sequence();
        }

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
        if (existing.isPresent()) {
            // On another session's bill: one meal never appears on two.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "That order is already on a bill",
                    Map.of("conflict", "ORDER_ALREADY_BILLED"));
        }
        int sequence;
        try {
            sequence = store.addOrder(sessionId, orderId, tenantId, now);
        } catch (DuplicateKeyException already) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "That order is already on a bill",
                    Map.of("conflict", "ORDER_ALREADY_BILLED"));
        }
        // The bill changed, so the session's version does: a close the operator confirmed
        // against the bill as they read it must answer STALE_VERSION now that a round has
        // joined it (the console's "Close table" relies on exactly that). A retry that
        // landed earlier returned above and moves nothing.
        int versionAfterRound = store.bumpVersionForRound(tenantId, sessionId, now);

        audit.record(AuditFact.of("dinein.session.round-added", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, session.brandId(), session.locationId()))
                .target("dinein.table_session", sessionId)
                .targetVersion((long) versionAfterRound)
                .because(reason)
                // Staff 9.3a: a freshly added round has no prior state.
                .changed(ChangeDocuments.created(Map.of("orderId", orderId.toString(), "sequence", sequence)))
                .usingCapability("dinein.session.manage")
                .correlatedBy(sessionId.toString())
                .occurredAt(now)
                .build());

        if (session.unconfirmedClaim() && RoundStatuses.accepted(order.status())) {
            // A round the restaurant has already accepted -- a cash order at an
            // auto-accepting branch, say -- makes the claim an ordinary session at
            // once (ADR 0143, Decision 4). Attaching alone confirms nothing:
            // BILLABLE counts PAYMENT_AUTHORIZING orders, and a claim a guest could
            // turn into a held table by starting a payment and abandoning it is the
            // denial the design exists to bound. A round still in flight is left to
            // the sweeper, which decides when the claim's own window ends.
            confirmClaimOfRound(session, orderId, versionAfterRound, now);
        }

        return sequence;
    }

    /**
     * The sweeper's confirmation: a round the restaurant accepted is on this claim.
     *
     * @return whether this call confirmed it; false when somebody (a host, the guest's
     *         own attach, a close) decided first
     */
    @Transactional
    public boolean confirmClaimByRound(SessionRow claim, UUID orderId) {
        Instant now = clock.instant();
        boolean confirmed = store.confirmClaim(claim.tenantId(), claim.id(), claim.version(), "round:" + orderId, now);
        if (confirmed) {
            auditClaimConfirmed(
                    claim,
                    "round:" + orderId,
                    "A round the restaurant accepted is on the claim",
                    (long) claim.version() + 1,
                    now);
        }
        return confirmed;
    }

    /**
     * Confirms a claim because an accepted round is on it. No version predicate: the
     * conditional on "still live and unconfirmed" is the whole question, and the attach
     * that got us here has itself moved the version past the one {@code session} was read at
     * ({@code versionAfterRound}), which is why that predicate would refuse our own write.
     */
    private void confirmClaimOfRound(SessionRow session, UUID orderId, int versionAfterRound, Instant now) {
        if (store.confirmClaim(session.tenantId(), session.id(), null, "round:" + orderId, now)) {
            auditClaimConfirmed(
                    session,
                    "round:" + orderId,
                    "A round the restaurant accepted is on the claim",
                    (long) versionAfterRound + 1,
                    now);
        }
    }

    /** The running bill: what table seven owes, right now. */
    public SessionBill bill(UUID tenantId, UUID sessionId) {
        require(tenantId, sessionId);
        return orders.bill(tenantId, sessionId);
    }

    public SessionRow find(UUID tenantId, UUID sessionId) {
        return require(tenantId, sessionId);
    }

    /**
     * One session, only if it sits at this branch.
     *
     * <p>The operations endpoints carry a {@code locationId} in the path and a
     * {@code LOCATION}-scoped capability check against it, so a manager of branch A
     * holds the grant for A and nothing else. A session id is a UUID in the same
     * tenant as branch B's; matching it on the tenant alone would let A's grant read
     * B's bill, attach A's caller's choice of order to B's table, or close B's
     * party. It answers exactly like a session that does not exist, so the id
     * cannot be probed across branches.
     */
    public SessionRow findAtLocation(UUID tenantId, UUID locationId, UUID sessionId) {
        return store.findSession(tenantId, sessionId)
                .filter(session -> session.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such session"));
    }

    public List<SessionRow> live(UUID tenantId, UUID locationId) {
        return store.listLiveSessions(tenantId, locationId);
    }

    public List<UUID> rounds(UUID tenantId, UUID sessionId) {
        return store.ordersInSession(tenantId, sessionId);
    }

    /** One physical table a session sits at: its stable code and display name. */
    public record SessionTable(UUID tableId, String code, String displayName) {}

    /**
     * The tables behind each of a batch of sessions, in join order -- one round
     * trip for the whole live list. A session with no table row (there is none:
     * {@link #open} refuses an empty list) is simply absent from the map.
     */
    public Map<UUID, List<SessionTable>> tablesOf(UUID tenantId, Collection<UUID> sessionIds) {
        Map<UUID, List<SessionTable>> bySession = new LinkedHashMap<>();
        for (SessionTableRow row : store.tablesForSessions(tenantId, sessionIds)) {
            bySession
                    .computeIfAbsent(row.sessionId(), ignored -> new ArrayList<>())
                    .add(new SessionTable(row.tableId(), row.code(), row.displayName()));
        }
        return bySession;
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
     *
     * <p>This is the staff path. A staff move of a guest's unconfirmed claim past
     * {@code OPEN} confirms it (ADR 0143, Decision 4): someone in the room has taken
     * charge of the table, so the claim must not lapse under them.
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
        return doMove(
                tenantId,
                sessionId,
                to,
                expectedVersion,
                closeReasonCode,
                ActorRef.user(actorSubject, null),
                true,
                reason);
    }

    /**
     * A guest's own move, from the table's token alone ({@code QrEntryController}).
     *
     * <p>Separate from {@link #move} so that "somebody in the room has taken charge"
     * can never be read off a guest's tap: a guest moving a claim past {@code OPEN}
     * confirms nothing. {@code BILL_REQUESTED} is refused for an unconfirmed claim
     * outright, because there is nothing the restaurant has accepted to bill
     * (ADR 0143, Decision 4).
     */
    @Transactional
    public SessionRow moveByGuest(
            UUID tenantId, UUID sessionId, SessionStatus to, int expectedVersion, UUID tableId, String reason) {
        SessionRow session = require(tenantId, sessionId);
        if (session.unconfirmedClaim() && to == SessionStatus.BILL_REQUESTED) {
            throw claimUnconfirmed();
        }
        return doMove(
                tenantId, sessionId, to, expectedVersion, null, ActorRef.user("guest:" + tableId, null), false, reason);
    }

    private SessionRow doMove(
            UUID tenantId,
            UUID sessionId,
            SessionStatus to,
            int expectedVersion,
            @Nullable String closeReasonCode,
            ActorRef actor,
            boolean staff,
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

        // A member of staff moving a claim to the bill or to settling has taken charge
        // of the table. Moving it back to OPEN, or closing it, does not confirm: the
        // first is the lapse's own intermediate hop and the second releases the table.
        boolean takesCharge = staff
                && session.unconfirmedClaim()
                && (to == SessionStatus.BILL_REQUESTED || to == SessionStatus.SETTLING);
        String confirmedBy = takesCharge ? actor.subject() : null;

        if (!store.moveSession(
                tenantId,
                sessionId,
                session.status(),
                to,
                expectedVersion,
                closedAt,
                settledTotal,
                closeReasonCode,
                confirmedBy,
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
        if (to == SessionStatus.CLOSED && closeReasonCode != null) {
            after.put("closeReasonCode", closeReasonCode);
        }

        AuditFact.Builder fact = AuditFact.of(
                        "dinein.session." + to.name().toLowerCase(Locale.ROOT).replace('_', '-'), AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, session.brandId(), session.locationId()))
                .target("dinein.table_session", sessionId)
                .targetVersion((long) expectedVersion + 1)
                .because(reason)
                // Staff 9.3a: "status" genuinely moves from the session's
                // prior status to the requested one; every other key is this
                // transition's own billing snapshot, with no prior value to
                // diff against.
                .changed(ChangeDocuments.diff(Map.of("status", session.status().name()), after))
                .correlatedBy(sessionId.toString())
                .occurredAt(now);
        if (actor.type() == ActorRef.Type.USER) {
            fact.usingCapability(
                    to == SessionStatus.FORCE_CLOSED ? "dinein.session.force_close" : "dinein.session.manage");
        }
        audit.record(fact.build());

        if (takesCharge) {
            auditClaimConfirmed(session, actor.subject(), reason, (long) session.version() + 1, now);
        }

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

    /**
     * Confirms a guest's claim on a member of staff's say-so, keeping the table for a
     * guest who has not ordered yet (ADR 0143, Staff surface).
     *
     * @throws ApiException {@code STALE_VERSION} when the session moved since it was
     *                      read; {@code RESOURCE_CONFLICT {conflict: NOT_AN_UNCONFIRMED_CLAIM}}
     *                      when it is a staff session, is already confirmed, or is over
     */
    @Transactional
    public SessionRow confirmClaim(
            UUID tenantId, UUID locationId, UUID sessionId, int expectedVersion, String actorSubject, String reason) {

        SessionRow session = findAtLocation(tenantId, locationId, sessionId);
        if (!session.unconfirmedClaim() || !session.status().live()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This session is not a guest's unconfirmed claim",
                    Map.of("conflict", "NOT_AN_UNCONFIRMED_CLAIM"));
        }
        Instant now = clock.instant();
        if (!store.confirmClaim(tenantId, sessionId, expectedVersion, actorSubject, now)) {
            throw ApiException.staleVersion(expectedVersion, session.version());
        }
        auditClaimConfirmed(session, actorSubject, reason, (long) session.version() + 1, now);
        return store.findSession(tenantId, sessionId).orElseThrow();
    }

    /**
     * Gives an unconfirmed claim's table back to the room (ADR 0143, Decision 4).
     *
     * <p>From {@code OPEN} or {@code SETTLING} that is {@code move} to {@code CLOSED}
     * with {@code CLAIM_LAPSED}. From {@code BILL_REQUESTED} it is {@code move} to
     * {@code OPEN} (the machine's existing return-to-service edge, so ADR 0047's
     * machine is untouched) and then to {@code CLOSED}, in this one transaction, both
     * hops under the system actor. The price is one intermediate
     * {@code dinein.session.open} fact, which reads as a reopening and carries the
     * same reason. The audit fact for the table release and the revocation of the
     * table's guest tokens then happen exactly as for a staff close.
     *
     * <p>A lapse racing an attach, a bill request or a staff move loses cleanly:
     * every hop is a conditional update on the version it read, and a loss is a
     * {@link ApiException} {@code STALE_VERSION} the caller treats as "somebody else
     * decided; look again next sweep".
     */
    @Transactional
    public SessionRow lapseClaim(UUID tenantId, UUID sessionId, ActorRef systemActor) {
        SessionRow session = require(tenantId, sessionId);
        if (!session.unconfirmedClaim() || !session.status().live()) {
            return session;
        }
        String reason = "claim lapsed";
        int version = session.version();
        if (session.status() == SessionStatus.BILL_REQUESTED) {
            SessionRow reopened =
                    doMove(tenantId, sessionId, SessionStatus.OPEN, version, null, systemActor, false, reason);
            version = reopened.version();
        }
        SessionRow closed =
                doMove(tenantId, sessionId, SessionStatus.CLOSED, version, CLAIM_LAPSED, systemActor, false, reason);

        metrics.lapsed();
        audit.record(AuditFact.of("dinein.session.claim-lapsed", AuditClass.BUSINESS)
                .by(systemActor)
                .at(ResourceScope.location(tenantId, session.brandId(), session.locationId()))
                .target("dinein.table_session", sessionId)
                .targetVersion((long) closed.version())
                .because(reason)
                .changed(ChangeDocuments.diff(
                        Map.of(
                                "claim",
                                "UNCONFIRMED",
                                "status",
                                session.status().name()),
                        Map.of("claim", "LAPSED", "status", closed.status().name())))
                .correlatedBy(sessionId.toString())
                .occurredAt(clock.instant())
                .build());
        return closed;
    }

    private void auditClaimConfirmed(
            SessionRow session, String confirmedBy, String reason, long targetVersion, Instant now) {
        metrics.confirmed();
        AuditFact.Builder fact = AuditFact.of("dinein.session.claim-confirmed", AuditClass.BUSINESS)
                .by(
                        confirmedBy.startsWith("round:")
                                ? ActorRef.systemJob("dinein.claim-confirmation")
                                : ActorRef.user(confirmedBy, null))
                .at(ResourceScope.location(session.tenantId(), session.brandId(), session.locationId()))
                .target("dinein.table_session", session.id())
                .targetVersion(targetVersion)
                .because(reason)
                .changed(ChangeDocuments.diff(
                        Map.of("claim", "UNCONFIRMED"), Map.of("claim", "CONFIRMED", "confirmedBy", confirmedBy)))
                .correlatedBy(session.id().toString())
                .occurredAt(now);
        if (!confirmedBy.startsWith("round:")) {
            fact.usingCapability("dinein.session.manage");
        }
        audit.record(fact.build());
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
        return mentions(conflict, JdbcDineInStore.TABLE_OCCUPIED_INDEX);
    }

    /** Matched on the index name V0467 gives the one-live-claim-per-account-per-branch guarantee. */
    static boolean isClaimAccountViolation(DataIntegrityViolationException conflict) {
        return mentions(conflict, JdbcDineInStore.CLAIM_ACCOUNT_INDEX);
    }

    private static boolean mentions(DataIntegrityViolationException conflict, String constraint) {
        Throwable cursor = conflict;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * The one answer for every reason a guest cannot seat themselves: the feature is
     * off, the table is held, a cap is reached, the account is blacklisted, the table
     * is not in service. No reason, because each reason is a fact about the room or
     * about another guest that the caller has no business learning (ADR 0143).
     */
    static ApiException tableNotAvailable() {
        return new ApiException(ErrorCode.RESOURCE_CONFLICT, NOT_AVAILABLE_MESSAGE, NOT_AVAILABLE);
    }

    static final String NOT_AVAILABLE_MESSAGE =
            "This table cannot be taken from here. Ask a member of staff to seat you.";

    static final Map<String, Object> NOT_AVAILABLE = Map.of("conflict", "TABLE_NOT_AVAILABLE");

    public static ApiException claimUnconfirmed() {
        return new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "There is nothing to bill yet. Place an order first, or ask a member of staff.",
                Map.of("conflict", "CLAIM_UNCONFIRMED"));
    }
}
