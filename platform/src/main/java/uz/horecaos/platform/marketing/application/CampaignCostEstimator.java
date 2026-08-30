package uz.horecaos.platform.marketing.application;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.SmsSegments;

/**
 * What a send will cost, per locale and per encoding (ADR 0044).
 *
 * <p>The mistake this exists to prevent is counting recipients. Latin copy encodes
 * as GSM-7 at 153 characters per concatenated segment; the same message in
 * Cyrillic falls to UCS-2 at 67, so a two-hundred-character body is two segments
 * in uz-Latn and three in ru. An estimator that multiplies recipients by a price
 * is wrong by more than a factor of two on a trilingual base, and whoever
 * discovers that is reading the Eskiz invoice.
 *
 * <p>The answer is a range, not a number. A personalised name changes the rendered
 * length per recipient, so the low bound renders every placeholder as nothing and
 * the high bound renders each as {@link #PLACEHOLDER_ALLOWANCE} characters. Both
 * bounds are stated; a single midpoint would be a guess presented as a fact, and
 * the ceiling would be set against it.
 *
 * <p>An unknown cost is returned as empty rather than as zero. Zero passes every
 * ceiling check there is.
 */
@Service
public class CampaignCostEstimator {

    /**
     * How long a substituted value is assumed to be at worst.
     *
     * <p>Twenty-four characters covers a long Uzbek given name and patronymic
     * comfortably. It is a stated assumption rather than a measured one, and it
     * only ever moves the upper bound, so being generous here costs a slightly
     * conservative ceiling and never an overspend.
     */
    public static final int PLACEHOLDER_ALLOWANCE = 24;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^}]*}}");

    /**
     * The cost of one send.
     *
     * @param bodiesByLocale the raw template body per locale tag, from the ADR 0020
     *                       delivery path. Empty when no template is active
     * @param membersByLocale how many recipients resolved to each locale
     * @param pricePerSegmentMinor the tenant-configured price, in integer minor
     *                             units. For UZS a minor unit is a whole som
     */
    public Optional<Estimate> estimate(
            MarketingChannel channel,
            Map<String, String> bodiesByLocale,
            Map<String, Integer> membersByLocale,
            Long pricePerSegmentMinor,
            String currency) {

        if (!channel.carriesMarginalCost()) {
            // Push and Telegram carry no marginal money, so there is nothing to
            // estimate and the ceiling is optional. The recipient cap is not, and
            // it is enforced elsewhere regardless of what this returns.
            return Optional.of(new Estimate(0, 0, 0, currency));
        }
        if (pricePerSegmentMinor == null || currency == null || bodiesByLocale.isEmpty()) {
            return Optional.empty();
        }

        long lowSegments = 0;
        long highSegments = 0;
        int recipients = 0;

        for (Map.Entry<String, Integer> entry : membersByLocale.entrySet()) {
            int count = entry.getValue();
            recipients += count;

            String body = bodiesByLocale.get(entry.getKey());
            if (body == null) {
                // A locale with members and no template is not a free send; it is a
                // send that cannot happen. Refusing to price it is the honest
                // answer, and the send path will refuse it too.
                return Optional.empty();
            }
            lowSegments += (long) SmsSegments.segmentsFor(strip(body)) * count;
            highSegments += (long) SmsSegments.segmentsFor(pad(body)) * count;
        }

        return Optional.of(new Estimate(
                lowSegments * pricePerSegmentMinor, highSegments * pricePerSegmentMinor, recipients, currency));
    }

    /** What one recipient's message costs, used to reserve a batch before sending it. */
    public long perRecipientCostMinor(MarketingChannel channel, String body, Long pricePerSegmentMinor) {
        if (!channel.carriesMarginalCost() || pricePerSegmentMinor == null || body == null) {
            return 0;
        }
        // The upper bound per recipient, deliberately. A reservation made at the
        // lower bound would let a batch of long messages overrun a ceiling that
        // the arithmetic said it fitted under.
        return (long) SmsSegments.segmentsFor(pad(body)) * pricePerSegmentMinor;
    }

    private static String strip(String body) {
        return PLACEHOLDER.matcher(body).replaceAll("");
    }

    private static String pad(String body) {
        return PLACEHOLDER.matcher(body).replaceAll("x".repeat(PLACEHOLDER_ALLOWANCE));
    }

    /**
     * A costed send.
     *
     * @param lowMinor every placeholder empty
     * @param highMinor every placeholder at its allowance
     */
    public record Estimate(long lowMinor, long highMinor, int recipients, String currency) {}
}
