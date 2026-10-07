package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.commercial.domain.PlatformBillingSettings;

/** The one row of HorecaOS's own bank details (ADR 0095, V0504). */
@Repository
public class JdbcPlatformBillingSettingsStore {

    private static final String COLUMNS = """
            bank_beneficiary, bank_name, bank_account, bank_mfo, bank_tax_id, configured, version,
            updated_by, updated_at, approved_by
            """;

    private final JdbcClient jdbc;

    public JdbcPlatformBillingSettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PlatformBillingSettings find() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM commercial.platform_billing_settings")
                .query(JdbcPlatformBillingSettingsStore::read)
                .single();
    }

    /** The row, locked for the rest of the transaction, so two approved changes cannot interleave. */
    public PlatformBillingSettings lock() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM commercial.platform_billing_settings FOR UPDATE")
                .query(JdbcPlatformBillingSettingsStore::read)
                .single();
    }

    /** Writes real details, which by V0504's constraint must carry two different names. */
    public void configure(
            PlatformBillingSettings next, String proposedBy, String approvedBy, UUID approvalRequestId, Instant now) {
        jdbc.sql("""
                        UPDATE commercial.platform_billing_settings
                           SET bank_beneficiary = :beneficiary, bank_name = :bankName, bank_account = :account,
                               bank_mfo = :mfo, bank_tax_id = :taxId, configured = true,
                               version = version + 1, updated_by = :proposedBy, updated_at = :now,
                               approved_by = :approvedBy, approval_request_id = :requestId
                        """)
                .param("beneficiary", next.beneficiary())
                .param("bankName", next.bankName())
                .param("account", next.account())
                .param("mfo", next.mfo())
                .param("taxId", next.taxId())
                .param("proposedBy", proposedBy)
                .param("approvedBy", approvedBy)
                .param("requestId", approvalRequestId)
                .param("now", utc(now))
                .update();
    }

    private static PlatformBillingSettings read(ResultSet row, int number) throws SQLException {
        return new PlatformBillingSettings(
                row.getString("bank_beneficiary"),
                row.getString("bank_name"),
                row.getString("bank_account"),
                row.getString("bank_mfo"),
                row.getString("bank_tax_id"),
                row.getBoolean("configured"),
                row.getLong("version"),
                row.getString("updated_by"),
                Objects.requireNonNull(row.getObject("updated_at", OffsetDateTime.class))
                        .toInstant(),
                row.getString("approved_by"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
