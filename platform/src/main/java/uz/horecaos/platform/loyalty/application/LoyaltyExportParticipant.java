package uz.horecaos.platform.loyalty.application;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportRow;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;

/**
 * The points a customer holds and every movement that made the balance (ADR 0029, ADR 0046): her account per brand and its
 * ledger. The ledger is what she is owed an explanation of, so it comes whole, newest first: what was earned or spent, the
 * balance after, the reason code and the order it belonged to. The staff member behind a manual adjustment is left out; that
 * is the tenant's employee, not her data.
 *
 * <p>Lots (the expiry bookkeeping of the same movements), reservations, clawbacks and the till-sync tables are the ledger's
 * working state and are named, with the reason, in the export inventory's exemptions.
 */
@Component
@ExportsCustomerData(
        name = "loyalty",
        tables = {"loyalty.accounts", "loyalty.entries"})
public class LoyaltyExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;

    public LoyaltyExportParticipant(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        List<Map<String, Object>> accounts = jdbc.sql("""
                SELECT id, brand_id, currency, status, balance_minor, reserved_minor, created_at
                  FROM loyalty.accounts
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows();
        List<Map<String, Object>> entries = jdbc.sql("""
                SELECT e.id, e.account_id, e.entry_type, e.amount_minor, e.balance_after_minor, e.order_id,
                       e.reason_code, e.occurred_at
                  FROM loyalty.entries e
                  JOIN loyalty.accounts a ON a.tenant_id = e.tenant_id AND a.id = e.account_id
                 WHERE a.tenant_id = :tenantId AND a.customer_account_id = :accountId
                 ORDER BY e.occurred_at DESC, e.id DESC
                 LIMIT :limit
                """)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows();
        return ExportSection.of(
                ExportTable.ofNewestFirst("loyalty.accounts", render(accounts)),
                ExportTable.ofNewestFirst("loyalty.entries", render(entries)));
    }

    private static List<Map<String, @Nullable Object>> render(List<Map<String, Object>> raw) {
        return raw.stream().map(ExportRow::render).toList();
    }
}
