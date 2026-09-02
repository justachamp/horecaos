package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * Read-side answers about a brand's pricing (ADR 0018): the price book list
 * screen, and resolving what a set of variants or modifier options currently
 * cost at a location.
 *
 * <p>Separate from {@link PriceAuthoringService} the way {@code
 * CatalogQueryService} is separate from {@code CatalogAuthoringService} in the
 * catalog module — a query service reads what authoring wrote, and neither
 * needs the other's concerns: authoring enforces lifecycle and optimistic
 * locking, queries never do.
 */
@Service
@Transactional(readOnly = true)
public class PriceQueryService {

    private final JdbcPricingStore store;
    private final SalesChannelLookup channels;
    private final Clock clock;

    public PriceQueryService(JdbcPricingStore store, SalesChannelLookup channels, Clock clock) {
        this.store = store;
        this.channels = channels;
        this.clock = clock;
    }

    /** A brand's price books, ranked the way {@code resolvePriceBook} ranks them. */
    public List<PriceBookSummary> priceBooks(UUID tenantId, UUID brandId) {
        return store.priceBooksForBrand(tenantId, brandId).stream()
                .map(row -> new PriceBookSummary(
                        row.id(),
                        row.name(),
                        row.currency(),
                        row.status(),
                        row.priority(),
                        row.validFrom(),
                        row.validUntil(),
                        row.version()))
                .toList();
    }

    /**
     * Resolves which price book applies at a scope right now, then reads current
     * amounts for the requested priceables from it.
     *
     * <p>Composes two already-existing store reads with no new SQL: {@code
     * resolvePriceBook} is exactly the lookup a quote performs, and {@code
     * pricesFor} is exactly the amount lookup a quote line performs, so this
     * answers "what would a cart pay right now" without pricing an actual cart.
     *
     * <p>A brand with no price book yet resolves to nothing, and that is a real,
     * displayable state rather than a refusal — a brand's first day, before an
     * operator has authored anything, per {@code PriceAuthoringService}'s own
     * Javadoc on the same gap.
     *
     * <p>{@code channelId} is resolved to its price plane exactly the way {@link
     * QuoteService} resolves one for an actual cart (ADR 0036):
     * {@code resolvePriceBook}'s own Javadoc requires the <em>price plane</em>
     * channel id, not the channel itself, and a caller-supplied channel that does
     * not exist for this tenant resolves to no channel — matching a cart's own
     * "an unregistered channel prices against nothing rather than a default" rule
     * — rather than throwing.
     */
    public ResolvedPrices resolvePrices(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID channelId,
            PriceableType type,
            Set<UUID> priceableIds) {
        Instant at = clock.instant();
        UUID pricingChannelId = channelId == null
                ? null
                : channels.pricingChannelId(tenantId, channelId).orElse(null);
        Optional<JdbcPricingStore.PriceBookRow> resolved =
                store.resolvePriceBook(tenantId, brandId, locationId, pricingChannelId, at);
        if (resolved.isEmpty()) {
            return new ResolvedPrices(null, null, Map.of());
        }
        JdbcPricingStore.PriceBookRow book = resolved.get();
        Map<UUID, Long> amounts = store.pricesFor(book.id(), type.name(), priceableIds, at);
        return new ResolvedPrices(book.id(), book.currency(), amounts);
    }

    public record PriceBookSummary(
            UUID priceBookId,
            String name,
            String currency,
            String status,
            int priority,
            Instant validFrom,
            @Nullable Instant validUntil,
            int version) {}

    public record ResolvedPrices(
            @Nullable UUID priceBookId, @Nullable String currency, Map<UUID, Long> amountsMinor) {}
}
