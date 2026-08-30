package uz.horecaos.platform.media.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.media.api.MediaAssetAvailable;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.media.domain.ImageCostLimits;
import uz.horecaos.platform.media.domain.ImageProbe;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;
import uz.horecaos.platform.media.domain.ProbedImage;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcDerivativeJobStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;

/**
 * The presigned upload lifecycle (ADR 0010).
 *
 * <p>Three steps, and the separation between them is the point: allocate records
 * an intent and hands out a constrained URL, the client uploads directly to the
 * store, and finalize verifies what actually arrived. Nothing becomes displayable
 * on a client's say-so.
 */
@Service
public class MediaAssetService implements MediaAvailability {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetService.class);

    /**
     * Long enough for a slow mobile upload, short enough that a leaked URL is
     * not a lasting write capability.
     */
    private static final Duration UPLOAD_WINDOW = Duration.ofMinutes(15);

    private static final Duration DOWNLOAD_WINDOW = Duration.ofMinutes(5);

    /**
     * Image types only, and checked against what the bytes actually are rather
     * than what the request said. SVG is excluded deliberately: it is a document
     * format that can carry script, so serving user-supplied SVG from our own
     * origin is a stored cross-site-scripting vector.
     */
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/avif");

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final JdbcMediaAssetStore store;
    private final JdbcDerivativeJobStore derivativeJobs;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String bucket;

    public MediaAssetService(
            JdbcMediaAssetStore store,
            JdbcDerivativeJobStore derivativeJobs,
            ObjectStorage storage,
            TransactionTemplate transactions,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${horecaos.media.bucket:horecaos-media}") String bucket) {
        this.store = store;
        this.derivativeJobs = derivativeJobs;
        this.storage = storage;
        this.transactions = transactions;
        this.events = events;
        this.clock = clock;
        this.bucket = bucket;
    }

    /**
     * Reserves an id and a key, and returns a URL constrained to them.
     *
     * @throws IllegalArgumentException if the declared type or size is outside policy
     */
    @Transactional
    public UploadTicket requestUpload(
            UUID tenantId,
            MediaOwner owner,
            MediaVisibility visibility,
            String contentType,
            long sizeBytes,
            String originalFilename,
            UUID actorId) {

        String normalizedType =
                contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).strip();
        if (!ALLOWED_IMAGE_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("Unsupported media type: " + contentType);
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Media size must be between 1 and " + MAX_IMAGE_BYTES + " bytes");
        }

        MediaAssetId assetId = MediaAssetId.generate();
        // The key is ours, derived from ids we control. A client-supplied name
        // anywhere in this path would allow traversal and cross-tenant
        // overwrite; the original filename is stored as a label only.
        String key =
                "%s/%s/%s/%s".formatted(tenantId, owner.scope().name().toLowerCase(Locale.ROOT), owner.id(), assetId);

        Instant now = clock.instant();
        store.insertPending(new MediaAsset(
                assetId,
                tenantId,
                owner,
                key,
                bucket,
                MediaAssetStatus.PENDING_UPLOAD,
                visibility,
                normalizedType,
                sizeBytes,
                null,
                null,
                null,
                null,
                safeLabel(originalFilename),
                null,
                null,
                actorId,
                now));

        ObjectStorage.PresignedUpload presigned =
                storage.presignUpload(bucket, key, normalizedType, sizeBytes, UPLOAD_WINDOW);

        return new UploadTicket(assetId, presigned.url(), presigned.requiredHeaders(), presigned.expiresAt());
    }

    /**
     * Verifies what arrived and decides whether it may be shown.
     *
     * <p>Every check reads the object store, never the request. A finalize call
     * carrying "trust me, it's a 200KB JPEG" is exactly the claim an attacker
     * would make.
     *
     * <p>Verification is synchronous rather than a queued worker, which is a
     * departure from ADR 0010's original sketch and is worth stating. Every check
     * here is bounded: one {@code HeadObject}, one ranged read of at most 128KB,
     * and header arithmetic. Deferring them would buy nothing — the asset is not
     * displayable until it is verified either way — while costing a second
     * lifecycle to operate, a reaper for assets stranded in {@code UPLOADED}, and
     * an upload screen that can only say "we will tell you later" instead of
     * "that is not a JPEG". What genuinely cannot be synchronous is derivative
     * rendering, which is CPU-bound and unbounded in latency; that is the part
     * the ADR's asynchronous stage is for.
     *
     * <p>Deliberately <strong>not</strong> {@code @Transactional}, and that is a
     * decision rather than an omission. {@code head} and {@code readPrefix} below
     * are blocking round-trips to the object store; a transaction around them
     * would hold one of ten pooled connections for however long a degraded MinIO
     * takes to answer, so ten concurrent finalizes would own every connection the
     * application has and stall ordering, tenancy and everything else with them.
     * Nothing here needs one: the method makes independent reads and a single
     * write, and each is atomic on its own.
     *
     * <p>What the split does cost is the snapshot the two used to share, which is
     * why the status is read a second time below before anything is written.
     *
     * <p>There <em>is</em> one transaction on the success path, opened in
     * {@link #publishAvailable} after the last object-store call has returned. It
     * covers three local statements and exists because the availability, the
     * derivative job it owes and the outbox fact have to commit or fail
     * together.
     */
    public MediaAssetStatus finalizeUpload(UUID tenantId, MediaAssetId assetId) {
        MediaAsset asset = store.findOwned(tenantId, assetId)
                .orElseThrow(() -> new IllegalArgumentException("No such media asset"));

        if (asset.status() == MediaAssetStatus.AVAILABLE) {
            // Finalize is idempotent: a client retrying after a lost response
            // must not turn an available asset back into a pending one.
            return MediaAssetStatus.AVAILABLE;
        }

        Optional<ObjectStorage.StoredObject> stored = storage.head(asset.bucket(), asset.objectKey());

        // Re-read, because the head call ran outside any transaction and a
        // concurrent finalize may have settled this asset while it was in
        // flight. Without this, the slower of two calls would write its verdict
        // over an already-published asset — which is the un-publish the
        // idempotency guard above exists to prevent, arriving by another door.
        MediaAssetStatus current = store.findOwned(tenantId, assetId)
                .map(MediaAsset::status)
                .orElseThrow(() -> new IllegalArgumentException("No such media asset"));
        if (current == MediaAssetStatus.AVAILABLE) {
            return MediaAssetStatus.AVAILABLE;
        }

        if (stored.isEmpty()) {
            return reject(asset, "OBJECT_MISSING", "No object exists at the allocated key");
        }
        ObjectStorage.StoredObject object = stored.get();

        if (object.sizeBytes() > MAX_IMAGE_BYTES) {
            return reject(asset, "SIZE_EXCEEDED", "Stored object is %d bytes".formatted(object.sizeBytes()));
        }
        if (object.sizeBytes() != asset.declaredSizeBytes()) {
            // A mismatch means the upload was not what was authorised. It is
            // rejected rather than accepted-as-found, because the declared size
            // is what the presigned URL was signed for.
            return reject(
                    asset,
                    "SIZE_MISMATCH",
                    "Declared %d bytes, stored %d".formatted(asset.declaredSizeBytes(), object.sizeBytes()));
        }

        String storedType = object.contentType() == null
                ? ""
                : object.contentType().toLowerCase(Locale.ROOT).strip();
        if (!ALLOWED_IMAGE_TYPES.contains(storedType)) {
            return reject(asset, "TYPE_NOT_ALLOWED", "Stored content type is " + storedType);
        }

        // Everything above this line is still the client's word. The presigned
        // URL signs the content type, so the store refuses a PUT whose header
        // differs — but the header came from the same client as the bytes, and
        // HeadObject reports it back unchanged. Reading the image's own header is
        // the first check in this method that the uploader did not author.
        Optional<ProbedImage> probed =
                ImageProbe.probe(storage.readPrefix(asset.bucket(), asset.objectKey(), ImageProbe.PROBE_BYTES));
        if (probed.isEmpty()) {
            return reject(asset, "CONTENT_NOT_AN_IMAGE", "The stored bytes do not begin with a supported image header");
        }
        ProbedImage image = probed.get();
        if (!image.contentType().equals(storedType)) {
            // Not silently corrected to what the bytes are: the declared type is
            // what the URL was signed for and what a storefront would serve this
            // as, so a disagreement means the upload was not the one authorised.
            return reject(
                    asset, "TYPE_MISMATCH", "Declared %s, content is %s".formatted(storedType, image.contentType()));
        }
        if (!ImageCostLimits.withinBudget(image)) {
            // The header's dimensions and its sample depth together, because
            // neither alone bounds a decode. Forty megapixels is 40MB as 8-bit
            // greyscale and 305MB as 16-bit RGBA, and a limit that counted only
            // pixels admitted both — a 311KB PNG declaring 8000x5000 at 16-bit
            // RGBA sat exactly on it and cost a third of a gigabyte to render.
            //
            // The code is unchanged so the rejection stays one thing to an
            // operator and to anything that has been reading it; what changed is
            // the quantity it is a verdict on. The reason names the cost, since
            // "8000x5000 was refused" is not an explanation on its own.
            return reject(
                    asset,
                    "DIMENSIONS_EXCEEDED",
                    "Header declares %dx%d at %d byte(s) per decoded pixel, %d bytes to decode"
                            .formatted(
                                    image.widthPx(),
                                    image.heightPx(),
                                    image.decodedBytesPerPixel(),
                                    image.decodedBytes()));
        }

        publishAvailable(asset, image, object, clock.instant());
        log.info("Media asset {} verified and available", assetId);
        return MediaAssetStatus.AVAILABLE;
    }

    /**
     * The one transaction on this path: the asset becomes displayable, its
     * renditions become owed, and the fact becomes publishable, together.
     *
     * <p>All three or none of them. An asset marked {@code AVAILABLE} without a
     * job row is an image that will never get a thumbnail and nothing that says
     * so; a job row without the asset is a worker looking for something that is
     * still pending. The outbox append rides the same transaction through
     * {@code @TransactionalEventListener(BEFORE_COMMIT)}, which is what keeps
     * ADR 0004's rule — no Kafka publish inside a business transaction, a row in
     * the same transaction and a relay that publishes it.
     *
     * <p>Short on purpose, and it is why the object-store calls above are
     * outside it. Three statements and no network, so this holds a pooled
     * connection for microseconds rather than for however long a degraded MinIO
     * takes to answer a head request.
     */
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

            // Enqueued rather than rendered here. Rendering decodes a raster and
            // re-encodes it three times; on this thread it would put an
            // attacker-chosen decode on a request and hold this transaction open
            // across three object writes.
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
        });
    }

    /** A short-lived read URL for a private asset. */
    @Transactional(readOnly = true)
    public Optional<java.net.URI> downloadUrl(UUID tenantId, MediaAssetId assetId) {
        return store.findOwned(tenantId, assetId)
                .filter(asset -> asset.status().isDisplayable())
                .map(asset -> storage.presignDownload(asset.bucket(), asset.objectKey(), DOWNLOAD_WINDOW));
    }

    @Transactional(readOnly = true)
    public Optional<MediaAsset> find(UUID tenantId, MediaAssetId assetId) {
        return store.findOwned(tenantId, assetId);
    }

    /**
     * Whether these assets may be attached to something a customer will see.
     *
     * <p>Catalog calls this before publishing. Publishing a reference to an
     * asset that is still pending would produce a live menu with broken images.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean allDisplayable(UUID tenantId, Set<MediaAssetId> assetIds) {
        return assetIds.stream()
                .allMatch(id -> store.findOwned(tenantId, id)
                        .map(asset -> asset.status().isDisplayable())
                        .orElse(false));
    }

    private MediaAssetStatus reject(MediaAsset asset, String code, String detail) {
        store.markRejected(asset.assetId(), code, detail, clock.instant());
        log.warn("Media asset {} rejected: {}", asset.assetId(), code);
        return MediaAssetStatus.REJECTED;
    }

    /** Kept only as a display label; it never reaches the object key. */
    private static String safeLabel(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String stripped = filename.strip().replaceAll("[\\p{Cntrl}/\\\\]", "");
        return stripped.length() > 255 ? stripped.substring(0, 255) : stripped;
    }

    public record UploadTicket(
            MediaAssetId assetId,
            java.net.URI uploadUrl,
            java.util.Map<String, String> requiredHeaders,
            Instant expiresAt) {}
}
