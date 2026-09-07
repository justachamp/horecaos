package uz.horecaos.platform.notifications.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Whether an instant falls inside a customer's own do-not-disturb window (ADR
 * 0020), and if so, the instant the window releases it.
 *
 * <p>{@code notification_preferences.quiet_hours_start}/{@code
 * quiet_hours_end} are local wall-clock times in {@code timezone} — "do not
 * text me before 08:00" means 08:00 in the customer's own zone, not UTC — so
 * every comparison here happens after converting {@code now} into that zone,
 * never before.
 *
 * <p>A pure function deliberately kept out of {@code
 * NotificationEligibilityService}: what "held" means is one question, and it
 * is a small enough one to prove correct with a boundary crossed instant
 * against a fixed window, independent of a database, a claim, or an
 * eligibility gate.
 */
public final class QuietHours {

    private QuietHours() {}

    /**
     * @param start the window's local start time, or {@code null} for no window
     * @param end   the window's local end time, or {@code null} for no window
     * @param zone  the IANA zone the two times above are read in; required
     *              whenever {@code start} is present, mirroring the
     *              {@code ck_preference_quiet_hours_zone} constraint the row
     *              this came from is written under
     * @return empty when {@code now} is not held; otherwise the first instant
     *         at or after {@code now} at which the window has closed. A caller
     *         holding a message defers it exactly there — never further out,
     *         and never by a short repeating backoff, which would spend the
     *         message's whole retry budget doing nothing but waiting (the same
     *         reasoning {@code CampaignPacer}'s own quiet-hours idiom gives)
     */
    public static Optional<Instant> heldUntil(
            Instant now, @Nullable LocalTime start, @Nullable LocalTime end, @Nullable String zone) {
        // Both null is "no window configured" (the ordinary, expected case for
        // almost every row today, since nothing writes these columns yet outside
        // NotificationPreferenceService's own new write path). Exactly one null
        // cannot happen past that service — it mirrors the DB's own
        // ck_preference_quiet_hours_pair check constraint — but a row this class
        // did not write (a future migration, a manual fix) is read defensively:
        // an incomplete window holds nothing rather than failing the send.
        if (start == null || end == null || zone == null) {
            return Optional.empty();
        }
        // A degenerate, zero-width window. Rather than deciding whether that
        // means "always" or "never", treated as configured wrong and ignored —
        // the same defensive answer as an incomplete window, and one
        // NotificationPreferenceService#set refuses at write time so this branch
        // is a backstop, not the primary guard.
        if (start.equals(end)) {
            return Optional.empty();
        }

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (DateTimeException invalidZone) {
            // Same defensive posture as an incomplete window: a row this class
            // did not write must not silently sit a transactional-adjacent
            // message in the queue because its timezone string is unparsable.
            return Optional.empty();
        }

        ZonedDateTime nowLocal = now.atZone(zoneId);
        LocalTime timeOfDay = nowLocal.toLocalTime();

        boolean wraps = end.isBefore(start);
        boolean inside = wraps
                // 22:00-08:00: held from start through midnight, and again from
                // midnight through end.
                ? !timeOfDay.isBefore(start) || timeOfDay.isBefore(end)
                // 13:00-14:00: held only between the two, same calendar day.
                : !timeOfDay.isBefore(start) && timeOfDay.isBefore(end);
        if (!inside) {
            return Optional.empty();
        }

        // The window's close is today's date unless now is still on the
        // "evening" side of a wrapping window (at or after start, before
        // midnight) — in which case the close is tomorrow's occurrence of
        // `end`, the one the customer is actually waiting out.
        boolean closesTomorrow = wraps && !timeOfDay.isBefore(start);
        ZonedDateTime closesAt = ZonedDateTime.of(
                closesTomorrow ? nowLocal.toLocalDate().plusDays(1) : nowLocal.toLocalDate(), end, zoneId);
        return Optional.of(closesAt.toInstant());
    }
}
