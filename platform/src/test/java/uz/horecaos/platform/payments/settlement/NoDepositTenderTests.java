package uz.horecaos.platform.payments.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.payments.domain.PaymentMethod;

/**
 * W05 leaves stored value exactly as ADR 0046 withdrew it: not deferred, not
 * kill-switched, absent (see {@code docs/operations-gap-map.md} rows
 * {@code 5.2f} and {@code 6.3a}, both blocked on a Central Bank authorisation
 * or the owner's own decision to hold funds, and V0042's own header, "no
 * DEPOSIT account type, no CUSTOMER_DEPOSIT payment method").
 *
 * <p>Split tender's read side (the {@code payment[]} array) makes every tender
 * method reach an operator's screen by name, which is exactly the surface a
 * quietly re-added deposit method would first appear on. These tests assert
 * the absence directly against the two enums a tender can ever be planned
 * against, rather than trusting that nobody adds one back — the same shape as
 * every other "closes a back door" assertion in this module: it fails the
 * moment the back door reopens, not some time later when an operator meets it
 * in production.
 */
class NoDepositTenderTests {

    @Test
    @DisplayName("no PaymentMethod names a deposit, under any spelling")
    void paymentMethodNamesNoDeposit() {
        assertThat(Arrays.stream(PaymentMethod.values()).map(Enum::name).toList())
                .as("ADR 0046 withdrew customer-funded stored value outright; a DEPOSIT "
                        + "payment method returns only behind a new ADR, never as a value "
                        + "quietly added to this enum")
                .noneMatch(name -> name.contains("DEPOSIT"));
    }

    @Test
    @DisplayName("exactly the five methods ADR 0038's registry can register a fiscal-facing tender against")
    void paymentMethodIsExactlyItsKnownFiveValues() {
        // Named individually, not just counted: a test that only checked size 5
        // would pass just as well with DEPOSIT swapped in for one of these.
        assertThat(Arrays.stream(PaymentMethod.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrder("CASH", "CLICK", "PAYME", "TELEGRAM", "MARKETPLACE");
    }

    @Test
    @DisplayName("no TenderStatus names a deposit-specific state")
    void tenderStatusNamesNoDeposit() {
        assertThat(Arrays.stream(TenderStatus.values()).map(Enum::name).toList())
                .noneMatch(name -> name.contains("DEPOSIT"));
    }

    @Test
    @DisplayName("the one balance-backed method a settlement plans against is loyalty points, "
            + "spelled exactly as CheckoutSettlementPlanner registers it")
    void theOnlyBalanceMethodCodeIsLoyaltyPoints() {
        assertThat(CheckoutSettlementPlanner.POINTS_METHOD_CODE)
                .as("this is the sole settles_from_balance = true code the registry ever sees; "
                        + "a second one -- CUSTOMER_DEPOSIT -- was the value V0042 deliberately "
                        + "did not add")
                .isEqualTo("LOYALTY_POINTS");
    }
}
