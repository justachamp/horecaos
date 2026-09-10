package uz.horecaos.platform.fiscal.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

    public PlatformFiscalController(JdbcFiscalLifecycleStore documents) {
        this.documents = documents;
    }

    @GetMapping("/api/v1/control-plane/fiscal-documents/blocked")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "Every tenant's blocked receipts, longest-waiting first")
    List<PlatformBlockedDocument> blocked(@RequestParam(defaultValue = "200") @Max(500) int limit) {
        return documents.blockedAcrossTenants(limit).stream()
                .map(row -> new PlatformBlockedDocument(
                        row.document().tenantId(),
                        row.tenantName(),
                        FiscalDocumentController.BlockedDocumentResponse.of(row.document())))
                .toList();
    }

    /** One blocked receipt with the tenant it belongs to named. */
    public record PlatformBlockedDocument(
            UUID tenantId, String tenantName, FiscalDocumentController.BlockedDocumentResponse document) {}
}
