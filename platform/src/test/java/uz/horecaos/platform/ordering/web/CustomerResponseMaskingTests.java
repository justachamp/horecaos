package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.application.OrderQueryService;

/**
 * The web-layer wiring between {@link OrderQueryService.CustomerDetail} (name
 * in full, phone decrypted) and {@link OperationsOrderController.CustomerResponse}
 * (name in full, phone masked) — orders.md §1.5.
 *
 * <p>No database: {@code CustomerDetail} is a plain record, and what is under
 * test is exactly the one line, {@code CustomerResponse.of}, that decides what
 * a client actually receives. {@link uz.horecaos.platform.ordering.CartCheckoutAndOrderTests}
 * covers the decrypt itself against a real ciphertext; {@link PhoneMaskingTests}
 * covers the mask format. This is the seam between the two.
 */
class CustomerResponseMaskingTests {

    @Test
    void thePhoneIsMaskedAndTheRawValueNeverAppears() {
        var detail =
                new OrderQueryService.CustomerDetail("Dilnoza", "+998901112233", true, true, true, "ACCOUNT", false);

        var response = OperationsOrderController.CustomerResponse.of(detail);

        assertThat(response.displayName()).as("the name is never masked").isEqualTo("Dilnoza");
        assertThat(response.phoneMasked()).isEqualTo("+998 90 ••• •• 33");
        assertThat(response.toString())
                .as("the raw phone must not be in this response")
                .doesNotContain("901112233")
                .doesNotContain("+998901112233");
        assertThat(response.customerType()).isEqualTo("ACCOUNT");
        assertThat(response.hasAddress()).isTrue();
        assertThat(response.hasDeliveryInstructions()).isTrue();
        assertThat(response.anonymized()).isFalse();
    }

    @Test
    void aCustomerWithNoPhoneOnFileMasksToNull() {
        var detail = new OrderQueryService.CustomerDetail("Guest", null, false, false, true, "GUEST", false);

        var response = OperationsOrderController.CustomerResponse.of(detail);

        assertThat(response.phoneMasked()).isNull();
    }

    @Test
    void anAnonymizedCustomerCarriesNoNameOrPhone() {
        // The ADR 0029 retention job blanks the snapshot's encrypted columns, so
        // OrderQueryService reads them back as null; the flag is what tells the
        // client this is "removed by policy" rather than "never had one".
        var detail = new OrderQueryService.CustomerDetail(null, null, false, false, true, "ACCOUNT", true);

        var response = OperationsOrderController.CustomerResponse.of(detail);

        assertThat(response.displayName()).isNull();
        assertThat(response.phoneMasked()).isNull();
        assertThat(response.anonymized()).isTrue();
    }
}
