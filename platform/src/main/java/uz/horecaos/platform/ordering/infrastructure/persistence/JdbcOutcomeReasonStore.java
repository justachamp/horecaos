package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;

/**
 * The tenant's cancellation and completion reason registry (ADR 0039).
 *
 * <p>Separate from {@link JdbcOrderStore} because it is a different aggregate
 * with a different lifecycle: an order is written once and closed, while a reason
 * is authored, renamed, and eventually archived by an administrator who never
 * touches an order.
 *
 * <p>Every query carries the tenant predicate. A reason id is a UUID that arrives
 * from a client, and citing another tenant's reason on a cancellation would put
 * their internal wording into this tenant's funnel.
 */
@Repository
public class JdbcOutcomeReasonStore {

    private final JdbcClient jdbc;

    public JdbcOutcomeReasonStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewReason reason) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", reason.id());
        params.put("tenantId", reason.tenantId());
        params.put("kind", reason.kind().name());
        params.put("category", reason.systemCategory());
        params.put("internalName", reason.internalName());
        params.put("disposition", reason.stockDisposition());
        params.put("liability", reason.liabilityParty());
        params.put("refund", reason.customerRefund());
        params.put(
                "modes",
                reason.allowedFulfillmentModes() == null
                        ? null
                        : reason.allowedFulfillmentModes().toArray(String[]::new));
        params.put("now", utc(reason.createdAt()));

        jdbc.sql("""
                INSERT INTO ordering.order_outcome_reasons (
                    id, tenant_id, kind, system_category, internal_name, stock_disposition,
                    liability_party, customer_refund, allowed_fulfillment_modes, status,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :kind, :category, :internalName, :disposition,
                    :liability, :refund, :modes::varchar[], 'ACTIVE',
                    1, :now, :now)
                """).params(params).update();
    }

    /**
     * Replaces a reason's consequence fields and bumps its version.
     *
     * <p>The version is what an outcome cites, so bumping it on every edit is what
     * makes "this order was cancelled under the reason as it read then" a
     * checkable statement rather than an assumption.
     */
    public Optional<Integer> update(
            UUID tenantId,
            UUID reasonId,
            int expectedVersion,
            String internalName,
            String stockDisposition,
            String liabilityParty,
            String customerRefund,
            List<String> allowedFulfillmentModes,
            Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", reasonId);
        params.put("expectedVersion", expectedVersion);
        params.put("internalName", internalName);
        params.put("disposition", stockDisposition);
        params.put("liability", liabilityParty);
        params.put("refund", customerRefund);
        params.put("modes", allowedFulfillmentModes == null ? null : allowedFulfillmentModes.toArray(String[]::new));
        params.put("now", utc(now));

        return jdbc.sql("""
                UPDATE ordering.order_outcome_reasons
                SET internal_name = :internalName,
                    stock_disposition = :disposition::varchar,
                    liability_party = :liability::varchar,
                    customer_refund = :refund::varchar,
                    allowed_fulfillment_modes = :modes::varchar[],
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                  AND status = 'ACTIVE'
                RETURNING version
                """).params(params).query(Integer.class).optional();
    }

    /**
     * Retires a reason without deleting it.
     *
     * <p>Archived rather than removed, because outcomes recorded under it must
     * still resolve. The partial unique index is on active rows only, so the name
     * becomes available again for a replacement.
     */
    public boolean archive(UUID tenantId, UUID reasonId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_outcome_reasons
                SET status = 'ARCHIVED', version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                  AND status = 'ACTIVE'
                """)
                        .param("tenantId", tenantId)
                        .param("id", reasonId)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public void replaceTexts(UUID reasonId, Map<String, String> customerTextByLocale) {
        jdbc.sql("DELETE FROM ordering.order_outcome_reason_texts WHERE reason_id = :id")
                .param("id", reasonId)
                .update();

        customerTextByLocale.forEach((locale, text) -> jdbc.sql("""
                INSERT INTO ordering.order_outcome_reason_texts (reason_id, locale, customer_text)
                VALUES (:id, :locale, :text)
                """)
                .param("id", reasonId)
                .param("locale", locale)
                .param("text", text)
                .update());
    }

    public Optional<ReasonRow> find(UUID tenantId, UUID reasonId) {
        return jdbc.sql(SELECT_REASON + """
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", reasonId)
                .query(JdbcOutcomeReasonStore::mapReason)
                .optional();
    }

    /** The reasons an operator may pick from, newest configuration first. */
    public List<ReasonRow> list(UUID tenantId, OutcomeReasonKind kind, boolean activeOnly) {
        return jdbc.sql(SELECT_REASON + """
                 WHERE tenant_id = :tenantId AND kind = :kind
                   AND (:activeOnly = false OR status = 'ACTIVE')
                 ORDER BY system_category, internal_name
                """)
                .param("tenantId", tenantId)
                .param("kind", kind.name())
                .param("activeOnly", activeOnly)
                .query(JdbcOutcomeReasonStore::mapReason)
                .list();
    }

    /** What the customer is told, per locale. Never the internal name. */
    public Map<String, String> texts(UUID reasonId) {
        Map<String, String> byLocale = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT locale, customer_text FROM ordering.order_outcome_reason_texts
                WHERE reason_id = :id ORDER BY locale
                """)
                .param("id", reasonId)
                .query((row, number) -> Map.entry(row.getString("locale"), row.getString("customer_text")))
                .list()
                .forEach(entry -> byLocale.put(entry.getKey(), entry.getValue()));
        return byLocale;
    }

    private static final String SELECT_REASON = """
            SELECT id, tenant_id, kind, system_category, internal_name, stock_disposition,
                   liability_party, customer_refund, allowed_fulfillment_modes, status,
                   version, created_at, updated_at
            FROM ordering.order_outcome_reasons""";

    private static ReasonRow mapReason(ResultSet row, int number) throws SQLException {
        return new ReasonRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                OutcomeReasonKind.valueOf(row.getString("kind")),
                row.getString("system_category"),
                row.getString("internal_name"),
                row.getString("stock_disposition"),
                row.getString("liability_party"),
                row.getString("customer_refund"),
                modes(row.getArray("allowed_fulfillment_modes")),
                row.getString("status"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /**
     * A SQL array to a list, distinguishing empty from absent.
     *
     * <p>A completion reason always has at least one mode and a cancellation
     * reason has none at all, and returning an empty list for both would make
     * "valid for no mode" and "modes do not apply" the same value.
     */
    private static List<String> modes(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add(String.valueOf(value));
        }
        return List.copyOf(values);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record NewReason(
            UUID id,
            UUID tenantId,
            OutcomeReasonKind kind,
            String systemCategory,
            String internalName,
            String stockDisposition,
            String liabilityParty,
            String customerRefund,
            List<String> allowedFulfillmentModes,
            Instant createdAt) {}

    public record ReasonRow(
            UUID id,
            UUID tenantId,
            OutcomeReasonKind kind,
            String systemCategory,
            String internalName,
            String stockDisposition,
            String liabilityParty,
            String customerRefund,
            List<String> allowedFulfillmentModes,
            String status,
            int version,
            Instant createdAt,
            Instant updatedAt) {}
}
