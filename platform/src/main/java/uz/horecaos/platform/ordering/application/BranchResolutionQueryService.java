package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort.DeliveryBranchMatch;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort.PickupBranchCandidate;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Gap map rows {@code 1.3} and {@code 0.1c}: "who can even take this order,
 * right now" — the New Order screen's «Филиал», resolved for real instead of
 * always answering with the operator's own current branch.
 *
 * <p>Three reads, composed, never a fourth implementation of any of them:
 *
 * <ul>
 *   <li>{@link BranchResolutionPort} (ADR 0037) says which branches a point
 *       falls inside a live {@code DELIVERY} zone for, ranked by the identical
 *       priority/area/id order {@code DeliveryFeeResolver} would apply if
 *       checkout later priced this address at that branch.
 *   <li>{@link ServiceabilityResolver} (ADR 0036) says whether each candidate
 *       is open for this channel and mode right now, and its preparation band.
 *   <li>{@link JdbcOrderStore#countsByLocation} is the exact brand-scoped live
 *       count the branch leaderboard (row {@code 0.1c}) already reads once per
 *       tick — reused here rather than a second per-branch counts query, so
 *       "how busy is this branch" answers identically on the Home board and on
 *       this screen.
 * </ul>
 *
 * <p>Every candidate is returned, closed ones included: an operator deciding
 * where to send a call needs to see that the resolver's own top pick is
 * closed, not have it silently disappear from the list. {@link
 * BranchResolution#proposedLocationId} is the first candidate in ranked order
 * that is currently open; if none is, it falls back to the top-ranked
 * candidate anyway, so the screen always has something to propose and a
 * reason to show for it.
 */
@Service
public class BranchResolutionQueryService {

    private final BranchResolutionPort branches;
    private final ServiceabilityResolver serviceability;
    private final SalesChannelLookup channels;
    private final JdbcOrderStore orders;
    private final Clock clock;

    public BranchResolutionQueryService(
            BranchResolutionPort branches,
            ServiceabilityResolver serviceability,
            SalesChannelLookup channels,
            JdbcOrderStore orders,
            Clock clock) {
        this.branches = branches;
        this.serviceability = serviceability;
        this.channels = channels;
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BranchResolution resolve(
            UUID tenantId, UUID brandId, FulfillmentMode mode, @Nullable GeoPoint point, String channelCode) {

        if (mode == FulfillmentMode.DELIVERY && point == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A DELIVERY branch resolution needs a coordinate to match zones against");
        }
        if (mode == FulfillmentMode.DINE_IN) {
            // Dine-in is seated at whichever branch the operator is already
            // standing in (the floor plan, ADR 0047) — there is no address and
            // no cross-branch question to resolve, and answering one here would
            // be a second, unasked-for opinion about a branch the caller never
            // named.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "DINE_IN has no cross-branch resolution: it is always the current branch");
        }

        Instant now = clock.instant();
        UUID channelId =
                channels.byCode(tenantId, channelCode).map(SalesChannel::id).orElse(null);
        Map<UUID, Long> loadByLocation = loadByLocation(tenantId, brandId);

        List<BranchCandidate> candidates = mode == FulfillmentMode.DELIVERY
                ? deliveryCandidates(
                        tenantId,
                        brandId,
                        // Refused above when null for DELIVERY; restated here
                        // because NullAway cannot see that cross-statement
                        // guarantee.
                        Objects.requireNonNull(point, "A DELIVERY resolution always carries a point here"),
                        now,
                        channelId,
                        mode,
                        loadByLocation)
                : pickupCandidates(tenantId, brandId, now, channelId, mode, loadByLocation);

        UUID proposed = candidates.stream()
                .filter(BranchCandidate::available)
                .findFirst()
                .or(() -> candidates.stream().findFirst())
                .map(BranchCandidate::locationId)
                .orElse(null);

        return new BranchResolution(candidates, proposed);
    }

    private List<BranchCandidate> deliveryCandidates(
            UUID tenantId,
            UUID brandId,
            GeoPoint point,
            Instant now,
            @Nullable UUID channelId,
            FulfillmentMode mode,
            Map<UUID, Long> loadByLocation) {

        List<DeliveryBranchMatch> matches = branches.deliveryCandidates(tenantId, brandId, point, now);
        // ADR 0037's own three-key order, restated here because
        // BranchResolutionPort.DeliveryBranchMatch crosses the module boundary
        // and cannot carry uz.horecaos.platform.fulfillment.domain.zone
        // .ZoneCandidate#RANKING itself across it — see that port's own doc.
        Comparator<DeliveryBranchMatch> ranking = Comparator.comparingInt(DeliveryBranchMatch::zonePriority)
                .reversed()
                .thenComparingDouble(DeliveryBranchMatch::zoneAreaSquareMeters)
                .thenComparing(DeliveryBranchMatch::zoneId);

        return matches.stream()
                .sorted(ranking)
                .map(match -> {
                    Serviceability answer = resolve(tenantId, brandId, match.locationId(), channelId, mode, now);
                    return new BranchCandidate(
                            match.locationId(),
                            match.displayName(),
                            answer.available(),
                            answer.reason() == null ? null : answer.reason().name(),
                            answer.preparationMinutes(),
                            loadByLocation.getOrDefault(match.locationId(), 0L),
                            match.zoneId(),
                            match.zonePriority(),
                            match.zoneAreaSquareMeters());
                })
                .toList();
    }

    private List<BranchCandidate> pickupCandidates(
            UUID tenantId,
            UUID brandId,
            Instant now,
            @Nullable UUID channelId,
            FulfillmentMode mode,
            Map<UUID, Long> loadByLocation) {

        List<PickupBranchCandidate> candidates = branches.pickupCandidates(tenantId, brandId);
        // No zone to rank by (row 1.3's own note: a PICKUP order has no
        // address). Ranked by current load ascending instead — the least busy
        // branch is the honest "best" answer when there is no geography to
        // decide it — then by name, for a stable order two branches of equal
        // load do not otherwise have.
        Comparator<PickupBranchCandidate> ranking = Comparator.comparingLong(
                        (PickupBranchCandidate candidate) -> loadByLocation.getOrDefault(candidate.locationId(), 0L))
                .thenComparing(PickupBranchCandidate::displayName);

        return candidates.stream()
                .sorted(ranking)
                .map(candidate -> {
                    Serviceability answer = resolve(tenantId, brandId, candidate.locationId(), channelId, mode, now);
                    return new BranchCandidate(
                            candidate.locationId(),
                            candidate.displayName(),
                            answer.available(),
                            answer.reason() == null ? null : answer.reason().name(),
                            answer.preparationMinutes(),
                            loadByLocation.getOrDefault(candidate.locationId(), 0L),
                            null,
                            null,
                            null);
                })
                .toList();
    }

    private Serviceability resolve(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId, FulfillmentMode mode, Instant now) {
        if (channelId == null) {
            // The same graceful answer ServiceabilityController gives a
            // customer following a stale link: a channel code naming no row is
            // CHANNEL_NOT_ENABLED rather than a fault this read throws over.
            return Serviceability.refused(
                    uz.horecaos.platform.tenancy.api.ServiceabilityReason.CHANNEL_NOT_ENABLED, null, false);
        }
        return serviceability.resolve(tenantId, brandId, locationId, channelId, mode, now);
    }

    private Map<UUID, Long> loadByLocation(UUID tenantId, UUID brandId) {
        // total_non_terminal is a current-status snapshot, not windowed by
        // created_at/closed_at (JdbcOrderStore's own COUNT_COLUMNS), so from/to
        // is irrelevant here and null/null is correct rather than an unused
        // window this read would otherwise have to invent one for.
        return orders.countsByLocation(tenantId, brandId, null, null).stream()
                .collect(java.util.stream.Collectors.toMap(
                        JdbcOrderStore.LocationCountsRow::locationId,
                        row -> row.counts().totalNonTerminal()));
    }

    /** @param proposedLocationId null only when {@code candidates} is empty */
    public record BranchResolution(
            List<BranchCandidate> candidates, @Nullable UUID proposedLocationId) {}

    /**
     * @param zoneId               the winning DELIVERY zone, null for a PICKUP candidate
     * @param zonePriority         ADR 0037's first ranking key, null for a PICKUP candidate
     * @param zoneAreaSquareMeters ADR 0037's second ranking key, null for a PICKUP candidate
     */
    public record BranchCandidate(
            UUID locationId,
            String displayName,
            boolean available,
            @Nullable String reason,
            @Nullable Integer preparationMinutes,
            long activeOrderCount,
            @Nullable UUID zoneId,
            @Nullable Integer zonePriority,
            @Nullable Double zoneAreaSquareMeters) {}
}
