package uz.qoida.platform.notifications.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.CurrentActor;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.notifications.application.NotificationTemplateService;
import uz.qoida.platform.notifications.application.NotificationTemplateService.Wording;
import uz.qoida.platform.notifications.domain.MessageLocale;
import uz.qoida.platform.notifications.domain.NotificationChannel;
import uz.qoida.platform.notifications.domain.NotificationClass;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * Authoring and approving message wording (ADR 0020).
 *
 * <p>A version is submitted in every locale at once. Saving them one at a time
 * would make a half-translated version a legitimate intermediate state, and
 * intermediate states are what get activated by accident — after which the first
 * customer reading Uzbek gets nothing.
 *
 * <p>Authoring and activation are different capabilities, because writing copy and
 * deciding it may be sent to customers are different acts by different people.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/notification-templates")
@Tag(name = "Notification templates",
        description = "Per-tenant, per-brand, per-locale message wording and its versions")
public class NotificationTemplateController {

    private final NotificationTemplateService templates;
    private final CurrentActor currentActor;

    public NotificationTemplateController(NotificationTemplateService templates,
            CurrentActor currentActor) {
        this.templates = templates;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND)
    @Operation(summary = "Templates that apply at this brand",
            description = "Includes the tenant's defaults as well as this brand's overrides, "
                    + "because what a brand actually sends is whichever of the two resolution "
                    + "picks, and showing only the overrides hides half the answer.")
    public ResponseEntity<List<TemplateResponse>> list(@PathVariable UUID tenantId,
            @PathVariable UUID brandId) {

        return ResponseEntity.ok(templates.forBrand(tenantId, brandId).stream()
                .map(row -> new TemplateResponse(row.id(), row.brandId(), row.templateKey(),
                        row.notificationClass(), row.channel(), row.consentPurpose(),
                        row.status(), row.activeVersion(), row.version()))
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Register a template",
            description = "A consent purpose is required for an optional or marketing class and "
                    + "refused for the others: an order confirmation is a receipt rather than "
                    + "marketing, and gating one on a promotional opt-in would withhold it from "
                    + "somebody who is owed it.")
    public ResponseEntity<IdResponse> create(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @Valid @RequestBody CreateTemplateRequest request) {

        try {
            // A brand-scoped path creates a brand-scoped template. Authoring the
            // tenant's default is a different act at a different scope, and letting
            // this endpoint do both would let a brand manager rewrite the wording
            // every other brand falls back to.
            return ResponseEntity.ok(new IdResponse(templates.createTemplate(tenantId, brandId,
                    request.templateKey(), request.notificationClass(), request.channel(),
                    request.consentPurpose())));
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @PostMapping("/{templateId}/versions")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Save a draft version in every locale",
            description = "Refused unless ru, uz-Latn, and en are all present, and unless every "
                    + "placeholder is declared by the variables schema. Both failures belong "
                    + "here, where an author can fix them, rather than at send time.")
    public ResponseEntity<VersionResponse> addVersion(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID templateId,
            @Valid @RequestBody AddVersionRequest request) {

        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        request.wordings().forEach((tag, wording) -> {
            MessageLocale locale = MessageLocale.parse(tag).orElseThrow(() -> new ApiException(
                    ErrorCode.VALIDATION_FAILED, "%s is not a supported locale".formatted(tag)));
            wordings.put(locale, new Wording(wording.subject(), wording.body()));
        });

        try {
            int versionNumber = templates.addVersion(tenantId, templateId, wordings,
                    request.variablesSchema());
            return ResponseEntity.ok(new VersionResponse(templateId, versionNumber));
        } catch (NotificationTemplateService.IncompleteTranslationException incomplete) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, incomplete.getMessage());
        } catch (uz.qoida.platform.notifications.domain.TemplateRenderer.TemplateContractException
                undeclared) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, undeclared.getMessage());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @PostMapping("/{templateId}/versions/{versionNumber}/activate")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_ACTIVATE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Make a version the one that is sent",
            description = "Records who approved it. ADR 0020's full approval workflow is "
                    + "deferred; the attribution is not, because copy that reached customers "
                    + "with nobody's name on it cannot be reviewed afterwards.")
    public ResponseEntity<Void> activate(@PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID templateId, @PathVariable int versionNumber) {

        try {
            // The approver comes from the verified token, never from the body.
            // Taking it from the request would let anyone holding this capability
            // sign somebody else's name to a copy change.
            templates.activate(tenantId, templateId, versionNumber,
                    currentActor.get().subject());
        } catch (NotificationTemplateService.IncompleteTranslationException incomplete) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, incomplete.getMessage());
        } catch (IllegalStateException conflict) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, conflict.getMessage());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, refused.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{templateId}/versions/{versionNumber}")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND)
    @Operation(summary = "One version, locale by locale")
    public ResponseEntity<List<WordingResponse>> version(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID templateId,
            @PathVariable int versionNumber) {

        return ResponseEntity.ok(templates.versions(tenantId, templateId, versionNumber).stream()
                .map(row -> new WordingResponse(row.locale(), row.subjectTemplate(),
                        row.bodyTemplate(), row.contentHash(), row.status(), row.approvedBy()))
                .toList());
    }

    public record CreateTemplateRequest(
            @NotBlank @Size(max = 64) String templateKey,
            @NotNull NotificationClass notificationClass,
            @NotNull NotificationChannel channel,
            @Size(max = 64) String consentPurpose) { }

    /**
     * @param wordings keyed by locale tag; every supported locale must be present
     * @param variablesSchema the allowlist, as name to declared type. A template
     *                        may name these and nothing else
     */
    public record AddVersionRequest(
            @NotEmpty Map<String, WordingRequest> wordings,
            @NotNull Map<String, String> variablesSchema) { }

    public record WordingRequest(@Size(max = 200) String subject,
            @NotBlank @Size(max = 4000) String body) { }

    public record IdResponse(UUID id) { }

    public record VersionResponse(UUID templateId, int versionNumber) { }

    public record WordingResponse(String locale, String subject, String body, String contentHash,
            String status, String approvedBy) { }

    public record TemplateResponse(UUID id, UUID brandId, String templateKey,
            String notificationClass, String channel, String consentPurpose, String status,
            Integer activeVersion, int version) { }
}
