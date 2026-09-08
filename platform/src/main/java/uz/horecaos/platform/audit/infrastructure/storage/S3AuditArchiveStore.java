package uz.horecaos.platform.audit.infrastructure.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uz.horecaos.platform.audit.api.AuditArchivalNotVerifiedException;
import uz.horecaos.platform.audit.api.AuditArchiveStore;

/**
 * S3-compatible protected storage for closed audit partitions (ADR 0027).
 *
 * <p><strong>What "protected" means here, and what it does not.</strong> Every
 * write requests an S3 Object Lock retention in {@code GOVERNANCE} mode, which
 * refuses an ordinary delete or overwrite of that object version until {@code
 * retainUntil} passes — including to the credential this adapter holds, which
 * carries no {@code s3:BypassGovernanceRetention} permission. That is what makes
 * this stronger than {@code infra/backup}'s bucket, which asks an operator to
 * configure Object Lock on the destination and trusts the configuration; this
 * adapter asks the store to confirm the lock on every single object, every
 * time, and refuses to consider an archive done if it cannot.
 *
 * <p><strong>What it does not prove: that the configured bucket is capable of
 * Object Lock at all.</strong> Object Lock is a bucket-creation-time setting no
 * later API call can retrofit. Locally, {@code compose.yaml} creates the archive
 * bucket with {@code mc mb --with-lock}, and this class's own tests run against
 * a real MinIO with that flag — so the mechanism is proven against a genuine
 * S3-compatible implementation. Whether the ADR 0073 production provider's
 * object storage supports Object Lock is unconfirmed, the same way SigV4
 * presigning was unconfirmed until that record's own probe against the real
 * endpoint. Until that probe runs, this class's own honesty is the safeguard:
 * {@link #archiveAndVerify} does not assume the lock took. It asks the store
 * afterwards, and a bucket that silently ignored the request — rather than
 * rejecting it outright — fails verification exactly as loudly as one that
 * never received the request at all.
 */
public class S3AuditArchiveStore implements AuditArchiveStore {

    private final S3Client client;
    private final String bucket;

    public S3AuditArchiveStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public ArchiveReceipt archiveAndVerify(String key, byte[] content, Instant retainUntil) {
        String sha256 = sha256Base64(content);

        // A bucket with no Object Lock configuration does not silently accept
        // and drop these two headers — measured against a real MinIO: it answers
        // the PUT itself with 400 InvalidRequest, "Bucket is missing
        // ObjectLockConfiguration". That is the loud failure this method wants,
        // and it is caught here rather than left to propagate as a bare SDK
        // exception, so every way this store can fail to protect an object comes
        // back as the one type a caller is asked to handle.
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("application/x-ndjson")
                            .objectLockMode(ObjectLockMode.GOVERNANCE)
                            .objectLockRetainUntilDate(retainUntil)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception cannotLock) {
            throw new AuditArchivalNotVerifiedException(
                    "%s/%s could not be written under a retention lock: %s"
                            .formatted(bucket, key, cannotLock.getMessage()),
                    cannotLock);
        }

        // Durability, proven rather than assumed: every byte read back and
        // hashed again, never a store-reported checksum. Flexible checksums are
        // not uniform across S3-compatible implementations — see
        // ObjectStorageConfiguration's own comment on the Ceph store that
        // answered 400 to the SDK's default checksum headers — so the one check
        // here that does not depend on that is downloading the object and
        // hashing it locally, the same discipline infra/backup/backup.sh uses
        // to verify its off-site copy.
        byte[] roundTrip = client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asByteArray();
        if (!sha256Base64(roundTrip).equals(sha256)) {
            throw new AuditArchivalNotVerifiedException(
                    "Archived object %s/%s did not read back byte-identical to what was written"
                            .formatted(bucket, key));
        }

        // Tamper-resistance, proven rather than assumed: ask the store what
        // retention it actually recorded, rather than trusting that the
        // objectLockMode/objectLockRetainUntilDate headers above were honoured.
        // A bucket with no Object Lock configuration can answer this call with
        // "no retention configured" rather than an error, which is exactly the
        // silent failure this method must not let past as "protected".
        ObjectLockRetention retention;
        try {
            retention = client.getObjectRetention(GetObjectRetentionRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build())
                    .retention();
        } catch (S3Exception noRetentionConfigured) {
            throw new AuditArchivalNotVerifiedException(
                    "%s/%s reports no retention lock — the bucket may not have Object Lock enabled"
                            .formatted(bucket, key),
                    noRetentionConfigured);
        }

        if (retention == null || retention.mode() == null || retention.retainUntilDate() == null) {
            throw new AuditArchivalNotVerifiedException(
                    "%s/%s has no retention recorded by the store; the requested lock was not honoured"
                            .formatted(bucket, key));
        }
        if (retention.retainUntilDate().isBefore(retainUntil)) {
            throw new AuditArchivalNotVerifiedException("%s/%s is locked only until %s, short of the requested %s"
                    .formatted(bucket, key, retention.retainUntilDate(), retainUntil));
        }

        return new ArchiveReceipt(bucket, key, content.length, sha256, retention.retainUntilDate());
    }

    private static String sha256Base64(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is a mandatory algorithm on every JDK implementation.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
