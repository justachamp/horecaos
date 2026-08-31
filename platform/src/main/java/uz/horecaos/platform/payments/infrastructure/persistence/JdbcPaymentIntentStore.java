package uz.horecaos.platform.payments.infrastructure.persistence;

import static uz.horecaos.platform.payments.infrastructure.persistence.PaymentTimestamps.instant;
import static uz.horecaos.platform.payments.infrastructure.persistence.PaymentTimestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentMethod;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.SomAmount;

/**
 * Payment intent persistence (ADR 0013).
 *
 * <p>Two rules run through every statement, the same two that run through
 * ordering's store. The tenant predicate is always inside the query, because an
 * intent id is a UUID that arrives from a client and a lookup matching on it alone
 * would serve another tenant's financial record. And every state change is a
 * conditional UPDATE naming the status and version it expects, so a checkout
 * retry, a provider callback, and an expiry sweep arriving together produce one
 * winner and two callers who are told what actually happened.
 */
@Repository
public class JdbcPaymentIntentStore {

    private static final String SELECT = """
            SELECT id, tenant_id, order_id, brand_id, location_id, tender_id, legal_entity_id,
                   tender, payment_method_code, provider_type, requested_amount_minor, currency,
                   status, capture_timing, idempotency_key, version, created_at, settled_at
            FROM payments.payment_intents
            """;

    private final JdbcClient jdbc;

    public JdbcPaymentIntentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PaymentIntent intent) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", intent.id());
        parameters.put("tenantId", intent.tenantId());
        parameters.put("orderId", intent.orderId());
        parameters.put("brandId", intent.brandId());
        parameters.put("locationId", intent.locationId());
        parameters.put("tenderId", intent.tenderId());
        parameters.put("legalEntityId", intent.legalEntityId());
        parameters.put("tender", intent.tender().name());
        parameters.put("methodCode", intent.method().code());
        parameters.put(
                "providerType",
                intent.providerType() == null ? null : intent.providerType().name());
        parameters.put("amount", intent.amount().value());
        parameters.put("currency", intent.amount().currency());
        parameters.put("status", intent.status().name());
        parameters.put("captureTiming", intent.captureTiming().name());
        parameters.put("idempotencyKey", intent.idempotencyKey());
        parameters.put("createdAt", utc(intent.createdAt()));

        // A HashMap rather than Map.of: the tender, the legal entity and the
        // provider are all legitimately null, and Map.of throws on a null value.
        jdbc.sql("""
                INSERT INTO payments.payment_intents (
                    id, tenant_id, order_id, brand_id, location_id, tender_id, legal_entity_id,
                    tender, payment_method_code, provider_type, requested_amount_minor, currency,
                    status, capture_timing, idempotency_key, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :orderId, :brandId, :locationId, :tenderId, :legalEntityId,
                    :tender, :methodCode, :providerType, :amount, :currency,
                    :status, :captureTiming, :idempotencyKey, 1, :createdAt, :createdAt)
                """).params(parameters).update();
    }

    public Optional<PaymentIntent> find(UUID tenantId, UUID intentId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", intentId)
                .query(JdbcPaymentIntentStore::map)
                .optional();
    }

    /**
     * The ADR 0031 idempotent replay. A repeated checkout returns the intent it
     * created the first time rather than creating a second one.
     */
    public Optional<PaymentIntent> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND idempotency_key = :key")
                .param("tenantId", tenantId)
                .param("key", idempotencyKey)
                .query(JdbcPaymentIntentStore::map)
                .optional();
    }

    /**
     * The live intent for an order, if there is one.
     *
     * <p>Bounded to the statuses {@code ux_payment_intent_live_per_order} covers,
     * so this query and that index cannot disagree about what "live" means.
     */
    public Optional<PaymentIntent> findLiveForOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                   AND status IN ('PENDING', 'AUTHORIZING', 'PAID')
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcPaymentIntentStore::map)
                .optional();
    }

    /**
     * Moves an intent from one status to another, and only from that status.
     *
     * @return the new version when this caller won, or empty when it lost
     */
    public Optional<Integer> transition(
            UUID tenantId,
            UUID intentId,
            PaymentIntentStatus from,
            PaymentIntentStatus to,
            int expectedVersion,
            Instant now) {
        return jdbc.sql("""
                UPDATE payments.payment_intents
                SET status = :to,
                    version = version + 1,
                    updated_at = :now,
                    settled_at = CASE WHEN :open THEN settled_at ELSE :now END
                WHERE tenant_id = :tenantId AND id = :id
                  AND status = :from AND version = :expectedVersion
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", intentId)
                .param("from", from.name())
                .param("to", to.name())
                .param("open", to.open())
                .param("expectedVersion", expectedVersion)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    private static PaymentIntent map(ResultSet row, int rowNumber) throws SQLException {
        String providerType = row.getString("provider_type");
        String methodCode = row.getString("payment_method_code");
        return new PaymentIntent(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("tender_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                PaymentTender.valueOf(row.getString("tender")),
                PaymentMethod.fromCode(methodCode)
                        .orElseThrow(() -> new IllegalStateException(
                                "Stored payment method " + methodCode + " is not one this build implements")),
                providerType == null ? null : PaymentProviderType.valueOf(providerType),
                new SomAmount(row.getLong("requested_amount_minor"), row.getString("currency")),
                PaymentIntentStatus.valueOf(row.getString("status")),
                CaptureTiming.valueOf(row.getString("capture_timing")),
                row.getString("idempotency_key"),
                // getInt answers 0 for SQL NULL, which would read as a version this
                // row can never have. The column is NOT NULL, so this is belt and
                // braces; the pattern matters more than the column.
                row.getObject("version", Integer.class),
                Objects.requireNonNull(instant(row, "created_at"), "payment_intents.created_at is NOT NULL"),
                instant(row, "settled_at"));
    }
}
