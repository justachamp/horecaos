package uz.horecaos.platform.assistant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.assistant.application.AssistantUsageService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What the assistant has done and cost this month (ADR 0069: "spend is capped
 * and visible"). Read-only, and read from the ledger the ceiling itself is decided
 * from.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/assistant")
@Tag(name = "Assistant usage", description = "Month-to-date turns, outcomes, tokens and spend")
public class AssistantUsageController {

    private final AssistantUsageService usage;

    public AssistantUsageController(AssistantUsageService usage) {
        this.usage = usage;
    }

    @GetMapping("/usage")
    @RequiresCapability(value = Capability.ASSISTANT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The assistant's month so far",
            description = "Calendar month in UTC. Spend is in millionths of a US dollar (one cent is "
                    + "10000); the ceiling is in US cents. ceilingReached is true exactly when the "
                    + "assistant is refusing for that reason.")
    UsageResponse usage(@PathVariable UUID tenantId) {
        return UsageResponse.of(usage.report(tenantId));
    }

    public record UsageResponse(
            String month,
            LocalDate monthStart,
            long turns,
            long answered,
            long refused,
            long escalated,
            long declined,
            long servedFromCache,
            long inputTokens,
            long outputTokens,
            long costUsdMicros,
            long ceilingUsdCents,
            boolean ceilingReached,
            boolean entitled,
            boolean providerConfigured,
            long publishedKnowledgeEntries) {

        static UsageResponse of(AssistantUsageService.Report report) {
            return new UsageResponse(
                    report.month(),
                    report.monthStart(),
                    report.turns(),
                    report.answered(),
                    report.refused(),
                    report.escalated(),
                    report.declined(),
                    report.servedFromCache(),
                    report.inputTokens(),
                    report.outputTokens(),
                    report.costUsdMicros(),
                    report.ceilingUsdCents(),
                    report.ceilingReached(),
                    report.entitled(),
                    report.providerConfigured(),
                    report.publishedKnowledgeEntries());
        }
    }
}
