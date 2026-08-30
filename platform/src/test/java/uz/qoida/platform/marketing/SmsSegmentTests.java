package uz.qoida.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.marketing.application.CampaignCostEstimator;
import uz.qoida.platform.marketing.domain.MarketingChannel;
import uz.qoida.platform.marketing.domain.SmsSegments;

/**
 * ADR 0044's cost model: segments per locale, not recipients.
 *
 * <p>The failure being guarded is the one the ADR names in its own context
 * section. A two-hundred-character body is two segments in uz-Latn and three in
 * ru, so an estimator that counts recipients is wrong by more than a factor of two
 * on a trilingual base — and whoever discovers that is reading the Eskiz invoice.
 */
class SmsSegmentTests {

    private final CampaignCostEstimator estimator = new CampaignCostEstimator();

    @Test
    @DisplayName("the same template costs more in Cyrillic than in Latin")
    void latinAndCyrillicAreCountedDifferently() {
        String latin = "x".repeat(200);
        String cyrillic = "ы".repeat(200);

        assertThat(SmsSegments.encodingOf(latin)).isEqualTo(SmsSegments.Encoding.GSM_7);
        assertThat(SmsSegments.encodingOf(cyrillic)).isEqualTo(SmsSegments.Encoding.UCS_2);

        // 200 septets over 153 per concatenated part is two; 200 UCS-2 units over
        // 67 is three. These are the exact numbers the ADR quotes.
        assertThat(SmsSegments.segmentsFor(latin)).isEqualTo(2);
        assertThat(SmsSegments.segmentsFor(cyrillic)).isEqualTo(3);
    }

    @Test
    @DisplayName("one non-GSM character re-encodes the whole body")
    void oneCyrillicCharacterCostsTheWholeMessage() {
        String almostLatin = "x".repeat(100) + "ы";

        // 101 characters is one GSM-7 segment and two UCS-2 segments, because the
        // single non-GSM character forces the entire body to UCS-2 — which is
        // exactly what the gateway charges for.
        assertThat(SmsSegments.segmentsFor("x".repeat(101))).isEqualTo(1);
        assertThat(SmsSegments.segmentsFor(almostLatin)).isEqualTo(2);
    }

    @Test
    @DisplayName("an escaped GSM character costs two septets")
    void extendedCharactersCostTwo() {
        // 80 braces is 160 septets, which is exactly one segment; 81 tips it over.
        assertThat(SmsSegments.segmentsFor("{".repeat(80))).isEqualTo(1);
        assertThat(SmsSegments.segmentsFor("{".repeat(81))).isEqualTo(2);
    }

    @Test
    @DisplayName("an estimate is a range, and a mixed-locale audience is priced per locale")
    void theEstimateIsCountedPerLocale() {
        Map<String, String> bodies = Map.of(
                "uz-Latn", "Chegirma {{name}}! " + "x".repeat(140),
                "ru", "Скидка {{name}}! " + "ы".repeat(50));
        Map<String, Integer> members = Map.of("uz-Latn", 100, "ru", 100);

        Optional<CampaignCostEstimator.Estimate> estimate =
                estimator.estimate(MarketingChannel.SMS, bodies, members, 200L, "UZS");

        assertThat(estimate).isPresent();
        // The upper bound is strictly above the lower one, because the placeholder
        // allowance pushes at least one locale into another segment. A single
        // number here would be a guess presented as a fact, and the ceiling would
        // be set against it.
        assertThat(estimate.get().highMinor()).isGreaterThan(estimate.get().lowMinor());
        assertThat(estimate.get().recipients()).isEqualTo(200);
        assertThat(estimate.get().currency()).isEqualTo("UZS");
    }

    @Test
    @DisplayName("an unknown cost is empty, never zero")
    void anUnpriceableSendIsNotFree() {
        // No template active for the channel. Zero would pass every ceiling check
        // there is, so the estimator refuses to produce a number at all.
        assertThat(estimator.estimate(MarketingChannel.SMS, Map.of(), Map.of("ru", 10), 200L, "UZS"))
                .isEmpty();

        // A locale with members and no body is a send that cannot happen rather
        // than a free one.
        assertThat(estimator.estimate(MarketingChannel.SMS, Map.of("ru", "hi"),
                Map.of("ru", 10, "en", 5), 200L, "UZS")).isEmpty();

        // No configured price per segment is the same kind of unknown.
        assertThat(estimator.estimate(MarketingChannel.SMS, Map.of("ru", "hi"),
                Map.of("ru", 10), null, "UZS")).isEmpty();
    }

    @Test
    @DisplayName("a channel with no marginal money is free, and still capped elsewhere")
    void pushCostsNothing() {
        assertThat(estimator.estimate(MarketingChannel.PUSH, Map.of(), Map.of("ru", 40_000),
                null, "UZS")).hasValueSatisfying(estimate -> {
                    assertThat(estimate.lowMinor()).isZero();
                    assertThat(estimate.highMinor()).isZero();
                });
    }
}
