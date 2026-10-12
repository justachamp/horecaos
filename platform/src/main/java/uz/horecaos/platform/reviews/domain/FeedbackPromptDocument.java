package uz.horecaos.platform.reviews.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The {@code reviews.feedback_prompt} policy document: when a customer is asked to rate a finished
 * order (ADR 0159, Decision 4, ADR 0030).
 *
 * <p>The prompt is an order notification governed by this document, not a marketing automation. Rows
 * are keyed by fulfilment mode and channel, each with {@code enabled}, {@code delayMinutes} and
 * {@code giveUpAfterHours}; one {@code sendWindow} in the location's own time zone and one
 * {@code inlineWindowDays} for the storefront's own prompt sit beside them. The document can only make
 * a prompt <em>rarer or later</em> than today's immediate send: there is still at most one prompt per
 * order per channel, so no row can make it more frequent.
 *
 * <p>Resolution is most specific row first, then the built-in default ({@link #builtInDefault()}).
 * "Most specific" ranks the order type above the channel, because a tenant that says "never ask a
 * dine-in guest" means it on every channel.
 *
 * @param sendWindow       when prompts may go out, in the location's time zone; null means any hour
 * @param inlineWindowDays how long a completed, unreviewed order stays in the storefront's own
 *                         "rate your order" prompt
 */
public record FeedbackPromptDocument(@Nullable SendWindow sendWindow, int inlineWindowDays, List<PromptRow> rows) {

    /** The wildcard in a row's {@code fulfillmentMode} or {@code channel}. */
    public static final String ANY = "*";

    public static final Set<String> MODES = Set.of("DELIVERY", "PICKUP", "DINE_IN", ANY);
    public static final Set<String> CHANNELS = Set.of("TELEGRAM", "SMS", "EMAIL", "PUSH", ANY);

    public static final int MAX_DELAY_MINUTES = 1440;
    public static final int MIN_GIVE_UP_HOURS = 1;
    public static final int MAX_GIVE_UP_HOURS = 72;
    public static final int MIN_INLINE_DAYS = 1;
    public static final int MAX_INLINE_DAYS = 90;
    public static final int MAX_ROWS = 40;

    public FeedbackPromptDocument {
        rows = List.copyOf(rows);
    }

    /**
     * The window prompts may go out in, as local clock times.
     *
     * @param start inclusive, {@code HH:mm}
     * @param end   exclusive, {@code HH:mm}, after {@code start}
     */
    public record SendWindow(String start, String end) {

        public LocalTime startTime() {
            return LocalTime.parse(start);
        }

        public LocalTime endTime() {
            return LocalTime.parse(end);
        }
    }

    /**
     * One rule.
     *
     * @param fulfillmentMode {@code DELIVERY}, {@code PICKUP}, {@code DINE_IN} or {@code *}
     * @param channel         {@code TELEGRAM}, {@code SMS}, {@code EMAIL}, {@code PUSH} or {@code *}
     * @param delayMinutes    how long after completion the prompt becomes sendable
     * @param giveUpAfterHours how long after it became sendable the prompt is still worth sending
     */
    public record PromptRow(
            String fulfillmentMode, String channel, boolean enabled, int delayMinutes, int giveUpAfterHours) {

        boolean matches(String mode, String requestedChannel) {
            return (ANY.equals(fulfillmentMode) || fulfillmentMode.equals(mode))
                    && (ANY.equals(channel) || channel.equals(requestedChannel));
        }

        /** Higher is more specific: the order type outranks the channel. */
        int specificity() {
            return (ANY.equals(fulfillmentMode) ? 0 : 2) + (ANY.equals(channel) ? 0 : 1);
        }
    }

    /**
     * What resolves when nothing was authored anywhere: Telegram on, SMS, email and push off, thirty
     * minutes after completion, given up after six hours (today's {@code order-expiry}), inside
     * 09:00-21:00, and an inline prompt for a week.
     *
     * <p>SMS is off by default, and stays off until a tenant enables it, for the legal reason in the
     * record's first open input: asking for a review is not a receipt, and a required transactional
     * SMS at 02:00 is a defect row 5.5 is partly about. The preservation migration (V0545) is what
     * keeps a tenant that already had an active SMS template sending it.
     */
    public static FeedbackPromptDocument builtInDefault() {
        return new FeedbackPromptDocument(
                new SendWindow("09:00", "21:00"),
                7,
                List.of(
                        new PromptRow(ANY, "TELEGRAM", true, 30, 6),
                        new PromptRow(ANY, "SMS", false, 30, 6),
                        new PromptRow(ANY, "EMAIL", false, 30, 6),
                        new PromptRow(ANY, "PUSH", false, 30, 6)));
    }

    /**
     * The most specific row for this order and channel in this document, then in the built-in default
     * when the document has none.
     */
    public PromptRow rowFor(String fulfillmentMode, String channel) {
        return best(rows, fulfillmentMode, channel)
                .or(() -> best(builtInDefault().rows, fulfillmentMode, channel))
                // The built-in default names every wired channel, so this is only an unknown channel:
                // an unknown channel is never prompted.
                .orElseGet(() -> new PromptRow(ANY, channel, false, 0, MIN_GIVE_UP_HOURS));
    }

    private static Optional<PromptRow> best(List<PromptRow> candidates, String mode, String channel) {
        PromptRow best = null;
        for (PromptRow candidate : candidates) {
            if (candidate.matches(mode, channel) && (best == null || candidate.specificity() > best.specificity())) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * When a prompt becomes sendable: {@code completedAt} plus the delay, moved to the next opening of
     * the send window when it would fall outside it.
     */
    public Instant scheduledAt(PromptRow row, Instant completedAt, ZoneId zone) {
        Instant earliest = completedAt.plus(Duration.ofMinutes(row.delayMinutes()));
        if (sendWindow == null) {
            return earliest;
        }
        ZonedDateTime local = earliest.atZone(zone);
        LocalTime start = sendWindow.startTime();
        LocalTime end = sendWindow.endTime();
        LocalTime time = local.toLocalTime();
        if (!time.isBefore(start) && time.isBefore(end)) {
            return earliest;
        }
        LocalDate day =
                time.isBefore(start) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
        // A DST gap can swallow the opening minute; resolve to the first valid instant at or after it.
        return day.atTime(start).atZone(zone).toInstant();
    }

    /** When a sendable prompt stops being worth sending. */
    public Instant expiresAt(PromptRow row, Instant scheduledAt) {
        return scheduledAt.plus(Duration.ofHours(row.giveUpAfterHours()));
    }

    /** Why this document may not be published, as sentences; empty when it may. */
    public List<String> violations() {
        List<String> found = new ArrayList<>();
        if (inlineWindowDays < MIN_INLINE_DAYS || inlineWindowDays > MAX_INLINE_DAYS) {
            found.add("inlineWindowDays must be between %d and %d".formatted(MIN_INLINE_DAYS, MAX_INLINE_DAYS));
        }
        if (sendWindow != null) {
            try {
                LocalTime start = sendWindow.startTime();
                LocalTime end = sendWindow.endTime();
                if (!start.isBefore(end)) {
                    found.add("sendWindow must open before it closes (HH:mm, same day)");
                }
            } catch (RuntimeException malformed) {
                found.add("sendWindow start and end must be HH:mm");
            }
        }
        if (rows.size() > MAX_ROWS) {
            found.add("a prompt policy has at most %d rows".formatted(MAX_ROWS));
        }
        Set<String> seen = new HashSet<>();
        for (PromptRow row : rows) {
            if (!MODES.contains(row.fulfillmentMode())) {
                found.add("fulfillmentMode must be DELIVERY, PICKUP, DINE_IN or *: " + row.fulfillmentMode());
            }
            if (!CHANNELS.contains(row.channel())) {
                found.add("channel must be TELEGRAM, SMS, EMAIL, PUSH or *: " + row.channel());
            }
            if (!seen.add(row.fulfillmentMode() + "|" + row.channel())) {
                found.add("two rows name the same order type and channel: %s / %s"
                        .formatted(row.fulfillmentMode(), row.channel()));
            }
            if (row.delayMinutes() < 0 || row.delayMinutes() > MAX_DELAY_MINUTES) {
                found.add("delayMinutes must be between 0 and " + MAX_DELAY_MINUTES);
            }
            if (row.giveUpAfterHours() < MIN_GIVE_UP_HOURS || row.giveUpAfterHours() > MAX_GIVE_UP_HOURS) {
                found.add("giveUpAfterHours must be between %d and %d".formatted(MIN_GIVE_UP_HOURS, MAX_GIVE_UP_HOURS));
            }
        }
        return List.copyOf(found);
    }
}
