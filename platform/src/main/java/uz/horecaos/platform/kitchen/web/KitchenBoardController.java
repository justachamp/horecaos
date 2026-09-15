package uz.horecaos.platform.kitchen.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.api.CourierEtaPort;
import uz.horecaos.platform.fulfillment.api.OrderProgressPort;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The kitchen board, the buffer, and the station advances taken from them
 * (ADR 0041).
 *
 * <p>The path is not the one ADR 0041 writes. It nests under
 * {@code /tenants/{tenantId}/brands/{brandId}/locations/{locationId}} because
 * ADR 0025's scope resolution reads all three identifiers from path variables:
 * a flat {@code /api/v1/kitchen/locations/{locationId}/...} cannot express a
 * {@code LOCATION} scope at all, and the ADR 0025 build gate would refuse it. The
 * ADR's shape is a sketch of the resource tree, not of the authorization model
 * that already exists.
 *
 * <p>Everything is at {@code LOCATION} scope. A kitchen principal belongs to one
 * branch, and ADR 0041 requires that a sibling location refuse it at both the
 * application and the SQL boundary — the second half is the foreign keys in
 * V0030 that bind a ticket item's station to its ticket's location.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kitchen")
@Tag(name = "Kitchen board", description = "Production tickets, the buffer, and station advances")
public class KitchenBoardController {

    private final KitchenTicketService tickets;
    private final CurrentActor currentActor;
    private final AuthorizationService authorization;
    private final CourierEtaPort courierEta;

    public KitchenBoardController(
            KitchenTicketService tickets,
            CurrentActor currentActor,
            AuthorizationService authorization,
            CourierEtaPort courierEta) {
        this.tickets = tickets;
        this.currentActor = currentActor;
        this.authorization = authorization;
        this.courierEta = courierEta;
    }

    @GetMapping("/tickets")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The board, or the buffer",
            description = "Live tickets by default, soonest due first. Pass stream=buffer for the "
                    + "held ones, ordered by when they will fire.")
    public ResponseEntity<BoardResponse> board(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false, defaultValue = "live") String stream,
            @RequestParam(defaultValue = "100") @Max(500) int limit) {

        List<String> statuses =
                switch (stream) {
                    case "live" -> List.of("FIRED", "IN_PRODUCTION", "READY");
                    case "buffer" -> List.of("HELD");
                    case "pass" -> List.of("READY");
                    default ->
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "stream is one of live, buffer, pass");
                };

        List<TicketRow> ticketRows = tickets.board(tenantId, locationId, statuses, limit);

        // One batch read over every distinct channel code this page carries,
        // not one lookup per ticket (gap map row 2.1: channelCode is typed
        // against sales_channels.system_type here, once, rather than left for
        // the client to guess at from a free string).
        Set<String> channelCodes = ticketRows.stream()
                .map(TicketRow::channelCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> channelSystemTypes = tickets.channelSystemTypes(tenantId, channelCodes);

        // One batch of order ids feeds both of the page's per-order joins
        // below: the courier ETA chip (gap map row 2.1a) and the VDU's
        // provider-assigned reference (gap map row 2.4). Each is a single
        // read over every distinct order id this page carries, not one
        // lookup per ticket.
        Set<UUID> orderIds = ticketRows.stream().map(TicketRow::orderId).collect(Collectors.toSet());
        Map<UUID, String> externalReferences = tickets.externalReferencesByOrder(tenantId, orderIds);
        Map<UUID, Instant> courierEtaByOrder = courierEta.etaByOrders(tenantId, orderIds);

        List<TicketResponse> board = ticketRows.stream()
                .map(ticket -> TicketResponse.of(
                        ticket,
                        tickets.items(tenantId, ticket.id()),
                        channelSystemTypes.get(ticket.channelCode()),
                        externalReferences.get(ticket.orderId()),
                        courierEtaByOrder.get(ticket.orderId())))
                .toList();

        // The gap travels on every response rather than in a startup log. A branch
        // running the screen while proposals are unwired has to keep advancing
        // orders by hand, and the screen is the only place anybody will read that.
        List<String> warnings = tickets.orderProgressWired() ? List.of() : List.of(OrderProgressPort.NOT_WIRED_WARNING);

        // The tab badges, exact over every matching ticket rather than over
        // just this page — gap map row 2.1's own finding: the client used to
        // count fulfilmentMode over the same <=200-row page the board itself
        // paginates, silently undercounting past that page.
        JdbcKitchenStore.TicketCountsRow counts = tickets.counts(tenantId, locationId, statuses);

        return ResponseEntity.ok(new BoardResponse(board, warnings, CountsResponse.of(counts)));
    }

    @GetMapping("/tickets/{ticketId}")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "One ticket with its lines")
    public ResponseEntity<TicketResponse> ticket(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID ticketId) {

        TicketRow ticket = atLocation(tenantId, ticketId, locationId);
        Instant eta = courierEta.etaByOrders(tenantId, Set.of(ticket.orderId())).get(ticket.orderId());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(ticket.version()))
                .body(TicketResponse.of(ticket, tickets.items(tenantId, ticket.id()), null, null, eta));
    }

    /**
     * The production lane of the order detail's timeline (gap map row 1.2b):
     * every {@code kitchen.ticket_events} row for the ticket this order
     * opened, whatever the ticket's own status — including {@code
     * HANDED_OVER} and {@code VOIDED}, which {@link #board} never returns and
     * which is exactly the case the gap map calls out: "a finished order's
     * ticket falls off the board entirely."
     *
     * <p>{@code ORDER_READ} rather than {@code KITCHEN_TICKET_READ} —
     * deliberately, per the wave's own brief: the order detail pane's
     * operator has the former and not necessarily the latter, and this
     * endpoint exists for that pane, not for the kitchen board.
     */
    @GetMapping("/orders/{orderId}/events")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "This order's kitchen production events, if it ever opened a ticket",
            description = "Empty when the order never opened a ticket at all — an ordinary state, "
                    + "not an error. A ticket that opened at a different branch answers not found, "
                    + "the same rule every other order-keyed read in this console follows.")
    public ResponseEntity<KitchenEventsResponse> eventsForOrder(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {

        Optional<TicketRow> ticket = tickets.byOrder(tenantId, orderId);
        if (ticket.isPresent() && !ticket.get().locationId().equals(locationId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such ticket");
        }
        if (ticket.isEmpty()) {
            return ResponseEntity.ok(new KitchenEventsResponse(null, null, List.of()));
        }
        List<JdbcKitchenStore.TicketEventRow> events =
                tickets.events(tenantId, ticket.get().id());
        return ResponseEntity.ok(new KitchenEventsResponse(
                ticket.get().id(),
                ticket.get().status().name(),
                events.stream().map(KitchenEventResponse::of).toList()));
    }

    @PostMapping("/tickets/{ticketId}/release")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_RELEASE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Fire a buffered ticket now",
            description = "A second press is not an error: the caller wanted the ticket on a "
                    + "screen, and it is on a screen.")
    public ResponseEntity<TicketResponse> release(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ReleaseRequest body) {

        atLocation(tenantId, ticketId, locationId);
        TicketRow after = tickets.releaseNow(
                tenantId,
                ticketId,
                body.expectedVersion(),
                body.reasonCode(),
                currentActor.get().subject(),
                null);
        return ResponseEntity.ok(TicketResponse.of(after, tickets.items(tenantId, ticketId)));
    }

    @PutMapping("/tickets/{ticketId}/release-schedule")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_RELEASE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Hold a ticket, or change when it fires",
            description = "Moving the fire time later than the promise permits additionally "
                    + "requires kitchen.ticket.release.override and a reason, and writes an "
                    + "ADR 0027 audit fact. Moving it earlier breaks no promise and needs "
                    + "neither.")
    public ResponseEntity<TicketResponse> reschedule(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID ticketId,
            @Valid @RequestBody RescheduleRequest body) {

        atLocation(tenantId, ticketId, locationId);
        // The endpoint's own declaration covers kitchen.ticket.release. The
        // override is a second, narrower capability asked for here rather than in
        // the annotation, because it is required only for the half of this
        // endpoint's job that moves a fire time later — declaring it on the method
        // would lock a manager out of pulling a ticket forward.
        boolean overrideGranted = authorization.has(
                currentActor.get().subject(),
                Capability.KITCHEN_TICKET_RELEASE_OVERRIDE,
                ResourceScope.location(tenantId, brandId, locationId));

        TicketRow after = tickets.reschedule(
                tenantId,
                ticketId,
                body.expectedVersion(),
                ReleaseMode.valueOf(body.releaseMode()),
                body.releaseAt(),
                overrideGranted,
                body.reasonCode(),
                currentActor.get().subject(),
                null);
        return ResponseEntity.ok(TicketResponse.of(after, tickets.items(tenantId, ticketId)));
    }

    @PostMapping("/tickets/{ticketId}/hand-over")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_HANDOVER, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Hand a ready ticket to the customer or courier",
            description = "IA 2.3 (Раздача). A second press is not an error: the caller wanted "
                    + "the ticket off the pass, and it is off the pass. The provider "
                    + "handover-code verification the spec asks for is ADR 0040 and not built — "
                    + "this endpoint is the roll-up's own gate and nothing else.")
    public ResponseEntity<TicketResponse> handOver(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID ticketId) {

        atLocation(tenantId, ticketId, locationId);
        TicketRow after = tickets.handOver(
                        tenantId, ticketId, currentActor.get().subject(), null)
                .orElseGet(() -> tickets.require(tenantId, ticketId));
        return ResponseEntity.ok(TicketResponse.of(after, tickets.items(tenantId, ticketId)));
    }

    @PostMapping("/ticket-items/{itemId}/start")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_ADVANCE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(summary = "Start one line at one station")
    public ResponseEntity<ItemResponse> start(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID itemId) {

        itemAtLocation(tenantId, itemId, locationId);
        var outcome = tickets.start(tenantId, itemId, currentActor.get().subject(), null);
        return ResponseEntity.ok(ItemResponse.of(outcome));
    }

    @PostMapping("/ticket-items/{itemId}/ready")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_ADVANCE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Mark one line ready",
            description = "Two devices pressing this in the same second settle once. The loser is "
                    + "told the settled state rather than given an error a cook has to interpret.")
    public ResponseEntity<ItemResponse> ready(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID itemId) {

        itemAtLocation(tenantId, itemId, locationId);
        var outcome = tickets.ready(tenantId, itemId, currentActor.get().subject(), null);
        return ResponseEntity.ok(ItemResponse.of(outcome));
    }

    @PostMapping("/ticket-items/{itemId}/recall")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_RECALL, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Pull a line back from ready",
            description = "Refused once the ticket has been handed over: the food has left the "
                    + "pass, and recalling it there would leave the order reading READY to a "
                    + "customer who is holding it.")
    public ResponseEntity<ItemResponse> recall(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID itemId,
            @Valid @RequestBody RecallRequest body) {

        itemAtLocation(tenantId, itemId, locationId);
        var outcome = tickets.recall(
                tenantId, itemId, body.reasonCode(), currentActor.get().subject(), null);
        return ResponseEntity.ok(ItemResponse.of(outcome));
    }

    /**
     * The application half of the isolation rule.
     *
     * <p>A ticket at a sibling branch answers "not found" rather than "forbidden".
     * The alternative confirms that a ticket of that id exists somewhere, which is
     * information the caller was not entitled to.
     */
    /**
     * The same isolation rule for the endpoints keyed by a line rather than a
     * ticket, and it has to run <em>before</em> the transition, not after it.
     *
     * <p>{@code start}, {@code ready} and {@code recall} used to call the
     * transition first and check the ticket it returned. Each of those service
     * methods is {@code @Transactional} and this controller is not, so the write
     * committed on its own and the check then threw against an already-durable
     * change: a cook scoped to one branch could advance a sibling branch's line
     * and get a 404 describing a mutation that had happened. Resolving the line's
     * ticket first costs one read and closes that. A ticket never moves branch,
     * so there is no window between this check and the transition for the answer
     * to change.
     */
    private void itemAtLocation(UUID tenantId, UUID itemId, UUID locationId) {
        requireLocation(tickets.ticketOfItem(tenantId, itemId), locationId);
    }

    private TicketRow atLocation(UUID tenantId, UUID ticketId, UUID locationId) {
        TicketRow ticket = tickets.require(tenantId, ticketId);
        requireLocation(ticket, locationId);
        return ticket;
    }

    private static void requireLocation(TicketRow ticket, UUID locationId) {
        if (!ticket.locationId().equals(locationId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such ticket");
        }
    }

    // ------------------------------------------------------------------ payloads

    /**
     * {@code counts} is the board's own tab badges (gap map row 2.1), exact
     * over every ticket the query matched rather than only over {@code
     * tickets} — which {@code limit} may have cut.
     */
    record BoardResponse(List<TicketResponse> tickets, List<String> warnings, CountsResponse counts) {}

    /** Mirrors {@link JdbcKitchenStore.TicketCountsRow}. */
    record CountsResponse(long total, long delivery, long pickup, long dineIn, long aggregator) {
        static CountsResponse of(JdbcKitchenStore.TicketCountsRow row) {
            return new CountsResponse(row.total(), row.delivery(), row.pickup(), row.dineIn(), row.aggregator());
        }
    }

    /**
     * {@code fulfilmentMode} and {@code channelCode} were on {@code TicketRow}
     * from V0030 onward but never left the JDBC layer — the KDS board (IA 2.1)
     * partitions its queue by fulfilment type and shows the channel on each
     * ticket, and a client cannot do either from a wire response that omits
     * both. {@code createdAt} is added alongside them for the same reason: the
     * board colour-codes a ticket against {@code targetReadyAt} where one
     * exists, and otherwise needs an elapsed-time fallback the way the order
     * board's own severity model does (orders.md's "45 minutes, no promise"
     * rule) — impossible without knowing when the ticket was opened.
     *
     * <p>{@code channelSystemType} (wave P16) is {@code
     * tenant.sales_channels.system_type} resolved off {@code channelCode} —
     * {@code AGGREGATOR} lets the client render a real aggregator tab instead
     * of the raw channel code as an unclassified chip (gap map row 2.1).
     *
     * <p>{@code externalReference} (wave T02, gap map row 2.4) is the
     * provider-assigned identifier a courier or a customer would actually
     * quote — {@code sequenceLabel} is HorecaOS's own number, never that.
     * {@code courierEtaAt} (wave P11, gap map row 2.1a) is the winning
     * partner quote's own ETA, joined off {@code
     * fulfillment.delivery_plans.courier_eta_at} by order id — null for a
     * pickup or dine-in ticket, a plan an in-house courier carries, or a
     * partner that answered no ETA. Both are resolved only by {@link #board},
     * each at the cost of one batch read over the page's distinct order ids
     * (sharing the same {@code orderIds} set); {@link #ticket} resolves only
     * {@code courierEtaAt} (not {@code externalReference}), and every
     * mutation response below keeps the cheaper two-argument {@link
     * #of(TicketRow, List)} overload, carrying neither — a client that
     * already holds either value from its last board read loses nothing by a
     * mutation response not repeating it.
     */
    record TicketResponse(
            UUID ticketId,
            UUID orderId,
            String sequenceLabel,
            @Nullable String externalReference,
            String fulfilmentMode,
            @Nullable String channelCode,
            @Nullable String channelSystemType,
            String status,
            String releaseMode,
            @Nullable Instant releaseAt,
            @Nullable Instant releasedAt,
            @Nullable Instant targetReadyAt,
            @Nullable Integer prepEstimateSeconds,
            @Nullable Instant startedAt,
            @Nullable Instant readyAt,
            int version,
            Instant createdAt,
            @Nullable Instant courierEtaAt,
            List<ItemView> items) {

        static TicketResponse of(TicketRow ticket, List<TicketItemRow> items) {
            return of(ticket, items, null, null, null);
        }

        static TicketResponse of(TicketRow ticket, List<TicketItemRow> items, @Nullable String channelSystemType) {
            return of(ticket, items, channelSystemType, null, null);
        }

        static TicketResponse of(
                TicketRow ticket,
                List<TicketItemRow> items,
                @Nullable String channelSystemType,
                @Nullable String externalReference,
                @Nullable Instant courierEtaAt) {
            return new TicketResponse(
                    ticket.id(),
                    ticket.orderId(),
                    ticket.sequenceLabel(),
                    externalReference,
                    ticket.fulfilmentMode(),
                    ticket.channelCode(),
                    channelSystemType,
                    ticket.status().name(),
                    ticket.releaseMode().name(),
                    ticket.releaseAt(),
                    ticket.releasedAt(),
                    ticket.targetReadyAt(),
                    ticket.prepEstimateSeconds(),
                    ticket.startedAt(),
                    ticket.readyAt(),
                    ticket.version(),
                    ticket.createdAt(),
                    courierEtaAt,
                    items.stream().map(ItemView::of).toList());
        }
    }

    /**
     * Deliberately no dish name. ADR 0041 keeps names out of kitchen events and
     * off kitchen rows; a display resolves them through an authorized read against
     * the ADR 0019 order snapshot, where the name has one authority and the
     * customer's note is under ADR 0029 envelope encryption.
     */
    record ItemView(
            UUID itemId, UUID orderLineId, UUID stationId, int quantity, String routedBy, String status, int version) {

        static ItemView of(TicketItemRow item) {
            return new ItemView(
                    item.id(),
                    item.orderLineId(),
                    item.stationId(),
                    item.quantity(),
                    item.routedBy().name(),
                    item.status().name(),
                    item.version());
        }
    }

    record ItemResponse(boolean applied, ItemView item, String ticketStatus, int ticketVersion) {

        static ItemResponse of(KitchenTicketService.ItemOutcome outcome) {
            return new ItemResponse(
                    outcome.applied(),
                    ItemView.of(outcome.item()),
                    outcome.ticket().status().name(),
                    outcome.ticket().version());
        }
    }

    /**
     * {@link #eventsForOrder}'s response. {@code ticketId}/{@code
     * ticketStatus} are null exactly when {@code events} is empty. {@code
     * public} — unlike this controller's other payload records — because the
     * order-keyed production lane (gap map row 1.2b) is tested from {@code
     * KitchenExecutionTests}, which needs its own package's seeding
     * infrastructure this class does not have.
     */
    public record KitchenEventsResponse(
            @Nullable UUID ticketId, @Nullable String ticketStatus, List<KitchenEventResponse> events) {}

    /**
     * One {@code kitchen.ticket_events} row. {@code ticketItemId} is null for a
     * ticket-level transition (FIRED, IN_PRODUCTION, READY, HANDED_OVER) and set
     * for a per-line station advance — the order detail's production lane (gap
     * map row 1.2b) uses the former to build its stage steps and elapsed
     * durations, ignoring the latter the same way the kitchen board itself
     * never names a dish (ADR 0041).
     */
    public record KitchenEventResponse(
            UUID id,
            @Nullable UUID ticketItemId,
            @Nullable String fromStatus,
            String toStatus,
            String trigger,
            String actorType,
            String actorId,
            @Nullable String reasonCode,
            Instant occurredAt) {

        static KitchenEventResponse of(JdbcKitchenStore.TicketEventRow row) {
            return new KitchenEventResponse(
                    row.id(),
                    row.ticketItemId(),
                    row.fromStatus(),
                    row.toStatus(),
                    row.trigger(),
                    row.actorType(),
                    row.actorId(),
                    row.reasonCode(),
                    row.occurredAt());
        }
    }

    record ReleaseRequest(
            @NotNull Integer expectedVersion,
            @NotBlank @Size(max = 48) String reasonCode) {}

    record RescheduleRequest(
            @NotNull Integer expectedVersion,
            @NotBlank @Size(max = 20) String releaseMode,
            Instant releaseAt,
            @Size(max = 48) String reasonCode) {}

    record RecallRequest(@NotBlank @Size(max = 48) String reasonCode) {}
}
