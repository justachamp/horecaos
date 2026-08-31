package uz.horecaos.platform.inventory.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.AvailabilityDecision.Unavailable;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.inventory.api.ReservationResult;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore.StockItemRow;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;

/**
 * Binary availability and the reservation path (ADR 0017).
 *
 * <p>The first cutover slice tracks nothing numerically. A kitchen marks a dish
 * available or not; there is no portion count to oversell. That keeps the
 * reservation path honest about what it guarantees: a hold means "this was
 * available when the cart was priced and nobody has marked it out since", not
 * "one portion is set aside for you".
 */
@Service
public class InventoryService implements InventoryReservationPort {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    /**
     * Matches the ADR 0018 quote TTL. A hold outliving its quote would keep stock
     * back for a price nobody can still accept.
     */
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private static final String OWNER_QUOTE = "QUOTE";

    private final JdbcInventoryStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public InventoryService(JdbcInventoryStore store, ApplicationEventPublisher events, Clock clock) {
        this.store = store;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public UUID listVariantAtLocation(UUID tenantId, UUID brandId, UUID locationId, UUID variantId, TrackingMode mode) {
        if (mode == TrackingMode.QUANTITY) {
            throw new UnsupportedTrackingModeException(mode);
        }
        return store.createStockItem(tenantId, brandId, locationId, variantId, mode, clock.instant());
    }

    /**
     * Whether every item in a cart can be fulfilled here.
     *
     * <p>An item with no stock record is unavailable rather than available. The
     * opposite default would let a location sell anything on the brand's menu
     * simply because nobody had listed it, which is how a kitchen receives an
     * order for a dish it does not make.
     */
    @Override
    @Transactional(readOnly = true)
    public AvailabilityDecision checkAvailability(UUID tenantId, UUID locationId, Set<UUID> variantIds) {

        Map<UUID, StockItemRow> items = store.findStockItems(tenantId, locationId, variantIds);
        List<Unavailable> blocked = new ArrayList<>();

        for (UUID variantId : variantIds) {
            StockItemRow item = items.get(variantId);
            if (item == null) {
                blocked.add(Unavailable.notStocked(variantId));
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
                case QUANTITY -> throw new UnsupportedTrackingModeException(item.trackingMode());
            }
        }

        return blocked.isEmpty() ? AvailabilityDecision.allAvailable() : AvailabilityDecision.blockedBy(blocked);
    }

    /** A kitchen marking a dish sold out, or back on. */
    @Transactional
    public void setAvailability(
            UUID tenantId,
            UUID locationId,
            UUID variantId,
            boolean available,
            String reasonCode,
            @Nullable UUID actorId) {

        StockItemRow item = store.findStockItem(tenantId, locationId, variantId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Variant " + variantId + " is not stocked at this location"));

        if (item.trackingMode() != TrackingMode.BINARY) {
            throw new IllegalStateException(
                    "Availability can only be set on a BINARY item; this one is " + item.trackingMode());
        }

        if (Boolean.valueOf(available).equals(item.binaryAvailable())) {
            // Already in this state, so nothing happened and nothing is recorded.
            // A movement per repeated tap would fill the ledger with events that
            // changed nothing and bury the ones that did.
            log.debug(
                    "Variant {} at location {} is already {}",
                    variantId,
                    locationId,
                    available ? "available" : "unavailable");
            return;
        }

        // Unique per real transition: the position sequence advances only when a
        // state actually changes, so a genuine flip back is never swallowed as a
        // duplicate while a concurrent repeat of this same transition is.
        String idempotencyKey = "avail:%s:%s:%d".formatted(variantId, available, item.positionSequence());

        store.setBinaryAvailability(
                tenantId,
                item.stockItemId(),
                available,
                idempotencyKey,
                reasonCode,
                actorId == null ? "SERVICE" : "USER",
                actorId,
                clock.instant());

        log.info("Variant {} at location {} marked {}", variantId, locationId, available ? "available" : "unavailable");

        // ADR 0058's operations trigger, gated in notifications on the
        // available->false direction alone ("an item 86'd") — this event
        // fires for both directions of the toggle because the fact itself
        // ("availability changed") is symmetric, and item.brandId() is
        // already in hand from the findStockItem read above, so there is
        // nothing to gain by publishing only one of the two.
        events.publishEvent(new ItemAvailabilityChanged(
                UUID.randomUUID(),
                tenantId,
                item.brandId(),
                locationId,
                variantId,
                available,
                reasonCode,
                clock.instant()));
    }

    /**
     * Holds a cart's items against a quote.
     *
     * <p>Availability is checked and the hold taken in one transaction, so a dish
     * marked sold out between the check and the hold cannot slip through.
     *
     * @return a hold, or the reason it was refused
     */
    @Override
    @Transactional
    public ReservationResult reserveForQuote(
            UUID tenantId, UUID brandId, UUID locationId, UUID quoteId, Map<UUID, Integer> quantitiesByVariant) {

        // ADR 0024 forbids a historical import from changing inventory, and this
        // is the movement it means: an order from 2021 holding stock a branch is
        // selling today.
        //
        // Refused, not skipped, and the two available no-ops are why. Returning a
        // hold would hand back a reservation id for nothing, and the commit that
        // follows would record a sale against stock that was never held; returning
        // a refusal would tell the import the dish was sold out and quarantine a
        // perfectly good order for a reason that is not true. Neither is a
        // truthful answer, so there is none — an import that reaches here has run
        // the live checkout path instead of importing a snapshot.
        ImportSuppression.refuse(ExternalEffect.INVENTORY_MOVEMENT, "reserve stock for a quote");

        AvailabilityDecision decision = checkAvailability(tenantId, locationId, quantitiesByVariant.keySet());
        if (!decision.available()) {
            return ReservationResult.refused(decision);
        }

        Instant now = clock.instant();
        UUID reservationId = UUID.randomUUID();

        boolean created = store.insertReservation(
                reservationId, tenantId, brandId, locationId, OWNER_QUOTE, quoteId, now.plus(RESERVATION_TTL), now);

        if (!created) {
            // A reservation row already exists for this quote. The uniqueness
            // constraint is on the owner regardless of status, so this row may be
            // a live hold, or one that was released, committed, or swept as
            // expired.
            var existing = store.findReservation(tenantId, OWNER_QUOTE, quoteId)
                    .orElseThrow(() -> new IllegalStateException("Reservation vanished mid-transaction"));

            if (!"HELD".equals(existing.status()) || !existing.expiresAt().isAfter(now)) {
                // Reporting this as a hold was the original behaviour and it was
                // wrong in the way that matters: checkout would believe it held
                // stock, create an order, and the later commit would quietly
                // return false with nothing reserved. A lapsed hold is a refusal,
                // and the customer re-prices rather than being sold a promise
                // inventory never made.
                log.info("Reservation for quote {} is {} and cannot be reused", quoteId, existing.status());
                return ReservationResult.refused(AvailabilityDecision.blockedBy(quantitiesByVariant.keySet().stream()
                        .map(Unavailable::holdExpired)
                        .toList()));
            }

            // A live hold for this quote. Returning it rather than failing keeps a
            // retried checkout idempotent instead of leaving the customer unable
            // to proceed.
            return ReservationResult.held(existing.id(), existing.expiresAt());
        }

        Map<UUID, StockItemRow> items = store.findStockItems(tenantId, locationId, quantitiesByVariant.keySet());
        quantitiesByVariant.forEach((variantId, quantity) -> {
            StockItemRow item = items.get(variantId);
            if (item == null) {
                // checkAvailability just confirmed every one of these variants has
                // a stock item at this location, inside the same transaction; a
                // null here means the two reads disagreed, which is a bug in the
                // store or a genuine race, not an ordinary refusal to swallow.
                throw new IllegalStateException("Stock item vanished mid-transaction for variant " + variantId);
            }
            store.insertReservationLine(reservationId, tenantId, item.stockItemId(), BigDecimal.valueOf(quantity));
        });

        return ReservationResult.held(reservationId, now.plus(RESERVATION_TTL));
    }

    /** Turns a hold into a committed sale when an order is confirmed. */
    @Override
    @Transactional
    public boolean commit(UUID tenantId, UUID quoteId) {
        // Unreachable while reserveForQuote refuses, and guarded anyway: false
        // here means "there was no hold to commit", which an import would read as
        // an ordinary lapsed reservation rather than as a suppression.
        ImportSuppression.refuse(ExternalEffect.INVENTORY_MOVEMENT, "commit a stock reservation");

        var reservation = store.findReservation(tenantId, OWNER_QUOTE, quoteId);
        return reservation.isPresent()
                && store.transitionReservation(tenantId, reservation.get().id(), "COMMITTED", clock.instant());
    }

    /** Frees a hold when a cart is abandoned or a checkout fails. */
    @Override
    @Transactional
    public boolean release(UUID tenantId, UUID quoteId) {
        ImportSuppression.refuse(ExternalEffect.INVENTORY_MOVEMENT, "release a stock reservation");

        var reservation = store.findReservation(tenantId, OWNER_QUOTE, quoteId);
        return reservation.isPresent()
                && store.transitionReservation(tenantId, reservation.get().id(), "RELEASED", clock.instant());
    }

    /** Sweeps abandoned holds so they stop reserving stock. */
    @Transactional
    public int expireStaleReservations() {
        List<UUID> expired = store.expireReservations(clock.instant());
        if (!expired.isEmpty()) {
            log.debug("Expired {} stale reservations", expired.size());
        }
        return expired.size();
    }

    /** Thrown rather than pretending to enforce a quantity the slice does not track. */
    public static class UnsupportedTrackingModeException extends RuntimeException {
        public UnsupportedTrackingModeException(TrackingMode mode) {
            super("Tracking mode " + mode + " is not implemented; the first slice is BINARY or UNTRACKED");
        }
    }
}
