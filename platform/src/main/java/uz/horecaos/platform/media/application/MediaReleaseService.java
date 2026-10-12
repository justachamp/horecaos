package uz.horecaos.platform.media.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaRelease;
import uz.horecaos.platform.media.api.MediaUsageProbe;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;

/**
 * {@link MediaRelease}: ask every module whether it still uses the asset, and only if none does, make
 * the asset undisplayable and queue its objects for the deletion worker (ADR 0157).
 *
 * <p>The probes are asked first and the status change is the commit point. There is a window between
 * the two in which a draft could start to name the asset; closing it with a lock across modules would
 * be worse than the race, and what the race produces is safe: the asset is no longer displayable, so
 * that draft's publication is refused with {@code CONTENT_MEDIA_NOT_DISPLAYABLE} rather than showing a
 * broken image.
 */
@Service
public class MediaReleaseService implements MediaRelease {

    private static final Logger log = LoggerFactory.getLogger(MediaReleaseService.class);

    private final JdbcMediaAssetStore store;
    private final List<MediaUsageProbe> probes;
    private final TransactionTemplate transactions;
    private final AuditRecorder audit;
    private final Clock clock;

    public MediaReleaseService(
            JdbcMediaAssetStore store,
            List<MediaUsageProbe> probes,
            TransactionTemplate transactions,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.probes = List.copyOf(probes);
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public Outcome release(UUID tenantId, UUID assetId, String reason) {
        var asset = store.findOwned(tenantId, new MediaAssetId(assetId));
        if (asset.isEmpty() || !store.isReleasable(tenantId, new MediaAssetId(assetId))) {
            return Outcome.NOT_RELEASABLE;
        }
        for (MediaUsageProbe probe : probes) {
            if (probe.inUse(tenantId, Set.of(assetId)).contains(assetId)) {
                // The module, never the row: which tenant's brand uses what is not a log line.
                log.debug("Media asset {} kept: still used by {}", assetId, probe.module());
                return Outcome.STILL_IN_USE;
            }
        }
        Instant now = clock.instant();
        Boolean moved = transactions.execute(status -> {
            if (!store.requestRelease(tenantId, new MediaAssetId(assetId))) {
                return false;
            }
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", asset.get().status().name());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", "DELETION_REQUESTED");
            audit.record(AuditFact.of("media.asset.released", AuditClass.BUSINESS)
                    .by(ActorRef.systemJob("content-revision-media-sweeper"))
                    .at(ResourceScope.tenant(tenantId))
                    .target("media_asset", assetId)
                    .correlatedBy(assetId.toString())
                    .because(reason)
                    .changed(ChangeDocuments.diff(before, after))
                    .occurredAt(now)
                    .build());
            return true;
        });
        return Boolean.TRUE.equals(moved) ? Outcome.RELEASED : Outcome.NOT_RELEASABLE;
    }
}
