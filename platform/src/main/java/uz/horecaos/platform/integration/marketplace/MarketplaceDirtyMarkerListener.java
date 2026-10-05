package uz.horecaos.platform.integration.marketplace;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.catalog.api.ChannelAssortmentChanged;
import uz.horecaos.platform.inventory.api.InventoryStopChanged;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.tenancy.api.SalesChannelInstallationChanged;

/**
 * Asks the marketplace reconciler for an early sweep when an input its resolver reads has just
 * changed (ADR 0141 Decision 7, "Markers, an accelerator").
 *
 * <p>In the same transaction as the change ({@code BEFORE_COMMIT}, the pattern {@code
 * InventoryOutboxEventListener} uses), so the marker and the fact commit together: a marker
 * that outlived a rolled-back stop would sweep for nothing, and a stop that committed without
 * its marker would wait for the next resync. It marks the bindings the change can reach — a
 * {@code LOCATION} stop or a position toggle the bindings of that location, anything wider the
 * bindings of the whole brand — and does nothing else; the sweep recomputes through the
 * resolver, so a marker never carries a value.
 *
 * <p>The list of inputs it listens to is deliberately not the correctness argument: a stop
 * made, lifted or expired, a position toggled, an offering switched, a channel exclusion
 * added or removed, a menu bound or unbound and the installation a sales channel is backed by
 * (or whether that channel is active) each ask for an early sweep. The item mapping itself is
 * marked inside the database, by the trigger {@code V0487} puts on {@code
 * integration.provider_entity_mappings}, because its writers are the mapping pane, a menu
 * sync that does not exist yet and whatever an adapter brings, and a list of writers to remember
 * is exactly what cannot be the correctness argument. An item's sale window and a stop's end
 * passing publish nothing at all, and the resync sweep picks them up within one interval
 * regardless. A missed marker costs a delay and never prevents a correction.
 */
@Component
public class MarketplaceDirtyMarkerListener {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceDirtyMarkerListener.class);

    private final JdbcMarketplaceAvailabilityStore store;
    private final java.time.Clock clock;

    public MarketplaceDirtyMarkerListener(JdbcMarketplaceAvailabilityStore store, java.time.Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onStopChanged(InventoryStopChanged event) {
        UUID locationId = event.scopeType() == StopScopeType.LOCATION ? event.locationId() : null;
        // A CHANNEL stop at one branch also names exactly one location.
        if (event.scopeType() == StopScopeType.CHANNEL && event.locationId() != null) {
            locationId = event.locationId();
        }
        mark(event.tenantId(), event.brandId(), locationId);
    }

    /** An offering switched, a channel exclusion added or removed, a menu bound or unbound. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAssortmentChanged(ChannelAssortmentChanged event) {
        mark(event.tenantId(), event.brandId(), event.locationId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAvailabilityChanged(ItemAvailabilityChanged event) {
        mark(event.tenantId(), event.brandId(), event.locationId());
    }

    /**
     * A sales channel was pointed at an installation, away from one, paused, reopened or retired
     * (ADR 0036): every binding of the installations it touches resolves its channel again.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onChannelInstallationChanged(SalesChannelInstallationChanged event) {
        try {
            for (UUID installationId : event.installationIds()) {
                List<UUID> bindings = store.bindingIdsOfInstallation(event.tenantId(), installationId);
                if (!bindings.isEmpty()) {
                    store.requestSweep(event.tenantId(), bindings, clock.instant());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Could not mark marketplace bindings for an early availability sweep", failure);
        }
    }

    private void mark(UUID tenantId, UUID brandId, @Nullable UUID locationId) {
        try {
            List<UUID> bindings = store.bindingIdsOf(tenantId, brandId, locationId);
            if (!bindings.isEmpty()) {
                store.requestSweep(tenantId, bindings, clock.instant());
            }
        } catch (RuntimeException failure) {
            // An accelerator must never block the change it accelerates. The resync sweep
            // converges the partner within one interval whether or not this marker landed.
            log.warn("Could not mark marketplace bindings for an early availability sweep", failure);
        }
    }
}
