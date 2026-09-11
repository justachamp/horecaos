package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingInputs;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingService;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingTemplateService;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingTemplateService.TemplateView;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.idempotency.Idempotent;

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
    private final FieldProtection protection;

    public OnboardingController(
            OnboardingService onboarding,
            OnboardingTemplateService templates,
            JdbcClient jdbc,
            CurrentActor currentActor,
            FieldProtection protection) {
        this.onboarding = onboarding;
        this.templates = templates;
        this.jdbc = jdbc;
        this.currentActor = currentActor;
        this.protection = protection;
    }

    @PostMapping
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, mutating = true)
    @Operation(
            summary = "Start an onboarding run",
            description = "Omit templateId to use the template suited to the tenant's business type, "
                    + "or the platform's default when none suits it (ADR 0090). Set sampleMenu to "
                    + "create and publish a clearly marked sample menu for the tenant's first brand, "
                    + "so the platform can see the storefront answer before the tenant has authored "
                    + "anything (ADR 0099); omitting it is a no.")
    ResponseEntity<Map<String, Object>> start(@PathVariable UUID tenantId, @Valid @RequestBody StartRequest request) {

        TemplateView template = request.templateId() == null
                ? templates.suggestedFor(businessTypeOf(tenantId)).template()
                : templates.get(request.templateId());

        // ADR 0029: the owner's address is personal data and every step's input
        // is stored, so it is kept encrypted, bound to this tenant, until the
        // owner step has used it to create the account (ADR 0097).
        String ownerEmail =
                request.ownerEmail() == null ? "" : request.ownerEmail().strip();
        String protectedEmail = ownerEmail.isEmpty()
                ? ""
                : protection
                        .protect(tenantId, DataClass.PERSONAL, OnboardingInputs.ownerEmailRecord(tenantId), ownerEmail)
                        .serialize();
        UUID runId = onboarding.startRun(
                tenantId,
                template.id(),
                template.version(),
                Map.of(
                        OnboardingInputs.OWNER_EMAIL_PROTECTED,
                        protectedEmail,
                        "ownerSubjectId",
                        request.ownerSubjectId() == null ? "" : request.ownerSubjectId(),
                        OnboardingInputs.OWNER_LOCALE,
                        OnboardingInputs.locale(request.ownerLocale()),
                        OnboardingInputs.SAMPLE_MENU,
                        Boolean.TRUE.equals(request.sampleMenu()),
                        "defaultConfiguration",
                        template.defaultConfiguration()),
                actor());

        return ResponseEntity.ok(Map.of("runId", runId));
    }

    @GetMapping("/suggested-template")
    @RequiresCapability(Capability.TENANT_ONBOARDING_MANAGE)
    @Operation(
            summary = "The template a new run would start under",
            description = "The newest active template naming the tenant's business type, or the "
                    + "platform's default when none does. The console pre-selects it (ADR 0090).")
    OnboardingTemplateService.Suggestion suggestedTemplate(@PathVariable UUID tenantId) {
        return templates.suggestedFor(businessTypeOf(tenantId));
    }

    private @Nullable String businessTypeOf(UUID tenantId) {
        return jdbc.sql("SELECT business_type FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional()
                .orElse(null);
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
            description = "Completed steps are never reset; a retry reconciles external work. "
                    + "Refused once the run has reached READY, ACTIVE or CANCELLED: nothing claims "
                    + "a step on a run the scheduler no longer drives, so reopening one there would "
                    + "report work that never happens.")
    ResponseEntity<Map<String, Object>> resume(
            @PathVariable UUID tenantId, @PathVariable UUID runId, @Valid @RequestBody ReasonRequest request) {

        int reopened;
        try {
            reopened = onboarding.resume(runId, actor(), request.reason());
        } catch (OnboardingService.ResumeNotPermittedException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage());
        }
        return ResponseEntity.ok(Map.of("reopenedSteps", reopened));
    }

    @PostMapping("/{runId}/cancel")
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, mutating = true)
    @Operation(
            summary = "Abandon a run that has not finished",
            description = "The only way to stop a run: nothing else lets a tenant that started "
                    + "onboarding by mistake, or that will never fix a step, get free of it. Refused "
                    + "once the run has already reached ACTIVE or FAILED, and refused the same way "
                    + "for both — a failed run is repaired by resume (or superseded by a fresh run "
                    + "entirely), never abandoned by cancel, and an activated tenant is not "
                    + "un-activated by cancelling the run that activated it.")
    ResponseEntity<Map<String, Object>> cancel(
            @PathVariable UUID tenantId, @PathVariable UUID runId, @Valid @RequestBody ReasonRequest request) {
        try {
            onboarding.cancel(tenantId, runId, actor(), request.reason());
        } catch (OnboardingService.OnboardingRunNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (OnboardingService.CancellationNotPermittedException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage());
        }
        return ResponseEntity.ok(Map.of("status", "CANCELLED"));
    }

    @PostMapping("/{runId}/validate")
    @RequiresCapability(Capability.TENANT_READ)
    @Idempotent
    @Operation(
            summary = "Dry-run the tenant's current configuration",
            description = "Runs every read-only readiness check now, synchronously, against the "
                    + "tenant's current configuration rather than the run's persisted step status — "
                    + "so a tenant can see what activation would find before committing to a resume "
                    + "or an activate call. Nothing is written: a step's stored status only changes "
                    + "when the scheduler actually executes it. The activation smoke test is not "
                    + "included, because unlike every other check it is not a pure read. Use a fresh "
                    + "Idempotency-Key to see a fresh answer — replaying one returns the same "
                    + "recorded outcome, as ADR 0031 requires for every effectful request, even one "
                    + "with no state of its own to replay.")
    ResponseEntity<OnboardingService.ValidationOutcome> validate(
            @PathVariable UUID tenantId, @PathVariable UUID runId) {
        try {
            return ResponseEntity.ok(onboarding.validate(tenantId, runId));
        } catch (OnboardingService.OnboardingRunNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
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
     * @param sampleMenu whether to create and publish a sample menu for the
     *                   tenant's first brand (ADR 0099). Absent means no: a
     *                   caller that predates this field must not start planting
     *                   sample catalogs in tenants that already have real menus
     */
    public record StartRequest(
            UUID templateId,
            @Size(max = 320) String ownerEmail,
            @Size(max = 255) String ownerSubjectId,
            /* The language the owner's invitation is written in: uz, ru or en (ADR 0097). */
            @Size(max = 8) String ownerLocale,
            /* Whether to create and publish a sample menu (ADR 0099). Absent is a no. */
            @Nullable Boolean sampleMenu) {

        /** A record's generated {@code toString} would print the owner's address. */
        @Override
        public String toString() {
            return "StartRequest[templateId=" + templateId + ", ownerEmail=<redacted>]";
        }
    }

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
