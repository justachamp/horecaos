package uz.horecaos.platform.content.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.SupersededAsset;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.media.api.MediaRelease;

/**
 * Releases the media that only superseded revisions use, once the retention window has passed (ADR 0157,
 * Media and video).
 *
 * <p>Revision rows are kept for good -- they are small text and the evidence of what customers were
 * shown -- but a revision's pictures and videos are the expensive part, and a brand that republishes a
 * banner weekly would otherwise keep every version's artwork forever. After
 * {@code content.revision_media_retention_days} (ninety by default) from the moment a revision was
 * superseded, an asset <em>every one of whose uses is superseded</em> is handed to the media module's
 * {@link MediaRelease}, which asks every module that stores asset ids whether it still uses it and, if
 * none does, queues the objects for deletion. Republishing an older revision whose media was released is
 * then refused with {@code CONTENT_REVISION_MEDIA_RELEASED}.
 *
 * <p>Safe to repeat and to run twice: an asset already released is skipped (it is no longer displayable),
 * a refusal changes nothing, and a pass that stops half way leaves the rest for the next. One pass at a
 * time in a process.
 */
@Component
@ConditionalOnProperty(name = "horecaos.content.media-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class ContentRevisionMediaSweeper {

    private static final Logger log = LoggerFactory.getLogger(ContentRevisionMediaSweeper.class);
    private static final String REASON = "Superseded content revision past its media retention";
    private static final int PAGE = 200;

    private final JdbcContentStore store;
    private final MediaCreativeLookup media;
    private final MediaRelease release;
    private final ContentEnvironment environment;
    private final ContentMetrics metrics;
    private final Clock clock;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    public ContentRevisionMediaSweeper(
            JdbcContentStore store,
            MediaCreativeLookup media,
            MediaRelease release,
            ContentEnvironment environment,
            ContentMetrics metrics,
            Clock clock,
            @Value("${horecaos.content.media-sweep.batch-size:50}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("A media sweep batch size must be positive");
        }
        this.store = store;
        this.media = media;
        this.release = release;
        this.environment = environment;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.content.media-sweep.initial-delay:PT5M}",
            fixedDelayString = "${horecaos.content.media-sweep.interval:PT6H}")
    public void sweepScheduled() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            log.error("Content media sweep could not run", failure);
        }
    }

    /**
     * Works one pass.
     *
     * @return the outcome counts of the release requests this pass made
     */
    public Result sweepOnce() {
        if (!running.compareAndSet(false, true)) {
            return new Result(0, 0, 0);
        }
        try {
            int released = 0;
            int kept = 0;
            int skipped = 0;
            int budget = batchSize;
            Instant now = clock.instant();
            for (UUID tenantId : store.tenantsWithSupersededMedia()) {
                if (budget <= 0) {
                    break;
                }
                Instant cutoff = now.minus(Duration.ofDays(environment.revisionMediaRetentionDays(tenantId)));
                Instant afterAt = Instant.EPOCH;
                UUID afterAsset = new UUID(0L, 0L);
                while (budget > 0) {
                    List<SupersededAsset> page = store.supersededAssets(tenantId, cutoff, afterAt, afterAsset, PAGE);
                    if (page.isEmpty()) {
                        break;
                    }
                    List<UUID> ids = new ArrayList<>(page.size());
                    for (SupersededAsset candidate : page) {
                        ids.add(candidate.assetId());
                    }
                    Map<UUID, MediaCreativeLookup.Creative> described = media.describe(tenantId, ids);
                    for (SupersededAsset candidate : page) {
                        afterAt = candidate.supersededAt();
                        afterAsset = candidate.assetId();
                        MediaCreativeLookup.Creative creative = described.get(candidate.assetId());
                        if (budget <= 0) {
                            break;
                        }
                        if (creative == null || !creative.status().isDisplayable()) {
                            // Already released or deleted, or not a thing media still holds: nothing to do.
                            skipped++;
                            continue;
                        }
                        budget--;
                        MediaRelease.Outcome outcome = release.release(tenantId, candidate.assetId(), REASON);
                        metrics.mediaRelease(outcome.name());
                        if (outcome == MediaRelease.Outcome.RELEASED) {
                            released++;
                        } else {
                            kept++;
                        }
                    }
                    if (page.size() < PAGE) {
                        break;
                    }
                }
            }
            if (released > 0 || kept > 0) {
                log.info("Content media sweep released {} asset(s) and kept {}", released, kept);
            }
            return new Result(released, kept, skipped);
        } finally {
            running.set(false);
        }
    }

    /** What a pass did: assets released, assets some module still uses, candidates that needed nothing. */
    public record Result(int released, int kept, int skipped) {}
}
