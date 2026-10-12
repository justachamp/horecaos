package uz.horecaos.platform.content.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
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
 * a refusal changes nothing, and a pass that stops half way leaves the rest for the next, which goes on
 * from there. One pass at a time in a process.
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

    /** Per tenant, where the last pass that ran out of budget stopped. Touched only by the pass that holds {@link #running}. */
    private final Map<UUID, Cursor> cursors = new ConcurrentHashMap<>();

    private volatile @Nullable UUID lastTenantSpentIn;

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
     * <p>A pass spends at most {@code batchSize} release requests, and an asset some module still uses
     * is a request that changes nothing: it is still a candidate on the next pass, and the one after.
     * Starting every pass from the oldest candidate would therefore let the oldest {@code batchSize}
     * held-back assets spend every budget for good, and the tenant that holds them would keep every other
     * tenant waiting. So a pass that runs out of budget remembers where it stopped, per tenant, and the
     * next one goes on from there; the tenant it stopped in goes to the back of the line; and a tenant
     * whose candidates have all been looked at starts again from the oldest, because what held an asset
     * back may be gone by then.
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
            @Nullable UUID spentIn = null;
            for (UUID tenantId : inTurn(store.tenantsWithSupersededMedia())) {
                if (budget <= 0) {
                    break;
                }
                Instant cutoff = now.minus(Duration.ofDays(environment.revisionMediaRetentionDays(tenantId)));
                Cursor from = cursors.getOrDefault(tenantId, Cursor.START);
                Instant afterAt = from.at();
                UUID afterAsset = from.asset();
                boolean stoppedForBudget = false;
                scan:
                while (true) {
                    if (budget <= 0) {
                        stoppedForBudget = true;
                        break;
                    }
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
                        MediaCreativeLookup.Creative creative = described.get(candidate.assetId());
                        if (creative == null || !creative.status().isDisplayable()) {
                            // Already released or deleted, or not a thing media still holds: nothing to do.
                            skipped++;
                        } else {
                            if (budget <= 0) {
                                // Not looked at: the next pass starts with this one.
                                stoppedForBudget = true;
                                break scan;
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
                        afterAt = candidate.supersededAt();
                        afterAsset = candidate.assetId();
                    }
                    if (page.size() < PAGE) {
                        break;
                    }
                }
                if (stoppedForBudget) {
                    cursors.put(tenantId, new Cursor(afterAt, afterAsset));
                } else {
                    cursors.remove(tenantId);
                }
                if (budget <= 0) {
                    spentIn = tenantId;
                }
            }
            lastTenantSpentIn = spentIn;
            if (released > 0 || kept > 0) {
                log.info("Content media sweep released {} asset(s) and kept {}", released, kept);
            }
            return new Result(released, kept, skipped);
        } finally {
            running.set(false);
        }
    }

    /**
     * The tenants in the order this pass takes them: in a fixed order, starting after the tenant the last
     * pass spent its budget in, so the tenants take turns and none is left waiting behind another's
     * held-back assets.
     */
    private List<UUID> inTurn(List<UUID> tenants) {
        List<UUID> ordered = new ArrayList<>(tenants);
        ordered.sort(Comparator.naturalOrder());
        UUID after = lastTenantSpentIn;
        if (after == null) {
            return ordered;
        }
        int start = 0;
        while (start < ordered.size() && ordered.get(start).compareTo(after) <= 0) {
            start++;
        }
        List<UUID> turn = new ArrayList<>(ordered.subList(start, ordered.size()));
        turn.addAll(ordered.subList(0, start));
        return turn;
    }

    /** Where a pass that ran out of budget stopped in one tenant's candidates: the last one it dealt with. */
    private record Cursor(Instant at, UUID asset) {
        static final Cursor START = new Cursor(Instant.EPOCH, new UUID(0L, 0L));
    }

    /** What a pass did: assets released, assets some module still uses, candidates that needed nothing. */
    public record Result(int released, int kept, int skipped) {}
}
