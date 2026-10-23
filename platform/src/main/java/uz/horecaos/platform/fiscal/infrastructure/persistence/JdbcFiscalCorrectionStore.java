package uz.horecaos.platform.fiscal.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The write side of correction and void requests (ADR 0166, Decision 8).
 *
 * <p>HorecaOS records a request and records the reference the tenant's own fiscal agent returns.
 * It never edits a filed document's evidence and never deletes one: the only things this class
 * does to {@code fiscal.fiscal_documents} are to move an issued document to the next status in
 * its life, conditional on the version the caller read, and to insert the <em>new</em> linked
 * document the agent's reference becomes. Every statement is conditional on the status it expects,
 * so two operators racing one document produce one winner and a refusal, not two corrections.
 */
@Repository
public class JdbcFiscalCorrectionStore {

    private final JdbcClient jdbc;

    public JdbcFiscalCorrectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Moves an issued document to a requested or finished status.
     *
     * @return true when the document was in {@code fromStatus} at {@code expectedVersion} and this call moved it
     */
    public boolean moveDocument(
            UUID tenantId, UUID documentId, String fromStatus, String toStatus, int expectedVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE fiscal.fiscal_documents
                           SET status = :to, version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND id = :id
                           AND status = :from AND version = :expectedVersion
                        """)
                        .param("tenantId", tenantId)
                        .param("id", documentId)
                        .param("from", fromStatus)
                        .param("to", toStatus)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Opens a request. The partial unique index is what makes a second open one for a document fail.
     *
     * @param sourceRevision the order revision whose amendment opened the request on its own (V0686), or null for
     *     a request a person raised
     */
    public void insertRequest(
            UUID id,
            UUID tenantId,
            UUID documentId,
            String kind,
            String reasonCode,
            String reasonNote,
            String requestedBy,
            @Nullable UUID approvalId,
            @Nullable Integer sourceRevision,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO fiscal.fiscal_correction_requests (
                            id, tenant_id, document_id, kind, reason_code, reason_note, status,
                            requested_by, approval_id, source_revision, requested_at, version, created_at, updated_at)
                        VALUES (:id, :tenantId, :documentId, :kind, :reasonCode, :reasonNote, 'REQUESTED',
                            :requestedBy, :approvalId, :sourceRevision, :now, 1, :now, :now)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("documentId", documentId)
                .param("kind", kind)
                .param("reasonCode", reasonCode)
                .param("reasonNote", reasonNote)
                .param("requestedBy", requestedBy)
                .param("approvalId", approvalId)
                .param("sourceRevision", sourceRevision)
                .param("now", utc(now))
                .update();
    }

    /**
     * The latest order revision any request on this document already answers, or 0 when none was opened by an
     * amendment. A request of any status counts: one the agent declined has still been asked.
     */
    public int highestCoveredRevision(UUID tenantId, UUID documentId) {
        return jdbc.sql("""
                        SELECT COALESCE(max(source_revision), 0)
                          FROM fiscal.fiscal_correction_requests
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                        """)
                .param("tenantId", tenantId)
                .param("documentId", documentId)
                .query(Integer.class)
                .single();
    }

    /** What the tenant's agent filed, as the operator attaches it. A reference, not a receipt. */
    public record AttachedReference(
            String fiscalSign,
            String receiptNumber,
            String receiptUrl,
            @Nullable String terminalId,
            @Nullable Instant registeredAt) {}

    /**
     * Inserts the document the agent's reference becomes: a {@code CORRECTION} or a {@code REFUND}
     * linked to the one it corrects, on the same order, entity, provider and merchant account, under
     * the same seller snapshot. The original is not touched by this statement.
     *
     * @return true when the original existed for the tenant and the linked document was written
     */
    public boolean insertLinkedDocument(
            UUID newId,
            UUID tenantId,
            UUID originalId,
            String documentType,
            String reasonCode,
            String reasonNote,
            AttachedReference reference,
            Instant now) {
        return jdbc.sql("""
                        INSERT INTO fiscal.fiscal_documents (
                            id, tenant_id, order_id, legal_entity_id, payment_intent_id, provider_type,
                            document_type, corrects_document_id, status, reason_code, reason_note,
                            external_receipt_id, fiscal_sign, terminal_id, receipt_reference,
                            registered_at, receipt_url, submitted_at, issued_at,
                            seller_tin, seller_name, seller_snapshot_source, merchant_binding_id,
                            attempt_count, version, created_at, updated_at)
                        SELECT :newId, o.tenant_id, o.order_id, o.legal_entity_id, o.payment_intent_id,
                               o.provider_type, :documentType, o.id, 'ISSUED', :reasonCode, :reasonNote,
                               :receiptNumber, :fiscalSign, :terminalId, :receiptNumber,
                               :registeredAt, :receiptUrl, :now, :now,
                               o.seller_tin, o.seller_name, o.seller_snapshot_source, o.merchant_binding_id,
                               0, 1, :now, :now
                          FROM fiscal.fiscal_documents o
                         WHERE o.tenant_id = :tenantId AND o.id = :originalId
                        """)
                        .param("newId", newId)
                        .param("tenantId", tenantId)
                        .param("originalId", originalId)
                        .param("documentType", documentType)
                        .param("reasonCode", reasonCode)
                        .param("reasonNote", reasonNote)
                        .param("receiptNumber", reference.receiptNumber())
                        .param("fiscalSign", reference.fiscalSign())
                        .param("terminalId", reference.terminalId())
                        .param("registeredAt", reference.registeredAt() == null ? null : utc(reference.registeredAt()))
                        .param("receiptUrl", reference.receiptUrl())
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Marks a request attached, conditional on it still being open at the version the caller read. */
    public boolean attachRequest(
            UUID tenantId,
            UUID requestId,
            int expectedVersion,
            UUID attachedDocumentId,
            String attachedBy,
            Instant now) {
        return jdbc.sql("""
                        UPDATE fiscal.fiscal_correction_requests
                           SET status = 'ATTACHED', attached_document_id = :attachedDocumentId,
                               attached_by = :attachedBy, attached_at = :now,
                               version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND id = :id
                           AND status = 'REQUESTED' AND version = :expectedVersion
                        """)
                        .param("tenantId", tenantId)
                        .param("id", requestId)
                        .param("expectedVersion", expectedVersion)
                        .param("attachedDocumentId", attachedDocumentId)
                        .param("attachedBy", attachedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Closes a request without a receipt: {@code DECLINED} (the tenant's agent would not issue it) or
     * {@code CANCELLED} (a person withdrew it). Conditional on the request still being open at the version
     * the caller read, so a closure racing an attachment has one winner.
     *
     * @return true when this call closed it
     */
    public boolean closeRequest(
            UUID tenantId,
            UUID requestId,
            int expectedVersion,
            String closedStatus,
            String closedBy,
            String closedNote,
            Instant now) {
        return jdbc.sql("""
                        UPDATE fiscal.fiscal_correction_requests
                           SET status = :status, closed_by = :closedBy, closed_at = :now,
                               closed_note = :closedNote, version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND id = :id
                           AND status = 'REQUESTED' AND version = :expectedVersion
                        """)
                        .param("tenantId", tenantId)
                        .param("id", requestId)
                        .param("expectedVersion", expectedVersion)
                        .param("status", closedStatus)
                        .param("closedBy", closedBy)
                        .param("closedNote", closedNote)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
