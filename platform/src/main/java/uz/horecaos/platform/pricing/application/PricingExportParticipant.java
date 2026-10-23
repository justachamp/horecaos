package uz.horecaos.platform.pricing.application;

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
 * The benefits the tenant granted a customer (ADR 0029): a discount or a gift she holds, when it is valid, whether she used
 * it and on which order. The code's hash is left out; a hash identifies nobody and would be a credential if it were the code.
 */
@Component
@ExportsCustomerData(
        name = "pricing",
        tables = {"pricing.benefit_grants"})
public class PricingExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;

    public PricingExportParticipant(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        List<Map<String, @Nullable Object>> rows = jdbc
                .sql("""
                SELECT id, brand_id, benefit_type, value, maximum_discount_minor, currency, min_basket_minor, code_hint,
                       status, source_type, valid_from, expires_at, redeemed_at, order_id, created_at
                  FROM pricing.benefit_grants
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows()
                .stream()
                .map(ExportRow::render)
                .toList();
        return ExportSection.of(ExportTable.ofNewestFirst("pricing.benefit_grants", rows));
    }
}
