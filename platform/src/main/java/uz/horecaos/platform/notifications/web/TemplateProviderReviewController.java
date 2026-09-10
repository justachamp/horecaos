package uz.horecaos.platform.notifications.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.notifications.application.TemplateProviderReviewService;
import uz.horecaos.platform.notifications.application.TemplateProviderReviewService.ReviewRow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * SMS wordings waiting on their gateway's approval, across tenants (ADR 0091).
 */
@RestController
@Validated
@Tag(name = "Template moderation", description = "ADR 0091: SMS wordings waiting on their gateway's approval")
public class TemplateProviderReviewController {

    private final TemplateProviderReviewService reviews;
    private final CurrentActor currentActor;

    public TemplateProviderReviewController(TemplateProviderReviewService reviews, CurrentActor currentActor) {
        this.reviews = reviews;
        this.currentActor = currentActor;
    }

    @GetMapping("/api/v1/control-plane/template-reviews")
    @RequiresCapability(value = Capability.NOTIFICATION_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "SMS wordings and where each stands with its gateway",
            description = "Versions in force or in draft, awaiting first. A version awaiting or refused "
                    + "is not sent. Filter to one state with ?state=.")
    List<TemplateReviewView> list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(defaultValue = "200") @Max(500) int limit) {
        return reviews.list(state, limit).stream().map(TemplateReviewView::of).toList();
    }

    @PostMapping("/api/v1/control-plane/tenants/{tenantId}/template-versions/{versionId}/provider-review")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_ACTIVATE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Record where an SMS wording stands with its gateway",
            description = "PENDING withholds it from sending until the answer is recorded; APPROVED needs "
                    + "the provider's reference and REJECTED what it objected to. NOT_REQUIRED sends as before.")
    ResponseEntity<Void> record(
            @PathVariable UUID tenantId, @PathVariable UUID versionId, @Valid @RequestBody ReviewRequest body) {
        reviews.record(
                tenantId,
                versionId,
                body.state(),
                body.reference(),
                body.providerNote(),
                ActorRef.user(currentActor.get().subject(), null),
                body.reason());
        return ResponseEntity.noContent().build();
    }

    /** One SMS wording and its review. */
    public record TemplateReviewView(
            UUID versionId,
            UUID tenantId,
            String tenantName,
            String templateKey,
            int versionNumber,
            String locale,
            String status,
            String body,
            String providerReview,
            @Nullable String reference,
            @Nullable String providerNote,
            @Nullable String updatedBy,
            @Nullable String updatedAt) {

        static TemplateReviewView of(ReviewRow row) {
            java.time.Instant updatedAt = row.updatedAt();
            return new TemplateReviewView(
                    row.versionId(),
                    row.tenantId(),
                    row.tenantName(),
                    row.templateKey(),
                    row.versionNumber(),
                    row.locale(),
                    row.status(),
                    row.body(),
                    row.providerReview(),
                    row.reference(),
                    row.providerNote(),
                    row.updatedBy(),
                    updatedAt == null ? null : updatedAt.toString());
        }
    }

    public record ReviewRequest(
            @NotBlank String state,
            @Size(max = 200) String reference,
            @Size(max = 1000) String providerNote,
            @NotBlank @Size(max = 1000) String reason) {}
}
