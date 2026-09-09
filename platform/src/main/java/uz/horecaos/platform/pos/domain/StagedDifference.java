package uz.horecaos.platform.pos.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One persisted {@link SyncDifference}, with its review state (ADR 0012).
 *
 * <p>{@link SyncDifference} itself is the engine's pure output and carries no
 * row identity — it does not need one to be compared or sorted. Deciding and
 * applying one, though, means addressing a specific stored row, and review
 * state (who decided, what, and when) has no place on a value the engine
 * recomputes identically on every run. This is the read model that joins the
 * two back together.
 */
public record StagedDifference(
        UUID id,
        SyncDifference difference,
        @Nullable ReviewOutcome reviewOutcome,
        @Nullable String reviewedBy,
        @Nullable Instant reviewedAt,
        @Nullable String reviewNote) {

    public boolean decided() {
        return reviewOutcome != null;
    }
}
