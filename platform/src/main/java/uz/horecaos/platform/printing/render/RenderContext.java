package uz.horecaos.platform.printing.render;

import java.time.Instant;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * What the renderer is told when an artifact is asked for, beside the stored document (ADR 0154,
 * Decision 7): how many times the job has been claimed, when it was queued, what time it is now, and how
 * long a wait is worth saying on the paper.
 *
 * <p>These are render inputs and never part of the document, so a job's digest is the same whether it
 * printed at once, was retried or sat behind an offline printer, and the retry and delayed lines are
 * drawn above the fiscal block and never inside it.
 *
 * @param attempt       the claim this render is for; 1 is the first, so a retry is greater than 1
 * @param lateMarkerMinutes {@code printing.late_marker_minutes}: a document that waited longer than this
 *                      since it was queued says «delayed» with the time it was queued
 * @param logos         resolves a brand logo (media asset id, width in dots) to a 1-bit picture, or empty
 */
public record RenderContext(
        int attempt,
        Instant queuedAt,
        Instant now,
        int lateMarkerMinutes,
        BiFunction<String, Integer, Optional<Bitmap1>> logos) {

    /** A preview, or a first print that did not wait: no retry line, no delayed line. */
    public static RenderContext fresh(Instant now) {
        return new RenderContext(1, now, now, 5, (asset, width) -> Optional.empty());
    }

    public RenderContext withLogos(BiFunction<String, Integer, Optional<Bitmap1>> resolver) {
        return new RenderContext(attempt, queuedAt, now, lateMarkerMinutes, resolver);
    }

    public boolean retried() {
        return attempt > 1;
    }

    /** Whether the wait since queueing was longer than the bound the location chose. */
    public boolean delayed() {
        return now.isAfter(queuedAt.plusSeconds(lateMarkerMinutes * 60L));
    }
}
