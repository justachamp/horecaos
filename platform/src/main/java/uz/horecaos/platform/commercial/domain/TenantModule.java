package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One module one tenant has, from when, and on whose word (ADR 0087).
 *
 * <p>Ending it keeps the row, because a statement for a month it was live in
 * still has to find it.
 *
 * <p>{@code acquiredVia} records the door it came through (ADR 0127): only a
 * {@link ModuleAcquisition#SELF_SERVICE} module can be ended by the tenant.
 */
public record TenantModule(
        UUID id,
        UUID tenantId,
        UUID moduleId,
        @Nullable Integer quantity,
        Instant startedAt,
        String startedBy,
        String startReason,
        ModuleAcquisition acquiredVia,
        @Nullable Instant endedAt,
        @Nullable String endedBy,
        @Nullable String endReason) {

    public boolean isLive() {
        return endedAt == null;
    }

    /** Whether the tenant itself may end this module: it bought it, and it is still live. */
    public boolean endableByTenant() {
        return isLive() && acquiredVia == ModuleAcquisition.SELF_SERVICE;
    }

    /** Whether it was live at any moment of {@code [start, end)}. */
    public boolean overlaps(Instant start, Instant end) {
        Instant ended = endedAt;
        return startedAt.isBefore(end) && (ended == null || ended.isAfter(start));
    }
}
