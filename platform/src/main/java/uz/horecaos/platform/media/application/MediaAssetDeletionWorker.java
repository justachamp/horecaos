package uz.horecaos.platform.media.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.media.domain.MediaDerivative;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore.DeletionCandidate;

/**
 * The second half of {@code AVAILABLE -> DELETION_REQUESTED -> DELETED} (ADR
 * 0010): removes the objects of every asset somebody asked to delete, then says
 * so.
 *
 * <p><strong>Why this exists.</strong> A staff member's photo is a face, which is
 * personal data (ADR 0029). Removing it, replacing it, losing an upload race and
 * the retention anonymisation all used to clear the reference and nothing else, so
 * the picture and every rendition of it stayed in the store for good, and a
 * comment in {@code StaffMemberService} promised a sweep that was never written.
 * The request now arrives as a database write inside the caller's transaction
 * ({@link uz.horecaos.platform.iam.api.staff.StaffPhotos#discard}), which makes the
 * asset stop being displayable at once; this worker does the store round trip,
 * outside any transaction, and retries it until the store agrees.
 *
 * <p><strong>Safe to repeat and to run twice.</strong> Deleting an object that is
 * already gone is not an error to the store, the status change is guarded by the
 * status it expects, and the work list is simply the rows still waiting. A store
 * that is down leaves the asset {@code DELETION_REQUESTED} for the next pass; it
 * is never marked {@code DELETED} before the objects are. The renditions go first
 * and the source last, so a pass that stops half way leaves the source, which is
 * what the next pass uses to find the rest.
 *
 * <p>No tenant is named in a metric and no name or filename in a log line: an
 * asset id and its tenant id are all this says.
 */
@Component
@ConditionalOnProperty(name = "horecaos.media.deletion.enabled", havingValue = "true", matchIfMissing = true)
public class MediaAssetDeletionWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetDeletionWorker.class);

    private final JdbcMediaAssetStore assets;
    private final MediaDerivativeStore derivatives;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Counter deleted;
    private final Counter failed;

    /** One pass at a time in this process: a batch can outlast the interval, and two overlapping would only repeat work. */
    private final AtomicBoolean running = new AtomicBoolean();

    public MediaAssetDeletionWorker(
            JdbcMediaAssetStore assets,
            MediaDerivativeStore derivatives,
            ObjectStorage storage,
            TransactionTemplate transactions,
            Clock clock,
            MeterRegistry meters,
            @Value("${horecaos.media.deletion.batch-size:25}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("A deletion batch size must be positive");
        }
        this.assets = assets;
        this.derivatives = derivatives;
        this.storage = storage;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.deleted = meters.counter("horecaos.media.deletion.assets", "outcome", "deleted");
        this.failed = meters.counter("horecaos.media.deletion.assets", "outcome", "failed");
    }

    @Scheduled(
            initialDelayString = "${horecaos.media.deletion.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.media.deletion.interval:PT1M}")
    public void deleteScheduledBatch() {
        try {
            deleteOnce();
        } catch (RuntimeException failure) {
            // Listing the work failed (the database), not one asset: nothing to settle, try again next time.
            log.error("Media deletion could not run", failure);
        }
    }

    /**
     * Works one batch.
     *
     * @return how many assets this pass finished deleting
     */
    public int deleteOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<DeletionCandidate> batch = assets.deletionRequested(batchSize);
            int finished = 0;
            for (DeletionCandidate candidate : batch) {
                try {
                    purge(candidate);
                    deleted.increment();
                    finished++;
                } catch (RuntimeException unavailable) {
                    // The store (or the database) could not be reached for this one. It stays
                    // DELETION_REQUESTED and is the first thing the next pass tries again.
                    failed.increment();
                    log.warn(
                            "Media asset {} of tenant {} is waiting to be deleted and could not be removed yet",
                            candidate.assetId().value(),
                            candidate.tenantId(),
                            unavailable);
                }
            }
            return finished;
        } finally {
            running.set(false);
        }
    }

    private void purge(DeletionCandidate candidate) {
        List<MediaDerivative> renditions = derivatives.findAll(candidate.tenantId(), candidate.assetId());
        for (MediaDerivative rendition : renditions) {
            storage.delete(rendition.bucket(), rendition.objectKey());
        }
        storage.delete(candidate.bucket(), candidate.objectKey());
        // Only now, and in one short transaction of their own: the objects are gone.
        transactions.executeWithoutResult(status -> {
            derivatives.deleteAll(candidate.tenantId(), candidate.assetId());
            assets.markDeleted(candidate.tenantId(), candidate.assetId(), clock.instant());
        });
    }
}
