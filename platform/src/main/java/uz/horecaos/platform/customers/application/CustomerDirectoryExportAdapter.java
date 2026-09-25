package uz.horecaos.platform.customers.application;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.customers.api.CustomerDirectoryExportPort;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AccountSummaryRow;

/**
 * Implements {@link CustomerDirectoryExportPort} over this module's own {@link
 * CustomerListQueryService} — see that interface's own doc for why the port exists at all rather
 * than {@code reporting} calling the service directly.
 *
 * <p>Public, not package-private: {@code reporting.application.ReportExportServiceTests} wires it
 * by hand (against a real Testcontainers database, the same shape {@code
 * CustomerIdentityTests} already wires {@code CustomerListQueryService}) rather than standing up a
 * Spring context, so the constructor has to be reachable from outside this package.
 *
 * <p><strong>Staff 9.4.</strong> {@link CustomerListQueryService#exportFiltered} is also an ADR
 * 0027 maker-checker action above the tenant's row threshold, and on a {@code Pending}/{@code
 * Declined} outcome {@code result.rows()} is empty for a reason that has nothing to do with the
 * filter. This adapter carries {@code result.approval()} straight through on {@link
 * ExportBundle#approval()} rather than collapsing it into the row list, so a caller — {@code
 * ReportExportService} is the only production one reachable from the queued/scheduled report-export
 * path — can tell "matched nothing" from "a second signature is still outstanding" and must not
 * treat the two the same. The {@code includePhone = false} branch below never touches an approval
 * policy at all, so it always carries {@link ApprovalOutcome.NotRequired}.
 */
@Component
public class CustomerDirectoryExportAdapter implements CustomerDirectoryExportPort {

    private final CustomerListQueryService lists;

    public CustomerDirectoryExportAdapter(CustomerListQueryService lists) {
        this.lists = lists;
    }

    @Override
    public ExportBundle export(
            java.util.UUID tenantId,
            @Nullable String status,
            @Nullable String query,
            boolean includePhone,
            int rowQuota,
            String purpose,
            ActorRef actor) {

        if (includePhone) {
            CustomerListQueryService.ExportResult result =
                    lists.exportFiltered(tenantId, status, query, purpose, actor);
            List<ExportedRow> rows = result.rows().stream()
                    .map(row -> new ExportedRow(row.accountId(), row.status(), row.displayName(), row.phone()))
                    .toList();
            return new ExportBundle(rows, result.truncated(), result.approval());
        }

        List<AccountSummaryRow> matched = lists.list(tenantId, status, query, null, rowQuota + 1);
        boolean truncated = matched.size() > rowQuota;
        List<AccountSummaryRow> bounded = truncated ? matched.subList(0, rowQuota) : matched;
        List<ExportedRow> rows = bounded.stream()
                .map(row -> new ExportedRow(row.id(), row.status(), row.displayName(), null))
                .toList();
        return new ExportBundle(rows, truncated, new ApprovalOutcome.NotRequired());
    }
}
