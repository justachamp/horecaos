package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.LiveBoardQueryService;
import uz.horecaos.platform.ordering.application.OperatorCustomerLookupService;
import uz.horecaos.platform.ordering.application.OperatorOrderingService;
import uz.horecaos.platform.ordering.application.OrderAction;
import uz.horecaos.platform.ordering.application.OrderActionsPolicy;
import uz.horecaos.platform.ordering.application.OrderAmendmentService;
import uz.horecaos.platform.ordering.application.OrderBulkActionService;
import uz.horecaos.platform.ordering.application.OrderCallProvenanceService;
import uz.horecaos.platform.ordering.application.OrderCountsPeriod;
import uz.horecaos.platform.ordering.application.OrderOutcomeReasonService;
import uz.horecaos.platform.ordering.application.OrderOutcomeService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.application.RejectReasonQueryService;
import uz.horecaos.platform.ordering.domain.AmendmentCommandType;
import uz.horecaos.platform.ordering.domain.BulkActionType;
import uz.horecaos.platform.ordering.domain.BulkItemStatus;
import uz.horecaos.platform.ordering.domain.OrderDecisionChannel;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderAmendmentStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcRejectReasonStore;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.Cursor;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.idempotency.Idempotent;

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
    private final RejectReasonQueryService rejectReasons;
    private final JdbcCartStore carts;
    private final CurrentActor currentActor;
    private final AuthorizationService authorization;
    private final OrderCallProvenanceService callProvenance;
    private final OperatorOrderingService operatorOrdering;
    private final OperatorCustomerLookupService customerLookup;
    private final OrderBulkActionService bulkActions;
    private final LiveBoardQueryService liveBoard;

    /**
     * Every capability {@link OrderActionsPolicy#availableFor} reads. Computed
     * once per request (§ list/detail below) rather than once per {@link
     * AuthorizationService#has} call per row, so a queue page does not pay for
     * a grant read per order.
     */
    private static final Set<Capability> ACTIONS_POLICY_CAPABILITIES = EnumSet.of(
            Capability.ORDER_APPROVE, Capability.ORDER_ADVANCE, Capability.ORDER_CANCEL, Capability.ORDER_AMEND);

    @SuppressWarnings("checkstyle:ParameterNumber")
    public OperationsOrderController(
            OrderQueryService orderQuery,
            OrderStateService orderState,
            OrderOutcomeService outcomes,
            OrderAmendmentService amendments,
            RejectReasonQueryService rejectReasons,
            JdbcCartStore carts,
            CurrentActor currentActor,
            AuthorizationService authorization,
            OrderCallProvenanceService callProvenance,
            OperatorOrderingService operatorOrdering,
            OperatorCustomerLookupService customerLookup,
            OrderBulkActionService bulkActions,
            LiveBoardQueryService liveBoard) {
        this.orderQuery = orderQuery;
        this.orderState = orderState;
        this.outcomes = outcomes;
        this.amendments = amendments;
        this.rejectReasons = rejectReasons;
        this.carts = carts;
        this.currentActor = currentActor;
        this.authorization = authorization;
        this.callProvenance = callProvenance;
        this.operatorOrdering = operatorOrdering;
        this.customerLookup = customerLookup;
        this.bulkActions = bulkActions;
        this.liveBoard = liveBoard;
    }

    /**
     * Which of {@link #ACTIONS_POLICY_CAPABILITIES} the current principal holds
     * at this order's {@code LOCATION} scope (ADR 0025) — the input {@link
     * OrderActionsPolicy#availableFor} gates every action on. Read once per
     * request and threaded through every row (list) or the one row (detail),
     * never recomputed per order: {@link AuthorizationService#has} is a pure
     * function of the principal's already-cached grants, but there is no
     * reason to call it once per row when the scope is the same for all of
     * them.
     *
     * <p>Package-private, not {@code private}, so {@code
     * OperationsOrderControllerActionCapabilitiesTests} can call it directly
     * against a mocked {@link AuthorizationService} without constructing a
     * whole order — the same reason {@link OrderActionsPolicy#canCancel} is
     * package-private rather than {@code private}.
     */
    Set<Capability> grantedOrderActionCapabilities(UUID tenantId, UUID brandId, UUID locationId) {
        String subject = currentActor.get().subject();
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);
        EnumSet<Capability> granted = EnumSet.noneOf(Capability.class);
        for (Capability capability : ACTIONS_POLICY_CAPABILITIES) {
            if (authorization.has(subject, capability, scope)) {
                granted.add(capability);
            }
        }
        return granted;
    }

    @GetMapping
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The branch's orders, newest first (superseded by GET .../orders/board)",
            deprecated = true,
            description = "The released v1 shape: a bare array, filterable by status, capped at "
                    + "five hundred and with no way to ask for the five hundred and first. "
                    + "Frozen, not removed — it is published in v1 and callers exist. New work "
                    + "uses `GET .../orders/board` (ADR 0102), which takes the board's whole "
                    + "filter set and pages with a cursor. Both read the same rows and return "
                    + "the same `OrderSummaryResponse`, so a caller that has not moved yet still "
                    + "gains every field ADR 0102 added.")
    public ResponseEntity<List<OrderSummaryResponse>> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "100") @jakarta.validation.constraints.Max(500) int limit) {

        JdbcOrderStore.OrderListQuery query =
                boardQuery(tenantId, brandId, locationId, status, null, null, null, null, null, null, null, null);
        Set<Capability> granted = grantedOrderActionCapabilities(tenantId, brandId, locationId);
        return ResponseEntity.ok(orderQuery.forLocation(query, null, limit).stream()
                .map(row -> OrderSummaryResponse.of(row, granted))
                .toList());
    }

    @GetMapping("/board")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The order board: the branch's orders, filtered and paged",
            description = "orders.md §2.4 (ADR 0102): the board's filter set, every predicate "
                    + "applied in the database rather than in the browser. An empty status "
                    + "filter returns everything rather than silently hiding the closed ones, "
                    + "because a branch reconciling a shift needs the orders that are over as "
                    + "much as the ones that are live. `reference` narrows this branch's orders "
                    + "by the order's own number or by an aggregator's or a POS's identifier "
                    + "(ADR 0040), normalised so `0911-142`, `0911 142` and `#0911142` are one "
                    + "query — it is a filter, not the tenant-wide search of orders.md §2.8, "
                    + "which needs an endpoint at its own scope: nothing here reaches past this "
                    + "location. It is not a phone lookup either: a phone number goes in a POST "
                    + "body, never a query string (orders.md §2.8, ADR 0029). Keyset-paginated "
                    + "(ADR 0031): pass the previous page's `nextCursor` back as `cursor`. "
                    + "Changing a filter invalidates the cursor — start the list again — because "
                    + "a window cut for one filter set says nothing about another.")
    public Page<OrderSummaryResponse> board(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) @Nullable String channelCode,
            @RequestParam(required = false) @Nullable String fulfillmentMode,
            @RequestParam(required = false) @Nullable UUID courierId,
            @RequestParam(required = false) @Nullable String paymentMethodCode,
            @RequestParam(required = false) @Nullable String createdByActorId,
            @RequestParam(required = false) @Nullable String reference,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {

        JdbcOrderStore.OrderListQuery query = boardQuery(
                tenantId,
                brandId,
                locationId,
                status,
                from,
                to,
                channelCode,
                fulfillmentMode,
                courierId,
                paymentMethodCode,
                createdByActorId,
                reference);

        String filterHash = filterHashOf(query);
        @Nullable UUID cursorOrderId = null;
        if (cursor != null && !cursor.isBlank()) {
            Cursor decoded = Cursor.decodeUnsigned(cursor, filterHash)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INVALID_REQUEST,
                            "This cursor was issued for a different filter set; start the list again"));
            cursorOrderId = parseCursorOrderId(decoded.sortKey());
        }

        int pageSize = Page.limitOrDefault(limit);
        List<JdbcOrderStore.OrderBoardRow> rows;
        try {
            rows = orderQuery.forLocation(query, cursorOrderId, pageSize);
        } catch (OrderQueryService.UnknownCursorException unknown) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "This cursor does not name an order of this branch");
        }

        Set<Capability> granted = grantedOrderActionCapabilities(tenantId, brandId, locationId);
        List<OrderSummaryResponse> items =
                rows.stream().map(row -> OrderSummaryResponse.of(row, granted)).toList();

        // A short page is the end of the collection. A full one may or may not
        // be, and answering "maybe" with a cursor costs the caller one empty
        // request, where answering "no" wrongly loses them every order after it.
        String nextCursor = items.size() < pageSize
                ? null
                : new Cursor(rows.getLast().order().orderId().toString(), filterHash).encodeUnsigned();

        return new Page<>(items, nextCursor);
    }

    /** Validates the board's filter parameters and assembles the query both reads share. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static JdbcOrderStore.OrderListQuery boardQuery(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable List<String> status,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable String channelCode,
            @Nullable String fulfillmentMode,
            @Nullable UUID courierId,
            @Nullable String paymentMethodCode,
            @Nullable String createdByActorId,
            @Nullable String reference) {

        List<String> statuses = status == null ? List.of() : status;
        statuses.forEach(OperationsOrderController::requireKnownStatus);
        requireKnownFulfillmentMode(fulfillmentMode);
        requireSearchableReference(reference);

        return new JdbcOrderStore.OrderListQuery(
                tenantId,
                brandId,
                locationId,
                statuses,
                from,
                to,
                channelCode,
                fulfillmentMode == null ? null : fulfillmentMode.toUpperCase(Locale.ROOT),
                courierId,
                paymentMethodCode,
                createdByActorId,
                reference);
    }

    /**
     * The cursor's filter fingerprint, hashed so the token stays short and does
     * not restate the caller's own query back to them in a readable form.
     */
    private static String filterHashOf(JdbcOrderStore.OrderListQuery query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(query.fingerprint().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 12));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static UUID parseCursorOrderId(String sortKey) {
        try {
            return UUID.fromString(sortKey);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "This cursor is not usable; start the list again");
        }
    }

    private static void requireKnownFulfillmentMode(@Nullable String mode) {
        if (mode == null) {
            return;
        }
        try {
            FulfillmentMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // Dropping an unknown mode would answer "no orders" for a typo, which
            // reads to an operator as a branch that has stopped taking delivery.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown fulfillment mode \"%s\"".formatted(mode));
        }
    }

    /**
     * A reference that normalises to nothing must not fall through to "no
     * filter applied" ({@link JdbcOrderStore.OrderListQuery#normalisedReference()}
     * turns it into {@code null}, indistinguishable from the caller never
     * having supplied {@code reference} at all). Left unguarded, a search for
     * {@code "#"} or {@code " - "} would answer with the location's entire
     * board instead of the empty result a nonsense reference search should
     * return — the opposite of what a filter parameter promises.
     */
    private static void requireSearchableReference(@Nullable String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        if (JdbcOrderStore.normalisedExternalReference(reference) == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "This reference has nothing searchable in it");
        }
    }

    // ------------------------------------------------------- operator order intake (ADR 0039)

    @PostMapping
    @RequiresCapability(value = Capability.ORDER_PLACE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Take an order by phone",
            description = "orders.md §5: the New order screen's Создать. Reuses the ordinary "
                    + "checkout path end to end — the same cart, quote and payment rules a "
                    + "customer's own checkout takes — so this is never a second order-creation "
                    + "path to keep in step with pricing, inventory or payment. What differs is "
                    + "attribution alone: `customerAccountId` is the resolved or freshly created "
                    + "customer (`POST /customers` and `POST .../customer-lookups` beside this "
                    + "endpoint resolve it), and the order records the operator as its "
                    + "created_by_actor (V0029) rather than the customer. Cash only in this "
                    + "release — a card link sent to the customer is a bigger piece of work this "
                    + "wave does not build, and `paymentMethodCode` is refused for anything else. "
                    + "On success, route straight to GET .../orders/{orderId}: the operator is "
                    + "still on the phone and needs to read the number back.")
    public ResponseEntity<PlaceOrderResponse> place(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest body) {
        try {
            var result = operatorOrdering.place(new OperatorOrderingService.PlaceOrderCommand(
                    tenantId,
                    brandId,
                    locationId,
                    body.customerAccountId(),
                    body.channelCode(),
                    body.fulfillmentMode(),
                    body.lines().stream().map(OrderLineRequest::toLine).toList(),
                    body.destination() == null ? null : body.destination().toDestination(),
                    body.paymentMethodCode(),
                    idempotencyKey,
                    currentActor.get().subject(),
                    null));

            if (result.outcome() == CheckoutService.CheckoutResult.Outcome.REJECTED) {
                String rejectionCode =
                        Objects.requireNonNull(result.rejectionCode(), "a rejection always names a code");
                throw new ApiException(
                        StorefrontOrderingController.errorCodeFor(rejectionCode),
                        result.rejectionDetail() == null ? rejectionCode : result.rejectionDetail(),
                        Map.of(
                                "reason", rejectionCode,
                                "unavailableItems", result.unavailableItems(),
                                "warnings", result.warnings()));
            }
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new PlaceOrderResponse(
                            Objects.requireNonNull(result.orderId()),
                            Objects.requireNonNull(result.publicOrderNumber()),
                            Objects.requireNonNull(result.status()).name(),
                            result.orderVersion(),
                            result.outcome().name(),
                            result.warnings()));
        } catch (CartService.CartRefusedException refused) {
            throw StorefrontOrderingController.refusal(refused);
        } catch (CartService.StaleCartException impossible) {
            // Every version this handler passes to CartService is one it just
            // read back from the previous step in the same transaction, so a
            // concurrent editor is not a case this endpoint can reach.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, impossible.getMessage());
        } catch (CartPricingPort.PricingRefusedException unpriced) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    unpriced.getMessage(),
                    Map.of("reason", unpriced.code(), "subjectId", String.valueOf(unpriced.subjectId())));
        }
    }

    @PostMapping("/customer-lookups")
    @RequiresCapability(value = Capability.CUSTOMER_READ, scope = ScopeType.LOCATION)
    @Idempotent
    @Operation(
            summary = "Find a returning customer by phone, for the New order screen",
            description = "orders.md §5.3. A POST with the number in the body, never a query "
                    + "string, resolving through the ADR 0015 keyed hash — never a LIKE over "
                    + "plaintext. Zero, one or several results are all ordinary: the hash index "
                    + "is deliberately not unique, because a household shares a phone and a "
                    + "recycled number changes owner. Picking a result is a selection, never a "
                    + "merge. Every call is a SECURITY-class ADR 0027 audit fact, matched or not, "
                    + "because this screen is a PII surface pointed at the tenant's entire "
                    + "customer base. No match: create the account with "
                    + "POST .../tenants/{tenantId}/customers, then place the order for it.")
    public ResponseEntity<CustomerLookupResponse> customerLookup(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CustomerLookupRequest body) {

        var candidates = customerLookup.lookupByPhone(
                tenantId,
                brandId,
                locationId,
                body.phone(),
                ActorRef.user(currentActor.get().subject(), null),
                Capability.CUSTOMER_READ.code());
        return ResponseEntity.ok(new CustomerLookupResponse(
                candidates.stream().map(CustomerLookupCandidateResponse::of).toList()));
    }

    @GetMapping("/counts")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The board's tab badges and the live board's mixes, one call",
            description = "orders.md §2.3: each tab shows a live count computed before the tab's "
                    + "own filters apply, and until ADR 0045's COUNTERS signal exists this is what "
                    + "computes them. One aggregate over the location's orders, scoped identically "
                    + "to the list above. Внимание's live severity queue (late orders, stuck "
                    + "processes) is not among these — it is derived per render from the promise "
                    + "and the clock, never stored, so a count of it would be wrong five seconds "
                    + "after being cached. Two independent ways to name a period, never both at "
                    + "once: `from`/`to` are the order board's own period (ADR 0102) and window "
                    + "every one of the nine counters on when the order arrived, so a badge and "
                    + "the tab beneath it count exactly the same orders; `period` (default "
                    + "ALL_TIME, which is what this endpoint answered before either parameter "
                    + "existed) instead cuts only completed, cancelled and total to the tenant's "
                    + "own business day (ADR 0043) — completed/cancelled on when the order closed, "
                    + "total on when it arrived — never to UTC midnight, and leaves the six live "
                    + "counters uncut: an order placed before the boundary and still in the "
                    + "kitchen is still in the kitchen. Supplying `from`/`to` together with a "
                    + "`period` other than ALL_TIME is refused with 400 INVALID_REQUEST rather "
                    + "than guessed at. The two mixes are exact server-side aggregates over the "
                    + "in-progress orders and sum to totalNonTerminal, regardless of which of the "
                    + "two periods was used.")
    public ResponseEntity<OrderCountsResponse> counts(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(defaultValue = "ALL_TIME") OrderCountsPeriod period) {

        if ((from != null || to != null) && period != OrderCountsPeriod.ALL_TIME) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "`from`/`to` (the order board's own period, ADR 0102) and a `period` other "
                            + "than ALL_TIME (the live board's business day, ADR 0043) name two "
                            + "different windows; supply at most one");
        }

        var board = liveBoard.forLocation(tenantId, brandId, locationId, period, from, to);
        return ResponseEntity.ok(OrderCountsResponse.of(board, period));
    }

    @GetMapping("/drafts")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Carts started and never converted (IA 1.4)",
            description = "orders.md §6: ACTIVE, EXPIRED or ABANDONED carts with no converted "
                    + "order, newest first. This is a log, not a queue — no action here converts "
                    + "a cart into an order, because nobody agreed to that basket.")
    public ResponseEntity<List<DraftCartResponse>> drafts(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID channelId,
            @RequestParam(defaultValue = "200") @jakarta.validation.constraints.Max(500) int limit) {

        return ResponseEntity.ok(carts.listDrafts(tenantId, brandId, locationId, from, to, channelId, limit).stream()
                .map(DraftCartResponse::of)
                .toList());
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

        Set<Capability> granted = grantedOrderActionCapabilities(tenantId, brandId, locationId);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(detail.order().version()))
                .body(OrderDetailResponse.of(
                        detail, orderQuery.outcome(tenantId, orderId).orElse(null), granted));
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

    @GetMapping("/reject-reasons")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The curated list a reject dialog picks from",
            description = "Platform-owned reference data (V0119), not a tenant registry: the same "
                    + "eight reasons for every tenant, read with ORDER_READ because the reject "
                    + "dialog has to populate its picker for every operator who can reject an "
                    + "order. `labels` carries every locale at once so the client renders whichever "
                    + "one the operator is using without a second round trip.")
    public ResponseEntity<List<RejectReasonResponse>> rejectReasons(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(rejectReasons.listActive().stream()
                .map(RejectReasonResponse::of)
                .toList());
    }

    @PostMapping("/{orderId}/approval-decisions")
    @RequiresCapability(value = Capability.ORDER_APPROVE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Approve or reject an order awaiting a decision",
            description = "The first valid command wins under compare-and-set. A command that "
                    + "loses is recorded and inert, and the response reports the outcome that "
                    + "actually settled the order — so a second click gives the same answer as "
                    + "the first rather than an error. A rejection names a code from "
                    + "GET .../reject-reasons; OTHER additionally needs `note`.")
    public ResponseEntity<DecisionResponse> decide(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody DecisionRequest body) {

        if (body.action() == OrderStateService.DecisionAction.REJECT
                && (body.reasonCode() == null || body.reasonCode().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A rejection needs a reason code");
        }

        try {
            OrderStateService.DecisionResult result = body.action() == OrderStateService.DecisionAction.REJECT
                    ? outcomes.reject(
                            tenantId,
                            orderId,
                            new OrderOutcomeService.RejectCommand(
                                    body.decisionId(),
                                    body.reasonCode(),
                                    body.note(),
                                    OrderDecisionChannel.HORECAOS_OPERATIONS.name(),
                                    "USER",
                                    currentActor.get().subject(),
                                    body.issuedAt() == null ? Instant.now() : body.issuedAt(),
                                    null))
                    : orderState.decide(
                            tenantId,
                            orderId,
                            new OrderStateService.DecisionCommand(
                                    body.decisionId(),
                                    body.action(),
                                    OrderDecisionChannel.HORECAOS_OPERATIONS.name(),
                                    "USER",
                                    currentActor.get().subject(),
                                    body.reasonCode(),
                                    body.issuedAt() == null ? Instant.now() : body.issuedAt(),
                                    null,
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
        } catch (RejectReasonQueryService.UnknownRejectReasonException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
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

    @PostMapping("/{orderId}/call-provenance")
    @RequiresCapability(value = Capability.ORDER_PROVENANCE_RECORD, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Record the call this order originated from",
            description = "ADR 0064: a phone order is an ordinary operations order, and the only "
                    + "channel-specific fact about it is the call id its screen-pop card carried. "
                    + "Write-once — a second call with the same id is a harmless retry, and a "
                    + "second call with a different id is refused rather than silently "
                    + "overwriting where the order came from.")
    public ResponseEntity<Void> recordCallProvenance(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody CallProvenanceRequest body) {
        callProvenance.record(
                tenantId,
                orderId,
                body.callId(),
                ActorRef.user(currentActor.get().subject(), null),
                Capability.ORDER_PROVENANCE_RECORD.code());
        return ResponseEntity.noContent().build();
    }

    public record CallProvenanceRequest(@NotNull UUID callId) {}

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

    // ---------------------------------------------------------- bulk actions

    @PostMapping("/bulk-actions")
    @RequiresCapability(value = Capability.ORDER_BULK_ACTION, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Apply ADVANCE or CANCEL to an operator's own order selection",
            description = "ADR 0039. N independent commands under one bulk operation id, never one "
                    + "all-or-nothing transaction: each order is applied through the same "
                    + "single-order service a lone request would use, in its own transaction, so "
                    + "one already-settled order cannot fail the other hundred and ninety-nine. "
                    + "Always 202 with a per-item outcome list, capped at 200 orders. ADVANCE "
                    + "targets PREPARING, READY or FULFILLING only; CANCEL names a reason from "
                    + "GET .../reject-reasons' sibling, the tenant's outcome-reason registry, "
                    + "exactly like a single cancellation. A resubmission carrying the same "
                    + "Idempotency-Key changes nothing and returns the outcome already recorded, "
                    + "applied or failed alike — a re-run does not retry the failures.")
    public ResponseEntity<BulkActionResponse> bulkAction(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody BulkActionRequest body,
            HttpServletRequest request) {

        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "A bulk action carries an Idempotency-Key (ADR 0031)");
        }

        try {
            var result = bulkActions.apply(
                    tenantId,
                    brandId,
                    locationId,
                    new OrderBulkActionService.BulkActionCommand(
                            body.actionType(),
                            body.orders().stream()
                                    .map(ref -> new OrderBulkActionService.BulkOrderRef(
                                            ref.orderId(), ref.expectedVersion()))
                                    .toList(),
                            body.targetStatus(),
                            body.reasonCode(),
                            body.cancelReasonId(),
                            body.cancelNote(),
                            idempotencyKey,
                            "USER",
                            currentActor.get().subject()));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(BulkActionResponse.of(result));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    /**
     * @param targetStatus   required for {@code ADVANCE}: {@code PREPARING}, {@code READY} or
     *                       {@code FULFILLING}
     * @param reasonCode     required for {@code ADVANCE}, exactly like a single state action
     * @param cancelReasonId required for {@code CANCEL}, from the tenant's outcome-reason registry
     */
    public record BulkActionRequest(
            @NotNull BulkActionType actionType,
            @NotEmpty @Size(max = 200) List<BulkOrderRefRequest> orders,
            @Nullable OrderStatus targetStatus,
            @Nullable String reasonCode,
            @Nullable UUID cancelReasonId,
            @Nullable String cancelNote) {}

    public record BulkOrderRefRequest(
            @NotNull UUID orderId, @NotNull Integer expectedVersion) {}

    public record BulkActionResponse(
            UUID bulkOperationId,
            String actionType,
            int requestedCount,
            int appliedCount,
            int failedCount,
            boolean replayed,
            List<BulkActionItemResponse> items) {

        static BulkActionResponse of(OrderBulkActionService.BulkActionResult result) {
            int applied = (int) result.items().stream()
                    .filter(item -> item.itemStatus() == BulkItemStatus.APPLIED)
                    .count();
            return new BulkActionResponse(
                    result.bulkOperationId(),
                    result.actionType().name(),
                    result.requestedCount(),
                    applied,
                    result.items().size() - applied,
                    result.replayed(),
                    result.items().stream().map(BulkActionItemResponse::of).toList());
        }
    }

    public record BulkActionItemResponse(
            UUID orderId,
            String itemStatus,
            @Nullable String itemProblemCode,
            @Nullable Integer resultingOrderVersion) {

        static BulkActionItemResponse of(OrderBulkActionService.BulkItemOutcome outcome) {
            return new BulkActionItemResponse(
                    outcome.orderId(),
                    outcome.itemStatus().name(),
                    outcome.itemProblemCode(),
                    outcome.resultingOrderVersion());
        }
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
                orderQuery
                        .revealLineNote(
                                tenantId,
                                orderId,
                                lineId,
                                purpose,
                                currentActor.get().subject())
                        .orElse(null)));
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

        return ResponseEntity.ok(new PhoneRevealResponse(orderQuery
                .revealCustomerPhone(
                        tenantId, orderId, purpose, currentActor.get().subject())
                .orElse(null)));
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
                .revealCustomerAddress(
                        tenantId, orderId, purpose, currentActor.get().subject())
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

    /**
     * @param reasonCode required for a rejection, naming a code from {@code
     *                   GET .../reject-reasons} (V0119); unused for an approval
     * @param note       the operator's own words, optional and encrypted at
     *                   rest — required when the picked reason itself does
     *                   (OTHER today), ignored for an approval
     */
    public record DecisionRequest(
            @NotBlank @Size(max = 64) String decisionId,
            @NotNull OrderStateService.DecisionAction action,
            @Size(max = 64) String reasonCode,
            Instant issuedAt,
            @Size(max = 2000) @Nullable String note) {}

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

    // -------------------------------------------------- operator order intake (ADR 0039)

    /**
     * Take an order by phone (orders.md §5).
     *
     * @param customerAccountId the resolved or freshly created customer this
     *                          order is for — never taken from anywhere else.
     *                          {@code POST .../customer-lookups} finds a
     *                          returning customer and
     *                          {@code POST .../tenants/{tenantId}/customers}
     *                          creates one when there is no match
     * @param channelCode       the tenant's own operator channel (ADR 0036,
     *                          {@code system_type = CALL_CENTRE}), resolved by
     *                          the caller the same way it resolves any other
     *                          channel code — this endpoint does not invent a
     *                          channel-selection rule of its own
     * @param paymentMethodCode <strong>{@code CASH} only, this release.</strong>
     *                          A card link sent to the customer is a bigger
     *                          piece of work this wave does not build, and
     *                          this endpoint refuses anything else before it
     *                          writes a row rather than half-building a
     *                          payment path it cannot test end to end
     */
    public record PlaceOrderRequest(
            @NotNull UUID customerAccountId,
            @NotBlank @Size(max = 32) String channelCode,
            @NotNull FulfillmentMode fulfillmentMode,
            @NotEmpty @Size(max = 50) List<OrderLineRequest> lines,
            @Nullable DestinationRequest destination,
            @NotBlank @Size(max = 32) String paymentMethodCode) {}

    /** One line the operator entered into the basket, same shape as a storefront cart line. */
    public record OrderLineRequest(
            @NotNull UUID variantId,
            @Positive @Max(999) int quantity,
            @Size(max = 20) List<UUID> modifierOptionIds,
            @Size(max = 500) @Nullable String customerNote) {

        OperatorOrderingService.OrderLine toLine() {
            return new OperatorOrderingService.OrderLine(
                    variantId, quantity, modifierOptionIds == null ? List.of() : modifierOptionIds, customerNote);
        }
    }

    /**
     * Where a delivery order goes — one of the resolved customer's own saved
     * addresses, named by id, never typed ad hoc: {@code CartService
     * #setDestination}'s own doc explains why. Required exactly when {@code
     * fulfillmentMode} is {@code DELIVERY}, refused otherwise.
     */
    public record DestinationRequest(
            @NotNull UUID customerAddressId,
            @NotBlank @Size(max = 120) String recipientName,
            @NotBlank @Size(max = 32) String recipientPhone,
            @Size(max = 500) @Nullable String deliveryNote) {

        OperatorOrderingService.Destination toDestination() {
            return new OperatorOrderingService.Destination(
                    customerAddressId, recipientName, recipientPhone, deliveryNote);
        }

        /** Never prints the recipient's name or phone — see {@code DestinationRequest} elsewhere for the same rule. */
        @Override
        public String toString() {
            return "DestinationRequest[address=%s]".formatted(customerAddressId);
        }
    }

    /**
     * The placed order, exactly what the storefront's own checkout answers
     * with — the operations app routes straight to {@code GET .../{orderId}}
     * on the strength of {@code orderId} alone, because the operator is still
     * on the phone and needs to read the number back.
     *
     * @param warnings platform gaps that apply to this order, such as an unwired payments port
     */
    public record PlaceOrderResponse(
            UUID orderId,
            String publicOrderNumber,
            String status,
            int version,
            String outcome,
            List<String> warnings) {}

    /** A phone number to search, in the body — never a query string (orders.md §5.3). */
    public record CustomerLookupRequest(
            @NotBlank @Size(max = 32) String phone) {

        /** Never prints the number being searched for. */
        @Override
        public String toString() {
            return "CustomerLookupRequest[phone=<redacted>]";
        }
    }

    public record CustomerLookupResponse(List<CustomerLookupCandidateResponse> candidates) {}

    /**
     * One phone-lookup result. {@code maskedDisplayName} is enough to
     * recognise a name and not enough to read it whole — this screen is a
     * search over the tenant's entire customer base and the operator has not
     * yet picked which account the order belongs to.
     */
    public record CustomerLookupCandidateResponse(
            UUID accountId,
            @Nullable String maskedDisplayName,
            @Nullable Instant lastOrderAt,
            int recentOrderCount) {

        static CustomerLookupCandidateResponse of(OperatorCustomerLookupService.PhoneLookupCandidate candidate) {
            return new CustomerLookupCandidateResponse(
                    candidate.accountId(),
                    candidate.maskedDisplayName(),
                    candidate.lastOrderAt(),
                    candidate.recentOrderCount());
        }
    }

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
     * One curated reject reason (V0119), as the reject dialog's picker renders it.
     *
     * @param labels every locale's label at once, keyed {@code ru}/{@code
     *               uz-Latn}/{@code en} — the same shape {@code
     *               OrderOutcomeReasonController.ReasonResponse.customerTexts}
     *               already returns for the same reason
     * @param requiresNote true only for {@code OTHER} today: picking it without
     *               `note` on the decision is refused
     */
    public record RejectReasonResponse(
            String code, int displayOrder, boolean requiresNote, Map<String, String> labels) {

        static RejectReasonResponse of(JdbcRejectReasonStore.ReasonRow row) {
            return new RejectReasonResponse(row.code(), row.displayOrder(), row.requiresNote(), row.labels());
        }
    }

    /**
     * One order, as the branch's queue renders it (ADR 0102).
     *
     * <p><strong>No field here is personal data.</strong> The customer's name,
     * phone, address and notes live behind the detail read and its ADR 0029
     * reveals; this row says only whether an order belongs to an account or to a
     * guest. A field whose name suggests a person — a name, a phone, an email,
     * an address, a note, a comment — does not belong on a list that renders on
     * a screen standing open in a branch all day, and
     * {@code OrderBoardQueryTests} asserts that over this record's components
     * rather than over one instance, so a field added later fails rather than
     * ships.
     *
     * @param actions the IA 1.2 server-supplied {@code actions[]} array
     *                (orders.md §4.2): exactly what {@link OrderActionsPolicy}
     *                — the same rules {@code OrderStateService} enforces —
     *                permits for this order's status and fulfilment mode right
     *                now. The client renders this list and never computes
     *                availability itself.
     * @param promisedAt ADR 0036's promise, decided once at checkout. Null when
     *                   the basis is {@code NOT_PROMISED}; the basis is what
     *                   says which of those two a missing time means, so the
     *                   pair travels together and orders.md §2.7 derives
     *                   lateness from it at render time — nothing stores it
     * @param customerAccountId null for a guest order. Present with
     *                   {@code guestReferenceHash} it is the account/guest
     *                   discriminator the Клиент column needs to render
     *                   <em>Гость</em> without reading the encrypted snapshot
     * @param guestReferenceHash a keyed hash, never the reference itself — a
     *                   guest order is not attributable to a person from it,
     *                   the same rule {@code DraftCartResponse} already follows
     * @param processAttention {@code MANUAL_ACTION_REQUIRED} — orders.md §2.7's
     *                   {@code BLOCKED} rail — or {@code FAILED_RETRYABLE}, or
     *                   null. Absent on a summary read outside the board, where
     *                   no process state was projected: absent means "not
     *                   asked", and the detail screen reads the processes
     *                   themselves
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
            List<OrderActionResponse> actions,
            @Nullable Instant promisedAt,
            String promiseBasis,
            String paymentStatusProjection,
            @Nullable UUID customerAccountId,
            @Nullable String guestReferenceHash,
            long feeMinor,
            long discountMinor,
            @Nullable String createdByActorType,
            @Nullable String createdByActorId,
            @Nullable String acceptedByActorType,
            @Nullable String acceptedByActorId,
            @Nullable String processAttention) {

        /**
         * The summary of an order read outside the board — the detail read's own
         * header — where no process state was projected alongside it.
         */
        static OrderSummaryResponse of(JdbcOrderStore.OrderRow order, Set<Capability> grantedCapabilities) {
            return of(new JdbcOrderStore.OrderBoardRow(order, null), grantedCapabilities);
        }

        static OrderSummaryResponse of(JdbcOrderStore.OrderBoardRow row, Set<Capability> grantedCapabilities) {
            JdbcOrderStore.OrderRow order = row.order();
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
                    OrderActionResponse.allFor(order.status(), order.fulfillmentMode(), grantedCapabilities),
                    order.promise().promisedAt(),
                    order.promise().basis().name(),
                    order.paymentStatusProjection(),
                    order.customerAccountId(),
                    order.guestReferenceHash(),
                    order.feeMinor(),
                    order.discountMinor(),
                    order.createdByActorType(),
                    order.createdByActorId(),
                    order.acceptedByActorType(),
                    order.acceptedByActorId(),
                    row.processAttention());
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
    public record OrderActionResponse(
            String action, @Nullable String targetStatus) {

        static List<OrderActionResponse> allFor(
                OrderStatus status,
                uz.horecaos.platform.tenancy.api.FulfillmentMode mode,
                Set<Capability> grantedCapabilities) {
            return OrderActionsPolicy.availableFor(status, mode, grantedCapabilities).stream()
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
                OrderQueryService.OrderDetail detail,
                JdbcOrderStore.@Nullable OutcomeRow outcomeRow,
                Set<Capability> grantedCapabilities) {
            var order = detail.order();
            return new OrderDetailResponse(
                    OrderSummaryResponse.of(order, grantedCapabilities),
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

    /**
     * {@code GET .../orders/counts}: the board's tab badges (orders.md §2.3) and
     * the live board's two mixes (IA 0.1a, 0.1b).
     *
     * <p>The nine counters stay flat and keep their names, because the order
     * board's tab bar reads them by name and this wave has no business changing
     * that contract. What is new sits beside them: the period the three
     * historical counters were cut to, and the two exact mixes that replace the
     * console's truncated client-side count.
     *
     * @param periodFrom inclusive, null when {@code period} is {@code ALL_TIME}
     * @param periodTo   exclusive, null when {@code period} is {@code ALL_TIME}
     * @param sourceMix  by sales channel, largest first. Channel codes are tenant
     *                   content and are never translation keys
     * @param typeMix    by fulfilment mode, largest first
     */
    public record OrderCountsResponse(
            long newOrders,
            long awaitingApproval,
            long inKitchen,
            long ready,
            long fulfilling,
            long completed,
            long cancelled,
            long totalNonTerminal,
            long total,
            String period,
            @Nullable Instant periodFrom,
            @Nullable Instant periodTo,
            List<OrderMixSliceResponse> sourceMix,
            List<OrderMixSliceResponse> typeMix) {

        static OrderCountsResponse of(LiveBoardQueryService.LocationLiveBoard board, OrderCountsPeriod period) {
            JdbcOrderStore.OrderCountsRow row = board.counts();
            return new OrderCountsResponse(
                    row.newOrders(),
                    row.awaitingApproval(),
                    row.inKitchen(),
                    row.ready(),
                    row.fulfilling(),
                    row.completed(),
                    row.cancelled(),
                    row.totalNonTerminal(),
                    row.total(),
                    period.name(),
                    board.window().from(),
                    board.window().to(),
                    OrderMixSliceResponse.of(board.mix(), JdbcOrderStore.MixSliceRow.CHANNEL),
                    OrderMixSliceResponse.of(board.mix(), JdbcOrderStore.MixSliceRow.FULFILLMENT_MODE));
        }
    }

    /**
     * One bar of a live-board mix (IA 0.1b).
     *
     * @param key    the sales-channel code as snapshotted onto the order, or the
     *               fulfilment mode — a value, never a translation key
     * @param orders how many in-progress orders carry it. Exact: the aggregate
     *               behind it has no page and therefore no silent ceiling
     */
    public record OrderMixSliceResponse(String key, long orders) {

        static List<OrderMixSliceResponse> of(List<JdbcOrderStore.MixSliceRow> rows, String dimension) {
            return rows.stream()
                    .filter(row -> dimension.equals(row.dimension()))
                    .map(row -> new OrderMixSliceResponse(row.key(), row.orders()))
                    .toList();
        }
    }

    /**
     * One row of the drafts list (IA 1.4).
     *
     * @param customerAccountId null for a guest cart — the caller resolves an
     *                          account cart's owner through IA 5.2, never
     *                          rendered here directly
     * @param guestReferenceHash a keyed hash, never the reference itself — a
     *                           guest cart is not attributable to a person
     */
    public record DraftCartResponse(
            UUID cartId,
            Instant createdAt,
            UUID channelId,
            UUID locationId,
            @Nullable UUID customerAccountId,
            @Nullable String guestReferenceHash,
            Instant expiresAt,
            String status,
            int lineCount) {

        static DraftCartResponse of(JdbcCartStore.DraftCartRow row) {
            return new DraftCartResponse(
                    row.cartId(),
                    row.createdAt(),
                    row.channelId(),
                    row.locationId(),
                    row.customerAccountId(),
                    row.guestReferenceHash(),
                    row.expiresAt(),
                    row.status().name(),
                    row.lineCount());
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
