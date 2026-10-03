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
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * What each marketplace at one branch has and has not been told (ADR 0141, "When the partner
 * is down" and "When there is no push API"): the read behind the stop list's propagation
 * banner — "Yandex: 7 items not confirmed since 14:32 — update in the partner portal".
 *
 * <p>Honest in the three ways the banner needs to be:
 * <ul>
 *   <li>{@link Mode#MANUAL} — the platform is not pushing to this marketplace. Nothing was pushed,
 *       nothing will be, and the operator is told to use the partner portal. Never silently "in
 *       sync". The {@link Reason} says why: the provider has no availability write API this build
 *       can call, or the reconciler cannot act on this binding (its installation is not active, no
 *       single sales channel is backed by it) or has nothing to act on (no mapped item has been
 *       synchronised).
 *   <li>{@link Mode#SUSPENDED} — the reconcile switch is off.
 *   <li>{@link Mode#AUTOMATIC} — pushes are made, and the counts say how many items the platform
 *       has not been able to confirm, and since when.
 * </ul>
 *
 * <p>{@code AUTOMATIC} is therefore claimed only when the reconciler would act on the binding:
 * the same conditions its worklist and its sweep apply, read here from the same data.
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
    private final SalesChannelLookup channels;

    public MarketplacePropagationQuery(
            JdbcMarketplaceAvailabilityStore store,
            MarketplaceAdapterRegistry adapters,
            ConfigurationResolver configuration,
            SalesChannelLookup channels) {
        this.store = store;
        this.adapters = adapters;
        this.configuration = configuration;
        this.channels = channels;
    }

    public enum Mode {
        AUTOMATIC,
        MANUAL,
        SUSPENDED
    }

    /** Why a binding is {@link Mode#MANUAL}: stable codes, never a provider's words (ADR 0029). */
    public enum Reason {
        /** The provider has no availability write adapter in this build. */
        NO_ADAPTER,
        /** The installation is not {@code ACTIVE}, so the reconciler's worklist leaves the binding out. */
        INSTALLATION_INACTIVE,
        /** No single active sales channel is backed by the installation (none, or more than one). */
        CHANNEL_UNRESOLVED,
        /** The reconciler keeps no item for the binding: nothing is mapped, or it has not swept yet. */
        NO_ITEMS_TRACKED
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
            @Nullable Reason reason,
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
        Reason reason = null;
        Mode mode;
        if (!adapters.propagates(summary.providerType())) {
            reason = Reason.NO_ADAPTER;
            mode = Mode.MANUAL;
        } else if (!enabled) {
            mode = Mode.SUSPENDED;
        } else {
            reason = reconcilerCannotAct(tenantId, summary);
            mode = reason == null ? Mode.AUTOMATIC : Mode.MANUAL;
        }
        // Rows exist only for a binding the reconciler maintains. A MANUAL binding has none (or
        // none it still looks after), so its counts are zero by construction rather than by a
        // claim of being in sync.
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
                reason,
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

    /**
     * The reason the reconciler is not acting on a binding whose provider has an adapter and whose
     * switch is on, or null when it is. Each case is a place {@code
     * MarketplaceAvailabilityReconciler} skips a binding or sweeps it to nothing: an installation
     * that is not {@code ACTIVE} (the worklist query leaves it out), no single channel backing the
     * installation (the sweep records zero items), and no item row at all.
     */
    private @Nullable Reason reconcilerCannotAct(UUID tenantId, BindingSummary summary) {
        if (!summary.installationActive()) {
            return Reason.INSTALLATION_INACTIVE;
        }
        if (channels.byProviderInstallation(tenantId, summary.installationId()).isEmpty()) {
            return Reason.CHANNEL_UNRESOLVED;
        }
        if (summary.inSync() + summary.pending() + summary.uncertain() + summary.rejected() == 0) {
            return Reason.NO_ITEMS_TRACKED;
        }
        return null;
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
