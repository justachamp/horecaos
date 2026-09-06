package uz.horecaos.platform.ordering.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.MenuPriceLookup;
import uz.horecaos.platform.catalog.api.MenuPriceLookup.MenuPrices;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;

/**
 * Whether one of a customer's own orders can be ordered again, and with what
 * (ADR 0074).
 *
 * <h2>Why this is a server's job</h2>
 *
 * <p>Answering it takes the live publication for the order's channel, the
 * location's offering rows, the published modifier groups and the active price
 * book — four reads and ADR 0036's precedence between the last two. A storefront
 * would have to reimplement all of it, the Telegram bot has no menu loaded at
 * all, and every reimplementation would drift. ADR 0070 makes the contract the
 * product; this is the part of it that is hardest to get right, so it is the
 * part that must not be pushed to clients.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It builds nothing. The plan is a read, and a client applies it through the
 * ordinary cart path — {@code POST /carts} then {@code PUT /carts/{id}/lines/…}
 * with the ids returned here — so a repeated basket is priced, quoted and
 * checked out by exactly the code every other basket travels. A service that
 * assembled the cart itself would be a second way to build one, and the two
 * would drift the way ADR 0074's alternatives table says.
 *
 * <p>It also does not promise. A dish can be 86'd between the plan and the
 * {@code PUT}; the plan narrows that window rather than closing it, and pricing
 * stays the authority that refuses.
 *
 * <h2>Two facts, both of them "available"</h2>
 *
 * <p>A dish is orderable only when the tenant offers it here <em>and</em> the
 * kitchen has it. Those are separate records with separate writers:
 * {@code catalog.location_offerings.status} is the tenant's own menu decision
 * ({@code CatalogAuthoringController#setOffering}), and
 * {@code inventory.positions.binary_available} is the 86 a kitchen flips from
 * the bot's {@code /86} or the operations stop list
 * ({@code InventoryController#setAvailability}, ADR 0060 §3).
 *
 * <p>Reading only the first would be the exact bug this endpoint exists to
 * prevent — a repeat button offered on a dish sold out ten minutes ago. So the
 * 86 is read through {@link InventoryReservationPort#checkAvailability}, which
 * is the same check {@code reserveForQuote} takes atomically with its hold: a
 * plan that said yes where checkout says no would be worse than no plan.
 *
 * <p>Inventory is reached through the port and never by a join. V0162 put
 * {@code inventory.*} behind row-level security precisely on the strength of
 * no module outside inventory reading those tables directly.
 */
@Service
public class ReorderPlanService {

    private final OrderQueryService orders;
    private final ReorderMenu menu;
    private final MenuPriceLookup prices;
    private final InventoryReservationPort inventory;

    public ReorderPlanService(
            OrderQueryService orders, ReorderMenu menu, MenuPriceLookup prices, InventoryReservationPort inventory) {
        this.orders = orders;
        this.menu = menu;
        this.prices = prices;
        this.inventory = inventory;
    }

    /**
     * The plan for one of the caller's own orders.
     *
     * <p>Scoped through {@link OrderQueryService#detailForCustomer} rather than by
     * a check after the read, so an order belonging to somebody else is
     * indistinguishable from one that does not exist.
     *
     * @return empty when the order is not this account's, or does not exist
     */
    @Transactional(readOnly = true)
    public Optional<ReorderPlan> planFor(UUID tenantId, UUID orderId, UUID customerAccountId) {
        return orders.detailForCustomer(tenantId, orderId, customerAccountId, null)
                .map(this::plan);
    }

    private ReorderPlan plan(OrderQueryService.OrderDetail detail) {
        OrderRow order = detail.order();

        Set<UUID> variantIds = new LinkedHashSet<>();
        detail.lines().forEach(line -> variantIds.add(line.line().sourceVariantId()));

        ReorderMenu.Snapshot offers =
                menu.at(order.tenantId(), order.brandId(), order.locationId(), order.channelCode(), variantIds);

        // One price read for the whole order, keyed on what actually survived:
        // asking for a withdrawn variant's price would be asking the price book
        // about a dish that is not on the menu.
        Set<UUID> survivingVariants = offers.offers().keySet();
        Set<UUID> survivingOptions = new LinkedHashSet<>();
        detail.lines().forEach(line -> {
            if (survivingVariants.contains(line.line().sourceVariantId())) {
                line.modifiers().forEach(modifier -> survivingOptions.add(modifier.sourceOptionId()));
            }
        });

        Optional<MenuPrices> priced = survivingVariants.isEmpty()
                ? Optional.empty()
                : prices.pricesFor(
                        order.tenantId(),
                        order.brandId(),
                        order.locationId(),
                        order.channelCode(),
                        survivingVariants,
                        survivingOptions);

        // The kitchen's own answer, not the menu's. A QUANTITY-tracked variant
        // makes this throw, exactly as it makes checkout throw: an unimplemented
        // tracking mode is not something a plan should smooth over into a button
        // that would fail at reservation.
        Map<UUID, String> blocked = survivingVariants.isEmpty()
                ? Map.of()
                : blockedBy(inventory.checkAvailability(order.tenantId(), order.locationId(), survivingVariants));

        List<PlannedLine> lines = new ArrayList<>(detail.lines().size());
        for (OrderQueryService.DetailLine detailLine : detail.lines()) {
            lines.add(planned(detailLine, offers, blocked, priced.orElse(null)));
        }

        return new ReorderPlan(
                order.orderId(),
                order.publicOrderNumber(),
                order.locationId(),
                order.channelCode(),
                verdictOf(lines),
                priced.map(MenuPrices::currency).orElse(order.currency()),
                List.copyOf(lines));
    }

    private static Map<UUID, String> blockedBy(AvailabilityDecision decision) {
        Map<UUID, String> reasons = new LinkedHashMap<>();
        decision.unavailableItems().forEach(item -> reasons.putIfAbsent(item.variantId(), item.reason()));
        return reasons;
    }

    private static PlannedLine planned(
            OrderQueryService.DetailLine detailLine,
            ReorderMenu.Snapshot offers,
            Map<UUID, String> blocked,
            @Nullable MenuPrices priced) {

        OrderLineRow line = detailLine.line();
        List<UUID> optionIds = detailLine.modifiers().stream()
                .map(OrderModifierRow::sourceOptionId)
                .toList();

        ReorderMenu.VariantOffer offer = offers.offers().get(line.sourceVariantId());
        if (offer == null) {
            return PlannedLine.of(line, optionIds, null, LineStatus.WITHDRAWN, null);
        }

        Long unitAmount = priced == null ? null : priced.variantPrices().get(line.sourceVariantId());

        String stockReason = blocked.get(line.sourceVariantId());

        LineStatus status;
        if (offer.soldOut() || "SOLD_OUT".equals(stockReason)) {
            status = LineStatus.SOLD_OUT;
        } else if (stockReason != null) {
            // Offered on the menu and not stocked here at all. ADR 0017 is
            // explicit that an unlisted variant is never orderable, so to a
            // customer this is the same fact as a dish the menu no longer
            // carries — not a sold-out one that returns this evening.
            status = LineStatus.WITHDRAWN;
        } else if (!offer.offeredOptionIds().containsAll(optionIds)) {
            // The dish is orderable and one of its choices is not. Repeating this
            // line would serve a plainer version of what the customer ordered,
            // which is not repeating it.
            status = LineStatus.MODIFIERS_WITHDRAWN;
        } else if (unitAmount == null) {
            status = LineStatus.UNPRICED;
        } else {
            status = LineStatus.AVAILABLE;
        }
        return PlannedLine.of(line, optionIds, offer.productId(), status, unitAmount);
    }

    private static Verdict verdictOf(List<PlannedLine> lines) {
        if (lines.isEmpty()) {
            // Nothing to repeat is not "everything is fine".
            return Verdict.UNAVAILABLE;
        }
        long available = lines.stream()
                .filter(line -> line.status() == LineStatus.AVAILABLE)
                .count();
        if (available == lines.size()) {
            return Verdict.READY;
        }
        return available == 0 ? Verdict.UNAVAILABLE : Verdict.PARTIAL;
    }

    /**
     * What an order would cost to place again, and whether it can be.
     *
     * @param currency the price book's currency where one resolved, and the
     *     order's own otherwise — never absent, because a client formatting
     *     {@code originalUnitAmountMinor} needs one even when nothing is orderable
     */
    public record ReorderPlan(
            UUID orderId,
            String publicOrderNumber,
            UUID locationId,
            String channelCode,
            Verdict verdict,
            String currency,
            List<PlannedLine> lines) {}

    /**
     * One historical line, resolved.
     *
     * @param productName the order's own snapshot — what was bought, not what the
     *     menu calls it today
     * @param productId null exactly when the line is {@code WITHDRAWN}: the
     *     product it belonged to is no longer published here, so there is nothing
     *     to link to
     * @param unitAmountMinor the price today, or null when none resolves. Whole
     *     som for UZS (ADR 0018), never divided
     * @param originalUnitAmountMinor what was paid, so a client can show a repeat
     *     whose total has moved rather than surprising the customer at checkout
     */
    public record PlannedLine(
            int lineNumber,
            String productName,
            @Nullable String variantName,
            @Nullable UUID productId,
            UUID variantId,
            int quantity,
            List<UUID> modifierOptionIds,
            LineStatus status,
            @Nullable Long unitAmountMinor,
            long originalUnitAmountMinor) {

        static PlannedLine of(
                OrderLineRow line,
                List<UUID> optionIds,
                @Nullable UUID productId,
                LineStatus status,
                @Nullable Long unitAmountMinor) {
            return new PlannedLine(
                    line.lineNumber(),
                    line.productName(),
                    line.variantName(),
                    productId,
                    line.sourceVariantId(),
                    line.quantity(),
                    List.copyOf(optionIds),
                    status,
                    unitAmountMinor,
                    line.unitAmountMinor());
        }
    }

    /** Whether a client offers the button. See ADR 0074. */
    public enum Verdict {
        /** Every line can be ordered again exactly as it was. */
        READY,
        /** Some lines can. Whether that is offered is the client's policy. */
        PARTIAL,
        /** None can, or the order had no lines. */
        UNAVAILABLE
    }

    /** Why one line is or is not repeatable. */
    public enum LineStatus {
        /** Published, offered here, orderable, priced, every option still offered. */
        AVAILABLE,
        /** Offered here and 86'd. This one comes back. */
        SOLD_OUT,
        /** Not on the live publication, or not offered at this location. */
        WITHDRAWN,
        /** Orderable, but no active price resolves for this location and channel. */
        UNPRICED,
        /** The variant is orderable; one of the line's chosen options is not. */
        MODIFIERS_WITHDRAWN
    }
}
