package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.web.api.Quantities;

/**
 * The magnitude of an amendment quantity is bounded before it is normalised (ADR 0137).
 *
 * <p>{@link Quantities#normalise} gives a number with a negative scale a scale of zero, which for
 * {@code 1e600000000} writes a six-hundred-million-digit integer. The service refuses a quantity
 * above 999, but only after the factories have normalised it, so an unbounded exponent cost a request
 * thread minutes of CPU and heap before the bound was ever consulted. The tests use an exponent large
 * enough that expanding it is plainly not intended and small enough that a build which still expands
 * it fails an assertion in milliseconds rather than exhausting the heap.
 */
class OrderAmendmentQuantityBoundsTests {

    private static final BigDecimal ABSURD = new BigDecimal("1E+2000000");

    @Test
    @DisplayName("a quantity change refuses an absurd magnitude as an invalid argument, before expanding it")
    void changeLineQuantityRefusesAnAbsurdMagnitude() {
        assertThatThrownBy(() -> OrderAmendmentService.AmendmentCommand.changeLineQuantity(UUID.randomUUID(), ABSURD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an added line refuses an absurd magnitude as an invalid argument, before expanding it")
    void addedLineRefusesAnAbsurdMagnitude() {
        assertThatThrownBy(() -> new OrderAmendmentService.AmendmentCommand.LineRequest(
                        UUID.randomUUID(), ABSURD, List.<UUID>of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a quantity the column can hold is still normalised: 3.000 is stored as 3")
    void anOrdinaryQuantityIsStillNormalised() {
        var line = new OrderAmendmentService.AmendmentCommand.LineRequest(
                UUID.randomUUID(), new BigDecimal("3.000"), List.<UUID>of());
        assertThat(line.quantity()).isEqualTo(new BigDecimal("3"));
        var change = OrderAmendmentService.AmendmentCommand.changeLineQuantity(UUID.randomUUID(), new BigDecimal("20"));
        assertThat(change.payload().get("quantity")).isEqualTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("fitsColumn answers false for an absurd magnitude without expanding it")
    void fitsColumnChecksTheMagnitudeFirst() {
        assertThat(Quantities.fitsColumn(ABSURD)).isFalse();
        assertThat(Quantities.fitsColumn(new BigDecimal("1E-2000000"))).isFalse();
        assertThat(Quantities.fitsColumn(new BigDecimal("9999999.999"))).isTrue();
        assertThat(Quantities.fitsColumn(new BigDecimal("0.001"))).isTrue();
    }
}
