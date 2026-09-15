package uz.horecaos.platform.ordering.web;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.LiveBoardQueryService;
import uz.horecaos.platform.ordering.application.OrderCountsPeriod;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.SnapshotSource;
import uz.horecaos.platform.telemetry.api.StreamChannel;

/**
 * The ADR 0045 {@code COUNTERS} channel's payload (wave P08).
 *
 * <p>{@code ordering} implements this published {@code telemetry.api} port
 * rather than telemetry querying {@code ordering} directly — the same
 * "inward port" shape {@code SnapshotSource}'s own doc now describes, and the
 * one that keeps the module dependency a single edge ({@code ordering} on
 * {@code telemetry.api}) instead of a cycle.
 *
 * <p>Reuses {@link OperationsOrderController.OrderCountsResponse} and {@link
 * OperationsBrandOrderController.BrandOrderCountsResponse} verbatim rather than
 * inventing a third shape for the same nine counters: a stream and a poll that
 * could disagree about what they mean is worse than one contract read from two
 * places, which is also what {@code CountersSnapshotClassificationTests}
 * checks — this source's payload is exactly an existing, already-classified
 * HTTP response body, never a new shape of its own.
 *
 * <p>Always {@link OrderCountsPeriod#ALL_TIME}: a stream subscription's scope
 * key carries no {@code period} query parameter to answer with, and {@code
 * ALL_TIME} is what {@code GET .../orders/counts} itself answered before
 * {@code period} existed — the same default a client that has not yet chosen a
 * period sees on first poll.
 */
@Component
public class OrderCountsSnapshotSource implements SnapshotSource {

    private final LiveBoardQueryService liveBoard;

    public OrderCountsSnapshotSource(LiveBoardQueryService liveBoard) {
        this.liveBoard = liveBoard;
    }

    @Override
    public StreamChannel channel() {
        return StreamChannel.COUNTERS;
    }

    @Override
    public Optional<Object> snapshot(UUID tenantId, ScopeKey scopeKey) {
        if (scopeKey.type() == ScopeType.LOCATION) {
            LiveBoardQueryService.LocationLiveBoard board =
                    liveBoard.locationCounts(tenantId, scopeKey.id(), OrderCountsPeriod.ALL_TIME);
            return Optional.of(OperationsOrderController.OrderCountsResponse.of(board, OrderCountsPeriod.ALL_TIME));
        }
        if (scopeKey.type() == ScopeType.BRAND) {
            LiveBoardQueryService.BrandLiveBoard board =
                    liveBoard.forBrand(tenantId, scopeKey.id(), OrderCountsPeriod.ALL_TIME);
            return Optional.of(
                    OperationsBrandOrderController.BrandOrderCountsResponse.of(board, OrderCountsPeriod.ALL_TIME));
        }
        // StreamChannel.COUNTERS declares only LOCATION and BRAND as
        // subscribable, so OperationsStreamController never resolves a
        // subscription to a third scope type here; defensive, not reachable.
        return Optional.empty();
    }
}
