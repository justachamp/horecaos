package uz.horecaos.platform.support;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * An S3-compatible object store for tests, over RustFS rather than MinIO.
 *
 * <p>MinIO's own images are gone: {@code quay.io/minio/minio} and {@code
 * quay.io/minio/mc} answer 401 to an anonymous pull and Docker Hub's {@code
 * minio/minio} is 404, so a suite that still named either one could only run
 * from whatever was already cached on a given machine. RustFS is the owner's
 * 2026-09-25 replacement, and this class is the one place that names the
 * image — every caller that used to build its own {@code GenericContainer}
 * with a MinIO image, command and health check now asks this class for a
 * client instead.
 *
 * <p>Pinned by digest rather than by tag alone, the same discipline {@link
 * TestDatabase}'s {@code IMAGE} applies to Postgres: a tag can move under a
 * suite without anyone here deciding it should. Verified interactively
 * against a running container on 2026-09-25 — {@code /health} on port 9000
 * answers {@code 200} with {@code {"status":"ok",...}} within a few seconds
 * of start, which is the wait strategy this constructor configures.
 *
 * <p>Credentials are fixed, test-only values, set through {@code
 * RUSTFS_ACCESS_KEY}/{@code RUSTFS_SECRET_KEY} rather than left unset —
 * RustFS falls back to a built-in default credential when neither variable
 * nor their {@code _FILE} counterparts are present, and a test fixture is
 * exactly the place that fallback must never be exercised silently.
 *
 * <p>{@link #s3Client()} and {@link #s3Presigner()} are built with the same
 * two settings production's own configuration classes use — path-style
 * addressing and {@code us-east-1} (see {@code ObjectStorageConfiguration}
 * and {@code AuditArchiveStorageConfiguration}) — so a test exercises the
 * same client shape production does, not a client that merely happens to
 * work against this store.
 */
public final class ObjectStoreContainer extends GenericContainer<ObjectStoreContainer> {

    /**
     * The one place this suite names the RustFS image. GA 2026-09-16,
     * Apache-2.0; pinned by digest so a moved tag cannot change what a test
     * runs against out from under it.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff");

    private static final int S3_PORT = 9000;

    private static final String ACCESS_KEY = "horecaos-test";
    private static final String SECRET_KEY = "horecaos-test-secret";

    public ObjectStoreContainer() {
        super(IMAGE);
        withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY);
        withEnv("RUSTFS_SECRET_KEY", SECRET_KEY);
        withExposedPorts(S3_PORT);
        // Plain, unauthenticated /health -- not MinIO's /minio/health/live --
        // confirmed against a real container to answer 200 once the server is
        // actually ready to take S3 calls, not merely once the process exists.
        waitingFor(Wait.forHttp("/health").forPort(S3_PORT).forStatusCode(200));
    }

    /** The access key every caller of this container's clients authenticates with. */
    public String accessKey() {
        return ACCESS_KEY;
    }

    /** The secret key every caller of this container's clients authenticates with. */
    public String secretKey() {
        return SECRET_KEY;
    }

    /** The S3 API endpoint, once the container has started and its port is mapped. */
    public URI endpoint() {
        return URI.create("http://" + getHost() + ":" + getMappedPort(S3_PORT));
    }

    /**
     * An {@link S3Client} over this container, configured exactly as
     * production's own object-store configuration classes configure theirs:
     * path-style addressing, {@code us-east-1}. Callers own the returned
     * client and must close it.
     */
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(pathStyle())
                .build();
    }

    /**
     * An {@link S3Presigner} over this container, on the same settings as
     * {@link #s3Client()}. Callers own the returned presigner and must close
     * it.
     */
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(pathStyle())
                .build();
    }

    private StaticCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
    }

    private static S3Configuration pathStyle() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).build();
    }
}
