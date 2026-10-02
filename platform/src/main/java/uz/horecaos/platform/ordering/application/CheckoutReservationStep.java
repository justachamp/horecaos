package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.inventory.api.ReservationResult;
import uz.horecaos.platform.ordering.application.CheckoutEligibilityGuard.Eligible;
import uz.horecaos.platform.ordering.application.CheckoutService.CheckoutCommand;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort;
import uz.horecaos.platform.pricing.api.PromotionRedemptionPort;
import uz.horecaos.platform.pricing.api.QuoteAcceptance;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.LocationCapacityPort;
import uz.horecaos.platform.web.api.Quantities;

/**
 * Steps 3 through 6 of {@link CheckoutService}'s order of operations: consume
 * a promo-code redemption if the quote carries one (ADR 0072), hold the
 * stock, claim a kitchen slot, and accept the quote — the point of no return.
 *
 * <p>Every refusal from here compensates whatever this step already committed
 * before it, which is the property that lets everything after it run
 * unconditionally: once {@link Reserved} comes back, the order is going to be
 * written.
 */
@Component
class CheckoutReservationStep {

    private final InventoryReservationPort inventory;
    private final LocationCapacityPort capacity;
    private final QuoteAcceptancePort quotes;
    private final PromoCodeRedemptionPort promoCodes;
    private final PromotionRedemptionPort promotions;

    CheckoutReservationStep(
            InventoryReservationPort inventory,
            LocationCapacityPort capacity,
            QuoteAcceptancePort quotes,
            PromoCodeRedemptionPort promoCodes,
            PromotionRedemptionPort promotions) {
        this.inventory = inventory;
        this.capacity = capacity;
        this.quotes = quotes;
        this.promoCodes = promoCodes;
        this.promotions = promotions;
    }

    sealed interface Outcome permits Reserved, ItemsUnavailable, Refused {}

    /** @param quantities the demand this reservation placed, one entry per distinct variant */
    record Reserved(UUID orderId, Map<UUID, Integer> quantities) implements Outcome {}

    /** The inventory refusal, naming every item that could not be held. */
    record ItemsUnavailable(AvailabilityDecision decision) implements Outcome {}

    /** A plain business refusal: at capacity, or the quote moved under the customer. */
    record Refused(String code, String detail) implements Outcome {}

    Outcome reserve(CheckoutCommand command, Eligible eligible, Instant now) {
        var cart = eligible.cart();
        QuoteSnapshot quote = eligible.quote();

        // The order's own id, minted here rather than at step 5, so the promo
        // redemption row below can carry it: pricing.coupon_redemptions
        // requires an order id on a REDEEMED row, the same id the kitchen
        // capacity claim uses a few lines later.
        UUID orderId = UUID.randomUUID();

        // 3. Consume the promo-code redemption this quote carries, if any
        // (ADR 0072). Before the stock hold, for the same reason as the hold
        // itself: a code exhausted by a concurrent checkout must refuse
        // before anything else is reserved. A quote with no applied,
        // still-eligible coupon returns NO_CODE_APPLIED, which is success —
        // there is nothing to reserve.
        PromoCodeRedemptionPort.RedemptionResult redemption = promoCodes.reserveForQuote(
                command.tenantId(), command.brandId(), command.quoteId(), orderId, cart.customerAccountId(), now);
        if (redemption.isRefused()) {
            return new Refused(
                    redemption.result().name(), "The applied promo code is no longer available for this order");
        }

        // 3b. The automatic promotions the quote was priced with (ADR 0140): the
        // same two conditional writes a coupon uses, claimed for a limited
        // promotion, and the one-row-per-(order, promotion) ledger written for
        // every one, limited or not, because it is also the source of report 7.9.
        // A promotion that ran out between pricing and now refuses here, before
        // anything else is held, and the code just redeemed is given back.
        PromotionRedemptionPort.Result claim = promotions.claimForQuote(
                command.tenantId(), command.brandId(), command.quoteId(), orderId, cart.customerAccountId(), now);
        if (claim.isRefused()) {
            promoCodes.release(command.tenantId(), command.quoteId());
            return new Refused(
                    claim.outcome().name(),
                    "An offer applied to this order is no longer available; request a new quote");
        }

        // 4. Hold the stock. Idempotent per quote, and refused rather than
        // silently reused when the earlier hold has lapsed.
        Map<UUID, Integer> quantities = quantitiesOf(quote);
        ReservationResult reservation = inventory.reserveForQuote(
                command.tenantId(),
                command.brandId(),
                cart.locationId(),
                command.quoteId(),
                quote.expiresAt(),
                quantities,
                // ADR 0141 Decision 6: the cart's own channel, so a stop that covers it
                // refuses the hold with ON_STOP where a threshold would only have hidden it.
                cart.channelId());
        if (!reservation.isHeld()) {
            promotions.releaseForQuote(command.tenantId(), command.quoteId());
            promoCodes.release(command.tenantId(), command.quoteId());
            return new ItemsUnavailable(reservation.refusal());
        }

        // 5. The kitchen slot, claimed under the order id minted above, so a
        // retry re-claims its own rather than consuming a second.
        if (capacity.claimCapacity(command.tenantId(), command.brandId(), cart.locationId(), orderId)
                == LocationCapacityPort.CapacityOutcome.AT_CAPACITY) {
            inventory.release(command.tenantId(), command.quoteId());
            promotions.releaseForQuote(command.tenantId(), command.quoteId());
            promoCodes.release(command.tenantId(), command.quoteId());
            return new Refused("AT_CAPACITY", "The kitchen is at its concurrent-order limit");
        }

        // 6. The point of no return. One conditional update decides which of two
        // concurrent checkouts owns this quote.
        QuoteAcceptance acceptance = quotes.acceptQuote(command.tenantId(), command.quoteId(), command.contextHash());
        if (!acceptance.isAccepted()) {
            capacity.releaseCapacity(command.tenantId(), orderId);
            inventory.release(command.tenantId(), command.quoteId());
            promotions.releaseForQuote(command.tenantId(), command.quoteId());
            promoCodes.release(command.tenantId(), command.quoteId());
            return new Refused(
                    acceptance.outcome() == QuoteAcceptance.Outcome.PRICE_CHANGED ? "PRICE_CHANGED" : "QUOTE_EXPIRED",
                    "The price changed or the quote lapsed; request a new quote");
        }

        return new Reserved(orderId, quantities);
    }

    private Map<UUID, Integer> quantitiesOf(QuoteSnapshot quote) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        // Summed rather than assigned: two lines of the same variant — one with
        // extra cheese, one without — are one stock demand, and overwriting would
        // under-reserve.
        // Whole units, rounded up (ADR 0137): stock is held in whole units and the
        // record leaves a fractional reservation to the one that next touches the
        // inventory ledger. Half a cake holds one cake, which can only over-hold,
        // never oversell.
        quote.lines()
                .forEach(line -> quantities.merge(
                        line.variantId(), Quantities.wholeUnitsCeiling(line.quantity()), Integer::sum));
        return quantities;
    }
}
