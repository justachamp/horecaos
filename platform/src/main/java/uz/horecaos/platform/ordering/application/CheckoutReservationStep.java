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
import uz.horecaos.platform.pricing.api.QuoteAcceptance;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.LocationCapacityPort;

/**
 * Steps 3 through 5 of {@link CheckoutService}'s order of operations: hold the
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

    CheckoutReservationStep(
            InventoryReservationPort inventory, LocationCapacityPort capacity, QuoteAcceptancePort quotes) {
        this.inventory = inventory;
        this.capacity = capacity;
        this.quotes = quotes;
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

        // 3. Hold the stock. Idempotent per quote, and refused rather than
        // silently reused when the earlier hold has lapsed.
        Map<UUID, Integer> quantities = quantitiesOf(quote);
        ReservationResult reservation = inventory.reserveForQuote(
                command.tenantId(), command.brandId(), cart.locationId(), command.quoteId(), quantities);
        if (!reservation.isHeld()) {
            return new ItemsUnavailable(reservation.refusal());
        }

        // 4. The kitchen slot, claimed under the id the order is about to take, so
        // a retry re-claims its own rather than consuming a second.
        UUID orderId = UUID.randomUUID();
        if (capacity.claimCapacity(command.tenantId(), command.brandId(), cart.locationId(), orderId)
                == LocationCapacityPort.CapacityOutcome.AT_CAPACITY) {
            inventory.release(command.tenantId(), command.quoteId());
            return new Refused("AT_CAPACITY", "The kitchen is at its concurrent-order limit");
        }

        // 5. The point of no return. One conditional update decides which of two
        // concurrent checkouts owns this quote.
        QuoteAcceptance acceptance = quotes.acceptQuote(command.tenantId(), command.quoteId(), command.contextHash());
        if (!acceptance.isAccepted()) {
            capacity.releaseCapacity(command.tenantId(), orderId);
            inventory.release(command.tenantId(), command.quoteId());
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
        quote.lines().forEach(line -> quantities.merge(line.variantId(), line.quantity(), Integer::sum));
        return quantities;
    }
}
