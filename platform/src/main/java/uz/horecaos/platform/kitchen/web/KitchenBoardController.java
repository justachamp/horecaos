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
import java.util.UUID;
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
import uz.horecaos.platform.fulfillment.api.OrderProgressPort;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
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

    public KitchenBoardController(
            KitchenTicketService tickets, CurrentActor currentActor, AuthorizationService authorization) {
        this.tickets = tickets;
        this.currentActor = currentActor;
        this.authorization = authorization;
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

        List<TicketResponse> board = tickets.board(tenantId, locationId, statuses, limit).stream()
                .map(ticket -> TicketResponse.of(ticket, tickets.items(tenantId, ticket.id())))
                .toList();

        // The gap travels on every response rather than in a startup log. A branch
        // running the screen while proposals are unwired has to keep advancing
        // orders by hand, and the screen is the only place anybody will read that.
        List<String> warnings = tickets.orderProgressWired() ? List.of() : List.of(OrderProgressPort.NOT_WIRED_WARNING);

        return ResponseEntity.ok(new BoardResponse(board, warnings));
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
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(ticket.version()))
                .body(TicketResponse.of(ticket, tickets.items(tenantId, ticket.id())));
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

    @PostMapping("/ticket-items/{itemId}/start")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_ADVANCE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(summary = "Start one line at one station")
    public ResponseEntity<ItemResponse> start(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID itemId) {

        var outcome = tickets.start(tenantId, itemId, currentActor.get().subject(), null);
        requireLocation(outcome.ticket(), locationId);
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

        var outcome = tickets.ready(tenantId, itemId, currentActor.get().subject(), null);
        requireLocation(outcome.ticket(), locationId);
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

        var outcome = tickets.recall(
                tenantId, itemId, body.reasonCode(), currentActor.get().subject(), null);
        requireLocation(outcome.ticket(), locationId);
        return ResponseEntity.ok(ItemResponse.of(outcome));
    }

    /**
     * The application half of the isolation rule.
     *
     * <p>A ticket at a sibling branch answers "not found" rather than "forbidden".
     * The alternative confirms that a ticket of that id exists somewhere, which is
     * information the caller was not entitled to.
     */
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

    record BoardResponse(List<TicketResponse> tickets, List<String> warnings) {}

    record TicketResponse(
            UUID ticketId,
            UUID orderId,
            String sequenceLabel,
            String status,
            String releaseMode,
            @Nullable Instant releaseAt,
            @Nullable Instant releasedAt,
            @Nullable Instant targetReadyAt,
            @Nullable Integer prepEstimateSeconds,
            @Nullable Instant startedAt,
            @Nullable Instant readyAt,
            int version,
            List<ItemView> items) {

        static TicketResponse of(TicketRow ticket, List<TicketItemRow> items) {
            return new TicketResponse(
                    ticket.id(),
                    ticket.orderId(),
                    ticket.sequenceLabel(),
                    ticket.status().name(),
                    ticket.releaseMode().name(),
                    ticket.releaseAt(),
                    ticket.releasedAt(),
                    ticket.targetReadyAt(),
                    ticket.prepEstimateSeconds(),
                    ticket.startedAt(),
                    ticket.readyAt(),
                    ticket.version(),
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
