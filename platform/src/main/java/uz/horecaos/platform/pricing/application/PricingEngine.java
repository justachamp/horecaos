package uz.horecaos.platform.pricing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
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
        String currency = inputs.currency();
        List<Quote.QuoteLine> lines = new ArrayList<>();
        List<Adjustment> adjustments = new ArrayList<>();
        int sequence = 0;

        long grossTotal = 0;

        for (QuoteRequest.Line line : request.lines()) {
            PricedLine priced = priceLine(line, inputs, true);
            grossTotal = Math.addExact(grossTotal, priced.grossMinor());
            for (AdjustmentDraft draft : priced.adjustments()) {
                adjustments.add(new Adjustment(
                        ++sequence,
                        draft.lineId(),
                        draft.type(),
                        draft.sourceType(),
                        draft.sourceId(),
                        draft.sourceVersion(),
                        Money.of(draft.amountMinor(), currency),
                        draft.descriptionCode()));
            }
            lines.addAll(priced.lines());
        }

        // Stages 3 and 4 (ADR 0018). Promotions reduce the gross *before* tax is
        // extracted, because VAT is owed on what the customer actually pays.
        // Extracting first and discounting after would charge tax on money
        // nobody handed over, and the receipt would not reconcile.
        PromotionEvaluator.Outcome offers = evaluateOffers(inputs, lines, grossTotal, now);

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
                // withAmounts keeps the combo grouping key: a discounted component is
                // still a component of the combo it was bought in.
                discounted.add(line.withAmounts(
                        Money.of(Math.subtractExact(line.finalAmount().minor(), off), currency), Money.zero(currency)));
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
        // Kept apart from grossTotal, which stage 8 below discounts, because
        // subtotal is reported gross of the discount (the receipt convention —
        // subtotal, then discount, then tax/fee, then total — and
        // ck_order_total_reconciles's: total = subtotal + tax + fee -
        // discount). Tax is still extracted from, or added onto, the
        // *discounted* amount below: VAT is owed on what the customer actually
        // pays, and discounting after extraction would charge tax on money
        // nobody handed over.
        long preDiscountGrossTotal = grossTotal;
        grossTotal = Math.subtractExact(grossTotal, discountTotal);

        // Stage 7. Rounding happens exactly once here, on the total — apportion()
        // below only splits an already-rounded figure across lines, it never
        // rounds a second time, which is what keeps the line taxes summing
        // exactly to the total tax.
        //
        // INCLUSIVE (the only mode a price book is authored under today, ADR
        // 0018's "Money and tax policy"): the price book amount is what the
        // customer pays, so tax is extracted from it rather than added — this
        // method's "gross" throughout means that inclusive amount. EXCLUSIVE
        // (the schema affordance ADR 0018 reserves for a jurisdiction that
        // quotes net prices): the price book amount is what the customer pays
        // *before* tax, so tax is added on top instead — under this mode alone,
        // grossTotal at this line is a net figure, and stage 8 below accounts
        // for that. Both directions are one BigDecimal division, HALF_UP to the
        // nearest whole minor unit (a whole som for UZS, ADR 0038) —
        // TaxCalculation.extractInclusiveTax and TaxCalculation.addExclusiveTax
        // are the only two places either rounding happens.
        long totalTax =
                switch (inputs.taxMode()) {
                    case INCLUSIVE -> TaxCalculation.extractInclusiveTax(grossTotal, inputs.taxRateBasisPoints());
                    case EXCLUSIVE -> TaxCalculation.addExclusiveTax(grossTotal, inputs.taxRateBasisPoints());
                };

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
            taxedLines.add(line.withAmounts(line.finalAmount(), Money.of(lineTaxes[i], currency)));
        }

        adjustments.add(new Adjustment(
                ++sequence,
                null,
                Adjustment.Type.TAX,
                "TAX_PROFILE",
                inputs.taxProfileId(),
                inputs.taxProfileVersion(),
                Money.of(totalTax, currency),
                // The evidence a quote's own promise depends on: an auditor
                // reading this adjustment must be able to tell whether the tax
                // was extracted from the price or added on top of it without
                // re-deriving the tax profile.
                inputs.taxMode() == TaxMode.INCLUSIVE ? "VAT_INCLUSIVE" : "VAT_EXCLUSIVE"));

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

        // Stage 8. subtotal is reported gross of the discount — derived from
        // preDiscountGrossTotal, not from the discounted grossTotal below —
        // while tax, delivery and the total keep discounting exactly as
        // before. INCLUSIVE: tax is already inside preDiscountGrossTotal, so
        // it is subtracted to report the net-of-tax, gross-of-discount
        // subtotal a receipt shows on its own line; the customer-facing total
        // still comes from the *discounted* grossTotal, adding only the
        // delivery fee. EXCLUSIVE: preDiscountGrossTotal is already net of tax
        // (nothing to subtract), and the total still adds both the tax just
        // computed on the discounted amount and the delivery fee. Either way
        // the delivery fee sits outside both figures, in its own column, for
        // the reason given in applyDelivery — its tax treatment is a separate,
        // open question, not this mode switch. The identity this leaves in
        // both modes: total = subtotal + tax + fee - discount.
        long subtotal = inputs.taxMode() == TaxMode.INCLUSIVE
                ? Math.subtractExact(preDiscountGrossTotal, totalTax)
                : preDiscountGrossTotal;
        long total = inputs.taxMode() == TaxMode.INCLUSIVE
                ? Math.addExact(grossTotal, delivery.feeMinor())
                : Math.addExact(Math.addExact(grossTotal, totalTax), delivery.feeMinor());

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
     * The goods subtotal this request prices to, before promotions and delivery.
     *
     * <p>Exists so the delivery resolver can be handed the figure stages 7 and 8
     * compare against the zone's minimum and threshold <em>before</em> the engine
     * runs, without a second copy of the arithmetic to drift from this one: it is the
     * same {@link #priceLine} the pricing pass uses. An item with no price is skipped
     * rather than thrown on, because the pricing pass refuses it a moment later with
     * the id of the offending item, and a helper whose job is a threshold comparison
     * is the wrong place to report it.
     */
    public long goodsSubtotal(QuoteRequest request, PricingInputs inputs) {
        long subtotal = 0;
        for (QuoteRequest.Line line : request.lines()) {
            subtotal = Math.addExact(subtotal, priceLine(line, inputs, false).grossMinor());
        }
        return subtotal;
    }

    /**
     * Stages 1 and 2 for one request line: what it costs and the evidence for it.
     *
     * <p>An ordinary line is the variant's price plus its modifiers, folded into one
     * unit price so "extra cheese" is visible as its own adjustment rather than
     * disappearing into a number. ADR 0136 adds three things to that, each a value
     * resolved before the engine ran: second-level modifier selections priced the way
     * first-level ones are, the hidden auto-selected charges that apply to this
     * variant on this order's fulfilment mode, and -- for a combo's container -- one
     * quote line per picked component at that component's own price.
     *
     * @param strict whether a missing price is an error. The pricing pass is strict;
     *        {@link #goodsSubtotal} is not
     */
    private PricedLine priceLine(QuoteRequest.Line line, PricingInputs inputs, boolean strict) {
        CompositePricing.CompositeInputs composite =
                inputs.composite() == null ? CompositePricing.CompositeInputs.none() : inputs.composite();

        List<CompositePricing.ComboLine> combo = CompositePricing.resolveCombo(line, composite);
        if (combo != null) {
            return priceComboLine(line, combo, inputs, composite, strict);
        }

        String currency = inputs.currency();
        Long unit = inputs.variantPrices().get(line.variantId());
        if (unit == null) {
            if (strict) {
                throw new UnpricedItemException(line.variantId());
            }
            return PricedLine.empty();
        }

        List<AdjustmentDraft> drafts = new ArrayList<>();

        long modifierTotal = 0;
        for (UUID optionId : line.modifierOptionIds()) {
            Long modifierPrice = inputs.modifierPrices().get(optionId);
            if (modifierPrice == null) {
                if (strict) {
                    throw new UnpricedItemException(optionId);
                }
                continue;
            }
            modifierTotal = Math.addExact(modifierTotal, modifierPrice);
        }

        // The second level (ADR 0136): validated against the facts, then priced exactly
        // as a first-level option is. A selection the facts do not allow never gets here.
        long nestedTotal = 0;
        for (UUID optionId : CompositePricing.resolveNested(line, composite)) {
            Long nestedPrice = inputs.modifierPrices().get(optionId);
            if (nestedPrice == null) {
                if (strict) {
                    throw new UnpricedItemException(optionId);
                }
                continue;
            }
            nestedTotal = Math.addExact(nestedTotal, nestedPrice);
        }

        HiddenTotal hidden = hiddenCharges(line.variantId(), inputs, composite, strict);

        long unitWithModifiers =
                Math.addExact(Math.addExact(Math.addExact(unit, modifierTotal), nestedTotal), hidden.totalMinor());
        long lineGross = Math.multiplyExact(unitWithModifiers, (long) line.quantity());

        drafts.add(new AdjustmentDraft(
                line.lineId(),
                Adjustment.Type.BASE_PRICE,
                "PRICE_BOOK",
                inputs.priceBookId(),
                inputs.priceBookVersion(),
                Math.multiplyExact(unit, (long) line.quantity()),
                "BASE_PRICE"));
        if (modifierTotal > 0) {
            drafts.add(new AdjustmentDraft(
                    line.lineId(),
                    Adjustment.Type.MODIFIER,
                    "PRICE_BOOK",
                    inputs.priceBookId(),
                    inputs.priceBookVersion(),
                    Math.multiplyExact(modifierTotal, (long) line.quantity()),
                    "MODIFIERS"));
        }
        if (nestedTotal > 0) {
            drafts.add(new AdjustmentDraft(
                    line.lineId(),
                    Adjustment.Type.MODIFIER,
                    "PRICE_BOOK",
                    inputs.priceBookId(),
                    inputs.priceBookVersion(),
                    Math.multiplyExact(nestedTotal, (long) line.quantity()),
                    "NESTED_MODIFIERS"));
        }
        addHiddenDrafts(drafts, line.lineId(), hidden, line.quantity());

        Quote.QuoteLine quoteLine = Quote.QuoteLine.item(
                line.lineId(),
                line.variantId(),
                line.quantity(),
                inputs.descriptions()
                        .getOrDefault(line.variantId(), line.variantId().toString()),
                Money.of(unitWithModifiers, currency),
                Money.of(lineGross, currency),
                Money.of(lineGross, currency),
                Money.zero(currency));
        return new PricedLine(List.of(quoteLine), drafts, lineGross);
    }

    /**
     * A combo order, as the several ordinary lines it is (ADR 0136).
     *
     * <p>There is no line for the container and no parent line: an order for a combo
     * is arithmetically an order for its components separately, so the quote total,
     * the order total and every reader that sums lines are correct without learning
     * that a combo exists. Each component carries its own price (the {@code
     * COMBO_COMPONENT} price map, keyed to the pairing and not the variant), its own
     * tax share in stage 7, and -- because it is a variant -- its own ИКПУ on the
     * receipt. Only the shared selection id says they were one purchase.
     *
     * <p>A component's quantity is the cart line's quantity times the units one pick of
     * it puts on the order times how many times it was picked, so two lunch boxes with
     * a six-piece wings pick are twelve wings, priced per wing.
     */
    private PricedLine priceComboLine(
            QuoteRequest.Line line,
            List<CompositePricing.ComboLine> combo,
            PricingInputs inputs,
            CompositePricing.CompositeInputs composite,
            boolean strict) {

        String currency = inputs.currency();
        UUID selectionId = composite.comboSelectionIds().get(line.lineId());
        if (selectionId == null) {
            // The caller mints it so this class stays a function of its inputs. Missing
            // is a bug in the caller, not a cart the customer can fix.
            throw new IllegalStateException("No combo selection id was supplied for line " + line.lineId());
        }

        List<Quote.QuoteLine> lines = new ArrayList<>();
        List<AdjustmentDraft> drafts = new ArrayList<>();
        long gross = 0;
        int position = 0;
        for (CompositePricing.ComboLine pick : combo) {
            CompositePricing.ComboComponentFact component = pick.component();
            Long componentPrice = composite.comboComponentPrices().get(component.id());
            if (componentPrice == null) {
                if (strict) {
                    throw new UnpricedItemException(component.id());
                }
                continue;
            }
            HiddenTotal hidden = hiddenCharges(component.componentVariantId(), inputs, composite, strict);

            long units = Math.multiplyExact((long) line.quantity(), (long) pick.unitsPerCombo());
            if (units > Integer.MAX_VALUE) {
                throw new ArithmeticException("combo component quantity overflows an order line");
            }
            long unit = Math.addExact(componentPrice, hidden.totalMinor());
            long lineGross = Math.multiplyExact(unit, units);
            gross = Math.addExact(gross, lineGross);

            // Stable per position within the sorted components, and short enough that
            // the cart's own key plus this suffix still fits quote_lines.line_id.
            String childId = line.lineId() + "~" + (++position);
            lines.add(Quote.QuoteLine.comboComponent(
                    childId,
                    component.componentVariantId(),
                    (int) units,
                    inputs.descriptions()
                            .getOrDefault(
                                    component.componentVariantId(),
                                    component.componentVariantId().toString()),
                    Money.of(unit, currency),
                    Money.of(lineGross, currency),
                    Money.of(lineGross, currency),
                    Money.zero(currency),
                    selectionId,
                    line.variantId(),
                    component.id(),
                    line.quantity(),
                    pick.pickQuantity()));
            drafts.add(new AdjustmentDraft(
                    childId,
                    Adjustment.Type.BASE_PRICE,
                    "PRICE_BOOK",
                    inputs.priceBookId(),
                    inputs.priceBookVersion(),
                    Math.multiplyExact(componentPrice, units),
                    "COMBO_COMPONENT_PRICE"));
            addHiddenDrafts(drafts, childId, hidden, units);
        }
        return new PricedLine(lines, drafts, gross);
    }

    /**
     * The hidden auto-selected charges that apply to one priced variant (ADR 0136),
     * each at its {@code MODIFIER_OPTION} price.
     *
     * <p>A hidden option with no price refuses the cart, as any modifier with no price
     * does: pricing it at zero because nobody wrote a number is a free box nobody
     * agreed to give.
     */
    private HiddenTotal hiddenCharges(
            UUID variantId, PricingInputs inputs, CompositePricing.CompositeInputs composite, boolean strict) {
        List<HiddenApplied> applied = new ArrayList<>();
        long total = 0;
        for (CompositePricing.HiddenCharge charge : composite.hiddenChargesOf(variantId)) {
            Long price = inputs.modifierPrices().get(charge.optionId());
            if (price == null) {
                if (strict) {
                    throw new UnpricedItemException(charge.optionId());
                }
                continue;
            }
            applied.add(new HiddenApplied(charge.optionId(), price));
            total = Math.addExact(total, price);
        }
        return new HiddenTotal(total, applied);
    }

    /**
     * One adjustment per hidden option, even at a price of zero.
     *
     * <p>"The box was applied, free" is a fact: it is what tells the order which
     * option to snapshot, and an adjustment that vanished at zero would make a free
     * hidden option indistinguishable from none.
     */
    private static void addHiddenDrafts(List<AdjustmentDraft> drafts, String lineId, HiddenTotal hidden, long units) {
        for (HiddenApplied charge : hidden.applied()) {
            drafts.add(new AdjustmentDraft(
                    lineId,
                    Adjustment.Type.MODIFIER,
                    HIDDEN_MODIFIER_SOURCE,
                    charge.optionId(),
                    null,
                    Math.multiplyExact(charge.priceMinor(), units),
                    "HIDDEN_MODIFIER"));
        }
    }

    /**
     * The {@code source_type} of an adjustment for a hidden option, whose {@code
     * source_id} is the option itself: the evidence an order reads to learn which
     * option the server selected for the customer.
     */
    public static final String HIDDEN_MODIFIER_SOURCE = QuoteSnapshot.Adjustment.HIDDEN_MODIFIER_SOURCE;

    private record HiddenApplied(UUID optionId, long priceMinor) {}

    private record HiddenTotal(long totalMinor, List<HiddenApplied> applied) {}

    private record AdjustmentDraft(
            @Nullable String lineId,
            Adjustment.Type type,
            String sourceType,
            @Nullable UUID sourceId,
            @Nullable Integer sourceVersion,
            long amountMinor,
            String descriptionCode) {}

    private record PricedLine(List<Quote.QuoteLine> lines, List<AdjustmentDraft> adjustments, long grossMinor) {

        static PricedLine empty() {
            return new PricedLine(List.of(), List.of(), 0L);
        }
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
            @Nullable ResolvedDeliveryCharge charge,
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

        // Stage 7, moved ahead of the rest: a basket below the zone's minimum
        // is not charged the fee at all, not charged and then hidden.
        // StorefrontOrderingController.DeliveryChargeResponse.of already forces
        // this same case's *outcome* to UNRESOLVED -- a resolved zone below its
        // minimum reads to the storefront exactly like any other not-yet-usable
        // fee, and renders a dash, never the amount. The amount actually
        // charged into the quote has to agree with that dash, or `totalMinor`
        // included a fee no line on the priced cart ever showed. Checkout
        // refuses this cart regardless (`CheckoutEligibilityGuard`,
        // DELIVERY_MINIMUM_BASKET_NOT_MET), so nothing is ever collected
        // against the uncharged fee; it becomes real again, at its real
        // amount, only once pricing re-runs against a basket that clears it.
        // The shortfall itself is still reported and not thrown -- ADR 0037
        // says checkout is refused below the minimum and the quote still
        // returns the shortfall, so the storefront can say how much more is
        // needed rather than only that something is wrong.
        if (charge.minBasketMinor() != null && goodsSubtotal < charge.minBasketMinor()) {
            return new Delivery(0L, lines, charge.minBasketMinor() - goodsSubtotal);
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
            // A shared, shrinking pool rather than a fixed ceiling every
            // promotion is clamped against independently: two non-exclusive
            // delivery promotions from different stacking groups (an explicitly
            // supported combination, PromotionEvaluator's own "different groups
            // combine") must not each write a full-value adjustment against the
            // same fee reduction. Decrementing as each promotion's share is
            // taken is what makes the adjustment rows sum to exactly what
            // `fee` is actually reduced by, below.
            long granted = Math.min(promotionBenefitMinor, fee);
            long remaining = granted;
            for (PromotionEvaluator.AppliedPromotion applied : offers.applied()) {
                if (applied.deliveryMinor() <= 0 || remaining <= 0) {
                    continue;
                }
                long share = Math.min(applied.deliveryMinor(), remaining);
                remaining -= share;
                adjustments.add(new Adjustment(
                        ++sequence,
                        DELIVERY_FEE_LINE_ID,
                        Adjustment.Type.DELIVERY_FEE_BENEFIT,
                        "PROMOTION",
                        applied.promotionId(),
                        applied.definitionVersion(),
                        Money.of(-share, currency),
                        applied.code()));
            }
            fee -= granted;
            withDelivery.set(withDelivery.size() - 1, deliveryLine(currency, gross, fee));
        }

        // Stage 7 (the below-minimum case) already returned above -- reaching
        // here means the basket clears the minimum, so there is no shortfall
        // left to report.
        return new Delivery(fee, List.copyOf(withDelivery), null);
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
            PricingInputs inputs, List<Quote.QuoteLine> lines, long grossTotal, java.time.Instant now) {

        PromotionInputs offers = inputs.promotions();
        if (offers == null || offers.promotions().isEmpty()) {
            return EMPTY_OFFERS;
        }

        List<PromotionEvaluator.BasketLine> basketLines = new ArrayList<>(lines.size());
        for (Quote.QuoteLine line : lines) {
            if (line.type() != Quote.LineType.ITEM) {
                continue;
            }
            // Quote.QuoteLine.variantId is only ever null on a DELIVERY_FEE line
            // (enforced by that record's own constructor), and the type check just
            // above has already excluded those, so an ITEM line's variant is
            // guaranteed present here even though the accessor's type is nullable.
            UUID variantId = Objects.requireNonNull(line.variantId(), "an ITEM line always carries a variant");
            MenuMembershipLookup.Membership membership = offers.membership().get(variantId);
            basketLines.add(new PromotionEvaluator.BasketLine(
                    line.lineId(),
                    variantId,
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

    private record Delivery(
            long feeMinor,
            List<Quote.QuoteLine> lines,
            @Nullable Long shortfallMinor) {}

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

        // ADR 0136. Only written when there is something to say, so a cart with no
        // combo, no nested choice and no hidden charge hashes exactly as it did before
        // the record existed. The hidden charges are in the hash because they are in the
        // total: a packaging option switched on for DELIVERY while the customer was
        // choosing a payment method must invalidate the quote like any other price
        // change. The fulfilment mode is named only alongside them, since it changes
        // nothing a quote contains until a hidden group reads it.
        CompositePricing.CompositeInputs composite = inputs.composite();
        if (composite != null && !composite.hiddenChargesByVariant().isEmpty()) {
            canonical.append("|mode=").append(request.effectiveFulfillmentMode());
            composite.hiddenChargesByVariant().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> entry.getValue().stream()
                            .map(charge -> entry.getKey() + ">" + charge.groupId() + ":" + charge.optionId())
                            .sorted()
                            .forEach(charged -> canonical.append("|hidden=").append(charged)));
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
                    line.nestedModifiers().stream()
                            .map(nested -> nested.parentOptionId() + ">" + nested.optionId())
                            .sorted()
                            .forEach(nested -> canonical.append("+nested=").append(nested));
                    // A pick is its component, its count, and what that component
                    // costs and how many units it puts on the order -- so a price or a
                    // default quantity changed under an in-flight quote changes the hash.
                    line.comboPicks().stream()
                            .map(pick -> pick.componentId() + "*" + pick.quantity() + "@" + comboTerms(composite, pick))
                            .sorted()
                            .forEach(pick -> canonical.append("+combo=").append(pick));
                });

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    /** What a combo pick costs and delivers, for the hash: its unit price and units per pick. */
    private static String comboTerms(
            CompositePricing.@Nullable CompositeInputs composite, QuoteRequest.ComboPick pick) {
        if (composite == null) {
            return "-";
        }
        CompositePricing.ComboComponentFact component =
                composite.comboComponents().get(pick.componentId());
        Long price = composite.comboComponentPrices().get(pick.componentId());
        return (price == null ? "unpriced" : price.toString())
                + "x"
                + (component == null ? "-" : Integer.toString(component.defaultQuantity()));
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
            @Nullable ResolvedDeliveryCharge deliveryCharge,
            /*
             * ADR 0018 stages 3 and 4, or null when nothing is on offer. Resolved
             * before the engine runs, like everything else here.
             */
            @Nullable PromotionInputs promotions,
            /*
             * ADR 0136: combos, hidden auto-selected charges and nested modifier
             * facts, or null when the cart has none of them -- every cart before the
             * record, and every test with nothing to say about composite products.
             */
            CompositePricing.@Nullable CompositeInputs composite) {

        /** A cart with no composite products, and every call site that predates ADR 0136. */
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
                @Nullable ResolvedDeliveryCharge deliveryCharge,
                @Nullable PromotionInputs promotions) {
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
                    promotions,
                    null);
        }

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
                @Nullable ResolvedDeliveryCharge deliveryCharge) {
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
     * What one call to {@link #price} produced.
     *
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
            @Nullable Long deliveryShortfallMinor,
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
}
