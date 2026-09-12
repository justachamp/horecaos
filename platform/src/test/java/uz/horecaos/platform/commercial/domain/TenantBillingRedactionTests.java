package uz.horecaos.platform.commercial.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR 0028: the card token reference belongs in PostgreSQL and nowhere else.
 *
 * <p>A record's generated {@code toString} prints every component, so one log
 * line or one wrapped exception interpolating a {@link TenantBilling} would
 * put a live vault reference into the application log. The override that
 * prevents it is one refactor away from being regenerated, which is why every
 * other redacted record in this repository is pinned by a test of its own
 * (see {@code DeliveryPartnerRedactionTests}) rather than by a comment.
 */
class TenantBillingRedactionTests {

    private static final String TOKEN = "vault:pilot-card-0f7a21";

    @Test
    void theCardTokenReferenceNeverReachesAStringOfThisRecord() {
        TenantBilling billing = new TenantBilling(
                UUID.fromString("018f6f4e-2100-7000-8000-0000000000d1"),
                PaymentMethod.CARD,
                TOKEN,
                "finance-1",
                Instant.parse("2026-09-11T09:00:00Z"));

        assertThat(billing.toString())
                .as("a log line interpolating this record must not be a copy of the vault reference")
                .doesNotContain(TOKEN)
                .contains("token=on-file");
        assertThat("settling %s".formatted(billing)).doesNotContain(TOKEN);
        assertThat(new IllegalStateException("cannot charge " + billing).getMessage())
                .doesNotContain(TOKEN);
    }

    @Test
    void aCardTenantWithNoTokenOnFileStillSaysSo() {
        TenantBilling billing = new TenantBilling(
                UUID.fromString("018f6f4e-2100-7000-8000-0000000000d1"),
                PaymentMethod.CARD,
                null,
                "finance-1",
                Instant.parse("2026-09-11T09:00:00Z"));

        assertThat(billing.toString())
                .as("CARD with nothing on file is the case a card adapter has to refuse, so the "
                        + "diagnostic that matters survives the redaction")
                .contains("method=CARD")
                .contains("token=none");
    }
}
