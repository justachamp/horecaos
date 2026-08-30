package uz.horecaos.platform.marketing.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Quiet hours and the frequency cap, resolved for one brand (ADR 0044).
 *
 * <p>Both numbers are ADR 0030 policy values that legal and product will confirm.
 * They are not left blank in the meantime, because an unset cap is an infinite cap
 * and the first production send would run without one. The defaults below are the
 * ADR's provisional ones, enforced from day one, and deliberately conservative:
 * the failure they accept is sending too little.
 *
 * <p>{@link #tightenedBy} is where the tighten-only rule lives. Tenants will ask
 * to loosen both, and it is their customer relationship, so the refusal needs a
 * reason: these numbers also protect the sending reputation of an aggregator
 * identity HorecaOS shares across tenants, and one tenant's aggressive sending
 * degrades delivery for every other tenant on the same sender. The externality is
 * what makes this the platform's decision rather than the tenant's, and it is
 * contained only when HorecaOS moves to per-tenant sender identities.
 */
public record EngagementPolicy(
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        ZoneId timezone,
        int messagesPer7Days,
        int messagesPer30Days,
        Long smsPricePerSegmentMinor,
        String currency) {

    /**
     * The morning boundary is later than a European default on purpose: a 09:00
     * marketing SMS in a market where the working day commonly starts at 09:00
     * arrives during the commute.
     */
    public static final LocalTime DEFAULT_QUIET_START = LocalTime.of(21, 0);
    public static final LocalTime DEFAULT_QUIET_END = LocalTime.of(10, 0);

    public static final int DEFAULT_MESSAGES_PER_7_DAYS = 3;
    public static final int DEFAULT_MESSAGES_PER_30_DAYS = 8;

    /**
     * Uzbekistan is UTC+5 with no daylight saving, so a brand timezone is a fixed
     * offset and a scheduled send does not shift twice a year.
     */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tashkent");

    public static EngagementPolicy platformDefault() {
        return new EngagementPolicy(DEFAULT_QUIET_START, DEFAULT_QUIET_END, DEFAULT_ZONE,
                DEFAULT_MESSAGES_PER_7_DAYS, DEFAULT_MESSAGES_PER_30_DAYS, null, null);
    }

    /**
     * Applies a tenant's override, refusing anything that loosens.
     *
     * @throws IllegalArgumentException with the value that was refused, so the
     *         marketer reads which of the four numbers was the problem rather than
     *         a generic rejection of the whole override
     */
    public EngagementPolicy tightenedBy(EngagementOverride override) {
        LocalTime start = override.quietHoursStart() == null
                ? quietHoursStart : override.quietHoursStart();
        LocalTime end = override.quietHoursEnd() == null
                ? quietHoursEnd : override.quietHoursEnd();

        // The closed window wraps midnight, so "tighter" is stated on the open
        // window it leaves behind: it must open no earlier and close no later.
        if (start.isAfter(quietHoursStart)) {
            throw new IllegalArgumentException(
                    "Quiet hours may be tightened and never loosened: a start of %s is later than %s"
                            .formatted(start, quietHoursStart));
        }
        if (end.isBefore(quietHoursEnd)) {
            throw new IllegalArgumentException(
                    "Quiet hours may be tightened and never loosened: an end of %s is earlier than %s"
                            .formatted(end, quietHoursEnd));
        }

        int weekly = override.messagesPer7Days() == null
                ? messagesPer7Days : override.messagesPer7Days();
        int monthly = override.messagesPer30Days() == null
                ? messagesPer30Days : override.messagesPer30Days();

        if (weekly > messagesPer7Days) {
            throw new IllegalArgumentException(
                    "The 7-day cap may be tightened and never loosened: %d exceeds %d"
                            .formatted(weekly, messagesPer7Days));
        }
        if (monthly > messagesPer30Days) {
            throw new IllegalArgumentException(
                    "The 30-day cap may be tightened and never loosened: %d exceeds %d"
                            .formatted(monthly, messagesPer30Days));
        }

        return new EngagementPolicy(start, end,
                override.timezone() == null ? timezone : override.timezone(),
                weekly, monthly,
                override.smsPricePerSegmentMinor(), override.currency());
    }

    /** Whether a message becoming eligible now would land inside the closed window. */
    public boolean isQuiet(Instant moment) {
        LocalTime local = ZonedDateTime.ofInstant(moment, timezone).toLocalTime();
        if (quietHoursStart.equals(quietHoursEnd)) {
            // A zero-length open window would mean nothing is ever sendable. Read
            // as "always quiet" rather than "never", because a policy row that says
            // this is far more likely to be a mistake than a deliberate blackout,
            // and holding messages is recoverable where sending them is not.
            return true;
        }
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !local.isBefore(quietHoursStart) && local.isBefore(quietHoursEnd);
        }
        return !local.isBefore(quietHoursStart) || local.isBefore(quietHoursEnd);
    }

    /**
     * When the closed window next opens.
     *
     * <p>Held to this boundary rather than dropped. Dropping loses the send
     * silently, and a marketer reading a delivered count cannot distinguish a
     * quiet-hour hold from a suppression.
     */
    public Instant nextOpenBoundary(Instant moment) {
        if (!isQuiet(moment)) {
            return moment;
        }
        ZonedDateTime local = ZonedDateTime.ofInstant(moment, timezone);
        ZonedDateTime boundary = local.with(quietHoursEnd);
        if (!boundary.isAfter(local)) {
            boundary = boundary.plusDays(1);
        }
        return boundary.toInstant();
    }

    /** A tenant's requested override. Any null field means "leave the platform value". */
    public record EngagementOverride(
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd,
            ZoneId timezone,
            Integer messagesPer7Days,
            Integer messagesPer30Days,
            Long smsPricePerSegmentMinor,
            String currency) { }
}
