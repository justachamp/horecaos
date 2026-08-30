package uz.horecaos.platform.pricing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.pricing.domain.Money;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.Quote.Adjustment;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.domain.TaxCalculation;

/**
 * The deterministic pricing pipeline (ADR 0018).
 *
 * <p>A pure function: same inputs, same total, on any machine, forever. It reads
 * no clock beyond the instant it is handed, touches no database, and consults
 * nothing that could differ between two runs. That is what makes the context hash
 * meaningful — checkout can prove the cart it is accepting is the cart that was
 * priced, rather than hoping so.
 *
 * <p>Implements stages 1, 2, 5, 6, 7, and 8 of the ADR's pipeline. Promotions and
 * coupons (stages 3 and 4) still have no rules to apply and are left out rather
 * than stubbed: an empty stage that silently does nothing is indistinguishable
 * from one that is broken.
 *
 * <p>Stages 5 and 6 arrive with ADR 0037. The charge is <em>resolved</em> outside
 * this class — zones and geometry and clocks live in fulfillment — and handed in
 * as a value, so the engine gains a delivery fee without gaining a database or a
 * clock. What happens here is only the last two things ADR 0037 leaves to the
 * pipeline: the fee becomes a line, and the zone's free-delivery threshold becomes
 * an adjustment against the post-discount goods subtotal.
 */
@Component
public class PricingEngine {

    /**
     * Bumped whenever the arithmetic changes.
     *
     * <p>Recorded on every quote so an old quote is never re-derived by new code
     * and silently disagreed with — the disagreement would surface as a customer
     * being charged something other than what they were shown.
     *
     * <p>Bumped to 2 by ADR 0037: a quote priced before delivery fees existed has
     * no fee line and no waiver, and re-deriving it under these rules would add
     * one to a total the customer already agreed to.
     */
    public static final int CALCULATION_VERSION = 2;

    /**
     * Stages 3 and 4, as a collaborator rather than as inlined code.
     *
     * <p>Constructed rather than injected, and that is not laziness: the
     * evaluator is a pure function of its arguments, so there is nothing to
     * configure and nothing to stub. Injecting it would make {@code new
     * PricingEngine()} impossible and turn every arithmetic test into a Spring
     * test, for no gain in what could be substituted.
     */
    private final PromotionEvaluator promotions = new PromotionEvaluator();

    public Result price(QuoteRequest request, PricingInputs inputs, Instant now) {
        if (inputs.taxMode() != TaxMode.INCLUSIVE) {
            // EXCLUSIVE exists in the schema for a future jurisdiction. Refusing
            // is deliberate: a half-implemented mode that produced plausible
            // wrong totals would be far worse than an error nobody can ignore.
            throw new UnsupportedTaxModeException(inputs.taxMode());
        }

        String currency = inputs.currency();
        List<Quote.QuoteLine> lines = new ArrayList<>();
        List<Adjustment> adjustments = new ArrayList<>();
        int sequence = 0;

        long grossTotal = 0;

        for (QuoteRequest.Line line : request.lines()) {
            Long unit = inputs.variantPrices().get(line.variantId());
            if (unit == null) {
                throw new UnpricedItemException(line.variantId());
            }

            // Modifiers are priced individually and folded into the unit price,
            // so "extra cheese" is visible as its own adjustment rather than
            // disappearing into a single number.
            long modifierTotal = 0;
            for (UUID optionId : line.modifierOptionIds()) {
                Long modifierPrice = inputs.modifierPrices().get(optionId);
                if (modifierPrice == null) {
                    throw new UnpricedItemException(optionId);
                }
                modifierTotal += modifierPrice;
            }

            long unitWithModifiers = Math.addExact(unit, modifierTotal);
            long lineGross = Math.multiplyExact(unitWithModifiers, (long) line.quantity());
            grossTotal = Math.addExact(grossTotal, lineGross);

            adjustments.add(new Adjustment(
                    ++sequence,
                    line.lineId(),
                    Adjustment.Type.BASE_PRICE,
                    "PRICE_BOOK",
                    inputs.priceBookId(),
                    inputs.priceBookVersion(),
                    Money.of(Math.multiplyExact(unit, (long) line.quantity()), currency),
                    "BASE_PRICE"));

            if (modifierTotal > 0) {
                adjustments.add(new Adjustment(
                        ++sequence,
                        line.lineId(),
                        Adjustment.Type.MODIFIER,
                        "PRICE_BOOK",
                        inputs.priceBookId(),
                        inputs.priceBookVersion(),
                        Money.of(Math.multiplyExact(modifierTotal, (long) line.quantity()), currency),
                        "MODIFIERS"));
            }

            lines.add(Quote.QuoteLine.item(
                    line.lineId(),
                    line.variantId(),
                    line.quantity(),
                    inputs.descriptions()
                            .getOrDefault(line.variantId(), line.variantId().toString()),
                    Money.of(unitWithModifiers, currency),
                    Money.of(lineGross, currency),
                    Money.of(lineGross, currency),
                    Money.zero(currency)));
        }

        // Stages 3 and 4 (ADR 0018). Promotions reduce the gross *before* tax is
        // extracted, because VAT is owed on what the customer actually pays.
        // Extracting first and discounting after would charge tax on money
        // nobody handed over, and the receipt would not reconcile.
        PromotionEvaluator.Outcome offers = evaluateOffers(request, inputs, lines, grossTotal, now);

        long lineDiscountTotal = 0;
        if (!offers.isEmpty()) {
            List<Quote.QuoteLine> discounted = new ArrayList<>(lines.size());
            for (Quote.QuoteLine line : lines) {
                long off = offers.lineDiscountsMinor().getOrDefault(line.lineId(), 0L);
                if (off <= 0) {
                    discounted.add(line);
                    continue;
                }
                lineDiscountTotal = Math.addExact(lineDiscountTotal, off);
                discounted.add(Quote.QuoteLine.item(
                        line.lineId(),
                        line.variantId(),
                        line.quantity(),
                        line.descriptionSnapshot(),
                        line.unitAmount(),
                        line.baseAmount(),
                        Money.of(Math.subtractExact(line.finalAmount().minor(), off), currency),
                        Money.zero(currency)));
            }
            lines = discounted;

            // One adjustment per promotion per affected line, naming the
            // promotion and the definition version that produced it. A quote is
            // explainable against the rule that actually priced it, not against
            // whatever that rule says today.
            for (PromotionEvaluator.AppliedPromotion applied : offers.applied()) {
                for (Map.Entry<String, Long> entry : applied.perLineMinor().entrySet()) {
                    if (entry.getValue() <= 0) {
                        continue;
                    }
                    adjustments.add(new Adjustment(
                            ++sequence,
                            entry.getKey(),
                            Adjustment.Type.ITEM_DISCOUNT,
                            "PROMOTION",
                            applied.promotionId(),
                            applied.definitionVersion(),
                            Money.of(-entry.getValue(), currency),
                            applied.code()));
                }
                if (applied.orderMinor() > 0) {
                    adjustments.add(new Adjustment(
                            ++sequence,
                            null,
                            Adjustment.Type.ORDER_DISCOUNT,
                            "PROMOTION",
                            applied.promotionId(),
                            applied.definitionVersion(),
                            Money.of(-applied.orderMinor(), currency),
                            applied.code()));
                }
            }
        }

        long orderDiscountTotal = offers.orderDiscountMinor();
        long discountTotal = Math.addExact(lineDiscountTotal, orderDiscountTotal);
        grossTotal = Math.subtractExact(grossTotal, discountTotal);

        // Stage 7. Tax is extracted from the gross, not added to a net, because
        // the prices are what the customer pays. Computed once on the total and
        // then apportioned, so the line taxes always sum to the total tax.
        long totalTax = TaxCalculation.extractInclusiveTax(grossTotal, inputs.taxRateBasisPoints());

        // Weighted by the *final* amount rather than the base one. A discounted
        // line bears less of the tax, which is the whole point of extracting VAT
        // from what the customer actually pays. Identical to the old behaviour
        // whenever nothing is discounted, because final equals base there.
        long[] weights =
                lines.stream().mapToLong(line -> line.finalAmount().minor()).toArray();
        long[] lineTaxes = TaxCalculation.apportion(totalTax, weights);

        List<Quote.QuoteLine> taxedLines = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Quote.QuoteLine line = lines.get(i);
            taxedLines.add(Quote.QuoteLine.item(
                    line.lineId(),
                    line.variantId(),
                    line.quantity(),
                    line.descriptionSnapshot(),
                    line.unitAmount(),
                    line.baseAmount(),
                    line.finalAmount(),
                    Money.of(lineTaxes[i], currency)));
        }

        adjustments.add(new Adjustment(
                ++sequence,
                null,
                Adjustment.Type.TAX,
                "TAX_PROFILE",
                inputs.taxProfileId(),
                inputs.taxProfileVersion(),
                Money.of(totalTax, currency),
                "VAT_INCLUSIVE"));

        // Stages 5 and 6. Everything from here reads only the resolved charge and
        // the goods subtotal, both of which are values: there is still nothing in
        // this method that could answer differently on a second run.
        //
        // The threshold and the minimum are both compared against grossTotal — the
        // post-discount goods subtotal, excluding the delivery fee and any service
        // charge. Comparing against a total that includes the fee makes the fee
        // oscillate: adding it crosses the threshold, which removes it, which
        // uncrosses the threshold, and the storefront shows two prices in turn.
        Delivery delivery = applyDelivery(
                inputs.deliveryCharge(),
                grossTotal,
                currency,
                taxedLines,
                adjustments,
                sequence,
                offers.deliveryBenefitMinor(),
                offers);

        // Stage 8. The goods total is the gross: tax is inside it, so adding tax
        // again would charge it twice. Subtotal is the net portion, which is what a
        // fiscal receipt reports separately. The delivery fee sits outside both, in
        // its own column, for the reason given in applyDelivery.
        long subtotal = Math.subtractExact(grossTotal, totalTax);
        long total = Math.addExact(grossTotal, delivery.feeMinor());

        return new Result(
                Money.of(subtotal, currency),
                Money.of(totalTax, currency),
                Money.of(delivery.feeMinor(), currency),
                Money.of(discountTotal, currency),
                Money.of(total, currency),
                delivery.lines(),
                List.copyOf(adjustments),
                delivery.shortfallMinor(),
                contextHash(request, inputs));
    }

    /**
     * ADR 0037 stages 5 to 8, in the order the decision states them.
     *
     * <p>Nothing happens at all unless a charge was resolved. An externally-priced
     * order, an address outside every zone, a brand with no tariff: each arrives
     * here as a non-resolved outcome and leaves the quote exactly as it was. That
     * is deliberate rather than a fallthrough — the refusal is a fact the
     * storefront reads off the resolution, and quietly pricing delivery at zero
     * because resolution failed is the single behaviour ADR 0037 refuses most
     * often.
     *
     * <p>The fee carries no tax share. Whether a delivery charge is VAT-bearing in
     * this jurisdiction, and under what classification, is an open finance and
     * legal input on ADR 0037 that lands with the fiscalization decision.
     * Apportioning VAT onto it now would put a number on a fiscal receipt that
     * nobody has ratified, and a wrong tax split is materially worse than an absent
     * one: the first is filed, the second is visibly missing.
     */
    private Delivery applyDelivery(
            ResolvedDeliveryCharge charge,
            long goodsSubtotal,
            String currency,
            List<Quote.QuoteLine> lines,
            List<Adjustment> adjustments,
            int sequence,
            long promotionBenefitMinor,
            PromotionEvaluator.Outcome offers) {

        if (charge == null || !charge.isResolved()) {
            return new Delivery(0L, lines, null);
        }

        // The line carries the gross charge and every reduction is its own
        // adjustment beside it. A line written net cannot be told apart from a
        // cheaper tariff, and the receipt this market requires shows the delivery
        // charge and its reduction as separate facts.
        long gross = charge.feeMinor();
        long fee = gross;
        List<Quote.QuoteLine> withDelivery = new ArrayList<>(lines);
        withDelivery.add(deliveryLine(currency, gross, fee));

        adjustments.add(new Adjustment(
                ++sequence,
                DELIVERY_FEE_LINE_ID,
                Adjustment.Type.FEE,
                "DELIVERY_TARIFF",
                charge.tariffId(),
                charge.tariffVersion(),
                Money.of(gross, currency),
                "DELIVERY_FEE"));

        // Stage 6's tail (ADR 0037, V0032). The rate table's own standing discount,
        // already capped at the fee by the resolver and capped again here: two
        // independent reductions that can each exceed the fee sum below zero, and
        // the cap is cheap enough to state twice.
        long tariffDiscount = Math.min(charge.tariffDiscountMinor(), fee);
        if (tariffDiscount > 0) {
            adjustments.add(new Adjustment(
                    ++sequence,
                    DELIVERY_FEE_LINE_ID,
                    Adjustment.Type.DELIVERY_TARIFF_DISCOUNT,
                    "DELIVERY_TARIFF",
                    charge.tariffId(),
                    charge.tariffVersion(),
                    Money.of(-tariffDiscount, currency),
                    "TARIFF_DELIVERY_DISCOUNT"));
            fee -= tariffDiscount;
            withDelivery.set(withDelivery.size() - 1, deliveryLine(currency, gross, fee));
        }

        // Stage 8. A waiver rather than a fee computed as zero, because a zero with
        // no adjustment beside it cannot be told apart from a broken tariff lookup
        // — and the two need completely different responses from whoever is
        // looking at the quote.
        //
        // It waives what the tariff discount left, not the gross. Waiving the gross
        // would post a reduction larger than the charge and make the two adjustments
        // sum the delivery line below zero.
        if (charge.freeDeliveryFromMinor() != null && goodsSubtotal >= charge.freeDeliveryFromMinor() && fee > 0) {
            adjustments.add(new Adjustment(
                    ++sequence,
                    DELIVERY_FEE_LINE_ID,
                    Adjustment.Type.DELIVERY_FEE_WAIVER,
                    "SERVICE_ZONE",
                    charge.zoneId(),
                    charge.zoneVersion(),
                    Money.of(-fee, currency),
                    "FREE_DELIVERY_THRESHOLD"));

            fee = 0L;
            withDelivery.set(withDelivery.size() - 1, deliveryLine(currency, gross, fee));
        }

        // ADR 0037 stage 9. A promotion's free or reduced delivery, applied last
        // and capped at whatever the tariff discount and the zone waiver left.
        // Its own adjustment type rather than a second waiver: the waiver answers
        // "did the basket clear a threshold" and this answers "was there an
        // offer", and every report that groups by adjustment type needs to tell
        // them apart. The enum has carried DELIVERY_FEE_BENEFIT unused since
        // ADR 0037 landed, for exactly this.
        if (promotionBenefitMinor > 0 && fee > 0) {
            long granted = Math.min(promotionBenefitMinor, fee);
            for (PromotionEvaluator.AppliedPromotion applied : offers.applied()) {
                if (applied.deliveryMinor() <= 0) {
                    continue;
                }
                adjustments.add(new Adjustment(
                        ++sequence,
                        DELIVERY_FEE_LINE_ID,
                        Adjustment.Type.DELIVERY_FEE_BENEFIT,
                        "PROMOTION",
                        applied.promotionId(),
                        applied.definitionVersion(),
                        Money.of(-Math.min(applied.deliveryMinor(), granted), currency),
                        applied.code()));
            }
            fee -= granted;
            withDelivery.set(withDelivery.size() - 1, deliveryLine(currency, gross, fee));
        }

        // Stage 7. The shortfall is reported and not thrown. ADR 0037 says checkout
        // is refused below the minimum and that the quote still returns the
        // shortfall, so the storefront can say how much more is needed rather than
        // only that something is wrong. Refusing here would destroy the number that
        // makes the message useful.
        Long shortfall = null;
        if (charge.minBasketMinor() != null && goodsSubtotal < charge.minBasketMinor()) {
            shortfall = charge.minBasketMinor() - goodsSubtotal;
        }
        return new Delivery(fee, List.copyOf(withDelivery), shortfall);
    }

    /**
     * Runs stages 3 and 4 over the basket as stages 1 and 2 left it.
     *
     * <p>Everything the evaluator needs arrives as values on {@link PricingInputs}
     * — the promotions themselves, the customer and clock facts, and the catalog
     * membership behind each line — so this method resolves nothing and the
     * engine stays a pure function.
     */
    private PromotionEvaluator.Outcome evaluateOffers(
            QuoteRequest request,
            PricingInputs inputs,
            List<Quote.QuoteLine> lines,
            long grossTotal,
            java.time.Instant now) {

        PromotionInputs offers = inputs.promotions();
        if (offers == null || offers.promotions().isEmpty()) {
            return EMPTY_OFFERS;
        }

        List<PromotionEvaluator.BasketLine> basketLines = new ArrayList<>(lines.size());
        for (Quote.QuoteLine line : lines) {
            if (line.type() != Quote.LineType.ITEM) {
                continue;
            }
            MenuMembershipLookup.Membership membership = offers.membership().get(line.variantId());
            basketLines.add(new PromotionEvaluator.BasketLine(
                    line.lineId(),
                    line.variantId(),
                    membership == null ? null : membership.productId(),
                    membership == null ? java.util.Set.of() : membership.categoryIds(),
                    line.quantity(),
                    line.unitAmount().minor(),
                    line.finalAmount().minor()));
        }

        long deliveryFee =
                inputs.deliveryCharge() == null || !inputs.deliveryCharge().isResolved()
                        ? 0L
                        : inputs.deliveryCharge().feeMinor();

        return promotions.evaluate(
                offers.promotions(),
                new PromotionEvaluator.Basket(inputs.currency(), basketLines, grossTotal, deliveryFee),
                offers.context(),
                now);
    }

    private static final PromotionEvaluator.Outcome EMPTY_OFFERS =
            new PromotionEvaluator.Outcome(Map.of(), 0L, 0L, List.of());

    /** The one delivery line, gross beside net, built the same way at each step. */
    private static Quote.QuoteLine deliveryLine(String currency, long gross, long net) {
        return new Quote.QuoteLine(
                DELIVERY_FEE_LINE_ID,
                Quote.LineType.DELIVERY_FEE,
                null,
                1,
                DELIVERY_FEE_DESCRIPTION,
                Money.of(gross, currency),
                Money.of(gross, currency),
                Money.of(net, currency),
                Money.zero(currency));
    }

    /**
     * Stable, and not a UUID.
     *
     * <p>{@code pricing.quote_lines} is keyed by {@code (quote_id, line_id)} and a
     * quote has exactly one delivery fee, so a fixed key is the honest one: it
     * makes a second fee line impossible rather than merely unlikely, and it lets a
     * re-quote be compared to its predecessor line by line.
     */
    public static final String DELIVERY_FEE_LINE_ID = "delivery-fee";

    /**
     * Not localised here.
     *
     * <p>Every other line's description is a snapshot of a menu name in the
     * customer's locale, and this one has no menu behind it. The storefront and the
     * receipt renderer own the wording; the quote owns the amount.
     */
    private static final String DELIVERY_FEE_DESCRIPTION = "DELIVERY_FEE";

    private record Delivery(long feeMinor, List<Quote.QuoteLine> lines, Long shortfallMinor) {}

    /**
     * Everything the total depends on, hashed.
     *
     * <p>Checkout accepts only a quote whose context still hashes to this. If a
     * price book changes, a menu is republished, or a line quantity is edited,
     * the hash changes and the customer is re-quoted rather than charged a
     * different amount than the one they agreed to.
     *
     * <p>Key order is pinned throughout, for the same reason the catalog content
     * hash pins it: a hash that depends on map iteration order is not reproducible
     * across processes, and an irreproducible hash proves nothing.
     */
    String contextHash(QuoteRequest request, PricingInputs inputs) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("v=").append(CALCULATION_VERSION);
        canonical.append("|tenant=").append(request.tenantId());
        canonical.append("|brand=").append(request.brandId());
        canonical.append("|location=").append(request.locationId());
        canonical.append("|channel=").append(request.channel());
        canonical.append("|customer=").append(request.customerAccountId());
        canonical.append("|publication=").append(inputs.catalogPublicationId());
        canonical.append("|priceBook=").append(inputs.priceBookId()).append(":").append(inputs.priceBookVersion());
        canonical
                .append("|tax=")
                .append(inputs.taxProfileId())
                .append(":")
                .append(inputs.taxProfileVersion())
                .append(":")
                .append(inputs.taxRateBasisPoints())
                .append(":")
                .append(inputs.taxMode());
        canonical.append("|currency=").append(inputs.currency());

        // ADR 0037. Zone version, tariff version, band, time rule and distance all
        // enter the hash, so a zone edit or a peak-window boundary crossed while
        // the customer was choosing a payment method invalidates the quote with
        // PRICE_CHANGED — exactly as a price-book edit already does. Without this
        // the fee would be the one number in the total that could change under the
        // customer without anything noticing.
        canonical
                .append("|delivery=")
                .append(
                        inputs.deliveryCharge() == null
                                ? "none"
                                : inputs.deliveryCharge().canonicalForm());

        // ADR 0018 stages 3 and 4. Every promotion that could apply, by id and
        // definition version, sorted so the same offers hash the same however the
        // store returned them. Without this a promotion suspended or re-authored
        // while the customer was choosing a payment method would leave the quote
        // valid and the total wrong -- the same hole the delivery clause above
        // exists to close. The coupon set is in the hash for the same reason: a
        // quote priced with a code presented is not the quote priced without it.
        PromotionInputs offers = inputs.promotions();
        canonical.append("|promotions=");
        if (offers == null || offers.promotions().isEmpty()) {
            canonical.append("none");
        } else {
            offers.promotions().stream()
                    .map(promotion -> promotion.promotionId() + ":" + promotion.definitionVersion())
                    .sorted()
                    .forEach(entry -> canonical.append(entry).append(","));
            canonical.append("|coupons=");
            offers.context().presentedCouponPromotionIds().stream()
                    .map(UUID::toString)
                    .sorted()
                    .forEach(entry -> canonical.append(entry).append(","));
        }

        // Sorted by line id, so the same cart in a different order hashes the
        // same. Otherwise re-ordering a basket would look like a changed cart.
        request.lines().stream()
                .sorted(java.util.Comparator.comparing(QuoteRequest.Line::lineId))
                .forEach(line -> {
                    canonical
                            .append("|line=")
                            .append(line.lineId())
                            .append(":")
                            .append(line.variantId())
                            .append("x")
                            .append(line.quantity());
                    line.modifierOptionIds().stream()
                            .map(UUID::toString)
                            .sorted()
                            .forEach(option -> canonical.append("+").append(option));
                });

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    public enum TaxMode {
        INCLUSIVE,
        EXCLUSIVE
    }

    /**
     * The resolved inputs a quote is computed from.
     *
     * <p>Passed in rather than looked up, so the engine stays a pure function and
     * every rule below can be tested on a literal.
     */
    public record PricingInputs(
            String currency,
            UUID catalogPublicationId,
            UUID priceBookId,
            int priceBookVersion,
            UUID taxProfileId,
            int taxProfileVersion,
            int taxRateBasisPoints,
            TaxMode taxMode,
            Map<UUID, Long> variantPrices,
            Map<UUID, Long> modifierPrices,
            Map<UUID, String> descriptions,
            /*
             * ADR 0037's resolved charge, or null when the cart is not being
             * delivered. Resolved before the engine runs, by the module that owns
             * geometry and clocks, so this class stays a pure function of values.
             */
            ResolvedDeliveryCharge deliveryCharge,
            /*
             * ADR 0018 stages 3 and 4, or null when nothing is on offer. Resolved
             * before the engine runs, like everything else here.
             */
            PromotionInputs promotions) {

        /** A cart with no promotions in play, and every call site that predates them. */
        public PricingInputs(
                String currency,
                UUID catalogPublicationId,
                UUID priceBookId,
                int priceBookVersion,
                UUID taxProfileId,
                int taxProfileVersion,
                int taxRateBasisPoints,
                TaxMode taxMode,
                Map<UUID, Long> variantPrices,
                Map<UUID, Long> modifierPrices,
                Map<UUID, String> descriptions,
                ResolvedDeliveryCharge deliveryCharge) {
            this(
                    currency,
                    catalogPublicationId,
                    priceBookId,
                    priceBookVersion,
                    taxProfileId,
                    taxProfileVersion,
                    taxRateBasisPoints,
                    taxMode,
                    variantPrices,
                    modifierPrices,
                    descriptions,
                    deliveryCharge,
                    null);
        }

        /** The pickup case, and every call site that predates ADR 0037. */
        public PricingInputs(
                String currency,
                UUID catalogPublicationId,
                UUID priceBookId,
                int priceBookVersion,
                UUID taxProfileId,
                int taxProfileVersion,
                int taxRateBasisPoints,
                TaxMode taxMode,
                Map<UUID, Long> variantPrices,
                Map<UUID, Long> modifierPrices,
                Map<UUID, String> descriptions) {
            this(
                    currency,
                    catalogPublicationId,
                    priceBookId,
                    priceBookVersion,
                    taxProfileId,
                    taxProfileVersion,
                    taxRateBasisPoints,
                    taxMode,
                    variantPrices,
                    modifierPrices,
                    descriptions,
                    null,
                    null);
        }
    }

    /**
     * What stages 3 and 4 read, all of it resolved before the engine runs.
     *
     * @param promotions every ACTIVE promotion of this brand. Filtering by window
     *        and by coupon happens in the evaluator, so this list is the same for
     *        every cart of the brand and can be cached by the caller.
     * @param context the customer and clock facts a condition may ask about. The
     *        local day and minute are resolved from the branch's IANA timezone by
     *        the caller: a lunchtime offer means lunchtime where the branch is,
     *        and this engine may read neither a clock nor a zone.
     * @param membership variant to product and categories, for the line-matching
     *        conditions.
     */
    public record PromotionInputs(
            List<Promotion> promotions,
            PromotionEvaluator.PromotionContext context,
            Map<UUID, MenuMembershipLookup.Membership> membership) {

        public PromotionInputs {
            promotions = promotions == null ? List.of() : List.copyOf(promotions);
            membership = membership == null ? Map.of() : Map.copyOf(membership);
        }
    }

    /**
     * @param deliveryShortfallMinor how far the basket is below the zone's minimum,
     *                               or null when there is no minimum or it is met.
     *                               Reported rather than thrown, so the storefront
     *                               can say how much more is needed
     */
    public record Result(
            Money subtotal,
            Money tax,
            Money fees,
            Money discount,
            Money total,
            List<Quote.QuoteLine> lines,
            List<Adjustment> adjustments,
            Long deliveryShortfallMinor,
            String contextHash) {}

    /** Thrown when a cart contains something with no active price. */
    public static class UnpricedItemException extends RuntimeException {
        private final UUID priceableId;

        public UnpricedItemException(UUID priceableId) {
            super("No active price for " + priceableId);
            this.priceableId = priceableId;
        }

        public UUID priceableId() {
            return priceableId;
        }
    }

    /** Thrown rather than approximating a tax mode this release does not implement. */
    public static class UnsupportedTaxModeException extends RuntimeException {
        public UnsupportedTaxModeException(TaxMode mode) {
            super("Tax mode " + mode + " is not implemented; prices at HorecaOS are VAT-inclusive");
        }
    }
}
