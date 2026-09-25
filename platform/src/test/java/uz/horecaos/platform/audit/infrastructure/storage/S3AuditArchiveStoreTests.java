package uz.horecaos.platform.audit.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uz.horecaos.platform.audit.api.AuditArchivalNotVerifiedException;
import uz.horecaos.platform.audit.api.AuditArchiveStore;
import uz.horecaos.platform.support.ObjectStoreContainer;

/**
 * Whether {@link S3AuditArchiveStore} genuinely proves what ADR 0027 asks it to
 * prove, against a real S3-compatible store rather than an assumption about one.
 *
 * <p>Two buckets, on purpose. {@code LOCKED_BUCKET} is created with {@code
 * objectLockEnabledForBucket(true)} — the API-level equivalent of MinIO's
 * {@code mc mb --with-lock}, and the setting RustFS honours the same way (see
 * {@link #rustFsGivesTheThreeGuaranteesTheAuditArchiveDependsOn()}) — a
 * setting no later API call can add, which is exactly why this class exists
 * rather than trusting the {@code objectLockMode} header on a PUT.
 * {@code UNLOCKED_BUCKET} has no such thing, and stands in for a provider whose
 * "S3-compatible" answer to Object Lock turns out to be no — the ADR 0073
 * question this record's own doc names as unconfirmed for production.
 */
class S3AuditArchiveStoreTests {

    private static final String LOCKED_BUCKET = "horecaos-audit-archive-test-locked";
    private static final String UNLOCKED_BUCKET = "horecaos-audit-archive-test-unlocked";

    private static ObjectStoreContainer objectStore;
    private static S3Client client;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for these tests");

        objectStore = new ObjectStoreContainer();
        objectStore.start();
        client = objectStore.s3Client();

        createBucket(LOCKED_BUCKET, true);
        createBucket(UNLOCKED_BUCKET, false);
    }

    @AfterAll
    static void stopInfrastructure() {
        if (client != null) {
            client.close();
        }
        if (objectStore != null) {
            objectStore.stop();
        }
    }

    @Test
    void archivesAndConfirmsTheRetentionLockAgainstAGenuinelyLockedBucket() {
        S3AuditArchiveStore store = new S3AuditArchiveStore(client, LOCKED_BUCKET);
        byte[] content = "{\"actionCode\":\"tenant.suspended\"}\n".getBytes(StandardCharsets.UTF_8);
        Instant retainUntil = Instant.parse("2036-01-01T00:00:00Z");

        AuditArchiveStore.ArchiveReceipt receipt =
                store.archiveAndVerify("audit-events/2026/audit_events_2026.ndjson", content, retainUntil);

        assertThat(receipt.bucket()).isEqualTo(LOCKED_BUCKET);
        assertThat(receipt.sizeBytes()).isEqualTo(content.length);
        assertThat(receipt.retainUntil())
                .as("the store's own confirmed lock date, not merely the one requested")
                .isAfterOrEqualTo(retainUntil);
    }

    @Test
    void aTamperAttemptOnTheLockedVersionIsRefusedByTheStoreItself() {
        S3AuditArchiveStore store = new S3AuditArchiveStore(client, LOCKED_BUCKET);
        String key = "audit-events/2027/audit_events_2027.ndjson";
        store.archiveAndVerify(
                key,
                "{\"actionCode\":\"tenant.created\"}\n".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2037-01-01T00:00:00Z"));

        // An unversioned DeleteObject on a versioned bucket does not destroy
        // anything — it only writes a new delete-marker version on top, which
        // would make this test pass whether or not Object Lock did anything at
        // all. The call Object Lock actually intercepts targets the specific
        // locked version by id, the same way a determined attempt to make
        // evidence disappear would have to.
        String versionId = client.headObject(
                        builder -> builder.bucket(LOCKED_BUCKET).key(key))
                .versionId();
        assertThat(versionId)
                .as("the bucket is Object-Lock-enabled, so every object is versioned")
                .isNotBlank();

        // The same credential this adapter uses for everything else, attempting
        // the one call ADR 0027 says the platform must not be able to perform on
        // an archived object's locked version. No bypass-governance header is
        // sent — this adapter never sends one — so a genuinely locked version
        // refuses.
        assertThatThrownBy(() -> client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(LOCKED_BUCKET)
                        .key(key)
                        .versionId(versionId)
                        .build()))
                .as("retention-protected means this call fails, not that nobody happened to try it")
                .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
    }

    @Test
    void refusesToReportAnArchiveVerifiedAgainstABucketWithNoObjectLock() {
        S3AuditArchiveStore store = new S3AuditArchiveStore(client, UNLOCKED_BUCKET);
        String key = "audit-events/2028/audit_events_2028.ndjson";

        assertThatThrownBy(() -> store.archiveAndVerify(
                        key,
                        "{\"actionCode\":\"tenant.created\"}\n".getBytes(StandardCharsets.UTF_8),
                        Instant.parse("2038-01-01T00:00:00Z")))
                .as("a bucket that cannot enforce the lock must fail loudly, per ADR 0027 — "
                        + "reporting it as archived would be exactly the false sense of protection "
                        + "the class's own doc warns against")
                .isInstanceOf(AuditArchivalNotVerifiedException.class);

        // Measured against a real RustFS: a bucket with no Object Lock
        // configuration refuses the objectLockMode/objectLockRetainUntilDate
        // headers on the PUT itself (400 InvalidRequest), so nothing is left
        // behind for a caller to mistake for an unverified-but-present archive.
        assertThat(client.listObjectsV2(
                                builder -> builder.bucket(UNLOCKED_BUCKET).prefix(key))
                        .contents())
                .as("the store refused before writing anything, not after")
                .isEmpty();
    }

    @Test
    void tamperingIsUndetectedOnAnUnprotectedBucketWhichIsPreciselyWhyVerificationMustNotTrustTheUpload() {
        // Not a test of the class under test — a control, proving the negative
        // this suite's other assertions depend on. Without Object Lock, the
        // platform's own ordinary delete succeeds, exactly the failure mode
        // archiveAndVerify exists to catch before a caller ever relies on it.
        String key = "audit-events/2029/audit_events_2029.ndjson";
        client.putObject(
                PutObjectRequest.builder().bucket(UNLOCKED_BUCKET).key(key).build(),
                software.amazon.awssdk.core.sync.RequestBody.fromString("{}"));

        client.deleteObject(
                DeleteObjectRequest.builder().bucket(UNLOCKED_BUCKET).key(key).build());

        assertThat(client.listObjectsV2(
                                builder -> builder.bucket(UNLOCKED_BUCKET).prefix(key))
                        .contents())
                .as("an unlocked bucket lets the same credential delete what it just wrote — "
                        + "the exact tampering ADR 0027 requires protected storage to refuse")
                .isEmpty();
    }

    /**
     * The three RustFS-level guarantees {@link S3AuditArchiveStore} is built on
     * top of, asserted directly against the S3 API rather than through the
     * class under test — the other tests in this suite prove {@code
     * S3AuditArchiveStore} uses these correctly; this one proves the store
     * itself actually offers them, which is what changed when MinIO's images
     * were withdrawn and this suite moved onto RustFS.
     */
    @Test
    void rustFsGivesTheThreeGuaranteesTheAuditArchiveDependsOn() {
        // 1. A bucket created with Object Lock enabled auto-enables versioning
        // (S3AuditArchiveStoreTests' own LOCKED_BUCKET, created in
        // startInfrastructure, never calls PutBucketVersioning itself).
        assertThat(client.getBucketVersioning(builder -> builder.bucket(LOCKED_BUCKET))
                        .statusAsString())
                .as("CreateBucket --object-lock-enabled-for-bucket must auto-enable versioning")
                .isEqualTo("Enabled");

        String key = "audit-events/2030/audit_events_2030.ndjson";
        Instant retainUntil = Instant.parse("2040-01-01T00:00:00Z");
        String versionId = client.putObject(
                        PutObjectRequest.builder()
                                .bucket(LOCKED_BUCKET)
                                .key(key)
                                .objectLockMode(ObjectLockMode.GOVERNANCE)
                                .objectLockRetainUntilDate(retainUntil)
                                .build(),
                        RequestBody.fromString("{\"actionCode\":\"tenant.created\"}\n"))
                .versionId();

        // 2. A PutObject with GOVERNANCE retention reads back the same retention.
        ObjectLockRetention retention = client.getObjectRetention(
                        builder -> builder.bucket(LOCKED_BUCKET).key(key))
                .retention();
        assertThat(retention.modeAsString()).isEqualTo("GOVERNANCE");
        assertThat(retention.retainUntilDate()).isEqualTo(retainUntil);

        // 3a. Deleting the locked version by id is refused.
        assertThatThrownBy(() -> client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(LOCKED_BUCKET)
                        .key(key)
                        .versionId(versionId)
                        .build()))
                .as("the specific locked version must be refused, by id, the way a determined "
                        + "attempt to remove evidence would have to address it")
                .isInstanceOf(S3Exception.class);

        // 3b. A plain DeleteObject (no version id) only adds a delete marker; the
        // locked version underneath is untouched and still carries its retention.
        client.deleteObject(
                DeleteObjectRequest.builder().bucket(LOCKED_BUCKET).key(key).build());
        assertThatThrownBy(() -> client.headObject(
                        builder -> builder.bucket(LOCKED_BUCKET).key(key)))
                .as("the latest version is now a delete marker, so an unversioned read sees nothing — "
                        + "which is different from the locked version having been destroyed")
                .isInstanceOf(S3Exception.class);
        ObjectLockRetention stillLocked = client.getObjectRetention(
                        builder -> builder.bucket(LOCKED_BUCKET).key(key).versionId(versionId))
                .retention();
        assertThat(stillLocked.retainUntilDate())
                .as("the plain delete only added a marker on top; the locked version itself "
                        + "is exactly where it was")
                .isEqualTo(retainUntil);
    }

    /**
     * {@code objectLockEnabledForBucket} is the whole mechanism under test: a
     * bucket created with it set locks every object written under a requested
     * retention; a bucket created without it accepts the same
     * {@code objectLockMode}/{@code objectLockRetainUntilDate} headers and
     * silently does nothing with them — which is exactly the gap {@link
     * S3AuditArchiveStore#archiveAndVerify} exists to catch rather than trust.
     */
    private static void createBucket(String bucket, boolean withLock) {
        try {
            client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucket)
                    .objectLockEnabledForBucket(withLock)
                    .build());
        } catch (BucketAlreadyOwnedByYouException alreadyThere) {
            // Reusing a bucket across runs is fine; each test keys on its own key.
        }
    }
}
