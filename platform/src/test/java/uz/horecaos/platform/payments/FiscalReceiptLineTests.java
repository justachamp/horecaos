package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.SomAmount;

/**
 * The invariant that lets a decimal count be exact (ADR 0166, ADR 0137): {@code unit price × count} is a
 * whole minor unit, so neither provider is ever asked to round a receipt that has to equal the payment.
 */
class FiscalReceiptLineTests {

    private static FiscalReceiptLine line(String quantity, long unitPrice, SomAmount discount) {
        return new FiscalReceiptLine(
                "Lamb, kg",
                "10101001001000000",
                "1500321",
                166L,
                new BigDecimal(quantity),
                new SomAmount(unitPrice, "UZS"),
                new SomAmount(0, "UZS"),
                12,
                discount,
                null,
                List.of(),
                "123456789",
                null,
                FiscalReceiptLine.Kind.ITEM);
    }

    @Test
    @DisplayName("a weighed count keeps its decimals and the total is exact")
    void aWeighedCountIsExact() {
        FiscalReceiptLine line = line("1.040", 80_000, new SomAmount(1, "UZS"));

        assertThat(line.quantity()).isEqualTo(new BigDecimal("1.04"));
        assertThat(line.fractional()).isTrue();
        assertThat(line.lineTotal().value()).isEqualTo(83_200);
        assertThat(line.netTotal().value()).as("less the stated rounding").isEqualTo(83_199);
    }

    @Test
    @DisplayName("a whole count is a plain number, never an exponent")
    void aWholeCountIsPlain() {
        FiscalReceiptLine line = line("10.00", 1_000, new SomAmount(0, "UZS"));

        assertThat(line.quantity().toString()).isEqualTo("10");
        assertThat(line.fractional()).isFalse();
        assertThat(line.lineTotal().value()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("a unit price whose product with the count is not a whole minor unit is refused, not rounded")
    void aProductThatIsNotWholeIsRefused() {
        assertThatThrownBy(() -> line("1.04", 79_999, new SomAmount(0, "UZS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole minor unit");
    }

    @Test
    @DisplayName("a count of nothing is refused")
    void aCountOfNothingIsRefused() {
        assertThatThrownBy(() -> line("0", 1_000, new SomAmount(0, "UZS")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an int count is the same number as the decimal one")
    void anIntCountIsTheSameNumber() {
        FiscalReceiptLine whole = new FiscalReceiptLine(
                "Plov",
                "10901001001000000",
                "1500123",
                796L,
                3,
                new SomAmount(25_000, "UZS"),
                new SomAmount(0, "UZS"),
                12,
                null,
                null,
                List.of(),
                "123456789",
                null);

        assertThat(whole.quantity()).isEqualTo(new BigDecimal("3"));
        assertThat(whole.kind()).isEqualTo(FiscalReceiptLine.Kind.ITEM);
        assertThat(whole.lineTotal().value()).isEqualTo(75_000);
    }
}
