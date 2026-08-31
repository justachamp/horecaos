package uz.horecaos.platform.courier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.courier.domain.CostBasis;
import uz.horecaos.platform.courier.domain.CostPath;
import uz.horecaos.platform.courier.domain.MatchStatus;
import uz.horecaos.platform.courier.domain.PartnerChargeType;

/**
 * The two delivery-cost paths and the partner invoices that settle one of them
 * (ADR 0042).
 *
 * <p>Every read of a cost total in this class takes a basis, and there is no
 * overload that does not. A caller who wants "the delivery cost" without saying
 * at what basis is asking a question with two answers recognised at different
 * instants, resting on different tax documents, and moving by different
 * mechanisms — an accrual by adjustment, an invoice only by credit note.
 *
 * <p>Live lines are the ones nothing supersedes. The supersession pointer runs
 * forwards, from the replacement to what it replaced, so the table stays
 * genuinely append-only rather than append-plus-one-permitted-update.
 */
@Repository
public class JdbcDeliveryCostStore {

    private final JdbcClient jdbc;

    public JdbcDeliveryCostStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Excludes any line a later line supersedes. */
    private static final String LIVE_LINES = """
             AND NOT EXISTS (SELECT 1 FROM fulfillment.delivery_cost_lines AS successor
                              WHERE successor.supersedes_line_id = line.id)
            """;

    // ------------------------------------------------------------- cost lines

    public void insertLine(CostLineRow line) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", line.id());
        params.put("tenantId", line.tenantId());
        params.put("shipmentId", line.shipmentId());
        params.put("legalEntityId", line.legalEntityId());
        params.put("businessDate", line.businessDate());
        params.put("costPath", line.costPath().name());
        params.put("costBasis", line.costBasis().name());
        params.put("amount", line.amountMinor());
        params.put("currency", line.currency());
        params.put("sourceType", line.sourceType());
        params.put("sourceId", line.sourceId());
        params.put("courierId", line.courierId());
        params.put("providerCode", line.providerCode());
        params.put("supersedes", line.supersedesLineId());
        params.put("recordedBy", line.recordedBy());
        params.put("recognisedAt", JdbcCourierStore.utc(line.recognisedAt()));

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_cost_lines (
                    id, tenant_id, shipment_id, legal_entity_id, business_date,
                    cost_path, cost_basis, amount_minor, currency, source_type, source_id,
                    courier_id, provider_code, recognised_at, supersedes_line_id, recorded_by)
                VALUES (:id, :tenantId, :shipmentId, :legalEntityId, :businessDate,
                    :costPath, :costBasis, :amount, :currency, :sourceType, :sourceId,
                    :courierId, :providerCode, :recognisedAt, :supersedes, :recordedBy)
                """).params(params).update();
    }

    public List<CostLineRow> linesOfShipment(UUID tenantId, UUID shipmentId) {
        return jdbc.sql(SELECT_LINE + """
                 WHERE line.tenant_id = :tenantId AND line.shipment_id = :shipmentId
                """ + LIVE_LINES + " ORDER BY line.recognised_at")
                .param("tenantId", tenantId)
                .param("shipmentId", shipmentId)
                .query(JdbcDeliveryCostStore::mapLine)
                .list();
    }

    /**
     * The total at one basis, split by path, for a date range.
     *
     * <p>Returns both paths as separate figures and never one number. The
     * combined presentation is two lines and a total, with the basis named on
     * every one of them.
     */
    public List<PathTotal> totalsByPath(UUID tenantId, CostBasis basis, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT line.cost_path, line.currency,
                       SUM(line.amount_minor)::bigint AS total_minor,
                       COUNT(DISTINCT line.shipment_id)::int AS shipment_count
                  FROM fulfillment.delivery_cost_lines AS line
                 WHERE line.tenant_id = :tenantId
                   AND line.cost_basis = :basis
                   AND line.business_date BETWEEN :from AND :to
                """ + LIVE_LINES + """
                 GROUP BY line.cost_path, line.currency
                 ORDER BY line.cost_path
                """)
                .param("tenantId", tenantId)
                .param("basis", basis.name())
                .param("from", from)
                .param("to", to)
                .query((ResultSet rs, int rowNumber) -> new PathTotal(
                        CostPath.valueOf(rs.getString("cost_path")),
                        rs.getString("currency"),
                        rs.getLong("total_minor"),
                        rs.getInt("shipment_count")))
                .list();
    }

    /**
     * Shipments carrying a cost line at some other basis than the one asked
     * for.
     *
     * <p>Reported as a count beside the total rather than silently omitted. A
     * report at {@code INVOICED} that quietly dropped every open internal
     * accrual would look like a cheap week.
     */
    public int shipmentsMissingBasis(UUID tenantId, CostBasis basis, LocalDate from, LocalDate to) {
        Integer count = jdbc.sql("""
                SELECT COUNT(DISTINCT line.shipment_id)::int
                  FROM fulfillment.delivery_cost_lines AS line
                 WHERE line.tenant_id = :tenantId
                   AND line.business_date BETWEEN :from AND :to
                   AND line.cost_basis <> :basis
                """ + LIVE_LINES + """
                   AND NOT EXISTS (
                        SELECT 1 FROM fulfillment.delivery_cost_lines AS atBasis
                         WHERE atBasis.tenant_id = line.tenant_id
                           AND atBasis.shipment_id = line.shipment_id
                           AND atBasis.cost_basis = :basis)
                """)
                .param("tenantId", tenantId)
                .param("basis", basis.name())
                .param("from", from)
                .param("to", to)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    // -------------------------------------------------------- partner invoices

    public void insertInvoice(InvoiceRow invoice) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", invoice.id());
        params.put("tenantId", invoice.tenantId());
        params.put("providerCode", invoice.providerCode());
        params.put("invoiceRef", invoice.providerInvoiceRef());
        params.put("legalEntityId", invoice.legalEntityId());
        params.put("start", invoice.periodStart());
        params.put("end", invoice.periodEnd());
        params.put("total", invoice.totalMinor());
        params.put("currency", invoice.currency());
        params.put("importedBy", invoice.importedBy());

        jdbc.sql("""
                INSERT INTO fulfillment.partner_delivery_invoices (
                    id, tenant_id, provider_code, provider_invoice_ref, legal_entity_id,
                    period_start, period_end, total_minor, currency, status,
                    imported_by, imported_at, version)
                VALUES (:id, :tenantId, :providerCode, :invoiceRef, :legalEntityId,
                    :start, :end, :total, :currency, 'IMPORTED', :importedBy, now(), 1)
                """).params(params).update();
    }

    public void insertInvoiceLine(InvoiceLineRow line) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", line.id());
        params.put("tenantId", line.tenantId());
        params.put("invoiceId", line.invoiceId());
        params.put("shipmentRef", line.providerShipmentRef());
        params.put("shipmentId", line.shipmentId());
        params.put("amount", line.amountMinor());
        params.put("currency", line.currency());
        params.put("chargeType", line.chargeType().name());
        params.put("matchStatus", line.matchStatus().name());

        jdbc.sql("""
                INSERT INTO fulfillment.partner_delivery_invoice_lines (
                    id, tenant_id, invoice_id, provider_shipment_ref, shipment_id,
                    amount_minor, currency, charge_type, match_status)
                VALUES (:id, :tenantId, :invoiceId, :shipmentRef, :shipmentId,
                    :amount, :currency, :chargeType, :matchStatus)
                """).params(params).update();
    }

    public boolean matchLine(
            UUID tenantId,
            UUID lineId,
            @Nullable UUID shipmentId,
            MatchStatus status,
            @Nullable Long varianceMinor,
            @Nullable String reasonCode) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", lineId);
        params.put("shipmentId", shipmentId);
        params.put("status", status.name());
        params.put("variance", varianceMinor);
        params.put("reasonCode", reasonCode);

        return jdbc.sql("""
                UPDATE fulfillment.partner_delivery_invoice_lines
                   SET shipment_id = :shipmentId, match_status = :status,
                       variance_minor = :variance, reason_code = :reasonCode, matched_at = now()
                 WHERE tenant_id = :tenantId AND id = :id
                """).params(params).update() == 1;
    }

    public void markInvoiceMatched(UUID tenantId, UUID invoiceId) {
        jdbc.sql("""
                UPDATE fulfillment.partner_delivery_invoices
                   SET status = 'MATCHED', matched_at = now(), version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'IMPORTED'
                """).param("tenantId", tenantId).param("id", invoiceId).update();
    }

    public List<InvoiceLineRow> linesOfInvoice(UUID tenantId, UUID invoiceId) {
        return jdbc.sql(SELECT_INVOICE_LINE + """
                 WHERE tenant_id = :tenantId AND invoice_id = :invoiceId
                 ORDER BY provider_shipment_ref, charge_type
                """)
                .param("tenantId", tenantId)
                .param("invoiceId", invoiceId)
                .query(JdbcDeliveryCostStore::mapInvoiceLine)
                .list();
    }

    public Optional<InvoiceRow> findInvoice(UUID tenantId, UUID invoiceId) {
        return jdbc.sql("""
                SELECT id, tenant_id, provider_code, provider_invoice_ref, legal_entity_id,
                       period_start, period_end, total_minor, currency, status, imported_by
                  FROM fulfillment.partner_delivery_invoices
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", invoiceId)
                .query((ResultSet rs, int rowNumber) -> new InvoiceRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("provider_code"),
                        rs.getString("provider_invoice_ref"),
                        rs.getObject("legal_entity_id", UUID.class),
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getLong("total_minor"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("imported_by")))
                .optional();
    }

    // -------------------------------------------------------------------- rows

    /**
     * One recognised cost, on one path at one basis.
     *
     * <p>{@code legalEntityId} is null until ADR 0038's registry resolves one;
     * {@code sourceId} where the source is a partner booking with no local row;
     * the courier and provider identify whichever path the line is on, so each
     * is null on the other's lines; {@code supersedesLineId} only on a
     * correction.
     */
    public record CostLineRow(
            UUID id,
            UUID tenantId,
            UUID shipmentId,
            @Nullable UUID legalEntityId,
            LocalDate businessDate,
            CostPath costPath,
            CostBasis costBasis,
            long amountMinor,
            String currency,
            String sourceType,
            @Nullable UUID sourceId,
            @Nullable UUID courierId,
            @Nullable String providerCode,
            Instant recognisedAt,
            @Nullable UUID supersedesLineId,
            String recordedBy) {}

    public record PathTotal(CostPath costPath, String currency, long totalMinor, int shipmentCount) {}

    /**
     * A partner invoice as imported.
     *
     * @param legalEntityId null until ADR 0038's registry can resolve one
     */
    public record InvoiceRow(
            UUID id,
            UUID tenantId,
            String providerCode,
            String providerInvoiceRef,
            @Nullable UUID legalEntityId,
            LocalDate periodStart,
            LocalDate periodEnd,
            long totalMinor,
            String currency,
            String status,
            String importedBy) {}

    /**
     * One line of a partner invoice.
     *
     * <p>{@code shipmentId} is null until matching resolves the partner's own
     * reference, and stays null on an {@code UNMATCHED_LINE}; the variance and
     * its reason exist only where matching found one.
     */
    public record InvoiceLineRow(
            UUID id,
            UUID tenantId,
            UUID invoiceId,
            String providerShipmentRef,
            @Nullable UUID shipmentId,
            long amountMinor,
            String currency,
            PartnerChargeType chargeType,
            MatchStatus matchStatus,
            @Nullable Long varianceMinor,
            @Nullable String reasonCode) {}

    // ----------------------------------------------------------------- mapping

    private static final String SELECT_LINE = """
            SELECT line.id, line.tenant_id, line.shipment_id, line.legal_entity_id,
                   line.business_date, line.cost_path, line.cost_basis, line.amount_minor,
                   line.currency, line.source_type, line.source_id, line.courier_id,
                   line.provider_code, line.recognised_at, line.supersedes_line_id,
                   line.recorded_by
              FROM fulfillment.delivery_cost_lines AS line
            """;

    private static final String SELECT_INVOICE_LINE = """
            SELECT id, tenant_id, invoice_id, provider_shipment_ref, shipment_id, amount_minor,
                   currency, charge_type, match_status, variance_minor, reason_code
              FROM fulfillment.partner_delivery_invoice_lines
            """;

    private static CostLineRow mapLine(ResultSet rs, int rowNumber) throws SQLException {
        return new CostLineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("shipment_id", UUID.class),
                rs.getObject("legal_entity_id", UUID.class),
                rs.getObject("business_date", LocalDate.class),
                CostPath.valueOf(rs.getString("cost_path")),
                CostBasis.valueOf(rs.getString("cost_basis")),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("source_type"),
                rs.getObject("source_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                rs.getString("provider_code"),
                // recognised_at is NOT NULL: every insert stamps the recognition instant.
                java.util.Objects.requireNonNull(
                        JdbcCourierStore.instant(rs.getObject("recognised_at", OffsetDateTime.class))),
                rs.getObject("supersedes_line_id", UUID.class),
                rs.getString("recorded_by"));
    }

    private static InvoiceLineRow mapInvoiceLine(ResultSet rs, int rowNumber) throws SQLException {
        return new InvoiceLineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("invoice_id", UUID.class),
                rs.getString("provider_shipment_ref"),
                rs.getObject("shipment_id", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                PartnerChargeType.valueOf(rs.getString("charge_type")),
                MatchStatus.valueOf(rs.getString("match_status")),
                // Null on every line that is not a variance, and getLong would
                // answer zero — a variance of nothing, which is a matched line.
                rs.getObject("variance_minor", Long.class),
                rs.getString("reason_code"));
    }
}
