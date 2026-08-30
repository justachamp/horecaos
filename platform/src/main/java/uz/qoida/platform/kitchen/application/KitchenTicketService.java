package uz.qoida.platform.kitchen.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.fulfillment.api.OrderProgressPort;
import uz.qoida.platform.fulfillment.api.OrderProgressPort.OrderProgress;
import uz.qoida.platform.kitchen.application.port.KitchenOrderSource;
import uz.qoida.platform.kitchen.application.port.KitchenOrderSource.OrderForKitchen;
import uz.qoida.platform.kitchen.application.port.KitchenOrderSource.OrderLineForKitchen;
import uz.qoida.platform.kitchen.domain.KitchenStateMachine;
import uz.qoida.platform.kitchen.domain.ReleaseMode;
import uz.qoida.platform.kitchen.domain.RoutingLevel;
import uz.qoida.platform.kitchen.domain.TicketItemStatus;
import uz.qoida.platform.kitchen.domain.TicketStatus;
import uz.qoida.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.qoida.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.ResolvedStation;
import uz.qoida.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.qoida.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * The kitchen aggregate (ADR 0041): routing a confirmed order onto stations,
 * moving items, rolling them up, and proposing what that means for the order.
 *
 * <p>One rule governs every mutation, and it is the same rule
 * {@code OrderStateService} follows for orders: a status change is a conditional
 * UPDATE naming the status it expects, and the row count decides who won. Two
 * devices marking one item ready, three stations finishing in the same second,
 * and an offline client replaying a queue of advances all reduce to that
 * question. The loser is never an error — a cook cannot interpret one, and a
 * screen that errors on a second tap gets tapped a third time.
 *
 * <p>Nothing here writes an order. Every order consequence goes through
 * {@link OrderProgressPort}, which proposes through ADR 0019's command path and
 * may report that no implementation is wired at all.
 */
@Service
public class KitchenTicketService {

    private static final Logger log = LoggerFactory.getLogger(KitchenTicketService.class);

    /**
     * The generation of the resolution algorithm, pinned onto every ticket.
     *
     * <p>Bumped when the five levels or their precedence change, so a ticket
     * created under the old rules is never re-explained by the new ones. It is not
     * a count of rule edits: those are the rules' own versions, and a menu change
     * must not invalidate every ticket on the pass.
     */
    static final int ROUTING_VERSION = 1;

    private final JdbcKitchenStore kitchen;
    private final KitchenOrderSource orders;
    private final OrderProgressPort orderProgress;
    private final AuditRecorder audit;
    private final Clock clock;

    public KitchenTicketService(JdbcKitchenStore kitchen, KitchenOrderSource orders,
            OrderProgressPort orderProgress, AuditRecorder audit, Clock clock) {
        this.kitchen = kitchen;
        this.orders = orders;
        this.orderProgress = orderProgress;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------ ticket creation

    /**
     * Builds the ticket for a confirmed order and fires it if its release is due.
     *
     * <p>Idempotent by the database rather than by a check-then-insert: one ticket
     * per order is a unique constraint, so an {@code OrderConfirmed} delivered
     * twice returns the existing ticket rather than routing the order onto the
     * stations a second time.
     */
    @Transactional
    public TicketRow open(UUID tenantId, UUID orderId, ReleaseMode requestedMode) {
        Optional<TicketRow> existing = kitchen.findTicketByOrder(tenantId, orderId);
        if (existing.isPresent()) {
            return existing.get();
        }

        OrderForKitchen order = orders.find(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        // A ticket for an order that never reached CONFIRMED is food cooked for a
        // commitment nobody made. The kitchen refuses rather than trusting its
        // caller, because the caller is an event consumer and events are replayed.
        if (!"CONFIRMED".equals(order.status()) && !"PREPARING".equals(order.status())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A production ticket is built from a confirmed order, and this one is "
                            + order.status());
        }

        UUID fallbackStation = kitchen.findFallbackStation(tenantId, order.locationId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "This branch has no fallback station, so a line that matches no rule "
                                + "would have nowhere to go. Configure the branch's stations "
                                + "before it runs a kitchen screen (ADR 0041)."));

        Instant now = clock.instant();
        Integer prepSeconds = prepEstimateSeconds(order);
        Instant targetReadyAt = targetReadyAt(order);
        Release release = decideRelease(requestedMode, targetReadyAt, prepSeconds, now);

        UUID ticketId = UUID.randomUUID();
        TicketRow ticket = new TicketRow(ticketId, tenantId, order.brandId(), order.locationId(),
                orderId, order.publicOrderNumber(), order.fulfillmentMode(), order.channelCode(),
                TicketStatus.HELD, release.mode(), release.releaseAt(), null, prepSeconds,
                targetReadyAt, null, null, null, ROUTING_VERSION, 1, now);
        kitchen.insertTicket(ticket);

        List<String> unresolved = routeLines(order, ticketId, fallbackStation, now);

        kitchen.recordEvent(tenantId, ticketId, null, null, TicketStatus.HELD.name(),
                "ORDER_CONFIRMED", "SERVICE", "kitchen", null, orderId.toString(), now);

        for (String line : unresolved) {
            // KitchenRoutingUnresolved. Recorded per line rather than per ticket,
            // because the fix is per line: somebody has to map that dish, and a
            // ticket-level count does not say which one.
            kitchen.recordEvent(tenantId, ticketId, null, null, TicketStatus.HELD.name(),
                    "ROUTING_UNRESOLVED", "SERVICE", "kitchen", "KITCHEN_ROUTING_UNRESOLVED",
                    line, now);
        }
        if (!unresolved.isEmpty()) {
            log.warn("{} line(s) on ticket {} matched no routing rule and went to the fallback "
                    + "station at location {}", unresolved.size(), ticketId, order.locationId());
        }

        if (release.fireNow()) {
            return fire(tenantId, ticketId, "ORDER_CONFIRMED", "SERVICE", "kitchen", null,
                    orderId.toString(), now).orElse(ticket);
        }
        return ticket;
    }

    /**
     * When the food is due at the pass.
     *
     * <p>ADR 0041 calls this ADR 0014's {@code estimated_ready_at}, which does not
     * exist. The nearest fact that does is V0023's stored promise, and it is a
     * better one: it is what the customer was actually told, decided once and
     * never recomputed. The travel component comes off it because a delivery order
     * promised for 20:00 with twenty minutes on the road is due at the pass at
     * 19:40 — the promise is when the customer eats, not when the kitchen
     * finishes.
     *
     * <p>Null travel on a delivery order means travel was not modelled at all, and
     * V0023 says so explicitly. Nothing is subtracted in that case: guessing a
     * road time here would produce a target the branch is measured against and
     * nobody chose.
     */
    private static Instant targetReadyAt(OrderForKitchen order) {
        if (order.promisedAt() == null) {
            return null;
        }
        Integer travel = order.promiseTravelMinutes();
        return travel == null ? order.promisedAt()
                : order.promisedAt().minus(Duration.ofMinutes(travel));
    }

    /**
     * How long this order takes to cook.
     *
     * <p>Taken from the promise's own preparation component rather than resolved
     * again from ADR 0036's bands. Re-resolving would key on a different instant
     * and could disagree with the number the customer was quoted, and a kitchen
     * working to a different estimate than the promise is how a branch is late
     * against a target it never saw.
     */
    private static Integer prepEstimateSeconds(OrderForKitchen order) {
        Integer minutes = order.promisePrepMinutes();
        return minutes == null || minutes <= 0 ? null : minutes * 60;
    }

    /**
     * Which release mode applies, and when.
     *
     * <p>An explicit {@code MANUAL_HOLD} always wins: somebody asked for the
     * ticket to wait. Otherwise the ticket is scheduled when there is a target and
     * an estimate and the resulting instant is still in the future, and fired
     * immediately in every other case. Firing is the safe default — a ticket held
     * by accident is food nobody cooks, while a ticket fired early is food cooked
     * early, and only one of those has a customer waiting at the end of it.
     */
    private static Release decideRelease(ReleaseMode requested, Instant targetReadyAt,
            Integer prepSeconds, Instant now) {

        if (requested == ReleaseMode.MANUAL_HOLD) {
            return new Release(ReleaseMode.MANUAL_HOLD, null, false);
        }
        if (targetReadyAt == null || prepSeconds == null) {
            return new Release(ReleaseMode.AUTO_ON_CONFIRM, null, true);
        }
        Instant releaseAt = targetReadyAt.minusSeconds(prepSeconds);
        if (!releaseAt.isAfter(now)) {
            return new Release(ReleaseMode.AUTO_ON_CONFIRM, null, true);
        }
        return new Release(ReleaseMode.SCHEDULED, releaseAt, false);
    }

    /** Resolves every line onto a station, and returns the ones nothing matched. */
    private List<String> routeLines(OrderForKitchen order, UUID ticketId, UUID fallbackStation,
            Instant now) {

        List<String> unresolved = new ArrayList<>();
        for (OrderLineForKitchen line : order.lines()) {
            Optional<ResolvedStation> resolved = kitchen.resolveStation(order.tenantId(),
                    order.brandId(), order.locationId(), line.variantId(), line.productId());

            UUID stationId = resolved.map(ResolvedStation::stationId).orElse(fallbackStation);
            RoutingLevel level = resolved.map(ResolvedStation::level).orElse(RoutingLevel.FALLBACK);
            if (level.unresolved()) {
                unresolved.add(line.orderLineId().toString());
            }

            kitchen.insertItem(new TicketItemRow(UUID.randomUUID(), order.tenantId(), ticketId,
                    order.locationId(), line.orderLineId(), stationId, line.quantity(), level,
                    TicketItemStatus.QUEUED, null, null, null, 1, now));
        }
        return unresolved;
    }

    // ------------------------------------------------------------------- release

    /**
     * Fires a held ticket now.
     *
     * @return the ticket as it stands afterwards, or empty when it was no longer
     *         {@code HELD} — which a second press of "release now" produces, and
     *         which is not an error
     */
    @Transactional
    public Optional<TicketRow> fire(UUID tenantId, UUID ticketId, String trigger, String actorType,
            String actorId, String reasonCode, String correlationId, Instant now) {

        Optional<Integer> won = kitchen.transitionTicket(tenantId, ticketId, TicketStatus.HELD,
                TicketStatus.FIRED, now);
        if (won.isEmpty()) {
            return Optional.empty();
        }
        kitchen.recordEvent(tenantId, ticketId, null, TicketStatus.HELD.name(),
                TicketStatus.FIRED.name(), trigger, actorType, actorId, reasonCode, correlationId,
                now);
        return kitchen.findTicket(tenantId, ticketId);
    }

    /** A person at the branch pressing "release now" on a buffered ticket. */
    @Transactional
    public TicketRow releaseNow(UUID tenantId, UUID ticketId, int expectedVersion,
            String reasonCode, String actorId, String correlationId) {

        Instant now = clock.instant();
        TicketRow ticket = require(tenantId, ticketId);
        requireVersion(ticket, expectedVersion);

        if (ticket.status() != TicketStatus.HELD) {
            // Already fired, by the scheduler or by whoever pressed it first. The
            // caller wanted the ticket on a screen and it is on a screen.
            return ticket;
        }
        return fire(tenantId, ticketId, "RELEASE_COMMAND", "USER", actorId, reasonCode,
                correlationId, now).orElse(ticket);
    }

    /**
     * Changes when a held ticket will fire, or holds it indefinitely.
     *
     * <p>ADR 0041's one bounded rule about fire time lives here: pushing release
     * <em>later</em> than {@code target_ready_at - prep_estimate} means the ticket
     * cannot be ready when it was promised, so it requires
     * {@code kitchen.ticket.release.override}, a reason, and an ADR 0027 audit
     * fact. Pulling it earlier needs none of that — cooking sooner than necessary
     * breaks no promise.
     *
     * <p>The capability itself is checked at the endpoint. What this method
     * enforces is that the two always travel together: an override without a
     * reason is refused here rather than being recorded as an unexplained decision
     * somebody has to reconstruct later.
     */
    @Transactional
    public TicketRow reschedule(UUID tenantId, UUID ticketId, int expectedVersion,
            ReleaseMode mode, Instant releaseAt, boolean overrideGranted, String reasonCode,
            String actorId, String correlationId) {

        Instant now = clock.instant();
        TicketRow ticket = require(tenantId, ticketId);
        requireVersion(ticket, expectedVersion);

        if (ticket.status() != TicketStatus.HELD) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This ticket has already been released; a fire time for food that is already "
                            + "cooking describes nothing");
        }
        if (mode.requiresInstant() == (releaseAt == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "SCHEDULED needs a fire time and the other modes must not carry one");
        }

        boolean pastThePromise = releaseAt != null && latestHonestRelease(ticket) != null
                && releaseAt.isAfter(latestHonestRelease(ticket));
        // MANUAL_HOLD on a ticket with a promise is the same act as pushing the
        // fire time past it — it pushes it to never — and is bounded identically.
        boolean heldPastThePromise = mode == ReleaseMode.MANUAL_HOLD
                && latestHonestRelease(ticket) != null;

        if (pastThePromise || heldPastThePromise) {
            if (!overrideGranted) {
                throw ApiException.insufficientCapability("kitchen.ticket.release.override",
                        "LOCATION");
            }
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Firing later than the promise permits requires a reason (ADR 0041)");
            }
        }

        Optional<Integer> won = kitchen.rescheduleRelease(tenantId, ticketId, mode, releaseAt, now);
        if (won.isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The ticket was released while this change was being made");
        }

        kitchen.recordEvent(tenantId, ticketId, null, TicketStatus.HELD.name(),
                TicketStatus.HELD.name(), "RELEASE_COMMAND", "USER", actorId, reasonCode,
                correlationId, now);

        if (pastThePromise || heldPastThePromise) {
            // ADR 0027: the decision that matters here is not "the fire time
            // changed" but "somebody chose to be late", and it is audited in the
            // same transaction as the change it describes.
            recordAudit(ticket, "kitchen.ticket.release-override", actorId, reasonCode,
                    Map.of("releaseMode", mode.name(),
                            "releaseAt", String.valueOf(releaseAt),
                            "latestHonestRelease", String.valueOf(latestHonestRelease(ticket))),
                    AuditFact.Outcome.SUCCEEDED, correlationId, now);
            log.warn("Ticket {} was re-timed past the promise by {}", ticketId, actorId);
        }
        return require(tenantId, ticketId);
    }

    /**
     * The last instant a ticket can be fired and still make its target, or null
     * when nothing was promised and there is therefore nothing to be late for.
     */
    private static Instant latestHonestRelease(TicketRow ticket) {
        if (ticket.targetReadyAt() == null || ticket.prepEstimateSeconds() == null) {
            return null;
        }
        return ticket.targetReadyAt().minusSeconds(ticket.prepEstimateSeconds());
    }

    /**
     * Fires everything the scheduler has claimed.
     *
     * <p>Each ticket goes through the same conditional update a human release
     * goes through, so a cook pressing "release now" in the same instant as the
     * scheduler still settles at one outcome rather than two.
     */
    @Transactional
    public int releaseDue(int batchSize) {
        Instant now = clock.instant();
        int fired = 0;
        for (TicketRow ticket : kitchen.claimDueForRelease(now, batchSize)) {
            if (fire(ticket.tenantId(), ticket.id(), "RELEASE_SCHEDULED", "SYSTEM_JOB",
                    "kitchen-release", null, ticket.orderId().toString(), now).isPresent()) {
                fired++;
            }
        }
        return fired;
    }

    // ------------------------------------------------------------ station actions

    /** A cook starting one line at one station. */
    @Transactional
    public ItemOutcome start(UUID tenantId, UUID itemId, String actorId, String correlationId) {
        return advanceItem(tenantId, itemId, TicketItemStatus.STARTED, false, actorId, null,
                correlationId);
    }

    /**
     * A cook marking one line ready.
     *
     * <p>A line still queued is started first rather than refused. The board ships
     * a "ticket ready" button that marks every line at once, and half of them are
     * usually untouched; refusing there would make the button useless and the real
     * outcome would be that nobody records readiness at all. The two transitions
     * are both written, so the timeline still says the line was started.
     */
    @Transactional
    public ItemOutcome ready(UUID tenantId, UUID itemId, String actorId, String correlationId) {
        TicketItemRow item = kitchen.findItem(tenantId, itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such item"));
        if (item.status() == TicketItemStatus.QUEUED) {
            advanceItem(tenantId, itemId, TicketItemStatus.STARTED, false, actorId, null,
                    correlationId);
        }
        return advanceItem(tenantId, itemId, TicketItemStatus.READY, false, actorId, null,
                correlationId);
    }

    /**
     * Undoing a readiness the pass may already have acted on.
     *
     * <p>Refused once the ticket has been handed over, which is the concrete
     * failure ADR 0041 names: a courier dispatched against a {@code READY} order,
     * arriving to find the dish back on the grill, with the order still reading
     * {@code READY} to the customer. The attempt is recorded rather than
     * discarded — somebody tried to recall food that had already left, and that is
     * exactly the fact an operational exception is about.
     */
    @Transactional
    public ItemOutcome recall(UUID tenantId, UUID itemId, String reasonCode, String actorId,
            String correlationId) {

        if (reasonCode == null || reasonCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A recall requires a reason");
        }
        TicketItemRow item = kitchen.findItem(tenantId, itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such item"));
        TicketRow ticket = require(tenantId, item.ticketId());

        if (ticket.status() == TicketStatus.HANDED_OVER) {
            Instant now = clock.instant();
            kitchen.recordEvent(tenantId, ticket.id(), itemId, ticket.status().name(),
                    ticket.status().name(), "STATION_ACTION", "USER", actorId,
                    "KITCHEN_RECALL_AFTER_READY", correlationId, now);
            recordAudit(ticket, "kitchen.ticket.recall", actorId, reasonCode,
                    Map.of("ticketItemId", itemId.toString(), "refused", "AFTER_HANDOVER"),
                    AuditFact.Outcome.REJECTED, correlationId, now);
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This ticket was handed over. The food has left the pass, so recalling it "
                            + "here would leave the order reading READY to a customer who is "
                            + "holding it. Raise an ADR 0039 order amendment instead.",
                    Map.of("exceptionCode", "KitchenRecallAfterReady"));
        }
        return advanceItem(tenantId, itemId, TicketItemStatus.STARTED, true, actorId, reasonCode,
                correlationId);
    }

    /**
     * Moves one item and applies whatever the move implies for its ticket.
     *
     * <p>The roll-up is recomputed from the item set rather than kept as a counter
     * on the ticket, so three stations finishing in the same second all compute
     * {@code READY} and the conditional update lets exactly one of them apply it.
     * That is what makes exactly one order-level {@code READY} proposal happen.
     */
    private ItemOutcome advanceItem(UUID tenantId, UUID itemId, TicketItemStatus target,
            boolean recalling, String actorId, String reasonCode, String correlationId) {

        Instant now = clock.instant();
        TicketItemRow item = kitchen.findItem(tenantId, itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such item"));

        // Start on a line that is already ready is not a recall. The item machine
        // has a READY -> STARTED edge and only the recall command may use it: a
        // cook pressing start on a finished line, or an offline client replaying a
        // start it queued before the ready it also queued, has not asked to undo
        // anything, and treating it as a recall would silently un-ready food on the
        // pass. Recall is a separate button behind a separate capability.
        if (!recalling && item.status() == TicketItemStatus.READY
                && target == TicketItemStatus.STARTED) {
            return new ItemOutcome(false, item, require(tenantId, item.ticketId()));
        }

        if (item.status() == target) {
            // A replayed advance from an offline client, or the second of two
            // devices. Settled, and the caller is told the settled state.
            return new ItemOutcome(false, item, require(tenantId, item.ticketId()));
        }
        if (!KitchenStateMachine.permits(item.status(), target)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This line is %s and cannot become %s".formatted(item.status(), target));
        }

        TicketRow ticket = require(tenantId, item.ticketId());
        if (ticket.status() == TicketStatus.HELD) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This ticket is still in the buffer. Release it before cooking from it, or "
                            + "the food is ready before the branch meant to start it.");
        }

        Optional<Integer> won = kitchen.transitionItem(tenantId, itemId, item.status(), target, now);
        if (won.isEmpty()) {
            // Somebody else moved it between the read and the write. Their outcome
            // stands and the caller sees it.
            TicketItemRow settled = kitchen.findItem(tenantId, itemId).orElseThrow();
            return new ItemOutcome(false, settled, require(tenantId, item.ticketId()));
        }

        kitchen.recordEvent(tenantId, ticket.id(), itemId, item.status().name(), target.name(),
                "STATION_ACTION", "USER", actorId, reasonCode, correlationId, now);

        TicketRow after = rollUp(tenantId, ticket, actorId, correlationId, now);
        return new ItemOutcome(true, kitchen.findItem(tenantId, itemId).orElseThrow(), after);
    }

    /** Applies the ticket status the items now imply, and proposes what it means. */
    private TicketRow rollUp(UUID tenantId, TicketRow ticket, String actorId, String correlationId,
            Instant now) {

        List<TicketItemStatus> statuses = kitchen.itemsOf(tenantId, ticket.id()).stream()
                .map(TicketItemRow::status)
                .toList();
        TicketStatus implied = KitchenStateMachine.rollUp(ticket.status(), statuses);

        if (implied == ticket.status()) {
            return ticket;
        }
        if (!KitchenStateMachine.permits(ticket.status(), implied)) {
            // Unreachable through the item machine, and asserted rather than
            // assumed: the ticket constraints in V0030 would refuse a ticket that
            // was ready without ever having started, and a violation there is far
            // harder to read than this line.
            log.error("Roll-up implied {} for ticket {} which is {}; refusing the transition",
                    implied, ticket.id(), ticket.status());
            return ticket;
        }
        Optional<Integer> won = kitchen.transitionTicket(tenantId, ticket.id(), ticket.status(),
                implied, now);
        if (won.isEmpty()) {
            // Another station's roll-up got there first, which is the normal
            // outcome when three finish together. Exactly one proposal is made.
            return require(tenantId, ticket.id());
        }
        kitchen.recordEvent(tenantId, ticket.id(), null, ticket.status().name(), implied.name(),
                "ITEM_ROLLUP", "USER", actorId, null, correlationId, now);

        propose(ticket, ticket.status(), implied, actorId, correlationId, now);
        return require(tenantId, ticket.id());
    }

    /**
     * Tells ordering what the kitchen just did, through ADR 0019's command path.
     *
     * <p>The idempotency key is the ticket and the transition, never the request:
     * an offline client replaying twelve queued advances must produce one
     * {@code PREPARING} and one {@code READY}, not twelve of each.
     *
     * <p>A refusal never rolls the ticket back. The food is where the food is, and
     * a kitchen that had to undo a readiness because an operator had already moved
     * the order by hand would be a kitchen that lies about its own pass.
     *
     * <p>What the order said back is written to {@code kitchen.ticket_events}
     * rather than only logged. A refusal means the board and the customer's order
     * now disagree, and the person who has to reconcile them is at the branch,
     * reading the ticket — not reading a server log they have no access to.
     */
    private void propose(TicketRow ticket, TicketStatus from, TicketStatus implied,
            String actorId, String correlationId, Instant now) {

        // A recall moves the ticket backwards, and ADR 0041 is explicit that a
        // recall never moves the order backwards: ADR 0019 forbids it and would
        // refuse anyway, but proposing it at all would fill the order timeline
        // with refusals that describe nothing except that the kitchen asked.
        if (from == TicketStatus.READY && implied == TicketStatus.IN_PRODUCTION) {
            log.info("Ticket {} was recalled; the order stays READY (ADR 0041)", ticket.id());
            return;
        }

        OrderProgress progress = switch (implied) {
            case IN_PRODUCTION -> OrderProgress.PREPARING;
            case READY -> OrderProgress.READY;
            default -> null;
        };
        if (progress == null) {
            return;
        }

        var outcome = orderProgress.propose(ticket.tenantId(), ticket.orderId(), progress,
                "kitchen-ticket:%s:%s".formatted(ticket.id(), progress),
                "KITCHEN_" + progress.name(), "USER", actorId,
                correlationId == null ? ticket.orderId().toString() : correlationId);

        kitchen.recordEvent(ticket.tenantId(), ticket.id(), null, implied.name(), implied.name(),
                "ORDER_PROPOSAL", "SERVICE", "kitchen",
                "ORDER_PROGRESS_" + outcome.name(), correlationId, now);

        if (outcome == OrderProgressPort.ProposalOutcome.REFUSED) {
            log.warn("Order {} refused the kitchen's {} proposal from ticket {}",
                    ticket.orderId(), progress, ticket.id());
        }
    }

    // -------------------------------------------------------------------- reading

    public List<TicketRow> board(UUID tenantId, UUID locationId, List<String> statuses, int limit) {
        return kitchen.board(tenantId, locationId,
                statuses.isEmpty() ? List.of("FIRED", "IN_PRODUCTION", "READY") : statuses, limit);
    }

    public List<TicketItemRow> items(UUID tenantId, UUID ticketId) {
        return kitchen.itemsOf(tenantId, ticketId);
    }

    /** Whether order proposals reach ordering at all, surfaced on every board. */
    public boolean orderProgressWired() {
        return orderProgress.isWired();
    }

    public TicketRow require(UUID tenantId, UUID ticketId) {
        return kitchen.findTicket(tenantId, ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such ticket"));
    }

    private static void requireVersion(TicketRow ticket, int expectedVersion) {
        if (ticket.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, ticket.version());
        }
    }

    private void recordAudit(TicketRow ticket, String actionCode, String actorId,
            String reasonCode, Map<String, Object> changed, AuditFact.Outcome outcome,
            String correlationId, Instant now) {

        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.user(actorId, null))
                .at(ResourceScope.location(ticket.tenantId(), ticket.brandId(),
                        ticket.locationId()))
                .target("kitchen.ticket", ticket.id())
                .targetVersion((long) ticket.version())
                .outcome(outcome)
                .because(reasonCode)
                .changed(changed)
                .correlatedBy(correlationId == null ? ticket.id().toString() : correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * @param applied whether this caller's command is the one that moved the item.
     *                False is a settled outcome, not a failure
     */
    public record ItemOutcome(boolean applied, TicketItemRow item, TicketRow ticket) { }

    private record Release(ReleaseMode mode, Instant releaseAt, boolean fireNow) { }
}
