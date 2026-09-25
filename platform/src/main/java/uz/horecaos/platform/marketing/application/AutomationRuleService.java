package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.domain.AutomationTriggerType;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.AutomationRuleRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.NewRule;

/**
 * Authoring and arming an automation rule (gap-map row 6.5, ADR 0044 Triggers).
 *
 * <p>"Nothing sends without a human" is two separate human acts, deliberately
 * not one: {@link #create} writes an inert rule — {@code active} defaults to
 * {@code false} at the schema (V0414) — and {@link #activate} is the second,
 * distinct act that arms it, gated by {@code campaign.approve} rather than
 * {@code campaign.author} at the controller. That mirrors the second-signature
 * shape {@code CampaignService#approve} already gives a campaign: the person who
 * writes a rule and the person who turns it loose may be, but need not be, the
 * same principal.
 */
@Service
public class AutomationRuleService {

    private final JdbcAutomationRuleStore rules;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final Clock clock;

    public AutomationRuleService(
            JdbcAutomationRuleStore rules, ObjectMapper objectMapper, AuditRecorder audit, Clock clock) {
        this.rules = rules;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UUID create(
            UUID tenantId,
            UUID brandId,
            String name,
            AutomationTriggerType triggerType,
            MarketingChannel channel,
            String consentPurpose,
            String templateKey,
            Map<String, Integer> triggerConfig,
            int cooldownDays,
            UUID authorId) {

        requireValid(name, consentPurpose, templateKey, cooldownDays);
        requireConfigured(triggerType, triggerConfig);

        UUID id = Ids.newId();
        rules.insert(new NewRule(
                id,
                tenantId,
                brandId,
                name,
                triggerType.name(),
                channel.name(),
                consentPurpose,
                templateKey,
                triggerConfig,
                cooldownDays,
                nextPriority(tenantId, brandId),
                authorId,
                clock.instant()));
        return id;
    }

    @Transactional
    public boolean update(
            UUID tenantId,
            UUID brandId,
            UUID ruleId,
            int expectedVersion,
            String name,
            MarketingChannel channel,
            String consentPurpose,
            String templateKey,
            Map<String, Integer> triggerConfig,
            int cooldownDays) {

        AutomationRuleRow rule = require(tenantId, brandId, ruleId);
        if (rule.active()) {
            throw new IllegalStateException(
                    "Rule %s is active; deactivate it before changing what it fires".formatted(ruleId));
        }
        requireValid(name, consentPurpose, templateKey, cooldownDays);
        AutomationTriggerType triggerType = AutomationTriggerType.valueOf(rule.triggerType());
        requireConfigured(triggerType, triggerConfig);

        return rules.update(
                tenantId,
                ruleId,
                expectedVersion,
                name,
                channel.name(),
                consentPurpose,
                templateKey,
                objectMapper.writeValueAsString(triggerConfig),
                cooldownDays,
                clock.instant());
    }

    /** The human act that arms a rule. See this class's own doc. */
    @Transactional
    public boolean activate(
            UUID tenantId, UUID brandId, UUID ruleId, int expectedVersion, ActorRef actor, String correlationId) {
        AutomationRuleRow rule = require(tenantId, brandId, ruleId);
        Instant now = clock.instant();
        boolean activated = rules.activate(tenantId, ruleId, expectedVersion, subjectOf(actor), now);

        audit.record(AuditFact.of("MARKETING_AUTOMATION_RULE_ACTIVATED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("MarketingAutomationRule", ruleId)
                .outcome(activated ? AuditFact.Outcome.SUCCEEDED : AuditFact.Outcome.REJECTED)
                .because("Operator armed the automation")
                .changed(Map.of("triggerType", rule.triggerType(), "channel", rule.channel()))
                .usingCapability("campaign.approve")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return activated;
    }

    @Transactional
    public boolean deactivate(
            UUID tenantId, UUID brandId, UUID ruleId, int expectedVersion, ActorRef actor, String correlationId) {
        require(tenantId, brandId, ruleId);
        Instant now = clock.instant();
        boolean deactivated = rules.deactivate(tenantId, ruleId, expectedVersion, now);

        audit.record(AuditFact.of("MARKETING_AUTOMATION_RULE_DEACTIVATED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("MarketingAutomationRule", ruleId)
                .outcome(deactivated ? AuditFact.Outcome.SUCCEEDED : AuditFact.Outcome.REJECTED)
                .because("Operator stopped the automation")
                .changed(Map.of())
                .usingCapability("campaign.approve")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return deactivated;
    }

    /** q-rule-list's own whole-set reorder. */
    @Transactional
    public int reorder(UUID tenantId, UUID brandId, List<UUID> orderedRuleIds) {
        return rules.reorder(tenantId, brandId, orderedRuleIds, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<AutomationRuleRow> list(UUID tenantId, UUID brandId) {
        return rules.listByBrand(tenantId, brandId);
    }

    @Transactional(readOnly = true)
    public AutomationRuleRow require(UUID tenantId, UUID brandId, UUID ruleId) {
        AutomationRuleRow rule = rules.find(tenantId, ruleId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No automation rule %s belongs to this tenant".formatted(ruleId)));
        if (!rule.brandId().equals(brandId)) {
            throw new IllegalArgumentException("Rule %s does not belong to brand %s".formatted(ruleId, brandId));
        }
        return rule;
    }

    private int nextPriority(UUID tenantId, UUID brandId) {
        return rules.listByBrand(tenantId, brandId).size();
    }

    private static void requireValid(String name, String consentPurpose, String templateKey, int cooldownDays) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An automation rule needs a name");
        }
        if (consentPurpose == null || consentPurpose.isBlank()) {
            throw new IllegalArgumentException("An automation rule needs a consent purpose");
        }
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("An automation rule needs a template key");
        }
        if (cooldownDays <= 0) {
            throw new IllegalArgumentException("cooldownDays must be positive, was " + cooldownDays);
        }
    }

    private static void requireConfigured(AutomationTriggerType triggerType, Map<String, Integer> triggerConfig) {
        Integer value = triggerConfig.get(triggerType.configKey());
        if (value == null || value < 0) {
            throw new IllegalArgumentException(
                    "%s needs a non-negative %s".formatted(triggerType, triggerType.configKey()));
        }
    }

    private static UUID subjectOf(ActorRef actor) {
        try {
            return UUID.fromString(actor.subject());
        } catch (IllegalArgumentException notAUuid) {
            // A SYSTEM_JOB/MIGRATION actor never reaches #activate (only a human
            // capability holder does), so this is defensive rather than expected.
            throw new IllegalArgumentException("An automation rule is activated by a user actor, not " + actor.type());
        }
    }
}
