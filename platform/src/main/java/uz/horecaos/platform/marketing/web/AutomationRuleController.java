package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
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
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.application.AutomationRuleService;
import uz.horecaos.platform.marketing.domain.AutomationTriggerType;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.AutomationRuleRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRunStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRunStore.AutomationRunRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Authoring and arming automations (gap-map row 6.5, ADR 0044 Triggers).
 *
 * <p>Two capabilities, deliberately not one: {@link Capability#CAMPAIGN_AUTHOR}
 * writes an inert rule and {@link Capability#CAMPAIGN_APPROVE} arms or disarms
 * it — the same second-signature split {@code CampaignService#approve} already
 * gives a campaign, restated here for "nothing sends without a human".
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/automations")
@Tag(
        name = "Marketing automations",
        description = "Unattended triggers: birthday, inactivity, cart abandonment (ADR 0044)")
public class AutomationRuleController {

    private static final int RUN_HISTORY_LIMIT = 100;

    private final AutomationRuleService rules;
    private final JdbcAutomationRunStore runs;
    private final CurrentActor currentActor;

    public AutomationRuleController(
            AutomationRuleService rules, JdbcAutomationRunStore runs, CurrentActor currentActor) {
        this.rules = rules;
        this.runs = runs;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every automation rule this brand has authored",
            description = "q-rule-list's own priority order (X.25's first live consumer): lowest "
                    + "priority first, ties broken by name.")
    public ResponseEntity<List<AutomationRuleResponse>> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(rules.list(tenantId, brandId).stream()
                .map(AutomationRuleResponse::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Author an automation rule",
            description = "Written inert: active defaults to false at the schema, and only "
                    + "activation (a separate, campaign.approve-gated act) arms it. \"Nothing "
                    + "sends without a human\" is this split, not a checkbox on this form.")
    public ResponseEntity<AutomationRuleResponse> create(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody AutomationRuleRequest body) {
        try {
            UUID id = rules.create(
                    tenantId,
                    brandId,
                    body.name(),
                    triggerType(body.triggerType()),
                    channel(body.channel()),
                    body.consentPurpose(),
                    body.templateKey(),
                    body.triggerConfig(),
                    body.cooldownDays(),
                    actorId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .eTag(AggregateVersion.toETag(1))
                    .body(AutomationRuleResponse.of(rules.require(tenantId, brandId, id)));
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @PutMapping("/{ruleId}")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Rewrite an inactive rule",
            description = "Refused while the rule is active: a marketer deactivates a running "
                    + "automation before changing what it fires, the same \"stop, then change\" "
                    + "posture a running campaign takes.")
    public ResponseEntity<AutomationRuleResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody AutomationRuleRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            boolean updated = rules.update(
                    tenantId,
                    brandId,
                    ruleId,
                    (int) expected,
                    body.name(),
                    channel(body.channel()),
                    body.consentPurpose(),
                    body.templateKey(),
                    body.triggerConfig(),
                    body.cooldownDays());
            if (!updated) {
                AutomationRuleRow current = rules.require(tenantId, brandId, ruleId);
                throw ApiException.staleVersion(expected, current.version());
            }
            return ResponseEntity.ok(AutomationRuleResponse.of(rules.require(tenantId, brandId, ruleId)));
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        } catch (IllegalStateException refused) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, refused.getMessage());
        }
    }

    @PostMapping("/{ruleId}/activations")
    @RequiresCapability(value = Capability.CAMPAIGN_APPROVE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Arm the rule",
            description = "The one act that lets this rule fire unattended. Gated by "
                    + "campaign.approve, the same second signature a campaign's own launch takes.")
    public ResponseEntity<AutomationRuleResponse> activate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID ruleId,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        boolean activated = rules.activate(tenantId, brandId, ruleId, (int) expected, actor(), correlationId());
        if (!activated) {
            AutomationRuleRow current = rules.require(tenantId, brandId, ruleId);
            throw ApiException.staleVersion(expected, current.version());
        }
        return ResponseEntity.ok(AutomationRuleResponse.of(rules.require(tenantId, brandId, ruleId)));
    }

    @PostMapping("/{ruleId}/deactivations")
    @RequiresCapability(value = Capability.CAMPAIGN_APPROVE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Stop the rule from firing",
            description = "Reversible: reactivating asks for a fresh If-Match, not a fresh design.")
    public ResponseEntity<AutomationRuleResponse> deactivate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID ruleId,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        boolean deactivated = rules.deactivate(tenantId, brandId, ruleId, (int) expected, actor(), correlationId());
        if (!deactivated) {
            AutomationRuleRow current = rules.require(tenantId, brandId, ruleId);
            throw ApiException.staleVersion(expected, current.version());
        }
        return ResponseEntity.ok(AutomationRuleResponse.of(rules.require(tenantId, brandId, ruleId)));
    }

    @PutMapping("/reorder")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Rewrite priority for the whole list",
            description = "q-rule-list's own whole-set contract: every id the caller means to "
                    + "keep, in the order it now wants, the same shape order-outcome-reasons' own "
                    + "reorder endpoint gives its sibling screen.")
    public ResponseEntity<List<AutomationRuleResponse>> reorder(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody ReorderRequest body) {
        rules.reorder(tenantId, brandId, body.orderedRuleIds());
        return ResponseEntity.ok(rules.list(tenantId, brandId).stream()
                .map(AutomationRuleResponse::of)
                .toList());
    }

    @GetMapping("/{ruleId}/runs")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "This rule's recent firing history",
            description = "Fired, refused (with its reason), or cancelled (the customer ordered "
                    + "before the send) — the same per-recipient honesty a campaign's own "
                    + "recipient list gives, at automation scale.")
    public ResponseEntity<List<AutomationRunResponse>> runs(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID ruleId) {
        // A read of one named resource is 404 for a wrong id, matching every
        // other GET .../{id} in this module (OperationsMarketingController's
        // own campaign/audience detail reads) — rules.require()'s
        // IllegalArgumentException is right for a mutation referencing a bad
        // id (400) but wrong for a read naming one.
        try {
            rules.require(tenantId, brandId, ruleId);
        } catch (IllegalArgumentException notFound) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, notFound.getMessage());
        }
        return ResponseEntity.ok(runs.recentByRule(tenantId, ruleId, RUN_HISTORY_LIMIT).stream()
                .map(AutomationRunResponse::of)
                .toList());
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private UUID actorId() {
        String subject = currentActor.get().subject();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This principal has no identifier that can be recorded as an author");
        }
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    private static AutomationTriggerType triggerType(String value) {
        try {
            return AutomationTriggerType.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, value + " is not an offered automation trigger");
        }
    }

    private static MarketingChannel channel(String value) {
        try {
            return MarketingChannel.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, value + " is not a marketing channel");
        }
    }

    public record AutomationRuleRequest(
            @NotBlank String name,
            @NotBlank String triggerType,
            @NotBlank String channel,
            @NotBlank String consentPurpose,
            @NotBlank String templateKey,
            @NotEmpty Map<@NotBlank String, @NotNull Integer> triggerConfig,
            @Positive int cooldownDays) {}

    public record ReorderRequest(@NotEmpty List<UUID> orderedRuleIds) {}

    public record AutomationRuleResponse(
            UUID id,
            String name,
            String triggerType,
            String channel,
            String consentPurpose,
            String templateKey,
            Map<String, Object> triggerConfig,
            int cooldownDays,
            int priority,
            boolean active,
            @Nullable UUID activatedBy,
            @Nullable Instant activatedAt,
            int version) {
        static AutomationRuleResponse of(AutomationRuleRow row) {
            return new AutomationRuleResponse(
                    row.id(),
                    row.name(),
                    row.triggerType(),
                    row.channel(),
                    row.consentPurpose(),
                    row.templateKey(),
                    row.triggerConfig(),
                    row.cooldownDays(),
                    row.priority(),
                    row.active(),
                    row.activatedBy(),
                    row.activatedAt(),
                    row.version());
        }
    }

    public record AutomationRunResponse(
            UUID id,
            UUID customerAccountId,
            String status,
            @Nullable String refusalReason,
            @Nullable String cancelledReason,
            Instant firedAt) {
        static AutomationRunResponse of(AutomationRunRow row) {
            return new AutomationRunResponse(
                    row.id(),
                    row.customerAccountId(),
                    row.status(),
                    row.refusalReason(),
                    row.cancelledReason(),
                    row.firedAt());
        }
    }
}
