package uz.horecaos.platform.integration.marketplace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        SUSPENDED,
        /**
         * The binding rehearses (ADR 0141 Phase 3, {@code marketplace.availability.dry_run}): the platform has
         * computed what it would send and recorded it, and has sent nothing. {@code wouldPush} counts the dishes.
         */
        DRY_RUN
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
            @Nullable String lastFailureCode,
            // Dry run: what the rehearsal would have sent for this dish, and when it decided; null otherwise.
            @Nullable Boolean wouldPushAvailable,
            @Nullable Instant wouldPushAt) {}

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
            List<Item> unconfirmedItems,
            // Dry run: how many dishes the rehearsal has recorded as "would push" and not yet sent.
            int wouldPush,
            // ADR 0180 Decision 1: since when a stale-channel incident is open on this binding, or null. It closes
            // by itself when the binding recovers or stops being worked, so a value here is an incident that is open.
            @Nullable Instant incidentOpenSince) {}

    @Transactional(readOnly = true)
    public List<Binding> at(UUID tenantId, UUID locationId) {
        boolean enabled = reconcileEnabled(tenantId);
        return store.summariesAtLocation(tenantId, locationId).stream()
                .map(summary -> toBinding(tenantId, locationId, summary, enabled))
                .toList();
    }

    /** One decision of a dry run: the dish, the partner's reference for it, and what it would be set to. */
    public record WouldPush(
            UUID variantId,
            String externalItemReference,
            boolean wouldPushAvailable,
            Instant wouldPushAt,
            @Nullable Boolean confirmedAvailable) {}

    /** A page of a dry run's decisions; {@code nextCursor} is null on the last. */
    public record WouldPushPage(
            List<WouldPush> items, @Nullable String nextCursor) {}

    /**
     * Every dish a dry-run binding would push, a page at a time, ordered by the partner's item reference: the
     * list behind the propagation read's {@code wouldPush} count, which is this list's size and uses the same
     * predicate. The sample on {@link Binding#unconfirmedItems()} stays a sample.
     *
     * <p>Empty for a binding that is not rehearsing: what a rehearsal left in a row is not a decision the
     * platform is about to make, and the read says {@code wouldPush} is zero there too.
     *
     * @param after the partner reference of the last dish already seen, or null for the first page
     * @return empty when the binding is not an active marketplace binding of this branch, which is the same
     *     answer as "it does not exist" and deliberately so
     */
    @Transactional(readOnly = true)
    public Optional<WouldPushPage> wouldPush(
            UUID tenantId, UUID locationId, UUID bindingId, @Nullable String after, int limit) {
        if (!store.bindingIsAtLocation(tenantId, locationId, bindingId)) {
            return Optional.empty();
        }
        boolean rehearsing = store.summariesAtLocation(tenantId, locationId).stream()
                .filter(summary -> summary.bindingId().equals(bindingId))
                .anyMatch(summary -> standing(tenantId, locationId, summary, reconcileEnabled(tenantId))
                                .mode()
                        == Mode.DRY_RUN);
        if (!rehearsing) {
            return Optional.of(new WouldPushPage(List.of(), null));
        }
        List<JdbcMarketplaceAvailabilityStore.WouldPushRow> rows =
                store.wouldPush(tenantId, bindingId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<WouldPush> items = rows.stream()
                .limit(limit)
                .map(row -> new WouldPush(
                        row.variantId(),
                        row.externalEntityId(),
                        row.wouldPushAvailable(),
                        row.wouldPushAt(),
                        row.confirmedAvailable()))
                .toList();
        return Optional.of(
                new WouldPushPage(items, more ? items.get(items.size() - 1).externalItemReference() : null));
    }

    private boolean reconcileEnabled(UUID tenantId) {
        return !Boolean.FALSE.equals(
                configuration.value(MarketplaceConfigurationKeys.RECONCILE_ENABLED, ResourceScope.tenant(tenantId)));
    }

    /** How the platform stands towards one binding right now, and why it is not pushing when it is not. */
    private record Standing(Mode mode, @Nullable Reason reason) {}

    private Standing standing(UUID tenantId, UUID locationId, BindingSummary summary, boolean enabled) {
        if (!adapters.propagates(summary.providerType())) {
            return new Standing(Mode.MANUAL, Reason.NO_ADAPTER);
        }
        if (!enabled) {
            return new Standing(Mode.SUSPENDED, null);
        }
        Reason reason = reconcilerCannotAct(tenantId, summary);
        if (reason != null) {
            return new Standing(Mode.MANUAL, reason);
        }
        return new Standing(dryRun(tenantId, locationId) ? Mode.DRY_RUN : Mode.AUTOMATIC, null);
    }

    private Binding toBinding(UUID tenantId, UUID locationId, BindingSummary summary, boolean enabled) {
        Standing standing = standing(tenantId, locationId, summary, enabled);
        Mode mode = standing.mode();
        Reason reason = standing.reason();
        // Rows exist only for a binding the reconciler maintains. A MANUAL binding has none (or
        // none it still looks after), so its counts are zero by construction rather than by a
        // claim of being in sync.
        List<Item> unconfirmed = mode == Mode.MANUAL
                ? List.of()
                : store.unconfirmedItems(tenantId, summary.bindingId(), ITEM_SAMPLE).stream()
                        .map(row -> toItem(row, mode == Mode.DRY_RUN))
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
                unconfirmed,
                mode == Mode.DRY_RUN ? summary.wouldPush() : 0,
                // The incident is open only while the platform is actually pushing to this binding and failing to be
                // believed; a binding that is manual, suspended or rehearsing has none (the mark is cleared there).
                mode == Mode.AUTOMATIC ? summary.staleAlertedAt() : null);
    }

    /** Whether the binding at this branch rehearses. The branch's brand is not needed: a location value outranks. */
    private boolean dryRun(UUID tenantId, UUID locationId) {
        return store.brandOfLocation(tenantId, locationId)
                .map(brandId -> Boolean.TRUE.equals(configuration.value(
                        MarketplaceConfigurationKeys.DRY_RUN, ResourceScope.location(tenantId, brandId, locationId))))
                .orElse(false);
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

    /** The recorded decision belongs to a rehearsal, so it is shown only while the binding is rehearsing. */
    private static Item toItem(ItemRow row, boolean rehearsing) {
        return new Item(
                row.variantId(),
                row.desiredAvailable(),
                row.confirmedAvailable(),
                row.state(),
                row.pendingSince(),
                row.lastFailureCode(),
                rehearsing ? row.wouldPushAvailable() : null,
                rehearsing ? row.wouldPushAt() : null);
    }
}
