package uz.horecaos.platform.fiscal.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fiscal.application.FiscalRetentionReport;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0084: blocked fiscal receipts across every tenant, for the control
 * plane's fiscalization board. Retrying one stays the tenant-scoped action it
 * always was; this only stops an operator having to pick tenants one by one
 * to find out which of them has a receipt waiting.
 */
@RestController
@Validated
@Tag(name = "Platform fiscal", description = "ADR 0084: blocked receipts across tenants")
public class PlatformFiscalController {

    private final JdbcFiscalLifecycleStore documents;
    private final FiscalRetentionReport retention;

    public PlatformFiscalController(JdbcFiscalLifecycleStore documents, FiscalRetentionReport retention) {
        this.documents = documents;
        this.retention = retention;
    }

    @GetMapping("/api/v1/control-plane/fiscal/retention")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "How much fiscal evidence each tenant holds, and how much is past its retention",
            description = "Report-only (ADR 0166 Decision 10): nothing is deleted or marked, and no role the "
                    + "application uses may delete a fiscal row. The period is the tenant's, never below the "
                    + "platform's, and flagged provisional while it is the unconfirmed default.")
    List<RetentionRow> retention() {
        return retention.report().stream()
                .map(row -> new RetentionRow(
                        row.tenantId(),
                        row.retentionYears(),
                        row.provisional(),
                        row.documents(),
                        row.pastRetention(),
                        row.retainedAfter(),
                        row.oldest()))
                .toList();
    }

    /** One tenant's evidence against its retention period. */
    public record RetentionRow(
            UUID tenantId,
            int retentionYears,
            boolean provisional,
            long documents,
            long pastRetention,
            Instant retainedAfter,
            @Nullable Instant oldest) {}

    @GetMapping("/api/v1/control-plane/fiscal-documents/blocked")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "Every tenant's blocked receipts, longest-waiting first")
    List<PlatformBlockedDocument> blocked(@RequestParam(defaultValue = "200") @Max(500) int limit) {
        return documents.blockedAcrossTenants(limit).stream()
                .map(row -> new PlatformBlockedDocument(
                        row.document().tenantId(),
                        row.tenantName(),
                        // Cross-tenant by design (ADR 0084); the order-number
                        // projection FiscalDocumentController's own worklist
                        // resolves through OrderDirectory is a tenant-scoped
                        // read this board deliberately does not reach for
                        // every tenant on one poll -- tenantName already
                        // tells this row's reader which restaurant it is.
                        FiscalDocumentController.BlockedDocumentResponse.of(row.document(), null)))
                .toList();
    }

    /** One blocked receipt with the tenant it belongs to named. */
    public record PlatformBlockedDocument(
            UUID tenantId, String tenantName, FiscalDocumentController.BlockedDocumentResponse document) {}
}
