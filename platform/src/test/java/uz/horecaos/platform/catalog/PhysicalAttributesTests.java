package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes.InvalidPhysicalAttributesException;

/**
 * ADR 0137's attribute row, on literals: the constraints V0448 states in SQL are
 * stated again by the domain object, so an author is told which field is wrong
 * rather than which constraint fired.
 *
 * <p>Each rule has the case it refuses and the nearby case it must not refuse, so
 * a constructor that rejected everything could not satisfy this class.
 */
class PhysicalAttributesTests {

    @Test
    @DisplayName("a variant is weighed or measured by volume, never both")
    void weightAndVolumeAreExclusive() {
        assertThatThrownBy(() -> attributes(250, 330, false, null, null, false, null))
                .isInstanceOfSatisfying(
                        InvalidPhysicalAttributesException.class,
                        e -> assertThat(e.code()).isEqualTo("WEIGHT_AND_VOLUME_EXCLUSIVE"));

        assertThat(attributes(250, null, false, null, null, false, null).netWeightGrams())
                .isEqualTo(250);
        assertThat(attributes(null, 330, false, null, null, false, null).netVolumeMillilitres())
                .isEqualTo(330);
    }

    @Test
    @DisplayName("a catchweight variant needs the weight its price is quoted per")
    void catchweightNeedsAQuantum() {
        assertThatThrownBy(() -> attributes(1200, null, true, null, null, false, null))
                .isInstanceOfSatisfying(
                        InvalidPhysicalAttributesException.class,
                        e -> assertThat(e.code()).isEqualTo("CATCHWEIGHT_NEEDS_QUANTUM"));

        assertThat(attributes(1200, null, true, 100, null, false, null).catchweightQuantumGrams())
                .isEqualTo(100);
    }

    @Test
    @DisplayName("a catchweight variant needs a weight to quote against: its nominal one, or failing that its net one")
    void catchweightNeedsSomethingToEstimateFrom() {
        assertThatThrownBy(() -> attributes(null, null, true, 100, null, false, null))
                .isInstanceOfSatisfying(
                        InvalidPhysicalAttributesException.class,
                        e -> assertThat(e.code()).isEqualTo("CATCHWEIGHT_NEEDS_WEIGHT"));

        assertThat(attributes(null, null, true, 100, 1200, false, null).quotedUnitGrams())
                .as("the nominal weight is the menu estimate")
                .isEqualTo(1200);
        assertThat(attributes(1500, null, true, 100, null, false, null).quotedUnitGrams())
                .as("the net weight stands in when no nominal weight was entered")
                .isEqualTo(1500);
        assertThat(attributes(1500, null, true, 100, 1200, false, null).quotedUnitGrams())
                .as("the nominal weight wins over the net weight")
                .isEqualTo(1200);
    }

    @Test
    @DisplayName("a pricing quantum or a nominal weight on a variant that is not catchweight is refused")
    void catchweightFactsNeedCatchweight() {
        assertThatThrownBy(() -> attributes(250, null, false, 100, null, false, null))
                .isInstanceOfSatisfying(
                        InvalidPhysicalAttributesException.class,
                        e -> assertThat(e.code()).isEqualTo("CATCHWEIGHT_FIELDS_WITHOUT_CATCHWEIGHT"));
        assertThatThrownBy(() -> attributes(250, null, false, null, 240, false, null))
                .isInstanceOf(InvalidPhysicalAttributesException.class);
    }

    @Test
    @DisplayName("a decimal portion size belongs to a splittable variant, and only a splittable one is fractional")
    void portionSizeNeedsASplittableVariant() {
        assertThatThrownBy(() -> attributes(null, null, false, null, null, false, new BigDecimal("0.5")))
                .isInstanceOfSatisfying(
                        InvalidPhysicalAttributesException.class,
                        e -> assertThat(e.code()).isEqualTo("PORTION_SIZE_NEEDS_SPLITTABLE"));

        PhysicalAttributes half = attributes(null, null, false, null, null, true, new BigDecimal("0.5"));
        assertThat(half.allowsFractionalQuantity()).isTrue();
        assertThat(attributes(null, null, false, null, null, true, null).allowsFractionalQuantity())
                .as("splittable without a portion step is still whole-units only")
                .isFalse();
    }

    @Test
    @DisplayName("a portion size is positive, at most three fraction digits, and canonical")
    void portionSizeIsPositiveAndCanonical() {
        assertThatThrownBy(() -> attributes(null, null, false, null, null, true, BigDecimal.ZERO))
                .isInstanceOf(InvalidPhysicalAttributesException.class);
        assertThatThrownBy(() -> attributes(null, null, false, null, null, true, new BigDecimal("0.0001")))
                .isInstanceOfSatisfying(
                        InvalidPhysicalAttributesException.class,
                        e -> assertThat(e.code()).isEqualTo("PORTION_SIZE_TOO_PRECISE"));

        assertThat(attributes(null, null, false, null, null, true, new BigDecimal("0.500"))
                        .portionSize())
                .as("0.500 and 0.5 are one portion size")
                .isEqualTo(new BigDecimal("0.5"));
        assertThat(String.valueOf(attributes(null, null, false, null, null, true, new BigDecimal("10"))
                        .portionSize()))
                .as("10 stays 10; a bare stripTrailingZeros would make it 1E+1")
                .isEqualTo("10");
    }

    @Test
    @DisplayName("measures are positive: zero and negative are not physical quantities")
    void measuresArePositive() {
        assertThatThrownBy(() -> attributes(0, null, false, null, null, false, null))
                .isInstanceOf(InvalidPhysicalAttributesException.class);
        assertThatThrownBy(() -> attributes(-5, null, false, null, null, false, null))
                .isInstanceOf(InvalidPhysicalAttributesException.class);
        assertThatThrownBy(() -> attributes(null, 0, false, null, null, false, null))
                .isInstanceOf(InvalidPhysicalAttributesException.class);
        assertThatThrownBy(() -> attributes(1200, null, true, 0, null, false, null))
                .isInstanceOf(InvalidPhysicalAttributesException.class);
    }

    @Test
    @DisplayName("КБЖУ per 100 g cannot be negative, and a macro cannot exceed 100 g per 100 g")
    void nutritionHasSaneBounds() {
        assertThatThrownBy(() -> nutrition(new BigDecimal("-1"), null, null, null))
                .isInstanceOfSatisfying(
                        InvalidPhysicalAttributesException.class,
                        e -> assertThat(e.code()).isEqualTo("CALORIES_NEGATIVE"));
        assertThatThrownBy(() -> nutrition(null, new BigDecimal("100.01"), null, null))
                .isInstanceOfSatisfying(
                        InvalidPhysicalAttributesException.class,
                        e -> assertThat(e.code()).isEqualTo("PROTEIN_OUT_OF_RANGE"));
        assertThatThrownBy(() -> nutrition(null, null, new BigDecimal("-0.1"), null))
                .isInstanceOf(InvalidPhysicalAttributesException.class);
        assertThatThrownBy(() -> nutrition(null, null, null, new BigDecimal("101")))
                .isInstanceOf(InvalidPhysicalAttributesException.class);

        PhysicalAttributes boundary = nutrition(new BigDecimal("0"), new BigDecimal("100"), BigDecimal.ZERO, null);
        assertThat(boundary.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("an attribute set with nothing in it is empty, and so is not worth a row")
    void anEmptySetIsEmpty() {
        assertThat(attributes(null, null, false, null, null, false, null).isEmpty())
                .isTrue();
        assertThat(attributes(1, null, false, null, null, false, null).isEmpty())
                .isFalse();
        assertThat(attributes(null, null, false, null, null, true, null).isEmpty())
                .as("splittable alone is a fact")
                .isFalse();
        assertThat(nutrition(new BigDecimal("250"), null, null, null).isEmpty()).isFalse();
    }

    private static PhysicalAttributes attributes(
            @Nullable Integer weight,
            @Nullable Integer volume,
            boolean catchweight,
            @Nullable Integer quantum,
            @Nullable Integer nominal,
            boolean splittable,
            @Nullable BigDecimal portion) {
        return new PhysicalAttributes(
                weight, volume, catchweight, quantum, nominal, splittable, portion, null, null, null, null);
    }

    private static PhysicalAttributes nutrition(
            @Nullable BigDecimal calories,
            @Nullable BigDecimal protein,
            @Nullable BigDecimal fat,
            @Nullable BigDecimal carbohydrates) {
        return new PhysicalAttributes(
                null, null, false, null, null, false, null, calories, protein, fat, carbohydrates);
    }
}
