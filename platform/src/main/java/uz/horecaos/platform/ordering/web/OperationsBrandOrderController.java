package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.LiveBoardQueryService;
import uz.horecaos.platform.ordering.application.OrderCountsPeriod;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.web.OperationsOrderController.OrderMixSliceResponse;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The brand's order counters, for the Home live board (IA 0.1, 0.1c).
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
 * <p>Read-only, so no {@code Idempotency-Key} and no expected version: there is
 * nothing here to replay.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/orders")
@Tag(name = "Operations brand order counters", description = "The brand's live board counters (IA 0.1)")
public class OperationsBrandOrderController {

    private final LiveBoardQueryService liveBoard;

    public OperationsBrandOrderController(LiveBoardQueryService liveBoard) {
        this.liveBoard = liveBoard;
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

        static BrandOrderCountsResponse of(LiveBoardQueryService.BrandLiveBoard board, OrderCountsPeriod period) {
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
