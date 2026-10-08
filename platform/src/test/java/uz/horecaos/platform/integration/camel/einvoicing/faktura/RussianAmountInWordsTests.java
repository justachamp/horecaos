package uz.horecaos.platform.integration.camel.einvoicing.faktura;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An amount in words as a printed invoice writes it. Faktura.uz marks the amounts-in-words fields
 * required, so a wrong gender or plural is a wrong invoice, not a typo.
 */
class RussianAmountInWordsTests {

    @Test
    @DisplayName("whole sums read with the right gender and number")
    void wholeSums() {
        assertThat(RussianAmountInWords.sums(new BigDecimal("0"))).isEqualTo("Ноль сум 00 тийин");
        assertThat(RussianAmountInWords.sums(new BigDecimal("1"))).isEqualTo("Один сум 00 тийин");
        assertThat(RussianAmountInWords.sums(new BigDecimal("2"))).isEqualTo("Два сум 00 тийин");
        assertThat(RussianAmountInWords.sums(new BigDecimal("15"))).isEqualTo("Пятнадцать сум 00 тийин");
        assertThat(RussianAmountInWords.sums(new BigDecimal("21"))).isEqualTo("Двадцать один сум 00 тийин");
        assertThat(RussianAmountInWords.sums(new BigDecimal("100"))).isEqualTo("Сто сум 00 тийин");
        assertThat(RussianAmountInWords.sums(new BigDecimal("345"))).isEqualTo("Триста сорок пять сум 00 тийин");
    }

    @Test
    @DisplayName("a thousand is feminine and takes its three plural forms")
    void thousands() {
        assertThat(RussianAmountInWords.words(1_000)).isEqualTo("одна тысяча");
        assertThat(RussianAmountInWords.words(2_000)).isEqualTo("две тысячи");
        assertThat(RussianAmountInWords.words(5_000)).isEqualTo("пять тысяч");
        assertThat(RussianAmountInWords.words(11_000)).isEqualTo("одиннадцать тысяч");
        assertThat(RussianAmountInWords.words(12_000)).isEqualTo("двенадцать тысяч");
        assertThat(RussianAmountInWords.words(21_000)).isEqualTo("двадцать одна тысяча");
        assertThat(RussianAmountInWords.words(22_000)).isEqualTo("двадцать две тысячи");
        assertThat(RussianAmountInWords.words(111_000)).isEqualTo("сто одиннадцать тысяч");
    }

    @Test
    @DisplayName("millions and billions are masculine")
    void millionsAndBillions() {
        assertThat(RussianAmountInWords.words(1_000_000)).isEqualTo("один миллион");
        assertThat(RussianAmountInWords.words(2_000_000)).isEqualTo("два миллиона");
        assertThat(RussianAmountInWords.words(5_000_000)).isEqualTo("пять миллионов");
        assertThat(RussianAmountInWords.words(1_000_000_000L)).isEqualTo("один миллиард");
        assertThat(RussianAmountInWords.words(1_064_000)).isEqualTo("один миллион шестьдесят четыре тысячи");
        assertThat(RussianAmountInWords.words(1_000_001)).isEqualTo("один миллион один");
    }

    @Test
    @DisplayName("the tiyin are written as two digits, and the amount is rounded to a tiyin")
    void tiyin() {
        assertThat(RussianAmountInWords.sums(new BigDecimal("1064000.07")))
                .isEqualTo("Один миллион шестьдесят четыре тысячи сум 07 тийин");
        assertThat(RussianAmountInWords.sums(new BigDecimal("950000.5")))
                .isEqualTo("Девятьсот пятьдесят тысяч сум 50 тийин");
        assertThat(RussianAmountInWords.sums(new BigDecimal("0.995"))).isEqualTo("Один сум 00 тийин");
    }
}
