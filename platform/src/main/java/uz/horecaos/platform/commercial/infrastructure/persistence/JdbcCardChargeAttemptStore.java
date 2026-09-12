package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
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

    /**
     * Records that this attempt is about to be made, at this amount and
     * under this card token -- pinned here so a later reuse retries under
     * the same token the id was minted with, never one read fresh off
     * {@code commercial.tenant_billing} at reuse time.
     *
     * @param cardTokenReference null exactly when the tenant has no token on
     *                           file yet; the adapter is handed that as it
     *                           stands (ADR 0095)
     */
    public void begin(
            UUID attemptId,
            UUID tenantId,
            UUID statementId,
            long amountMinor,
            String currency,
            @Nullable String cardTokenReference,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO commercial.card_charge_attempts (
                            id, tenant_id, statement_id, amount_minor, currency, card_token_reference,
                            outcome, attempted_at)
                        VALUES (:id, :tenantId, :statementId, :amount, :currency, :cardTokenReference, 'PENDING', :now)
                        """)
                .param("id", attemptId)
                .param("tenantId", tenantId)
                .param("statementId", statementId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("cardTokenReference", cardTokenReference)
                .param("now", utc(now))
                .update();
    }

    /**
     * The unresolved attempt already on file for this exact statement, if
     * one exists (ADR 0095, V0222).
     *
     * <p>Called under {@code wallet.lockBilling}, which serialises callers
     * for one tenant, so two attempts for the same tenant cannot both reach
     * this query believing it empty and then both insert — {@link #begin}'s
     * caller either sees the winner here, or wins the insert itself and the
     * loser hits V0222's unique index instead. Either way, one PENDING
     * attempt per statement is the most that ever exists.
     */
    public Optional<PendingAttempt> findPending(UUID tenantId, UUID statementId) {
        return jdbc.sql("""
                        SELECT id, amount_minor, currency, card_token_reference
                          FROM commercial.card_charge_attempts
                         WHERE tenant_id = :tenantId AND statement_id = :statementId AND outcome = 'PENDING'
                        """)
                .param("tenantId", tenantId)
                .param("statementId", statementId)
                .query((row, number) -> new PendingAttempt(
                        row.getObject("id", UUID.class),
                        row.getLong("amount_minor"),
                        row.getString("currency"),
                        row.getString("card_token_reference")))
                .optional();
    }

    /**
     * Records what the provider answered.
     *
     * @param providerDetail the provider's own reference on a success or its
     *                       reason code on a decline; never a token and never a
     *                       card number (ADR 0028)
     * @return whether this call is the one that resolved the attempt —
     *         {@code false} means a racing settlement pass already settled
     *         it (V0222 lets at most one attempt be PENDING per statement,
     *         but two callers can each hold a reference to the same one and
     *         both ask the provider), so the caller must record nothing a
     *         second time for it
     */
    public boolean settle(UUID attemptId, String outcome, @Nullable String providerDetail, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE commercial.card_charge_attempts
                           SET outcome = :outcome, provider_detail = :detail, settled_at = :now
                         WHERE id = :id AND outcome = 'PENDING'
                        """)
                .param("id", attemptId)
                .param("outcome", outcome)
                .param("detail", providerDetail)
                .param("now", utc(now))
                .update();
        return rows > 0;
    }

    /**
     * Moves an attempt from {@code SUPERSEDED} to {@code SUCCEEDED}: the
     * provider's real answer for a charge that was still outstanding when the
     * row was superseded out from under it (ADR 0095) has arrived after the
     * fact, and it was a genuine charge that needs to be on the books.
     *
     * <p>Never touches a row in any other state — {@code WHERE outcome =
     * 'SUPERSEDED'} gives this the same single-resolution guarantee {@link
     * #settle} gives a {@code PENDING} row, entered through the door a
     * superseded row leaves open instead of the one a pending row does.
     *
     * @param providerDetail the provider's own reference; never a token and
     *                       never a card number (ADR 0028)
     * @return whether this call is the one that resolved it — {@code false}
     *         means a racing report already moved this exact row to
     *         {@code SUCCEEDED}, so the caller must record nothing a second
     *         time for it, exactly as a duplicate {@link #settle} answer does
     */
    public boolean settleSupersededSuccess(UUID attemptId, String providerDetail, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE commercial.card_charge_attempts
                           SET outcome = 'SUCCEEDED', provider_detail = :detail, settled_at = :now
                         WHERE id = :id AND outcome = 'SUPERSEDED'
                        """)
                .param("id", attemptId)
                .param("detail", providerDetail)
                .param("now", utc(now))
                .update();
        return rows > 0;
    }

    /**
     * This attempt's row exactly as it stands, whatever its outcome — unlike
     * {@link #findPending}, never filtered to {@code PENDING}.
     *
     * <p>Exists so a caller whose {@link #settle} call found the row already
     * resolved can tell apart <em>why</em>: a racing pass reporting the same
     * true answer a second time ({@code SUCCEEDED} already), a genuine charge
     * that landed after the row was superseded out from under it while its
     * own provider call was still outstanding ({@code SUPERSEDED}), or an
     * answer that no longer matters because a racing pass already recorded a
     * different one ({@code FAILED} or {@code NOT_CONFIGURED}).
     */
    public Optional<AttemptRecord> find(UUID tenantId, UUID attemptId) {
        return jdbc.sql("""
                        SELECT outcome, card_token_reference, amount_minor, currency
                          FROM commercial.card_charge_attempts
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("id", attemptId)
                .query((row, number) -> new AttemptRecord(
                        row.getString("outcome"),
                        row.getString("card_token_reference"),
                        row.getLong("amount_minor"),
                        row.getString("currency")))
                .optional();
    }

    /**
     * The most recently attempted row this tenant has on file for this
     * statement, other than the one named.
     *
     * <p>Used only to enrich {@code commercial.wallet.card_charge_after_supersede}
     * with the fresh attempt's id alongside the superseded one's: when a
     * superseded attempt's late outcome is being recorded, this is almost
     * always {@code supersedeAndMintFresh}'s own replacement for it, because a
     * statement already paid off never has a further attempt minted against
     * it. Ordered on {@code ix_card_charge_attempt_statement} (V0214), so this
     * is a plain index scan, not a table scan.
     */
    public Optional<UUID> findMostRecentOtherAttempt(UUID tenantId, UUID statementId, UUID excludingAttemptId) {
        return jdbc.sql("""
                        SELECT id FROM commercial.card_charge_attempts
                         WHERE tenant_id = :tenantId AND statement_id = :statementId AND id <> :excluding
                         ORDER BY attempted_at DESC
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("statementId", statementId)
                .param("excluding", excludingAttemptId)
                .query(UUID.class)
                .optional();
    }

    /** An unresolved attempt already on file, as {@link #begin} left it. */
    public record PendingAttempt(
            UUID id,
            long amountMinor,
            String currency,
            @Nullable String cardTokenReference) {}

    /** One attempt's row exactly as it stands, whatever its outcome — see {@link #find}. */
    public record AttemptRecord(
            String outcome, @Nullable String cardTokenReference, long amountMinor, String currency) {}

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
