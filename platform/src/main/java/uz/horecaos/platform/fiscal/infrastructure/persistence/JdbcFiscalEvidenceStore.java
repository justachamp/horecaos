package uz.horecaos.platform.fiscal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;

/**
 * The reads behind the fiscal evidence surface (ADR 0166).
 *
 * <p>Read-only, and deliberately not part of {@link JdbcFiscalLifecycleStore}: that one writes the
 * obligation and its progress, never the evidence columns, and is read by a worklist that must not
 * see an identifier. This one returns the identifiers, so everything above it decides what a caller
 * may be shown. A read model over rows that already exist: never a second copy, never a receipt
 * HorecaOS composes.
 *
 * <p>Two queries read outside the {@code fiscal} schema ({@code payments.merchant_bindings} for the
 * account a submission went through, {@code payments.payment_methods} and friends for the
 * per-tenant payment types). That is SQL and not a module dependency, the same trade
 * {@link JdbcFiscalLifecycleStore} documents for its own joins.
 */
@Repository
public class JdbcFiscalEvidenceStore {

    private final JdbcClient jdbc;

    public JdbcFiscalEvidenceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One document of an order, with every column the evidence surface can show.
     *
     * <p>The identifiers are here in full ({@code fiscalSign}, {@code receiptReference},
     * {@code receiptUrl}, {@code terminalId}); masking and the reveal are the service's.
     *
     * @param merchantAccountReference Click's {@code service_id} or Payme's cashbox id: it names an
     *                                 account without authenticating to it
     */
    public record EvidenceRow(
            UUID id,
            UUID orderId,
            @Nullable UUID legalEntityId,
            @Nullable UUID tenderId,
            @Nullable LocalDate businessDate,
            @Nullable UUID paymentIntentId,
            @Nullable String providerType,
            String documentType,
            @Nullable UUID correctsDocumentId,
            FiscalDocumentState state,
            String reasonCode,
            String reasonNote,
            @Nullable String externalReceiptId,
            @Nullable String fiscalSign,
            @Nullable String terminalId,
            @Nullable String receiptReference,
            @Nullable Instant registeredAt,
            @Nullable String receiptUrl,
            @Nullable String providerStatusCode,
            @Nullable String sellerTin,
            @Nullable String sellerName,
            @Nullable String sellerSnapshotSource,
            boolean linesSynthetic,
            @Nullable String protectedRequestReference,
            @Nullable String sentTenderSplitReference,
            @Nullable UUID merchantBindingId,
            @Nullable String merchantAccountReference,
            @Nullable Instant submittedAt,
            @Nullable Instant issuedAt,
            @Nullable Instant blockedAt,
            int attemptCount,
            int version,
            Instant createdAt) {

        /** Whether the tax authority's identifiers are on the row. */
        public boolean hasEvidence() {
            return fiscalSign != null || externalReceiptId != null;
        }

        /** Who owes the receipt: a document with no provider is the tenant's own equipment's to discharge. */
        public String responsibility() {
            return providerType == null ? "TERMINAL" : "PARTNER";
        }
    }

    /** Every document for an order, oldest first. Plural, and never anything else. */
    public List<EvidenceRow> documentsOfOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                        SELECT d.id, d.order_id, d.legal_entity_id, d.tender_id, d.business_date, d.payment_intent_id,
                               d.provider_type, d.document_type, d.corrects_document_id, d.status,
                               d.reason_code, d.reason_note, d.external_receipt_id, d.fiscal_sign,
                               d.terminal_id, d.receipt_reference, d.registered_at, d.receipt_url,
                               d.provider_status_code, d.seller_tin, d.seller_name,
                               d.seller_snapshot_source, d.lines_synthetic,
                               d.protected_request_reference, d.sent_tender_split_reference,
                               d.merchant_binding_id, b.merchant_account_reference,
                               d.submitted_at, d.issued_at, d.blocked_at, d.attempt_count,
                               d.version, d.created_at
                          FROM fiscal.fiscal_documents d
                          LEFT JOIN payments.merchant_bindings b
                                 ON b.tenant_id = d.tenant_id AND b.id = d.merchant_binding_id
                         WHERE d.tenant_id = :tenantId AND d.order_id = :orderId
                         ORDER BY d.created_at, d.id
                        """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcFiscalEvidenceStore::mapEvidence)
                .list();
    }

    /**
     * A receipt link the customer whose order it is may be shown (ADR 0166, ADR 0058).
     *
     * <p>Deliberately not an {@link EvidenceRow}: the customer's page needs the link and when it was
     * registered, and a type that also carried the fiscal sign, the terminal or the seller could one
     * day be serialised whole.
     */
    public record CustomerReceiptRow(
            String documentType,
            String receiptUrl,
            String reasonCode,
            @Nullable Instant registeredAt) {}

    /**
     * The receipts of an order that are still the one in force, oldest first.
     *
     * <p>A document that has been corrected or voided is no longer the receipt, and showing its link
     * beside the order would point a customer at a document that has been superseded; the correction
     * or refund document that replaced it is listed in its own right. An issued receipt with a
     * correction merely <em>requested</em> is still the one filed and stays. Only an {@code https}
     * link is returned: the link is rendered as an anchor, and whatever is stored in a column the
     * providers write must not become a {@code javascript:} target.
     */
    public List<CustomerReceiptRow> receiptLinksOfOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                        SELECT document_type, receipt_url, reason_code, COALESCE(registered_at, issued_at) AS registered
                          FROM fiscal.fiscal_documents
                         WHERE tenant_id = :tenantId AND order_id = :orderId
                           AND status IN ('ISSUED', 'CORRECTION_REQUESTED', 'VOID_REQUESTED')
                           AND receipt_url LIKE 'https://%'
                         ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new CustomerReceiptRow(
                        row.getString("document_type"),
                        row.getString("receipt_url"),
                        row.getString("reason_code"),
                        instant(row, "registered")))
                .list();
    }

    /** One request that left for the provider, as kept (V0643). The ciphertext is the document's to open. */
    public record SentRequestRow(
            UUID documentId, int attempt, Instant sentAt, String protectedRequestReference, boolean linesSynthetic) {}

    /**
     * Every request that left for any document of an order, oldest first within a document. The newest of a
     * document's requests is the one its own columns hold; the ones before it are the ones it replaced.
     */
    public List<SentRequestRow> sentRequestsOfOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                        SELECT s.document_id, s.attempt, s.sent_at, s.protected_request_reference, s.lines_synthetic
                          FROM fiscal.fiscal_sent_requests s
                          JOIN fiscal.fiscal_documents d ON d.tenant_id = s.tenant_id AND d.id = s.document_id
                         WHERE s.tenant_id = :tenantId AND d.order_id = :orderId
                         ORDER BY s.document_id, s.attempt
                        """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new SentRequestRow(
                        row.getObject("document_id", UUID.class),
                        row.getInt("attempt"),
                        requiredInstant(row, "sent_at"),
                        row.getString("protected_request_reference"),
                        row.getBoolean("lines_synthetic")))
                .list();
    }

    /** One correction or void request, as the surface and the worklist show it. */
    public record CorrectionRow(
            UUID id,
            UUID documentId,
            UUID orderId,
            String kind,
            String reasonCode,
            String status,
            String requestedBy,
            @Nullable UUID approvalId,
            Instant requestedAt,
            @Nullable UUID attachedDocumentId,
            @Nullable Instant attachedAt,
            @Nullable Instant closedAt,
            int version) {}

    private static final String CORRECTION_COLUMNS = """
            r.id, r.document_id, d.order_id, r.kind, r.reason_code, r.status, r.requested_by,
            r.approval_id, r.requested_at, r.attached_document_id, r.attached_at, r.closed_at, r.version
            """;

    /** Every request raised against any document of an order, newest first. */
    public List<CorrectionRow> correctionsOfOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("SELECT " + CORRECTION_COLUMNS + """
                  FROM fiscal.fiscal_correction_requests r
                  JOIN fiscal.fiscal_documents d ON d.tenant_id = r.tenant_id AND d.id = r.document_id
                 WHERE r.tenant_id = :tenantId AND d.order_id = :orderId
                 ORDER BY r.requested_at DESC, r.id
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcFiscalEvidenceStore::mapCorrection)
                .list();
    }

    /** The open requests of a tenant, longest-waiting first: the ageing worklist (Decision 8). */
    public List<CorrectionRow> openCorrections(UUID tenantId, int limit) {
        return jdbc.sql("SELECT " + CORRECTION_COLUMNS + """
                  FROM fiscal.fiscal_correction_requests r
                  JOIN fiscal.fiscal_documents d ON d.tenant_id = r.tenant_id AND d.id = r.document_id
                 WHERE r.tenant_id = :tenantId AND r.status = 'REQUESTED'
                 ORDER BY r.requested_at, r.id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query(JdbcFiscalEvidenceStore::mapCorrection)
                .list();
    }

    public Optional<CorrectionRow> findCorrection(UUID tenantId, UUID requestId) {
        return jdbc.sql("SELECT " + CORRECTION_COLUMNS + """
                  FROM fiscal.fiscal_correction_requests r
                  JOIN fiscal.fiscal_documents d ON d.tenant_id = r.tenant_id AND d.id = r.document_id
                 WHERE r.tenant_id = :tenantId AND r.id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", requestId)
                .query(JdbcFiscalEvidenceStore::mapCorrection)
                .optional();
    }

    // ------------------------------------------------------------ payment types

    /** One payment-method registry row of a tenant (ADR 0038). */
    public record MethodRow(
            UUID id,
            String code,
            String displayName,
            String responsibility,
            boolean settlesFromBalance,
            String status) {}

    /** An active merchant account of a legal entity that can discharge a partner receipt. */
    public record PartnerPathRow(
            UUID bindingId,
            UUID legalEntityId,
            String providerType,
            String merchantAccountReference,
            boolean supportsPartnerFiscalization) {}

    /** A legal entity's capable terminals. */
    public record TerminalPathRow(UUID legalEntityId, long capableTerminals) {}

    /** A legal entity, as the payment-types table names it. */
    public record EntityRow(UUID id, String tin, String legalName) {}

    public List<MethodRow> paymentMethods(UUID tenantId) {
        return jdbc.sql("""
                        SELECT id, code, display_name, responsibility, settles_from_balance, status
                          FROM payments.payment_methods
                         WHERE tenant_id = :tenantId
                         ORDER BY code
                        """)
                .param("tenantId", tenantId)
                .query((row, number) -> new MethodRow(
                        row.getObject("id", UUID.class),
                        row.getString("code"),
                        row.getString("display_name"),
                        row.getString("responsibility"),
                        row.getBoolean("settles_from_balance"),
                        row.getString("status")))
                .list();
    }

    /** Merchant accounts in force today. */
    public List<PartnerPathRow> activePartnerPaths(UUID tenantId) {
        return jdbc.sql("""
                        SELECT id, legal_entity_id, provider_type, merchant_account_reference,
                               supports_partner_fiscalization
                          FROM payments.merchant_bindings
                         WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                           AND effective_from <= current_date
                           AND (effective_until IS NULL OR effective_until > current_date)
                         ORDER BY provider_type, legal_entity_id
                        """)
                .param("tenantId", tenantId)
                .query((row, number) -> new PartnerPathRow(
                        row.getObject("id", UUID.class),
                        row.getObject("legal_entity_id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("merchant_account_reference"),
                        row.getBoolean("supports_partner_fiscalization")))
                .list();
    }

    /** Active terminals that claim to issue a fiscal receipt, by the legal entity that owns them. */
    public List<TerminalPathRow> capableTerminals(UUID tenantId) {
        return jdbc.sql("""
                        SELECT legal_entity_id, count(*) AS capable
                          FROM fiscal.fiscal_terminals
                         WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                           AND capability_snapshot @> '{"IssueFiscalReceipt": true}'::jsonb
                         GROUP BY legal_entity_id
                         ORDER BY legal_entity_id
                        """)
                .param("tenantId", tenantId)
                .query((row, number) ->
                        new TerminalPathRow(row.getObject("legal_entity_id", UUID.class), row.getLong("capable")))
                .list();
    }

    public List<EntityRow> legalEntities(UUID tenantId) {
        return jdbc.sql("""
                        SELECT id, tin, legal_name FROM tenant.legal_entities
                         WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                         ORDER BY code
                        """)
                .param("tenantId", tenantId)
                .query((row, number) -> new EntityRow(
                        row.getObject("id", UUID.class), row.getString("tin"), row.getString("legal_name")))
                .list();
    }

    // ------------------------------------------------------------------ mapping

    private static EvidenceRow mapEvidence(ResultSet row, int number) throws SQLException {
        return new EvidenceRow(
                row.getObject("id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                row.getObject("tender_id", UUID.class),
                row.getObject("business_date", LocalDate.class),
                row.getObject("payment_intent_id", UUID.class),
                row.getString("provider_type"),
                row.getString("document_type"),
                row.getObject("corrects_document_id", UUID.class),
                FiscalDocumentState.require(row.getString("status")),
                row.getString("reason_code"),
                row.getString("reason_note"),
                row.getString("external_receipt_id"),
                row.getString("fiscal_sign"),
                row.getString("terminal_id"),
                row.getString("receipt_reference"),
                instant(row, "registered_at"),
                row.getString("receipt_url"),
                row.getString("provider_status_code"),
                row.getString("seller_tin"),
                row.getString("seller_name"),
                row.getString("seller_snapshot_source"),
                row.getBoolean("lines_synthetic"),
                row.getString("protected_request_reference"),
                row.getString("sent_tender_split_reference"),
                row.getObject("merchant_binding_id", UUID.class),
                row.getString("merchant_account_reference"),
                instant(row, "submitted_at"),
                instant(row, "issued_at"),
                instant(row, "blocked_at"),
                row.getInt("attempt_count"),
                row.getInt("version"),
                requiredInstant(row, "created_at"));
    }

    private static CorrectionRow mapCorrection(ResultSet row, int number) throws SQLException {
        return new CorrectionRow(
                row.getObject("id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getString("kind"),
                row.getString("reason_code"),
                row.getString("status"),
                row.getString("requested_by"),
                row.getObject("approval_id", UUID.class),
                requiredInstant(row, "requested_at"),
                row.getObject("attached_document_id", UUID.class),
                instant(row, "attached_at"),
                instant(row, "closed_at"),
                row.getInt("version"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant requiredInstant(ResultSet row, String column) throws SQLException {
        Instant value = instant(row, column);
        if (value == null) {
            throw new IllegalStateException(column + " is NOT NULL");
        }
        return value;
    }
}
