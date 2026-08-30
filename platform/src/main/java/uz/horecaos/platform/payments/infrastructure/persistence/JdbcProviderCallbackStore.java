package uz.horecaos.platform.payments.infrastructure.persistence;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.payments.domain.CallbackKind;
import uz.horecaos.platform.payments.domain.PaymentProviderType;

import static uz.horecaos.platform.payments.infrastructure.persistence.PaymentTimestamps.utc;

/**
 * The provider-shaped inbox (ADR 0005, ADR 0013).
 *
 * <p>Every inbound arrival is recorded, including the ones whose signature failed.
 * On Click that is not a nicety: the SHOP API carries no auth header at all, so an
 * MD5 over a secret-prefixed concatenation is the only thing standing between an
 * anonymous form post and a credited order, and a burst of {@code -1 SIGN CHECK
 * FAILED!} is the sole available warning that a key rotation was missed or that
 * someone is probing.
 */
@Repository
public class JdbcProviderCallbackStore {

    private final JdbcClient jdbc;

    public JdbcProviderCallbackStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one arrival.
     *
     * <p>Deduplicated on the provider reference <em>and</em> the body hash. Two
     * callbacks with one reference and different bodies are a real event worth
     * seeing rather than a duplicate to swallow — on Click the same
     * {@code click_trans_id} may legitimately arrive on both Prepare and Complete
     * with different fields.
     *
     * @return true when this arrival had not been seen before
     */
    public boolean record(UUID id, UUID tenantId, PaymentProviderType providerType,
            UUID merchantBindingId, CallbackKind kind, String providerReference,
            String requestBodyHash, boolean signatureValid, UUID attemptId,
            String responseCode, Instant receivedAt,
            String protectedRequestReference, String protectedResponseReference) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        parameters.put("tenantId", tenantId);
        parameters.put("providerType", providerType.name());
        parameters.put("bindingId", merchantBindingId);
        parameters.put("kind", kind.name());
        parameters.put("providerReference", providerReference);
        parameters.put("bodyHash", requestBodyHash);
        parameters.put("signatureValid", signatureValid);
        parameters.put("attemptId", attemptId);
        parameters.put("responseCode", responseCode);
        parameters.put("receivedAt", utc(receivedAt));
        parameters.put("protectedRequest", protectedRequestReference);
        parameters.put("protectedResponse", protectedResponseReference);

        int inserted = jdbc.sql("""
                INSERT INTO payments.provider_callbacks (
                    id, tenant_id, provider_type, merchant_binding_id, callback_kind,
                    provider_reference, request_body_hash, signature_valid, attempt_id,
                    response_code, received_at,
                    protected_request_reference, protected_response_reference)
                VALUES (
                    :id, :tenantId, :providerType, :bindingId, :kind,
                    :providerReference, :bodyHash, :signatureValid, :attemptId,
                    :responseCode, :receivedAt,
                    :protectedRequest, :protectedResponse)
                ON CONFLICT ON CONSTRAINT uq_provider_callback_delivery DO NOTHING
                """)
                .params(parameters)
                .update();

        return inserted == 1;
    }

    /**
     * How many signatures have failed on this binding lately.
     *
     * <p>Read by the alert ADR 0013 asks for. The window is the caller's, because
     * what counts as a burst on a busy production service and on a sandbox are
     * different numbers.
     */
    public int signatureFailuresSince(UUID tenantId, UUID merchantBindingId, Instant since) {
        Integer failures = jdbc.sql("""
                SELECT count(*)
                FROM payments.provider_callbacks
                WHERE tenant_id = :tenantId AND merchant_binding_id = :bindingId
                  AND NOT signature_valid AND received_at >= :since
                """)
                .param("tenantId", tenantId).param("bindingId", merchantBindingId)
                .param("since", utc(since))
                .query(Integer.class)
                .single();
        return failures == null ? 0 : failures;
    }
}
