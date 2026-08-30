package uz.qoida.platform.tenancy.domain.channel;

import java.util.Collection;

/**
 * How long the kitchen says it will take (ADR 0036).
 *
 * <p>Assembled in a fixed order and the longest wins: the time-of-day band for
 * the order's start instant, then the maximum of that and any line-level
 * {@code preparation_duration_override} from {@code catalog.location_offerings}.
 *
 * <p>The order is fixed and the rule is "longest", not "most specific", because a
 * pizza that takes 40 minutes does not become 20 because the quiet-hours band
 * says so. Getting that backwards produces the single most complained-about
 * number in food delivery, and it now has three sources — this band, the item
 * override, and ADR 0019's lead time.
 */
public final class PreparationPromise {

    private PreparationPromise() {
    }

    /**
     * @param bandMinutes     the band covering the order's start instant, or null
     *                        when no band covers it
     * @param overrideMinutes per-line overrides for the items in the basket
     * @return the promised preparation time, or null when nothing said anything
     */
    public static Integer minutes(Integer bandMinutes, Collection<Integer> overrideMinutes) {
        Integer longest = bandMinutes;
        if (overrideMinutes != null) {
            for (Integer override : overrideMinutes) {
                if (override == null) {
                    continue;
                }
                if (longest == null || override > longest) {
                    longest = override;
                }
            }
        }
        return longest;
    }
}
