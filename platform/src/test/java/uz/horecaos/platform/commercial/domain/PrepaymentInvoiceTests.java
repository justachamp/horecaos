package uz.horecaos.platform.commercial.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Where an invoice stands is read from what was paid and what the clock says, never stored. */
class PrepaymentInvoiceTests {

    private static final Instant ISSUED = Instant.parse("2026-10-08T09:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-10-22T09:00:00Z");

    private static PrepaymentInvoice invoice(long amount, long paid, @Nullable Instant cancelledAt) {
        return new PrepaymentInvoice(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PI-202610-000001",
                amount,
                "UZS",
                VALID_UNTIL,
                "b",
                "b",
                "a",
                "m",
                "t",
                "u",
                ISSUED,
                cancelledAt == null ? null : "u",
                cancelledAt,
                cancelledAt == null ? null : "r",
                paid);
    }

    @Test
    void anUnpaidInvoiceIsOpenUntilItExpiresAndThenExpired() {
        PrepaymentInvoice unpaid = invoice(1_000_000, 0, null);

        assertThat(unpaid.statusAt(ISSUED.plusSeconds(60))).isEqualTo(PrepaymentInvoice.OPEN);
        assertThat(unpaid.statusAt(VALID_UNTIL)).as("valid until is exclusive").isEqualTo(PrepaymentInvoice.EXPIRED);
        assertThat(unpaid.dueMinor()).isEqualTo(1_000_000);
    }

    @Test
    void aPartPaymentIsPartialAndAFullOneIsPaidEvenAfterItWouldHaveExpired() {
        assertThat(invoice(1_000_000, 400_000, null).statusAt(ISSUED)).isEqualTo(PrepaymentInvoice.PARTIALLY_PAID);
        assertThat(invoice(1_000_000, 400_000, null).dueMinor()).isEqualTo(600_000);
        assertThat(invoice(1_000_000, 1_000_000, null).statusAt(VALID_UNTIL.plusSeconds(86_400 * 30)))
                .as("the money is in the ledger whatever the date says")
                .isEqualTo(PrepaymentInvoice.PAID);
    }

    @Test
    void anOverpaymentIsPaidAndOwesNothingNegative() {
        PrepaymentInvoice over = invoice(1_000_000, 1_300_000, null);

        assertThat(over.statusAt(ISSUED)).isEqualTo(PrepaymentInvoice.PAID);
        assertThat(over.dueMinor())
                .as("the excess is the tenant's wallet credit, not a debt of HorecaOS")
                .isZero();
    }

    @Test
    void aCancelledInvoiceIsCancelledWhateverElseIsTrue() {
        assertThat(invoice(1_000_000, 0, ISSUED.plusSeconds(60)).statusAt(ISSUED))
                .isEqualTo(PrepaymentInvoice.CANCELLED);
    }
}
