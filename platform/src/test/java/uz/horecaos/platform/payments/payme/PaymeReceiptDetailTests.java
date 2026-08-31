package uz.horecaos.platform.payments.payme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.domain.TiyinAmount;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeReceiptDetail;

/**
 * Payme's {@code detail} object, and the trap in the middle of it.
 *
 * <p>Click's {@code Price} is the <em>line total</em> and Payme's {@code price} is
 * the <em>unit price</em>. Same word, a factor of quantity apart. These tests exist
 * mostly so that a future refactor which introduces a shared line builder between
 * the two adapters fails here rather than fiscalizing an order at quantity squared
 * times its value.
 */
class PaymeReceiptDetailTests {

    private static final String UZS = "UZS";

    @Test
    @DisplayName("price is the unit price, not the line total")
    void priceIsTheUnitPrice() {
        FiscalReceiptLine line = line("Лагман", 3, 25_000, null);

        Map<String, Object> detail = PaymeReceiptDetail.of(List.of(line), TiyinAmount.of(new SomAmount(75_000, UZS)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                Objects.requireNonNull((List<Map<String, Object>>) detail.get("items"), "detail must carry items");
        assertThat(items).hasSize(1);
        // 25 000 som a portion, in tiyin, and three portions. Click would have been
        // sent 7 500 000 in the same field.
        assertThat(items.getFirst()).containsEntry("price", 2_500_000L);
        assertThat(items.getFirst()).containsEntry("count", 3);
        assertThat(detail).containsEntry("receipt_type", 0);
    }

    /**
     * The discount is already multiplied out, unlike the price.
     *
     * <p>Payme documents it "с учётом количества" — across the whole line — while
     * the price beside it is per unit. Two adjacent fields with opposite conventions
     * is exactly the kind of thing that survives review and fails an inspection.
     */
    @Test
    @DisplayName("the discount is the whole line's, while the price beside it is per unit")
    void discountIsForTheWholeLine() {
        FiscalReceiptLine line = line("Лагман", 3, 25_000, new SomAmount(5_000, UZS));

        Map<String, Object> detail = PaymeReceiptDetail.of(List.of(line), TiyinAmount.of(new SomAmount(70_000, UZS)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                Objects.requireNonNull((List<Map<String, Object>>) detail.get("items"), "detail must carry items");
        assertThat(items.getFirst()).containsEntry("price", 2_500_000L).containsEntry("discount", 500_000L);
    }

    /**
     * The arithmetic the docs never state, enforced anyway (U11).
     *
     * <p>A receipt that disagrees with the amount charged is a tax problem rather
     * than a bug, and it is discovered at an inspection rather than in a log.
     */
    @Test
    @DisplayName("a receipt that does not add up to the charge is refused")
    void refusesAReceiptThatDoesNotMatchTheCharge() {
        FiscalReceiptLine line = line("Лагман", 2, 25_000, null);

        assertThatThrownBy(() -> PaymeReceiptDetail.of(List.of(line), TiyinAmount.of(new SomAmount(49_000, UZS))))
                .isInstanceOf(PaymeReceiptDetail.PaymeReceiptRefused.class)
                .hasMessageContaining("5000000")
                .hasMessageContaining("4900000");
    }

    /**
     * Payme has no field for a marking code anywhere in {@code detail}.
     *
     * <p>Click has {@code Labels}. So a marked good cannot lawfully be fiscalized
     * through Payme, and the honest response is to refuse the receipt rather than
     * emit one that is silently incomplete — a tenant selling marked goods must have
     * Payme removed from the channel's payment methods.
     */
    @Test
    @DisplayName("a marked good is refused rather than fiscalized without its marking code")
    void refusesAMarkedGood() {
        FiscalReceiptLine marked = new FiscalReceiptLine(
                "Вода",
                "00702001001000001",
                "1234",
                241092L,
                1,
                new SomAmount(10_000, UZS),
                new SomAmount(1_200, UZS),
                12,
                null,
                null,
                List.of("0104870123456789"),
                "123456789",
                null);

        assertThatThrownBy(() -> PaymeReceiptDetail.of(List.of(marked), TiyinAmount.of(new SomAmount(10_000, UZS))))
                .isInstanceOf(PaymeReceiptDetail.PaymeReceiptRefused.class)
                .extracting(refused -> ((PaymeReceiptDetail.PaymeReceiptRefused) refused).code())
                .isEqualTo("MARKING_CODES_UNSUPPORTED");
    }

    /**
     * The delivery fee is an item line and never {@code shipping}.
     *
     * <p>Payme's {@code shipping} object accepts a title and a price and nothing
     * else — no ИКПУ, no package code, no VAT rate — while ADR 0038 requires the
     * delivery fee to carry a classification and blocks publication without one.
     * Putting it in {@code shipping} would throw away the one thing a validator rule
     * was written to guarantee.
     */
    @Test
    @DisplayName("nothing is emitted as shipping")
    void emitsNoShippingObject() {
        Map<String, Object> detail = PaymeReceiptDetail.of(
                List.of(line("Лагман", 1, 25_000, null), line("Доставка", 1, 12_000, null)),
                TiyinAmount.of(new SomAmount(37_000, UZS)));

        assertThat(detail).doesNotContainKey("shipping");
        assertThat(detail).containsOnlyKeys("receipt_type", "items");
    }

    @Test
    @DisplayName("a fiscalised cashbox with no lines is refused")
    void refusesAnEmptyReceipt() {
        assertThatThrownBy(() -> PaymeReceiptDetail.of(List.of(), TiyinAmount.of(new SomAmount(1, UZS))))
                .isInstanceOf(PaymeReceiptDetail.PaymeReceiptRefused.class)
                .extracting(refused -> ((PaymeReceiptDetail.PaymeReceiptRefused) refused).code())
                .isEqualTo("NO_RECEIPT_LINES");
    }

    private static FiscalReceiptLine line(String name, int quantity, long unitPriceSom, @Nullable SomAmount discount) {
        return new FiscalReceiptLine(
                name,
                "00702001001000001",
                "1234",
                241092L,
                quantity,
                new SomAmount(unitPriceSom, UZS),
                new SomAmount(unitPriceSom / 10, UZS),
                12,
                discount,
                null,
                List.of(),
                "123456789",
                null);
    }
}
