package uz.horecaos.platform.customers.application;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
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
 * <p><strong>Staff 9.4.</strong> {@link CustomerListQueryService#exportFiltered} is now also an
 * ADR 0027 maker-checker action above the tenant's row threshold. This adapter does not (yet)
 * distinguish a {@code Pending}/{@code Declined} outcome from "matched nothing": {@code
 * result.rows()} is empty either way, and a scheduled report-export job built on this port reads
 * an empty bundle rather than a reason to wait and retry. In practice this changes nothing unless
 * a tenant authors an {@code audit.approval_policies} row naming {@code
 * ApprovalAction.CUSTOMER_PII_EXPORT} — {@code MissingPolicyMode.ALLOW_WITHOUT_APPROVAL} means an
 * unconfigured tenant sees {@code NotRequired} exactly as before this wave — but a tenant that does
 * configure one should not point a scheduled {@code CUSTOMER_DIRECTORY} export job at a PII column
 * group until this port can surface a pending approval as something other than an empty result.
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
            return new ExportBundle(rows, result.truncated());
        }

        List<AccountSummaryRow> matched = lists.list(tenantId, status, query, null, rowQuota + 1);
        boolean truncated = matched.size() > rowQuota;
        List<AccountSummaryRow> bounded = truncated ? matched.subList(0, rowQuota) : matched;
        List<ExportedRow> rows = bounded.stream()
                .map(row -> new ExportedRow(row.id(), row.status(), row.displayName(), null))
                .toList();
        return new ExportBundle(rows, truncated);
    }
}
