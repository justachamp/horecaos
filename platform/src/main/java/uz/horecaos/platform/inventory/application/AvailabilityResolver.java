package uz.horecaos.platform.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.AvailabilityDecision.Unavailable;
import uz.horecaos.platform.inventory.api.ChannelContext;
import uz.horecaos.platform.inventory.api.InventoryConfigurationKeys;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore.StockItemRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * The one reader of everything that decides whether a dish is sellable at a
 * location on a channel (ADR 0141 Decision 1).
 *
 * <pre>
 * sellable(variant, location, channel) =
 *       stocked here                                             -- an item with no stock item is unavailable
 *   AND supply_ok(variant, location)                             -- BINARY boolean | QUANTITY remaining >= requested | UNTRACKED
 *   AND NOT EXISTS a stop in force covering (variant, location, channel)
 *   AND (tracking != QUANTITY OR remaining > threshold(channel.system_type))   -- projection only
 * </pre>
 *
 * <p>The storefront menu, the cart, checkout, the operator's New order picker,
 * the stop list, the "why can't I sell this?" explainer and the marketplace
 * reconciler all reach this class (through {@code InventoryService}), so there is
 * no second notion of "available" for them to disagree about. The offering term
 * ("is it on this channel's menu at all") is catalog's and is folded in only by
 * {@link #resolveForChannel}, which a reconciler needs and the storefront render
 * does not (it composes the offering itself, then asks this class for the rest).
 *
 * <h2>Two kinds of "not sellable", kept apart</h2>
 *
 * <p><em>Supply state</em> is ADR 0017's: the {@code BINARY} boolean and the {@code
 * QUANTITY} remaining count, "we do not have it". A <em>stop</em> is new: "we, or
 * a system we trust, decided not to sell it here, there, or until then". Neither
 * replaces the other, and a stop is evaluated first, so when both are true the
 * reported reason is the one an operator can lift.
 *
 * <h2>What a stop covers</h2>
 *
 * <p>The union of every covering stop, with no "allow" override:
 * <ul>
 *   <li>{@code LOCATION} — this location, every channel;
 *   <li>{@code BRAND} — every location of the brand, including one that did not exist
 *       when the stop was made (the row holds no location, so there is nothing to go
 *       stale; the variant already belongs to exactly one brand);
 *   <li>{@code CHANNEL} — this channel, at this location or (no location on the row)
 *       everywhere it runs;
 *   <li>{@code MENU} — this {@code (location, channel)} when the menu catalog resolves
 *       for it is the stop's menu (channel-specific binding, else the branch default,
 *       else none).
 * </ul>
 * A question that names no channel is covered only by the first two: a {@code CHANNEL}
 * or {@code MENU} stop cannot be said to cover it.
 *
 * <h2>Time</h2>
 *
 * <p>A stop is in force at {@code at} when it is {@code ACTIVE} and its {@code
 * ends_at} is null or after {@code at}. The caller supplies {@code at}; nothing
 * here reads the clock, which is what lets the marketplace resync sweep evaluate an
 * expiry at its own {@code now} and restore a dish an {@code EXPIRED} sweeper never
 * got to.
 */
public class AvailabilityResolver {

    private final JdbcInventoryStore store;
    private final @Nullable JdbcAvailabilityStopStore stops;
    private final @Nullable ChannelOfferingLookup catalog;
    private final ConfigurationResolver configuration;

    public AvailabilityResolver(
            JdbcInventoryStore store,
            @Nullable JdbcAvailabilityStopStore stops,
            @Nullable ChannelOfferingLookup catalog,
            ConfigurationResolver configuration) {
        this.store = store;
        this.stops = stops;
        this.catalog = catalog;
        this.configuration = configuration;
    }

    /**
     * Supply, stops and the channel type's threshold for every requested variant at
     * the requested quantity.
     *
     * @param channel the channel the question is asked on — see {@link ChannelContext}
     *     for what each half decides
     * @param at the instant stops are evaluated at
     */
    public Evaluation evaluate(
            UUID tenantId,
            UUID locationId,
            Map<UUID, Integer> quantitiesByVariant,
            ChannelContext channel,
            Instant at) {
        Map<UUID, StockItemRow> items = store.findStockItems(tenantId, locationId, quantitiesByVariant.keySet());
        Map<UUID, List<StopRow>> covering =
                coveringStops(tenantId, locationId, quantitiesByVariant.keySet(), channel, at);

        List<Unavailable> blocked = new ArrayList<>();
        Boolean quantityLogicOn = null;

        for (Map.Entry<UUID, Integer> entry : quantitiesByVariant.entrySet()) {
            UUID variantId = entry.getKey();
            StockItemRow item = items.get(variantId);
            if (item == null) {
                blocked.add(Unavailable.notStocked(variantId));
                continue;
            }
            if (!covering.getOrDefault(variantId, List.of()).isEmpty()) {
                // A stop is an explicit instruction and is refused at cart and
                // checkout, where a threshold only hides. Reported ahead of supply:
                // it is the specific fact, and the one an operator can lift.
                blocked.add(Unavailable.onStop(variantId));
                continue;
            }
            switch (item.trackingMode()) {
                case UNTRACKED -> {
                    // Unlimited. The catalog offering still decides whether it is
                    // shown at all, so untracked is not the same as always visible.
                }
                case BINARY -> {
                    if (!Boolean.TRUE.equals(item.binaryAvailable())) {
                        blocked.add(Unavailable.soldOut(variantId));
                    }
                }
                case QUANTITY -> {
                    // Resolved at most once per call, not once per item: every
                    // QUANTITY item in one request shares the same tenant.
                    if (quantityLogicOn == null) {
                        quantityLogicOn = useStockLogicEnabled(tenantId);
                    }
                    if (!quantityLogicOn) {
                        // catalog.use_stock_logic is off: this item behaves exactly
                        // like UNTRACKED (TrackingMode.QUANTITY's own doc).
                        continue;
                    }
                    BigDecimal requested = BigDecimal.valueOf(entry.getValue());
                    BigDecimal remaining = item.remainingQuantity();
                    if (remaining.compareTo(requested) < 0) {
                        blocked.add(Unavailable.soldOut(variantId));
                        continue;
                    }
                    if (channel.systemType() != null) {
                        store.findChannelStopThreshold(tenantId, item.stockItemId(), channel.systemType())
                                .filter(threshold -> remaining.compareTo(threshold) <= 0)
                                .ifPresent(threshold -> blocked.add(Unavailable.channelStopped(variantId)));
                    }
                }
            }
        }

        AvailabilityDecision decision =
                blocked.isEmpty() ? AvailabilityDecision.allAvailable() : AvailabilityDecision.blockedBy(blocked);
        return new Evaluation(decision, covering);
    }

    /**
     * The stops in force that cover each variant at this location on this channel.
     * Every covering stop is returned, not the first, so the explainer can list them.
     */
    public Map<UUID, List<StopRow>> coveringStops(
            UUID tenantId, UUID locationId, Set<UUID> variantIds, ChannelContext channel, Instant at) {
        if (stops == null || variantIds.isEmpty()) {
            return Map.of();
        }
        List<StopRow> inForce = stops.activeForVariants(tenantId, variantIds, at);
        if (inForce.isEmpty()) {
            return Map.of();
        }
        // The menu lookup is made at most once per brand, and only if a MENU stop is
        // present at all: a tenant with no MENU stops pays nothing for the scope.
        Map<UUID, Optional<UUID>> menuByBrand = new HashMap<>();
        Map<UUID, List<StopRow>> covering = new LinkedHashMap<>();
        for (StopRow stop : inForce) {
            if (covers(stop, locationId, channel, menuByBrand, tenantId)) {
                covering.computeIfAbsent(stop.variantId(), key -> new ArrayList<>())
                        .add(stop);
            }
        }
        return covering;
    }

    private boolean covers(
            StopRow stop,
            UUID locationId,
            ChannelContext channel,
            Map<UUID, Optional<UUID>> menuByBrand,
            UUID tenantId) {
        UUID channelId = channel.channelId();
        return switch (stop.scopeType()) {
            case LOCATION -> locationId.equals(stop.locationId());
            case BRAND -> true;
            case CHANNEL ->
                channelId != null
                        && channelId.equals(stop.channelId())
                        && (stop.locationId() == null || locationId.equals(stop.locationId()));
            case MENU -> {
                if (channelId == null || catalog == null) {
                    yield false;
                }
                Optional<UUID> menu = menuByBrand.computeIfAbsent(
                        stop.brandId(), brand -> catalog.menuBoundTo(tenantId, brand, locationId, channelId));
                yield menu.isPresent() && menu.get().equals(stop.menuId());
            }
            // Refused at write; a row cannot exist. Never "covers" if one ever did.
            case TERMINAL -> false;
        };
    }

    /**
     * Sellability for a marketplace reconciler: the catalog's offering, then the
     * same supply, stop and threshold composition as every other reader, for one
     * channel at one location, at quantity one each.
     */
    public Map<UUID, ChannelResolution> resolveForChannel(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            @Nullable String systemType,
            Set<UUID> variantIds,
            Instant at) {
        Map<UUID, Integer> quantityOfOneEach = new HashMap<>();
        variantIds.forEach(variantId -> quantityOfOneEach.put(variantId, 1));
        Evaluation evaluation =
                evaluate(tenantId, locationId, quantityOfOneEach, new ChannelContext(channelId, systemType), at);
        Set<UUID> offered = catalog == null
                ? variantIds
                : catalog.offeredVariants(tenantId, brandId, locationId, channelId, variantIds, at);

        Map<UUID, List<String>> reasons = new HashMap<>();
        evaluation
                .decision()
                .unavailableItems()
                .forEach(item -> reasons.computeIfAbsent(item.variantId(), key -> new ArrayList<>())
                        .add(item.reason()));

        Map<UUID, ChannelResolution> result = new LinkedHashMap<>();
        for (UUID variantId : variantIds) {
            List<String> why = new ArrayList<>(reasons.getOrDefault(variantId, List.of()));
            if (!offered.contains(variantId)) {
                why.add("NOT_OFFERED");
            }
            result.put(
                    variantId,
                    new ChannelResolution(
                            why.isEmpty(), why, evaluation.covering().getOrDefault(variantId, List.of())));
        }
        return result;
    }

    private boolean useStockLogicEnabled(UUID tenantId) {
        Boolean enabled =
                configuration.value(InventoryConfigurationKeys.CATALOG_USE_STOCK_LOGIC, ResourceScope.tenant(tenantId));
        return Boolean.TRUE.equals(enabled);
    }

    /** The supply/stop/threshold decision, plus which stops cover which variant. */
    public record Evaluation(AvailabilityDecision decision, Map<UUID, List<StopRow>> covering) {}

    /** One variant on one channel: sellable, why not, and the stops that cover it. */
    public record ChannelResolution(boolean sellable, List<String> reasons, List<StopRow> coveringStops) {}

    /** Whether any stop in a list stops the dish on every channel (so the dish is simply on stop here). */
    public static boolean coversEveryChannel(StopRow stop) {
        return stop.scopeType() == StopScopeType.LOCATION || stop.scopeType() == StopScopeType.BRAND;
    }
}
