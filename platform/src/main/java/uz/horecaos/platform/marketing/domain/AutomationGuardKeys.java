package uz.horecaos.platform.marketing.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * The guard key each {@link AutomationTriggerType} writes onto {@code
 * marketing.automation_runs} — the value {@code uq_automation_run_guard}
 * (V0415) makes a database constraint rather than something a service has to
 * remember (ADR 0044: "each firing writes a ... row whose guard key is a
 * unique index").
 */
public final class AutomationGuardKeys {

    private AutomationGuardKeys() {}

    /**
     * ADR 0044's literal "once per customer per year" — a calendar year in the
     * brand's own timezone, not a 365-day bucket, so a customer born on the
     * boundary is guarded by the same notion of "year" the rule's own daily
     * sweep and a human reading the birthday off a calendar both use.
     */
    public static String birthday(Instant now, ZoneId brandZone) {
        return "YEAR:" + ZonedDateTime.ofInstant(now, brandZone).getYear();
    }

    /**
     * A fixed-width bucket of {@code cooldownDays}, floored on whole days since
     * the epoch. An approximation of a sliding "N days since last fire" cooldown
     * rather than an exact one — a customer re-crossing the threshold one day
     * into a new bucket can be fired again sooner than a full cooldown after the
     * last fire — accepted because a bucket is a value a unique index can guard
     * and a sliding window is a query, not a constraint.
     */
    public static String cooldownBucket(Instant now, int cooldownDays) {
        if (cooldownDays <= 0) {
            throw new IllegalArgumentException("cooldownDays must be positive, was " + cooldownDays);
        }
        long epochDay = Math.floorDiv(now.getEpochSecond(), 86_400L);
        long bucket = Math.floorDiv(epochDay, (long) cooldownDays);
        return "BUCKET:" + bucket;
    }

    /** ADR 0044's "once per cart" — no cooldown bucketing, a cart is abandoned once, ever. */
    public static String cart(UUID cartId) {
        return "CART:" + cartId;
    }
}
