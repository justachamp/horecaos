package uz.horecaos.platform.ordering.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                .map(detail -> plan(detail, detail.order().locationId()));
    }

    /**
     * The same plan, resolved against a caller-supplied location rather than
     * the order's own (gap map rows 1.3f/1.3a) — the New Order screen's own
     * operator-staffed branch, which the customer's original order may not
     * have anything to do with: a LOCATION_STAFF operator taking a call at
     * branch B has no read grant on branch A's own {@code ORDER_READ} scope
     * at all, and even a manager who does would want branch B's own menu,
     * offering rows and stock checked — the same honest "this branch, right
     * now" the New Order screen's own phone-lookup history peek already
     * promises for every other panel it renders.
     *
     * <p>{@code channelCode} is deliberately still the order's own — {@link
     * ReorderMenu#at}'s own doc warns that resolving one channel's order
     * against a different channel's menu "answers a question nobody asked",
     * and nothing about *where* the operator is sitting changes *which*
     * price plane and publication ADR 0036 says this order's channel reads.
     * Only the location moves.
     *
     * <p>The returned {@link ReorderPlan#locationId()} is {@code
     * resolveAgainstLocationId}, not the order's own — it names the branch
     * this plan is actually valid at, which is where {@code POST /carts}
     * needs to open the new one.
     *
     * <p>{@code requiredBrandId} is checked against the order's own {@code
     * brandId} and the read refuses (returns empty, exactly like an order
     * that does not exist) when they differ. {@link OrderQueryService#detailForCustomer}
     * scopes only by tenant and customer account — deliberately, since {@code
     * planFor} above needs no brand check, the account already owns the whole
     * read. This caller is different: {@code
     * uz.horecaos.platform.ordering.web.CustomerOrderReorderController}
     * reaches this method with {@code ORDER_READ} granted at one {@code
     * LOCATION}, and a location's grant never widens past its own brand
     * (ADR 0025). Skipping this check would let that LOCATION-scoped read
     * return another brand's order in full — line items, quantities, and what
     * the customer paid — to an operator who holds no grant on that brand at
     * all, resolved only against a location that happens to share a tenant
     * with it.
     *
     * @return empty when the order is not this account's, does not belong to
     *     {@code requiredBrandId}, or does not exist
     */
    @Transactional(readOnly = true)
    public Optional<ReorderPlan> planForAtLocation(
            UUID tenantId, UUID requiredBrandId, UUID orderId, UUID customerAccountId, UUID resolveAgainstLocationId) {
        return orders.detailForCustomer(tenantId, orderId, customerAccountId, null)
                .filter(detail -> requiredBrandId.equals(detail.order().brandId()))
                .map(detail -> plan(detail, resolveAgainstLocationId));
    }

    private ReorderPlan plan(OrderQueryService.OrderDetail detail, UUID resolveAgainstLocationId) {
        OrderRow order = detail.order();
        List<Unit> units = unitsOf(detail.lines());

        // What the menu is asked about is what the customer would add to a cart: an ordinary
        // line's own variant, and a combo's container. What stock is asked about is what the
        // kitchen would have to make: a combo's components, and the same variant otherwise.
        Set<UUID> variantIds = new LinkedHashSet<>();
        units.forEach(unit -> variantIds.add(unit.cartVariantId()));

        ReorderMenu.Snapshot offers =
                menu.at(order.tenantId(), order.brandId(), resolveAgainstLocationId, order.channelCode(), variantIds);

        // One price read for the whole order, keyed on what actually survived:
        // asking for a withdrawn variant's price would be asking the price book
        // about a dish that is not on the menu. A combo has no variant price to ask for
        // (its container is never priced; see PlannedLine), so only ordinary lines are asked.
        Set<UUID> survivingVariants = offers.offers().keySet();
        Set<UUID> pricedVariants = new LinkedHashSet<>();
        Set<UUID> survivingOptions = new LinkedHashSet<>();
        units.stream().filter(unit -> !unit.combo()).forEach(unit -> {
            if (survivingVariants.contains(unit.cartVariantId())) {
                pricedVariants.add(unit.cartVariantId());
                unit.firstLevel().forEach(modifier -> survivingOptions.add(modifier.sourceOptionId()));
            }
        });

        Optional<MenuPrices> priced = pricedVariants.isEmpty()
                ? Optional.empty()
                : prices.pricesFor(
                        order.tenantId(),
                        order.brandId(),
                        resolveAgainstLocationId,
                        order.channelCode(),
                        pricedVariants,
                        survivingOptions);

        // The kitchen's own answer, not the menu's. A QUANTITY-tracked variant
        // makes this throw, exactly as it makes checkout throw: an unimplemented
        // tracking mode is not something a plan should smooth over into a button
        // that would fail at reservation.
        Set<UUID> stocked = new LinkedHashSet<>();
        units.stream()
                .filter(unit -> survivingVariants.contains(unit.cartVariantId()))
                .forEach(unit -> stocked.addAll(unit.stockVariantIds()));
        Map<UUID, String> blocked = stocked.isEmpty()
                ? Map.of()
                : blockedBy(inventory.checkAvailability(order.tenantId(), resolveAgainstLocationId, stocked));

        List<PlannedLine> lines = new ArrayList<>(units.size());
        for (Unit unit : units) {
            lines.add(planned(unit, offers, blocked, priced.orElse(null)));
        }

        return new ReorderPlan(
                order.orderId(),
                order.publicOrderNumber(),
                resolveAgainstLocationId,
                order.channelCode(),
                verdictOf(lines),
                priced.map(MenuPrices::currency).orElse(order.currency()),
                List.copyOf(lines));
    }

    /**
     * What a customer would put in a cart to buy this order again (ADR 0136).
     *
     * <p>An ordinary line is itself. The component lines of one combo purchase -- they share a
     * selection id, and the container they were bought as part of is on none of them -- fold back
     * into the one thing the customer chose, in the order the first of them was bought. A line is
     * never both: a combo's components take no modifiers of their own except the options the
     * server applied.
     */
    private static List<Unit> unitsOf(List<OrderQueryService.DetailLine> detailLines) {
        Map<Object, List<OrderQueryService.DetailLine>> grouped = new LinkedHashMap<>();
        for (OrderQueryService.DetailLine detailLine : detailLines) {
            Object key = detailLine.line().comboSelectionId() == null
                    ? detailLine.line().lineId()
                    : detailLine.line().comboSelectionId();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(detailLine);
        }
        return grouped.values().stream().map(Unit::of).toList();
    }

    /** One thing to repeat: an ordinary line, or all the component lines of one combo. */
    private record Unit(List<OrderQueryService.DetailLine> lines) {

        static Unit of(List<OrderQueryService.DetailLine> lines) {
            return new Unit(List.copyOf(lines));
        }

        OrderQueryService.DetailLine first() {
            return lines.get(0);
        }

        boolean combo() {
            return first().line().comboSelectionId() != null;
        }

        /** What goes in the cart: the combo's container, or the line's own variant. */
        UUID cartVariantId() {
            return combo()
                    ? Objects.requireNonNull(first().line().comboContainerVariantId())
                    : first().line().sourceVariantId();
        }

        /** What the kitchen has to have: every component, or the line's own variant. */
        Set<UUID> stockVariantIds() {
            Set<UUID> variants = new LinkedHashSet<>();
            lines.forEach(line -> variants.add(line.line().sourceVariantId()));
            return variants;
        }

        /** The options the customer chose at the first level, in the order they were bought. */
        List<OrderModifierRow> firstLevel() {
            return first().modifiers().stream()
                    .filter(modifier -> !modifier.autoSelected() && modifier.parentModifierId() == null)
                    .toList();
        }

        /** The second-level options, each naming the first-level option it hangs off. */
        List<CartService.NestedModifier> nested() {
            Map<UUID, UUID> optionByModifier = new LinkedHashMap<>();
            first().modifiers()
                    .forEach(modifier -> optionByModifier.put(modifier.modifierId(), modifier.sourceOptionId()));
            return first().modifiers().stream()
                    .filter(modifier -> modifier.parentModifierId() != null)
                    .map(modifier -> new CartService.NestedModifier(
                            Objects.requireNonNull(optionByModifier.get(modifier.parentModifierId())),
                            modifier.sourceOptionId()))
                    .toList();
        }

        /** What the customer picked inside a combo; empty for an ordinary line. */
        List<CartService.ComboPick> picks() {
            if (!combo()) {
                return List.of();
            }
            return lines.stream()
                    .map(line -> new CartService.ComboPick(
                            Objects.requireNonNull(line.line().comboComponentId()),
                            Objects.requireNonNull(line.line().comboPickQuantity())))
                    .toList();
        }

        /** How many of it: the combos bought, or the line's own quantity. */
        int quantity() {
            return combo()
                    ? Objects.requireNonNull(first().line().comboQuantity())
                    : first().line().quantity();
        }

        /** What one of it cost: per combo for a combo, per unit otherwise. Before discounts. */
        long originalUnitAmountMinor() {
            if (!combo()) {
                return first().line().unitAmountMinor();
            }
            long gross = lines.stream()
                    .mapToLong(line -> line.line().baseAmountMinor())
                    .sum();
            return gross / quantity();
        }

        /** The product name the customer bought: the combo's name, or the line's snapshot. */
        String name() {
            return combo()
                    ? Objects.requireNonNull(first().line().comboName())
                    : first().line().productName();
        }
    }

    private static Map<UUID, String> blockedBy(AvailabilityDecision decision) {
        Map<UUID, String> reasons = new LinkedHashMap<>();
        decision.unavailableItems().forEach(item -> reasons.putIfAbsent(item.variantId(), item.reason()));
        return reasons;
    }

    private static PlannedLine planned(
            Unit unit, ReorderMenu.Snapshot offers, Map<UUID, String> blocked, @Nullable MenuPrices priced) {

        List<UUID> optionIds =
                unit.firstLevel().stream().map(OrderModifierRow::sourceOptionId).toList();

        ReorderMenu.VariantOffer offer = offers.offers().get(unit.cartVariantId());
        if (offer == null) {
            return PlannedLine.of(unit, optionIds, null, LineStatus.WITHDRAWN, null);
        }

        // A combo has no price to look up: its container is never priced, and what the cart will
        // charge is the sum of its components at their combo prices, which the cart's own quote
        // resolves. Not knowing it here is not "unpriced".
        Long unitAmount =
                unit.combo() || priced == null ? null : priced.variantPrices().get(unit.cartVariantId());

        // Blocked on the first stocked variant that is, in the order the unit lists them: a
        // combo with its drink sold out is a combo the kitchen cannot make.
        String stockReason = unit.stockVariantIds().stream()
                .map(blocked::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        LineStatus status;
        if (offer.soldOut() || "SOLD_OUT".equals(stockReason)) {
            status = LineStatus.SOLD_OUT;
        } else if (stockReason != null) {
            // Offered on the menu and not stocked here at all. ADR 0017 is
            // explicit that an unlisted variant is never orderable, so to a
            // customer this is the same fact as a dish the menu no longer
            // carries -- not a sold-out one that returns this evening.
            status = LineStatus.WITHDRAWN;
        } else if (!offer.offeredOptionIds().containsAll(optionIds)) {
            // The dish is orderable and one of its choices is not. Repeating this
            // line would serve a plainer version of what the customer ordered,
            // which is not repeating it.
            status = LineStatus.MODIFIERS_WITHDRAWN;
        } else if (unitAmount == null && !unit.combo()) {
            status = LineStatus.UNPRICED;
        } else {
            status = LineStatus.AVAILABLE;
        }
        return PlannedLine.of(unit, optionIds, offer.productId(), status, unitAmount);
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
     * @param variantId what goes in the cart: the line's own variant, or for a combo (ADR 0136)
     *     its container. A combo's {@code unitAmountMinor} is always null -- it has no price of
     *     its own -- and its {@code originalUnitAmountMinor} is what one combo cost, before
     *     discounts. {@code quantity} counts combos
     * @param comboPicks what the customer picked inside a combo, empty otherwise
     * @param nestedModifiers the second-level selections, each naming the first-level option it
     *     hangs off; a repeat is built from the first level and these, never from an option the
     *     server applied by itself
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
            long originalUnitAmountMinor,
            List<CartService.ComboPick> comboPicks,
            List<CartService.NestedModifier> nestedModifiers) {

        static PlannedLine of(
                Unit unit,
                List<UUID> optionIds,
                @Nullable UUID productId,
                LineStatus status,
                @Nullable Long unitAmountMinor) {
            OrderLineRow line = unit.first().line();
            return new PlannedLine(
                    line.lineNumber(),
                    unit.name(),
                    // A combo's name is already the whole of what it was sold as.
                    unit.combo() ? null : line.variantName(),
                    productId,
                    unit.cartVariantId(),
                    unit.quantity(),
                    List.copyOf(optionIds),
                    status,
                    unitAmountMinor,
                    unit.originalUnitAmountMinor(),
                    unit.picks(),
                    unit.nested());
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
