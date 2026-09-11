package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One attempt to charge a tenant's card, written before the provider is called
 * (ADR 0095, V0214).
 *
 * <p>The row's id is the idempotency key the provider is handed. That is the
 * whole reason it exists: the key used to be the statement id, and the amount
 * charged under it changed between settlement passes — a statement declined at
 * its full due, part-paid by a transfer, then charged for the remainder. A
 * provider that honours keys would replay the stored decline forever, or replay
 * an earlier success for a different amount, and the success path credits the
 * amount it asked for rather than the amount anybody took.
 *
 * <p>Written before the call so the key is durable before anything can be
 * charged under it, and settled after, so a row left {@code PENDING} is exactly
 * the state a real adapter would reconcile: we asked and never learned the
 * answer.
 */
@Repository
public class JdbcCardChargeAttemptStore {

    private final JdbcClient jdbc;

    public JdbcCardChargeAttemptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records that this attempt is about to be made, at this amount. */
    public void begin(UUID attemptId, UUID tenantId, UUID statementId, long amountMinor, String currency, Instant now) {
        jdbc.sql("""
                        INSERT INTO commercial.card_charge_attempts (
                            id, tenant_id, statement_id, amount_minor, currency, outcome, attempted_at)
                        VALUES (:id, :tenantId, :statementId, :amount, :currency, 'PENDING', :now)
                        """)
                .param("id", attemptId)
                .param("tenantId", tenantId)
                .param("statementId", statementId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("now", utc(now))
                .update();
    }

    /**
     * Records what the provider answered.
     *
     * @param providerDetail the provider's own reference on a success or its
     *                       reason code on a decline; never a token and never a
     *                       card number (ADR 0028)
     */
    public void settle(UUID attemptId, String outcome, @Nullable String providerDetail, Instant now) {
        jdbc.sql("""
                        UPDATE commercial.card_charge_attempts
                           SET outcome = :outcome, provider_detail = :detail, settled_at = :now
                         WHERE id = :id AND outcome = 'PENDING'
                        """)
                .param("id", attemptId)
                .param("outcome", outcome)
                .param("detail", providerDetail)
                .param("now", utc(now))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
