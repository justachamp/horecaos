package uz.horecaos.platform.media.application;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.MediaAssetAvailable;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetIngestion;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.media.domain.ImageCostLimits;
import uz.horecaos.platform.media.domain.ImageProbe;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;
import uz.horecaos.platform.media.domain.ProbedImage;
import uz.horecaos.platform.media.infrastructure.UrlImageFetcher;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcDerivativeJobStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;

/**
 * The {@code media.api} face of {@link MediaAssetIngestion} (ADR 0010, row
 * 4.5b) — a caller that already has bytes, or a URL to fetch them from, rather
 * than a client on the other end of a presigned upload.
 *
 * <p>Its own bean rather than a further method on {@link MediaAssetService},
 * even though the two share every dependency and the final "mark available"
 * step is copied from {@link MediaAssetService#publishAvailable} rather than
 * called: {@link MediaAssetService}'s constructor is exercised directly by
 * {@code MediaLifecycleTests} and {@code ExternalCallTransactionBoundaryTests},
 * and widening it for one new caller would touch both for a change that has
 * nothing to do with the presigned lifecycle they cover. The duplication this
 * costs is the same handful of statements {@link MediaAssetService}'s own
 * {@code publishAvailable} has — insert, put, mark available, enqueue a
 * derivative job, publish the event, record the audit fact — not the
 * validation itself, which is identical because both classes call the same
 * {@link ImageProbe}/{@link ImageCostLimits}.
 */
@Service
public class MediaAssetIngestionService implements MediaAssetIngestion {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetIngestionService.class);

    /** Matches {@link MediaAssetService}'s own limit — the same policy, enforced a second time for a different entry point. */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/avif");

    private final JdbcMediaAssetStore store;
    private final JdbcDerivativeJobStore derivativeJobs;
    private final ObjectStorage storage;
    private final UrlImageFetcher urlFetcher;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String bucket;

    public MediaAssetIngestionService(
            JdbcMediaAssetStore store,
            JdbcDerivativeJobStore derivativeJobs,
            ObjectStorage storage,
            UrlImageFetcher urlFetcher,
            TransactionTemplate transactions,
            ApplicationEventPublisher events,
            AuditRecorder audit,
            Clock clock,
            @Value("${horecaos.media.bucket:horecaos-media}") String bucket) {
        this.store = store;
        this.derivativeJobs = derivativeJobs;
        this.storage = storage;
        this.urlFetcher = urlFetcher;
        this.transactions = transactions;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.bucket = bucket;
    }

    @Override
    public IngestOutcome ingestFromUrl(
            UUID tenantId,
            OwnerScope ownerScope,
            UUID ownerId,
            boolean publicVisibility,
            URI url,
            @Nullable UUID actorId) {
        String scheme = url == null ? null : url.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return IngestOutcome.rejected("URL_NOT_ALLOWED");
        }

        Optional<byte[]> fetched = urlFetcher.fetch(url, MAX_IMAGE_BYTES);
        if (fetched.isEmpty()) {
            return IngestOutcome.rejected("FETCH_FAILED");
        }
        return ingestFromBytes(
                tenantId, ownerScope, ownerId, publicVisibility, fetched.get(), lastSegment(url), actorId);
    }

    @Override
    public IngestOutcome ingestFromBytes(
            UUID tenantId,
            OwnerScope ownerScope,
            UUID ownerId,
            boolean publicVisibility,
            byte[] content,
            @Nullable String originalFilename,
            @Nullable UUID actorId) {

        if (content.length == 0 || content.length > MAX_IMAGE_BYTES) {
            return IngestOutcome.rejected("SIZE_EXCEEDED");
        }

        // The bytes' own header, never the caller's word and never a response
        // header from wherever they came from -- this is the whole point of
        // this class. A short array is passed through unchanged; ImageProbe
        // already treats "too short to contain a header" as "not an image".
        byte[] header =
                content.length <= ImageProbe.PROBE_BYTES ? content : Arrays.copyOf(content, ImageProbe.PROBE_BYTES);
        Optional<ProbedImage> probed = ImageProbe.probe(header);
        if (probed.isEmpty()) {
            return IngestOutcome.rejected("CONTENT_NOT_AN_IMAGE");
        }
        ProbedImage image = probed.get();
        if (!ALLOWED_IMAGE_TYPES.contains(image.contentType())) {
            return IngestOutcome.rejected("TYPE_NOT_ALLOWED");
        }
        if (!ImageCostLimits.withinBudget(image)) {
            return IngestOutcome.rejected("DIMENSIONS_EXCEEDED");
        }

        MediaOwner owner = toMediaOwner(ownerScope, ownerId);
        MediaAssetId assetId = MediaAssetId.generate();
        String key =
                "%s/%s/%s/%s".formatted(tenantId, owner.scope().name().toLowerCase(Locale.ROOT), owner.id(), assetId);
        Instant now = clock.instant();
        String checksum = sha256Base64(content);

        MediaAsset asset = new MediaAsset(
                assetId,
                tenantId,
                owner,
                key,
                bucket,
                MediaAssetStatus.PENDING_UPLOAD,
                publicVisibility ? MediaVisibility.PUBLIC : MediaVisibility.PRIVATE,
                image.contentType(),
                content.length,
                checksum,
                null,
                null,
                null,
                safeLabel(originalFilename),
                null,
                null,
                actorId,
                null,
                now);
        store.insertPending(asset);

        // The object is written before the row is marked available, matching
        // MediaDerivativeService's own ordering comment: the other order
        // leaves a row pointing at nothing, which surfaces as a broken image;
        // this order can at worst leave an unreferenced object, which a
        // lifecycle rule sweeps up.
        storage.put(bucket, key, image.contentType(), content);

        ObjectStorage.StoredObject stored =
                new ObjectStorage.StoredObject(content.length, image.contentType(), Optional.of(checksum), checksum);
        publishAvailable(asset, image, stored, now);
        log.info("Media asset {} ingested server-side ({}) and available", assetId, ownerScope);
        return IngestOutcome.accepted(assetId);
    }

    /** The one transaction on this path -- see {@code MediaAssetService#publishAvailable}'s own doc for why these four things are one write. */
    private void publishAvailable(MediaAsset asset, ProbedImage image, ObjectStorage.StoredObject object, Instant now) {
        transactions.executeWithoutResult(status -> {
            store.markAvailable(
                    asset.assetId(),
                    image.contentType(),
                    object.sizeBytes(),
                    object.checksumSha256().orElse(object.eTag()),
                    image.widthPx(),
                    image.heightPx(),
                    now);

            derivativeJobs.enqueue(UUID.randomUUID(), asset.tenantId(), asset.assetId(), now);

            events.publishEvent(new MediaAssetAvailable(
                    UUID.randomUUID(),
                    asset.tenantId(),
                    asset.assetId(),
                    now,
                    asset.owner().scope().name(),
                    asset.owner().id(),
                    asset.visibility().name(),
                    image.contentType(),
                    object.sizeBytes(),
                    image.widthPx(),
                    image.heightPx()));

            Map<String, Object> changed = new LinkedHashMap<>();
            changed.put("status", MediaAssetStatus.AVAILABLE.name());
            changed.put("verifiedContentType", image.contentType());
            changed.put("verifiedSizeBytes", object.sizeBytes());
            changed.put("ingestedServerSide", "true");

            audit.record(AuditFact.of("media.asset.ingested", AuditClass.BUSINESS)
                    .by(
                            asset.createdBy() == null
                                    ? ActorRef.systemJob("catalog-import")
                                    : ActorRef.user(asset.createdBy().toString(), null))
                    .at(ResourceScope.tenant(asset.tenantId()))
                    .target("media_asset", asset.assetId().value())
                    .correlatedBy(asset.assetId().value().toString())
                    .occurredAt(now)
                    .changed(changed)
                    .build());
        });
    }

    private static MediaOwner toMediaOwner(OwnerScope scope, UUID ownerId) {
        return switch (scope) {
            case TENANT -> MediaOwner.tenant(ownerId);
            case BRAND -> MediaOwner.brand(ownerId);
            case LOCATION -> MediaOwner.location(ownerId);
        };
    }

    /** The URL's last path segment only, as a display label -- never the query string, which may carry a signed token. */
    private static @Nullable String lastSegment(URI url) {
        String path = url.getPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String segment = slash >= 0 ? path.substring(slash + 1) : path;
        return segment.isBlank() ? null : segment;
    }

    /** Kept only as a display label; it never reaches the object key. Mirrors {@code MediaAssetService#safeLabel}. */
    private static @Nullable String safeLabel(@Nullable String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String stripped = filename.strip().replaceAll("[\\p{Cntrl}/\\\\]", "");
        return stripped.length() > 255 ? stripped.substring(0, 255) : stripped;
    }

    private static String sha256Base64(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is a JDK-mandated algorithm (every conformant JCE provider
            // set includes it); this can never actually throw.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
