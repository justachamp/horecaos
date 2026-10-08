package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.commercial.domain.PrepaymentInvoice;

/**
 * Requests for payment of money to be held in a tenant's wallet (ADR 0095, V0506).
 *
 * <p>What an invoice has been paid is the sum of the ledger entries that name it, computed in every read
 * below, and never a column: a stored status beside the ledger is the gap every earlier ledger defect
 * lived in.
 */
@Repository
public class JdbcPrepaymentInvoiceStore {

    private static final String SELECT = """
            SELECT i.id, i.tenant_id, i.number, i.amount_minor, i.currency, i.valid_until,
                   i.bank_beneficiary, i.bank_name, i.bank_account, i.bank_mfo, i.bank_tax_id,
                   i.issued_by, i.issued_at, i.cancelled_by, i.cancelled_at, i.cancel_reason,
                   COALESCE((SELECT SUM(w.amount_minor) FROM commercial.wallet_entries w
                              WHERE w.tenant_id = i.tenant_id AND w.prepayment_invoice_id = i.id), 0) AS paid_minor
              FROM commercial.prepayment_invoices i
            """;

    private final JdbcClient jdbc;

    public JdbcPrepaymentInvoiceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Numbers and files an invoice; the number is the one the tenant quotes in the payment's purpose. */
    public String insert(PrepaymentInvoice invoice) {
        return jdbc.sql("""
                        INSERT INTO commercial.prepayment_invoices (
                            id, tenant_id, number, amount_minor, currency, valid_until,
                            bank_beneficiary, bank_name, bank_account, bank_mfo, bank_tax_id,
                            issued_by, issued_at)
                        VALUES (
                            :id, :tenantId,
                            'PI-' || to_char(CAST(:issuedAt AS timestamptz) AT TIME ZONE 'UTC', 'YYYYMM') || '-'
                                || lpad(nextval('commercial.prepayment_invoice_number_seq')::text, 6, '0'),
                            :amount, :currency, :validUntil,
                            :beneficiary, :bankName, :account, :mfo, :taxId, :issuedBy, :issuedAt)
                        RETURNING number
                        """)
                .param("id", invoice.id())
                .param("tenantId", invoice.tenantId())
                .param("amount", invoice.amountMinor())
                .param("currency", invoice.currency())
                .param("validUntil", utc(invoice.validUntil()))
                .param("beneficiary", invoice.bankBeneficiary())
                .param("bankName", invoice.bankName())
                .param("account", invoice.bankAccount())
                .param("mfo", invoice.bankMfo())
                .param("taxId", invoice.bankTaxId())
                .param("issuedBy", invoice.issuedBy())
                .param("issuedAt", utc(invoice.issuedAt()))
                .query(String.class)
                .single();
    }

    public Optional<PrepaymentInvoice> find(UUID tenantId, UUID id) {
        return jdbc.sql(SELECT + " WHERE i.tenant_id = :tenantId AND i.id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(JdbcPrepaymentInvoiceStore::read)
                .optional();
    }

    public Optional<PrepaymentInvoice> findByNumber(UUID tenantId, String number) {
        return jdbc.sql(SELECT + " WHERE i.tenant_id = :tenantId AND i.number = :number")
                .param("tenantId", tenantId)
                .param("number", number)
                .query(JdbcPrepaymentInvoiceStore::read)
                .optional();
    }

    /** Newest first. */
    public List<PrepaymentInvoice> list(UUID tenantId) {
        return jdbc.sql(SELECT + " WHERE i.tenant_id = :tenantId ORDER BY i.issued_at DESC, i.id DESC")
                .param("tenantId", tenantId)
                .query(JdbcPrepaymentInvoiceStore::read)
                .list();
    }

    /** Invoices that are neither cancelled, expired nor paid in full: the ones still asking for money. */
    public int countOpen(UUID tenantId, Instant now) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM (""" + SELECT + """
                             WHERE i.tenant_id = :tenantId AND i.cancelled_at IS NULL AND i.valid_until > :now) open_ones
                         WHERE open_ones.paid_minor < open_ones.amount_minor
                        """)
                .param("tenantId", tenantId)
                .param("now", utc(now))
                .query(Integer.class)
                .single();
    }

    /** Cancels once; false when it was already cancelled. The column grant lets the application write nothing else. */
    public boolean cancel(UUID tenantId, UUID id, String cancelledBy, String reason, Instant now) {
        return jdbc.sql("""
                                UPDATE commercial.prepayment_invoices
                                   SET cancelled_by = :by, cancelled_at = :now, cancel_reason = :reason
                                 WHERE tenant_id = :tenantId AND id = :id AND cancelled_at IS NULL
                                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("by", cancelledBy)
                        .param("reason", reason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    private static PrepaymentInvoice read(ResultSet row, int number) throws SQLException {
        return new PrepaymentInvoice(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("number"),
                row.getLong("amount_minor"),
                row.getString("currency"),
                Objects.requireNonNull(instant(row, "valid_until")),
                row.getString("bank_beneficiary"),
                row.getString("bank_name"),
                row.getString("bank_account"),
                row.getString("bank_mfo"),
                row.getString("bank_tax_id"),
                row.getString("issued_by"),
                Objects.requireNonNull(instant(row, "issued_at")),
                row.getString("cancelled_by"),
                instant(row, "cancelled_at"),
                row.getString("cancel_reason"),
                row.getLong("paid_minor"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
