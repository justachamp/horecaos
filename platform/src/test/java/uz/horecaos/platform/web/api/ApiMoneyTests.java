package uz.horecaos.platform.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** ADR 0031: money is an object of integer minor units and an ISO currency. */
class ApiMoneyTests {

    @Test
    void addsWithinOneCurrency() {
        assertThat(ApiMoney.of(125_000, "UZS").plus(ApiMoney.of(25_000, "UZS")))
                .isEqualTo(ApiMoney.of(150_000, "UZS"));
    }

    @Test
    void refusesToCombineDifferentCurrencies() {
        assertThatThrownBy(() -> ApiMoney.of(100, "UZS").plus(ApiMoney.of(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UZS")
                .hasMessageContaining("USD");
    }

    @Test
    void rejectsAMalformedCurrencyCode() {
        assertThatThrownBy(() -> ApiMoney.of(100, "uzs")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiMoney.of(100, "UZSS")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAWellFormedButUnknownCurrency() {
        assertThatThrownBy(() -> ApiMoney.of(100, "XYZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ISO 4217");
    }

    @Test
    void failsLoudlyOnOverflowRatherThanWrapping() {
        assertThatThrownBy(() -> ApiMoney.of(Long.MAX_VALUE, "UZS").plus(ApiMoney.of(1, "UZS")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void allowsNegativeAmountsForRefundsAndAdjustments() {
        assertThat(ApiMoney.of(1_000, "UZS").minus(ApiMoney.of(1_500, "UZS")).amountMinor())
                .isEqualTo(-500);
    }
}
