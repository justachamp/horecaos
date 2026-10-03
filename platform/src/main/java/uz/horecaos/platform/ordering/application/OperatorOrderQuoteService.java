package uz.horecaos.platform.ordering.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;

/**
 * What the New order screen shows before «Создать»: the price the server will book,
 * not the price the menu adds up to (gap map row {@code 1.3e}).
 *
 * <p><strong>The real path, then undone.</strong> A quote is {@link
 * OperatorOrderingService#fillAndPrice} -- the cart opened for the customer, every line,
 * the destination, the promo code and the payment method put on it, and the cart priced
 * by {@code PricingEngine} -- run inside a transaction that is always rolled back. Every
 * rule {@link OperatorOrderingService#place} applies on the way to a price (the menu, the
 * sale windows, stock, the combo and modifier rules, the promo code's eligibility, the
 * delivery zone) is therefore applied here by the very same code, and the screen cannot
 * show a figure the order would then not be booked at. A second, parallel pricing path
 * built for previews is the thing this class exists not to be.
 *
 * <p>Nothing survives: no cart, no cart line, no stored quote, no delivery-fee evidence,
 * no audit fact for the address that was read to price a delivery, and no outbox row.
 * The transaction is {@code REQUIRES_NEW} and rolled back whatever happens inside it, a
 * refusal included, and a caller that is itself inside a transaction is not poisoned by
 * the rollback.
 *
 * <p>Checkout is never reached, so nothing is reserved, redeemed or counted: a promo
 * code's redemption is taken at checkout, and an operator typing a code to see what it is
 * worth does not use one up.
 */
@Service
public class OperatorOrderQuoteService {

    private final OperatorOrderingService ordering;
    private final TransactionTemplate transactions;

    public OperatorOrderQuoteService(OperatorOrderingService ordering, TransactionTemplate transactions) {
        this.ordering = ordering;
        // Its own transaction, never joining a caller's: the rollback below is this method's
        // whole job and must not be able to mark somebody else's work rollback-only.
        TransactionTemplate isolated = new TransactionTemplate(Objects.requireNonNull(
                transactions.getTransactionManager(), "a transaction template always carries its manager"));
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions = isolated;
    }

    /**
     * The adjustment types that reduce what the customer pays, in the order the engine
     * applies them. {@code ITEM_MARKUP} raises a line and is part of the subtotal, so it is
     * not listed as a discount.
     */
    private static final Set<String> DISCOUNT_TYPES = Set.of(
            "ITEM_DISCOUNT",
            "ORDER_DISCOUNT",
            "DELIVERY_FEE_WAIVER",
            "DELIVERY_FEE_BENEFIT",
            "DELIVERY_TARIFF_DISCOUNT");

    /**
     * A priced basket as the order would be booked, in integer minor units and a currency.
     *
     * <p>{@code subtotalMinor} is gross of the discount, the receipt convention {@code
     * ck_order_total_reconciles} encodes: {@code totalMinor = subtotalMinor + taxMinor +
     * feeMinor - discountMinor}. Where the price book is tax-inclusive (the only mode a book
     * is authored in today) {@code taxMinor} is already inside the figure the customer pays.
     *
     * @param deliveryOutcome how delivery-fee resolution ended, or null when the order is not
     *                        a delivery; anything but {@code RESOLVED} or {@code
     *                        EXTERNALLY_PRICED} means checkout would refuse the order
     * @param deliveryShortfallMinor how far the goods are below the zone's minimum basket
     * @param provisional true while any line is sold by weight: the figure is an estimate
     *                        the weighing at handover replaces
     */
    public record OrderQuote(
            String currency,
            long subtotalMinor,
            long discountMinor,
            long feeMinor,
            long taxMinor,
            long totalMinor,
            @Nullable String deliveryOutcome,
            @Nullable Long deliveryShortfallMinor,
            @Nullable Long deliveryMinBasketMinor,
            @Nullable Long deliveryFreeFromMinor,
            boolean provisional,
            List<QuotedLine> lines,
            List<QuotedDiscount> discounts) {

        public OrderQuote {
            lines = List.copyOf(lines);
            discounts = List.copyOf(discounts);
        }
    }

    /**
     * One line the operator entered, priced. A combo's components are summed back into the
     * combo's own line, so {@code index} always names a line of the request.
     *
     * @param baseAmountMinor  before any discount
     * @param finalAmountMinor after the line's own discounts, tax included
     */
    public record QuotedLine(
            int index, long baseAmountMinor, long finalAmountMinor, long taxAmountMinor, boolean provisional) {}

    /**
     * One reduction in the price: a promotion on a line or on the order, or a delivery-fee
     * waiver. {@code code} is the promotion's own authored code -- never what the operator
     * typed -- and {@code amountMinor} is positive.
     *
     * @param lineIndex the request line it landed on, or null for an order-level reduction
     */
    public record QuotedDiscount(
            @Nullable Integer lineIndex,
            String type,
            @Nullable String code,
            long amountMinor) {}

    /**
     * Prices an order exactly as {@link OperatorOrderingService#place} would, and keeps
     * nothing.
     *
     * @throws uz.horecaos.platform.web.api.ApiException {@code VALIDATION_FAILED} for a
     *         request that is malformed in the way {@code place} refuses before it opens a cart
     * @throws CartService.CartRefusedException for any rule the cart refuses on (an item out
     *         of stock or outside its sale window, a promo code that does not apply, a
     *         destination that cannot be delivered to)
     */
    public OrderQuote quote(OperatorOrderingService.PlaceOrderCommand command) {
        ordering.requireWellFormed(command);
        OrderQuote quote = transactions.execute(status -> {
            try {
                return toQuote(command, ordering.fillAndPrice(command).priced().quote());
            } finally {
                // On every exit, a refusal included. A cart that was priced is a cart nobody
                // asked to keep.
                status.setRollbackOnly();
            }
        });
        return Objects.requireNonNull(quote, "a transaction callback that returned a quote returns it");
    }

    private static OrderQuote toQuote(OperatorOrderingService.PlaceOrderCommand command, QuoteSnapshot snapshot) {
        List<QuotedLine> lines = new ArrayList<>(command.lines().size());
        for (int index = 0; index < command.lines().size(); index++) {
            // The key fillAndPrice gives line i; a combo's component lines carry it as a prefix.
            String key = OperatorOrderingService.lineKey(index);
            long base = 0;
            long finalAmount = 0;
            long tax = 0;
            boolean provisional = false;
            for (QuoteSnapshot.Line line : snapshot.lines()) {
                if (!line.cartLineKey().equals(key)) {
                    continue;
                }
                base += line.baseAmountMinor();
                finalAmount += line.finalAmountMinor();
                tax += line.taxAmountMinor();
                provisional |= line.catchweight() != null && !line.catchweight().reconciled();
            }
            lines.add(new QuotedLine(index, base, finalAmount, tax, provisional));
        }

        List<QuotedDiscount> discounts = new ArrayList<>();
        for (QuoteSnapshot.Adjustment adjustment : snapshot.adjustments()) {
            if (!DISCOUNT_TYPES.contains(adjustment.adjustmentType()) || adjustment.amountMinor() == 0) {
                continue;
            }
            discounts.add(new QuotedDiscount(
                    lineIndexOf(adjustment.lineKey()),
                    adjustment.adjustmentType(),
                    adjustment.descriptionCode(),
                    Math.abs(adjustment.amountMinor())));
        }

        return new OrderQuote(
                snapshot.currency(),
                snapshot.subtotalMinor(),
                snapshot.discountMinor(),
                snapshot.feeMinor(),
                snapshot.taxMinor(),
                snapshot.totalMinor(),
                snapshot.deliveryOutcome() == null
                        ? null
                        : snapshot.deliveryOutcome().name(),
                snapshot.deliveryShortfallMinor(),
                snapshot.deliveryMinBasketMinor(),
                snapshot.deliveryFreeFromMinor(),
                lines.stream().anyMatch(QuotedLine::provisional),
                lines,
                discounts);
    }

    /** The request line an adjustment's key belongs to, or null for an order-level one. */
    private static @Nullable Integer lineIndexOf(@Nullable String lineKey) {
        if (lineKey == null) {
            return null;
        }
        int separator = lineKey.indexOf(CartService.COMBO_KEY_SEPARATOR);
        String cartLine = separator > 0 ? lineKey.substring(0, separator) : lineKey;
        return OperatorOrderingService.lineIndexOf(cartLine);
    }
}
