package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.MarketplaceBindingLookup;
import uz.horecaos.platform.ordering.api.MarketplaceBindingLookup.BindingLabel;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessThresholds;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.Lateness;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.LatenessThreshold;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.MarketplaceBindingUsage;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderListQuery;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The two small read models the order board's filters need that the order row
 * alone cannot answer (gap map rows {@code 1.1c}, orders.md §2.4).
 *
 * <p><strong>Lateness.</strong> «Только опаздывающие» is derived, never stored
 * (§2.7): an order is late when it is not over and the clock has passed its
 * promise plus a grace, or — with no promise — its creation plus a fallback. The
 * grace and the fallback are policy (ADR 0030, {@code ordering.lateness}),
 * resolved per branch and per fulfilment mode, and a brand-wide board spans
 * branches that may each override them. So the thresholds are resolved once per
 * request for exactly the branches the board can show — the requested ones, or
 * every branch of the brand that holds an order still in play — and handed to
 * the one board statement, which applies them in the database. The board's
 * paging depends on the predicate being in the statement: a lateness filter
 * applied to a page after it was cut would make a short page mean "none of
 * these were late" instead of "no more orders".
 *
 * <p>The resolution reads the same {@link OrderLatenessPolicyService} the boards
 * and {@code GET .../{orderId}/lateness} read, so the toolbar's filter and a
 * row's late tint are one answer and cannot drift. The rule itself is {@link
 * OrderLatenessPolicy#evaluate}'s, restated as a predicate: {@code
 * OrderBoardTogglesQueryTests#theLateFilterAgreesWithTheDomainRule} holds the two
 * together, and is the only thing that does -- change either side and that test
 * is the one to read first.
 *
 * <p><strong>Aggregator bindings.</strong> Which provider bindings the orders in
 * scope arrived through, with the name integration gives each. Ordering owns the
 * counts ({@code ordering.orders.marketplace_binding_id}, V0038); the name comes
 * through {@link MarketplaceBindingLookup}, the same seam manual aggregator
 * entry uses, so ordering still names no integration table.
 */
@Service
public class OrderBoardReadModels {

    private final JdbcOrderStore orders;
    private final OrderLatenessPolicyService latenessPolicies;
    private final MarketplaceBindingLookup bindings;
    private final Clock clock;

    public OrderBoardReadModels(
            JdbcOrderStore orders,
            OrderLatenessPolicyService latenessPolicies,
            MarketplaceBindingLookup bindings,
            Clock clock) {
        this.orders = orders;
        this.latenessPolicies = latenessPolicies;
        this.bindings = bindings;
        this.clock = clock;
    }

    /**
     * {@code query}, with its lateness thresholds resolved when it asks for the
     * late-only filter, and unchanged otherwise.
     */
    @Transactional(readOnly = true)
    public OrderListQuery withLatenessResolved(OrderListQuery query) {
        if (!query.lateOnly()) {
            return query;
        }
        Set<UUID> locations = new LinkedHashSet<>(query.locationIds());
        if (locations.isEmpty()) {
            locations.addAll(orders.locationsWithOpenOrders(query.tenantId(), query.brandId()));
        }
        List<LatenessThreshold> thresholds = new ArrayList<>();
        for (UUID locationId : locations) {
            OrderLatenessPolicy policy = latenessPolicies
                    .resolve(query.tenantId(), query.brandId(), locationId)
                    .policy();
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                LatenessThresholds forMode = policy.forMode(mode);
                thresholds.add(new LatenessThreshold(
                        locationId, mode, forMode.lateAfterSeconds(), forMode.noPromiseFallbackSeconds()));
            }
        }
        return query.withLateness(new Lateness(clock.instant(), thresholds));
    }

    /**
     * The aggregator bindings the orders in scope arrived through, most recently
     * used first, named where integration knows the name.
     *
     * @param locationIds the branches in scope, or empty for the whole brand
     */
    @Transactional(readOnly = true)
    public List<MarketplaceBindingOption> marketplaceBindings(UUID tenantId, UUID brandId, List<UUID> locationIds) {
        List<MarketplaceBindingUsage> usage = orders.marketplaceBindingUsage(tenantId, brandId, locationIds);
        if (usage.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = usage.stream().map(MarketplaceBindingUsage::bindingId).collect(Collectors.toSet());
        Map<UUID, BindingLabel> labels = bindings.labelsOf(tenantId, ids);
        return usage.stream()
                .map(row -> {
                    @Nullable BindingLabel label = labels.get(row.bindingId());
                    return new MarketplaceBindingOption(
                            row.bindingId(),
                            label == null ? null : label.providerType(),
                            label == null ? null : label.displayName(),
                            row.orderCount(),
                            row.lastOrderAt());
                })
                .toList();
    }

    /**
     * @param providerType the aggregator's catalogue code, or null when
     *                     integration no longer resolves the binding
     * @param displayName  what the tenant called the installation, or null with
     *                     {@code providerType}
     */
    public record MarketplaceBindingOption(
            UUID bindingId,
            @Nullable String providerType,
            @Nullable String displayName,
            long orderCount,
            Instant lastOrderAt) {}
}
