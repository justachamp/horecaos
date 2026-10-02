package uz.horecaos.platform.integration.marketplace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.integration.api.MarketplaceConfigurationKeys;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.BindingSummary;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.ItemRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * What each marketplace at one branch has and has not been told (ADR 0141, "When the partner
 * is down" and "When there is no push API"): the read behind the stop list's propagation
 * banner — "Yandex: 7 items not confirmed since 14:32 — update in the partner portal".
 *
 * <p>Honest in the three ways the banner needs to be:
 * <ul>
 *   <li>{@link Mode#MANUAL} — this provider has no availability write API this build can call.
 *       Nothing was pushed, nothing will be, and the operator is told to use the partner portal.
 *       Never silently "in sync".
 *   <li>{@link Mode#SUSPENDED} — the reconcile switch is off.
 *   <li>{@link Mode#AUTOMATIC} — pushes are made, and the counts say how many items the platform
 *       has not been able to confirm, and since when.
 * </ul>
 *
 * <p>Carries identifiers, counts, timestamps and stable codes only — no item name, no provider
 * response body (ADR 0029).
 */
@Service
public class MarketplacePropagationQuery {

    private static final int ITEM_SAMPLE = 20;

    private final JdbcMarketplaceAvailabilityStore store;
    private final MarketplaceAdapterRegistry adapters;
    private final ConfigurationResolver configuration;

    public MarketplacePropagationQuery(
            JdbcMarketplaceAvailabilityStore store,
            MarketplaceAdapterRegistry adapters,
            ConfigurationResolver configuration) {
        this.store = store;
        this.adapters = adapters;
        this.configuration = configuration;
    }

    public enum Mode {
        AUTOMATIC,
        MANUAL,
        SUSPENDED
    }

    public record Item(
            UUID variantId,
            boolean desiredAvailable,
            @Nullable Boolean confirmedAvailable,
            String state,
            @Nullable Instant since,
            @Nullable String lastFailureCode) {}

    public record Binding(
            UUID bindingId,
            String providerType,
            String displayName,
            Mode mode,
            int inSync,
            int pending,
            int uncertain,
            int rejectedUnmapped,
            @Nullable Instant oldestUnconfirmedSince,
            @Nullable Instant lastSuccessAt,
            @Nullable Instant lastFailureAt,
            @Nullable String lastFailureCode,
            List<Item> unconfirmedItems) {}

    @Transactional(readOnly = true)
    public List<Binding> at(UUID tenantId, UUID locationId) {
        boolean enabled = !Boolean.FALSE.equals(
                configuration.value(MarketplaceConfigurationKeys.RECONCILE_ENABLED, ResourceScope.tenant(tenantId)));
        return store.summariesAtLocation(tenantId, locationId).stream()
                .map(summary -> toBinding(tenantId, summary, enabled))
                .toList();
    }

    private Binding toBinding(UUID tenantId, BindingSummary summary, boolean enabled) {
        Mode mode = !adapters.propagates(summary.providerType())
                ? Mode.MANUAL
                : (!enabled ? Mode.SUSPENDED : Mode.AUTOMATIC);
        // Rows exist only for a binding the reconciler maintains. A MANUAL binding has none,
        // so its counts are zero by construction rather than by a claim of being in sync.
        List<Item> unconfirmed = mode == Mode.MANUAL
                ? List.of()
                : store.unconfirmedItems(tenantId, summary.bindingId(), ITEM_SAMPLE).stream()
                        .map(MarketplacePropagationQuery::toItem)
                        .toList();
        return new Binding(
                summary.bindingId(),
                summary.providerType(),
                summary.displayName(),
                mode,
                summary.inSync(),
                summary.pending(),
                summary.uncertain(),
                summary.rejected(),
                summary.oldestPendingSince(),
                summary.lastSuccessAt(),
                summary.lastFailureAt(),
                summary.lastFailureCode(),
                unconfirmed);
    }

    private static Item toItem(ItemRow row) {
        return new Item(
                row.variantId(),
                row.desiredAvailable(),
                row.confirmedAvailable(),
                row.state(),
                row.pendingSince(),
                row.lastFailureCode());
    }
}
