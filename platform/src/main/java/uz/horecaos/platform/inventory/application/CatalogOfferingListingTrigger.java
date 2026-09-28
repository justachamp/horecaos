package uz.horecaos.platform.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.catalog.api.OfferingBecameAvailable;
import uz.horecaos.platform.inventory.api.StockListingPort;

/**
 * Turning {@link OfferingBecameAvailable} into a listed, sellable stock item
 * (gap-map row 4.1's pilot-critical follow-up).
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)}, the shape
 * {@code marketing.application.LoyaltyBalanceChangeAutomationTrigger} gives
 * for {@code LoyaltyBalanceChanged} and for the same reason: listing is a
 * best-effort consequence of an authoring decision that has already
 * committed, not something the business wants atomic with it. {@code
 * StockListingPort#ensureListed} joins no caller transaction here (there is
 * none left to join, the offering write already committed), so a failure —
 * a lock timeout, a schema surprise — can only fail this listener, never the
 * product edit that triggered it. Caught and logged rather than left to
 * propagate, matching that same trigger's own per-candidate try/catch: this
 * event's publisher already returned successfully to its own caller by the
 * time this method runs, so there is no HTTP response left for an uncaught
 * exception here to turn into a 500 for a write that, from the caller's own
 * point of view, already succeeded.
 *
 * <p>A variant this leaves unlisted — the crash window between the offering's
 * commit and this method running, or any failure inside {@code
 * ensureListed} itself — is caught by the same two nets a pre-existing
 * tenant's entire backlog is: the operations backfill endpoint ({@code
 * InventoryController#backfillLocationListing}) and the product editor's own
 * "not listed at N branches" action.
 */
@Component
public class CatalogOfferingListingTrigger {

    private static final Logger log = LoggerFactory.getLogger(CatalogOfferingListingTrigger.class);

    private final StockListingPort stock;

    public CatalogOfferingListingTrigger(StockListingPort stock) {
        this.stock = stock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOfferingBecameAvailable(OfferingBecameAvailable event) {
        try {
            stock.ensureListed(event.tenantId(), event.brandId(), event.locationId(), event.variantId());
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not auto-list variant {} at location {} after its offering became available",
                    event.variantId(),
                    event.locationId(),
                    failure);
        }
    }
}
