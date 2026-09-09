package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fulfillment.api.OrderProgressPort;
import uz.horecaos.platform.fulfillment.api.OrderProgressPort.OrderProgress;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.kitchen.application.port.KitchenOrderSource;
import uz.horecaos.platform.kitchen.application.port.KitchenOrderSource.OrderForKitchen;
import uz.horecaos.platform.kitchen.application.port.KitchenOrderSource.OrderLineForKitchen;
import uz.horecaos.platform.kitchen.domain.KitchenStateMachine;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.domain.RoutingLevel;
import uz.horecaos.platform.kitchen.domain.StationCapacityShift;
import uz.horecaos.platform.kitchen.domain.StationCapacityShift.WindowOccurrence;
import uz.horecaos.platform.kitchen.domain.TicketItemStatus;
import uz.horecaos.platform.kitchen.domain.TicketStatus;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.ResolvedStation;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationCapacityRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

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
    private final TransactionTemplate independently;

    public KitchenTicketService(
            JdbcKitchenStore kitchen,
            KitchenOrderSource orders,
            OrderProgressPort orderProgress,
            AuditRecorder audit,
            Clock clock,
            TransactionTemplate unitOfWork) {
        this.kitchen = kitchen;
        this.orders = orders;
        this.orderProgress = orderProgress;
        this.audit = audit;
        this.clock = clock;
        // For the one pair of writes that has to outlive the exception they
        // accompany -- see recall's refusal of a handed-over ticket.
        this.independently = new TransactionTemplate(Objects.requireNonNull(
                unitOfWork.getTransactionManager(), "unitOfWork must already carry a transaction manager"));
        this.independently.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "A production ticket is built from a confirmed order, and this one is " + order.status());
        }

        UUID fallbackStation = kitchen.findFallbackStation(tenantId, order.locationId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "This branch has no fallback station, so a line that matches no rule "
                                + "would have nowhere to go. Configure the branch's stations "
                                + "before it runs a kitchen screen (ADR 0041)."));

        Instant now = clock.instant();
        Integer prepSeconds = prepEstimateSeconds(order);
        Instant targetReadyAt = targetReadyAt(order);

        // Resolved before the ticket is inserted, and not yet written: the
        // capacity shift below needs to know which stations and how many
        // portions this ticket asks of each one, and that has to be known before
        // release_at is decided, which has to be known before the ticket row —
        // carrying release_at — can be inserted at all.
        List<RoutedLine> routedLines = resolveLines(order, fallbackStation);
        long capacityOffsetSeconds =
                capacityOffsetSeconds(order, routedLines, requestedMode, targetReadyAt, prepSeconds);
        Release release = decideRelease(requestedMode, targetReadyAt, prepSeconds, capacityOffsetSeconds, now);

        UUID ticketId = UUID.randomUUID();
        TicketRow ticket = new TicketRow(
                ticketId,
                tenantId,
                order.brandId(),
                order.locationId(),
                orderId,
                order.publicOrderNumber(),
                order.fulfillmentMode(),
                order.channelCode(),
                TicketStatus.HELD,
                release.mode(),
                release.releaseAt(),
                null,
                prepSeconds,
                targetReadyAt,
                null,
                null,
                null,
                ROUTING_VERSION,
                1,
                now);
        kitchen.insertTicket(ticket);

        List<String> unresolved = insertRoutedItems(order.tenantId(), order.locationId(), ticketId, routedLines, now);

        kitchen.recordEvent(
                tenantId,
                ticketId,
                null,
                null,
                TicketStatus.HELD.name(),
                "ORDER_CONFIRMED",
                "SERVICE",
                "kitchen",
                null,
                orderId.toString(),
                now);

        for (String line : unresolved) {
            // KitchenRoutingUnresolved. Recorded per line rather than per ticket,
            // because the fix is per line: somebody has to map that dish, and a
            // ticket-level count does not say which one.
            kitchen.recordEvent(
                    tenantId,
                    ticketId,
                    null,
                    null,
                    TicketStatus.HELD.name(),
                    "ROUTING_UNRESOLVED",
                    "SERVICE",
                    "kitchen",
                    "KITCHEN_ROUTING_UNRESOLVED",
                    line,
                    now);
        }
        if (!unresolved.isEmpty()) {
            log.warn(
                    "{} line(s) on ticket {} matched no routing rule and went to the fallback "
                            + "station at location {}",
                    unresolved.size(),
                    ticketId,
                    order.locationId());
        }

        if (release.ceilingExceeded()) {
            // ADR 0041: a ceiling shifts release_at and never holds a ticket past
            // the promise to protect its own number. This is that boundary hit —
            // the offset the ceiling wanted could not fit before "now" caught up
            // with it, so the ticket fires without the buffer rather than waiting
            // for an instant that has already gone. Recorded on the ticket the
            // branch reads, not only logged: the food is going out at risk of the
            // exact queue delay the ceiling exists to warn about, and the person
            // who has to plan around that is at the branch.
            kitchen.recordEvent(
                    tenantId,
                    ticketId,
                    null,
                    TicketStatus.HELD.name(),
                    TicketStatus.HELD.name(),
                    "CAPACITY_CEILING_REACHED",
                    "SERVICE",
                    "kitchen",
                    "KITCHEN_CAPACITY_CEILING_REACHED",
                    orderId.toString(),
                    now);
            log.warn(
                    "Ticket {} at location {} reached a station's throughput ceiling; it is "
                            + "releasing without the full queue buffer rather than being held past "
                            + "its promise (ADR 0041)",
                    ticketId,
                    order.locationId());
        }

        if (release.fireNow()) {
            return fire(tenantId, ticketId, "ORDER_CONFIRMED", "SERVICE", "kitchen", null, orderId.toString(), now)
                    .orElse(ticket);
        }
        return ticket;
    }

    /**
     * How much earlier this ticket must release for its stations' throughput
     * ceilings, or zero when none apply.
     *
     * <p>Skipped entirely — no timezone lookup, no capacity query — whenever
     * there is nothing for a ceiling to shift: an explicit hold ignores release
     * timing altogether, and a ticket with no promise or no estimate has no
     * {@code target_ready_at - prep_estimate} baseline to shift in the first
     * place.
     *
     * <p>A ticket routes to more than one station whenever its lines do, and
     * each station's ceiling is independent. The ticket fires as one unit — ADR
     * 0041 has no per-item release — so the binding constraint is whichever
     * station asks for the most lead time; the rest get more buffer than they
     * strictly needed, which costs nothing.
     */
    private long capacityOffsetSeconds(
            OrderForKitchen order,
            List<RoutedLine> routedLines,
            ReleaseMode requestedMode,
            @Nullable Instant targetReadyAt,
            @Nullable Integer prepSeconds) {

        if (requestedMode == ReleaseMode.MANUAL_HOLD || targetReadyAt == null || prepSeconds == null) {
            return 0;
        }

        Map<UUID, Long> portionsByStation = new HashMap<>();
        for (RoutedLine line : routedLines) {
            portionsByStation.merge(line.stationId(), (long) line.quantity(), Long::sum);
        }
        if (portionsByStation.isEmpty()) {
            return 0;
        }

        // ADR 0041 / ScheduleCadence: local wall-clock through the branch's own
        // IANA zone, never UTC. target_ready_at anchors which service period the
        // ticket belongs to — a plov due at 13:00 is lunch-rush demand whether
        // the order that produced it was confirmed at 09:00 or at 12:55.
        Optional<String> timezone = kitchen.locationTimezone(order.tenantId(), order.locationId());
        if (timezone.isEmpty()) {
            return 0;
        }
        ZoneId zone = ZoneId.of(timezone.get());
        ZonedDateTime targetLocal = targetReadyAt.atZone(zone);
        int weekday = targetLocal.getDayOfWeek().getValue();
        LocalTime localTime = targetLocal.toLocalTime();

        long maxOffsetSeconds = 0;
        for (Map.Entry<UUID, Long> entry : portionsByStation.entrySet()) {
            UUID stationId = entry.getKey();
            long thisTicketPortions = entry.getValue();

            Optional<StationCapacityRow> ceiling =
                    kitchen.capacityWindowCovering(order.tenantId(), stationId, weekday, localTime);
            if (ceiling.isEmpty()) {
                // No ceiling configured for this station at this hour: the same
                // "unbounded" answer V0144 gives a station with no capacity rows
                // at all.
                continue;
            }
            StationCapacityRow window = ceiling.get();
            WindowOccurrence occurrence =
                    StationCapacityShift.occurrence(targetReadyAt, zone, window.windowStart(), window.windowEnd());
            long committed =
                    kitchen.committedPortions(order.tenantId(), stationId, occurrence.start(), occurrence.end());
            long offsetSeconds = StationCapacityShift.offsetSeconds(
                    window.portionsPerHour(), occurrence.duration(), committed, thisTicketPortions);
            maxOffsetSeconds = Math.max(maxOffsetSeconds, offsetSeconds);
        }
        return maxOffsetSeconds;
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
    private static @Nullable Instant targetReadyAt(OrderForKitchen order) {
        if (order.promisedAt() == null) {
            return null;
        }
        Integer travel = order.promiseTravelMinutes();
        return travel == null ? order.promisedAt() : order.promisedAt().minus(Duration.ofMinutes(travel));
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
    private static @Nullable Integer prepEstimateSeconds(OrderForKitchen order) {
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
     *
     * <p>{@code capacityOffsetSeconds} only ever pulls the schedulable instant
     * earlier than the plain {@code target_ready_at - prep_estimate} baseline —
     * see {@link StationCapacityShift}'s class doc for why that direction is the
     * whole of ADR 0041's rule here. When even the baseline has already passed
     * by the time the offset is applied, {@code ceilingExceeded} says so: the
     * ticket still fires now rather than waiting for an instant that no longer
     * exists, but the caller records why.
     */
    private static Release decideRelease(
            ReleaseMode requested,
            @Nullable Instant targetReadyAt,
            @Nullable Integer prepSeconds,
            long capacityOffsetSeconds,
            Instant now) {

        if (requested == ReleaseMode.MANUAL_HOLD) {
            return new Release(ReleaseMode.MANUAL_HOLD, null, false, false);
        }
        if (targetReadyAt == null || prepSeconds == null) {
            return new Release(ReleaseMode.AUTO_ON_CONFIRM, null, true, false);
        }
        Instant honestReleaseAt = targetReadyAt.minusSeconds(prepSeconds);
        Instant releaseAt = honestReleaseAt.minusSeconds(capacityOffsetSeconds);
        if (!releaseAt.isAfter(now)) {
            boolean ceilingExceeded = capacityOffsetSeconds > 0 && honestReleaseAt.isAfter(now);
            return new Release(ReleaseMode.AUTO_ON_CONFIRM, null, true, ceilingExceeded);
        }
        return new Release(ReleaseMode.SCHEDULED, releaseAt, false, false);
    }

    /** Resolves every line onto a station, without writing anything yet. */
    private List<RoutedLine> resolveLines(OrderForKitchen order, UUID fallbackStation) {
        List<RoutedLine> resolved = new ArrayList<>();
        for (OrderLineForKitchen line : order.lines()) {
            Optional<ResolvedStation> match = kitchen.resolveStation(
                    order.tenantId(), order.brandId(), order.locationId(), line.variantId(), line.productId());

            UUID stationId = match.map(ResolvedStation::stationId).orElse(fallbackStation);
            RoutingLevel level = match.map(ResolvedStation::level).orElse(RoutingLevel.FALLBACK);
            resolved.add(new RoutedLine(line.orderLineId(), stationId, level, line.quantity()));
        }
        return resolved;
    }

    /** Writes every resolved line as a ticket item, and returns the ones nothing matched. */
    private List<String> insertRoutedItems(
            UUID tenantId, UUID locationId, UUID ticketId, List<RoutedLine> lines, Instant now) {

        List<String> unresolved = new ArrayList<>();
        for (RoutedLine line : lines) {
            if (line.level().unresolved()) {
                unresolved.add(line.orderLineId().toString());
            }
            kitchen.insertItem(new TicketItemRow(
                    UUID.randomUUID(),
                    tenantId,
                    ticketId,
                    locationId,
                    line.orderLineId(),
                    line.stationId(),
                    line.quantity(),
                    line.level(),
                    TicketItemStatus.QUEUED,
                    null,
                    null,
                    null,
                    1,
                    now));
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
    public Optional<TicketRow> fire(
            UUID tenantId,
            UUID ticketId,
            String trigger,
            String actorType,
            String actorId,
            @Nullable String reasonCode,
            @Nullable String correlationId,
            Instant now) {

        Optional<Integer> won =
                kitchen.transitionTicket(tenantId, ticketId, TicketStatus.HELD, TicketStatus.FIRED, now);
        if (won.isEmpty()) {
            return Optional.empty();
        }
        kitchen.recordEvent(
                tenantId,
                ticketId,
                null,
                TicketStatus.HELD.name(),
                TicketStatus.FIRED.name(),
                trigger,
                actorType,
                actorId,
                reasonCode,
                correlationId,
                now);
        return kitchen.findTicket(tenantId, ticketId);
    }

    /**
     * Custody transfer: the ticket leaves the pass, given to the customer or a
     * courier (IA 2.3, the Раздача screen).
     *
     * <p>The provider handover-code verification the operations spec asks for
     * (ADR 0040) is not built — this is the roll-up's own gate and nothing
     * else, the same honest reduction {@code recall} already documents for the
     * opposite edge. A second press is not an error: the caller wanted the
     * ticket off the pass, and it is off the pass.
     *
     * @return the ticket as it stands afterwards, or empty when it was no
     *         longer {@code READY}
     */
    @Transactional
    public Optional<TicketRow> handOver(UUID tenantId, UUID ticketId, String actorId, @Nullable String correlationId) {

        Instant now = clock.instant();
        Optional<Integer> won =
                kitchen.transitionTicket(tenantId, ticketId, TicketStatus.READY, TicketStatus.HANDED_OVER, now);
        if (won.isEmpty()) {
            return Optional.empty();
        }
        kitchen.recordEvent(
                tenantId,
                ticketId,
                null,
                TicketStatus.READY.name(),
                TicketStatus.HANDED_OVER.name(),
                "STATION_ACTION",
                "USER",
                actorId,
                null,
                correlationId,
                now);
        return kitchen.findTicket(tenantId, ticketId);
    }

    /** A person at the branch pressing "release now" on a buffered ticket. */
    @Transactional
    public TicketRow releaseNow(
            UUID tenantId,
            UUID ticketId,
            int expectedVersion,
            String reasonCode,
            String actorId,
            @Nullable String correlationId) {

        Instant now = clock.instant();
        TicketRow ticket = require(tenantId, ticketId);
        requireVersion(ticket, expectedVersion);

        if (ticket.status() != TicketStatus.HELD) {
            // Already fired, by the scheduler or by whoever pressed it first. The
            // caller wanted the ticket on a screen and it is on a screen.
            return ticket;
        }
        return fire(tenantId, ticketId, "RELEASE_COMMAND", "USER", actorId, reasonCode, correlationId, now)
                .orElse(ticket);
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
    public TicketRow reschedule(
            UUID tenantId,
            UUID ticketId,
            int expectedVersion,
            ReleaseMode mode,
            Instant releaseAt,
            boolean overrideGranted,
            @Nullable String reasonCode,
            String actorId,
            @Nullable String correlationId) {

        Instant now = clock.instant();
        TicketRow ticket = require(tenantId, ticketId);
        requireVersion(ticket, expectedVersion);

        if (ticket.status() != TicketStatus.HELD) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This ticket has already been released; a fire time for food that is already "
                            + "cooking describes nothing");
        }
        if (mode.requiresInstant() == (releaseAt == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "SCHEDULED needs a fire time and the other modes must not carry one");
        }

        boolean pastThePromise = releaseAt != null
                && latestHonestRelease(ticket) != null
                && releaseAt.isAfter(latestHonestRelease(ticket));
        // MANUAL_HOLD on a ticket with a promise is the same act as pushing the
        // fire time past it — it pushes it to never — and is bounded identically.
        boolean heldPastThePromise = mode == ReleaseMode.MANUAL_HOLD && latestHonestRelease(ticket) != null;

        // Captured once validation passes, rather than re-read off the parameter
        // below: NullAway cannot see that a second, separate "pastThePromise ||
        // heldPastThePromise" check implies the null-and-blank check above already
        // ran, but it can see that this local is non-null exactly when it was.
        String overrideReason = null;
        if (pastThePromise || heldPastThePromise) {
            if (!overrideGranted) {
                throw ApiException.insufficientCapability("kitchen.ticket.release.override", "LOCATION");
            }
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Firing later than the promise permits requires a reason (ADR 0041)");
            }
            overrideReason = reasonCode;
        }

        Optional<Integer> won = kitchen.rescheduleRelease(tenantId, ticketId, mode, releaseAt, now);
        if (won.isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "The ticket was released while this change was being made");
        }

        kitchen.recordEvent(
                tenantId,
                ticketId,
                null,
                TicketStatus.HELD.name(),
                TicketStatus.HELD.name(),
                "RELEASE_COMMAND",
                "USER",
                actorId,
                reasonCode,
                correlationId,
                now);

        if (overrideReason != null) {
            // ADR 0027: the decision that matters here is not "the fire time
            // changed" but "somebody chose to be late", and it is audited in the
            // same transaction as the change it describes.
            recordAudit(
                    ticket,
                    "kitchen.ticket.release-override",
                    actorId,
                    overrideReason,
                    Map.of(
                            "releaseMode",
                            mode.name(),
                            "releaseAt",
                            String.valueOf(releaseAt),
                            "latestHonestRelease",
                            String.valueOf(latestHonestRelease(ticket))),
                    AuditFact.Outcome.SUCCEEDED,
                    correlationId,
                    now);
            log.warn("Ticket {} was re-timed past the promise by {}", ticketId, actorId);
        }
        return require(tenantId, ticketId);
    }

    /**
     * The last instant a ticket can be fired and still make its target, or null
     * when nothing was promised and there is therefore nothing to be late for.
     */
    private static @Nullable Instant latestHonestRelease(TicketRow ticket) {
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
            if (fire(
                            ticket.tenantId(),
                            ticket.id(),
                            "RELEASE_SCHEDULED",
                            "SYSTEM_JOB",
                            "kitchen-release",
                            null,
                            ticket.orderId().toString(),
                            now)
                    .isPresent()) {
                fired++;
            }
        }
        return fired;
    }

    // ------------------------------------------------------------ station actions

    /** A cook starting one line at one station. */
    @Transactional
    public ItemOutcome start(UUID tenantId, UUID itemId, String actorId, @Nullable String correlationId) {
        return advanceItem(tenantId, itemId, TicketItemStatus.STARTED, false, actorId, null, correlationId);
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
    public ItemOutcome ready(UUID tenantId, UUID itemId, String actorId, @Nullable String correlationId) {
        TicketItemRow item = kitchen.findItem(tenantId, itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such item"));
        if (item.status() == TicketItemStatus.QUEUED) {
            advanceItem(tenantId, itemId, TicketItemStatus.STARTED, false, actorId, null, correlationId);
        }
        return advanceItem(tenantId, itemId, TicketItemStatus.READY, false, actorId, null, correlationId);
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
    public ItemOutcome recall(
            UUID tenantId, UUID itemId, String reasonCode, String actorId, @Nullable String correlationId) {

        if (reasonCode == null || reasonCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A recall requires a reason");
        }
        TicketItemRow item = kitchen.findItem(tenantId, itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such item"));
        TicketRow ticket = require(tenantId, item.ticketId());

        if (ticket.status() == TicketStatus.HANDED_OVER) {
            Instant now = clock.instant();
            // In its own transaction. ADR 0041 wants this attempt kept -- "somebody
            // tried to recall food that had already left, and that is exactly the
            // fact an operational exception is about" -- but the refusal below is
            // thrown from inside this @Transactional method, so recording it here
            // and throwing there rolled the record back and kept nothing at all.
            independently.executeWithoutResult(ignored -> {
                kitchen.recordEvent(
                        tenantId,
                        ticket.id(),
                        itemId,
                        ticket.status().name(),
                        ticket.status().name(),
                        "STATION_ACTION",
                        "USER",
                        actorId,
                        "KITCHEN_RECALL_AFTER_READY",
                        correlationId,
                        now);
                recordAudit(
                        ticket,
                        "kitchen.ticket.recall",
                        actorId,
                        reasonCode,
                        Map.of("ticketItemId", itemId.toString(), "refused", "AFTER_HANDOVER"),
                        AuditFact.Outcome.REJECTED,
                        correlationId,
                        now);
            });
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This ticket was handed over. The food has left the pass, so recalling it "
                            + "here would leave the order reading READY to a customer who is "
                            + "holding it. Raise an ADR 0039 order amendment instead.",
                    Map.of("exceptionCode", "KitchenRecallAfterReady"));
        }
        return advanceItem(tenantId, itemId, TicketItemStatus.STARTED, true, actorId, reasonCode, correlationId);
    }

    /**
     * Moves one item and applies whatever the move implies for its ticket.
     *
     * <p>The roll-up is recomputed from the item set rather than kept as a counter
     * on the ticket, so three stations finishing in the same second all compute
     * {@code READY} and the conditional update lets exactly one of them apply it.
     * That is what makes exactly one order-level {@code READY} proposal happen.
     */
    private ItemOutcome advanceItem(
            UUID tenantId,
            UUID itemId,
            TicketItemStatus target,
            boolean recalling,
            String actorId,
            @Nullable String reasonCode,
            @Nullable String correlationId) {

        Instant now = clock.instant();
        TicketItemRow item = kitchen.findItem(tenantId, itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such item"));

        // Start on a line that is already ready is not a recall. The item machine
        // has a READY -> STARTED edge and only the recall command may use it: a
        // cook pressing start on a finished line, or an offline client replaying a
        // start it queued before the ready it also queued, has not asked to undo
        // anything, and treating it as a recall would silently un-ready food on the
        // pass. Recall is a separate button behind a separate capability.
        if (!recalling && item.status() == TicketItemStatus.READY && target == TicketItemStatus.STARTED) {
            return new ItemOutcome(false, item, require(tenantId, item.ticketId()));
        }

        if (item.status() == target) {
            // A replayed advance from an offline client, or the second of two
            // devices. Settled, and the caller is told the settled state.
            return new ItemOutcome(false, item, require(tenantId, item.ticketId()));
        }
        if (!KitchenStateMachine.permits(item.status(), target)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This line is %s and cannot become %s".formatted(item.status(), target));
        }

        TicketRow ticket = require(tenantId, item.ticketId());
        if (ticket.status() == TicketStatus.HELD) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
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

        kitchen.recordEvent(
                tenantId,
                ticket.id(),
                itemId,
                item.status().name(),
                target.name(),
                "STATION_ACTION",
                "USER",
                actorId,
                reasonCode,
                correlationId,
                now);

        TicketRow after = rollUp(tenantId, ticket, actorId, correlationId, now);
        return new ItemOutcome(true, kitchen.findItem(tenantId, itemId).orElseThrow(), after);
    }

    /** Applies the ticket status the items now imply, and proposes what it means. */
    private TicketRow rollUp(
            UUID tenantId, TicketRow ticket, String actorId, @Nullable String correlationId, Instant now) {

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
            log.error(
                    "Roll-up implied {} for ticket {} which is {}; refusing the transition",
                    implied,
                    ticket.id(),
                    ticket.status());
            return ticket;
        }
        Optional<Integer> won = kitchen.transitionTicket(tenantId, ticket.id(), ticket.status(), implied, now);
        if (won.isEmpty()) {
            // Another station's roll-up got there first, which is the normal
            // outcome when three finish together. Exactly one proposal is made.
            return require(tenantId, ticket.id());
        }
        kitchen.recordEvent(
                tenantId,
                ticket.id(),
                null,
                ticket.status().name(),
                implied.name(),
                "ITEM_ROLLUP",
                "USER",
                actorId,
                null,
                correlationId,
                now);

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
    private void propose(
            TicketRow ticket,
            TicketStatus from,
            TicketStatus implied,
            String actorId,
            @Nullable String correlationId,
            Instant now) {

        // A recall moves the ticket backwards, and ADR 0041 is explicit that a
        // recall never moves the order backwards: ADR 0019 forbids it and would
        // refuse anyway, but proposing it at all would fill the order timeline
        // with refusals that describe nothing except that the kitchen asked.
        if (from == TicketStatus.READY && implied == TicketStatus.IN_PRODUCTION) {
            log.info("Ticket {} was recalled; the order stays READY (ADR 0041)", ticket.id());
            return;
        }

        OrderProgress progress =
                switch (implied) {
                    case IN_PRODUCTION -> OrderProgress.PREPARING;
                    case READY -> OrderProgress.READY;
                    default -> null;
                };
        if (progress == null) {
            return;
        }

        var outcome = orderProgress.propose(
                ticket.tenantId(),
                ticket.orderId(),
                progress,
                "kitchen-ticket:%s:%s".formatted(ticket.id(), progress),
                "KITCHEN_" + progress.name(),
                "USER",
                actorId,
                correlationId == null ? ticket.orderId().toString() : correlationId);

        kitchen.recordEvent(
                ticket.tenantId(),
                ticket.id(),
                null,
                implied.name(),
                implied.name(),
                "ORDER_PROPOSAL",
                "SERVICE",
                "kitchen",
                "ORDER_PROGRESS_" + outcome.name(),
                correlationId,
                now);

        if (outcome == OrderProgressPort.ProposalOutcome.REFUSED) {
            log.warn(
                    "Order {} refused the kitchen's {} proposal from ticket {}",
                    ticket.orderId(),
                    progress,
                    ticket.id());
        }
    }

    // -------------------------------------------------------------------- reading

    public List<TicketRow> board(UUID tenantId, UUID locationId, List<String> statuses, int limit) {
        return kitchen.board(
                tenantId,
                locationId,
                statuses.isEmpty() ? List.of("FIRED", "IN_PRODUCTION", "READY") : statuses,
                limit);
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

    /**
     * The ticket one line belongs to, so a caller holding a line's id can check
     * which branch it is at <em>before</em> asking for a transition on it.
     *
     * <p>The station actions are keyed by item id, not ticket id, so unlike
     * {@code release} or {@code hand-over} the controller cannot resolve the
     * branch from the URL alone. Without this it could only look afterwards, at
     * the ticket the transition returned — and every one of those transitions
     * commits in its own {@code @Transactional} method while the controller
     * around it is not transactional, so "look afterwards" meant the write had
     * already landed on another branch's ticket and the caller merely received
     * a 404 for it.
     */
    public TicketRow ticketOfItem(UUID tenantId, UUID itemId) {
        TicketItemRow item = kitchen.findItem(tenantId, itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such item"));
        return require(tenantId, item.ticketId());
    }

    private static void requireVersion(TicketRow ticket, int expectedVersion) {
        if (ticket.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, ticket.version());
        }
    }

    private void recordAudit(
            TicketRow ticket,
            String actionCode,
            String actorId,
            String reasonCode,
            Map<String, Object> changed,
            AuditFact.Outcome outcome,
            @Nullable String correlationId,
            Instant now) {

        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.user(actorId, null))
                .at(ResourceScope.location(ticket.tenantId(), ticket.brandId(), ticket.locationId()))
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
     * The result of a state-transition command against one ticket item.
     *
     * @param applied whether this caller's command is the one that moved the item.
     *                False is a settled outcome, not a failure
     */
    public record ItemOutcome(boolean applied, TicketItemRow item, TicketRow ticket) {}

    /**
     * @param ceilingExceeded whether a station's throughput ceiling asked for
     *                        more lead time than there was left before {@code
     *                        now}, so the ticket is firing without the buffer
     *                        the ceiling wanted (ADR 0041's operational
     *                        exception)
     */
    private record Release(ReleaseMode mode, @Nullable Instant releaseAt, boolean fireNow, boolean ceilingExceeded) {}

    /** One order line, resolved onto a station but not yet written as a ticket item. */
    private record RoutedLine(UUID orderLineId, UUID stationId, RoutingLevel level, int quantity) {}
}
