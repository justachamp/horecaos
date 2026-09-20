package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.partner.web.PartnerApiClientController;

/**
 * Every HTTP record that carries a raw credential prints itself without it
 * (ADR 0028). A record's generated {@code toString} lists every component, and
 * Spring's message converters log the (de)serialized body at TRACE, so the
 * default is a credential in a log line the day tracing is switched on —
 * which is how {@code SecretIngressRequest} leaked until 2026-09-20. The
 * non-secret components stay printable on purpose: a redaction that blanks the
 * whole record makes the trace line useless and gets reverted.
 */
class SecretBearingRecordsRedactTests {

    private static final String SECRET = "live-credential-7f3a9c";

    @Test
    void theSecretDoorsRequestPrintsItsCategoryAndTypeButNeverTheValue() {
        String printed = new SecretIngressController.SecretIngressRequest(
                        SecretCategory.PROVIDER_NOTIFICATION, "TELEGRAM_BOT_API", SECRET)
                .toString();

        assertThat(printed).doesNotContain(SECRET).contains("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", "REDACTED");
    }

    @Test
    void rotationByValuePrintsItsReasonButNeverTheValue() {
        String printed =
                new ProviderInstallationController.RotateSecretValueRequest(SECRET, "quarterly rotation").toString();

        assertThat(printed).doesNotContain(SECRET).contains("quarterly rotation", "REDACTED");
    }

    @Test
    void anIssuedOrRotatedPartnerClientNeverPrintsItsPlaintextSecret() {
        UUID id = UUID.fromString("018f9b20-7000-7000-8000-00000000aa01");

        String issued =
                new PartnerApiClientController.IssuedClientResponse(id, "client-abc", SECRET, null, 1).toString();
        String rotated = new PartnerApiClientController.RotatedClientResponse(id, SECRET, null, 2).toString();

        assertThat(issued).doesNotContain(SECRET).contains("client-abc", id.toString(), "REDACTED");
        assertThat(rotated).doesNotContain(SECRET).contains(id.toString(), "REDACTED");
    }
}
