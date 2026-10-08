package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the marks of marked order lines (ADR 0161, {@code ordering.order_line_marks}).
 *
 * <p>Every statement names the tenant, the table has no row-level-security policy in v1 (see V0557), and
 * a mark is released, never deleted: the only {@code UPDATE} this class issues sets {@code released_at}
 * and {@code released_reason}, the only columns the application role may update.
 *
 * <p>No statement here selects {@code code_encrypted} except {@link #protectedLiveMarks}, whose one
 * caller is the receipt projection; the read model the console sees is built from {@link LiveMark}, which
 * has no code in it.
 */
@Repository
public class JdbcOrderMarkStore {

    private final JdbcClient jdbc;

    public JdbcOrderMarkStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the order row's lock, or reports that the tenant has no such order.
     *
     * <p>The one lock the capture, the removal and the handover guard serialise on (ADR 0161). Taken
     * before the order's status is read and before anything is counted, so whoever holds it first wins:
     * a removal that commits first leaves the guard counting one fewer; a guard that holds it first
     * makes the removal wait and then find the order handed over.
     */
    public boolean lockOrder(UUID tenantId, UUID orderId) {
        return !jdbc.sql("SELECT 1 FROM ordering.orders WHERE tenant_id = :tenantId AND id = :orderId FOR UPDATE")
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(Integer.class)
                .list()
                .isEmpty();
    }

    /** The lines of an order that no revision has closed, with what marking needs to know about each. */
    public List<LiveLine> liveLines(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, line_number, source_variant_id, product_name_snapshot, quantity
                FROM ordering.order_lines
                WHERE tenant_id = :tenantId AND order_id = :orderId AND revision_to IS NULL
                ORDER BY line_number
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new LiveLine(
                        row.getObject("id", UUID.class),
                        row.getInt("line_number"),
                        row.getObject("source_variant_id", UUID.class),
                        row.getString("product_name_snapshot"),
                        row.getBigDecimal("quantity")))
                .list();
    }

    /** One live line of an order, or empty for a line that does not belong to it or has been closed. */
    public Optional<LiveLine> findLiveLine(UUID tenantId, UUID orderId, UUID lineId) {
        return liveLines(tenantId, orderId).stream()
                .filter(line -> line.lineId().equals(lineId))
                .findFirst();
    }

    /** Live marks per line, in sequence order. Never a code. */
    public List<LiveMark> liveMarks(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, order_line_id, sequence, gtin, capture_stage, entry_method, gtin_checked,
                       captured_by_actor_id, captured_at
                FROM ordering.order_line_marks
                WHERE tenant_id = :tenantId AND order_id = :orderId AND released_at IS NULL
                ORDER BY order_line_id, sequence
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcOrderMarkStore::toLiveMark)
                .list();
    }

    /** How many live marks each line of the order holds; a line with none is absent. */
    public Map<UUID, Integer> liveCounts(UUID tenantId, UUID orderId) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT order_line_id, count(*) AS marks
                FROM ordering.order_line_marks
                WHERE tenant_id = :tenantId AND order_id = :orderId AND released_at IS NULL
                GROUP BY order_line_id
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> {
                    counts.put(row.getObject("order_line_id", UUID.class), row.getInt("marks"));
                    return 0;
                })
                .list();
        return counts;
    }

    public boolean hasLiveMarks(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM ordering.order_line_marks
                    WHERE tenant_id = :tenantId AND order_id = :orderId AND released_at IS NULL)
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(Boolean.class)
                .single();
    }

    /** The live mark that already holds this code (by keyed hash) anywhere in the tenant. */
    public Optional<LiveCodeOwner> liveOwnerOfCode(UUID tenantId, String codeLookupHash) {
        return jdbc.sql("""
                SELECT order_id, order_line_id FROM ordering.order_line_marks
                WHERE tenant_id = :tenantId AND code_lookup_hash = :hash AND released_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("hash", codeLookupHash)
                .query((row, number) -> new LiveCodeOwner(
                        row.getObject("order_id", UUID.class), row.getObject("order_line_id", UUID.class)))
                .optional();
    }

    /** The lowest sequence in {@code 1..limit} that no live mark of the line holds, or empty when the line is full. */
    public Optional<Integer> lowestFreeSequence(UUID tenantId, UUID lineId, int limit) {
        return jdbc.sql("""
                SELECT s FROM generate_series(1, :limit) AS s
                WHERE NOT EXISTS (
                    SELECT 1 FROM ordering.order_line_marks m
                    WHERE m.tenant_id = :tenantId AND m.order_line_id = :lineId
                      AND m.sequence = s AND m.released_at IS NULL)
                ORDER BY s LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("lineId", lineId)
                .param("limit", limit)
                .query(Integer.class)
                .optional();
    }

    public void insert(NewMark mark) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", mark.id());
        params.put("tenantId", mark.tenantId());
        params.put("orderId", mark.orderId());
        params.put("lineId", mark.lineId());
        params.put("sequence", mark.sequence());
        params.put("gtin", mark.gtin());
        params.put("codeEncrypted", mark.codeEncrypted());
        params.put("hash", mark.codeLookupHash());
        params.put("stage", mark.stage());
        params.put("method", mark.entryMethod());
        params.put("gtinChecked", mark.gtinChecked());
        params.put("actorType", mark.actorType());
        params.put("actorId", mark.actorId());
        params.put("now", utc(mark.capturedAt()));
        jdbc.sql("""
                INSERT INTO ordering.order_line_marks (
                    id, tenant_id, order_id, order_line_id, sequence, gtin, code_encrypted, code_lookup_hash,
                    capture_stage, entry_method, gtin_checked, captured_by_actor_type, captured_by_actor_id,
                    captured_at)
                VALUES (:id, :tenantId, :orderId, :lineId, :sequence, :gtin, :codeEncrypted, :hash,
                    :stage, :method, :gtinChecked, :actorType, :actorId, :now)
                """).params(params).update();
    }

    /** A live mark of this order, or empty for one that is released, of another order, or of another tenant. */
    public Optional<LiveMark> findLiveMark(UUID tenantId, UUID orderId, UUID markId) {
        return jdbc.sql("""
                SELECT id, order_line_id, sequence, gtin, capture_stage, entry_method, gtin_checked,
                       captured_by_actor_id, captured_at
                FROM ordering.order_line_marks
                WHERE tenant_id = :tenantId AND order_id = :orderId AND id = :markId AND released_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("markId", markId)
                .query(JdbcOrderMarkStore::toLiveMark)
                .optional();
    }

    /** Releases one live mark; false when it was already released (a replayed removal). */
    public boolean release(UUID tenantId, UUID markId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_line_marks
                SET released_at = :now, released_reason = :reason
                WHERE tenant_id = :tenantId AND id = :markId AND released_at IS NULL
                """)
                        .param("now", utc(now))
                        .param("reason", reason)
                        .param("tenantId", tenantId)
                        .param("markId", markId)
                        .update()
                == 1;
    }

    /** Releases every live mark of an order; returns how many. */
    public int releaseAll(UUID tenantId, UUID orderId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_line_marks
                SET released_at = :now, released_reason = :reason
                WHERE tenant_id = :tenantId AND order_id = :orderId AND released_at IS NULL
                """)
                .param("now", utc(now))
                .param("reason", reason)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .update();
    }

    /** Releases every live mark of one line; returns how many. */
    public int releaseLine(UUID tenantId, UUID lineId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_line_marks
                SET released_at = :now, released_reason = :reason
                WHERE tenant_id = :tenantId AND order_line_id = :lineId AND released_at IS NULL
                """)
                .param("now", utc(now))
                .param("reason", reason)
                .param("tenantId", tenantId)
                .param("lineId", lineId)
                .update();
    }

    /**
     * The live marks with their protected codes, for the receipt projection and for nothing else.
     *
     * <p>The ciphertext, not the code: the caller decrypts it under a recorded purpose.
     */
    public List<ProtectedMark> protectedLiveMarks(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, order_line_id, sequence, gtin, code_encrypted
                FROM ordering.order_line_marks
                WHERE tenant_id = :tenantId AND order_id = :orderId AND released_at IS NULL
                ORDER BY order_line_id, sequence
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new ProtectedMark(
                        row.getObject("id", UUID.class),
                        row.getObject("order_line_id", UUID.class),
                        row.getInt("sequence"),
                        row.getString("gtin"),
                        row.getString("code_encrypted")))
                .list();
    }

    private static LiveMark toLiveMark(ResultSet row, int number) throws SQLException {
        return new LiveMark(
                row.getObject("id", UUID.class),
                row.getObject("order_line_id", UUID.class),
                row.getInt("sequence"),
                row.getString("gtin"),
                row.getString("capture_stage"),
                row.getString("entry_method"),
                row.getBoolean("gtin_checked"),
                row.getString("captured_by_actor_id"),
                row.getObject("captured_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** A live order line, as marking sees it. */
    public record LiveLine(UUID lineId, int lineNumber, UUID variantId, String productName, BigDecimal quantity) {

        /** The units to scan: the integer quantity (a marked good is never fractional). */
        public int units() {
            return quantity.setScale(0, java.math.RoundingMode.CEILING).intValueExact();
        }
    }

    /** One live mark, without its code. */
    public record LiveMark(
            UUID markId,
            UUID lineId,
            int sequence,
            String gtin,
            String stage,
            String entryMethod,
            boolean gtinChecked,
            String capturedBy,
            Instant capturedAt) {}

    public record LiveCodeOwner(UUID orderId, UUID lineId) {}

    /**
     * A mark about to be written.
     *
     * @param codeEncrypted the ADR 0029 {@code FINANCIAL} envelope of the canonical code, bound to this row
     */
    public record NewMark(
            UUID id,
            UUID tenantId,
            UUID orderId,
            UUID lineId,
            int sequence,
            String gtin,
            String codeEncrypted,
            String codeLookupHash,
            String stage,
            String entryMethod,
            boolean gtinChecked,
            String actorType,
            String actorId,
            Instant capturedAt) {}

    /** A live mark with its protected code, for the receipt projection only. */
    public record ProtectedMark(UUID markId, UUID lineId, int sequence, String gtin, String codeEncrypted) {}
}
