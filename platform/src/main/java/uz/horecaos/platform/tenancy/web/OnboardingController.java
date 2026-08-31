package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingService;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingTemplateService;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingTemplateService.TemplateView;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Tenant onboarding (ADR 0008).
 *
 * <p>The step list is the point of these endpoints. Support answering "why is
 * this tenant not live" should read one response, not a log.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/onboarding-runs")
@Tag(name = "Tenant onboarding", description = "Resumable onboarding runs and activation")
public class OnboardingController {

    private final OnboardingService onboarding;
    private final OnboardingTemplateService templates;
    private final JdbcClient jdbc;
    private final CurrentActor currentActor;

    public OnboardingController(
            OnboardingService onboarding,
            OnboardingTemplateService templates,
            JdbcClient jdbc,
            CurrentActor currentActor) {
        this.onboarding = onboarding;
        this.templates = templates;
        this.jdbc = jdbc;
        this.currentActor = currentActor;
    }

    @PostMapping
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, mutating = true)
    @Operation(
            summary = "Start an onboarding run",
            description = "Omit templateId to use the platform's current default template (Gap B).")
    ResponseEntity<Map<String, Object>> start(@PathVariable UUID tenantId, @Valid @RequestBody StartRequest request) {

        TemplateView template =
                request.templateId() == null ? templates.currentDefault() : templates.get(request.templateId());

        UUID runId = onboarding.startRun(
                tenantId,
                template.id(),
                template.version(),
                Map.of(
                        "ownerEmail", request.ownerEmail() == null ? "" : request.ownerEmail(),
                        "ownerSubjectId", request.ownerSubjectId() == null ? "" : request.ownerSubjectId(),
                        "defaultConfiguration", template.defaultConfiguration()),
                actor());

        return ResponseEntity.ok(Map.of("runId", runId));
    }

    @GetMapping("/current")
    @RequiresCapability(Capability.TENANT_READ)
    @Operation(summary = "The tenant's current onboarding run")
    RunView current(@PathVariable UUID tenantId) {
        UUID runId = jdbc.sql("""
                SELECT id FROM tenant.onboarding_runs
                 WHERE tenant_id = :tenantId
                 ORDER BY started_at DESC LIMIT 1
                """)
                .param("tenantId", tenantId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This tenant has no onboarding run"));
        return view(tenantId, runId);
    }

    @GetMapping("/{runId}")
    @RequiresCapability(Capability.TENANT_READ)
    @Operation(
            summary = "One onboarding run with every step",
            description = "Blocked steps are listed with the decision that would unblock them, "
                    + "so a tenant that is live without a check is visible rather than implied.")
    RunView get(@PathVariable UUID tenantId, @PathVariable UUID runId) {
        return view(tenantId, runId);
    }

    @PostMapping("/{runId}/resume")
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, mutating = true)
    @Operation(
            summary = "Reopen failed steps",
            description = "Completed steps are never reset; a retry reconciles external work.")
    ResponseEntity<Map<String, Object>> resume(
            @PathVariable UUID tenantId, @PathVariable UUID runId, @Valid @RequestBody ReasonRequest request) {

        int reopened = onboarding.resume(runId, actor(), request.reason());
        return ResponseEntity.ok(Map.of("reopenedSteps", reopened));
    }

    @PostMapping("/{runId}/activate")
    @RequiresCapability(value = Capability.TENANT_WRITE, mutating = true)
    @Operation(
            summary = "Activate the tenant",
            description = "Requires every required step to have completed, plus platform approval "
                    + "where a policy demands it. Activating twice produces one transition.")
    ResponseEntity<OnboardingService.ActivationOutcome> activate(
            @PathVariable UUID tenantId, @PathVariable UUID runId, @Valid @RequestBody ReasonRequest request) {

        var outcome = onboarding.activate(runId, actor(), request.reason());
        return ResponseEntity.ok(outcome);
    }

    private RunView view(UUID tenantId, UUID runId) {
        var run = jdbc.sql("""
                SELECT id, tenant_id, status, current_phase, started_by, last_error
                  FROM tenant.onboarding_runs WHERE id = :runId AND tenant_id = :tenantId
                """)
                .param("runId", runId)
                .param("tenantId", tenantId)
                .query((rs, n) -> new RunSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("current_phase"),
                        rs.getString("started_by"),
                        rs.getString("last_error")))
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Unknown onboarding run"));

        List<StepView> steps = jdbc.sql("""
                SELECT step_key, phase, status, required, attempt_count,
                       last_error_code, last_error, external_reference
                  FROM tenant.onboarding_steps
                 WHERE run_id = :runId ORDER BY sequence_number
                """)
                .param("runId", runId)
                .query((rs, n) -> new StepView(
                        rs.getString("step_key"),
                        rs.getString("phase"),
                        rs.getString("status"),
                        rs.getBoolean("required"),
                        rs.getInt("attempt_count"),
                        rs.getString("last_error_code"),
                        rs.getString("last_error"),
                        rs.getString("external_reference")))
                .list();

        return new RunView(run, steps, onboarding.outstandingRequiredSteps(runId));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    /**
     * Starts an onboarding run.
     *
     * @param templateId omit to use the platform's current default template
     *                   (Gap B) — {@code GET .../control-plane/onboarding-templates/default}
     *                   shows which one that resolves to. The template's own
     *                   {@code version} is always used; a caller cannot pin a
     *                   run to a version other than the one {@code templateId}
     *                   currently names.
     */
    public record StartRequest(
            UUID templateId,
            @Size(max = 320) String ownerEmail,
            @Size(max = 255) String ownerSubjectId) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record RunSummary(UUID id, String status, String currentPhase, String startedBy, String lastError) {}

    /**
     * One onboarding run with every step.
     *
     * @param outstandingRequired what still blocks activation, named rather than implied
     */
    public record RunView(RunSummary run, List<StepView> steps, List<String> outstandingRequired) {}

    public record StepView(
            String stepKey,
            String phase,
            String status,
            boolean required,
            int attemptCount,
            String errorCode,
            String detail,
            String externalReference) {}
}
