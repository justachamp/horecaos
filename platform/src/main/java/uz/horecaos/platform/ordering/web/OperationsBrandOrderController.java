package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.LiveBoardQueryService;
import uz.horecaos.platform.ordering.application.OrderCountsPeriod;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.web.OperationsOrderController.MarketplaceBindingsResponse;
import uz.horecaos.platform.ordering.web.OperationsOrderController.OrderMixSliceResponse;
import uz.horecaos.platform.ordering.web.OperationsOrderController.OrderSummaryResponse;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The brand's order counters, for the Home live board (IA 0.1, 0.1c), and the
 * order board across the brand's branches (row 1.1, wave 16).
 *
 * <p>A sibling of {@link OperationsOrderController} rather than a method on it,
 * because the scope is genuinely different: everything on that controller is at
 * {@code LOCATION} scope and its class doc says why, while this read is about a
 * brand and its branches together and cannot be narrower than the question.
 *
 * <p><strong>Why it exists at all.</strong> The live board's branch leaderboard
 * was built from one {@code GET .../locations/{id}/orders/counts} per active
 * branch, refreshed every ten seconds: a ten-branch tenant paid eleven requests
 * a tick to render one table, and the cost grew with the customer. This answers
 * the whole board — the brand's totals, a row per branch, and both mixes — in
 * one request whose cost does not.
 *
 * <p><strong>It refuses a location-scoped principal, by design.</strong>
 * {@code ORDER_READ} at {@code BRAND} scope is not satisfied by a grant at one
 * location (ADR 0025: scopes cover downwards, never up), so a branch manager
 * asking this endpoint gets 403 rather than a sight of their neighbours'
 * volumes. The console treats that 403 as a routing answer, not an error, and
 * falls back to the location-scoped counts for its own branch.
 *
 * <p><strong>The brand board is the branch board with a wider set of branches.</strong>
 * {@code GET .../orders/board} reads through the same {@link OrderQueryService}
 * and the same {@code JdbcOrderStore.listForLocation} statement as the branch
 * board — {@link OrderBoardFilters} owns the validation and the paging for both —
 * and differs in exactly two ways: it needs {@code ORDER_READ} at {@code BRAND}
 * scope, and the branches it reads are the {@code locationId} values the caller
 * narrows to, or every branch of the brand. Each row carries its {@code
 * locationId}, and its {@code actions[]} are computed from what the caller holds
 * <em>at that row's branch</em>: a brand-level grant covers every branch, but the
 * array is asked per branch on the page rather than assumed uniform.
 *
 * <p>Read-only, so no {@code Idempotency-Key} and no expected version: there is
 * nothing here to replay.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/orders")
@Tag(name = "Operations brand order counters", description = "The brand's live board counters (IA 0.1)")
public class OperationsBrandOrderController {

    private final LiveBoardQueryService liveBoard;
    private final OrderQueryService orderQuery;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;

    public OperationsBrandOrderController(
            LiveBoardQueryService liveBoard,
            OrderQueryService orderQuery,
            AuthorizationService authorization,
            CurrentActor currentActor) {
        this.liveBoard = liveBoard;
        this.orderQuery = orderQuery;
        this.authorization = authorization;
        this.currentActor = currentActor;
    }

    @GetMapping("/counts")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's counters, its branch leaderboard and its two mixes, in one read",
            description = "IA 0.1's live board, served brand-wide so the console stops issuing one "
                    + "counts request per branch per tick. `period` cuts completed, cancelled and "
                    + "total to the tenant's own business day (ADR 0043) rather than to UTC "
                    + "midnight, and defaults to ALL_TIME. The six live counters are never cut: an "
                    + "order placed before the boundary and still in the kitchen is still in the "
                    + "kitchen. Locations carries a row only for a branch that has taken an order "
                    + "in scope — the caller holds the brand roster it needs the display names "
                    + "from anyway, and renders an absent branch as zero. ORDER_READ at BRAND "
                    + "scope: a location-scoped grant is refused.")
    public ResponseEntity<BrandOrderCountsResponse> counts(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(defaultValue = "ALL_TIME") OrderCountsPeriod period) {

        return ResponseEntity.ok(BrandOrderCountsResponse.of(liveBoard.forBrand(tenantId, brandId, period), period));
    }

    @GetMapping("/board")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The order board across the brand's branches: filtered and paged",
            description = "orders.md §2.4/§2.5 (ADR 0102), the «Все филиалы» mode: the branch "
                    + "board's whole filter set, and the same statement, over every branch of "
                    + "the brand — or over the `locationId` values given (repeat the parameter "
                    + "for several; the Филиал filter). A branch of another brand or tenant "
                    + "matches nothing, exactly as on the branch board. Each row carries its "
                    + "`locationId`, and `actions[]` is computed from the caller's grants at that "
                    + "row's branch. `ORDER_READ` at BRAND scope: a location-scoped grant is "
                    + "refused with 403 — the console's signal to keep its single-branch board — "
                    + "and a caller who needs only some branches asks the branch endpoint for "
                    + "each. Keyset-paginated (ADR 0031); changing a filter or the branch set "
                    + "invalidates the cursor.")
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Page<OrderSummaryResponse> board(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable List<UUID> locationId,
            @RequestParam(required = false) @Nullable List<String> status,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) @Nullable String channelCode,
            @RequestParam(required = false) @Nullable String fulfillmentMode,
            @RequestParam(required = false) @Nullable UUID courierId,
            @RequestParam(required = false) @Nullable String paymentMethodCode,
            @RequestParam(required = false) @Nullable String createdByActorId,
            @RequestParam(required = false) @Nullable String reference,
            @RequestParam(required = false) @Nullable String origin,
            @RequestParam(required = false) @Nullable String paymentStatus,
            @RequestParam(required = false) @Nullable UUID marketplaceBindingId,
            @RequestParam(required = false) @Nullable Boolean late,
            @RequestParam(required = false) @Nullable Boolean problem,
            @RequestParam(required = false) @Nullable Boolean callbackRequested,
            @RequestParam(required = false) @Nullable List<String> fiscalStatus,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {

        JdbcOrderStore.OrderListQuery query = OrderBoardFilters.query(
                tenantId,
                brandId,
                locationId == null ? List.of() : locationId,
                status,
                from,
                to,
                channelCode,
                fulfillmentMode,
                courierId,
                paymentMethodCode,
                createdByActorId,
                reference,
                origin,
                paymentStatus,
                marketplaceBindingId,
                late,
                problem,
                callbackRequested,
                fiscalStatus);

        String subject = currentActor.get().subject();
        boolean invoiceCapability = OperationsOrderController.invoiceCapabilityHeld(authorization, subject, tenantId);
        return OrderBoardFilters.page(orderQuery, query, cursor, limit, branch -> {
            Set<Capability> granted = OperationsOrderController.grantedActionCapabilities(
                    authorization, subject, tenantId, brandId, branch);
            return OperationsOrderController.withInvoiceCapability(granted, invoiceCapability);
        });
    }

    @GetMapping("/marketplace-bindings")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The aggregator bindings the brand's orders arrived through",
            description = "The options behind the board's «Агрегатор» filter in the «Все филиалы» mode "
                    + "(orders.md §2.4, ADR 0040): one entry per provider binding that has an order "
                    + "at any of the brand's branches (or at the `locationId` values given), most "
                    + "recently used first. ORDER_READ at BRAND scope.")
    public MarketplaceBindingsResponse marketplaceBindings(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable List<UUID> locationId) {
        return MarketplaceBindingsResponse.of(
                orderQuery.marketplaceBindings(tenantId, brandId, locationId == null ? List.of() : locationId));
    }

    /**
     * {@code GET .../brands/{brandId}/orders/counts}: the live board, whole.
     *
     * @param periodFrom inclusive, null when {@code period} is {@code ALL_TIME}
     * @param periodTo   exclusive, null when {@code period} is {@code ALL_TIME}
     * @param totals     the brand's own aggregate, read independently rather than
     *                   summed from {@code locations}
     * @param locations  one row per branch that has an order in scope, busiest
     *                   first by live load
     * @param sourceMix  by sales channel, largest first
     * @param typeMix    by fulfilment mode, largest first
     */
    public record BrandOrderCountsResponse(
            String period,
            @Nullable Instant periodFrom,
            @Nullable Instant periodTo,
            OrderCountTotalsResponse totals,
            List<BranchOrderCountsResponse> locations,
            List<OrderMixSliceResponse> sourceMix,
            List<OrderMixSliceResponse> typeMix) {

        // public: the ADR 0045 COUNTERS snapshot source (ordering.infrastructure.realtime)
        // reuses this mapping at BRAND scope rather than duplicating it.
        public static BrandOrderCountsResponse of(
                LiveBoardQueryService.BrandLiveBoard board, OrderCountsPeriod period) {
            List<BranchOrderCountsResponse> branches = board.locations().stream()
                    .map(row ->
                            new BranchOrderCountsResponse(row.locationId(), OrderCountTotalsResponse.of(row.counts())))
                    .sorted((left, right) -> Long.compare(
                            right.counts().totalNonTerminal(), left.counts().totalNonTerminal()))
                    .toList();

            return new BrandOrderCountsResponse(
                    period.name(),
                    board.window().from(),
                    board.window().to(),
                    OrderCountTotalsResponse.of(board.totals()),
                    branches,
                    OrderMixSliceResponse.of(board.mix(), JdbcOrderStore.MixSliceRow.CHANNEL),
                    OrderMixSliceResponse.of(board.mix(), JdbcOrderStore.MixSliceRow.FULFILLMENT_MODE));
        }
    }

    /** One branch's row of the leaderboard. Identifiers only — no branch name, which tenancy owns. */
    public record BranchOrderCountsResponse(UUID locationId, OrderCountTotalsResponse counts) {}

    /**
     * The nine badges as a block.
     *
     * <p>The same nine fields {@code OrderCountsResponse} carries flat. Kept as
     * its own record here rather than reusing that one, because that record also
     * carries the period and the mixes, which on this response belong to the
     * brand and not to each branch — embedding it would put a period field on
     * every row and invite a reader to believe the rows could differ.
     */
    public record OrderCountTotalsResponse(
            long newOrders,
            long awaitingApproval,
            long inKitchen,
            long ready,
            long fulfilling,
            long completed,
            long cancelled,
            long totalNonTerminal,
            long total) {

        static OrderCountTotalsResponse of(JdbcOrderStore.OrderCountsRow row) {
            return new OrderCountTotalsResponse(
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
}
