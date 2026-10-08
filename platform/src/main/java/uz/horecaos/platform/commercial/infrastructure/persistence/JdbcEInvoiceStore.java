package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.domain.EInvoiceDelivery;
import uz.horecaos.platform.commercial.domain.EInvoicingInstallation;
import uz.horecaos.platform.commercial.domain.EInvoicingLineClassification;
import uz.horecaos.platform.commercial.domain.StatementEInvoice;

/**
 * HorecaOS's own e-invoicing accounts, the classification each statement line kind
 * is invoiced under, and the record of what was sent (ADR 0096).
 *
 * <p>Every update is a compare-and-set on {@code version}: two staff members editing
 * one installation, or a refresh racing a sweep over one document, cannot overwrite
 * each other silently -- the loser's update touches no row and is told so.
 */
@Repository
public class JdbcEInvoiceStore {

    /**
     * A document the operator has not settled. Signed, refused and cancelled are final: the sweep
     * never asks about them, and no write moves one, so a refused attempt cannot come back into the
     * live index beside the attempt that replaced it, and a signed invoice cannot be rewritten.
     */
    private static final String NOT_SETTLED =
            "(operator_state IS NULL OR operator_state NOT IN ('SIGNED', 'REFUSED', 'CANCELLED'))";

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    /** The longest failure text kept: enough to tell an operator what was wrong, short enough to be no story. */
    static final int MAX_FAILURE_DETAIL = 500;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEInvoiceStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ environments

    /** An approved endpoint (ADR 0026): the only place a base URL ever comes from. */
    public record Environment(
            String code, String providerType, String baseUrl, boolean production, String egressAllowlist) {}

    public Optional<Environment> environment(String code) {
        return jdbc.sql("""
                        SELECT code, provider_type, base_url, is_production, egress_allowlist
                          FROM integration.provider_environments WHERE code = :code
                        """)
                .param("code", code)
                .query((row, number) -> new Environment(
                        row.getString("code"),
                        row.getString("provider_type"),
                        row.getString("base_url"),
                        row.getBoolean("is_production"),
                        row.getString("egress_allowlist")))
                .optional();
    }

    /** The approved environments of one provider type, production first. */
    public List<Environment> environmentsOf(String providerType) {
        return jdbc.sql("""
                        SELECT code, provider_type, base_url, is_production, egress_allowlist
                          FROM integration.provider_environments
                         WHERE provider_type = :providerType
                         ORDER BY is_production DESC, code
                        """)
                .param("providerType", providerType)
                .query((row, number) -> new Environment(
                        row.getString("code"),
                        row.getString("provider_type"),
                        row.getString("base_url"),
                        row.getBoolean("is_production"),
                        row.getString("egress_allowlist")))
                .list();
    }

    // ----------------------------------------------------------- installations

    private static final String INSTALLATION = """
            SELECT id, provider_type, environment_code, display_name, status, secret_reference,
                   non_sensitive_config, adapter_version, version, updated_by, updated_at
              FROM commercial.einvoicing_installations
            """;

    public List<EInvoicingInstallation> installations() {
        return jdbc.sql(INSTALLATION + " ORDER BY provider_type, environment_code")
                .query(this::installationOf)
                .list();
    }

    public Optional<EInvoicingInstallation> installation(UUID id) {
        return jdbc.sql(INSTALLATION + " WHERE id = :id")
                .param("id", id)
                .query(this::installationOf)
                .optional();
    }

    /** The one live account of an operator, or empty when none is active. */
    public Optional<EInvoicingInstallation> activeInstallation(String providerType) {
        return jdbc.sql(INSTALLATION + " WHERE provider_type = :providerType AND status = 'ACTIVE'")
                .param("providerType", providerType)
                .query(this::installationOf)
                .optional();
    }

    /** @return false when the installation moved since {@code expectedVersion} was read */
    public boolean updateInstallation(
            UUID id,
            long expectedVersion,
            String displayName,
            @Nullable String secretReference,
            Map<String, String> config,
            String updatedBy,
            Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.einvoicing_installations
                           SET display_name = :displayName, secret_reference = :secretReference,
                               non_sensitive_config = CAST(:config AS jsonb), updated_by = :updatedBy,
                               updated_at = :now, version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        """)
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("displayName", displayName)
                        .param("secretReference", secretReference)
                        .param("config", objectMapper.writeValueAsString(new LinkedHashMap<>(config)))
                        .param("updatedBy", updatedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean setInstallationStatus(UUID id, long expectedVersion, String status, String updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.einvoicing_installations
                           SET status = :status, updated_by = :updatedBy, updated_at = :now, version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        """)
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("status", status)
                        .param("updatedBy", updatedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Recorded when an adapter is wired in, so the screen can say what version it speaks. */
    public void recordAdapterVersion(UUID id, String adapterVersion) {
        jdbc.sql("""
                        UPDATE commercial.einvoicing_installations
                           SET adapter_version = :adapterVersion
                         WHERE id = :id AND adapter_version <> :adapterVersion
                        """).param("id", id).param("adapterVersion", adapterVersion).update();
    }

    private EInvoicingInstallation installationOf(ResultSet row, int number) throws SQLException {
        return new EInvoicingInstallation(
                row.getObject("id", UUID.class),
                row.getString("provider_type"),
                row.getString("environment_code"),
                row.getString("display_name"),
                row.getString("status"),
                row.getString("secret_reference"),
                objectMapper.readValue(row.getString("non_sensitive_config"), STRING_MAP),
                row.getString("adapter_version"),
                row.getLong("version"),
                row.getString("updated_by"),
                java.util.Objects.requireNonNull(instant(row, "updated_at"), "updated_at is NOT NULL"));
    }

    // --------------------------------------------------------- classifications

    private static final String CLASSIFICATION = """
            SELECT line_kind, item_label, catalog_code, catalog_name, package_code, package_name, vat_rate_bp,
                   provisional, confirmed_by, confirmed_at, version, updated_by, updated_at
              FROM commercial.einvoicing_line_classifications
            """;

    public List<EInvoicingLineClassification> classifications() {
        return jdbc.sql(CLASSIFICATION + " ORDER BY line_kind")
                .query(JdbcEInvoiceStore::classificationOf)
                .list();
    }

    public Optional<EInvoicingLineClassification> classification(String lineKind) {
        return jdbc.sql(CLASSIFICATION + " WHERE line_kind = :kind")
                .param("kind", lineKind)
                .query(JdbcEInvoiceStore::classificationOf)
                .optional();
    }

    /**
     * Replaces the classification of one kind. {@code confirm} stamps who confirmed it
     * and when and clears the provisional flag; without it the row stays provisional
     * (and a previous confirmation is withdrawn, because what was confirmed is gone).
     *
     * @return false when the row moved since {@code expectedVersion} was read
     */
    public boolean updateClassification(
            String lineKind,
            long expectedVersion,
            String itemLabel,
            String catalogCode,
            String catalogName,
            String packageCode,
            String packageName,
            int vatRateBp,
            boolean confirm,
            String updatedBy,
            Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.einvoicing_line_classifications
                           SET item_label = :itemLabel, catalog_code = :catalogCode, catalog_name = :catalogName,
                               package_code = :packageCode, package_name = :packageName,
                               vat_rate_bp = :vatRateBp, provisional = NOT :confirm,
                               confirmed_by = CASE WHEN :confirm THEN :updatedBy END,
                               confirmed_at = CASE WHEN :confirm THEN CAST(:now AS timestamptz) END,
                               updated_by = :updatedBy, updated_at = :now, version = version + 1
                         WHERE line_kind = :lineKind AND version = :expectedVersion
                        """)
                        .param("lineKind", lineKind)
                        .param("expectedVersion", expectedVersion)
                        .param("itemLabel", itemLabel)
                        .param("catalogCode", catalogCode)
                        .param("catalogName", catalogName)
                        .param("packageCode", packageCode)
                        .param("packageName", packageName)
                        .param("vatRateBp", vatRateBp)
                        .param("confirm", confirm)
                        .param("updatedBy", updatedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    private static EInvoicingLineClassification classificationOf(ResultSet row, int number) throws SQLException {
        return new EInvoicingLineClassification(
                row.getString("line_kind"),
                row.getString("item_label"),
                row.getString("catalog_code"),
                row.getString("catalog_name"),
                row.getString("package_code"),
                row.getString("package_name"),
                row.getInt("vat_rate_bp"),
                row.getBoolean("provisional"),
                row.getString("confirmed_by"),
                instant(row, "confirmed_at"),
                row.getLong("version"),
                row.getString("updated_by"),
                java.util.Objects.requireNonNull(instant(row, "updated_at"), "updated_at is NOT NULL"));
    }

    // -------------------------------------------------------- sent documents

    private static final String EINVOICE = """
            SELECT id, tenant_id, statement_id, installation_id, provider_type, legal_entity_id, buyer_tin,
                   buyer_name, seller_tin, document_number, document_date, currency, net_minor, vat_minor,
                   total_minor, classification_provisional, CAST(sent_document AS text) AS sent_document,
                   delivery, failure_code, failure_detail, operator_document_id, operator_state,
                   operator_status, state_checked_at, state_changed_at, send_reason, sent_by, created_at,
                   updated_at, version
              FROM commercial.statement_einvoices
            """;

    /**
     * Records the attempt before the operator is called.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the statement already has a live document
     */
    public void insertPending(StatementEInvoice row) {
        jdbc.sql("""
                        INSERT INTO commercial.statement_einvoices (
                            id, tenant_id, statement_id, installation_id, provider_type, legal_entity_id,
                            buyer_tin, buyer_name, seller_tin, document_number, document_date, currency,
                            net_minor, vat_minor, total_minor, classification_provisional, sent_document,
                            delivery, send_reason, sent_by, created_at, updated_at, version)
                        VALUES (
                            :id, :tenantId, :statementId, :installationId, :providerType, :legalEntityId,
                            :buyerTin, :buyerName, :sellerTin, :documentNumber, :documentDate, :currency,
                            :net, :vat, :total, :provisional, CAST(:sentDocument AS jsonb),
                            'PENDING', :reason, :sentBy, :now, :now, 0)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("statementId", row.statementId())
                .param("installationId", row.installationId())
                .param("providerType", row.providerType())
                .param("legalEntityId", row.legalEntityId())
                .param("buyerTin", row.buyerTaxpayerNumber())
                .param("buyerName", row.buyerName())
                .param("sellerTin", row.sellerTaxpayerNumber())
                .param("documentNumber", row.documentNumber())
                .param("documentDate", row.documentDate())
                .param("currency", row.currency())
                .param("net", row.netMinor())
                .param("vat", row.vatMinor())
                .param("total", row.totalMinor())
                .param("provisional", row.classificationProvisional())
                .param("sentDocument", objectMapper.writeValueAsString(row.sentDocument()))
                .param("reason", row.sendReason())
                .param("sentBy", row.sentBy())
                .param("now", utc(row.createdAt()))
                .update();
    }

    public Optional<StatementEInvoice> find(UUID tenantId, UUID id) {
        return jdbc.sql(EINVOICE + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::einvoiceOf)
                .optional();
    }

    public List<StatementEInvoice> forStatement(UUID tenantId, UUID statementId) {
        return jdbc.sql(EINVOICE + " WHERE tenant_id = :tenantId AND statement_id = :statementId"
                        + " ORDER BY created_at DESC, id DESC")
                .param("tenantId", tenantId)
                .param("statementId", statementId)
                .query(this::einvoiceOf)
                .list();
    }

    /**
     * The identifiers the operators have given for the statement's <em>other</em> attempts. Every
     * attempt for a statement carries the statement's number, so a lookup by number can meet the
     * documents of earlier attempts; these are what it must set aside.
     */
    public Set<String> documentIdsOfOtherAttempts(UUID tenantId, UUID statementId, UUID exceptEinvoiceId) {
        return Set.copyOf(jdbc.sql("""
                        SELECT operator_document_id FROM commercial.statement_einvoices
                         WHERE tenant_id = :tenantId AND statement_id = :statementId AND id <> :exceptId
                           AND operator_document_id IS NOT NULL
                        """)
                .param("tenantId", tenantId)
                .param("statementId", statementId)
                .param("exceptId", exceptEinvoiceId)
                .query(String.class)
                .list());
    }

    public List<StatementEInvoice> forTenant(UUID tenantId) {
        return jdbc.sql(EINVOICE + " WHERE tenant_id = :tenantId ORDER BY created_at DESC, id DESC")
                .param("tenantId", tenantId)
                .query(this::einvoiceOf)
                .list();
    }

    /** Documents the operator may still tell us something new about, least recently asked first. */
    public List<StatementEInvoice> openDocuments(Instant checkedBefore, int limit) {
        return jdbc.sql(EINVOICE + """
                         WHERE delivery IN ('SUBMITTED', 'UNCERTAIN')
                           AND %s
                           AND (state_checked_at IS NULL OR state_checked_at < :checkedBefore)
                         ORDER BY state_checked_at NULLS FIRST, created_at
                         LIMIT :limit
                        """.formatted(NOT_SETTLED))
                .param("checkedBefore", utc(checkedBefore))
                .param("limit", limit)
                .query(this::einvoiceOf)
                .list();
    }

    /** Attempts recorded and never concluded: the process died between writing them and hearing back. */
    public List<StatementEInvoice> stalePending(Instant cutoff, int limit) {
        return jdbc.sql(EINVOICE + """
                         WHERE delivery = 'PENDING' AND created_at < :cutoff
                         ORDER BY created_at
                         LIMIT :limit
                        """)
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .query(this::einvoiceOf)
                .list();
    }

    /** PENDING to SUBMITTED, or UNCERTAIN to SUBMITTED, once the operator names the document. */
    public boolean markSubmitted(
            UUID id,
            long expectedVersion,
            String operatorDocumentId,
            EInvoiceOperatorState state,
            String rawStatus,
            Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.statement_einvoices
                           SET delivery = 'SUBMITTED', failure_code = NULL, failure_detail = NULL,
                               operator_document_id = :documentId, operator_state = :state,
                               operator_status = :rawStatus, state_checked_at = :now, state_changed_at = :now,
                               updated_at = :now, version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        """)
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("documentId", operatorDocumentId)
                        .param("state", state.name())
                        .param("rawStatus", truncate(rawStatus, 128))
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean markFailed(UUID id, long expectedVersion, String code, String detail, Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.statement_einvoices
                           SET delivery = 'FAILED', failure_code = :code, failure_detail = :detail,
                               updated_at = :now, version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        """)
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("code", truncate(code, 64))
                        .param("detail", truncate(detail, MAX_FAILURE_DETAIL))
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean markUncertain(UUID id, long expectedVersion, String code, String detail, Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.statement_einvoices
                           SET delivery = 'UNCERTAIN', failure_code = :code, failure_detail = :detail,
                               updated_at = :now, version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        """)
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("code", truncate(code, 64))
                        .param("detail", truncate(detail, MAX_FAILURE_DETAIL))
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * What the operator reported when asked. {@code state_changed_at} moves only when the
     * state itself did; {@code state_checked_at} moves on every answer. A document the operator
     * has settled (signed, refused, cancelled) is not moved by any answer: false, as for a lost
     * version race.
     */
    public boolean recordState(
            UUID id,
            long expectedVersion,
            String operatorDocumentId,
            EInvoiceOperatorState state,
            String rawStatus,
            Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.statement_einvoices
                           SET delivery = 'SUBMITTED', failure_code = NULL, failure_detail = NULL,
                               operator_document_id = :documentId,
                               state_changed_at = CASE WHEN operator_state IS DISTINCT FROM :state
                                                         OR operator_status IS DISTINCT FROM :rawStatus
                                                       THEN :now ELSE state_changed_at END,
                               operator_state = :state, operator_status = :rawStatus,
                               state_checked_at = :now, updated_at = :now, version = version + 1
                         WHERE id = :id AND version = :expectedVersion AND %s
                        """.formatted(NOT_SETTLED))
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("documentId", operatorDocumentId)
                        .param("state", state.name())
                        .param("rawStatus", truncate(rawStatus, 128))
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * The operator was asked and had nothing to add (or could not answer): only the asking is
     * recorded. A settled document is not moved, and answers false like any write that lost.
     */
    public boolean touchChecked(UUID id, long expectedVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.statement_einvoices
                           SET state_checked_at = :now, updated_at = :now, version = version + 1
                         WHERE id = :id AND version = :expectedVersion AND %s
                        """.formatted(NOT_SETTLED))
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    private StatementEInvoice einvoiceOf(ResultSet row, int number) throws SQLException {
        String state = row.getString("operator_state");
        return new StatementEInvoice(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("statement_id", UUID.class),
                row.getObject("installation_id", UUID.class),
                row.getString("provider_type"),
                row.getObject("legal_entity_id", UUID.class),
                row.getString("buyer_tin"),
                row.getString("buyer_name"),
                row.getString("seller_tin"),
                row.getString("document_number"),
                row.getObject("document_date", LocalDate.class),
                row.getString("currency"),
                row.getLong("net_minor"),
                row.getLong("vat_minor"),
                row.getLong("total_minor"),
                row.getBoolean("classification_provisional"),
                objectMapper.readValue(row.getString("sent_document"), EInvoiceDocument.class),
                EInvoiceDelivery.valueOf(row.getString("delivery")),
                row.getString("failure_code"),
                row.getString("failure_detail"),
                row.getString("operator_document_id"),
                state == null ? null : EInvoiceOperatorState.valueOf(state),
                row.getString("operator_status"),
                instant(row, "state_checked_at"),
                instant(row, "state_changed_at"),
                row.getString("send_reason"),
                row.getString("sent_by"),
                java.util.Objects.requireNonNull(instant(row, "created_at"), "created_at is NOT NULL"),
                java.util.Objects.requireNonNull(instant(row, "updated_at"), "updated_at is NOT NULL"),
                row.getLong("version"));
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
