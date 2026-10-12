package uz.horecaos.platform.content.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Whether a published item is in front of customers <em>at this instant</em> (ADR 0157, Decision 2).
 *
 * <p>Computed from the live revision's window at the moment of every read -- start inclusive, end
 * exclusive -- and never stored. A pure function of an instant and two nullable bounds, which is
 * what lets one assembler serve the live read and the preview.
 */
public enum ContentVisibility {
    LIVE,
    SCHEDULED,
    EXPIRED;

    public static ContentVisibility of(@Nullable Instant startsAt, @Nullable Instant endsAt, Instant now) {
        if (startsAt != null && now.isBefore(startsAt)) {
            return SCHEDULED;
        }
        if (endsAt != null && !now.isBefore(endsAt)) {
            return EXPIRED;
        }
        return LIVE;
    }
}
