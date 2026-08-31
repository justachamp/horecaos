package uz.horecaos.platform.payments.infrastructure.persistence;

import static uz.horecaos.platform.payments.infrastructure.persistence.PaymentTimestamps.instant;
import static uz.horecaos.platform.payments.infrastructure.persistence.PaymentTimestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.payments.domain.ProviderEvidence;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.domain.UncertaintyResolver;

/**
 * Payment attempt persistence (ADR 0013).
 *
 * <p>This is where the double charge is prevented, and it is prevented by the
 * database rather than by anything in this class. {@code
 * ux_payment_attempt_open_per_intent} permits at most one attempt per intent in
 * any non-terminal state, so two concurrent requests to charge one order become
 * one insert and one {@link DuplicateKeyException}. The read-then-decide-then-write
 * alternative is the classic path to charging a customer twice, and concurrent
 * Payme {@code CreateTransaction} calls for one order are exactly what it fails
 * under.
 *
 * <p>V0045 widened that index from the three live states to every non-terminal
 * one, because the outbound half has the same failure with a different shape: a
 * customer who abandons a Click payment link and comes back must be handed the
 * link they already have, not a second attempt with a second
 * {@code merchant_trans_id} while the first is still payable.
 *
 * <p>Every transition is a conditional UPDATE naming the status it expects, for
 * the same reason.
 */
@Repository
public class JdbcPaymentAttemptStore {

    private static final String SELECT = """
            SELECT id, tenant_id, intent_id, provider_type, merchant_binding_id,
                   merchant_trans_id, business_date, external_payment_id, external_document_id,
                   requested_amount_minor, currency, status, presentation_kind, presented_at,
                   provider_state, provider_reason, provider_state_recorded_at,
                   provider_created_at, expires_at, failure_code,
                   uncertain_since, uncertain_resolver, uncertain_deadline,
                   uncertain_resolution_attempts, uncertain_resolved_at,
                   version, created_at, settled_at
            FROM payments.payment_attempts
            """;

    private final JdbcClient jdbc;

    public JdbcPaymentAttemptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the attempt before anything is asked of a provider.
     *
     * <p>The order matters and is not negotiable. {@code merchant_trans_id} and
     * {@code business_date} are what an uncertain outcome is resolved with, and a
     * mutating call made before they are committed produces a charge nobody can
     * ask about afterwards.
     *
     * @throws DuplicateKeyException when another attempt against this intent
     *                               already holds money or holds a question about
     *                               money. The caller resolves that one rather
     *                               than starting a second
     */
    public void insert(PaymentAttempt attempt) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", attempt.id());
        parameters.put("tenantId", attempt.tenantId());
        parameters.put("intentId", attempt.intentId());
        parameters.put("providerType", attempt.providerType().name());
        parameters.put("bindingId", attempt.merchantBindingId());
        parameters.put("merchantTransId", attempt.merchantTransId());
        parameters.put("businessDate", attempt.businessDate());
        parameters.put("amount", attempt.amount().value());
        parameters.put("currency", attempt.amount().currency());
        parameters.put("status", attempt.status().name());
        parameters.put("providerCreatedAt", utc(attempt.providerCreatedAt()));
        parameters.put("expiresAt", utc(attempt.expiresAt()));
        parameters.put("createdAt", utc(attempt.createdAt()));

        jdbc.sql("""
                INSERT INTO payments.payment_attempts (
                    id, tenant_id, intent_id, provider_type, merchant_binding_id,
                    merchant_trans_id, business_date, requested_amount_minor, currency, status,
                    provider_created_at, expires_at, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :intentId, :providerType, :bindingId,
                    :merchantTransId, :businessDate, :amount, :currency, :status,
                    :providerCreatedAt, :expiresAt, 1, :createdAt, :createdAt)
                """).params(parameters).update();
    }

    public Optional<PaymentAttempt> find(UUID tenantId, UUID attemptId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", attemptId)
                .query(JdbcPaymentAttemptStore::map)
                .optional();
    }

    /**
     * The lookup both inbound surfaces use.
     *
     * <p>Click's callback carries {@code merchant_trans_id} and Payme's carries it
     * as {@code account.order_id}, so one query serves both. The provider is in the
     * predicate because the id space is per provider, not global.
     */
    public Optional<PaymentAttempt> findByMerchantTransId(
            UUID tenantId, PaymentProviderType providerType, String merchantTransId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND provider_type = :providerType
                   AND merchant_trans_id = :merchantTransId
                """)
                .param("tenantId", tenantId)
                .param("providerType", providerType.name())
                .param("merchantTransId", merchantTransId)
                .query(JdbcPaymentAttemptStore::map)
                .optional();
    }

    /** The Payme lookup by the transaction id Payme minted. */
    public Optional<PaymentAttempt> findByExternalPaymentId(
            UUID tenantId, PaymentProviderType providerType, String externalPaymentId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND provider_type = :providerType
                   AND external_payment_id = :externalPaymentId
                """)
                .param("tenantId", tenantId)
                .param("providerType", providerType.name())
                .param("externalPaymentId", externalPaymentId)
                .query(JdbcPaymentAttemptStore::map)
                .optional();
    }

    public Optional<PaymentAttempt> findLiveForIntent(UUID tenantId, UUID intentId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND intent_id = :intentId
                   AND status IN ('RESERVED', 'CAPTURED', 'UNCERTAIN')
                """)
                .param("tenantId", tenantId)
                .param("intentId", intentId)
                .query(JdbcPaymentAttemptStore::map)
                .optional();
    }

    /**
     * The one attempt an intent may currently have, whatever state it is in.
     *
     * <p>The read side of {@code ux_payment_attempt_open_per_intent}, and the
     * question a returning customer asks: is there already an attempt against this
     * money? {@link #findLiveForIntent} answers a narrower one — is there an
     * attempt holding money — and using it here is what would let an abandoned
     * {@code PRESENTED} checkout acquire a second payable link.
     *
     * <p>The index guarantees at most one row, so this cannot silently pick a
     * winner from several.
     */
    public Optional<PaymentAttempt> findOpenForIntent(UUID tenantId, UUID intentId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND intent_id = :intentId
                   AND status NOT IN ('CANCELLED', 'EXPIRED', 'REVERSED', 'FAILED')
                """)
                .param("tenantId", tenantId)
                .param("intentId", intentId)
                .query(JdbcPaymentAttemptStore::map)
                .optional();
    }

    /**
     * How many times a surface has been handed to a customer for this attempt.
     *
     * <p>Its own read rather than a component on {@link PaymentAttempt}: the count
     * decides nothing in the domain — no transition consults it, no invariant
     * depends on it — and it is evidence a support conversation and the settlement
     * review ask for. Widening the aggregate for a number nothing in it uses is how
     * a record grows a field per report.
     */
    public int presentationCount(UUID tenantId, UUID attemptId) {
        return jdbc.sql("""
                SELECT presentation_count FROM payments.payment_attempts
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", attemptId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    public List<PaymentAttempt> listForIntent(UUID tenantId, UUID intentId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND intent_id = :intentId
                 ORDER BY created_at
                """)
                .param("tenantId", tenantId)
                .param("intentId", intentId)
                .query(JdbcPaymentAttemptStore::map)
                .list();
    }

    /** The ADR 0013 operations queue, deadline first. */
    public List<PaymentAttempt> listUncertain(UUID tenantId, int limit) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND status = 'UNCERTAIN'
                 ORDER BY uncertain_deadline
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query(JdbcPaymentAttemptStore::map)
                .list();
    }

    /**
     * Reservations whose window has closed.
     *
     * <p>The sweep exists because lazy expiry never fires for a checkout the
     * customer abandoned: Payme's own templates expire a transaction on the next
     * inbound call, and for an abandoned tab that call never comes, so the
     * reservation would hold stock forever. Not scoped by tenant on purpose — it is
     * a platform job, and the tenant travels with each row it acts on.
     */
    public List<PaymentAttempt> listExpiredReservations(Instant now, int limit) {
        return jdbc.sql(SELECT + """
                 WHERE status = 'RESERVED' AND expires_at IS NOT NULL AND expires_at < :now
                 ORDER BY expires_at
                 LIMIT :limit
                """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(JdbcPaymentAttemptStore::map)
                .list();
    }

    /**
     * Moves an attempt from one status to another, and only from that status.
     *
     * <p>The provider's own state travels with the transition rather than in a
     * second statement: evidence written separately from the state it explains is
     * evidence that can be missing when the state is not.
     *
     * @return the new version when this caller won, or empty when it lost
     */
    public Optional<Integer> transition(
            UUID tenantId,
            UUID attemptId,
            PaymentAttemptStatus from,
            PaymentAttemptStatus to,
            @Nullable ProviderEvidence evidence,
            @Nullable String externalPaymentId,
            @Nullable String externalDocumentId,
            @Nullable String failureCode,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", attemptId);
        parameters.put("from", from.name());
        parameters.put("to", to.name());
        parameters.put("open", !to.terminal() && to != PaymentAttemptStatus.CAPTURED);
        parameters.put("providerState", evidence == null ? null : evidence.state());
        parameters.put("providerReason", evidence == null ? null : evidence.reason());
        parameters.put("providerRecordedAt", evidence == null ? null : utc(evidence.recordedAt()));
        parameters.put("externalPaymentId", externalPaymentId);
        parameters.put("externalDocumentId", externalDocumentId);
        parameters.put("failureCode", failureCode);
        parameters.put("now", utc(now));

        // COALESCE on the identifiers, because a later callback that omits one must
        // not erase what an earlier one established. Click's Complete carries
        // click_paydoc_id and its Prepare does not.
        return jdbc.sql("""
                UPDATE payments.payment_attempts
                SET status = :to,
                    version = version + 1,
                    updated_at = :now,
                    settled_at = CASE WHEN :open THEN settled_at ELSE :now END,
                    provider_state = COALESCE(:providerState, provider_state),
                    provider_reason = COALESCE(:providerReason, provider_reason),
                    provider_state_recorded_at =
                        COALESCE(CAST(:providerRecordedAt AS timestamptz), provider_state_recorded_at),
                    external_payment_id = COALESCE(:externalPaymentId, external_payment_id),
                    external_document_id = COALESCE(:externalDocumentId, external_document_id),
                    failure_code = COALESCE(:failureCode, failure_code),
                    uncertain_resolved_at = CASE
                        WHEN status = 'UNCERTAIN' AND :to <> 'UNCERTAIN' THEN :now
                        ELSE uncertain_resolved_at END
                WHERE tenant_id = :tenantId AND id = :id AND status = :from
                RETURNING version
                """).params(parameters).query(Integer.class).optional();
    }

    /**
     * Records that an outcome is unknown, together with the obligation that
     * carries.
     *
     * <p>A separate statement from {@link #transition} because it writes the
     * resolver and the deadline, and because it must be reachable from any status:
     * a response can be lost at any point, including the response to a reversal of
     * something already captured.
     */
    public Optional<Integer> markUncertain(
            UUID tenantId,
            UUID attemptId,
            PaymentAttemptStatus from,
            UncertaintyResolver resolver,
            Instant since,
            Instant deadline,
            @Nullable String failureCode) {
        return jdbc.sql("""
                UPDATE payments.payment_attempts
                SET status = 'UNCERTAIN',
                    version = version + 1,
                    updated_at = :since,
                    settled_at = NULL,
                    uncertain_since = COALESCE(uncertain_since, :since),
                    uncertain_resolver = :resolver,
                    uncertain_deadline = :deadline,
                    uncertain_resolved_at = NULL,
                    failure_code = COALESCE(:failureCode, failure_code)
                WHERE tenant_id = :tenantId AND id = :id AND status = :from
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", attemptId)
                .param("from", from.name())
                .param("resolver", resolver.name())
                .param("since", utc(since))
                .param("deadline", utc(deadline))
                .param("failureCode", failureCode)
                .query(Integer.class)
                .optional();
    }

    /**
     * Counts one attempt at resolving an uncertainty, and re-points the resolver
     * when the automated path has run out.
     *
     * <p>Separate from the resolution itself because a resolver that answers "still
     * in flight" has not resolved anything, and the count is what eventually turns
     * a Click {@code status_by_mti} that keeps reporting nothing into an operations
     * exception rather than an infinite poll.
     */
    public void recordResolutionAttempt(UUID tenantId, UUID attemptId, UncertaintyResolver resolver, Instant now) {
        jdbc.sql("""
                UPDATE payments.payment_attempts
                SET uncertain_resolution_attempts = uncertain_resolution_attempts + 1,
                    uncertain_resolver = :resolver,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'UNCERTAIN'
                """)
                .param("tenantId", tenantId)
                .param("id", attemptId)
                .param("resolver", resolver.name())
                .param("now", utc(now))
                .update();
    }

    /**
     * Records what the customer was shown. Never moves money and never a state.
     *
     * <p>{@code presentation_count} counts, rather than a boolean recording that a
     * presentation happened, because the interesting customer is the one who came
     * back: a count above one is an abandoned checkout that was re-presented, which
     * is a fact worth having in the row when somebody asks why an order sat in
     * {@code PAYMENT_AUTHORIZING} for an hour.
     */
    public void recordPresentation(
            UUID tenantId,
            UUID attemptId,
            PresentationKind kind,
            @Nullable String externalInvoiceId,
            @Nullable Instant expiresAt,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", attemptId);
        parameters.put("kind", kind.name());
        parameters.put("externalInvoiceId", externalInvoiceId);
        parameters.put("expiresAt", utc(expiresAt));
        parameters.put("now", utc(now));

        // The invoice id goes to its own column and never to external_payment_id.
        // Click's invoice_id names a request pushed to a phone; the payment_id the
        // status, reversal and fiscal calls take arrives later and only if the
        // customer accepts. Writing the first where the second belongs would put it
        // under the unique index that identifies a payment, and every later
        // transition's COALESCE would then preserve it against the real one.
        jdbc.sql("""
                UPDATE payments.payment_attempts
                SET presentation_kind = :kind,
                    presented_at = :now,
                    presentation_count = presentation_count + 1,
                    external_invoice_id = COALESCE(:externalInvoiceId, external_invoice_id),
                    expires_at = COALESCE(CAST(:expiresAt AS timestamptz), expires_at),
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                """).params(parameters).update();
    }

    /**
     * Records Payme's own creation time and the twelve-hour deadline derived from
     * it.
     *
     * <p>Written from {@code params.time} and never from HorecaOS's clock. Payme's own
     * Java template measures the window from the merchant's creation time, which is
     * wrong by however far apart the two clocks and the two events are — and the
     * consequence of measuring from the wrong one is performing an expired
     * transaction.
     *
     * <p>Predicated on {@code external_payment_id}, so only the caller whose Payme
     * transaction actually claimed the attempt may write its deadline. Two
     * concurrent {@code CreateTransaction} calls for one order carry two different
     * Payme ids and two different {@code params.time} values; the conditional
     * transition picks a winner, but an unconditional write here let the loser
     * stamp its own creation time and deadline onto the winner's row before being
     * refused — and its refusal does not roll the write back, because the dispatch
     * is deliberately {@code noRollbackFor} an RPC exception. An attempt created at
     * T and claimed by the winner then carried the loser's T+11h, so
     * {@code PerformTransaction} would perform it up to eleven hours after Payme
     * had already cancelled it under reason 4.
     *
     * @return whether this caller owns the attempt and its deadline was written
     */
    public boolean recordProviderCreation(
            UUID tenantId, UUID attemptId, String externalPaymentId, Instant providerCreatedAt, Instant expiresAt) {
        return jdbc.sql("""
                UPDATE payments.payment_attempts
                SET provider_created_at = :providerCreatedAt,
                    expires_at = :expiresAt,
                    updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id
                  AND external_payment_id = :externalPaymentId
                """)
                        .param("tenantId", tenantId)
                        .param("id", attemptId)
                        .param("externalPaymentId", externalPaymentId)
                        .param("providerCreatedAt", utc(providerCreatedAt))
                        .param("expiresAt", utc(expiresAt))
                        .update()
                == 1;
    }

    private static PaymentAttempt map(ResultSet row, int rowNumber) throws SQLException {
        String providerState = row.getString("provider_state");
        String presentationKind = row.getString("presentation_kind");
        String resolver = row.getString("uncertain_resolver");
        Instant uncertainSince = instant(row, "uncertain_since");

        return new PaymentAttempt(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("intent_id", UUID.class),
                PaymentProviderType.valueOf(row.getString("provider_type")),
                row.getObject("merchant_binding_id", UUID.class),
                row.getString("merchant_trans_id"),
                row.getObject("business_date", LocalDate.class),
                row.getString("external_payment_id"),
                row.getString("external_document_id"),
                new SomAmount(row.getLong("requested_amount_minor"), row.getString("currency")),
                PaymentAttemptStatus.valueOf(row.getString("status")),
                presentationKind == null ? null : PresentationKind.valueOf(presentationKind),
                providerState == null
                        ? null
                        : new ProviderEvidence(
                                providerState,
                                row.getString("provider_reason"),
                                Objects.requireNonNull(
                                        instant(row, "provider_state_recorded_at"),
                                        "provider_state_recorded_at must be set alongside provider_state")),
                instant(row, "provider_created_at"),
                instant(row, "expires_at"),
                row.getString("failure_code"),
                uncertainSince == null
                        ? null
                        : new PaymentAttempt.Uncertainty(
                                uncertainSince,
                                UncertaintyResolver.valueOf(resolver),
                                Objects.requireNonNull(
                                        instant(row, "uncertain_deadline"),
                                        "uncertain_deadline must be set alongside uncertain_since"),
                                // getInt answers 0 for SQL NULL, which here would be a
                                // plausible count rather than an obvious error.
                                row.getObject("uncertain_resolution_attempts", Integer.class),
                                instant(row, "uncertain_resolved_at")),
                row.getObject("version", Integer.class),
                Objects.requireNonNull(instant(row, "created_at"), "payment_attempts.created_at is NOT NULL"),
                instant(row, "settled_at"));
    }
}
