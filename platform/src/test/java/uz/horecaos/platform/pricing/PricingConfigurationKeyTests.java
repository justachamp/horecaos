package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.pricing.api.PricingConfigurationKeys;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * {@code pricing.quote_ttl_seconds}, declared twice and kept identical
 * (ADR 0030).
 *
 * <p>The registry the startup validator consults lives inside the tenancy
 * module and cannot be imported from here; a reference the other way would
 * make the modules cyclic. So the key exists in both places, and this test is
 * what stops them drifting — the same arrangement ADR 0021's enforcement
 * ceiling and ADR 0045's telemetry keys already use.
 */
class PricingConfigurationKeyTests {

    @Test
    @DisplayName("the quote TTL declaration is identical on both sides")
    void theKeysAgree() {
        assertThat(registered(PricingConfigurationKeys.QUOTE_TTL_SECONDS_CODE))
                .isEqualTo(PricingConfigurationKeys.QUOTE_TTL_SECONDS);
    }

    @Test
    @DisplayName("the declared default matches what QuoteService actually does")
    void theDefaultMatchesTheCode() {
        // 2026-09-10's correction: an earlier draft declared 300 (five minutes)
        // while QuoteService.QUOTE_TTL was, and remains, fifteen. Wiring the key
        // as declared would have silently cut every quote's life to a third of
        // what it is today for every tenant that had not overridden it.
        assertThat(PricingConfigurationKeys.QUOTE_TTL_SECONDS.defaultValue())
                .isEqualTo((int) QuoteService.QUOTE_TTL.toSeconds())
                .isEqualTo(900);
        assertThat(QuoteService.QUOTE_TTL).isEqualTo(Duration.ofMinutes(15));
    }

    /** Gap map row {@code 4.4d}, wave w7: the catalog base setting pricing reads. */
    @Test
    @DisplayName("the catalog.qr_kiosk_price_plane declaration is identical on both sides, off by default, tenant-only")
    void theQrKioskPricePlaneKeyAgreesAndIsTenantOnly() {
        assertThat(registered(PricingConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE_CODE))
                .isEqualTo(PricingConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE);
        assertThat(PricingConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE.defaultValue())
                .as("off by default: a QR or kiosk channel prices as itself until a tenant opts in")
                .isEqualTo(false);
        assertThat(PricingConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE.tenantVisible())
                .isTrue();
        assertThat(PricingConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE.settableScopes())
                .as("a whole-company switch, not per brand or per branch")
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
    }

    @Test
    @DisplayName("the ADR 0140 promotion keys are declared identically on both sides with the record's defaults")
    void thePromotionKeysAgreeAndCarryTheRecordsDefaults() {
        assertThat(registered("pricing.promotion.approval.percentage_over_bp"))
                .isEqualTo(PricingConfigurationKeys.PROMOTION_APPROVAL_PERCENTAGE_OVER_BP);
        assertThat(registered("pricing.promotion.approval.amount_over_minor"))
                .isEqualTo(PricingConfigurationKeys.PROMOTION_APPROVAL_AMOUNT_OVER_MINOR);
        assertThat(registered("pricing.promotion.approval.always_for_markup"))
                .isEqualTo(PricingConfigurationKeys.PROMOTION_APPROVAL_ALWAYS_FOR_MARKUP);
        assertThat(registered("pricing.promotion.simulate.max_lines"))
                .isEqualTo(PricingConfigurationKeys.PROMOTION_SIMULATE_MAX_LINES);

        assertThat(PricingConfigurationKeys.PROMOTION_APPROVAL_PERCENTAGE_OVER_BP.defaultValue())
                .as("30%")
                .isEqualTo(3000);
        assertThat(PricingConfigurationKeys.PROMOTION_APPROVAL_AMOUNT_OVER_MINOR.defaultValue())
                .as("100 000 som")
                .isEqualTo(100000L);
        assertThat(PricingConfigurationKeys.PROMOTION_APPROVAL_ALWAYS_FOR_MARKUP.defaultValue())
                .isEqualTo(true);
        assertThat(PricingConfigurationKeys.PROMOTION_SIMULATE_MAX_LINES.defaultValue())
                .isEqualTo(50);
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
