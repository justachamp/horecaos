package uz.horecaos.platform.payments.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The merchant-binding rotation body is package-private, so its redaction is
 * proved from here; the reasoning is on
 * {@code uz.horecaos.platform.integration.web.SecretBearingRecordsRedactTests}.
 */
class MerchantBindingSecretRequestRedactTests {

    @Test
    void aRotationBodyPrintsItsReasonButNeverTheCredential() {
        String printed = new MerchantBindingController.RotateMerchantBindingSecretRequest(
                        "click-secret-key-51c2", "merchant rotated the key")
                .toString();

        assertThat(printed).doesNotContain("click-secret-key-51c2").contains("merchant rotated the key", "REDACTED");
    }
}
