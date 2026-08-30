package uz.horecaos.platform.loyalty;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.loyalty.api.RedemptionAllocation;
import uz.horecaos.platform.loyalty.api.RedemptionAllocation.Line;
import uz.horecaos.platform.loyalty.api.RedemptionAllocation.LineDiscount;
import uz.horecaos.platform.loyalty.domain.RedemptionLimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What a redemption becomes on a receipt, and how much of an order it may cover
 * (ADR 0046).
 *
 * <p>No database. Both of these are pure arithmetic over whole som, and they are
 * the arithmetic a customer, a tenant's accountant, and eventually a tax
 * inspector will each check by hand. A test that needed a container to assert
 * that 12 000 allocated across three lines still sums to 12 000 would be
 * asserting the wrong thing.
 */
class RedemptionAllocationTests {

    private static final UUID PLOV = UUID.randomUUID();
    private static final UUID SALAD = UUID.randomUUID();
    private static final UUID TEA = UUID.randomUUID();
    private static final UUID DELIVERY = UUID.randomUUID();

    @Test
    @DisplayName("the worked order: 12 000 of points across the food lines, none on the fee, "
            + "and the lines net of discount equal the money tender")
    void theWorkedOrderAllocates() {
        // ADR 0046's worked example. 84 000 of food, a 10 000 delivery fee,
        // 12 000 settled from points and 82 000 by Click.
        List<Line> lines = List.of(
                new Line(PLOV, 60_000L, true),
                new Line(SALAD, 18_000L, true),
                new Line(TEA, 6_000L, true),
                new Line(DELIVERY, 10_000L, false));

        List<LineDiscount> allocated = RedemptionAllocation.allocate(lines, 12_000L);

        long total = allocated.stream().mapToLong(LineDiscount::discountMinor).sum();
        assertThat(total)
                .as("the allocated discount reconciles to the points tender exactly, or the "
                        + "receipt does not balance against what the provider settled")
                .isEqualTo(12_000L);

        assertThat(discountOf(allocated, DELIVERY))
                .as("the default policy excludes the delivery fee, so its own classification and "
                        + "VAT are untouched by a redemption")
                .isZero();

        long linesNetOfDiscount = lines.stream()
                .mapToLong(line -> line.grossMinor() - discountOf(allocated, line.lineId()))
                .sum();
        assertThat(linesNetOfDiscount)
                .as("the lines net of discount equal the money tender, which is what Click's "
                        + "submit_items requires of the payment it fiscalizes")
                .isEqualTo(82_000L);
    }

    @Test
    @DisplayName("the remainder goes to the highest-value line, so the lines still sum")
    void theRemainderLandsOnTheLargestLine() {
        // 1 000 over three lines of 1 000, 1 000 and 1 001 divides unevenly, and
        // three truncated shares are one som short. ADR 0038 already puts the
        // rounding remainder on the highest-value line, so this composes with it
        // instead of fighting it for the same som.
        List<Line> lines = List.of(
                new Line(PLOV, 1_000L, true),
                new Line(SALAD, 1_000L, true),
                new Line(TEA, 1_001L, true));

        List<LineDiscount> allocated = RedemptionAllocation.allocate(lines, 1_000L);

        assertThat(allocated.stream().mapToLong(LineDiscount::discountMinor).sum())
                .isEqualTo(1_000L);
        assertThat(discountOf(allocated, TEA))
                .as("the highest-value line carries the remainder")
                .isGreaterThan(discountOf(allocated, PLOV));
    }

    @Test
    @DisplayName("a redemption larger than the lines it applies to is refused, not clamped")
    void anOversizedRedemptionIsRefused() {
        List<Line> lines = List.of(new Line(PLOV, 5_000L, true),
                new Line(DELIVERY, 10_000L, false));

        // Clamping would hide a redemption cap that was not enforced upstream and
        // would produce a receipt whose discount nobody could reconcile to a
        // tender.
        assertThat(catchThrowable(() -> RedemptionAllocation.allocate(lines, 6_000L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("every line comes back, including the ones that carry nothing")
    void everyLineIsAnswered() {
        List<Line> lines = List.of(new Line(PLOV, 5_000L, true),
                new Line(DELIVERY, 10_000L, false));

        // A caller writing discount_minor onto every fiscal_document_lines row
        // must not be able to silently skip one.
        assertThat(RedemptionAllocation.allocate(lines, 1_000L)).hasSize(2);
        assertThat(RedemptionAllocation.allocate(lines, 0L))
                .allMatch(discount -> discount.discountMinor() == 0L);
    }

    @Test
    @DisplayName("the cap is the policy share of the fee-excluded value")
    void theCapExcludesTheDeliveryFee() {
        // 94 000 total, 10 000 of it fee, 50% of the remaining 84 000.
        assertThat(RedemptionLimit.maximumRedeemable(94_000L, 10_000L, 5_000, 50_000L, true))
                .isEqualTo(42_000L);
    }

    @Test
    @DisplayName("an order below the policy minimum redeems nothing")
    void aSmallOrderRedeemsNothing() {
        // The minimum order stops a small balance producing a stream of near-free
        // small orders.
        assertThat(RedemptionLimit.maximumRedeemable(40_000L, 0L, 5_000, 50_000L, true))
                .isZero();
    }

    @Test
    @DisplayName("points can never cover the whole order, whatever the policy says")
    void someMoneyAlwaysChangesHands() {
        // A 100% share is refused by the database; this is the second wall. An
        // order with no money tender has no fiscal path — no Click payment to
        // hang submit_items on, no Payme receipt — and on a cash order it is a
        // courier who collects nothing while handing over food.
        long cap = RedemptionLimit.maximumRedeemable(50_000L, 0L, 10_000, 0L, false);

        assertThat(cap).isLessThan(50_000L);
        assertThat(50_000L - cap)
                .as("at least one som of money is left on the order")
                .isGreaterThanOrEqualTo(1L);
    }

    private static long discountOf(List<LineDiscount> allocated, UUID lineId) {
        return allocated.stream().filter(discount -> discount.lineId().equals(lineId))
                .mapToLong(LineDiscount::discountMinor).findFirst().orElseThrow();
    }
}
