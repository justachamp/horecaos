package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OrderAction;
import uz.horecaos.platform.ordering.application.OrderActionsPolicy;
import uz.horecaos.platform.ordering.application.OrderAmendmentService;
import uz.horecaos.platform.ordering.application.OrderOutcomeReasonService;
import uz.horecaos.platform.ordering.application.OrderOutcomeService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.domain.AmendmentCommandType;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderAmendmentStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The restaurant's side of ordering (ADR 0002, ADR 0019).
 *
 * <p>Everything here is at {@code LOCATION} scope. A branch manager approving
 * their own branch's orders should not need a grant that reaches the whole brand,
 * and the ADR 0025 build gate enforces that the declared scope is no wider than
 * the path.
 *
 * <p>Every mutation carries a reason, an {@code Idempotency-Key}, and the
 * expected order version, per ADR 0031. The version is what makes two operators
 * deciding at the same moment settle at one outcome instead of two.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders")
@Tag(name = "Operations orders", description = "The branch's order queue, approvals, and timeline")
public class OperationsOrderController {

    private final OrderQueryService orderQuery;
    private final OrderStateService orderState;
    private final OrderOutcomeService outcomes;
    private final OrderAmendmentService amendments;
    private final CurrentActor currentActor;

    public OperationsOrderController(
            OrderQueryService orderQuery,
            OrderStateService orderState,
            OrderOutcomeService outcomes,
            OrderAmendmentService amendments,
            CurrentActor currentActor) {
        this.orderQuery = orderQuery;
        this.orderState = orderState;
        this.outcomes = outcomes;
        this.amendments = amendments;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The branch's orders, newest first",
            description = "Filterable by status. An empty filter returns everything rather than "
                    + "silently hiding the closed ones, because a branch reconciling a shift "
                    + "needs the orders that are over as much as the ones that are live.")
    public ResponseEntity<List<OrderSummaryResponse>> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "100") @jakarta.validation.constraints.Max(500) int limit) {

        List<String> statuses = status == null ? List.of() : status;
        statuses.forEach(OperationsOrderController::requireKnownStatus);

        return ResponseEntity.ok(orderQuery.forLocation(tenantId, brandId, locationId, statuses, limit).stream()
                .map(OrderSummaryResponse::of)
                .toList());
    }

    @GetMapping("/counts")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The board's tab badges, one call",
            description = "orders.md §2.3: each tab shows a live count computed before the tab's "
                    + "own filters apply, and until ADR 0045's COUNTERS signal exists this is what "
                    + "computes them. One aggregate over the location's orders, scoped identically "
                    + "to the list above. Внимание's live severity queue (late orders, stuck "
                    + "processes) is not among these — it is derived per render from the promise "
                    + "and the clock, never stored, so a count of it would be wrong five seconds "
                    + "after being cached.")
    public ResponseEntity<OrderCountsResponse> counts(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(OrderCountsResponse.of(orderQuery.counts(tenantId, brandId, locationId)));
    }

    @GetMapping("/{orderId}")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "One order with its snapshotted lines",
            description = "Revision-aware (ADR 0039). The default is the order's current "
                    + "revision; naming an earlier one re-renders the lines as they were. A read "
                    + "that forgets to pin a revision double-counts, and the mistake stays "
                    + "invisible until somebody reconciles a total by hand.")
    public ResponseEntity<OrderDetailResponse> detail(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @RequestParam(required = false) Integer revision) {

        var detail = orderQuery
                .detail(tenantId, orderId, revision)
                .filter(found -> found.order().locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(detail.order().version()))
                .body(OrderDetailResponse.of(
                        detail, orderQuery.outcome(tenantId, orderId).orElse(null)));
    }

    @GetMapping("/{orderId}/revisions")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Every revision of this order, with its own reproducible total",
            description = "Revision 1 is the ADR 0019 checkout snapshot and is byte-identical for "
                    + "ever. Each applied amendment appends one carrying a complete recomputed "
                    + "total plus the delta against its predecessor — the figure the operator "
                    + "read to the customer.")
    public ResponseEntity<List<RevisionResponse>> revisions(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {

        requireOrderAtLocation(tenantId, orderId, locationId);

        return ResponseEntity.ok(orderQuery.revisions(tenantId, orderId).stream()
                .map(RevisionResponse::of)
                .toList());
    }

    @GetMapping("/{orderId}/timeline")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Every transition, with what caused it and who",
            description = "The answer to \"why is this order in this state\". An order row alone "
                    + "holds only the current status and can never answer it.")
    public ResponseEntity<List<TimelineEntryResponse>> timeline(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {

        requireOrderAtLocation(tenantId, orderId, locationId);

        return ResponseEntity.ok(orderQuery.timeline(tenantId, orderId).stream()
                .map(TimelineEntryResponse::of)
                .toList());
    }

    @PostMapping("/{orderId}/approval-decisions")
    @RequiresCapability(value = Capability.ORDER_APPROVE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Approve or reject an order awaiting a decision",
            description = "The first valid command wins under compare-and-set. A command that "
                    + "loses is recorded and inert, and the response reports the outcome that "
                    + "actually settled the order — so a second click gives the same answer as "
                    + "the first rather than an error.")
    public ResponseEntity<DecisionResponse> decide(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody DecisionRequest body) {

        var result = orderState.decide(
                tenantId,
                orderId,
                new OrderStateService.DecisionCommand(
                        body.decisionId(),
                        body.action(),
                        "HORECAOS_OPERATIONS",
                        "USER",
                        currentActor.get().subject(),
                        body.reasonCode(),
                        body.issuedAt() == null ? Instant.now() : body.issuedAt(),
                        null));

        return ResponseEntity.ok(new DecisionResponse(
                orderId,
                result.status().name(),
                result.orderVersion(),
                result.applied(),
                result.effectiveDecision() == null
                        ? null
                        : result.effectiveDecision().decisionId(),
                result.effectiveDecision() == null
                        ? null
                        : result.effectiveDecision().action()));
    }

    @PostMapping("/{orderId}/state-actions")
    @RequiresCapability(value = Capability.ORDER_ADVANCE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Move a confirmed order along the kitchen path",
            description = "Guarded by the canonical state machine, including the fulfilment-mode "
                    + "split at READY: a pickup order cannot enter FULFILLING, where it would "
                    + "wait for a courier that does not exist.")
    public ResponseEntity<DecisionResponse> stateAction(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody StateActionRequest body,
            HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var result = orderState.advance(
                    tenantId,
                    orderId,
                    body.targetStatus(),
                    (int) expected,
                    body.reasonCode(),
                    "USER",
                    currentActor.get().subject(),
                    null);
            return ResponseEntity.ok(new DecisionResponse(
                    orderId, result.status().name(), result.orderVersion(), result.applied(), null, null));
        } catch (OrderStateService.StaleOrderException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderStateMachine.IllegalTransitionException illegal) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    illegal.getMessage(),
                    java.util.Map.of(
                            "from", illegal.from().name(), "to", illegal.to().name()));
        }
    }

    @PostMapping("/{orderId}/cancellations")
    @RequiresCapability(value = Capability.ORDER_CANCEL, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Cancel an order, recording why and what it cost",
            description = "With a reason from the tenant's registry (ADR 0039) this is permitted "
                    + "after confirmation: the reason names the stock disposition and the liable "
                    + "party, which is exactly what ADR 0019 refused to guess at. Without one it "
                    + "is still refused once confirmed, because none of those consequences has a "
                    + "default that is safe to assume. The operator never picks the write-off: "
                    + "the dialog shows what the reason carries and cannot change it.")
    public ResponseEntity<DecisionResponse> cancel(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody CancelRequest body,
            HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var result = body.reasonId() == null
                    ? orderState.cancel(
                            tenantId,
                            orderId,
                            (int) expected,
                            body.reasonCode(),
                            "USER",
                            currentActor.get().subject(),
                            null)
                    : outcomes.cancel(
                            tenantId,
                            orderId,
                            (int) expected,
                            new OrderOutcomeService.CancelCommand(
                                    body.reasonId(),
                                    body.note(),
                                    "USER",
                                    currentActor.get().subject(),
                                    null));
            return ResponseEntity.ok(new DecisionResponse(
                    orderId, result.status().name(), result.orderVersion(), result.applied(), null, null));
        } catch (OrderStateService.StaleOrderException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderStateService.CancellationNotPermittedException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage());
        } catch (OrderOutcomeReasonService.ReasonNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @PostMapping("/{orderId}/completion")
    @RequiresCapability(value = Capability.ORDER_ADVANCE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Complete an order, naming how it was completed",
            description = "«Самовывоз выполнен» and «Доставлен сторонней службой» are different "
                    + "facts, and both the courier SLA report and the external-logistics "
                    + "settlement are built on the distinction. The reason is validated against "
                    + "the order's fulfilment mode; omitting it records the one the mode implies, "
                    + "because a dialog confirmed three hundred times a shift teaches people to "
                    + "click through dialogs.")
    public ResponseEntity<DecisionResponse> complete(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody CompleteRequest body,
            HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var result = outcomes.complete(
                    tenantId,
                    orderId,
                    (int) expected,
                    body.reasonId(),
                    "USER",
                    currentActor.get().subject(),
                    null);
            return ResponseEntity.ok(new DecisionResponse(
                    orderId, result.status().name(), result.orderVersion(), result.applied(), null, null));
        } catch (OrderStateService.StaleOrderException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderStateMachine.IllegalTransitionException illegal) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    illegal.getMessage(),
                    java.util.Map.of(
                            "from", illegal.from().name(), "to", illegal.to().name()));
        } catch (OrderOutcomeReasonService.ReasonNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    // ------------------------------------------------------------ amendments

    @PostMapping("/{orderId}/amendments")
    @RequiresCapability(value = Capability.ORDER_AMEND, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Amend a live order, appending a new revision",
            description = "An amendment is not an edit. Applying one appends a revision carrying "
                    + "its own complete total and leaves the previous one byte-identical; it "
                    + "never rewrites a revision and never creates a second order. Three of ADR "
                    + "0039's ten commands are built — the kitchen note, the callback flag and "
                    + "change-due — and the other seven are refused by name rather than "
                    + "half-performed.")
    public ResponseEntity<AmendmentResponse> amend(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody AmendRequest body,
            HttpServletRequest request) {

        requireOrderAtLocation(tenantId, orderId, locationId);
        long expected = AggregateVersion.requireIfMatch(request);
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "An amendment carries an Idempotency-Key (ADR 0031)");
        }

        try {
            var result = amendments.propose(
                    tenantId,
                    orderId,
                    new OrderAmendmentService.ProposeCommand(
                            (int) expected,
                            body.commands().stream()
                                    .map(AmendmentCommandRequest::toCommand)
                                    .toList(),
                            body.applyImmediately(),
                            idempotencyKey,
                            body.reasonCode(),
                            "USER",
                            currentActor.get().subject(),
                            null));
            return ResponseEntity.ok(AmendmentResponse.of(result));
        } catch (OrderStateService.StaleOrderException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderAmendmentService.AmendmentInProgressException held) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    held.getMessage(),
                    java.util.Map.of("amendmentId", held.amendmentId().toString()));
        } catch (OrderAmendmentService.PosExportUnacknowledgedException blocked) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, blocked.getMessage());
        } catch (OrderAmendmentService.AmendmentNotPermittedException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage());
        } catch (OrderAmendmentService.AmendmentExpiredException lapsed) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, lapsed.getMessage());
        } catch (OrderAmendmentService.CustomerConfirmationRequiredException unconfirmed) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, unconfirmed.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    @PostMapping("/{orderId}/amendments/{amendmentId}/confirmation")
    @RequiresCapability(value = Capability.ORDER_AMEND, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Record that the customer agreed to the change",
            description = "The operator attests it on the call, and the attestation carries who, "
                    + "when, and through which channel. An amendment that raises the total cannot "
                    + "commit without one: charging more than the customer agreed to is the "
                    + "failure this prevents, and the database refuses the applied row as well.")
    public ResponseEntity<Void> confirmAmendment(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @PathVariable UUID amendmentId,
            @Valid @RequestBody ConfirmAmendmentRequest body,
            HttpServletRequest request) {

        requireOrderAtLocation(tenantId, orderId, locationId);
        try {
            amendments.attestConfirmation(
                    tenantId,
                    amendmentId,
                    (int) AggregateVersion.requireIfMatch(request),
                    currentActor.get().subject(),
                    body.channel());
            return ResponseEntity.noContent().build();
        } catch (OrderStateService.StaleOrderException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderAmendmentService.AmendmentNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @DeleteMapping("/{orderId}/amendments/{amendmentId}")
    @RequiresCapability(value = Capability.ORDER_AMEND, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Withdraw an open amendment",
            description = "The row stays, marked REJECTED. It is evidence of what an operator "
                    + "tried, and deleting it would make the attempt invisible to the next person "
                    + "asked why an order looks the way it does.")
    public ResponseEntity<Void> withdrawAmendment(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @PathVariable UUID amendmentId,
            @RequestParam(defaultValue = "WITHDRAWN_BY_OPERATOR") String reasonCode) {

        requireOrderAtLocation(tenantId, orderId, locationId);
        try {
            amendments.withdraw(tenantId, amendmentId, reasonCode);
            return ResponseEntity.noContent().build();
        } catch (OrderAmendmentService.AmendmentNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @GetMapping("/{orderId}/amendments")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "Every amendment on this order, applied or not")
    public ResponseEntity<List<AmendmentResponse>> listAmendments(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {

        requireOrderAtLocation(tenantId, orderId, locationId);

        return ResponseEntity.ok(amendments.forOrder(tenantId, orderId).stream()
                .map(row -> AmendmentResponse.of(
                        row,
                        List.of(),
                        amendments.commands(tenantId, row.id()).stream()
                                .map(command -> command.commandType().name())
                                .toList()))
                .toList());
    }

    @GetMapping("/{orderId}/lines/{lineId}/note")
    @RequiresCapability(value = Capability.CUSTOMER_PII_REVEAL, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Reveal one line's customer note",
            description = "Separate from reading the order and requiring a stated purpose, "
                    + "because the note is the customer's own words about themselves (ADR 0029). "
                    + "A kitchen ticket needs it; an order list does not.")
    public ResponseEntity<NoteResponse> revealNote(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @RequestParam @NotBlank String purpose) {

        requireOrderAtLocation(tenantId, orderId, locationId);

        return ResponseEntity.ok(new NoteResponse(
                lineId,
                orderQuery.revealLineNote(tenantId, orderId, lineId, purpose).orElse(null)));
    }

    @GetMapping("/{orderId}/customer/phone")
    @RequiresCapability(value = Capability.CUSTOMER_PII_REVEAL, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Reveal the customer's phone in full",
            description = "orders.md §1.5: the detail screen shows the phone masked with no gate; "
                    + "going from masked to whole is this separate, audited call, mirroring "
                    + "GET .../lines/{lineId}/note. Copy-to-clipboard of the phone counts as a "
                    + "reveal and performs this call rather than copying an already-decrypted "
                    + "value.")
    public ResponseEntity<PhoneRevealResponse> revealPhone(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @RequestParam @NotBlank String purpose) {

        requireOrderAtLocation(tenantId, orderId, locationId);

        return ResponseEntity.ok(new PhoneRevealResponse(
                orderQuery.revealCustomerPhone(tenantId, orderId, purpose).orElse(null)));
    }

    @GetMapping("/{orderId}/customer/address")
    @RequiresCapability(value = Capability.CUSTOMER_PII_REVEAL, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Reveal the delivery address and instructions in full",
            description = "orders.md §3.8: дом, квартира, подъезд, этаж and ориентир, decrypted "
                    + "together with the coordinate that travels inside the same document. The "
                    + "detail screen shows only whether an address is on file; this is the "
                    + "capability-gated, audited call that opens it.")
    public ResponseEntity<AddressResponse> revealAddress(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @RequestParam @NotBlank String purpose) {

        requireOrderAtLocation(tenantId, orderId, locationId);

        // A Problem Details refusal (ADR 0031) rather than a bare 404: unlike
        // NoteResponse's nullable text, AddressResponse carries primitive
        // latitude/longitude, and there is no null coordinate honest enough to
        // stand in for "no address on file" — 0,0 is a real point.
        return ResponseEntity.ok(AddressResponse.of(orderQuery
                .revealCustomerAddress(tenantId, orderId, purpose)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No address on file for this order"))));
    }

    /**
     * The order exists, at this branch.
     *
     * <p>The location predicate is not decoration. An order id is a UUID a client
     * supplies, and a handler that looked it up by id alone would serve — and let
     * an operator amend — an order belonging to a branch they hold no grant over.
     */
    private void requireOrderAtLocation(UUID tenantId, UUID orderId, UUID locationId) {
        boolean atLocation = orderQuery
                .detail(tenantId, orderId)
                .filter(found -> found.order().locationId().equals(locationId))
                .isPresent();
        if (!atLocation) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }
    }

    private static void requireKnownStatus(String status) {
        try {
            OrderStatus.valueOf(status);
        } catch (IllegalArgumentException unknown) {
            // Silently dropping an unknown status would return "no orders" for a
            // typo, which reads to an operator as a quiet shift.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown order status \"%s\"".formatted(status));
        }
    }

    public record DecisionRequest(
            @NotBlank @Size(max = 64) String decisionId,
            @NotNull OrderStateService.DecisionAction action,
            @Size(max = 64) String reasonCode,
            Instant issuedAt) {}

    public record StateActionRequest(
            @NotNull OrderStatus targetStatus,
            @NotBlank @Size(max = 64) String reasonCode) {}

    /**
     * A cancellation request, with an optional registry reason and note.
     *
     * @param reasonId the tenant's cancellation reason. Supplying it is what makes
     *                 cancellation after confirmation possible, because the reason
     *                 is what decides the stock disposition and the liable party
     * @param note     the operator's own words, optional and encrypted at rest
     */
    public record CancelRequest(
            @NotBlank @Size(max = 64) String reasonCode,
            @Nullable UUID reasonId,
            @Size(max = 2000) @Nullable String note) {}

    /**
     * A completion request, naming how the order finished.
     *
     * @param reasonId omitted records the completion the fulfilment mode implies
     */
    public record CompleteRequest(@Nullable UUID reasonId) {}

    /**
     * A request to amend a live order.
     *
     * @param applyImmediately apply in the same request once priced. Refused for
     *                         anything that raises the total or needs approval, so
     *                         it can never become a way past the customer's
     *                         agreement
     */
    public record AmendRequest(
            @NotEmpty @Size(max = 10) List<AmendmentCommandRequest> commands,
            boolean applyImmediately,
            @NotBlank @Size(max = 64) String reasonCode) {}

    /**
     * One command, with only the fields its type declares.
     *
     * <p>Not a generic payload map. ADR 0039 rejects a free-form patch by name:
     * an unbounded edit has no consequence vector, and six months later nobody can
     * say whether a given save re-fiscalized, released stock, or reprinted the
     * kitchen ticket.
     */
    public record AmendmentCommandRequest(
            @NotNull AmendmentCommandType type,
            @Size(max = 1000) String kitchenNote,
            Boolean callbackRequested,
            @jakarta.validation.constraints.PositiveOrZero Long cashTenderedMinor) {

        OrderAmendmentService.AmendmentCommand toCommand() {
            return switch (type) {
                case SET_KITCHEN_NOTE -> OrderAmendmentService.AmendmentCommand.kitchenNote(kitchenNote);
                case SET_CALLBACK_REQUESTED ->
                    OrderAmendmentService.AmendmentCommand.callback(Boolean.TRUE.equals(callbackRequested));
                case SET_CASH_TENDERED -> {
                    if (cashTenderedMinor == null) {
                        throw new IllegalArgumentException(
                                "SET_CASH_TENDERED carries the amount the customer will hand " + "over, in whole som");
                    }
                    yield OrderAmendmentService.AmendmentCommand.cashTendered(cashTenderedMinor);
                }
                // Declared by ADR 0039 and not built. Refused here as well as in
                // the service, so the failure arrives before anything is written.
                default ->
                    throw new OrderAmendmentService.AmendmentNotPermittedException(
                            "%s is declared by ADR 0039 and not built in this release".formatted(type));
            };
        }
    }

    public record ConfirmAmendmentRequest(
            @NotBlank @Size(max = 24) String channel) {}

    /**
     * One amendment, as the operator's screen renders it.
     *
     * @param warnings things the operator is told and not blocked by, such as
     *                 change-due now short of the total — the customer can hand
     *                 over more, and refusing the order over a hint would be worse
     */
    public record AmendmentResponse(
            UUID amendmentId,
            UUID orderId,
            String status,
            int baseRevision,
            Integer appliedRevision,
            long deltaTotalMinor,
            boolean requiresApproval,
            String confirmationChannel,
            Instant expiresAt,
            int amendmentVersion,
            int orderVersion,
            List<String> commands,
            List<String> warnings,
            boolean replayed) {

        static AmendmentResponse of(OrderAmendmentService.AmendmentResult result) {
            return of(result.amendment(), result.warnings(), List.of())
                    .withOrderVersion(result.orderVersion(), result.replayed());
        }

        static AmendmentResponse of(
                JdbcOrderAmendmentStore.AmendmentRow row, List<String> warnings, List<String> commands) {
            return new AmendmentResponse(
                    row.id(),
                    row.orderId(),
                    row.status().name(),
                    row.baseRevision(),
                    row.appliedRevision(),
                    row.deltaTotalMinor(),
                    row.requiresApproval(),
                    row.confirmationChannel(),
                    row.expiresAt(),
                    row.version(),
                    0,
                    commands,
                    warnings,
                    false);
        }

        AmendmentResponse withOrderVersion(int version, boolean wasReplayed) {
            return new AmendmentResponse(
                    amendmentId,
                    orderId,
                    status,
                    baseRevision,
                    appliedRevision,
                    deltaTotalMinor,
                    requiresApproval,
                    confirmationChannel,
                    expiresAt,
                    amendmentVersion,
                    version,
                    commands,
                    warnings,
                    wasReplayed);
        }
    }

    /**
     * One revision, as the operator's timeline renders it.
     *
     * @param deltaTotalMinor signed, against the predecessor. This is the figure an
     *                        operator reads to a customer — «было 146 000 → 164
     *                        000» — and never only the new total
     */
    public record RevisionResponse(
            int revision,
            String source,
            @Nullable UUID amendmentId,
            String currency,
            long subtotalMinor,
            long taxMinor,
            long discountMinor,
            long feeMinor,
            long totalMinor,
            long deltaTotalMinor,
            String createdByActorType,
            @Nullable String createdByActorId,
            Instant createdAt) {

        static RevisionResponse of(JdbcOrderStore.RevisionRow row) {
            return new RevisionResponse(
                    row.revision(),
                    row.source(),
                    row.amendmentId(),
                    row.currency(),
                    row.subtotalMinor(),
                    row.taxMinor(),
                    row.discountMinor(),
                    row.feeMinor(),
                    row.totalMinor(),
                    row.deltaTotalMinor(),
                    row.createdByActorType(),
                    row.createdByActorId(),
                    row.createdAt());
        }
    }

    /**
     * The one terminal fact the order ended in.
     *
     * <p>Carries the platform category and never the tenant's internal wording:
     * «Не дозвонились» is what the operator picked from and the customer is told
     * the softened text the tenant wrote, which is a different string in a
     * different table.
     */
    public record OutcomeResponse(
            String kind,
            String systemCategory,
            @Nullable UUID reasonId,
            @Nullable Integer reasonVersion,
            String stockDisposition,
            @Nullable String liabilityParty,
            @Nullable String customerRefund,
            boolean reservationCommitted,
            Instant occurredAt) {

        static OutcomeResponse of(JdbcOrderStore.OutcomeRow row) {
            return new OutcomeResponse(
                    row.kind(),
                    row.systemCategory(),
                    row.reasonId(),
                    row.reasonVersion(),
                    row.stockDisposition(),
                    row.liabilityParty(),
                    row.customerRefund(),
                    row.reservationCommitted(),
                    row.occurredAt());
        }
    }

    /**
     * The result of an approve/reject or state-action command.
     *
     * @param applied whether this caller's command moved the order
     * @param effectiveDecisionId the decision that actually settled it, which may
     *                            be another operator's
     */
    public record DecisionResponse(
            UUID orderId,
            String status,
            int version,
            boolean applied,
            @Nullable String effectiveDecisionId,
            @Nullable String effectiveAction) {}

    /**
     * One order, as the branch's queue renders it.
     *
     * @param actions the IA 1.2 server-supplied {@code actions[]} array
     *                (orders.md §4.2): exactly what {@link OrderActionsPolicy}
     *                — the same rules {@code OrderStateService} enforces —
     *                permits for this order's status and fulfilment mode right
     *                now. The client renders this list and never computes
     *                availability itself.
     */
    public record OrderSummaryResponse(
            UUID orderId,
            String publicOrderNumber,
            String status,
            String fulfillmentMode,
            String channelCode,
            String currency,
            long totalMinor,
            int version,
            Instant createdAt,
            @Nullable Instant approvalDeadlineAt,
            List<OrderActionResponse> actions) {

        static OrderSummaryResponse of(JdbcOrderStore.OrderRow order) {
            return new OrderSummaryResponse(
                    order.orderId(),
                    order.publicOrderNumber(),
                    order.status().name(),
                    order.fulfillmentMode().name(),
                    order.channelCode(),
                    order.currency(),
                    order.totalMinor(),
                    order.version(),
                    order.createdAt(),
                    order.approvalDeadlineAt(),
                    OrderActionResponse.allFor(order.status(), order.fulfillmentMode()));
        }
    }

    /**
     * One action the console may take on this order right now.
     *
     * @param action       the code the client matches on; see {@link
     *                     uz.horecaos.platform.ordering.application.OrderActionCode}
     * @param targetStatus the status {@code ADVANCE} would move the order to.
     *                     Null for every other action
     */
    public record OrderActionResponse(String action, @Nullable String targetStatus) {

        static List<OrderActionResponse> allFor(
                OrderStatus status, uz.horecaos.platform.tenancy.api.FulfillmentMode mode) {
            return OrderActionsPolicy.availableFor(status, mode).stream()
                    .map(OrderActionResponse::of)
                    .toList();
        }

        private static OrderActionResponse of(OrderAction action) {
            return new OrderActionResponse(
                    action.code().name(),
                    action.targetStatus() == null ? null : action.targetStatus().name());
        }
    }

    /**
     * One order in full, as the branch's detail screen renders it.
     *
     * @param changeDueMinor {@code tendered − total}, recomputed on every read and
     *                       never stored. It is an operational hint: no money has
     *                       moved, and storing it would make a figure that is only
     *                       true until the next amendment look like a payment
     * @param outcome        present only once the order has ended
     * @param customer       orders.md §3.7-§3.8: name in full, phone masked,
     *                       guest-vs-account, and presence flags for the
     *                       delivery address and instructions. The raw phone
     *                       and address are never here — {@link #revealPhone}
     *                       and {@link #revealAddress} are the capability-gated
     *                       calls that return them
     */
    public record OrderDetailResponse(
            OrderSummaryResponse summary,
            long subtotalMinor,
            long taxMinor,
            String acceptanceMode,
            List<LineResponse> lines,
            List<String> warnings,
            int currentRevision,
            String createdByActorType,
            @Nullable String createdByActorId,
            @Nullable String acceptedByActorType,
            @Nullable String acceptedByActorId,
            @Nullable Instant acceptedAt,
            boolean callbackRequested,
            @Nullable Instant callbackResolvedAt,
            @Nullable String kitchenNote,
            @Nullable Long cashTenderedExpectedMinor,
            @Nullable Long changeDueMinor,
            @Nullable OutcomeResponse outcome,
            CustomerResponse customer) {

        static OrderDetailResponse of(
                OrderQueryService.OrderDetail detail, JdbcOrderStore.@Nullable OutcomeRow outcomeRow) {
            var order = detail.order();
            return new OrderDetailResponse(
                    OrderSummaryResponse.of(order),
                    order.subtotalMinor(),
                    order.taxMinor(),
                    order.acceptanceMode(),
                    lineResponses(detail),
                    detail.warnings(),
                    order.currentRevision(),
                    order.createdByActorType(),
                    order.createdByActorId(),
                    order.acceptedByActorType(),
                    order.acceptedByActorId(),
                    order.acceptedAt(),
                    order.callbackRequested(),
                    order.callbackResolvedAt(),
                    order.kitchenNote(),
                    order.cashTenderedExpectedMinor(),
                    order.cashTenderedExpectedMinor() == null
                            ? null
                            : order.cashTenderedExpectedMinor() - order.totalMinor(),
                    outcomeRow == null ? null : OutcomeResponse.of(outcomeRow),
                    CustomerResponse.of(detail.customer()));
        }

        private static List<LineResponse> lineResponses(OrderQueryService.OrderDetail detail) {
            return detail.lines().stream()
                    .map(line -> new LineResponse(
                            line.line().lineNumber(),
                            line.line().productName(),
                            line.line().variantName(),
                            line.line().sku(),
                            line.line().quantity(),
                            line.line().finalAmountMinor(),
                            line.modifiers().stream().map(m -> m.optionName()).toList(),
                            line.line().lineId(),
                            line.line().hasNote()))
                    .toList();
        }
    }

    /**
     * The order's customer, exactly as far as an ordinary {@code ORDER_READ}
     * may see it (orders.md §1.5, §3.7-§3.8).
     *
     * @param displayName             in full — never masked
     * @param phoneMasked             {@code +998 90 ••• •• 42}, or null when
     *                                there is no phone on file
     * @param customerType            {@code "ACCOUNT"} or {@code "GUEST"}
     * @param hasAddress              a delivery address is on file, for
     *                                delivery orders — revealed at {@link
     *                                #revealAddress}
     * @param hasDeliveryInstructions the customer left instructions — revealed
     *                                at the same call
     * @param anonymized              the ADR 0029 retention job has blanked the
     *                                snapshot; the panel renders "Данные удалены
     *                                по сроку хранения" and shows no reveal
     *                                control at all
     */
    public record CustomerResponse(
            @Nullable String displayName,
            @Nullable String phoneMasked,
            @Nullable String customerType,
            boolean hasAddress,
            boolean hasDeliveryInstructions,
            boolean transactionalContactAllowed,
            boolean anonymized) {

        static CustomerResponse of(OrderQueryService.CustomerDetail detail) {
            return new CustomerResponse(
                    detail.displayName(),
                    PhoneMasking.mask(detail.contactDecrypted()),
                    detail.customerType(),
                    detail.hasAddress(),
                    detail.hasDeliveryInstructions(),
                    detail.transactionalContactAllowed(),
                    detail.anonymized());
        }
    }

    /** The delivery address in full, as {@link #revealAddress} returns it. */
    public record AddressResponse(
            String line1,
            String line2,
            String city,
            String district,
            String postalCode,
            String entrance,
            String floor,
            String apartment,
            String landmark,
            double latitude,
            double longitude,
            @Nullable String deliveryInstructions) {

        static AddressResponse of(OrderQueryService.CustomerAddressReveal reveal) {
            var address = reveal.address();
            return new AddressResponse(
                    address.line1(),
                    address.line2(),
                    address.city(),
                    address.district(),
                    address.postalCode(),
                    address.entrance(),
                    address.floor(),
                    address.apartment(),
                    address.landmark(),
                    address.latitude(),
                    address.longitude(),
                    reveal.deliveryInstructions());
        }
    }

    public record PhoneRevealResponse(@Nullable String phone) {}

    /** {@code GET .../orders/counts}: the board's tab badges (orders.md §2.3). */
    public record OrderCountsResponse(
            long newOrders,
            long awaitingApproval,
            long inKitchen,
            long ready,
            long fulfilling,
            long completed,
            long cancelled,
            long totalNonTerminal,
            long total) {

        static OrderCountsResponse of(JdbcOrderStore.OrderCountsRow row) {
            return new OrderCountsResponse(
                    row.newOrders(),
                    row.awaitingApproval(),
                    row.inKitchen(),
                    row.ready(),
                    row.fulfilling(),
                    row.completed(),
                    row.cancelled(),
                    row.totalNonTerminal(),
                    row.total());
        }
    }

    /**
     * One snapshotted order line.
     *
     * @param hasNote whether the customer left a note. The text itself is
     *                personal data and is not rendered in a list; revealing it
     *                records a purpose
     */
    public record LineResponse(
            int lineNumber,
            String productName,
            String variantName,
            String sku,
            int quantity,
            long finalAmountMinor,
            List<String> modifiers,
            UUID lineId,
            boolean hasNote) {}

    public record NoteResponse(UUID lineId, @Nullable String note) {}

    public record TimelineEntryResponse(
            int sequence,
            String fromStatus,
            String toStatus,
            String trigger,
            String reasonCode,
            String actorType,
            Instant occurredAt) {

        static TimelineEntryResponse of(JdbcOrderStore.TransitionRow row) {
            return new TimelineEntryResponse(
                    row.sequenceNumber(),
                    row.fromStatus(),
                    row.toStatus(),
                    row.trigger(),
                    row.reasonCode(),
                    row.actorType(),
                    row.occurredAt());
        }
    }
}
