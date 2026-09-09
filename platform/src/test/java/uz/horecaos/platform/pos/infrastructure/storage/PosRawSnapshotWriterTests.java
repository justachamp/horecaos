package uz.horecaos.platform.pos.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.domain.SourceKind;

/**
 * The raw-evidence write ADR 0012's {@code raw_object_key} always named and
 * nothing wrote until this wave.
 */
class PosRawSnapshotWriterTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-7600-7000-8000-0000000f0001");
    private static final UUID BINDING = UUID.fromString("018f6f4e-7600-7000-8000-0000000f0002");
    private static final UUID RUN = UUID.fromString("018f6f4e-7600-7000-8000-0000000f0003");

    @Test
    void writesEveryEntitysRawPayloadUnderAnImmutableTenantScopedKey() {
        RecordingStorage storage = new RecordingStorage();
        PosRawSnapshotWriter writer =
                new PosRawSnapshotWriter(storage, JsonMapper.builder().build(), "horecaos-media");

        CatalogSnapshot snapshot = new CatalogSnapshot(
                Instant.parse("2026-08-24T04:00:00Z"),
                true,
                1,
                List.of(),
                List.of(new CatalogSnapshot.Product(
                        "ext-41",
                        "Lagman",
                        "cat-1",
                        SourceKind.DISH,
                        true,
                        false,
                        32_000L,
                        "UZS",
                        true,
                        false,
                        null,
                        Map.of("name", "Lagman", "price", "32000"))),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        Optional<String> key = writer.writeSnapshot(TENANT, BINDING, RUN, snapshot);

        assertThat(key).isPresent();
        assertThat(key.get()).isEqualTo("tenants/%s/pos-sync/%s/%s/raw-snapshot.json".formatted(TENANT, BINDING, RUN));
        assertThat(storage.puts).hasSize(1);
        RecordedPut put = storage.puts.getFirst();
        assertThat(put.bucket()).isEqualTo("horecaos-media");
        assertThat(put.key()).isEqualTo(key.get());
        assertThat(put.contentType()).isEqualTo("application/json");
        String document = new String(put.content(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(document).contains("ext-41").contains("Lagman").contains("32000");
    }

    @Test
    void aStorageFailureIsSwallowedRatherThanFailingTheRun() {
        ObjectStorage failing = new RecordingStorage() {
            @Override
            public void put(String bucket, String key, String contentType, byte[] content) {
                throw new IllegalStateException("MinIO is unreachable");
            }
        };
        PosRawSnapshotWriter writer =
                new PosRawSnapshotWriter(failing, JsonMapper.builder().build(), "horecaos-media");

        Optional<String> key = writer.writeSnapshot(
                TENANT,
                BINDING,
                RUN,
                new CatalogSnapshot(
                        Instant.EPOCH, true, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        assertThat(key)
                .as("a diagnostic write failing must never look like a run failure to the caller")
                .isEmpty();
    }

    private record RecordedPut(String bucket, String key, String contentType, byte[] content) {}

    private static class RecordingStorage implements ObjectStorage {
        private final List<RecordedPut> puts = new ArrayList<>();

        @Override
        public PresignedUpload presignUpload(
                String bucket, String key, String contentType, long maxSizeBytes, Duration validFor) {
            throw new UnsupportedOperationException("Not exercised by this writer");
        }

        @Override
        public URI presignDownload(String bucket, String key, Duration validFor) {
            throw new UnsupportedOperationException("Not exercised by this writer");
        }

        @Override
        public Optional<StoredObject> head(String bucket, String key) {
            throw new UnsupportedOperationException("Not exercised by this writer");
        }

        @Override
        public void put(String bucket, String key, String contentType, byte[] content) {
            puts.add(new RecordedPut(bucket, key, contentType, content));
        }

        @Override
        public void delete(String bucket, String key) {
            throw new UnsupportedOperationException("Not exercised by this writer");
        }
    }
}
