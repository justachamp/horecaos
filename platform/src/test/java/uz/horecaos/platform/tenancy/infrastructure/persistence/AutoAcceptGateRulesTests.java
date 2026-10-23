package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0177 decision 3: the two auto-accept gates are read at checkout now, so a malformed value is no
 * longer harmless -- a list nobody can read would hold every order, and a negative minimum nothing. The
 * shape is refused where the one writer applies it; whether each channel is real is the ordering
 * module's {@code AutoAcceptChannelsGuard}'s question.
 */
class AutoAcceptGateRulesTests {

    private static final String CHANNELS = "ordering.auto_accept_eligible_channels";
    private static final String MINIMUM = "ordering.auto_accept_min_prior_orders";

    private static void validate(String code, Object value) {
        ConfigurationValueRules.validate(ConfigurationKeys.require(code), value);
    }

    @Test
    @DisplayName("ALL, or a list of channel codes, is accepted")
    void allAndListsOfCodesAreAccepted() {
        assertThatCode(() -> validate(CHANNELS, "ALL")).doesNotThrowAnyException();
        assertThatCode(() -> validate(CHANNELS, " all ")).doesNotThrowAnyException();
        assertThatCode(() -> validate(CHANNELS, "STOREFRONT")).doesNotThrowAnyException();
        assertThatCode(() -> validate(CHANNELS, "STOREFRONT, call-centre,telegram_bot"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("blank, ALL beside a code, an empty member and a code with a space or a symbol in it are refused")
    void malformedListsAreRefused() {
        for (String bad : new String[] {
            "",
            "   ",
            "ALL,STOREFRONT",
            "STOREFRONT,all",
            "STOREFRONT,,telegram",
            "STORE FRONT",
            "STORE;FRONT",
            "x".repeat(33),
            ","
        }) {
            assertThatThrownBy(() -> validate(CHANNELS, bad))
                    .as("'%s'", bad)
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            failure -> org.assertj.core.api.Assertions.assertThat(failure.errorCode())
                                    .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("a list of forty-one channels is a typo, not a policy")
    void aSillyLongListIsRefused() {
        String forty = String.join(",", java.util.Collections.nCopies(40, "A"));
        assertThatCode(() -> validate(CHANNELS, forty)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(CHANNELS, forty + ",B")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the minimum of prior orders is zero (no gate) to a thousand")
    void theMinimumIsBounded() {
        assertThatCode(() -> validate(MINIMUM, 0)).doesNotThrowAnyException();
        assertThatCode(() -> validate(MINIMUM, 1_000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(MINIMUM, -1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validate(MINIMUM, 1_001)).isInstanceOf(ApiException.class);
    }
}
