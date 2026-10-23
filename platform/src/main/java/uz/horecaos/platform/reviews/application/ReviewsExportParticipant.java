package uz.horecaos.platform.reviews.application;

import java.util.ArrayList;
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
 * What a customer said about her orders and what the tenant did about it (ADR 0029): her ratings and comments, whether she
 * agreed to them being shown publicly, and the service-recovery cases opened for her with how each moved and ended.
 *
 * <p>The notes staff wrote on a case are their working record about a complaint and are withheld (named in the manifest);
 * so are the staff identifiers. Whether a data subject is entitled to a tenant's internal case notes is a question for
 * legal, and the export does not answer it by including them.
 */
@Component
@ExportsCustomerData(
        name = "reviews",
        tables = {
            "reviews.order_reviews",
            "reviews.review_publications",
            "reviews.recovery_cases",
            "reviews.recovery_case_events"
        })
public class ReviewsExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;
    private final ReviewCommentVault vault;

    public ReviewsExportParticipant(JdbcClient jdbc, ReviewCommentVault vault) {
        this.jdbc = jdbc;
        this.vault = vault;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        List<Map<String, @Nullable Object>> reviews = new ArrayList<>();
        for (Map<String, Object> raw : query(subject, """
                SELECT id, brand_id, location_id, order_id, rating, comment_protected, submitted_at
                  FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY submitted_at DESC, id DESC
                 LIMIT :limit
                """)) {
            reviews.add(vault.renderForExport(subject, raw));
        }
        return new ExportSection(
                List.of(
                        ExportTable.ofNewestFirst("reviews.order_reviews", reviews),
                        plain(subject, "reviews.review_publications", """
                                SELECT p.review_id, p.public_consent, p.comment_state, p.decided_at
                                  FROM reviews.review_publications p
                                  JOIN reviews.order_reviews r ON r.tenant_id = p.tenant_id AND r.id = p.review_id
                                 WHERE r.tenant_id = :tenantId AND r.customer_account_id = :accountId
                                 ORDER BY p.created_at DESC, p.review_id
                                 LIMIT :limit
                                """),
                        plain(subject, "reviews.recovery_cases", """
                                SELECT id, order_id, review_id, source, state, outcome_code, first_response_at,
                                       closed_at, created_at
                                  FROM reviews.recovery_cases
                                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                                 ORDER BY created_at DESC, id DESC
                                 LIMIT :limit
                                """),
                        plain(subject, "reviews.recovery_case_events", """
                                SELECT e.id, e.case_id, e.kind, e.from_state, e.to_state, e.detail_code, e.occurred_at
                                  FROM reviews.recovery_case_events e
                                  JOIN reviews.recovery_cases c ON c.tenant_id = e.tenant_id AND c.id = e.case_id
                                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                                 ORDER BY e.occurred_at DESC, e.id DESC
                                 LIMIT :limit
                                """)),
                List.of("recovery_case_events.staff_notes", "recovery_cases.staff_identifiers"));
    }

    private ExportTable plain(ExportSubject subject, String name, String sql) {
        List<Map<String, @Nullable Object>> rows =
                query(subject, sql).stream().map(ExportRow::render).toList();
        return ExportTable.ofNewestFirst(name, rows);
    }

    private List<Map<String, Object>> query(ExportSubject subject, String sql) {
        return jdbc.sql(sql)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows();
    }
}
