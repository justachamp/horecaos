package uz.horecaos.platform.payments.infrastructure.click;

import java.math.BigDecimal;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How a receipt line's count becomes Click's {@code Items[].Amount} (ADR 0166, ADR 0137).
 *
 * <p>Click types {@code Amount} as {@code uint64}, so a weighed line's {@code 1.040} kg has no
 * spelling of its own, and whether the field is scaled (thousandths are common in Uzbek OFD schemas)
 * is an open question to Click in writing (the provider notes' open question, and the
 * external-connections runbook for this wave). The answer sets a <em>constant</em> and not a
 * structure, so the constant is injected: {@code horecaos.payments.click.quantity-scale}.
 *
 * <p>The scale is <strong>uniform</strong>: it multiplies every line's count, a whole one included. A
 * policy that scaled only the lines that happen to be fractional would send {@code 2} for two
 * pieces and {@code 1040} for 1.040 kg to a field that means one thing, and Click would read the
 * first as two thousandths. The default is {@code 1}, which is what every receipt filed so far
 * says: counts are sent as they are, and a fractional count is <em>refused</em> before anything
 * leaves, because rounding a weighed quantity is a misstated line on a legal document.
 */
@Component
public final class ClickQuantityScale {

    /** The default: counts as they are, whole numbers only. */
    public static final ClickQuantityScale WHOLE_COUNTS = new ClickQuantityScale(1);

    private final int scale;

    public ClickQuantityScale(@Value("${horecaos.payments.click.quantity-scale:1}") int scale) {
        if (scale < 1) {
            throw new IllegalArgumentException("A Click quantity scale is a positive multiplier");
        }
        this.scale = scale;
    }

    /**
     * The {@code Amount} for this count, or empty when the scale cannot say it exactly.
     *
     * @return {@code quantity × scale} when that is a whole number, which is the only thing a
     *     {@code uint64} can carry; empty otherwise
     */
    public OptionalLong amountOf(BigDecimal quantity) {
        BigDecimal scaled = quantity.multiply(BigDecimal.valueOf(scale)).stripTrailingZeros();
        if (scaled.scale() > 0 || scaled.signum() <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(scaled.longValueExact());
    }

    public int scale() {
        return scale;
    }
}
