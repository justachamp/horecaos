package uz.horecaos.platform.media.api;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * The media module's port onto an object store (ADR 0010).
 *
 * <p>No provider SDK type crosses this interface. ADR 0034 starts on a local
 * provider and may move to AWS later, and the point of this boundary is that the
 * move is one adapter rather than a change to every caller.
 */
public interface ObjectStorage {

    /**
     * A short-lived URL the client uploads to directly.
     *
     * <p>Constrained by key, content type, and size, so a presigned URL cannot
     * be reused to write somewhere else or to upload something enormous. Bytes
     * never pass through the application, which is what keeps a large upload
     * from occupying a request thread.
     */
    PresignedUpload presignUpload(String bucket, String key, String contentType, long maxSizeBytes, Duration validFor);

    /**
     * A short-lived URL for reading a private object.
     *
     * <p>Public catalog media is served through a CDN rather than this, per
     * ADR 0010: a signed URL per image per page load is both slow and a way to
     * leak reads that outlive a session.
     */
    URI presignDownload(String bucket, String key, Duration validFor);

    /**
     * The store's own view of an object.
     *
     * <p>This is the trusted source at finalize. What the client claimed it
     * uploaded is a constraint on the presigned URL, not evidence of what
     * arrived.
     */
    Optional<StoredObject> head(String bucket, String key);

    /**
     * The leading bytes of an object, for reading an image's own header.
     *
     * <p>Ranged on purpose. The object may be ten megabytes and the header is in
     * the first hundred kilobytes of it, so a whole-object read would pull an
     * attacker-chosen payload into the application's heap to learn something the
     * prefix already answers.
     *
     * <p>Defaulted rather than abstract, and the default is empty rather than an
     * exception: a store that cannot serve a ranged read still satisfies this
     * port, and a caller that cannot inspect an object must refuse to publish it.
     * Failing closed on an empty result is the contract; treating empty as "fine,
     * probably" would undo the check.
     *
     * @return up to {@code maxBytes}, or empty if nothing could be read
     */
    default byte[] readPrefix(String bucket, String key, int maxBytes) {
        return new byte[0];
    }

    /**
     * Writes an object the platform produced itself, such as a derivative.
     *
     * <p>Not presigned, because nothing outside the platform generates these. A
     * presigned URL is a capability handed to a client; a derivative never leaves
     * our own process.
     *
     * <p>Defaulted to refuse rather than to no-op. A store that silently accepted
     * this and wrote nothing would leave a derivative row pointing at a key that
     * does not exist, which surfaces as a broken image long after the cause.
     */
    default void put(String bucket, String key, String contentType, byte[] content) {
        throw new UnsupportedOperationException("This object store cannot be written to directly");
    }

    void delete(String bucket, String key);

    /**
     * A presigned upload URL and what the client must send along with it.
     *
     * @param requiredHeaders headers the client must send for the signature to hold
     */
    record PresignedUpload(URI url, java.util.Map<String, String> requiredHeaders, java.time.Instant expiresAt) {}

    /**
     * The store's own view of an object, as returned by {@link #head}.
     *
     * @param checksumSha256 base64 SHA-256 if the store recorded one; empty when
     *                       the object was written without checksum support
     */
    record StoredObject(long sizeBytes, String contentType, Optional<String> checksumSha256, String eTag) {}
}
