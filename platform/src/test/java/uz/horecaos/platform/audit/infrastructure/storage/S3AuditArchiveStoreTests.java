package uz.horecaos.platform.audit.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uz.horecaos.platform.audit.api.AuditArchivalNotVerifiedException;
import uz.horecaos.platform.audit.api.AuditArchiveStore;

/**
 * Whether {@link S3AuditArchiveStore} genuinely proves what ADR 0027 asks it to
 * prove, against a real S3-compatible store rather than an assumption about one.
 *
 * <p>Two buckets, on purpose. {@code LOCKED_BUCKET} is created with MinIO's
 * {@code --with-lock}, the local equivalent of an S3 bucket created with Object
 * Lock enabled — a setting no later API call can add, which is exactly why this
 * class exists rather than trusting the {@code objectLockMode} header on a PUT.
 * {@code UNLOCKED_BUCKET} has no such thing, and stands in for a provider whose
 * "S3-compatible" answer to Object Lock turns out to be no — the ADR 0073
 * question this record's own doc names as unconfirmed for production.
 */
class S3AuditArchiveStoreTests {

    private static final String LOCKED_BUCKET = "horecaos-audit-archive-test-locked";
    private static final String UNLOCKED_BUCKET = "horecaos-audit-archive-test-unlocked";

    private static GenericContainer<?> minio;
    private static S3Client client;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for these tests");

        minio = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z"))
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", "horecaos")
                .withEnv("MINIO_ROOT_PASSWORD", "horecaos-local-secret")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();

        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("horecaos", "horecaos-local-secret")))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        createBucket(LOCKED_BUCKET, true);
        createBucket(UNLOCKED_BUCKET, false);
    }

    @AfterAll
    static void stopInfrastructure() {
        if (client != null) {
            client.close();
        }
        if (minio != null) {
            minio.stop();
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

        // Measured against a real MinIO: a bucket with no Object Lock
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
