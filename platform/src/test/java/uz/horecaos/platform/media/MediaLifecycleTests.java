package uz.horecaos.platform.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javax.sql.DataSource;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.integration.outbox.JdbcOutboxStore;
import uz.horecaos.platform.integration.outbox.MediaOutboxEventListener;
import uz.horecaos.platform.media.api.ImageDerivativeRenderer;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.api.MediaEvent;
import uz.horecaos.platform.media.application.MediaAssetService;
import uz.horecaos.platform.media.application.MediaDerivativeService;
import uz.horecaos.platform.media.application.MediaDerivativeStore;
import uz.horecaos.platform.media.application.MediaDerivativeWorker;
import uz.horecaos.platform.media.domain.DerivativeVariant;
import uz.horecaos.platform.media.domain.ImageProbe;
import uz.horecaos.platform.media.domain.MediaDerivative;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;
import uz.horecaos.platform.media.domain.ProbedImage;
import uz.horecaos.platform.media.infrastructure.imaging.ImageIoDerivativeRenderer;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcDerivativeJobStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaDerivativeStore;
import uz.horecaos.platform.media.infrastructure.storage.S3ObjectStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The presigned upload lifecycle against a real S3-compatible store (ADR 0010).
 *
 * <p>Run against MinIO rather than a stub, because the behaviour under test is
 * whether a signature actually constrains an upload — and a stub would simply
 * agree that it does.
 */
class MediaLifecycleTests {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String BUCKET = "horecaos-media-test";

    /**
     * Real encoder output, because finalize now reads the image's own header.
     * A string of the right length used to pass every check in this suite, which
     * was precisely the hole: nothing looked inside the bytes.
     */
    private static final byte[] JPEG = encode("jpg", 640, 480);
    private static final byte[] PNG = encode("png", 320, 240);
    private static final byte[] HTML =
            "<html><script>fetch('https://evil.example')</script></html>".getBytes(StandardCharsets.UTF_8);

    /**
     * A real, valid PNG of about 300KB that decodes to about 300MB.
     *
     * <p>Not a hand-built header. Signature, IHDR, a deflated IDAT and IEND, so
     * every gate in the upload path sees a genuine file: under the ten-megabyte
     * size limit, both sides under the twelve-thousand-pixel dimension limit,
     * exactly forty megapixels — on, and so under, a strict pixel ceiling —
     * {@code image/png} on the allow-list, and the probed type equal to the
     * stored type. What made it a bomb is the two bytes after the dimensions:
     * sixteen bits a sample and four channels, so a pixel is eight bytes rather
     * than one.
     *
     * <p>Built by deflating zeros rather than by encoding a raster, because
     * encoding one would mean holding 305MB in this JVM to produce the fixture
     * that exists to prove nothing should ever hold it.
     */
    private static final byte[] RASTER_BOMB = pngOfZeros(8000, 5000, 16, 6, 4);

    /** Where every test's clock starts, so an advance is visible as an advance. */
    private static final Instant START = Instant.parse("2026-08-21T09:00:00Z");

    private static TestDatabase.Handle db;
    private static GenericContainer<?> minio;
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private static S3Client s3;
    private static S3Presigner presigner;

    private MediaAssetService media;
    private MediaDerivativeService derivatives;
    private MediaDerivativeWorker worker;
    private JdbcDerivativeJobStore jobs;
    private MediaDerivativeStore derivativeRows;
    private JdbcClient jdbc;
    private MovableClock clock;
    private S3ObjectStorage storage;
    private SimpleMeterRegistry meters;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the media lifecycle tests");

        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();

        minio = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-07-23T15-54-02Z"))
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", "horecaos")
                .withEnv("MINIO_ROOT_PASSWORD", "horecaos-local-secret")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();

        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("horecaos", "horecaos-local-secret"));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(credentials).serviceConfiguration(pathStyle).build();
        presigner = S3Presigner.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(credentials).serviceConfiguration(pathStyle).build();

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException alreadyThere) {
            // Reusing a bucket across runs is fine; the tests key on fresh ids.
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
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        // CASCADE, because media assets are referenced from outside this module and
        // the set grows: ADR 0042's courier engagement points at one for its
        // registration evidence. A bare TRUNCATE fails the moment anything takes a
        // foreign key to this table, which is a fixture problem rather than a
        // reason for the reference not to exist.
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        // Real tenancy rows, because integration.outbox_events has a foreign key
        // to tenant.tenants — an availability fact for a tenant that does not
        // exist is refused by the database, which is the constraint doing its
        // job rather than a fixture inconvenience. The CASCADE clears the outbox
        // with them.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant(TENANT_A, "media-tenant-a");
        insertTenant(TENANT_B, "media-tenant-b");

        // Movable, because the worker under test is defined by durations. Its
        // lease is five minutes, its backoff thirty seconds doubling to fifteen,
        // and the claim query wants due_at <= now and reclaims on leased_until
        // <= now. On a clock that cannot move, the dead-worker reclaim, the
        // retry after a reschedule and the attempt-limit abandonment are not
        // merely untested — they are unreachable, and every media test in this
        // file used to assert single-pass behaviour only.
        clock = new MovableClock(START);
        storage = new S3ObjectStorage(s3, presigner);
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        // Stands in for the @TransactionalEventListener(BEFORE_COMMIT) Spring
        // wires in production. finalize publishes from inside its own
        // transaction, so calling the real listener straight through appends the
        // outbox row on the same connection and before the same commit — which
        // is the property these tests are about. A stub publisher that recorded
        // the event would prove the event was constructed and nothing about
        // where its row lands.
        MediaOutboxEventListener outbox = new MediaOutboxEventListener(
                new JdbcOutboxStore(jdbc), JsonMapper.builder().build(), "media.events");
        ApplicationEventPublisher events = event -> outbox.append((MediaEvent) event);

        jobs = new JdbcDerivativeJobStore(jdbc);
        media = new MediaAssetService(new JdbcMediaAssetStore(jdbc), jobs, storage,
                transactions, events, clock, BUCKET);

        // Everything here is real: real originals, a real renderer, real objects
        // written to and read back from MinIO, and — since V0058 landed the table
        // — real rows in media.derivatives rather than the in-memory stand-in
        // this suite used while that table was still only proposed.
        derivativeRows = new JdbcMediaDerivativeStore(jdbc);
        meters = new SimpleMeterRegistry();
        derivatives = new MediaDerivativeService(new JdbcMediaAssetStore(jdbc), derivativeRows,
                storage, new ImageIoDerivativeRenderer(), clock);
        worker = workerOver(derivatives);
    }

    @Test
    @DisplayName("a complete upload becomes available and yields a working download URL")
    void completeUploadBecomesAvailable() throws Exception {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/jpeg", JPEG.length, "burger.jpg", null);

        int uploadStatus = put(ticket.uploadUrl(), ticket.requiredHeaders(), JPEG);
        assertThat(uploadStatus).isEqualTo(200);

        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.AVAILABLE);

        URI download = media.downloadUrl(TENANT_A, ticket.assetId()).orElseThrow();
        assertThat(get(download)).isEqualTo(JPEG);

        // Dimensions come from the image's own header at finalize. A storefront
        // that knows them can reserve the right box before the bytes arrive,
        // which is the difference between a menu that loads and one that jumps.
        assertThat(dimensions(ticket.assetId())).containsExactly(640, 480);
    }

    @Test
    @DisplayName("a document uploaded as an image is rejected on its content, not its label")
    void contentThatIsNotAnImageIsRejected() throws Exception {
        // Every earlier check passes: the type is allowed, the size matches, and
        // the store echoes back image/jpeg because that is the header the
        // signature required. Only reading the bytes catches it — and serving
        // this from our own origin would be stored cross-site scripting.
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/jpeg", HTML.length, "burger.jpg", null);

        assertThat(put(ticket.uploadUrl(), ticket.requiredHeaders(), HTML)).isEqualTo(200);

        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.REJECTED);
        assertThat(rejectionCode(ticket.assetId())).isEqualTo("CONTENT_NOT_AN_IMAGE");
        assertThat(media.downloadUrl(TENANT_A, ticket.assetId())).isEmpty();
    }

    @Test
    @DisplayName("a real image of the wrong format is rejected rather than quietly relabelled")
    void contentOfADifferentImageFormatIsRejected() throws Exception {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/jpeg", PNG.length, "burger.jpg", null);

        assertThat(put(ticket.uploadUrl(), ticket.requiredHeaders(), PNG)).isEqualTo(200);

        // The bytes are a perfectly good PNG. It is still not the upload that was
        // authorised, and the stored object's own metadata will keep telling every
        // future reader it is a JPEG.
        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.REJECTED);
        assertThat(rejectionCode(ticket.assetId())).isEqualTo("TYPE_MISMATCH");
    }

    @Test
    @DisplayName("a PNG uploaded as a PNG becomes available with its dimensions recorded")
    void pngIsAcceptedOnItsOwnTerms() throws Exception {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/png", PNG.length, "logo.png", null);

        assertThat(put(ticket.uploadUrl(), ticket.requiredHeaders(), PNG)).isEqualTo(200);

        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.AVAILABLE);
        assertThat(dimensions(ticket.assetId())).containsExactly(320, 240);
    }

    @Test
    @DisplayName("finalizing without uploading is rejected, not left pending")
    void finalizeWithoutUploadIsRejected() {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/png", JPEG.length, "never-sent.png", null);

        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.REJECTED);
        assertThat(rejectionCode(ticket.assetId())).isEqualTo("OBJECT_MISSING");
    }

    @Test
    @DisplayName("the store itself refuses an upload of a different size than declared")
    void sizeMismatchIsRefusedByTheSignature() throws Exception {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/jpeg", JPEG.length, "short.jpg", null);

        int uploadStatus = put(ticket.uploadUrl(), ticket.requiredHeaders(),
                "tiny".getBytes(StandardCharsets.UTF_8));

        // The content length is part of the signature, so the store rejects the
        // request outright and writes nothing. This is what makes a presigned
        // URL a bounded capability rather than a write-anything token: a leaked
        // URL cannot be used to upload a gigabyte.
        assertThat(uploadStatus).isEqualTo(403);
        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.REJECTED);
        assertThat(rejectionCode(ticket.assetId())).isEqualTo("OBJECT_MISSING");
    }

    @Test
    @DisplayName("a mismatched content type is refused by the signature too")
    void contentTypeMismatchIsRefusedByTheSignature() throws Exception {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/jpeg", JPEG.length, "mislabelled.jpg", null);

        // Same bytes, different declared type. The service's own type check at
        // finalize stays as defence in depth against a store that does not
        // enforce the signed headers, but the store is the first line.
        int uploadStatus = put(ticket.uploadUrl(), Map.of("Content-Type", "text/html"), JPEG);

        assertThat(uploadStatus).isEqualTo(403);
    }

    @Test
    @DisplayName("one tenant cannot finalize, read, or sign another tenant's asset")
    void assetsAreTenantIsolated() throws Exception {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PRIVATE,
                "image/jpeg", JPEG.length, "private.jpg", null);
        put(ticket.uploadUrl(), ticket.requiredHeaders(), JPEG);
        media.finalizeUpload(TENANT_A, ticket.assetId());

        // Tenant B holds a valid asset id and still gets nothing. The isolation
        // is in the query predicate, so there is no path that loads the row.
        assertThat(media.find(TENANT_B, ticket.assetId())).isEmpty();
        assertThat(media.downloadUrl(TENANT_B, ticket.assetId())).isEmpty();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> media.finalizeUpload(TENANT_B, ticket.assetId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("finalize is idempotent, so a retried call cannot un-publish an image")
    void finalizeIsIdempotent() throws Exception {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/jpeg", JPEG.length, "repeat.jpg", null);
        put(ticket.uploadUrl(), ticket.requiredHeaders(), JPEG);

        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId())).isEqualTo(MediaAssetStatus.AVAILABLE);
        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId())).isEqualTo(MediaAssetStatus.AVAILABLE);
    }

    @Test
    @DisplayName("a disallowed media type is refused before any URL is issued")
    void disallowedTypeNeverGetsAnUploadUrl() {
        // SVG is the one that matters: it is a document format that can carry
        // script, so serving user-supplied SVG from our origin is stored XSS.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                        "image/svg+xml", 1024, "logo.svg", null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM media.assets").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("an unverified asset yields no download URL")
    void pendingAssetIsNotServable() {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/jpeg", JPEG.length, "pending.jpg", null);

        assertThat(media.downloadUrl(TENANT_A, ticket.assetId())).isEmpty();
        assertThat(media.allDisplayable(TENANT_A, Set.of(ticket.assetId()))).isFalse();
    }

    @Test
    @DisplayName("a verified photograph gets every rendition, written to the store as real JPEG")
    void derivativesAreRenderedForAVerifiedAsset() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);

        var report = derivatives.renderMissing(TENANT_A, assetId);

        assertThat(report.created()).containsExactly(
                DerivativeVariant.THUMBNAIL, DerivativeVariant.CARD, DerivativeVariant.DETAIL);
        assertThat(report.sourceUnsupported()).isFalse();

        // Read back from MinIO and probed, not trusted from the row. The row
        // saying 200 pixels wide and the object being a text file is exactly the
        // failure the whole module is built to make impossible. The trailing 3
        // is the decoded cost of a pixel, which for a three-component 8-bit
        // JPEG is three bytes — read from the frame header like everything else.
        assertThat(storedDerivative(assetId, DerivativeVariant.THUMBNAIL))
                .isEqualTo(new ProbedImage("image/jpeg", 200, 150, 3));
        assertThat(storedDerivative(assetId, DerivativeVariant.CARD))
                .isEqualTo(new ProbedImage("image/jpeg", 400, 300, 3));
        assertThat(storedDerivative(assetId, DerivativeVariant.DETAIL))
                .isEqualTo(new ProbedImage("image/jpeg", 640, 480, 3));
    }

    @Test
    @DisplayName("a redelivered render does no work and creates no second row")
    void renderingIsIdempotent() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);
        derivatives.renderMissing(TENANT_A, assetId);

        // At-least-once delivery means this call is normal, not exceptional. Two
        // rows for one rendition would make "which thumbnail is the thumbnail"
        // unanswerable.
        var again = derivatives.renderMissing(TENANT_A, assetId);

        assertThat(again.created()).isEmpty();
        assertThat(again.alreadyPresent()).hasSize(DerivativeVariant.values().length);
        assertThat(derivatives.findAll(TENANT_A, assetId)).hasSize(DerivativeVariant.values().length);
    }

    @Test
    @DisplayName("a small original is re-encoded at its own size rather than enlarged")
    void derivativesAreNeverUpscaled() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/png", PNG);

        derivatives.renderMissing(TENANT_A, assetId);

        // Enlarging a 320-pixel logo to 800 produces a bigger file that looks
        // worse. The variant is a bound, not a promise about exact width.
        assertThat(storedDerivative(assetId, DerivativeVariant.DETAIL))
                .isEqualTo(new ProbedImage("image/jpeg", 320, 240, 3));
    }

    @Test
    @DisplayName("an unverified asset gets no renditions and no orphaned objects")
    void unverifiedAssetsAreNotRendered() {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/jpeg", JPEG.length, "pending.jpg", null);

        var report = derivatives.renderMissing(TENANT_A, ticket.assetId());

        assertThat(report.created()).isEmpty();
        assertThat(derivativeCount()).isZero();
    }

    @Test
    @DisplayName("one tenant cannot render another tenant's asset")
    void renderingIsTenantIsolated() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> derivatives.renderMissing(TENANT_B, assetId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(derivativeCount()).isZero();
    }

    // ------------------------------------------------------------------
    // The pipeline: what a finalize owes, and who pays it (ADR 0010, V0065)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("finalizing owes the renditions and announces the asset, in one transaction")
    void finalizeOwesDerivativesAndAnnouncesTheAsset() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);

        // The job is what makes the render survive a broker outage: the asset is
        // displayable and this row says its renditions are still owed.
        assertThat(jobStatus(assetId)).isEqualTo("PENDING");

        // And the fact reaches the rest of the platform the only way ADR 0004
        // allows — a row in the same transaction, for the relay to publish.
        // Nothing here publishes to Kafka.
        var event = jdbc.sql("""
                        SELECT event_type, topic, partition_key, tenant_id, status,
                               payload::text AS payload
                          FROM integration.outbox_events
                        """)
                .query((row, number) -> List.of(
                        row.getString("event_type"), row.getString("topic"),
                        row.getString("partition_key"), row.getString("tenant_id"),
                        row.getString("status"), row.getString("payload")))
                .single();

        assertThat(event.get(0)).isEqualTo("MediaAssetAvailable");
        assertThat(event.get(1)).isEqualTo("media.events");
        assertThat(event.get(2)).isEqualTo(assetId.value().toString());
        assertThat(event.get(3)).isEqualTo(TENANT_A.toString());
        assertThat(event.get(4)).isEqualTo("PENDING");

        // A topic is read by consumers with no authorization to the bytes, so an
        // object key or a signed URL on it is a read capability handed to all of
        // them, and the filename is text a customer typed (ADR 0029).
        assertThat(event.get(5))
                .contains("\"widthPx\": 640")
                .doesNotContain("objectKey")
                .doesNotContain("originalFilename")
                .doesNotContain(BUCKET)
                .doesNotContain(objectKey(assetId));
    }

    @Test
    @DisplayName("a finalized upload gets its derivatives through the real path and nothing else")
    void theWorkerRendersWhatFinalizeOwed() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);

        // No direct call to MediaDerivativeService anywhere in this test. One
        // upload, one finalize, one poll — which is exactly what a running
        // platform does, and what nothing did before V0065.
        assertThat(worker.renderOnce()).isEqualTo(1);

        assertThat(derivatives.findAll(TENANT_A, assetId))
                .extracting(MediaDerivative::variant)
                .containsExactly(DerivativeVariant.THUMBNAIL, DerivativeVariant.CARD,
                        DerivativeVariant.DETAIL);
        assertThat(storedDerivative(assetId, DerivativeVariant.CARD))
                .isEqualTo(new ProbedImage("image/jpeg", 400, 300, 3));
        assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a replayed trigger produces exactly one set of derivatives")
    void aReplayedTriggerRendersOneSet() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);
        assertThat(worker.renderOnce()).isEqualTo(1);

        var firstKeys = derivatives.findAll(TENANT_A, assetId).stream()
                .map(MediaDerivative::objectKey).toList();

        // At-least-once is the ordinary case, not the exceptional one: a
        // redelivered availability event, an operator re-queueing a batch, a
        // worker that died after writing objects and before completing its job.
        jobs.enqueue(UUID.randomUUID(), TENANT_A, assetId, clock.instant());
        assertThat(worker.renderOnce()).isEqualTo(1);

        // One row per rendition, at the same immutable keys. Two rows claiming to
        // be the thumbnail would make "which one is the thumbnail" unanswerable,
        // and a second key would leave the first object referenced by nothing.
        assertThat(derivatives.findAll(TENANT_A, assetId))
                .extracting(MediaDerivative::objectKey)
                .containsExactlyElementsOf(firstKeys);
        assertThat(derivativeCount()).isEqualTo(DerivativeVariant.values().length);
    }

    @Test
    @DisplayName("a malformed original fails its own job without taking the worker down")
    void aMalformedOriginalDoesNotStopTheBatch() throws Exception {
        // A valid PNG signature and IHDR — so finalize's header probe accepts it,
        // as it must: the probe reads a header and this file has a real one — over
        // a body of noise. The decoder discovers that only by trying, which is
        // the whole reason the decode is not on the request thread.
        byte[] malformed = java.util.Arrays.copyOf(PNG, PNG.length);
        java.util.Arrays.fill(malformed, 33, malformed.length, (byte) 0x7F);

        MediaAssetId hostile = anAvailableAsset("image/png", malformed);
        MediaAssetId healthy = anAvailableAsset("image/jpeg", JPEG);

        assertThat(worker.renderOnce()).isEqualTo(2);

        // The malformed one produced nothing and is finished rather than retried:
        // the next delivery would find the same bytes. Crucially the other asset
        // in the same batch still got its renditions — one hostile upload must not
        // cost every other tenant their thumbnails.
        assertThat(derivatives.findAll(TENANT_A, hostile)).isEmpty();
        assertThat(jobStatus(hostile)).isEqualTo("COMPLETED");
        assertThat(derivatives.findAll(TENANT_A, healthy)).hasSize(3);
    }

    @Test
    @DisplayName("an asset deleted between the fact and the render is abandoned, not retried")
    void anAssetDeletedBeforeItsRenderIsAbandoned() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);

        // The availability fact and a deletion request cross. Rendering a
        // rendition of something nobody may see produces an orphaned object and
        // nothing else.
        jdbc.sql("UPDATE media.assets SET status = 'DELETED' WHERE asset_id = :id")
                .param("id", assetId.value()).update();

        assertThat(worker.renderOnce()).isEqualTo(1);

        // ABANDONED and not deleted: the row is the only record that rendering
        // was ever owed, and an operator asking why a dish has no thumbnail needs
        // the attempt count and the code rather than an absence.
        assertThat(jobStatus(assetId)).isEqualTo("ABANDONED");
        assertThat(jobErrorCode(assetId)).isEqualTo("ASSET_NOT_RENDERABLE");
        assertThat(derivativeCount()).isZero();
    }

    @Test
    @DisplayName("two outstanding jobs for one asset cannot exist")
    void oneOutstandingJobPerAsset() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);

        // Enqueueing again while one is outstanding is refused by the partial
        // unique index, not by a read-then-write that two workers could both pass.
        assertThat(jobs.enqueue(UUID.randomUUID(), TENANT_A, assetId, clock.instant()))
                .isFalse();

        assertThat(jdbc.sql("SELECT count(*) FROM media.derivative_jobs WHERE asset_id = :id")
                .param("id", assetId.value()).query(Long.class).single())
                .isEqualTo(1);
    }


    // ------------------------------------------------------------------
    // What a render is allowed to cost, and what happens when it costs more
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a 300KB PNG that decodes to 300MB is refused, though it is under every pixel limit")
    void aRasterBombIsRefusedOnItsDecodedCost() throws Exception {
        // Every gate before this one passes, which is the point: 311KB against a
        // ten-megabyte limit, 8000x5000 against a twelve-thousand-pixel
        // dimension limit, forty megapixels against a strict forty-megapixel
        // ceiling, image/png on the allow-list, and the header's own type equal
        // to the stored type. The file is a real PNG and MinIO stores it.
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                "image/png", RASTER_BOMB.length, "wallpaper.png", null);
        assertThat(put(ticket.uploadUrl(), ticket.requiredHeaders(), RASTER_BOMB)).isEqualTo(200);

        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.REJECTED);
        assertThat(rejectionCode(ticket.assetId())).isEqualTo("DIMENSIONS_EXCEEDED");
        // The reason names the quantity the verdict is actually about. 305MB,
        // from two bytes of IHDR that nothing used to read.
        assertThat(rejectionDetail(ticket.assetId()))
                .contains("8 byte(s) per decoded pixel")
                .contains("320000000 bytes to decode");

        // And nothing was owed, so no worker ever meets those bytes.
        assertThat(jdbc.sql("SELECT count(*) FROM media.derivative_jobs")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("the same dimensions at 8-bit RGB are a photograph and still render")
    void theSameDimensionsAtEightBitsAreStillAccepted() throws Exception {
        // The limit did not become "no large photographs". 8000x5000 at three
        // bytes a pixel is 120MB, which is what the pixel ceiling was always
        // described as permitting, and it is permitted.
        byte[] photograph = pngOfZeros(8000, 5000, 8, 2, 3);

        MediaAssetId assetId = anAvailableAsset("image/png", photograph);

        assertThat(dimensions(assetId)).containsExactly(8000, 5000);
    }

    @Test
    @DisplayName("a bomb stored before the gate existed is settled by the renderer, not decoded")
    void aBombThatPredatesTheGateIsSettledRatherThanDecoded() throws Exception {
        // The case the renderer's own comment worries about: a re-render sweep,
        // or any caller, reaching a row written before finalize checked decoded
        // cost. The renderer restates the limit rather than trusting the gate,
        // so the 305MB decode never happens here either.
        MediaAssetId assetId = anAssetTheGateWouldNowRefuse("image/png", RASTER_BOMB);

        assertThat(worker.renderOnce()).isEqualTo(1);

        // Settled, not failed: the header will say the same thing forever, so
        // there is nothing for a retry to discover. COMPLETED with no
        // derivatives is the honest record of that, and the storefront falls
        // back to the original.
        assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
        assertThat(derivativeCount()).isZero();
        assertThat(outcomeCount("rendered")).isEqualTo(1);
        assertThat(outcomeCount("abandoned")).isZero();
    }

    @Test
    @DisplayName("a render that runs out of memory is abandoned with its error, never completed")
    void aRenderThatRanOutOfMemoryIsAbandonedWithItsErrorRecorded() throws Exception {
        // The defect, end to end. A genuinely hostile original, and a renderer
        // that answers it the way JPEGImageReader answers a raster it cannot
        // allocate — by letting the OutOfMemoryError out as an Error rather than
        // wrapping it. Reproduced at -Xmx256m: PNG arrives as
        // IIOException(cause=OutOfMemoryError) and was recorded as a completed
        // job; JPEG arrives as a bare Error, which no catch in the worker saw,
        // so the job stayed LEASED and was re-claimed on every lease expiry for
        // as long as the row existed.
        //
        // Thrown by a stand-in rather than provoked for real, because a real
        // OutOfMemoryError requires deciding this JVM's heap, and a test that
        // only passes under one -Xmx proves nothing about this behaviour.
        MediaAssetId assetId = anAssetTheGateWouldNowRefuse("image/png", RASTER_BOMB);
        MediaDerivativeWorker starved = workerOver(serviceOver(new ExhaustsMemory()));

        // Three attempts, which is this fixture's max-attempts, each separated
        // by a backoff the clock has to cross.
        assertThat(starved.renderOnce()).isEqualTo(1);
        assertThat(jobStatus(assetId)).isEqualTo("PENDING");
        assertThat(jobErrorCode(assetId)).isEqualTo("RENDER_OUT_OF_MEMORY");

        clock.advance(Duration.ofMinutes(16));
        assertThat(starved.renderOnce()).isEqualTo(1);
        assertThat(jobStatus(assetId)).isEqualTo("PENDING");

        clock.advance(Duration.ofMinutes(16));
        assertThat(starved.renderOnce()).isEqualTo(1);

        // Abandoned, with the code and the time recorded. Not COMPLETED with its
        // error columns nulled and the rendered counter incremented, which is
        // what the original code did, and not leased forever, which is what it
        // did for a JPEG.
        assertThat(jobStatus(assetId)).isEqualTo("ABANDONED");
        assertThat(jobErrorCode(assetId)).isEqualTo("RENDER_OUT_OF_MEMORY");
        assertThat(jobErroredAt(assetId)).isNotNull();
        assertThat(attemptCount(assetId)).isEqualTo(3);
        assertThat(derivativeCount()).isZero();
        assertThat(outcomeCount("rendered")).isZero();
        assertThat(outcomeCount("abandoned")).isEqualTo(1);

        // And it stays abandoned. The claim only looks at PENDING and LEASED, so
        // nothing picks this up again however long the platform runs.
        clock.advance(Duration.ofHours(24));
        assertThat(starved.renderOnce()).isZero();
    }

    @Test
    @DisplayName("a job whose worker died is reclaimed once its lease expires, not before")
    void aDeadWorkersJobIsReclaimedWhenItsLeaseExpires() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);

        // A worker claims the job and dies mid-render: nothing completes it,
        // reschedules it or abandons it, and the row keeps a live lease.
        jobs.claim(clock.instant(), Duration.ofMinutes(5), 4, "worker-that-died");
        assertThat(jobStatus(assetId)).isEqualTo("LEASED");

        // While the lease holds, nobody else may touch it. This half of the
        // claim predicate had no test at all, because a fixed clock can never
        // leave the window in which it is true.
        assertThat(worker.renderOnce()).isZero();

        clock.advance(Duration.ofMinutes(6));

        assertThat(worker.renderOnce()).isEqualTo(1);
        assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
        assertThat(derivatives.findAll(TENANT_A, assetId)).hasSize(3);
        // Two claims, two attempts. The count is what tells an operator the
        // first worker died rather than that the asset is difficult.
        assertThat(attemptCount(assetId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a failed render waits out its backoff and then succeeds")
    void aFailedRenderIsRetriedAfterItsBackoff() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);
        // Fails twice — an encode failure is the transient one: a temp-file or
        // object-store fault on the way out, not a property of the bytes — then
        // renders for real.
        MediaDerivativeWorker flaky = workerOver(serviceOver(
                new FailsThenRenders(2, new ImageDerivativeRenderer.Failed("ENCODE_FAILED"))));

        assertThat(flaky.renderOnce()).isEqualTo(1);
        assertThat(jobStatus(assetId)).isEqualTo("PENDING");
        assertThat(jobErrorCode(assetId)).isEqualTo("ENCODE_FAILED");
        assertThat(derivativeCount()).isZero();

        // Due in the future, so an immediate poll claims nothing. The backoff is
        // only a backoff if something enforces it, and nothing could observe
        // that on a clock that cannot pass due_at.
        assertThat(flaky.renderOnce()).isZero();

        clock.advance(Duration.ofMinutes(16));
        assertThat(flaky.renderOnce()).isEqualTo(1);
        assertThat(jobStatus(assetId)).isEqualTo("PENDING");

        clock.advance(Duration.ofMinutes(16));
        assertThat(flaky.renderOnce()).isEqualTo(1);

        // Completed, and the error columns cleared because this time there is no
        // error — which is the one case in which nulling them is right.
        assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
        assertThat(jobErrorCode(assetId)).isNull();
        assertThat(derivatives.findAll(TENANT_A, assetId)).hasSize(3);
        assertThat(outcomeCount("retried")).isEqualTo(2);
        assertThat(outcomeCount("rendered")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unsettled job cannot be re-claimed past its attempt budget")
    void anUnsettledJobStillSpendsItsBudget() throws Exception {
        MediaAssetId assetId = anAvailableAsset("image/jpeg", JPEG);

        // The state a tick that died on an Error used to leave behind: leased,
        // unsettled, and with an attempt count already past the limit. Nothing
        // consulted maximumAttempts on that path — it lives inside
        // retryOrAbandon, reachable only from a caught RuntimeException — so the
        // row was re-claimed on every lease expiry forever.
        jdbc.sql("""
                UPDATE media.derivative_jobs
                   SET status = 'LEASED', attempt_count = 9,
                       lease_token = :token, leased_until = :until, leased_by = 'worker-that-died'
                 WHERE asset_id = :id
                """)
                .param("token", UUID.randomUUID())
                .param("until", java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", assetId.value())
                .update();

        clock.advance(Duration.ofMinutes(6));
        assertThat(worker.renderOnce()).isEqualTo(1);

        assertThat(jobStatus(assetId)).isEqualTo("ABANDONED");
        assertThat(jobErrorCode(assetId)).isEqualTo("ATTEMPTS_EXHAUSTED");

        clock.advance(Duration.ofHours(24));
        assertThat(worker.renderOnce()).isZero();
    }

    @Test
    @DisplayName("a batch interrupted by a process-fatal Error hands back the jobs it never reached")
    void anInterruptedBatchDoesNotStrandItsSiblings() throws Exception {
        // The second half of the same defect. A tick meeting an Error the
        // process is not meant to survive rethrows, which used to abandon the
        // rest of the batch where it stood: three jobs LEASED, each with an
        // attempt already spent on work nobody did, each waiting out a
        // five-minute lease before anybody could look at them again. Six rounds
        // of that and the worker's own budget guard abandoned them as
        // ATTEMPTS_EXHAUSTED without one of them ever having been rendered.
        //
        // Metaspace rather than heap space, because that is the classification
        // that makes the tick rethrow at all: a heap allocation failure is this
        // asset's problem and is swallowed after settling.
        java.util.List<MediaAssetId> batch = new java.util.ArrayList<>();
        for (int asset = 0; asset < 4; asset++) {
            batch.add(anAvailableAsset("image/jpeg", JPEG));
        }
        MediaDerivativeWorker doomed = workerOver(serviceOver(new ProcessIsFinished()));

        // It escapes, and it must: the scheduler's error handler is the only
        // thing that can act on it, and it acts by refusing traffic so the
        // container is restarted. ProcessFatalErrorTests owns that half.
        assertThatThrownBy(doomed::renderOnce)
                .isInstanceOf(OutOfMemoryError.class)
                .hasMessage("Metaspace");

        assertThat(jobStatuses())
                .as("no job may be left LEASED by a batch that will not come back for it")
                .containsOnly("PENDING");

        java.util.List<MediaAssetId> attempted = batch.stream()
                .filter(asset -> jobErrorCode(asset) != null)
                .toList();
        assertThat(attempted)
                .as("exactly one job was in flight; the claim order the database returns is "
                        + "not fixed, so which one is not asserted")
                .hasSize(1);
        MediaAssetId inFlight = attempted.getFirst();

        // The one that was tried keeps its attempt and its backoff. Deliberate:
        // if this asset is what exhausted the Metaspace, releasing it would hand
        // it straight to the process that replaces this one and turn a bad
        // upload into a crash loop no restart can leave.
        assertThat(jobErrorCode(inFlight)).isEqualTo("RENDER_OUT_OF_MEMORY");
        assertThat(attemptCount(inFlight)).isEqualTo(1);

        // The three behind it were never touched, so they carry no attempt and
        // no error. A job the batch did not reach is not a job that failed.
        for (MediaAssetId untouched : batch) {
            if (untouched.equals(inFlight)) {
                continue;
            }
            assertThat(attemptCount(untouched)).isZero();
            assertThat(jobErrorCode(untouched)).isNull();
        }

        // And they are claimable immediately, by this process or by the one that
        // replaces it, without waiting a lease out. Three and not four: the job
        // that was tried is behind its backoff, which is the difference between
        // being settled and being released.
        assertThat(worker.renderOnce()).isEqualTo(3);
        for (MediaAssetId untouched : batch) {
            if (untouched.equals(inFlight)) {
                continue;
            }
            assertThat(jobStatus(untouched)).isEqualTo("COMPLETED");
            // One, not two. The refund is what keeps a batch that ended badly
            // from spending the budget of every job it was carrying.
            assertThat(attemptCount(untouched)).isEqualTo(1);
            assertThat(derivatives.findAll(TENANT_A, untouched)).hasSize(3);
        }
    }

    /** Uploads and finalizes, so the asset under test reached AVAILABLE the way a real one does. */
    private MediaAssetId anAvailableAsset(String contentType, byte[] content) throws Exception {
        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                contentType, content.length, "dish" + contentType.hashCode(), null);
        assertThat(put(ticket.uploadUrl(), ticket.requiredHeaders(), content)).isEqualTo(200);
        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.AVAILABLE);
        return ticket.assetId();
    }

    private ProbedImage storedDerivative(MediaAssetId assetId, DerivativeVariant variant) {
        MediaDerivative row = derivativeRows.find(TENANT_A, assetId, variant).orElseThrow();
        byte[] stored = new S3ObjectStorage(s3, presigner)
                .readPrefix(row.bucket(), row.objectKey(), ImageProbe.PROBE_BYTES);
        return ImageProbe.probe(stored).orElseThrow();
    }

    private void insertTenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Media Test LLC', 'Media Test', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId).param("slug", slug)
                .update();
    }

    private String objectKey(MediaAssetId assetId) {
        return jdbc.sql("SELECT object_key FROM media.assets WHERE asset_id = :id")
                .param("id", assetId.value())
                .query(String.class)
                .single();
    }

    private long derivativeCount() {
        return jdbc.sql("SELECT count(*) FROM media.derivatives").query(Long.class).single();
    }

    private String jobStatus(MediaAssetId assetId) {
        return jdbc.sql("SELECT status FROM media.derivative_jobs WHERE asset_id = :id")
                .param("id", assetId.value())
                .query(String.class)
                .single();
    }

    /** Every job's status, for an assertion about a batch rather than about a row. */
    private List<String> jobStatuses() {
        return jdbc.sql("SELECT status FROM media.derivative_jobs")
                .query(String.class)
                .list();
    }

    private String jobErrorCode(MediaAssetId assetId) {
        return jdbc.sql("SELECT last_error_code FROM media.derivative_jobs WHERE asset_id = :id")
                .param("id", assetId.value())
                // Wrapped, because a null column is the assertion in the test
                // that proves completing a job clears its error columns, and
                // single() will not hand back a bare null.
                .query((row, number) -> java.util.Optional.ofNullable(
                        row.getString("last_error_code")))
                .single()
                .orElse(null);
    }

    private List<Integer> dimensions(MediaAssetId assetId) {
        return jdbc.sql("SELECT width_px, height_px FROM media.assets WHERE asset_id = :id")
                .param("id", assetId.value())
                .query((row, number) -> List.of(row.getInt("width_px"), row.getInt("height_px")))
                .single();
    }

    private static byte[] encode(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, bytes);
        } catch (IOException impossible) {
            throw new IllegalStateException("Encoding an in-memory image cannot fail", impossible);
        }
        return bytes.toByteArray();
    }

    private String rejectionCode(MediaAssetId assetId) {
        return jdbc.sql("SELECT rejection_code FROM media.assets WHERE asset_id = :id")
                .param("id", assetId.value())
                .query(String.class)
                .single();
    }


    private MediaDerivativeService serviceOver(ImageDerivativeRenderer renderer) {
        return new MediaDerivativeService(new JdbcMediaAssetStore(jdbc), derivativeRows,
                storage, renderer, clock);
    }

    /** The production settings, except three attempts rather than six, so a budget is spendable. */
    private MediaDerivativeWorker workerOver(MediaDerivativeService service) {
        return new MediaDerivativeWorker(jobs, service, clock, meters,
                4, Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMinutes(15), 3,
                "media-lifecycle-tests");
    }

    /**
     * An AVAILABLE asset whose bytes today's finalize would refuse.
     *
     * <p>Uploaded and finalized for real — so the object in MinIO is the real
     * file at the real key — and then marked available by hand, because that is
     * exactly the row a re-render sweep finds: one written before the gate
     * existed. Faking the verdict rather than the object is the point; the
     * renderer is what is under test.
     */
    private MediaAssetId anAssetTheGateWouldNowRefuse(String contentType, byte[] content)
            throws Exception {

        var ticket = media.requestUpload(TENANT_A, MediaOwner.brand(BRAND), MediaVisibility.PUBLIC,
                contentType, content.length, "legacy" + contentType.hashCode(), null);
        assertThat(put(ticket.uploadUrl(), ticket.requiredHeaders(), content)).isEqualTo(200);
        assertThat(media.finalizeUpload(TENANT_A, ticket.assetId()))
                .isEqualTo(MediaAssetStatus.REJECTED);

        ProbedImage header = ImageProbe.probe(content).orElseThrow();
        jdbc.sql("""
                UPDATE media.assets
                   SET status = 'AVAILABLE', rejection_code = NULL, rejection_detail = NULL,
                       verified_content_type = :type, verified_size_bytes = :size,
                       verified_checksum_sha256 = 'legacy-row-has-no-checksum',
                       width_px = :width, height_px = :height, available_at = :now
                 WHERE asset_id = :id
                """)
                .param("type", contentType)
                .param("size", (long) content.length)
                .param("width", header.widthPx())
                .param("height", header.heightPx())
                .param("now", java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", ticket.assetId().value())
                .update();

        assertThat(jobs.enqueue(UUID.randomUUID(), TENANT_A, ticket.assetId(), clock.instant()))
                .isTrue();
        return ticket.assetId();
    }

    private double outcomeCount(String outcome) {
        return meters.counter("horecaos.media.derivative.jobs", "outcome", outcome).count();
    }

    private int attemptCount(MediaAssetId assetId) {
        return jdbc.sql("SELECT attempt_count FROM media.derivative_jobs WHERE asset_id = :id")
                .param("id", assetId.value())
                .query(Integer.class)
                .single();
    }

    private Instant jobErroredAt(MediaAssetId assetId) {
        return jdbc.sql("SELECT last_error_at FROM media.derivative_jobs WHERE asset_id = :id")
                .param("id", assetId.value())
                .query((row, number) -> {
                    var at = row.getObject("last_error_at", java.time.OffsetDateTime.class);
                    return at == null ? null : at.toInstant();
                })
                .single();
    }

    private String rejectionDetail(MediaAssetId assetId) {
        return jdbc.sql("SELECT rejection_detail FROM media.assets WHERE asset_id = :id")
                .param("id", assetId.value())
                .query((row, number) -> row.getString("rejection_detail"))
                .single();
    }

    /**
     * A clock a test can push, because the worker under test is made of durations.
     *
     * <p>{@code Clock.fixed} is right for asserting that a timestamp was written;
     * it is wrong for a component whose whole behaviour is "not yet" and "now".
     */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException("This fixture's clock is UTC");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * What {@code JPEGImageReader} does when it cannot allocate its raster.
     *
     * <p>An {@code Error}, not an exception, and that is the whole point: it
     * passed through every catch the renderer and the worker had.
     */
    private static final class ExhaustsMemory implements ImageDerivativeRenderer {

        @Override
        public String processorVersion() {
            return "out-of-memory-stand-in";
        }

        @Override
        public RenderOutcome render(byte[] source, List<DerivativeVariant> variants) {
            throw new OutOfMemoryError("Java heap space");
        }
    }

    /**
     * An {@code Error} that says the process is finished rather than that this
     * image was too big.
     *
     * <p>Metaspace exhaustion is the canonical one: the class metadata space is
     * process-wide, unwinding this frame gives none of it back, and the next job
     * meets exactly the same wall. {@code DecodeError} refuses to vouch for it,
     * so the tick settles its own job and rethrows.
     */
    private static final class ProcessIsFinished implements ImageDerivativeRenderer {

        @Override
        public String processorVersion() {
            return "process-is-finished-stand-in";
        }

        @Override
        public RenderOutcome render(byte[] source, List<DerivativeVariant> variants) {
            throw new OutOfMemoryError("Metaspace");
        }
    }

    /** Fails a fixed number of times and then renders for real. */
    private static final class FailsThenRenders implements ImageDerivativeRenderer {

        private final ImageDerivativeRenderer real = new ImageIoDerivativeRenderer();
        private final int failures;
        private final RenderOutcome failure;
        private int calls;

        private FailsThenRenders(int failures, RenderOutcome failure) {
            this.failures = failures;
            this.failure = failure;
        }

        @Override
        public String processorVersion() {
            return real.processorVersion();
        }

        @Override
        public RenderOutcome render(byte[] source, List<DerivativeVariant> variants) {
            return calls++ < failures ? failure : real.render(source, variants);
        }
    }

    /**
     * A real PNG of the given header, whose pixels are all zero.
     *
     * <p>Deflating zeros rather than encoding a raster, so a fixture that
     * describes 305MB of image costs 300KB to build and never allocates it. The
     * output is a valid file: signature, IHDR with the declared depth and colour
     * type, one IDAT of correctly-sized filtered scanlines, IEND.
     */
    private static byte[] pngOfZeros(int width, int height, int bitDepth, int colourType,
            int channels) {

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeInt(header, width);
        writeInt(header, height);
        header.write(bitDepth);
        header.write(colourType);
        header.write(0);   // deflate
        header.write(0);   // adaptive filtering
        header.write(0);   // no interlacing

        long rowBytes = (long) width * channels * (bitDepth / 8) + 1;
        long remaining = rowBytes * height;
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        byte[] zeros = new byte[64 * 1024];
        byte[] compressed = new byte[64 * 1024];
        while (remaining > 0) {
            int chunk = (int) Math.min(zeros.length, remaining);
            deflater.setInput(zeros, 0, chunk);
            remaining -= chunk;
            while (!deflater.needsInput()) {
                int produced = deflater.deflate(compressed);
                if (produced == 0) {
                    break;
                }
                pixels.write(compressed, 0, produced);
            }
        }
        deflater.finish();
        while (!deflater.finished()) {
            int produced = deflater.deflate(compressed);
            if (produced > 0) {
                pixels.write(compressed, 0, produced);
            }
        }
        deflater.end();

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        file.writeBytes(chunk("IHDR", header.toByteArray()));
        file.writeBytes(chunk("IDAT", pixels.toByteArray()));
        file.writeBytes(chunk("IEND", new byte[0]));
        return file.toByteArray();
    }

    private static byte[] chunk(String type, byte[] payload) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, payload.length);
        out.writeBytes(name);
        out.writeBytes(payload);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(payload);
        writeInt(out, (int) crc.getValue());
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static int put(URI url, Map<String, String> headers, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static byte[] get(URI url) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
        }
    }
}
