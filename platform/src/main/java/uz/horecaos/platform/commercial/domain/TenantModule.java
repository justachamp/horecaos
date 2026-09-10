package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One module one tenant has, from when, and on whose word (ADR 0087).
 *
 * <p>Ending it keeps the row, because a statement for a month it was live in
 * still has to find it.
 */
public record TenantModule(
        UUID id,
        UUID tenantId,
        UUID moduleId,
        @Nullable Integer quantity,
        Instant startedAt,
        String startedBy,
        String startReason,
        @Nullable Instant endedAt,
        @Nullable String endedBy,
        @Nullable String endReason) {

    public boolean isLive() {
        return endedAt == null;
    }

    /** Whether it was live at any moment of {@code [start, end)}. */
    public boolean overlaps(Instant start, Instant end) {
        Instant ended = endedAt;
        return startedAt.isBefore(end) && (ended == null || ended.isAfter(start));
    }
}
