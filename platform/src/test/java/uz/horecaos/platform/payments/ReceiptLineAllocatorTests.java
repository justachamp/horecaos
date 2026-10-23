package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.Allocation;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.Group;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.Input;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.Piece;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.UnitPricing;

/**
 * The arithmetic of a receipt, pinned by tables (ADR 0166 Decision 9, ADR 0038, ADR 0046).
 *
 * <p>The properties a test that merely checks «it produced numbers» would miss: the receipt is exactly
 * the payment, no piece is ever discounted below nothing, the VAT is the quote's recorded share (and moves
 * only when points were redeemed), and the same input always gives the same output.
 */
class ReceiptLineAllocatorTests {

    private static final int TWELVE_PERCENT = 1200;

    private static Piece goods(String key, String group, long gross) {
        return new Piece(key, group, gross, true);
    }

    private static Allocation of(List<Allocation> allocations, String key) {
        return allocations.stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------ ADR 0046

    @Test
    @DisplayName(
            "ADR 0046's worked order: food 84 000, fee 10 000, 12 000 of points -> 82 000 net, VAT 7 714 on the food")
    void theWorkedOrderOfAdr0046() {
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("food", "L1", 84_000), new Piece("fee", "FEE", 10_000, false)),
                List.of(new Group("L1", 0, 9_000), new Group("FEE", 0, 0)),
                0,
                12_000,
                TWELVE_PERCENT));

        assertThat(of(result, "food").discountMinor())
                .as("the redemption is a per-line discount on the food")
                .isEqualTo(12_000);
        assertThat(of(result, "food").netMinor()).isEqualTo(72_000);
        assertThat(of(result, "food").taxMinor())
                .as("the VAT base moved with the consideration: round(72 000 x 1200 / 11 200), not the quote's 9 000")
                .isEqualTo(7_714);
        assertThat(of(result, "fee").discountMinor())
                .as("the delivery fee carries no share of the redemption")
                .isZero();
        assertThat(of(result, "fee").netMinor()).isEqualTo(10_000);
        assertThat(of(result, "fee").taxMinor()).isZero();
        assertThat(result.stream().mapToLong(Allocation::netMinor).sum())
                .as("the receipt equals the Click payment")
                .isEqualTo(82_000);
    }

    @Test
    @DisplayName("without a redemption the VAT is the quote's recorded share, split and never recomputed")
    void taxIsTheQuotesShare() {
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("food", "L1", 84_000), new Piece("fee", "FEE", 10_000, false)),
                List.of(new Group("L1", 0, 9_000), new Group("FEE", 0, 0)),
                0,
                0,
                TWELVE_PERCENT));

        assertThat(of(result, "food").taxMinor()).isEqualTo(9_000);
        assertThat(of(result, "fee").taxMinor()).isZero();
    }

    @Test
    @DisplayName("a line's recorded tax is divided between its pieces in proportion to what they cost, summing exactly")
    void aLinesTaxIsSplitBetweenItsPieces() {
        // A dish of 72 000 with an option of 12 000, on a line whose recorded VAT is 9 000.
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("dish", "L1", 72_000), goods("cheese", "L1", 12_000)),
                List.of(new Group("L1", 0, 9_000)),
                0,
                0,
                TWELVE_PERCENT));

        // 9 000 x 72/84 = 7 714.28 and x 12/84 = 1 285.71: floored, and the spare unit to the larger piece.
        assertThat(of(result, "dish").taxMinor()).isEqualTo(7_715);
        assertThat(of(result, "cheese").taxMinor()).isEqualTo(1_285);
        assertThat(result.stream().mapToLong(Allocation::taxMinor).sum())
                .as("the receipt's VAT is the order's to the minor unit")
                .isEqualTo(9_000);
    }

    @Test
    @DisplayName("a redemption over two lines moves the VAT once, then divides it between lines by what they were paid")
    void aRedemptionMovesTheTaxOnceAcrossLines() {
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("a", "L1", 60_000), goods("b", "L2", 40_000)),
                List.of(new Group("L1", 0, 6_429), new Group("L2", 0, 4_286)),
                0,
                10_000,
                TWELVE_PERCENT));

        // 100 000 - 10 000 = 90 000 paid; tax extracted once: round(90 000 x 1200 / 11 200) = 9 643.
        assertThat(of(result, "a").discountMinor()).isEqualTo(6_000);
        assertThat(of(result, "b").discountMinor()).isEqualTo(4_000);
        assertThat(result.stream().mapToLong(Allocation::taxMinor).sum()).isEqualTo(9_643);
        assertThat(result.stream().mapToLong(Allocation::netMinor).sum()).isEqualTo(90_000);
    }

    @Test
    @DisplayName("a redemption larger than the goods is refused, not clamped")
    void aRedemptionLargerThanTheGoodsIsRefused() {
        Input input = new Input(
                List.of(goods("a", "L1", 5_000), new Piece("fee", "FEE", 10_000, false)),
                List.of(new Group("L1", 0, 0), new Group("FEE", 0, 0)),
                0,
                6_000,
                TWELVE_PERCENT);

        assertThatThrownBy(() -> ReceiptLineAllocator.allocate(input)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------- the remainder rule

    @Test
    @DisplayName(
            "an item discount is spread pro rata over what the line is made of, the remainder to the largest piece")
    void anItemDiscountIsSpreadOverTheLine() {
        // 1 001 over 30 000 and 10 000: 750.75 and 250.25 -> 750 and 250, the spare 1 to the larger.
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("dish", "L1", 30_000), goods("sauce", "L1", 10_000)),
                List.of(new Group("L1", 1_001, 0)),
                0,
                0,
                TWELVE_PERCENT));

        assertThat(of(result, "dish").discountMinor()).isEqualTo(751);
        assertThat(of(result, "sauce").discountMinor()).isEqualTo(250);
    }

    @Test
    @DisplayName("the order-level discount is spread over every goods piece by what each is still worth")
    void theOrderDiscountIsSpreadOverTheGoods() {
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("a", "L1", 50_000), goods("b", "L2", 30_000), new Piece("fee", "FEE", 8_000, false)),
                List.of(new Group("L1", 0, 0), new Group("L2", 0, 0), new Group("FEE", 0, 0)),
                8_000,
                0,
                TWELVE_PERCENT));

        assertThat(of(result, "a").discountMinor()).isEqualTo(5_000);
        assertThat(of(result, "b").discountMinor()).isEqualTo(3_000);
        assertThat(of(result, "fee").discountMinor())
                .as("the fee is outside the discounted subtotal")
                .isZero();
    }

    @Test
    @DisplayName("the remainder never pushes a piece below nothing: it spills to the next largest")
    void theRemainderSpillsInsteadOfGoingNegative() {
        // Three pieces of 3 and a discount of 8: floors give 2, 2, 2 and two left over. Handed to one piece
        // that is a discount of 4 on a piece that cost 3.
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("a", "L1", 3), goods("b", "L2", 3), goods("c", "L3", 3)),
                List.of(new Group("L1", 0, 0), new Group("L2", 0, 0), new Group("L3", 0, 0)),
                8,
                0,
                0));

        assertThat(result).allSatisfy(a -> assertThat(a.netMinor()).isBetween(0L, 3L));
        assertThat(result.stream().mapToLong(Allocation::discountMinor).sum()).isEqualTo(8);
        assertThat(result.stream().map(Allocation::discountMinor)).containsExactly(3L, 3L, 2L);
    }

    @Test
    @DisplayName("the same input gives the same output, so a correction re-derived later reproduces the receipt")
    void theAllocationIsDeterministic() {
        Input input = new Input(
                List.of(goods("a", "L1", 3), goods("b", "L2", 3), goods("c", "L3", 3)),
                List.of(new Group("L1", 0, 1), new Group("L2", 0, 1), new Group("L3", 0, 1)),
                4,
                2,
                TWELVE_PERCENT);

        assertThat(ReceiptLineAllocator.allocate(input)).isEqualTo(ReceiptLineAllocator.allocate(input));
    }

    @Test
    @DisplayName("discounts compose: item, then order, then points, each on what the one before left")
    void discountsCompose() {
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("dish", "L1", 100_000), goods("drink", "L2", 20_000)),
                List.of(new Group("L1", 10_000, 0), new Group("L2", 0, 0)),
                11_000,
                9_000,
                TWELVE_PERCENT));

        // After the item discount: 90 000 and 20 000. Order discount 11 000 over 110 000: 9 000 and 2 000.
        // After that: 81 000 and 18 000. Points 9 000 over 99 000: 7 363 and 1 636, the spare to the larger.
        assertThat(of(result, "dish").discountMinor()).isEqualTo(10_000 + 9_000 + 7_364);
        assertThat(of(result, "drink").discountMinor()).isEqualTo(0 + 2_000 + 1_636);
        assertThat(result.stream().mapToLong(Allocation::netMinor).sum()).isEqualTo(120_000 - 10_000 - 11_000 - 9_000);
    }

    @Test
    @DisplayName("a free piece takes no part and a zero rate extracts no tax")
    void freePiecesAndZeroRate() {
        List<Allocation> result = ReceiptLineAllocator.allocate(new Input(
                List.of(goods("dish", "L1", 10_000), goods("free", "L1", 0)),
                List.of(new Group("L1", 0, 0)),
                0,
                1_000,
                0));

        assertThat(of(result, "free").discountMinor()).isZero();
        assertThat(of(result, "dish").taxMinor()).isZero();
        assertThat(of(result, "dish").netMinor()).isEqualTo(9_000);
    }

    // ------------------------------------------------------- unit pricing

    static Stream<Arguments> unitPricing() {
        return Stream.of(
                // gross, net, quantity, unit, discount
                Arguments.of(84_000L, 84_000L, "1", 84_000L, 0L),
                Arguments.of(84_000L, 72_000L, "1", 84_000L, 12_000L),
                // Three for 100 001: not divisible, so the rounding is stated as discount (the unit is 33 334).
                Arguments.of(100_001L, 100_001L, "3", 33_334L, 1L),
                Arguments.of(30_000L, 30_000L, "2", 15_000L, 0L),
                // A half portion at 40 000: the count 1/2 needs a unit that is a multiple of 2.
                Arguments.of(40_000L, 40_000L, "0.5", 80_000L, 0L),
                // A weighed 1.040 kg (26/25): unit a multiple of 25, listed 83 200 for a line of 83 199.
                Arguments.of(83_199L, 83_199L, "1.040", 80_000L, 1L),
                // The weighed line exactly divisible.
                Arguments.of(83_200L, 83_200L, "1.04", 80_000L, 0L),
                Arguments.of(83_200L, 80_000L, "1.04", 80_000L, 3_200L));
    }

    @ParameterizedTest(name = "gross {0}, net {1}, count {2} -> unit {3}, discount {4}")
    @MethodSource("unitPricing")
    @DisplayName("the unit price is exact: unit x count - discount is what was paid, for a whole or a decimal count")
    void unitPriceMakesTheLineAddUp(long gross, long net, String quantity, long unit, long discount) {
        BigDecimal count = new BigDecimal(quantity);

        UnitPricing pricing = ReceiptLineAllocator.unitPriceOf(gross, net, count);

        assertThat(pricing.unitMinor()).isEqualTo(unit);
        assertThat(pricing.discountMinor()).isEqualTo(discount);
        BigDecimal listed = BigDecimal.valueOf(pricing.unitMinor()).multiply(count);
        assertThat(listed.stripTrailingZeros().scale())
                .as("unit x count is a whole minor unit, so no provider has to round")
                .isLessThanOrEqualTo(0);
        assertThat(listed.longValueExact() - pricing.discountMinor())
                .as("what the line adds up to is what was paid")
                .isEqualTo(net);
    }

    @Test
    @DisplayName("a count of nothing, or a net above the gross, is refused")
    void impossibleLinesAreRefused() {
        assertThatThrownBy(() -> ReceiptLineAllocator.unitPriceOf(100, 100, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReceiptLineAllocator.unitPriceOf(100, 101, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
