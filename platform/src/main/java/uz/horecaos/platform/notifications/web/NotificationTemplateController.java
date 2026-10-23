package uz.horecaos.platform.notifications.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.OtpWording;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.TemplateProviderReviewService;
import uz.horecaos.platform.notifications.application.TemplateTestSendService;
import uz.horecaos.platform.notifications.application.TemplateTestSendService.TestSendOutcome;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.NotificationVariableCatalog;
import uz.horecaos.platform.notifications.domain.PlatformDefaultTemplates;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.VersionRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

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
@Tag(
        name = "Notification templates",
        description = "Per-tenant, per-brand, per-locale message wording and its versions")
public class NotificationTemplateController {

    private final NotificationTemplateService templates;
    private final TemplateTestSendService testSend;
    private final CurrentActor currentActor;

    public NotificationTemplateController(
            NotificationTemplateService templates, TemplateTestSendService testSend, CurrentActor currentActor) {
        this.templates = templates;
        this.testSend = testSend;
        this.currentActor = currentActor;
    }

    @GetMapping("/variable-catalogue")
    @RequiresCapability(value = Capability.NOTIFICATION_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Which merge variables an author may declare, per notification class",
            description = "Fixes the defect that made the editor create-only in practice: the console used "
                    + "to save every version with an empty schema, and the renderer correctly refuses any "
                    + "placeholder that schema does not declare. This is what an author now picks from.")
    public ResponseEntity<List<VariableCatalogueEntry>> variableCatalogue(
            @RequestParam(required = false) @Nullable @Size(max = 64) String templateKey) {
        // ADR 0184, D21: one variable, courierFirstName, exists for one template key only, so the editor
        // names the key it is editing and the catalogue answers for it. Without a key the answer is the
        // class's own list, as it always was.
        return ResponseEntity.ok(NotificationVariableCatalog.all().keySet().stream()
                .sorted()
                .map(notificationClass -> new VariableCatalogueEntry(
                        notificationClass.name(),
                        (templateKey == null
                                        ? NotificationVariableCatalog.forClass(notificationClass)
                                        : NotificationVariableCatalog.forTemplate(notificationClass, templateKey))
                                .stream()
                                        .map(variable ->
                                                new VariableCatalogueVariable(variable.name(), variable.description()))
                                        .toList()))
                .toList());
    }

    @GetMapping("/platform-defaults")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "The wording the platform sends when a brand and its tenant have written none",
            description = "ADR 0020's last resolution layer (ADR 0184): bundled ru, uz-Latn and en wording for each "
                    + "order milestone, read-only. A brand customises one by registering its own template for the "
                    + "key and saving a version, which from then on outranks it. ORDER_COMPLETED has no default: "
                    + "the rating prompt is opt-in by activating a template (ADR 0075).")
    public ResponseEntity<List<PlatformDefaultResponse>> platformDefaults() {
        return ResponseEntity.ok(PlatformDefaultTemplates.all().stream()
                .map(entry -> new PlatformDefaultResponse(
                        entry.templateKey(),
                        entry.notificationClass().name(),
                        entry.consentPurpose(),
                        PlatformDefaultTemplates.CHANNELS.stream()
                                .map(NotificationChannel::name)
                                .toList(),
                        PlatformDefaultTemplates.VERSION,
                        new LinkedHashMap<>(entry.bodies()),
                        List.copyOf(entry.variables())))
                .toList());
    }

    @GetMapping("/otp-wording")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "The sentence a customer reads with a one-time code, read-only",
            description = "The platform's, not the tenant's (ADR 0184, D19): the sentence is sent by the customers "
                    + "module without a template, so there is nothing to edit and no SECURITY template can be created. "
                    + "gatewayApproval says whether this tenant's SMS gateway moderates wordings, in which case the "
                    + "gateway must have approved the platform's sentences before a code reaches a handset.")
    public ResponseEntity<OtpWordingResponse> otpWording(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(new OtpWordingResponse(
                OtpWording.sentences().stream()
                        .map(sentence -> new OtpSentenceResponse(
                                sentence.locale(),
                                sentence.pattern(),
                                sentence.pattern().length()))
                        .toList(),
                templates.smsGatewayModerates(tenantId) ? "GATEWAY_MODERATES" : "NOT_MODERATED"));
    }

    @GetMapping
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "Templates that apply at this brand",
            description = "Includes the tenant's defaults as well as this brand's overrides, "
                    + "because what a brand actually sends is whichever of the two resolution "
                    + "picks, and showing only the overrides hides half the answer.")
    public ResponseEntity<List<TemplateResponse>> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {

        return ResponseEntity.ok(templates.listing(tenantId, brandId).stream()
                .map(listing -> {
                    var row = listing.template();
                    return new TemplateResponse(
                            row.id(),
                            row.brandId(),
                            row.templateKey(),
                            row.notificationClass(),
                            row.channel(),
                            row.consentPurpose(),
                            row.status(),
                            row.activeVersion(),
                            row.version(),
                            row.fulfillmentMode(),
                            row.channelSource(),
                            row.isTenantWide() ? "TENANT" : "BRAND",
                            listing.shownVersion(),
                            listing.locales(),
                            listing.requiredLocales(),
                            listing.providerReview());
                })
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Register a template",
            description = "A consent purpose is required for an optional or marketing class and "
                    + "refused for the others: an order confirmation is a receipt rather than "
                    + "marketing, and gating one on a promotional opt-in would withhold it from "
                    + "somebody who is owed it.")
    public ResponseEntity<IdResponse> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody CreateTemplateRequest request) {

        try {
            // A brand-scoped path creates a brand-scoped template. Authoring the
            // tenant's default is a different act at a different scope, and letting
            // this endpoint do both would let a brand manager rewrite the wording
            // every other brand falls back to.
            return ResponseEntity.ok(new IdResponse(templates.createTemplate(
                    tenantId,
                    brandId,
                    request.templateKey(),
                    request.notificationClass(),
                    request.channel(),
                    request.consentPurpose(),
                    request.fulfillmentMode(),
                    request.channelSource())));
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @PostMapping("/{templateId}/versions")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Save a draft version in every locale",
            description = "Refused unless every language the brand serves is present (ADR 0149), and unless every "
                    + "placeholder is declared by the variables schema. Both failures belong "
                    + "here, where an author can fix them, rather than at send time.")
    public ResponseEntity<VersionResponse> addVersion(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID templateId,
            @Valid @RequestBody AddVersionRequest request) {

        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        request.wordings().forEach((tag, wording) -> {
            MessageLocale locale = MessageLocale.parse(tag)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.VALIDATION_FAILED, "%s is not a supported locale".formatted(tag)));
            wordings.put(
                    locale,
                    new Wording(wording.subject(), wording.body(), wording.coverAssetId(), wording.targetPath()));
        });

        try {
            int versionNumber = templates.addVersion(
                    tenantId,
                    brandId,
                    templateId,
                    wordings,
                    request.variablesSchema(),
                    Boolean.TRUE.equals(request.showItemPrices()));
            // ADR 0091: told here, immediately, rather than left for the author
            // to discover after activating — the exact silent failure this
            // wave's row exists to close. Read back rather than threaded through
            // addVersion's own return type, so every other caller of that
            // service method (several pre-existing tests among them) is
            // untouched by this wave.
            boolean awaitsProviderReview = templates.versions(tenantId, brandId, templateId, versionNumber).stream()
                    .anyMatch(row -> TemplateProviderReviewService.withheld(row.providerReview()));
            return ResponseEntity.ok(new VersionResponse(templateId, versionNumber, awaitsProviderReview));
        } catch (NotificationTemplateService.IncompleteTranslationException incomplete) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, incomplete.getMessage());
        } catch (uz.horecaos.platform.notifications.domain.TemplateRenderer.TemplateContractException undeclared) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, undeclared.getMessage());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @PostMapping("/{templateId}/versions/{versionNumber}/activate")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_ACTIVATE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Make a version the one that is sent",
            description = "Records who approved it. ADR 0020's full approval workflow is "
                    + "deferred; the attribution is not, because copy that reached customers "
                    + "with nobody's name on it cannot be reviewed afterwards.")
    public ResponseEntity<Void> activate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID templateId,
            @PathVariable int versionNumber) {

        try {
            // The approver comes from the verified token, never from the body.
            // Taking it from the request would let anyone holding this capability
            // sign somebody else's name to a copy change.
            templates.activate(
                    tenantId,
                    brandId,
                    templateId,
                    versionNumber,
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

    @GetMapping("/{templateId}/versions")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every version of this template, every locale",
            description = "The read a create-only editor never had a caller for: without this, an "
                    + "author could not read back the wording of a template that already exists. "
                    + "Newest version first; the caller groups rows by versionNumber.")
    public ResponseEntity<List<WordingResponse>> versions(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID templateId) {

        try {
            return ResponseEntity.ok(templates.allVersions(tenantId, brandId, templateId).stream()
                    .map(this::toWordingResponse)
                    .toList());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, refused.getMessage());
        }
    }

    @GetMapping("/{templateId}/versions/{versionNumber}")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND)
    @Operation(summary = "One version, locale by locale")
    public ResponseEntity<List<WordingResponse>> version(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID templateId,
            @PathVariable int versionNumber) {

        try {
            return ResponseEntity.ok(templates.versions(tenantId, brandId, templateId, versionNumber).stream()
                    .map(this::toWordingResponse)
                    .toList());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, refused.getMessage());
        }
    }

    @PostMapping("/{templateId}/versions/{versionNumber}/test-send")
    @RequiresCapability(value = Capability.NOTIFICATION_TEMPLATE_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Send this locale to a test destination, for real",
            description = "SMS only today. Refused, with the reason named, for a wording still awaiting "
                    + "or refused by its SMS gateway (ADR 0091) — the exact case that used to send silently "
                    + "into nothing once the version was activated. The destination is never stored.")
    public ResponseEntity<TestSendResponse> testSend(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID templateId,
            @PathVariable int versionNumber,
            @Valid @RequestBody TestSendRequest request) {

        TestSendOutcome outcome = testSend.testSend(
                tenantId, brandId, templateId, versionNumber, request.locale(), request.destination());
        return ResponseEntity.ok(new TestSendResponse(outcome.status(), outcome.providerStatus(), outcome.errorCode()));
    }

    private WordingResponse toWordingResponse(VersionRow row) {
        return new WordingResponse(
                row.versionNumber(),
                row.locale(),
                row.subjectTemplate(),
                row.bodyTemplate(),
                row.contentHash(),
                row.status(),
                row.approvedBy(),
                templates.declaredVariablesSchema(row),
                row.providerReview(),
                row.providerReviewReference(),
                row.providerReviewNote(),
                row.providerReviewUpdatedAt() == null
                        ? null
                        : row.providerReviewUpdatedAt().toString(),
                row.showItemPrices(),
                row.coverAssetId(),
                row.targetPath());
    }

    /**
     * @param fulfillmentMode gap-map row 10.9a: null for every fulfilment mode,
     *                        set to create a variant that only resolves for
     *                        delivery, pickup or dine-in orders
     * @param channelSource null for every channel source, set to create a
     *                      variant scoped to one inbound channel
     */
    public record CreateTemplateRequest(
            @NotBlank @Size(max = 64) String templateKey,
            @NotNull NotificationClass notificationClass,
            @NotNull NotificationChannel channel,
            @Nullable @Size(max = 64) String consentPurpose,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable SalesChannelSystemType channelSource) {}

    /**
     * One version's draft wording, submitted in every locale at once.
     *
     * @param wordings keyed by locale tag; every supported locale must be present
     * @param variablesSchema the allowlist, as name to declared type. A template
     *                        may name these and nothing else
     */
    public record AddVersionRequest(
            @NotEmpty Map<String, WordingRequest> wordings,
            @NotNull Map<String, String> variablesSchema,
            /**
             * ADR 0184: whether {{productList}} follows each item with its line total. Boxed, because
             * Jackson 3 refuses a body that omits a primitive and the console's older drafts omit it;
             * absent reads as off.
             */
            @Nullable Boolean showItemPrices) {}

    /**
     * @param coverAssetId a push wording's 3:1 cover, an ADR 0010 media asset the tenant owns and has made
     *                     public (ADR 0182, D14). Refused on any other channel
     * @param targetPath the storefront path a tap on a push notification opens: a single leading slash,
     *                   never an absolute URL. Refused on any other channel
     */
    public record WordingRequest(
            @Nullable @Size(max = 200) String subject,
            @NotBlank @Size(max = 4000) String body,
            @Nullable UUID coverAssetId,
            @Nullable @Size(max = 199) String targetPath) {

        public WordingRequest(@Nullable String subject, String body) {
            this(subject, body, null, null);
        }
    }

    public record IdResponse(UUID id) {}

    /**
     * @param awaitsProviderReview ADR 0091: true when at least one locale of
     *                             this version is PENDING or REJECTED with its
     *                             SMS gateway — told here, at save time, rather
     *                             than left for the author to find out after
     *                             activating a wording nothing will ever send.
     */
    public record VersionResponse(UUID templateId, int versionNumber, boolean awaitsProviderReview) {}

    /**
     * @param providerReview ADR 0091: NOT_REQUIRED, PENDING, APPROVED or
     *                        REJECTED. PENDING and REJECTED are withheld from
     *                        sending by {@code NotificationEligibilityService}
     *                        — the state this wave's row exists to surface.
     * @param providerReviewReference the SMS gateway's own reference for an
     *                                 APPROVED review; null otherwise
     * @param providerReviewNote why a REJECTED review was refused, or the
     *                           platform's own note for a PENDING one it
     *                           marked automatically; null for NOT_REQUIRED
     * @param providerReviewUpdatedAt when the review state was last recorded,
     *                                ISO-8601; null for NOT_REQUIRED
     */
    public record WordingResponse(
            int versionNumber,
            String locale,
            @Nullable String subject,
            String body,
            String contentHash,
            String status,
            @Nullable String approvedBy,
            Map<String, String> variablesSchema,
            String providerReview,
            @Nullable String providerReviewReference,
            @Nullable String providerReviewNote,
            @Nullable String providerReviewUpdatedAt,
            boolean showItemPrices,
            @Nullable UUID coverAssetId,
            @Nullable String targetPath) {}

    /**
     * @param fulfillmentMode gap-map row 10.9a: null when this row resolves for
     *                        every fulfilment mode
     * @param channelSource null when this row resolves for every channel source
     */
    public record TemplateResponse(
            UUID id,
            @Nullable UUID brandId,
            String templateKey,
            String notificationClass,
            String channel,
            @Nullable String consentPurpose,
            String status,
            @Nullable Integer activeVersion,
            int version,
            @Nullable String fulfillmentMode,
            @Nullable String channelSource,
            String origin,
            @Nullable Integer shownVersion,
            List<String> versionLocales,
            List<String> requiredLocales,
            String providerReview) {}

    /**
     * @param channels the customer channels the wording is offered on
     * @param version {@code PlatformDefaultTemplates#VERSION}, the handle a sent message freezes
     * @param bodies locale tag to the platform's sentence
     * @param variables the merge variables the sentences name
     */
    public record PlatformDefaultResponse(
            String templateKey,
            String notificationClass,
            @Nullable String consentPurpose,
            List<String> channels,
            int version,
            Map<String, String> bodies,
            List<String> variables) {}

    /**
     * @param gatewayApproval {@code GATEWAY_MODERATES} when the tenant's SMS gateway refuses wordings it has not
     *                        approved, {@code NOT_MODERATED} when it does not
     */
    public record OtpWordingResponse(List<OtpSentenceResponse> sentences, String gatewayApproval) {}

    /**
     * @param text the sentence with {{code}} and {{minutes}} still in it
     * @param characters its length with the placeholders, the figure to keep under one SMS segment
     */
    public record OtpSentenceResponse(String locale, String text, int characters) {}

    /** One class's merge variables, for the editor's VariableChip picker. */
    public record VariableCatalogueEntry(String notificationClass, List<VariableCatalogueVariable> variables) {}

    public record VariableCatalogueVariable(String name, String description) {}

    public record TestSendRequest(
            @NotBlank String locale,
            @NotBlank @Size(max = 64) String destination) {}

    /**
     * What the real send answered. Never the destination — see {@link
     * TemplateTestSendService}'s own doc for why nothing here is stored.
     */
    public record TestSendResponse(
            String status,
            @Nullable String providerStatus,
            @Nullable String errorCode) {}
}
