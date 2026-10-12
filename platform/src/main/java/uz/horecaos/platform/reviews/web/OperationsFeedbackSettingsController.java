package uz.horecaos.platform.reviews.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reviews.application.FeedbackPromptPolicyService;
import uz.horecaos.platform.reviews.application.FeedbackTagService;
import uz.horecaos.platform.reviews.application.FeedbackTagService.NewTag;
import uz.horecaos.platform.reviews.application.FeedbackTagService.TagEdit;
import uz.horecaos.platform.reviews.application.FeedbackTagService.TagView;
import uz.horecaos.platform.reviews.application.PolicyEditor;
import uz.horecaos.platform.reviews.application.RecoveryPolicyService;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument.PromptRow;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument.SendWindow;
import uz.horecaos.platform.reviews.domain.RecoveryPolicyDocument;
import uz.horecaos.platform.reviews.domain.TagCategory;
import uz.horecaos.platform.reviews.domain.TagSentiment;
import uz.horecaos.platform.reviews.domain.TagStatus;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * §5.5 Feedback settings (ADR 0159, Decisions 2 and 4): the tenant's tag library, the timing of the
 * rating prompt, and the recovery policy that decides which reviews open a case and how long staff have.
 *
 * <p>All of it is configuration, so it uses the capabilities the order-policy card already uses --
 * {@code tenant.configuration.read} and {@code tenant.configuration.write}. The scalar settings (the two
 * flags, public visibility, the minimum count, comment approval) are ordinary configuration keys written
 * through the generic configuration routes, not here.
 *
 * <p>The two policy documents are settable at tenant and brand scope and replaced whole, under the
 * version the last read returned (the ETag, as {@code If-Match}); the response says where each resolved
 * from, so a brand sees whether it is following the tenant.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Feedback settings", description = "The tag library, the rating prompt's timing and the recovery policy")
public class OperationsFeedbackSettingsController {

    private final FeedbackTagService tags;
    private final FeedbackPromptPolicyService prompts;
    private final RecoveryPolicyService recovery;
    private final CurrentActor currentActor;

    public OperationsFeedbackSettingsController(
            FeedbackTagService tags,
            FeedbackPromptPolicyService prompts,
            RecoveryPolicyService recovery,
            CurrentActor currentActor) {
        this.tags = tags;
        this.prompts = prompts;
        this.recovery = recovery;
        this.currentActor = currentActor;
    }

    // ============================================================================== the tag library

    @GetMapping("/feedback/tags")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "The tenant's feedback tag library",
            description = "Every tag, active and retired, in the tenant's order, with its wording in every "
                    + "locale it was written in, its icon, and whether any review carries it (a used tag's "
                    + "code, category and sentiment are frozen). locales is the set the editor offers: "
                    + "the union of the tenant's brands' languages.")
    public TagLibraryResponse listTags(@PathVariable UUID tenantId) {
        TenantLocaleSet locales = tags.localeSet(tenantId);
        List<TagView> library = tags.list(tenantId);
        return new TagLibraryResponse(
                library.stream()
                        .map(tag -> FeedbackTagResponse.of(tenantId, tag))
                        .toList(),
                locales.locales(),
                locales.defaultLocale(),
                FeedbackTagService.MAXIMUM_ACTIVE_TAGS,
                FeedbackTagService.ICON_RATIO_WIDTH + ":" + FeedbackTagService.ICON_RATIO_HEIGHT,
                FeedbackTagService.ICON_MAXIMUM_BYTES);
    }

    @PostMapping("/feedback/tags")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, mutating = true)
    @Operation(
            summary = "Register a feedback tag",
            description = "code is lower case letters, digits and underscores and never changes. The "
                    + "wording is required in the tenant's default language and optional in the others. "
                    + "iconAssetId, if given, must be a tenant-owned PUBLIC image, 3:2, at most 1 MB, "
                    + "uploaded and verified; anything else is VALIDATION_FAILED. At most 40 tags are "
                    + "active; a duplicate code is RESOURCE_CONFLICT.")
    public ResponseEntity<FeedbackTagResponse> createTag(
            @PathVariable UUID tenantId, @Valid @RequestBody NewFeedbackTagRequest body) {
        TagView created = tags.create(
                tenantId,
                new NewTag(
                        body.code(),
                        body.category(),
                        body.sentiment(),
                        body.iconAssetId(),
                        body.sortOrder() == null ? 100 : body.sortOrder(),
                        body.labels()),
                actor());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(created.row().version()))
                .body(FeedbackTagResponse.of(tenantId, created));
    }

    @PostMapping("/feedback/tags/starter-pack")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, mutating = true)
    @Operation(
            summary = "Import the twelve starter tags, once",
            description = "Six praise and six complain, in Russian, Uzbek (Latin) and English, with no "
                    + "icons and none about a courier. They are the tenant's to edit afterwards; "
                    + "RESOURCE_CONFLICT when any of them is already in the library.")
    public ResponseEntity<List<FeedbackTagResponse>> importStarterPack(@PathVariable UUID tenantId) {
        List<FeedbackTagResponse> imported = tags.importStarterPack(tenantId, actor()).stream()
                .map(tag -> FeedbackTagResponse.of(tenantId, tag))
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(imported);
    }

    @PutMapping("/feedback/tags/{tagId}")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, mutating = true)
    @Operation(
            summary = "Edit or retire a feedback tag",
            description = "Whole-tag replace under If-Match (or expectedVersion) with the version the "
                    + "list returned. status RETIRED stops the tag being offered and leaves it on every "
                    + "review that carries it; there is no delete. labels names only the locales the "
                    + "editor showed: every other locale keeps its wording. category and sentiment cannot "
                    + "change once a review carries the tag (UNPROCESSABLE_STATE).")
    public ResponseEntity<FeedbackTagResponse> updateTag(
            @PathVariable UUID tenantId,
            @PathVariable UUID tagId,
            @Valid @RequestBody UpdateTagRequest body,
            HttpServletRequest request) {
        int expected = ExpectedVersions.resolve(request, body.expectedVersion());
        TagView updated = tags.update(
                tenantId,
                tagId,
                new TagEdit(
                        body.category(),
                        body.sentiment(),
                        body.iconAssetId(),
                        body.sortOrder(),
                        body.status(),
                        body.labels() == null ? Map.of() : body.labels(),
                        expected),
                actor());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(updated.row().version()))
                .body(FeedbackTagResponse.of(tenantId, updated));
    }

    // =========================================================================== the prompt's timing

    @GetMapping("/feedback/prompt-policy")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "When customers are asked to rate an order: the tenant's policy",
            description = "Resolves reviews.feedback_prompt at tenant scope. isDefault is true when "
                    + "nothing was published, and the built-in default applies: Telegram on, SMS, email "
                    + "and push off, 30 minutes after completion, given up after 6 hours, inside "
                    + "09:00-21:00. The ETag, and versionAtScope, are the version to send back as If-Match.")
    public ResponseEntity<PromptPolicyResponse> readPromptPolicy(@PathVariable UUID tenantId) {
        return promptResponse(prompts.view(ResourceScope.tenant(tenantId)));
    }

    @PutMapping("/feedback/prompt-policy")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, mutating = true)
    @Operation(
            summary = "Publish the tenant's rating-prompt policy",
            description = "Whole-document replace under If-Match. Rows are keyed by order type "
                    + "(DELIVERY, PICKUP, DINE_IN or *) and channel (TELEGRAM, SMS, EMAIL, PUSH or *); "
                    + "the most specific row wins, the order type before the channel, then the built-in "
                    + "default. delayMinutes 0-1440, giveUpAfterHours 1-72. A policy can only make a "
                    + "prompt rarer or later than today's immediate send. A prompt outside sendWindow "
                    + "(in the branch's time zone) moves to its next opening.")
    public ResponseEntity<PromptPolicyResponse> writePromptPolicy(
            @PathVariable UUID tenantId, @Valid @RequestBody PromptPolicyRequest body, HttpServletRequest request) {
        return promptResponse(prompts.author(
                ResourceScope.tenant(tenantId),
                body.toDocument(),
                ifMatch(request),
                ActorRef.user(actor(), null),
                body.reason()));
    }

    @GetMapping("/brands/{brandId}/feedback/prompt-policy")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The rating-prompt policy in force for a brand",
            description = "Resolved brand then tenant then the built-in default; versionAtScope is the "
                    + "version authored at the brand itself (0 when it follows the tenant).")
    public ResponseEntity<PromptPolicyResponse> readBrandPromptPolicy(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return promptResponse(prompts.view(ResourceScope.brand(tenantId, brandId)));
    }

    @PutMapping("/brands/{brandId}/feedback/prompt-policy")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish a brand's own rating-prompt policy",
            description = "As the tenant route, for one brand.")
    public ResponseEntity<PromptPolicyResponse> writeBrandPromptPolicy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody PromptPolicyRequest body,
            HttpServletRequest request) {
        return promptResponse(prompts.author(
                ResourceScope.brand(tenantId, brandId),
                body.toDocument(),
                ifMatch(request),
                ActorRef.user(actor(), null),
                body.reason()));
    }

    // ================================================================================ the case policy

    @GetMapping("/feedback/recovery-policy")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "Which reviews open a service-recovery case, and the two clocks: the tenant's policy",
            description = "Resolves reviews.recovery at tenant scope. The built-in default: a rating of 2 "
                    + "or less opens a case, first touch within 240 minutes, resolution within 4320 "
                    + "(72 hours), wall-clock, no chat alerts.")
    public ResponseEntity<RecoveryPolicyResponse> readRecoveryPolicy(@PathVariable UUID tenantId) {
        return recoveryResponse(recovery.view(ResourceScope.tenant(tenantId)));
    }

    @PutMapping("/feedback/recovery-policy")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, mutating = true)
    @Operation(
            summary = "Publish the tenant's recovery policy",
            description = "Whole-document replace under If-Match. autoOpenMaxRating 0 (never) to 5, "
                    + "firstResponseMinutes 15-10080, resolutionMinutes 60-43200 and not shorter than the "
                    + "first. A case stores the version it opened under, so an edit never makes an old "
                    + "case late.")
    public ResponseEntity<RecoveryPolicyResponse> writeRecoveryPolicy(
            @PathVariable UUID tenantId, @Valid @RequestBody RecoveryPolicyRequest body, HttpServletRequest request) {
        return recoveryResponse(recovery.author(
                ResourceScope.tenant(tenantId),
                body.toDocument(),
                ifMatch(request),
                ActorRef.user(actor(), null),
                body.reason()));
    }

    @GetMapping("/brands/{brandId}/feedback/recovery-policy")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_READ, scope = ScopeType.BRAND)
    @Operation(summary = "The recovery policy in force for a brand")
    public ResponseEntity<RecoveryPolicyResponse> readBrandRecoveryPolicy(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return recoveryResponse(recovery.view(ResourceScope.brand(tenantId, brandId)));
    }

    @PutMapping("/brands/{brandId}/feedback/recovery-policy")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Publish a brand's own recovery policy", description = "As the tenant route, for one brand.")
    public ResponseEntity<RecoveryPolicyResponse> writeBrandRecoveryPolicy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody RecoveryPolicyRequest body,
            HttpServletRequest request) {
        return recoveryResponse(recovery.author(
                ResourceScope.brand(tenantId, brandId),
                body.toDocument(),
                ifMatch(request),
                ActorRef.user(actor(), null),
                body.reason()));
    }

    // ----------------------------------------------------------------------------------- helpers

    private String actor() {
        return currentActor.get().subject();
    }

    private static int ifMatch(HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        if (expected < 0 || expected > Integer.MAX_VALUE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "If-Match must carry a document version");
        }
        return (int) expected;
    }

    private static ResponseEntity<PromptPolicyResponse> promptResponse(PolicyEditor<FeedbackPromptDocument> editor) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(editor.versionAtScope()))
                .body(PromptPolicyResponse.of(editor));
    }

    private static ResponseEntity<RecoveryPolicyResponse> recoveryResponse(
            PolicyEditor<RecoveryPolicyDocument> editor) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(editor.versionAtScope()))
                .body(RecoveryPolicyResponse.of(editor));
    }

    // ------------------------------------------------------------------------------ wire shapes

    /**
     * @param code        immutable
     * @param sortOrder   boxed and optional: 100 when absent
     * @param labels      locale to wording; the tenant's default locale is required
     */
    public record NewFeedbackTagRequest(
            @NotBlank @Size(max = 48) String code,
            @NotNull TagCategory category,
            @NotNull TagSentiment sentiment,
            @Nullable UUID iconAssetId,
            @Nullable Integer sortOrder,
            @NotNull Map<String, String> labels) {}

    /**
     * @param iconAssetId null removes the icon
     * @param labels      only the locales the editor showed; absent changes no wording
     * @param expectedVersion the tag's version, if not sent as If-Match
     */
    public record UpdateTagRequest(
            @NotNull TagCategory category,
            @NotNull TagSentiment sentiment,
            @Nullable UUID iconAssetId,
            @NotNull Integer sortOrder,
            @NotNull TagStatus status,
            @Nullable Map<String, String> labels,
            @Nullable Integer expectedVersion) {}

    public record FeedbackTagResponse(
            UUID id,
            String code,
            TagCategory category,
            TagSentiment sentiment,
            @Nullable UUID iconAssetId,
            @Nullable String iconUrl,
            int sortOrder,
            TagStatus status,
            Map<String, String> labels,
            boolean used,
            int version) {

        static FeedbackTagResponse of(UUID tenantId, TagView view) {
            return new FeedbackTagResponse(
                    view.row().id(),
                    view.row().code(),
                    view.row().category(),
                    view.row().sentiment(),
                    view.row().iconAssetId(),
                    view.row().iconAssetId() == null
                            ? null
                            : "/api/v1/storefront/tenants/%s/media/%s"
                                    .formatted(tenantId, view.row().iconAssetId()),
                    view.row().sortOrder(),
                    view.row().status(),
                    view.labels(),
                    view.used(),
                    view.row().version());
        }
    }

    public record TagLibraryResponse(
            List<FeedbackTagResponse> tags,
            List<String> locales,
            String defaultLocale,
            int maximumActiveTags,
            String iconAspectRatio,
            long iconMaximumBytes) {}

    public record SendWindowDto(
            @NotBlank String start, @NotBlank String end) {}

    public record PromptRowDto(
            @NotBlank String fulfillmentMode,
            @NotBlank String channel,
            @NotNull Boolean enabled,
            @NotNull Integer delayMinutes,
            @NotNull Integer giveUpAfterHours) {}

    /**
     * @param sendWindow       null means any hour
     * @param inlineWindowDays boxed and optional: 7 when absent
     */
    public record PromptPolicyRequest(
            @Nullable @Valid SendWindowDto sendWindow,
            @Nullable Integer inlineWindowDays,
            @NotNull @Valid List<@Valid PromptRowDto> rows,
            @NotBlank @Size(max = 1000) String reason) {

        FeedbackPromptDocument toDocument() {
            return new FeedbackPromptDocument(
                    sendWindow == null ? null : new SendWindow(sendWindow.start(), sendWindow.end()),
                    inlineWindowDays == null ? 7 : inlineWindowDays,
                    rows.stream()
                            .map(row -> new PromptRow(
                                    row.fulfillmentMode(),
                                    row.channel(),
                                    row.enabled(),
                                    row.delayMinutes(),
                                    row.giveUpAfterHours()))
                            .toList());
        }
    }

    public record PromptPolicyResponse(
            @Nullable SendWindowDto sendWindow,
            int inlineWindowDays,
            List<PromptRowDto> rows,
            boolean isDefault,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<PolicyEditor.Level> levels) {

        static PromptPolicyResponse of(PolicyEditor<FeedbackPromptDocument> editor) {
            FeedbackPromptDocument document = editor.document();
            return new PromptPolicyResponse(
                    document.sendWindow() == null
                            ? null
                            : new SendWindowDto(
                                    document.sendWindow().start(),
                                    document.sendWindow().end()),
                    document.inlineWindowDays(),
                    document.rows().stream()
                            .map(row -> new PromptRowDto(
                                    row.fulfillmentMode(),
                                    row.channel(),
                                    row.enabled(),
                                    row.delayMinutes(),
                                    row.giveUpAfterHours()))
                            .toList(),
                    editor.isDefault(),
                    editor.winningScope(),
                    editor.policyId(),
                    editor.policyVersion(),
                    editor.versionAtScope(),
                    editor.levels());
        }
    }

    public record RecoveryPolicyRequest(
            @NotNull Integer autoOpenMaxRating,
            @NotNull Integer firstResponseMinutes,
            @NotNull Integer resolutionMinutes,
            @Nullable Boolean alertOnOpen,
            @Nullable Boolean alertOnBreach,
            @NotBlank @Size(max = 1000) String reason) {

        RecoveryPolicyDocument toDocument() {
            return new RecoveryPolicyDocument(
                    autoOpenMaxRating,
                    firstResponseMinutes,
                    resolutionMinutes,
                    Boolean.TRUE.equals(alertOnOpen),
                    Boolean.TRUE.equals(alertOnBreach));
        }
    }

    public record RecoveryPolicyResponse(
            int autoOpenMaxRating,
            int firstResponseMinutes,
            int resolutionMinutes,
            boolean alertOnOpen,
            boolean alertOnBreach,
            boolean isDefault,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<PolicyEditor.Level> levels) {

        static RecoveryPolicyResponse of(PolicyEditor<RecoveryPolicyDocument> editor) {
            RecoveryPolicyDocument document = editor.document();
            return new RecoveryPolicyResponse(
                    document.autoOpenMaxRating(),
                    document.firstResponseMinutes(),
                    document.resolutionMinutes(),
                    document.alertOnOpen(),
                    document.alertOnBreach(),
                    editor.isDefault(),
                    editor.winningScope(),
                    editor.policyId(),
                    editor.policyVersion(),
                    editor.versionAtScope(),
                    editor.levels());
        }
    }
}
