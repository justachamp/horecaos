package uz.horecaos.platform.kitchen.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
import uz.horecaos.platform.dinein.api.OrderTablesPort;
import uz.horecaos.platform.fulfillment.api.CourierEtaPort;
import uz.horecaos.platform.fulfillment.api.OrderProgressPort;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.kitchen.application.KitchenDeviceDisplayService;
import uz.horecaos.platform.kitchen.application.KitchenDeviceDisplayService.WallCaller;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.kitchen.application.port.KitchenOrderSource.OrderClock;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;
import uz.horecaos.platform.ordering.api.LatenessPolicyPort;
import uz.horecaos.platform.ordering.api.LatenessPolicyPort.LatenessPolicyView;
import uz.horecaos.platform.ordering.api.LatenessPolicyPort.Thresholds;
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
    private final OrderTablesPort orderTables;
    private final KitchenDeviceDisplayService displays;
    private final LatenessPolicyPort latenessPolicies;

    public KitchenBoardController(
            KitchenTicketService tickets,
            CurrentActor currentActor,
            AuthorizationService authorization,
            CourierEtaPort courierEta,
            OrderTablesPort orderTables,
            KitchenDeviceDisplayService displays,
            LatenessPolicyPort latenessPolicies) {
        this.tickets = tickets;
        this.currentActor = currentActor;
        this.authorization = authorization;
        this.courierEta = courierEta;
        this.orderTables = orderTables;
        this.displays = displays;
        this.latenessPolicies = latenessPolicies;
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
        // The order's own lateness clock (ADR 0150), one batch over the same ids: the colour of a ticket
        // is the board's rule applied to the order, not to the ticket the kitchen opened later.
        Map<UUID, OrderClock> clocks = tickets.orderClocksByOrder(tenantId, orderIds);

        // The table beside a dine-in ticket: one batch over the page's DINE_IN
        // orders only -- a delivery or pickup ticket can never sit at a table --
        // through the port dine-in owns, rather than a join into dinein.* from
        // this module (ADR 0047).
        Set<UUID> dineInOrderIds = ticketRows.stream()
                .filter(ticket -> "DINE_IN".equals(ticket.fulfilmentMode()))
                .map(TicketRow::orderId)
                .collect(Collectors.toSet());
        Map<UUID, OrderTablesPort.OrderTable> tableByOrder =
                dineInOrderIds.isEmpty() ? Map.of() : orderTables.tablesByOrders(tenantId, dineInOrderIds);

        List<TicketResponse> board = ticketRows.stream()
                .map(ticket -> TicketResponse.of(
                                ticket,
                                tickets.items(tenantId, ticket.id()),
                                channelSystemTypes.get(ticket.channelCode()),
                                externalReferences.get(ticket.orderId()),
                                courierEtaByOrder.get(ticket.orderId()),
                                tableByOrder.get(ticket.orderId()))
                        .withClock(clocks.get(ticket.orderId())))
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

    /**
     * The VDU wall projection (ADR 0041 rollout step 4, gap map row 2.4).
     *
     * <p><strong>A dedicated read model, not a filtered reuse of {@link
     * #board}.</strong> {@link VduTicketResponse} carries only what a screen
     * mounted above the pass and read from across the room needs — a
     * sequence, a provider reference, a fulfilment mode, a status, the
     * order's lateness clock the wall colour-codes against (its creation, its
     * promise and whether it is over, ADR 0150), and each item's station and
     * quantity. It has no {@code orderId} (an internal identifier nobody
     * reads off a wall), no {@code releaseMode}/{@code releaseAt}/{@code
     * releasedAt}/{@code prepEstimateSeconds} (buffer-management facts a
     * touch KDS needs and a read-only wall does not), no {@code
     * orderLineId}/{@code routedBy}/{@code version} on a line (a wall never
     * mutates one), and — same as every kitchen row since V0030 — no dish
     * name and no per-line note: ADR 0041 keeps both off kitchen rows
     * entirely, and a wall display is the last place either belongs.
     *
     * <p><strong>The station filter</strong> narrows to the tickets that have
     * at least one line at the named station, and within each of those
     * tickets to that station's own lines — "VDU is a projection with a
     * station filter", ADR 0041's own words. Omitting {@code station} answers
     * the whole branch, the same reduction the desk console's VDU page
     * carried before this wave. <strong>For a caller that is a wall display
     * (ADR 0151) the station is the device's own configuration</strong>: the
     * server applies the one Kitchen → Devices holds for it (the whole branch
     * when none is set) and ignores the request's, because a wall that can be
     * pointed at another station by editing a URL is a convenience, not a
     * boundary. A person's request is honoured as it always was; this is not a
     * boundary against the same branch's staff.
     *
     * <p><strong>The capability is {@code kitchen.display.read}</strong> (ADR
     * 0151), held by every bundle that held {@code kitchen.ticket.read} and by
     * a wall display alone, so the wall cannot call the touch board's read, a
     * single ticket, or advance anything.
     *
     * <p><strong>The projection carries the lateness policy</strong> resolved
     * at the location of the call, in the shape {@code GET
     * .../orders/lateness-policy} serves. A wall display holds no {@code
     * order.read} and cannot call that endpoint; without the policy it would
     * colour a ticket "on time" that the manager's console shows late, and
     * nothing on the screen would say why. The resolution is the cached one
     * every board uses, and it is tenant configuration, not customer data.
     */
    @GetMapping("/vdu")
    @RequiresCapability(value = Capability.KITCHEN_DISPLAY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The VDU wall projection",
            description = "Fired tickets, no controls, no notes, no customer data -- only the fields "
                    + "a wall needs, and the lateness policy to colour them with. Pass station to narrow to "
                    + "one station's own lines; for a wall display enrolled as KITCHEN_VDU the configured "
                    + "station is applied instead and the parameter is ignored.")
    public ResponseEntity<VduBoardResponse> vdu(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable UUID station) {

        WallCaller wall = displays.wallCaller(currentActor.get().subject(), tenantId, locationId)
                .orElse(null);
        UUID effectiveStation = wall != null ? wall.stationId() : station;

        List<TicketRow> ticketRows =
                tickets.board(tenantId, locationId, List.of("FIRED", "IN_PRODUCTION", "READY"), 200);

        Set<UUID> orderIds = ticketRows.stream().map(TicketRow::orderId).collect(Collectors.toSet());
        Map<UUID, String> externalReferences = tickets.externalReferencesByOrder(tenantId, orderIds);
        Map<UUID, Instant> courierEtaByOrder = courierEta.etaByOrders(tenantId, orderIds);
        Map<UUID, OrderClock> clocks = tickets.orderClocksByOrder(tenantId, orderIds);

        List<VduTicketResponse> board = ticketRows.stream()
                .map(ticket -> VduTicketResponse.of(
                        ticket,
                        tickets.items(tenantId, ticket.id()),
                        effectiveStation,
                        externalReferences.get(ticket.orderId()),
                        courierEtaByOrder.get(ticket.orderId()),
                        clocks.get(ticket.orderId())))
                // A ticket that touches no line at the requested station has
                // nothing for that wall to show -- a grill wall does not
                // render an empty tile for a ticket that never routed to the
                // grill.
                .flatMap(Optional::stream)
                .toList();

        if (wall != null) {
            displays.recordWallRead(wall, tenantId);
        } else {
            displays.recordStaffRead();
        }
        return ResponseEntity.ok(new VduBoardResponse(
                board, VduLatenessPolicy.of(latenessPolicies.policyAt(tenantId, brandId, locationId))));
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
        OrderTablesPort.OrderTable table = "DINE_IN".equals(ticket.fulfilmentMode())
                ? orderTables.tablesByOrders(tenantId, Set.of(ticket.orderId())).get(ticket.orderId())
                : null;
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(ticket.version()))
                .body(TicketResponse.of(ticket, tickets.items(tenantId, ticket.id()), null, null, eta, table)
                        .withClock(clockOf(tenantId, ticket.orderId())));
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
        return ResponseEntity.ok(TicketResponse.of(after, tickets.items(tenantId, ticketId))
                .withClock(clockOf(tenantId, after.orderId())));
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
        return ResponseEntity.ok(TicketResponse.of(after, tickets.items(tenantId, ticketId))
                .withClock(clockOf(tenantId, after.orderId())));
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
        return ResponseEntity.ok(TicketResponse.of(after, tickets.items(tenantId, ticketId))
                .withClock(clockOf(tenantId, after.orderId())));
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

    private @Nullable OrderClock clockOf(UUID tenantId, UUID orderId) {
        return tickets.orderClocksByOrder(tenantId, Set.of(orderId)).get(orderId);
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
     *
     * <p><strong>{@code orderCreatedAt}, {@code orderPromisedAt} and {@code orderTerminal} are the
     * ORDER's lateness clock (ADR 0150), and the only inputs a screen colours a ticket by.</strong>
     * {@code createdAt} above is when the kitchen opened the ticket, which for an order that waited for
     * approval or was taken for a slot is long after checkout, and {@code targetReadyAt} is the promise
     * less the road. The board's rule is the order's -- late once its promise plus the grace has passed,
     * or, with no promise, once its creation plus the fallback has, never for a finished order -- so a
     * queue or a wall that coloured by the ticket's two instants would start the clock again on
     * acceptance and call a delivery late a road-time before the board does. All three are on every
     * response, mutations included, so a ticket does not change colour when a cook presses a button.
     * {@code orderCreatedAt} is null only on a response built without a clock, which a screen reads as
     * "colour from the ticket's own instants, as before".
     *
     * <p>{@code table} (batch 14, gap map rows {@code 1.1}/{@code 2.1}'s
     * dine-in visibility) is the table -- or joined tables -- and session a
     * DINE_IN ticket's order was seated at, resolved through {@link
     * OrderTablesPort} for DINE_IN tickets only. Codes and display names, never
     * a guest. Null for every other ticket, for a DINE_IN order an operator keyed
     * in without seating anyone, and on every mutation response, which follows
     * the same keep-the-last-board-read rule {@code courierEtaAt} does.
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
            @Nullable Instant orderCreatedAt,
            @Nullable Instant orderPromisedAt,
            boolean orderTerminal,
            @Nullable Instant courierEtaAt,
            OrderTablesPort.@Nullable OrderTable table,
            List<ItemView> items) {

        /** This response with the order's lateness clock (ADR 0150); unchanged when the order has none. */
        TicketResponse withClock(@Nullable OrderClock clock) {
            if (clock == null) {
                return this;
            }
            return new TicketResponse(
                    ticketId,
                    orderId,
                    sequenceLabel,
                    externalReference,
                    fulfilmentMode,
                    channelCode,
                    channelSystemType,
                    status,
                    releaseMode,
                    releaseAt,
                    releasedAt,
                    targetReadyAt,
                    prepEstimateSeconds,
                    startedAt,
                    readyAt,
                    version,
                    createdAt,
                    clock.createdAt(),
                    clock.promisedAt(),
                    clock.terminal(),
                    courierEtaAt,
                    table,
                    items);
        }

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
            return of(ticket, items, channelSystemType, externalReference, courierEtaAt, null);
        }

        static TicketResponse of(
                TicketRow ticket,
                List<TicketItemRow> items,
                @Nullable String channelSystemType,
                @Nullable String externalReference,
                @Nullable Instant courierEtaAt,
                OrderTablesPort.@Nullable OrderTable table) {
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
                    null,
                    null,
                    false,
                    courierEtaAt,
                    table,
                    items.stream().map(ItemView::of).toList());
        }
    }

    /**
     * Deliberately no dish name. ADR 0041 keeps names out of kitchen events and
     * off kitchen rows; a display resolves them through an authorized read against
     * the ADR 0019 order snapshot, where the name has one authority and the
     * customer's note is under ADR 0029 envelope encryption.
     *
     * <p>That holds for a combo's name too (ADR 0136). {@code comboSelectionId} is the key the
     * component items of one combo purchase share, so a display groups them under one header, and
     * {@code comboContainerVariantId} says which combo; the header's text is the order line's
     * {@code combo.name} in the order read, copied there when the combo was sold. Both are null on
     * every other item. Routing never looks at either: each component routes by its own variant.
     */
    record ItemView(
            UUID itemId,
            UUID orderLineId,
            UUID stationId,
            BigDecimal quantity,
            String routedBy,
            String status,
            int version,
            @Nullable UUID comboSelectionId,
            @Nullable UUID comboContainerVariantId) {

        static ItemView of(TicketItemRow item) {
            return new ItemView(
                    item.id(),
                    item.orderLineId(),
                    item.stationId(),
                    item.quantity(),
                    item.routedBy().name(),
                    item.status().name(),
                    item.version(),
                    item.comboSelectionId(),
                    item.comboContainerVariantId());
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

    /**
     * {@link #vdu}'s response.
     *
     * @param lateness the {@code ordering.lateness} policy resolved at the location of the call, so a
     *                 wall display colours its tickets from the tenant's own thresholds without a read
     *                 it has no capability for (ADR 0151)
     */
    record VduBoardResponse(List<VduTicketResponse> tickets, VduLatenessPolicy lateness) {}

    /** One fulfilment mode's thresholds, in the shape {@code GET .../orders/lateness-policy} serves. */
    record VduLatenessThresholds(int atRiskBeforeSeconds, int lateAfterSeconds, int noPromiseFallbackSeconds) {

        static VduLatenessThresholds of(Thresholds thresholds) {
            return new VduLatenessThresholds(
                    thresholds.atRiskBeforeSeconds(),
                    thresholds.lateAfterSeconds(),
                    thresholds.noPromiseFallbackSeconds());
        }
    }

    /**
     * The lateness policy as {@code OrderLatenessPolicyController.LatenessPolicyResponse} serves it —
     * the same field names, so the console reads both with one parser.
     */
    record VduLatenessPolicy(
            VduLatenessThresholds delivery,
            VduLatenessThresholds pickup,
            VduLatenessThresholds dineIn,
            boolean isPlatformDefault,
            @Nullable UUID policyId,
            int policyVersion,
            @Nullable String lateColour) {

        static VduLatenessPolicy of(LatenessPolicyView policy) {
            return new VduLatenessPolicy(
                    VduLatenessThresholds.of(policy.delivery()),
                    VduLatenessThresholds.of(policy.pickup()),
                    VduLatenessThresholds.of(policy.dineIn()),
                    policy.isPlatformDefault(),
                    policy.policyId(),
                    policy.policyVersion(),
                    policy.lateColour());
        }
    }

    /**
     * {@link #vdu}'s own narrow ticket shape -- see that method's own doc for
     * exactly what this deliberately omits relative to {@link TicketResponse}.
     *
     * <p>{@code orderCreatedAt}, {@code orderPromisedAt} and {@code orderTerminal} are the order's
     * lateness clock (ADR 0150) and what a wall colours the ticket by, exactly as on {@link
     * TicketResponse}: not {@code createdAt}, which is when the kitchen opened the ticket, and not
     * {@code targetReadyAt}, which is the promise less the road. They are timing facts about an order,
     * not customer data, and carry no identifier.
     */
    record VduTicketResponse(
            UUID ticketId,
            String sequenceLabel,
            @Nullable String externalReference,
            String fulfilmentMode,
            String status,
            @Nullable Instant targetReadyAt,
            Instant createdAt,
            @Nullable Instant orderCreatedAt,
            @Nullable Instant orderPromisedAt,
            boolean orderTerminal,
            @Nullable Instant courierEtaAt,
            List<VduItemView> items) {

        /**
         * The same projection without an order clock (an order the kitchen cannot read), which a wall
         * colours from the ticket's own instants as it did before ADR 0150.
         */
        static Optional<VduTicketResponse> of(
                TicketRow ticket,
                List<TicketItemRow> items,
                @Nullable UUID station,
                @Nullable String externalReference,
                @Nullable Instant courierEtaAt) {
            return of(ticket, items, station, externalReference, courierEtaAt, null);
        }

        /**
         * @param station when non-null, narrows {@code items} to that station's
         *                own lines; {@code empty()} when a station filter is
         *                asked for and this ticket has no line at it, which is
         *                {@link #vdu}'s own "nothing for that wall to show"
         *                case
         */
        static Optional<VduTicketResponse> of(
                TicketRow ticket,
                List<TicketItemRow> items,
                @Nullable UUID station,
                @Nullable String externalReference,
                @Nullable Instant courierEtaAt,
                @Nullable OrderClock clock) {

            List<TicketItemRow> visible = station == null
                    ? items
                    : items.stream()
                            .filter(item -> station.equals(item.stationId()))
                            .toList();
            if (station != null && visible.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new VduTicketResponse(
                    ticket.id(),
                    ticket.sequenceLabel(),
                    externalReference,
                    ticket.fulfilmentMode(),
                    ticket.status().name(),
                    ticket.targetReadyAt(),
                    ticket.createdAt(),
                    clock == null ? null : clock.createdAt(),
                    clock == null ? null : clock.promisedAt(),
                    clock != null && clock.terminal(),
                    courierEtaAt,
                    visible.stream().map(VduItemView::of).toList()));
        }
    }

    /**
     * One line, reduced to the two facts a wall renders: which station, how many. A combo's items
     * also carry the key they are grouped on (ADR 0136), an id and never a name.
     */
    record VduItemView(
            UUID stationId,
            BigDecimal quantity,
            String status,
            @Nullable UUID comboSelectionId) {

        static VduItemView of(TicketItemRow item) {
            return new VduItemView(
                    item.stationId(), item.quantity(), item.status().name(), item.comboSelectionId());
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
