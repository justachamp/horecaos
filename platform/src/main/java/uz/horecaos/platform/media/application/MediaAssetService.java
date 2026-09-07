package uz.horecaos.platform.media.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.MalwareScanner;
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
import uz.horecaos.platform.media.infrastructure.persistence.JdbcVerificationJobStore;

/**
 * The presigned upload lifecycle (ADR 0010).
 *
 * <p>Four steps now, not three, and the separation between them is the point:
 * allocate records an intent and hands out a constrained URL, the client
 * uploads directly to the store, finalize records the client's claim that the
 * upload is complete, and {@link #verifyUpload} — called only by {@link
 * MediaVerificationWorker}, never on a request thread — is what actually reads
 * the store and decides whether the asset may be shown. Nothing becomes
 * displayable on a client's say-so, and nothing blocks a request on the object
 * store's answer either.
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
    private final JdbcVerificationJobStore verificationJobs;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final AuditRecorder audit;
    private final Optional<MalwareScanner> malwareScanner;
    private final Clock clock;
    private final String bucket;

    public MediaAssetService(
            JdbcMediaAssetStore store,
            JdbcDerivativeJobStore derivativeJobs,
            JdbcVerificationJobStore verificationJobs,
            ObjectStorage storage,
            TransactionTemplate transactions,
            ApplicationEventPublisher events,
            AuditRecorder audit,
            // Empty in production today: no malware scanner is registered. See
            // MalwareScanner's own doc for why that is a recorded gap rather
            // than a default that pretends to have scanned something.
            Optional<MalwareScanner> malwareScanner,
            Clock clock,
            @Value("${horecaos.media.bucket:horecaos-media}") String bucket) {
        this.store = store;
        this.derivativeJobs = derivativeJobs;
        this.verificationJobs = verificationJobs;
        this.storage = storage;
        this.transactions = transactions;
        this.events = events;
        this.audit = audit;
        this.malwareScanner = malwareScanner;
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
            @Nullable String originalFilename,
            @Nullable UUID actorId) {

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
                null,
                now));

        ObjectStorage.PresignedUpload presigned =
                storage.presignUpload(bucket, key, normalizedType, sizeBytes, UPLOAD_WINDOW);

        return new UploadTicket(assetId, presigned.url(), presigned.requiredHeaders(), presigned.expiresAt());
    }

    /**
     * Records the client's claim that its upload is complete, and queues
     * verification. Never touches the object store.
     *
     * <p>This used to be where verification happened — one {@code HeadObject},
     * one ranged read of at most 128KB, and header arithmetic, all on the
     * request thread that called it. That was defensible while every check
     * stayed that cheap, but ADR 0010 asked for a separate asynchronous
     * validation worker from the start, and holding a request thread on any
     * object-store round trip, however bounded, is the thing {@link
     * MediaVerificationWorker} now exists to avoid. The lifecycle already had
     * the shape for this: {@code PENDING_UPLOAD}/{@code UPLOADED}/{@code
     * AVAILABLE}/{@code REJECTED} has been in {@code media.assets}'s status
     * check constraint since {@code V0015}, and nothing had ever written {@code
     * UPLOADED} until now.
     *
     * <p>Idempotent by construction rather than by an equality check: {@link
     * JdbcMediaAssetStore#markUploaded} only moves a row that is still {@code
     * PENDING_UPLOAD}, so a retried finalize, or one that lost a race with
     * another concurrent call, finds the asset already past that state and
     * simply reports what it is now — never re-enqueueing a second verification
     * job, never moving a settled asset backward.
     *
     * @return {@code UPLOADED} on the ordinary path, or whatever the asset's
     *         current status already was if this call arrived after the fact
     */
    @Transactional
    public MediaAssetStatus finalizeUpload(UUID tenantId, MediaAssetId assetId) {
        MediaAsset asset = store.findOwned(tenantId, assetId)
                .orElseThrow(() -> new IllegalArgumentException("No such media asset"));

        if (asset.status() != MediaAssetStatus.PENDING_UPLOAD) {
            return asset.status();
        }

        Instant now = clock.instant();
        if (!store.markUploaded(assetId, now)) {
            // Lost the race: another finalize call moved this asset past
            // PENDING_UPLOAD between the read above and this write. Whatever it
            // is now is the honest answer, not necessarily UPLOADED.
            return store.findOwned(tenantId, assetId).map(MediaAsset::status).orElse(MediaAssetStatus.UPLOADED);
        }

        verificationJobs.enqueue(UUID.randomUUID(), tenantId, assetId, now);
        log.info("Media asset {} claims its upload complete; verification queued", assetId);
        return MediaAssetStatus.UPLOADED;
    }

    /**
     * Verifies an upload already claimed complete, and decides whether it may
     * be shown. This is what {@code finalizeUpload} used to do inline.
     *
     * <p>Called only by {@link MediaVerificationWorker}, under a lease, never on
     * a request thread. Every check reads the object store, never the request:
     * a client claiming "it's a 200KB JPEG" is exactly the claim an attacker
     * would make, and by the time a job naming this asset is claimed, there is
     * no request left to trust anyway.
     *
     * <p>Deliberately <strong>not</strong> {@code @Transactional}, for the
     * reason {@code finalizeUpload} itself no longer needs stating: {@code
     * head} and {@code readPrefix} below are blocking round trips to the object
     * store, and a transaction around them would hold one of ten pooled
     * connections for however long a degraded MinIO takes to answer.
     * {@code ExternalCallTransactionBoundaryTests} covers this property on the
     * worker that calls this method now.
     *
     * <p>What the split costs is the snapshot the two round trips used to
     * share, which is why the status is read a second time below before
     * anything is written — the same defence the old synchronous method
     * carried, now guarding against a second worker racing past an expired
     * lease while the first has not actually finished, rather than against two
     * concurrent client retries.
     *
     * <p>There <em>is</em> one transaction on the success and rejection paths,
     * opened in {@link #publishAvailable} or {@link #reject} after the last
     * object-store call has returned.
     *
     * @throws IllegalArgumentException if no such asset belongs to this tenant
     * @throws IllegalStateException    if the asset is not {@code UPLOADED} —
     *                                  a verification job should only ever name
     *                                  one that is, so reaching this means an
     *                                  invariant this class maintains
     *                                  elsewhere broke
     */
    public MediaAssetStatus verifyUpload(UUID tenantId, MediaAssetId assetId) {
        MediaAsset asset = store.findOwned(tenantId, assetId)
                .orElseThrow(() -> new IllegalArgumentException("No such media asset"));

        if (asset.status() == MediaAssetStatus.AVAILABLE || asset.status() == MediaAssetStatus.REJECTED) {
            // Already settled — a redelivered job, most often. Idempotent
            // rather than re-checked, so a retry cannot un-publish an image.
            return asset.status();
        }
        if (asset.status() != MediaAssetStatus.UPLOADED) {
            throw new IllegalStateException(
                    "Media asset %s is not awaiting verification (status %s)".formatted(assetId, asset.status()));
        }

        Optional<ObjectStorage.StoredObject> stored = storage.head(asset.bucket(), asset.objectKey());

        // Re-read: see this method's own doc for why.
        MediaAssetStatus current = store.findOwned(tenantId, assetId)
                .map(MediaAsset::status)
                .orElseThrow(() -> new IllegalArgumentException("No such media asset"));
        if (current == MediaAssetStatus.AVAILABLE || current == MediaAssetStatus.REJECTED) {
            return current;
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

        // The one check this method added that finalizeUpload never had:
        // MalwareScanner is a port with no bound adapter today (see its own
        // doc), so in every deployment that exists right now this branch never
        // runs and an asset reaches AVAILABLE unscanned for malware. When an
        // adapter is registered, this is where it starts mattering without
        // anything else in the pipeline changing.
        if (malwareScanner.isPresent()) {
            MalwareScanner.Verdict verdict = malwareScanner.get().scan(asset.bucket(), asset.objectKey());
            if (!verdict.clean()) {
                return reject(asset, "MALWARE_DETECTED", "Scanner reported " + verdict.signature());
            }
        }

        publishAvailable(asset, image, object, clock.instant());
        log.info("Media asset {} verified and available", assetId);
        return MediaAssetStatus.AVAILABLE;
    }

    /**
     * The one transaction on this path: the asset becomes displayable, its
     * renditions become owed, the fact becomes publishable, and the evidence
     * that it happened is recorded, together.
     *
     * <p>All four or none of them. An asset marked {@code AVAILABLE} without a
     * job row is an image that will never get a thumbnail and nothing that says
     * so; a job row without the asset is a worker looking for something that is
     * still pending; an availability that reached the outbox without an audit
     * row is an act ADR 0027 says somebody should be able to prove later and
     * cannot. The outbox append rides the same transaction through
     * {@code @TransactionalEventListener(BEFORE_COMMIT)}, which is what keeps
     * ADR 0004's rule — no Kafka publish inside a business transaction, a row in
     * the same transaction and a relay that publishes it. {@link AuditRecorder}
     * is the same shape again: {@code JdbcAuditRecorder} carries no transaction
     * of its own, so its insert joins whichever one is open when {@code record}
     * is called.
     *
     * <p>Short on purpose, and it is why the object-store calls above are
     * outside it. Four statements and no network, so this holds a pooled
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

            audit.record(auditFact("media.asset.available", asset, now)
                    .changed(availabilityChangeDocument(asset, image, object))
                    .build());
        });
    }

    /**
     * Settles a verdict of {@code REJECTED} and the evidence that it happened,
     * together — the same reasoning as {@link #publishAvailable}, on the other
     * outcome. Before {@link AuditRecorder} existed here this was a single bare
     * {@code UPDATE}; it needs a transaction now for the same reason
     * {@code publishAvailable} always did, not because the rejection itself
     * became any less atomic on its own.
     */
    private MediaAssetStatus reject(MediaAsset asset, String code, String detail) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            store.markRejected(asset.assetId(), code, detail, now);
            audit.record(auditFact("media.asset.rejected", asset, now)
                    .outcome(AuditFact.Outcome.REJECTED)
                    .because(detail)
                    .changed(Map.of("status", MediaAssetStatus.REJECTED.name(), "rejectionCode", code))
                    .build());
        });
        log.warn("Media asset {} rejected: {}", asset.assetId(), code);
        return MediaAssetStatus.REJECTED;
    }

    /**
     * The fields every media audit fact shares. Actor is always the worker,
     * never the uploading user: {@link #verifyUpload} decides these outcomes
     * asynchronously, on its own lease, and attributing that decision to
     * whoever happened to upload the bytes would misstate who caused it. The
     * uploader is instead named in the change document, for provenance.
     */
    private AuditFact.Builder auditFact(String actionCode, MediaAsset asset, Instant now) {
        return AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.systemJob("media-verification-worker"))
                .at(ResourceScope.tenant(asset.tenantId()))
                .target("media_asset", asset.assetId().value())
                .correlatedBy(asset.assetId().value().toString())
                .occurredAt(now);
    }

    private static Map<String, Object> availabilityChangeDocument(
            MediaAsset asset, ProbedImage image, ObjectStorage.StoredObject object) {
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("status", MediaAssetStatus.AVAILABLE.name());
        changed.put("verifiedContentType", image.contentType());
        changed.put("verifiedSizeBytes", object.sizeBytes());
        if (asset.createdBy() != null) {
            changed.put("uploadedBy", asset.createdBy());
        }
        return changed;
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

    /** Kept only as a display label; it never reaches the object key. */
    private static @Nullable String safeLabel(@Nullable String filename) {
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
