package uz.horecaos.platform.reviews.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code reviews.recovery} policy document: when a review opens a service-recovery case and how
 * long staff have (ADR 0159, Decisions 8 and 10, ADR 0030).
 *
 * <p>Versioned, and the case stores the version it used, so a later edit never makes an old case late.
 *
 * @param autoOpenMaxRating a rating at or under this opens a case in the review's own transaction;
 *                          {@code 0} means never open one automatically
 * @param firstResponseMinutes how long until someone must start work
 * @param resolutionMinutes    how long until the case must be closed
 * @param alertOnOpen   announce an opened case to the subscribed operations chats; off by default,
 *                      because an alert nobody asked for is the noise that gets a chat muted
 * @param alertOnBreach announce a breached clock to the same chats; off by default for the same reason
 */
public record RecoveryPolicyDocument(
        int autoOpenMaxRating,
        int firstResponseMinutes,
        int resolutionMinutes,
        boolean alertOnOpen,
        boolean alertOnBreach) {

    public static final int MAX_AUTO_OPEN_RATING = 5;
    public static final int MIN_FIRST_RESPONSE_MINUTES = 15;
    public static final int MAX_FIRST_RESPONSE_MINUTES = 10_080;
    public static final int MIN_RESOLUTION_MINUTES = 60;
    public static final int MAX_RESOLUTION_MINUTES = 43_200;

    /** A rating of 2 or less opens a case; first touch in 240 minutes; resolution in 72 hours; wall-clock; no alerts. */
    public static RecoveryPolicyDocument builtInDefault() {
        return new RecoveryPolicyDocument(2, 240, 4320, false, false);
    }

    public boolean opensFor(int rating) {
        return autoOpenMaxRating > 0 && rating <= autoOpenMaxRating;
    }

    public Instant firstResponseDueAt(Instant openedAt) {
        return openedAt.plus(Duration.ofMinutes(firstResponseMinutes));
    }

    public Instant resolutionDueAt(Instant openedAt) {
        return openedAt.plus(Duration.ofMinutes(resolutionMinutes));
    }

    /** Why this document may not be published, as sentences; empty when it may. */
    public List<String> violations() {
        List<String> found = new ArrayList<>();
        if (autoOpenMaxRating < 0 || autoOpenMaxRating > MAX_AUTO_OPEN_RATING) {
            found.add("autoOpenMaxRating must be between 0 (never) and " + MAX_AUTO_OPEN_RATING);
        }
        if (firstResponseMinutes < MIN_FIRST_RESPONSE_MINUTES || firstResponseMinutes > MAX_FIRST_RESPONSE_MINUTES) {
            found.add("firstResponseMinutes must be between %d and %d"
                    .formatted(MIN_FIRST_RESPONSE_MINUTES, MAX_FIRST_RESPONSE_MINUTES));
        }
        if (resolutionMinutes < MIN_RESOLUTION_MINUTES || resolutionMinutes > MAX_RESOLUTION_MINUTES) {
            found.add("resolutionMinutes must be between %d and %d"
                    .formatted(MIN_RESOLUTION_MINUTES, MAX_RESOLUTION_MINUTES));
        }
        if (resolutionMinutes < firstResponseMinutes) {
            found.add("resolutionMinutes must not be shorter than firstResponseMinutes");
        }
        return List.copyOf(found);
    }
}
