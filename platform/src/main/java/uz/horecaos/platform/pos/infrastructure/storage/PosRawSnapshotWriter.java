package uz.horecaos.platform.pos.infrastructure.storage;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;

/**
 * Writes one run's raw provider evidence to S3-compatible storage (ADR 0012,
 * ADR 0010).
 *
 * <p>ADR 0012 names {@code raw_object_key} on {@code pos_sync_runs} (V0037) and
 * nothing has ever written it — a bad import has only the per-entity {@code
 * raw_payload} JSONB already sitting in the staging tables to diagnose it from,
 * never the provider's response as a whole. This class is the write: it bundles
 * every entity's already-captured {@link CatalogSnapshot} raw payload — the
 * evidence the normalizer kept precisely so it would survive past comparison —
 * into one document and stores it under an immutable, tenant-scoped key.
 *
 * <p><b>Reuses {@code media}'s already-proven object store rather than standing
 * up a second one.</b> {@code audit} has its own {@code S3Client} and bucket
 * ({@code AuditArchiveStorageConfiguration}) because it needs Object Lock
 * retention {@link ObjectStorage} does not expose; this class needs neither that
 * nor a new credential, endpoint, and bucket to provision in every environment
 * for one diagnostic artifact, so it depends on {@link ObjectStorage} —
 * {@code media}'s own public port, exactly the seam {@code media.api} exists to
 * be depended on through — with its own key prefix in the same bucket.
 *
 * <p><b>Best-effort, and stated as such.</b> A raw snapshot is what lets an
 * operator diagnose a bad import after the fact; it is not itself part of what
 * makes an import correct — {@link uz.horecaos.platform.pos.domain.DifferenceEngine}
 * runs against the normalized {@link CatalogSnapshot}, never against this
 * object. A store outage therefore costs future diagnosability, not today's
 * run, so a write failure here is logged and swallowed rather than failing the
 * run underneath it.
 */
@Component
public class PosRawSnapshotWriter {

    private static final Logger log = LoggerFactory.getLogger(PosRawSnapshotWriter.class);

    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;
    private final String bucket;

    public PosRawSnapshotWriter(
            ObjectStorage storage,
            ObjectMapper objectMapper,
            @Value("${horecaos.media.bucket:horecaos-media}") String bucket) {
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
    }

    /**
     * @return the object key on a successful write, empty when the write
     *         failed. Never throws: see the class doc on why this is
     *         best-effort
     */
    public Optional<String> writeSnapshot(UUID tenantId, UUID bindingId, UUID runId, CatalogSnapshot snapshot) {
        String key = "tenants/%s/pos-sync/%s/%s/raw-snapshot.json".formatted(tenantId, bindingId, runId);
        try {
            byte[] content = objectMapper.writeValueAsString(document(snapshot)).getBytes(StandardCharsets.UTF_8);
            storage.put(bucket, key, "application/json", content);
            return Optional.of(key);
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not write the raw POS snapshot for run {} to {}/{}; the run continues without it",
                    runId,
                    bucket,
                    key,
                    failure);
            return Optional.empty();
        }
    }

    /**
     * One document holding every entity's own raw provider payload, keyed by
     * external id within its kind. Not a re-fetch and not a re-request of the
     * provider: every value here is a {@link CatalogSnapshot} entity's own
     * {@code raw} field, already captured on the way through the normalizer.
     */
    private static Map<String, Object> document(CatalogSnapshot snapshot) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("readAt", snapshot.readAt().toString());
        document.put("walkStable", snapshot.walkStable());
        document.put("pageCount", snapshot.pageCount());
        document.put(
                "categories",
                rawOf(snapshot.categories(), CatalogSnapshot.Category::externalId, CatalogSnapshot.Category::raw));
        document.put(
                "products",
                rawOf(snapshot.products(), CatalogSnapshot.Product::externalId, CatalogSnapshot.Product::raw));
        document.put(
                "variants",
                rawOf(snapshot.variants(), CatalogSnapshot.Variant::externalId, CatalogSnapshot.Variant::raw));
        document.put(
                "modifierGroups",
                rawOf(
                        snapshot.modifierGroups(),
                        CatalogSnapshot.ModifierGroup::externalId,
                        CatalogSnapshot.ModifierGroup::raw));
        document.put(
                "modifiers",
                rawOf(snapshot.modifiers(), CatalogSnapshot.Modifier::externalId, CatalogSnapshot.Modifier::raw));
        document.put(
                "availability",
                rawOf(
                        snapshot.availability(),
                        CatalogSnapshot.Availability::externalId,
                        CatalogSnapshot.Availability::raw));
        return document;
    }

    private static <T> Map<String, Object> rawOf(
            List<T> entities,
            java.util.function.Function<T, String> id,
            java.util.function.Function<T, Map<String, Object>> raw) {
        Map<String, Object> byId = new LinkedHashMap<>();
        for (T entity : entities) {
            byId.put(id.apply(entity), raw.apply(entity));
        }
        return byId;
    }
}
