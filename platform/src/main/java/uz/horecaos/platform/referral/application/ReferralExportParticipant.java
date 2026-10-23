package uz.horecaos.platform.referral.application;

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
 * Her referral code and the referrals she gave or received (ADR 0029): the code, and each redemption where she was the
 * referrer or the referee with what each side was rewarded. The other party appears only as an account id; their name and
 * number are theirs to ask for.
 */
@Component
@ExportsCustomerData(
        name = "referral",
        tables = {"referral.codes", "referral.redemptions"})
public class ReferralExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;

    public ReferralExportParticipant(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        return ExportSection.of(table(subject, "referral.codes", """
                        SELECT id, brand_id, code, status, created_at
                          FROM referral.codes
                         WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                         ORDER BY created_at DESC, id DESC
                         LIMIT :limit
                        """), table(subject, "referral.redemptions", """
                        SELECT id, brand_id, code_id, status, redeemed_at, rewarded_at, qualifying_order_id,
                               referrer_customer_account_id, referee_customer_account_id,
                               referrer_reward_minor, referee_reward_minor
                          FROM referral.redemptions
                         WHERE tenant_id = :tenantId
                           AND (referrer_customer_account_id = :accountId OR referee_customer_account_id = :accountId)
                         ORDER BY redeemed_at DESC, id DESC
                         LIMIT :limit
                        """));
    }

    private ExportTable table(ExportSubject subject, String name, String sql) {
        List<Map<String, @Nullable Object>> rows = jdbc
                .sql(sql)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows()
                .stream()
                .map(ExportRow::render)
                .toList();
        return ExportTable.ofNewestFirst(name, rows);
    }
}
