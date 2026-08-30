package uz.horecaos.platform.pos.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.Entry;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.IdempotencyBehaviour;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;

/**
 * Reads the vendor ceiling and writes what a credential proved (ADR 0011).
 *
 * <p>Two stores with one shape between them. The ceiling in
 * {@code pos_provider_capabilities} says what the vendor's API can ever do and is
 * platform-owned; the probes and the snapshot say what one restaurant's
 * credential did. The narrowing direction is enforced here as well as documented,
 * because a probe that appeared to find a capability the vendor does not have
 * would otherwise be written into the snapshot and read back as fact.
 */
@Component
public class JdbcPosCapabilityStore {

    private static final TypeReference<Map<String, Map<String, Object>>> SNAPSHOT_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPosCapabilityStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** What this vendor's API can ever do, by capability code. */
    public Map<PosCapability, CapabilitySupport> ceiling(String providerType) {
        Map<PosCapability, CapabilitySupport> ceiling = new EnumMap<>(PosCapability.class);
        jdbc.sql("""
                SELECT capability_code, support_level
                  FROM integration.pos_provider_capabilities
                 WHERE provider_type = :providerType
                """)
                .param("providerType", providerType)
                .query((row, number) -> Map.entry(row.getString("capability_code"), row.getString("support_level")))
                .list()
                .forEach(entry -> capabilityOf(entry.getKey())
                        .ifPresent(capability -> ceiling.put(capability, CapabilitySupport.valueOf(entry.getValue()))));
        return Map.copyOf(ceiling);
    }

    /** Records one probe. Append-only, so "when did this stop working" is answerable. */
    public void recordProbe(
            UUID tenantId,
            UUID installationId,
            PosCapability capability,
            String probeStatus,
            Integer providerStatusCode,
            String providerErrorCode,
            String evidence,
            String adapterVersion,
            Instant probedAt) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", UUID.randomUUID());
        parameters.put("tenantId", tenantId);
        parameters.put("installationId", installationId);
        parameters.put("capability", capability.code());
        parameters.put("probeStatus", probeStatus);
        parameters.put("statusCode", providerStatusCode);
        parameters.put("errorCode", providerErrorCode);
        parameters.put("evidence", evidence);
        parameters.put("adapterVersion", adapterVersion);
        parameters.put("probedAt", OffsetDateTime.ofInstant(probedAt, ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO integration.pos_capability_probes
                    (id, tenant_id, installation_id, capability_code, probe_status,
                     provider_status_code, provider_error_code, evidence, adapter_version, probed_at)
                VALUES (:id, :tenantId, :installationId, :capability, :probeStatus,
                        :statusCode, :errorCode, :evidence, :adapterVersion, :probedAt)
                """).params(parameters).update();
    }

    /**
     * Stores the snapshot on the ADR 0026 installation row.
     *
     * <p>Written as JSON on the installation rather than as a table of its own,
     * because ADR 0026 already defines {@code capability_snapshot} there and a
     * second authority for one fact has no defined winner when the two disagree.
     */
    public void writeSnapshot(UUID tenantId, UUID installationId, CapabilitySnapshot snapshot) {
        Map<String, Object> document = new LinkedHashMap<>();
        snapshot.entries().forEach((capability, entry) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("support", entry.support().name());
            value.put("idempotency", entry.idempotency().name());
            value.put("push", entry.pushSupported());
            value.put("version", entry.capabilityVersion());
            value.put("limits", entry.limits());
            value.put("evidence", entry.evidence());
            document.put(capability.code(), value);
        });

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("installationId", installationId);
        parameters.put("snapshot", objectMapper.writeValueAsString(document));
        parameters.put("adapterVersion", snapshot.adapterVersion());
        parameters.put(
                "checkedAt",
                snapshot.verifiedAt() == null ? null : OffsetDateTime.ofInstant(snapshot.verifiedAt(), ZoneOffset.UTC));

        jdbc.sql("""
                UPDATE integration.installations
                   SET capability_snapshot = cast(:snapshot AS jsonb),
                       adapter_version = :adapterVersion,
                       last_connection_check_at = :checkedAt,
                       last_connection_status = CASE
                           WHEN cast(:snapshot AS jsonb) = '{}'::jsonb THEN 'UNVERIFIED'
                           ELSE 'SUCCEEDED' END,
                       version = version + 1,
                       updated_at = now()
                 WHERE tenant_id = :tenantId AND id = :installationId
                """).params(parameters).update();
    }

    public Optional<CapabilitySnapshot> readSnapshot(UUID tenantId, UUID installationId) {
        return jdbc.sql("""
                SELECT capability_snapshot::text AS snapshot, adapter_version,
                       last_connection_check_at
                  FROM integration.installations
                 WHERE tenant_id = :tenantId AND id = :installationId
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query((row, number) -> toSnapshot(
                        row.getString("snapshot"),
                        row.getString("adapter_version"),
                        row.getObject("last_connection_check_at", OffsetDateTime.class)))
                .optional();
    }

    /**
     * The capability codes a binding has enabled.
     *
     * <p>Read from the generic ADR 0026 table, which the V0037 trigger already
     * refuses to let hold an unsupported POS capability. This method therefore
     * reports configuration rather than re-checking it: two places deciding the
     * same rule is how they come to disagree.
     */
    public List<String> enabledCapabilities(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                SELECT capability_code
                  FROM integration.binding_capabilities
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId AND enabled
                 ORDER BY capability_code
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query(String.class)
                .list();
    }

    private CapabilitySnapshot toSnapshot(String json, String adapterVersion, OffsetDateTime checkedAt) {

        if (json == null || json.isBlank()) {
            return CapabilitySnapshot.empty();
        }
        Map<String, Map<String, Object>> document = objectMapper.readValue(json, SNAPSHOT_TYPE);
        Map<PosCapability, Entry> entries = new EnumMap<>(PosCapability.class);

        document.forEach((code, value) -> capabilityOf(code)
                .ifPresent(capability -> entries.put(
                        capability,
                        new Entry(
                                supportOf(value.get("support")),
                                idempotencyOf(value.get("idempotency")),
                                Boolean.TRUE.equals(value.get("push")),
                                value.get("version") == null ? null : String.valueOf(value.get("version")),
                                limitsOf(value.get("limits")),
                                value.get("evidence") == null ? null : String.valueOf(value.get("evidence")),
                                checkedAt == null ? null : checkedAt.toInstant()))));

        return new CapabilitySnapshot(entries, checkedAt == null ? null : checkedAt.toInstant(), adapterVersion);
    }

    /**
     * @return empty for a code this build does not know. A snapshot written by a
     *         later release and read by an earlier one is a real situation during
     *         a rolling deployment, and failing the read would take capability
     *         resolution down across the estate for the duration
     */
    private static Optional<PosCapability> capabilityOf(String code) {
        try {
            return Optional.of(PosCapability.valueOf(code));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static CapabilitySupport supportOf(Object value) {
        try {
            return CapabilitySupport.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException unknown) {
            // The safe reading of an unrecognised support level. Treating it as
            // supported would enable something on the strength of a string nobody
            // in this build understands.
            return CapabilitySupport.UNSUPPORTED;
        }
    }

    private static IdempotencyBehaviour idempotencyOf(Object value) {
        try {
            return IdempotencyBehaviour.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException unknown) {
            // NONE is the safe default for the same reason: it forbids a retry
            // rather than permitting one.
            return IdempotencyBehaviour.NONE;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> limitsOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> limits = new LinkedHashMap<>();
        ((Map<String, Object>) map).forEach((key, item) -> {
            if (item != null) {
                limits.put(key, String.valueOf(item));
            }
        });
        return Map.copyOf(limits);
    }
}
