package uz.horecaos.platform.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import uz.horecaos.platform.media.api.MediaAssetIngestion;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaDerivativeStore;
import uz.horecaos.platform.media.infrastructure.storage.S3ObjectStorage;
import uz.horecaos.platform.support.ObjectStoreContainer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * A staff photo that nothing points at any more leaves the store (ADR 0010, ADR
 * 0139), against a real PostgreSQL and a real RustFS.
 *
 * <p>The assertion that matters is on the store, not on the row: a status that
 * says {@code DELETED} while the object is still there is exactly the defect this
 * exists to prevent, so every test reads the objects back after the worker has
 * run. And the other half of the contract is what the request must <em>not</em>
 * delete: the staff photo port is the only caller, and it must be unable to reach
 * a catalogue image, a brand's logo or another tenant's file.
 */
class StaffPhotoDeletionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String BUCKET = "horecaos-media-staff-photo-deletion";
    private static final byte[] PICTURE = {1, 2, 3, 4};

    private static TestDatabase.Handle db;
    private static ObjectStoreContainer objectStore;
    private static S3Client s3;
    private static S3Presigner presigner;

    private JdbcClient jdbc;
    private JdbcMediaAssetStore assetStore;
    private JdbcMediaDerivativeStore derivativeStore;
    private S3ObjectStorage storage;
    private StaffPhotoAdapter adapter;
    private TransactionTemplate transactions;
    private SimpleMeterRegistry meters;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the deletion tests");
        db = TestDatabase.migrated();
        objectStore = new ObjectStoreContainer();
        objectStore.start();
        s3 = objectStore.s3Client();
        presigner = objectStore.s3Presigner();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException alreadyThere) {
            // Fine: the tests key on fresh ids.
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
        if (objectStore != null) {
            objectStore.stop();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        assetStore = new JdbcMediaAssetStore(jdbc);
        derivativeStore = new JdbcMediaDerivativeStore(jdbc);
        storage = new S3ObjectStorage(s3, presigner);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        meters = new SimpleMeterRegistry();
        adapter = new StaffPhotoAdapter(mock(MediaAssetIngestion.class), mock(MediaAssetService.class), assetStore);
    }

    private MediaAssetDeletionWorker workerOver(ObjectStorage objects) {
        return new MediaAssetDeletionWorker(
                assetStore,
                derivativeStore,
                objects,
                transactions,
                Clock.fixed(Instant.parse("2026-10-03T08:00:00Z"), ZoneOffset.UTC),
                meters,
                25);
    }

    // ------------------------------------------------------------------ the photo leaves

    @Test
    @DisplayName(
            "a discarded photo stops being displayable at once, and its object and renditions leave the store when the worker runs")
    void aDiscardedPhotoLeavesTheStore() {
        Photo photo = photo(TENANT, "TENANT", TENANT, "PRIVATE", 2);

        adapter.discard(TENANT, photo.id());

        assertThat(statusOf(photo.id())).isEqualTo("DELETION_REQUESTED");
        assertThat(adapter.isPrivateTenantAsset(TENANT, photo.id()))
                .as("not displayable from the moment the request commits")
                .isFalse();
        assertThat(photo.allKeys())
                .as("the request alone has not touched the store")
                .allMatch(this::present);

        assertThat(workerOver(storage).deleteOnce()).isEqualTo(1);

        assertThat(photo.allKeys())
                .as("the original and both renditions are gone from the store itself")
                .noneMatch(this::present);
        assertThat(statusOf(photo.id())).isEqualTo("DELETED");
        assertThat(jdbc.sql("SELECT deleted_at FROM media.assets WHERE asset_id = :id")
                        .param("id", photo.id())
                        .query(java.time.OffsetDateTime.class)
                        .single())
                .isNotNull();
        assertThat(count("SELECT count(*) FROM media.derivatives WHERE asset_id = :id", photo.id()))
                .as("no row keeps naming a key that no longer exists")
                .isZero();
    }

    @Test
    @DisplayName("asking twice is the same as asking once, and a second pass has nothing left to do")
    void deletionIsIdempotent() {
        Photo photo = photo(TENANT, "TENANT", TENANT, "PRIVATE", 1);

        adapter.discard(TENANT, photo.id());
        adapter.discard(TENANT, photo.id());
        MediaAssetDeletionWorker worker = workerOver(storage);

        assertThat(worker.deleteOnce()).isEqualTo(1);
        assertThat(worker.deleteOnce()).isZero();
        adapter.discard(TENANT, photo.id());
        assertThat(statusOf(photo.id())).as("a deleted asset is not revived").isEqualTo("DELETED");
        assertThat(worker.deleteOnce()).isZero();
    }

    // ------------------------------------------------------------------ what it must not reach

    @Test
    @DisplayName(
            "the request cannot reach a public image, a brand's file or another tenant's photo, whatever id it is given")
    void theRequestReachesOnlyAPrivateTenantOwnedAsset() {
        Photo publicImage = photo(TENANT, "TENANT", TENANT, "PUBLIC", 1);
        Photo brandFile = photo(TENANT, "BRAND", BRAND, "PRIVATE", 1);
        Photo foreign = photo(OTHER_TENANT, "TENANT", OTHER_TENANT, "PRIVATE", 1);

        adapter.discard(TENANT, publicImage.id());
        adapter.discard(TENANT, brandFile.id());
        adapter.discard(TENANT, foreign.id());
        adapter.discard(TENANT, UUID.randomUUID());

        for (Photo untouched : new Photo[] {publicImage, brandFile, foreign}) {
            assertThat(statusOf(untouched.id())).isEqualTo("AVAILABLE");
        }
        assertThat(workerOver(storage).deleteOnce()).isZero();
        assertThat(publicImage.allKeys()).allMatch(this::present);
        assertThat(brandFile.allKeys()).allMatch(this::present);
        assertThat(foreign.allKeys()).allMatch(this::present);
    }

    // ------------------------------------------------------------------ a store that is down

    @Test
    @DisplayName(
            "a store that cannot be reached leaves the asset waiting, never marked deleted, and the next pass finishes it")
    void aStoreThatIsDownIsRetried() {
        Photo photo = photo(TENANT, "TENANT", TENANT, "PRIVATE", 2);
        adapter.discard(TENANT, photo.id());

        assertThat(workerOver(failingDeletes(storage)).deleteOnce()).isZero();

        assertThat(statusOf(photo.id()))
                .as("DELETED is only ever said after the objects are gone")
                .isEqualTo("DELETION_REQUESTED");
        assertThat(present(photo.originalKey()))
                .as("the original is deleted last, so a pass that stopped part way can still find the rest")
                .isTrue();
        assertThat(meters.get("horecaos.media.deletion.assets")
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1.0);

        assertThat(workerOver(storage).deleteOnce()).isEqualTo(1);
        assertThat(photo.allKeys()).noneMatch(this::present);
        assertThat(statusOf(photo.id())).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("one asset the store refuses does not hold up the others in the batch")
    void oneFailureDoesNotStopTheBatch() {
        Photo stuck = photo(TENANT, "TENANT", TENANT, "PRIVATE", 0);
        Photo fine = photo(TENANT, "TENANT", TENANT, "PRIVATE", 0);
        adapter.discard(TENANT, stuck.id());
        adapter.discard(TENANT, fine.id());
        ObjectStorage refusesOne = new DelegatingStorage(storage) {
            @Override
            public void delete(String bucket, String key) {
                if (key.equals(stuck.originalKey())) {
                    throw new IllegalStateException("the store refused this one");
                }
                super.delete(bucket, key);
            }
        };

        assertThat(workerOver(refusesOne).deleteOnce()).isEqualTo(1);

        assertThat(statusOf(fine.id())).isEqualTo("DELETED");
        assertThat(statusOf(stuck.id())).isEqualTo("DELETION_REQUESTED");
    }

    // ------------------------------------------------------------------ fixtures

    /** A verified, available asset with its object and {@code renditions} derivative objects really in the store. */
    private Photo photo(UUID tenant, String ownerScope, UUID ownerId, String visibility, int renditions) {
        UUID id = UUID.randomUUID();
        String originalKey = tenant + "/" + ownerScope.toLowerCase() + "/" + id;
        storage.put(BUCKET, originalKey, "image/jpeg", PICTURE);
        jdbc.sql("""
                INSERT INTO media.assets (asset_id, tenant_id, owner_scope, owner_id, bucket, object_key,
                    visibility, status, declared_content_type, declared_size_bytes,
                    verified_content_type, verified_size_bytes, verified_checksum_sha256)
                VALUES (:id, :tenant, :scope, :owner, :bucket, :key, :visibility, 'AVAILABLE',
                    'image/jpeg', 4, 'image/jpeg', 4, repeat('0', 64))
                """)
                .param("id", id)
                .param("tenant", tenant)
                .param("scope", ownerScope)
                .param("owner", ownerId)
                .param("bucket", BUCKET)
                .param("key", originalKey)
                .param("visibility", visibility)
                .update();
        java.util.List<String> keys = new java.util.ArrayList<>();
        String[] variants = {"THUMBNAIL", "CARD", "DETAIL"};
        for (int index = 0; index < renditions; index++) {
            String key = originalKey + "/" + variants[index].toLowerCase();
            storage.put(BUCKET, key, "image/jpeg", PICTURE);
            jdbc.sql("""
                    INSERT INTO media.derivatives (derivative_id, tenant_id, asset_id, variant, object_key,
                        bucket, content_type, size_bytes, checksum_sha256, width_px, height_px, processor_version)
                    VALUES (:derivative, :tenant, :asset, :variant, :key, :bucket, 'image/jpeg', 4,
                        repeat('0', 64), 10, 10, 'test')
                    """)
                    .param("derivative", UUID.randomUUID())
                    .param("tenant", tenant)
                    .param("asset", id)
                    .param("variant", variants[index])
                    .param("key", key)
                    .param("bucket", BUCKET)
                    .update();
            keys.add(key);
        }
        return new Photo(id, originalKey, keys);
    }

    private record Photo(UUID id, String originalKey, java.util.List<String> renditionKeys) {
        java.util.List<String> allKeys() {
            java.util.List<String> all = new java.util.ArrayList<>(renditionKeys);
            all.add(originalKey);
            return all;
        }
    }

    private boolean present(String key) {
        return storage.head(BUCKET, key).isPresent();
    }

    private String statusOf(UUID assetId) {
        return jdbc.sql("SELECT status FROM media.assets WHERE asset_id = :id")
                .param("id", assetId)
                .query(String.class)
                .single();
    }

    private long count(String sql, UUID assetId) {
        return jdbc.sql(sql).param("id", assetId).query(Long.class).single();
    }

    /** A store whose deletes all fail, as one that is down would. */
    private static ObjectStorage failingDeletes(ObjectStorage real) {
        return new DelegatingStorage(real) {
            @Override
            public void delete(String bucket, String key) {
                throw new IllegalStateException("the object store is not reachable");
            }
        };
    }

    private static class DelegatingStorage implements ObjectStorage {

        private final ObjectStorage real;

        DelegatingStorage(ObjectStorage real) {
            this.real = real;
        }

        @Override
        public PresignedUpload presignUpload(
                String bucket, String key, String contentType, long maxSizeBytes, Duration validFor) {
            return real.presignUpload(bucket, key, contentType, maxSizeBytes, validFor);
        }

        @Override
        public URI presignDownload(String bucket, String key, Duration validFor) {
            return real.presignDownload(bucket, key, validFor);
        }

        @Override
        public Optional<StoredObject> head(String bucket, String key) {
            return real.head(bucket, key);
        }

        @Override
        public void delete(String bucket, String key) {
            real.delete(bucket, key);
        }
    }
}
