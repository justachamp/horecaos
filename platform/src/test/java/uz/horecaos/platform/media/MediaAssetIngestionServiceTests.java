package uz.horecaos.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.integration.outbox.JdbcOutboxStore;
import uz.horecaos.platform.integration.outbox.MediaOutboxEventListener;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetIngestion;
import uz.horecaos.platform.media.api.MediaAssetIngestion.IngestOutcome;
import uz.horecaos.platform.media.api.MediaAssetIngestion.OwnerScope;
import uz.horecaos.platform.media.api.MediaEvent;
import uz.horecaos.platform.media.application.MediaAssetIngestionService;
import uz.horecaos.platform.media.infrastructure.UrlImageFetcher;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcDerivativeJobStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;
import uz.horecaos.platform.media.infrastructure.storage.S3ObjectStorage;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link MediaAssetIngestionService} against a real object store and a real
 * in-process HTTP server (row 4.5b, ADR 0010) — the same "run against MinIO
 * rather than a stub" reasoning {@link MediaLifecycleTests} gives for the
 * presigned lifecycle, extended to the server-side fetch this class adds.
 *
 * <p>The property every {@code ingestFrom*} test here is actually about is
 * "never trust the caller's or the URL's word for the content type" — a body
 * that is not really an image is rejected by {@code ImageProbe} sniffing the
 * bytes themselves, even when the caller's filename hint, or the remote
 * server's own {@code Content-Type} response header, insists otherwise.
 */
class MediaAssetIngestionServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String BUCKET = "horecaos-media-ingest-test";
    private static final byte[] JPEG = encode(64, 48);
    private static final byte[] NOT_AN_IMAGE = "<html><script>evil()</script></html>".getBytes(StandardCharsets.UTF_8);

    private static TestDatabase.Handle db;
    private static GenericContainer<?> minio;
    private static S3Client s3;
    private static S3Presigner presigner;

    private JdbcClient jdbc;
    private MediaAssetIngestion ingestion;
    private HttpServer httpServer;
    private int httpPort;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for media ingestion tests");

        db = TestDatabase.migrated();

        minio = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z"))
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", "horecaos")
                .withEnv("MINIO_ROOT_PASSWORD", "horecaos-local-secret")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();

        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        var credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("horecaos", "horecaos-local-secret"));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException alreadyThere) {
            // Fine to reuse a bucket across runs; tests key on fresh ids.
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        if (s3 != null) {
            s3.close();
        }
        if (presigner != null) {
            presigner.close();
        }
        if (minio != null) {
            minio.stop();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'media-ingest-tenant', 'Media Ingest Test LLC', 'Media Ingest Test', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        S3ObjectStorage storage = new S3ObjectStorage(s3, presigner);
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T09:00:00Z"), ZoneOffset.UTC);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        MediaOutboxEventListener outbox = new MediaOutboxEventListener(
                new JdbcOutboxStore(jdbc), JsonMapper.builder().build(), "media.events");
        ApplicationEventPublisher events = event -> outbox.append((MediaEvent) event);
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());

        ingestion = new MediaAssetIngestionService(
                new JdbcMediaAssetStore(jdbc),
                new JdbcDerivativeJobStore(jdbc),
                storage,
                new UrlImageFetcher(),
                transactions,
                events,
                audit,
                clock,
                BUCKET);

        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/good.jpg", exchange -> respondWithDeclaredLength(exchange, 200, "image/jpeg", JPEG));
        httpServer.createContext(
                "/liar.jpg", exchange -> respondWithDeclaredLength(exchange, 200, "image/jpeg", NOT_AN_IMAGE));
        httpServer.createContext(
                "/oversize.jpg", exchange -> respondWithDeclaredLength(exchange, 200, "image/jpeg", oversizeBody()));
        httpServer.createContext(
                "/understated-length.jpg", exchange -> respondChunked(exchange, 200, "image/jpeg", oversizeBody()));
        httpServer.createContext(
                "/missing.jpg", exchange -> respondWithDeclaredLength(exchange, 404, "text/plain", new byte[0]));
        httpServer.start();
        httpPort = httpServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void ingestsAGenuineImageFromBytes() {
        IngestOutcome outcome =
                ingestion.ingestFromBytes(TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, JPEG, "dish.jpg", null);

        assertThat(outcome.accepted()).isTrue();
        MediaAssetId assetId = requireAssetId(outcome);
        assertThat(status(assetId)).isEqualTo("AVAILABLE");
    }

    @Test
    void refusesBytesThatAreNotReallyAnImageEvenWithAnImageFilename() {
        // The caller's own hint (a ".jpg" filename) says image; the bytes do not.
        // This is the property the whole class exists for: sniffing wins.
        IngestOutcome outcome = ingestion.ingestFromBytes(
                TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, NOT_AN_IMAGE, "totally-a-photo.jpg", null);

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode()).isEqualTo("CONTENT_NOT_AN_IMAGE");
        assertThat(outcome.assetId()).isNull();
    }

    @Test
    void refusesBytesOverTheSizeCap() {
        IngestOutcome outcome = ingestion.ingestFromBytes(
                TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, oversizeBody(), "huge.jpg", null);

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode()).isEqualTo("SIZE_EXCEEDED");
    }

    @Test
    void ingestsAGenuineImageFetchedFromAUrl() {
        IngestOutcome outcome =
                ingestion.ingestFromUrl(TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, uri("/good.jpg"), null);

        assertThat(outcome.accepted()).isTrue();
        assertThat(status(requireAssetId(outcome))).isEqualTo("AVAILABLE");
    }

    @Test
    void refusesAUrlWhoseServerClaimsImageJpegForABodyThatIsNot() {
        // The remote server's own Content-Type header says image/jpeg. This
        // must not be trusted either -- only the fetched bytes' own header
        // decides.
        IngestOutcome outcome =
                ingestion.ingestFromUrl(TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, uri("/liar.jpg"), null);

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode()).isEqualTo("CONTENT_NOT_AN_IMAGE");
    }

    @Test
    void refusesAUrlWhoseDeclaredLengthExceedsTheCap() {
        IngestOutcome outcome =
                ingestion.ingestFromUrl(TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, uri("/oversize.jpg"), null);

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode()).isEqualTo("FETCH_FAILED");
    }

    @Test
    void refusesAUrlWhoseServerUnderstatesItsContentLengthAndStreamsMoreAnyway() {
        // No Content-Length header at all this time (chunked transfer) -- the
        // streamed cap, not the declared-length pre-check, is what has to catch
        // this one.
        IngestOutcome outcome = ingestion.ingestFromUrl(
                TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, uri("/understated-length.jpg"), null);

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode()).isEqualTo("FETCH_FAILED");
    }

    @Test
    void refusesADisallowedSchemeWithoutEverMakingARequest() {
        IngestOutcome outcome = ingestion.ingestFromUrl(
                TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, URI.create("ftp://127.0.0.1/x.jpg"), null);

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode()).isEqualTo("URL_NOT_ALLOWED");
    }

    @Test
    void refusesA404FetchAsFetchFailedRatherThanThrowing() {
        IngestOutcome outcome =
                ingestion.ingestFromUrl(TENANT, OwnerScope.BRAND, UUID.randomUUID(), true, uri("/missing.jpg"), null);

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode()).isEqualTo("FETCH_FAILED");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + httpPort + path);
    }

    private static MediaAssetId requireAssetId(IngestOutcome outcome) {
        MediaAssetId assetId = outcome.assetId();
        if (assetId == null) {
            throw new AssertionError("Expected an accepted outcome to carry an asset id");
        }
        return assetId;
    }

    private String status(MediaAssetId assetId) {
        return jdbc.sql("SELECT status FROM media.assets WHERE asset_id = :id")
                .param("id", assetId.value())
                .query(String.class)
                .single();
    }

    /** One byte over {@code MediaAssetIngestionService}'s own ten-megabyte cap. */
    private static byte[] oversizeBody() {
        return new byte[10 * 1024 * 1024 + 1];
    }

    /** An honest {@code Content-Length}, or no body at all when {@code body} is empty. */
    private static void respondWithDeclaredLength(
            com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    /**
     * Chunked transfer encoding: no {@code Content-Length} header at all, so
     * the fetcher's declared-length pre-check has nothing to reject on and only
     * the streamed cap can catch an oversize body.
     */
    private static void respondChunked(
            com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, 0);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    private static byte[] encode(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", bytes);
        } catch (IOException impossible) {
            throw new IllegalStateException("Encoding an in-memory image cannot fail", impossible);
        }
        return bytes.toByteArray();
    }
}
