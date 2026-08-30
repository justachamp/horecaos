package uz.qoida.platform.payments.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.payments.domain.PaymentTransaction;
import uz.qoida.platform.payments.domain.PaymentTransactionType;
import uz.qoida.platform.payments.domain.ProviderEvidence;
import uz.qoida.platform.payments.domain.SomAmount;

import static uz.qoida.platform.payments.infrastructure.persistence.PaymentTimestamps.instant;
import static uz.qoida.platform.payments.infrastructure.persistence.PaymentTimestamps.utc;

/**
 * The append-only record of what providers said happened (ADR 0013).
 *
 * <p>There is no update method here and there will not be one: the grant block in
 * V0027 withholds UPDATE and DELETE from the application role, so a mistaken
 * rewrite of a financial fact fails at the database rather than in review.
 */
@Repository
public class JdbcPaymentTransactionStore {

    private final JdbcClient jdbc;

    public JdbcPaymentTransactionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends a transaction, or does nothing if this exact one is already here.
     *
     * <p>{@code ON CONFLICT DO NOTHING} against
     * {@code uq_payment_transaction_occurrence} is the whole replay story. Payme
     * sends every mutating method at least twice by design and requires the second
     * response to match the first; Click retries Complete until it is answered.
     * Both land here and insert nothing the second time, and the caller derives its
     * answer from the persisted attempt state rather than from a stored response
     * body — which is what stops a replayed cancel from overwriting a cancel time
     * or rewriting a Payme {@code -2} back to a {@code -1}.
     *
     * @return true when this call was the one that recorded the fact
     */
    public boolean append(PaymentTransaction transaction) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", transaction.id());
        parameters.put("tenantId", transaction.tenantId());
        parameters.put("intentId", transaction.intentId());
        parameters.put("attemptId", transaction.attemptId());
        parameters.put("type", transaction.type().name());
        parameters.put("amount", transaction.amount().value());
        parameters.put("currency", transaction.amount().currency());
        parameters.put("providerReference", transaction.providerReference());
        parameters.put("providerState",
                transaction.evidence() == null ? null : transaction.evidence().state());
        parameters.put("providerReason",
                transaction.evidence() == null ? null : transaction.evidence().reason());
        parameters.put("occurredAt", utc(transaction.occurredAt()));
        parameters.put("recordedAt", utc(transaction.recordedAt()));
        parameters.put("protectedRequest", transaction.protectedRequestReference());
        parameters.put("protectedResponse", transaction.protectedResponseReference());

        int inserted = jdbc.sql("""
                INSERT INTO payments.payment_transactions (
                    id, tenant_id, intent_id, attempt_id, transaction_type,
                    amount_minor, currency, provider_reference, provider_state, provider_reason,
                    occurred_at, recorded_at,
                    protected_request_reference, protected_response_reference)
                VALUES (
                    :id, :tenantId, :intentId, :attemptId, :type,
                    :amount, :currency, :providerReference, :providerState, :providerReason,
                    :occurredAt, :recordedAt,
                    :protectedRequest, :protectedResponse)
                ON CONFLICT ON CONSTRAINT uq_payment_transaction_occurrence DO NOTHING
                """)
                .params(parameters)
                .update();

        return inserted == 1;
    }

    public List<PaymentTransaction> listForAttempt(UUID tenantId, UUID attemptId) {
        return jdbc.sql("""
                SELECT id, tenant_id, intent_id, attempt_id, transaction_type,
                       amount_minor, currency, provider_reference, provider_state, provider_reason,
                       occurred_at, recorded_at,
                       protected_request_reference, protected_response_reference
                FROM payments.payment_transactions
                WHERE tenant_id = :tenantId AND attempt_id = :attemptId
                ORDER BY occurred_at, recorded_at
                """)
                .param("tenantId", tenantId).param("attemptId", attemptId)
                .query(JdbcPaymentTransactionStore::map)
                .list();
    }

    /**
     * What the intent has actually been credited, from the transactions rather than
     * from a running total on the intent.
     *
     * <p>A cached balance and an append-only ledger are two answers to one question
     * with no defined winner when they disagree, and this is the side of that
     * choice a settlement dispute can be argued from.
     */
    public long capturedMinor(UUID tenantId, UUID intentId) {
        Long captured = jdbc.sql("""
                SELECT COALESCE(SUM(amount_minor), 0)
                FROM payments.payment_transactions
                WHERE tenant_id = :tenantId AND intent_id = :intentId
                  AND transaction_type = 'CAPTURE'
                """)
                .param("tenantId", tenantId).param("intentId", intentId)
                .query(Long.class)
                .single();
        return captured == null ? 0L : captured;
    }

    /** What has been given back, by reversal or by a back-recorded console refund. */
    public long returnedMinor(UUID tenantId, UUID intentId) {
        Long returned = jdbc.sql("""
                SELECT COALESCE(SUM(amount_minor), 0)
                FROM payments.payment_transactions
                WHERE tenant_id = :tenantId AND intent_id = :intentId
                  AND transaction_type IN ('REVERSE', 'REFUND')
                """)
                .param("tenantId", tenantId).param("intentId", intentId)
                .query(Long.class)
                .single();
        return returned == null ? 0L : returned;
    }

    private static PaymentTransaction map(ResultSet row, int rowNumber) throws SQLException {
        String providerState = row.getString("provider_state");
        return new PaymentTransaction(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("intent_id", UUID.class),
                row.getObject("attempt_id", UUID.class),
                PaymentTransactionType.valueOf(row.getString("transaction_type")),
                new SomAmount(row.getLong("amount_minor"), row.getString("currency")),
                row.getString("provider_reference"),
                providerState == null ? null : new ProviderEvidence(
                        providerState, row.getString("provider_reason"),
                        instant(row, "recorded_at")),
                instant(row, "occurred_at"),
                instant(row, "recorded_at"),
                row.getString("protected_request_reference"),
                row.getString("protected_response_reference"));
    }
}
