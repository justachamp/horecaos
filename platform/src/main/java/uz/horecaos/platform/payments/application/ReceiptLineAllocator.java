package uz.horecaos.platform.payments.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import uz.horecaos.platform.loyalty.api.RedemptionAllocation;

/**
 * The arithmetic of a receipt: what each line was worth, what came off it and what tax it carries
 * (ADR 0166 Decision 9, ADR 0038, ADR 0046, ADR 0018).
 *
 * <p>Pure. It takes the figures the order stored and returns the figures a receipt states, and decides
 * nothing about classification, providers or wire shapes; {@link ReceiptLineBuilder} does the
 * assembly around it, so the rules below are pinned by table-driven tests that need no database.
 *
 * <h2>The rules</h2>
 *
 * <ol>
 * <li><strong>A discount is allocated across lines and never carried as a line of its own.</strong> A
 *   receipt has no line type meaning «bonus», «discount» or «rounding» (ADR 0038, ADR 0046). An item
 *   discount the quote took off an order line is spread over the pieces that line is made of; the
 *   order-level discount over every goods piece; a points redemption over the goods pieces after both.
 *   Each step is pro rata to what the piece is still worth, with the remainder placed deterministically
 *   (see below).</li>
 * <li><strong>The delivery fee carries no share of any discount.</strong> It sits outside the goods
 *   subtotal the quote discounts, and the default redemption policy excludes it (ADR 0046).</li>
 * <li><strong>VAT is the quote's recorded share, split, and never recomputed</strong> (ADR 0038): each
 *   order line's recorded tax is divided between its pieces in proportion to what they cost, so the
 *   receipt's VAT is the order's to the minor unit. <strong>One exception, named: a points redemption
 *   moves the VAT base.</strong> Under ADR 0018 prices are VAT-inclusive and a discount reduces the
 *   gross tax is extracted from, so what a merchant-funded rebate takes off the consideration takes the
 *   tax with it; the quote's share is then the tax of a bigger sale than was made. The goods' VAT is
 *   extracted again, once, from what the goods were actually paid, exactly as the pricing engine does,
 *   and divided between lines and pieces the same way (ADR 0046's worked order: food 84 000, points
 *   12 000, VAT 7 714, not 9 000). The confirmation that this is the right tax treatment is finance's
 *   and legal's, and is the first open input of ADR 0046.</li>
 * <li><strong>The remainder goes to the largest piece that can bear it</strong> (ADR 0038's rule for
 *   ADR 0018's rounding remainder, which ADR 0046 reuses): shares are floored, and what is left over is
 *   added to the piece with the largest weight (the first, on a tie), never past what that piece is
 *   worth, spilling to the next. The same input always produces the same output, so a correction
 *   document re-derived later reproduces the receipt that was issued and not a near miss.</li>
 * </ol>
 *
 * <p>Everything is in minor units; nothing is a floating point number.
 */
public final class ReceiptLineAllocator {

    private ReceiptLineAllocator() {}

    /**
     * One thing the receipt bills for: the variant part of an order line, one of its options, or the
     * delivery fee.
     *
     * @param key identifies the piece in the result; unique within an input
     * @param group the order line the piece belongs to (the fee is a group of its own)
     * @param grossMinor what the piece cost before any discount, VAT included. Not negative
     * @param goods false for the delivery fee, which takes no discount
     */
    public record Piece(String key, String group, long grossMinor, boolean goods) {

        public Piece {
            if (grossMinor < 0) {
                throw new IllegalArgumentException("A piece's gross value is not negative");
            }
        }
    }

    /**
     * What the quote recorded about one order line.
     *
     * @param itemDiscountMinor what was taken off the line itself (its base less its final amount)
     * @param recordedTaxMinor the quote's tax share of the line
     */
    public record Group(String key, long itemDiscountMinor, long recordedTaxMinor) {

        public Group {
            if (itemDiscountMinor < 0 || recordedTaxMinor < 0) {
                throw new IllegalArgumentException("A line's discount and tax are not negative");
            }
        }
    }

    /**
     * @param orderDiscountMinor the order-level discount, taken off no line by the quote
     * @param redeemedMinor the settled points tender, to be shown as a per-piece discount
     * @param vatBasisPoints the rate tax was extracted at, 1200 for 12 percent; read only when something
     *     was redeemed
     */
    public record Input(
            List<Piece> pieces, List<Group> groups, long orderDiscountMinor, long redeemedMinor, int vatBasisPoints) {

        public Input {
            pieces = List.copyOf(pieces);
            groups = List.copyOf(groups);
            if (orderDiscountMinor < 0 || redeemedMinor < 0 || vatBasisPoints < 0) {
                throw new IllegalArgumentException("A discount, a redemption and a rate are not negative");
            }
        }
    }

    /**
     * What the receipt states for one piece.
     *
     * @param discountMinor everything taken off the piece: item discount, its share of the order discount
     *     and its share of the redemption
     * @param netMinor what was paid for it
     * @param taxMinor the VAT inside {@code netMinor}
     */
    public record Allocation(String key, long grossMinor, long discountMinor, long netMinor, long taxMinor) {}

    /** @return one allocation per input piece, in the input's order, zeros included */
    public static List<Allocation> allocate(Input input) {
        List<Piece> pieces = input.pieces();
        int count = pieces.size();
        long[] gross = new long[count];
        for (int i = 0; i < count; i++) {
            gross[i] = pieces.get(i).grossMinor();
        }
        long[] discount = new long[count];

        // 1. The item discount of each order line, spread over what that line is made of.
        for (Group group : input.groups()) {
            List<Integer> members = membersOf(pieces, group.key(), true);
            if (group.itemDiscountMinor() == 0) {
                continue;
            }
            long[] weights = pick(gross, members);
            long[] shares = apportion(group.itemDiscountMinor(), weights, weights);
            for (int j = 0; j < members.size(); j++) {
                discount[members.get(j)] += shares[j];
            }
        }

        // 2. The order-level discount, over every goods piece by what it is still worth.
        List<Integer> goods = goodsOf(pieces);
        if (input.orderDiscountMinor() > 0) {
            long[] weights = remaining(gross, discount, goods);
            long[] shares = apportion(input.orderDiscountMinor(), weights, weights);
            for (int j = 0; j < goods.size(); j++) {
                discount[goods.get(j)] += shares[j];
            }
        }

        // 3. The points redemption, over the goods pieces, by ADR 0046's own pure allocation.
        if (input.redeemedMinor() > 0) {
            long[] remaining = remaining(gross, discount, goods);
            List<RedemptionAllocation.Line> lines = new ArrayList<>(goods.size());
            for (int j = 0; j < goods.size(); j++) {
                lines.add(new RedemptionAllocation.Line(
                        idOf(pieces.get(goods.get(j)).key()), remaining[j], true));
            }
            List<RedemptionAllocation.LineDiscount> shares =
                    RedemptionAllocation.allocate(lines, input.redeemedMinor());
            for (int j = 0; j < goods.size(); j++) {
                discount[goods.get(j)] += shares.get(j).discountMinor();
            }
        }

        long[] net = new long[count];
        for (int i = 0; i < count; i++) {
            net[i] = gross[i] - discount[i];
        }

        // 4. Tax.
        long[] tax = new long[count];
        long[] groupTax = new long[input.groups().size()];
        if (input.redeemedMinor() > 0) {
            // The base moved: extract once from what the goods were paid, then divide between lines.
            long goodsNet = 0;
            for (int index : goods) {
                goodsNet += net[index];
            }
            long extracted = extractInclusive(goodsNet, input.vatBasisPoints());
            long[] lineWeights = new long[input.groups().size()];
            for (int g = 0; g < input.groups().size(); g++) {
                for (int index : membersOf(pieces, input.groups().get(g).key(), true)) {
                    lineWeights[g] += net[index];
                }
            }
            groupTax = apportion(extracted, lineWeights);
        } else {
            for (int g = 0; g < groupTax.length; g++) {
                groupTax[g] = input.groups().get(g).recordedTaxMinor();
            }
        }
        for (int g = 0; g < input.groups().size(); g++) {
            List<Integer> members = membersOf(pieces, input.groups().get(g).key(), false);
            long[] shares = apportion(groupTax[g], pick(net, members));
            for (int j = 0; j < members.size(); j++) {
                tax[members.get(j)] = shares[j];
            }
        }

        List<Allocation> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new Allocation(pieces.get(i).key(), gross[i], discount[i], net[i], tax[i]));
        }
        return List.copyOf(result);
    }

    /**
     * The unit price a provider is told, and the discount that makes the line add up to what was paid.
     *
     * <p>Both providers add up {@code unit price × count − discount} and compare it with the payment, so
     * the line has to be exact in whole minor units whatever the count is. The unit price is therefore the
     * smallest one for which {@code unit × count} is a whole minor unit that is not below the piece's gross
     * value; the shortfall between that and the gross is a rounding the line states as discount, strictly
     * less than the numerator of the count (zero for a count that divides the gross, which is every whole
     * count the menu sells at a round price). A weighed {@code 1.040} kg is {@code 26/25}: the unit price is
     * a multiple of 25 and the rounding is under 26 minor units on a line of tens of thousands.
     *
     * @param grossMinor what the piece cost before any discount
     * @param netMinor what was paid for it
     * @param quantity the count, positive
     */
    public static UnitPricing unitPriceOf(long grossMinor, long netMinor, BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("A count is positive");
        }
        if (netMinor < 0 || netMinor > grossMinor) {
            throw new IllegalArgumentException("A line is paid for between nothing and its gross value");
        }
        BigDecimal stripped = quantity.stripTrailingZeros();
        BigInteger numerator;
        BigInteger denominator;
        if (stripped.scale() > 0) {
            denominator = BigInteger.TEN.pow(stripped.scale());
            numerator = stripped.unscaledValue();
            BigInteger common = numerator.gcd(denominator);
            numerator = numerator.divide(common);
            denominator = denominator.divide(common);
        } else {
            numerator = stripped.toBigIntegerExact();
            denominator = BigInteger.ONE;
        }
        // unit = denominator × k, count × unit = numerator × k; the least k with numerator × k >= gross.
        BigInteger k = BigInteger.valueOf(grossMinor)
                .add(numerator)
                .subtract(BigInteger.ONE)
                .divide(numerator);
        long unit = denominator.multiply(k).longValueExact();
        long listed = numerator.multiply(k).longValueExact();
        return new UnitPricing(unit, listed - netMinor);
    }

    /**
     * @param unitMinor the unit price
     * @param discountMinor what the line states as discount so that {@code unit × count − discount} is
     *     what was paid: the real discounts plus the rounding above
     */
    public record UnitPricing(long unitMinor, long discountMinor) {}

    /** Tax extracted from a VAT-inclusive amount, rounded half up to a whole minor unit once (ADR 0018). */
    static long extractInclusive(long grossMinor, int basisPoints) {
        if (basisPoints == 0 || grossMinor == 0) {
            return 0;
        }
        return BigDecimal.valueOf(grossMinor)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10_000L + basisPoints), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * Splits {@code total} in proportion to {@code weights}: floored shares, then the remainder to the
     * largest weight (the first on a tie). For tax, which a piece may bear in any amount.
     */
    static long[] apportion(long total, long[] weights) {
        long[] unlimited = new long[weights.length];
        java.util.Arrays.fill(unlimited, Long.MAX_VALUE);
        return apportion(total, weights, unlimited);
    }

    /**
     * As {@link #apportion(long, long[])}, but no share may exceed its capacity: the remainder goes to the
     * largest weight up to what it can still bear and then on to the next largest. A discount is limited by
     * what the piece is worth, so that no line of a receipt is ever negative.
     */
    static long[] apportion(long total, long[] weights, long[] capacity) {
        long[] shares = new long[weights.length];
        long weightSum = 0;
        for (long weight : weights) {
            weightSum += weight;
        }
        if (total == 0) {
            return shares;
        }
        if (weightSum == 0) {
            throw new IllegalArgumentException("Nothing to apportion " + total + " over: every weight is zero");
        }
        long allocated = 0;
        for (int i = 0; i < weights.length; i++) {
            shares[i] = BigInteger.valueOf(total)
                    .multiply(BigInteger.valueOf(weights[i]))
                    .divide(BigInteger.valueOf(weightSum))
                    .longValueExact();
            allocated += shares[i];
        }
        long remainder = total - allocated;
        if (remainder > 0) {
            List<Integer> order = new ArrayList<>(weights.length);
            for (int i = 0; i < weights.length; i++) {
                order.add(i);
            }
            order.sort(Comparator.<Integer>comparingLong(i -> -weights[i]).thenComparingInt(i -> i));
            for (int index : order) {
                if (remainder == 0) {
                    break;
                }
                long room = Math.min(remainder, capacity[index] - shares[index]);
                if (room > 0) {
                    shares[index] += room;
                    remainder -= room;
                }
            }
            if (remainder > 0) {
                throw new IllegalArgumentException("The pieces cannot bear " + total + ": nothing is left to take it");
            }
        }
        return shares;
    }

    private static List<Integer> membersOf(List<Piece> pieces, String group, boolean goodsOnly) {
        List<Integer> members = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            if (group.equals(piece.group()) && (!goodsOnly || piece.goods())) {
                members.add(i);
            }
        }
        return members;
    }

    /** Every goods piece of the order: what a discount that belongs to no line is spread over. */
    private static List<Integer> goodsOf(List<Piece> pieces) {
        List<Integer> members = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).goods()) {
                members.add(i);
            }
        }
        return members;
    }

    /** The pieces of a group for tax: the fee's group has the fee in it, goods or not. */
    private static long[] pick(long[] values, List<Integer> members) {
        long[] picked = new long[members.size()];
        for (int j = 0; j < picked.length; j++) {
            picked[j] = values[members.get(j)];
        }
        return picked;
    }

    private static long[] remaining(long[] gross, long[] discount, List<Integer> members) {
        long[] left = new long[members.size()];
        for (int j = 0; j < left.length; j++) {
            left[j] = gross[members.get(j)] - discount[members.get(j)];
        }
        return left;
    }

    /** A stable identifier for a piece, which {@link RedemptionAllocation} wants as a {@link UUID}. */
    private static UUID idOf(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
