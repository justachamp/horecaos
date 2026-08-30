package uz.qoida.platform.payments.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.payments.domain.FiscalDocument;
import uz.qoida.platform.payments.domain.FiscalDocumentType;
import uz.qoida.platform.payments.domain.FiscalStatus;
import uz.qoida.platform.payments.domain.PaymentProviderType;

import static uz.qoida.platform.payments.infrastructure.persistence.PaymentTimestamps.instant;
import static uz.qoida.platform.payments.infrastructure.persistence.PaymentTimestamps.utc;

/**
 * Fiscal document persistence, on the partner path (ADR 0013, ADR 0038).
 *
 * <p>There is no {@code findByOrderId} returning a single document, and that is
 * the point: a Payme {@code PERFORM} and its {@code CANCEL} are two receipts for
 * one order by the provider's own statement, and a correction links to the sale
 * rather than replacing it. Every read here returns a list, so the shape of the
 * API cannot tempt a caller into assuming there is one.
 *
 * <p>The receipt lines are not stored as rows. What is worth keeping is the exact
 * {@code Items} array or {@code detail} object that was <em>sent</em>, behind an
 * ADR 0029 protected reference, because that — and not a reconstruction — is what
 * makes an incorrect receipt explicable a year later.
 */
@Repository
public class JdbcFiscalDocumentStore {

    private static final String SELECT = """
            SELECT id, tenant_id, order_id, legal_entity_id, payment_intent_id,
                   payment_transaction_id, provider_type, document_type, corrects_document_id,
                   status, reason_code, reason_note, external_receipt_id, fiscal_sign,
                   terminal_id, receipt_reference, registered_at, receipt_url,
                   provider_status_code, provider_message, version, created_at
            FROM payments.fiscal_documents
            """;

    private final JdbcClient jdbc;

    public JdbcFiscalDocumentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(FiscalDocument document) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", document.id());
        parameters.put("tenantId", document.tenantId());
        parameters.put("orderId", document.orderId());
        parameters.put("legalEntityId", document.legalEntityId());
        parameters.put("intentId", document.paymentIntentId());
        parameters.put("transactionId", document.paymentTransactionId());
        parameters.put("providerType",
                document.providerType() == null ? null : document.providerType().name());
        parameters.put("documentType", document.documentType().name());
        parameters.put("correctsId", document.correctsDocumentId());
        parameters.put("status", document.status().name());
        parameters.put("reasonCode", document.reasonCode());
        parameters.put("reasonNote", document.reasonNote());
        parameters.put("createdAt", utc(document.createdAt()));

        jdbc.sql("""
                INSERT INTO payments.fiscal_documents (
                    id, tenant_id, order_id, legal_entity_id, payment_intent_id,
                    payment_transaction_id, provider_type, document_type, corrects_document_id,
                    status, reason_code, reason_note, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :orderId, :legalEntityId, :intentId,
                    :transactionId, :providerType, :documentType, :correctsId,
                    :status, :reasonCode, :reasonNote, 1, :createdAt, :createdAt)
                """)
                .params(parameters)
                .update();
    }

    public Optional<FiscalDocument> find(UUID tenantId, UUID documentId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", documentId)
                .query(JdbcFiscalDocumentStore::map)
                .optional();
    }

    /** Every document for an order, oldest first. Plural by design. */
    public List<FiscalDocument> listForOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                 ORDER BY created_at
                """)
                .param("tenantId", tenantId).param("orderId", orderId)
                .query(JdbcFiscalDocumentStore::map)
                .list();
    }

    /**
     * The query the cash decision exists to make possible.
     *
     * <p>The user decided on 2026-08-22 that a cash order receives no provider
     * fiscal receipt, and the decision was recorded as a reason code rather than as
     * a null precisely so that reversing it is a migration and not an archaeology
     * exercise. This is the method that finds them, and
     * {@code ix_fiscal_documents_not_applicable} is the index that makes it cheap.
     */
    public List<FiscalDocument> listNotApplicable(UUID tenantId, String reasonCode,
            Instant from, Instant to, int limit) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND status = 'NOT_APPLICABLE'
                   AND reason_code = :reasonCode
                   AND created_at >= :from AND created_at < :to
                 ORDER BY created_at
                 LIMIT :limit
                """)
                .param("tenantId", tenantId).param("reasonCode", reasonCode)
                .param("from", utc(from)).param("to", utc(to)).param("limit", limit)
                .query(JdbcFiscalDocumentStore::map)
                .list();
    }

    /**
     * Documents owed a receipt that has not arrived.
     *
     * <p>Reachable on both providers by different mechanisms: Click's
     * {@code submit_items} can fail after a successful capture, and Payme's
     * {@code SetFiscalData} arrives asynchronously and may simply never come.
     * Whether a deadline exists after which it will not arrive is an open question
     * to Payme, which is why this is a query someone runs rather than a timer.
     */
    public List<FiscalDocument> listAwaitingEvidence(UUID tenantId, Instant submittedBefore,
            int limit) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId
                   AND status IN ('PENDING', 'SUBMITTED')
                   AND (submitted_at IS NULL OR submitted_at < :submittedBefore)
                 ORDER BY created_at
                 LIMIT :limit
                """)
                .param("tenantId", tenantId).param("submittedBefore", utc(submittedBefore))
                .param("limit", limit)
                .query(JdbcFiscalDocumentStore::map)
                .list();
    }

    public void recordSubmission(UUID tenantId, UUID documentId, FiscalStatus status,
            String reasonCode, String protectedRequestReference, Instant submittedAt) {
        jdbc.sql("""
                UPDATE payments.fiscal_documents
                SET status = :status,
                    reason_code = :reasonCode,
                    submitted_at = :submittedAt,
                    protected_request_reference = :protectedRequest,
                    version = version + 1,
                    updated_at = :submittedAt
                WHERE tenant_id = :tenantId AND id = :id
                  AND status IN ('PENDING', 'SUBMITTED', 'FAILED')
                """)
                .param("tenantId", tenantId).param("id", documentId)
                .param("status", status.name()).param("reasonCode", reasonCode)
                .param("protectedRequest", protectedRequestReference)
                .param("submittedAt", utc(submittedAt))
                .update();
    }

    /**
     * Attaches the evidence a provider returned.
     *
     * <p>Guarded on a status that has not yet been issued, so a late duplicate of
     * an outcome cannot rewrite a fiscal sign that is already on file with the tax
     * authority.
     *
     * @return true when this caller wrote the evidence
     */
    public boolean recordEvidence(UUID tenantId, UUID documentId, FiscalStatus status,
            String reasonCode, FiscalDocument.FiscalEvidence evidence,
            String protectedResponseReference, Instant issuedAt) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", documentId);
        parameters.put("status", status.name());
        parameters.put("reasonCode", reasonCode);
        parameters.put("externalReceiptId", evidence == null ? null : evidence.externalReceiptId());
        parameters.put("fiscalSign", evidence == null ? null : evidence.fiscalSign());
        parameters.put("terminalId", evidence == null ? null : evidence.terminalId());
        parameters.put("receiptReference", evidence == null ? null : evidence.receiptReference());
        parameters.put("registeredAt", evidence == null ? null : utc(evidence.registeredAt()));
        parameters.put("receiptUrl", evidence == null ? null : evidence.receiptUrl());
        parameters.put("providerStatusCode", evidence == null ? null : evidence.providerStatusCode());
        parameters.put("providerMessage", evidence == null ? null : evidence.providerMessage());
        parameters.put("protectedResponse", protectedResponseReference);
        parameters.put("issuedAt", utc(issuedAt));

        int updated = jdbc.sql("""
                UPDATE payments.fiscal_documents
                SET status = :status,
                    reason_code = :reasonCode,
                    external_receipt_id = :externalReceiptId,
                    fiscal_sign = :fiscalSign,
                    terminal_id = :terminalId,
                    receipt_reference = :receiptReference,
                    registered_at = CAST(:registeredAt AS timestamptz),
                    receipt_url = :receiptUrl,
                    provider_status_code = :providerStatusCode,
                    provider_message = :providerMessage,
                    protected_response_reference =
                        COALESCE(:protectedResponse, protected_response_reference),
                    issued_at = CASE WHEN :status = 'ISSUED' THEN :issuedAt ELSE issued_at END,
                    version = version + 1,
                    updated_at = :issuedAt
                WHERE tenant_id = :tenantId AND id = :id AND status <> 'ISSUED'
                """)
                .params(parameters)
                .update();

        return updated == 1;
    }

    private static FiscalDocument map(ResultSet row, int rowNumber) throws SQLException {
        String providerType = row.getString("provider_type");
        return new FiscalDocument(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                row.getObject("payment_intent_id", UUID.class),
                row.getObject("payment_transaction_id", UUID.class),
                providerType == null ? null : PaymentProviderType.valueOf(providerType),
                FiscalDocumentType.valueOf(row.getString("document_type")),
                row.getObject("corrects_document_id", UUID.class),
                FiscalStatus.valueOf(row.getString("status")),
                row.getString("reason_code"),
                row.getString("reason_note"),
                List.of(),
                new FiscalDocument.FiscalEvidence(
                        row.getString("external_receipt_id"),
                        row.getString("fiscal_sign"),
                        row.getString("terminal_id"),
                        row.getString("receipt_reference"),
                        instant(row, "registered_at"),
                        row.getString("receipt_url"),
                        row.getString("provider_status_code"),
                        row.getString("provider_message")),
                row.getObject("version", Integer.class),
                instant(row, "created_at"));
    }
}
