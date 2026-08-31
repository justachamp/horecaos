package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingTemplateService;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingTemplateService.TemplateView;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Read-only ADR 0008 onboarding templates (Gap B of the 2026-08-30 proving
 * run).
 *
 * <p>Platform-wide reference data, not a tenant resource — {@code
 * tenant.onboarding_templates} carries no {@code tenant_id} — so this sits at
 * {@code PLATFORM} scope rather than nested under a tenant path. Authoring
 * and versioning a template is a platform-release decision and is
 * deliberately out of scope here; V0098 seeds the one template a v1
 * deployment needs, and {@code OnboardingController.start} resolves it
 * automatically when a caller omits {@code templateId}.
 */
@RestController
@RequestMapping("/api/v1/control-plane/onboarding-templates")
@Tag(name = "Onboarding templates", description = "Read-only: which templates exist and what they configure (ADR 0008)")
public class OnboardingTemplateController {

    private final OnboardingTemplateService templates;

    public OnboardingTemplateController(OnboardingTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(summary = "Every onboarding template version")
    List<TemplateView> list() {
        return templates.list();
    }

    @GetMapping("/{templateId}")
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(summary = "One onboarding template version")
    TemplateView get(@PathVariable UUID templateId) {
        return templates.get(templateId);
    }

    @GetMapping("/default")
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The template a run uses when none is specified",
            description = "The newest ACTIVE version of the platform's 'default' template.")
    TemplateView currentDefault() {
        return templates.currentDefault();
    }
}
