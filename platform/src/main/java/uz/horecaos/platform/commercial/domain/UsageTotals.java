package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import uz.horecaos.platform.commercial.api.UsagePeriod;

/**
 * The consumed figure for one key in one period, and its two components
 * (ADR 0021).
 *
 * <p>Measured and adjusted quantities stay apart all the way to the screen. An
 * operator answering "why is this 19 640" needs to know how much of it a person
 * decided, and a single total can never say.
 */
public record UsageTotals(
        String entitlementKey,
        UsagePeriod period,
        long eventQuantity,
        long adjustmentQuantity,
        int eventCount,
        Instant lastEventAt) {

    public long consumed() {
        return eventQuantity + adjustmentQuantity;
    }

    public static UsageTotals empty(String entitlementKey, UsagePeriod period) {
        return new UsageTotals(entitlementKey, period, 0, 0, 0, null);
    }
}
