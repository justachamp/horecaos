package uz.horecaos.platform.assistant.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.assistant.api.AssistantConfigurationKeys;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Everything that decides whether, and how far, the assistant may act for one
 * brand: the plan entitlement (ADR 0021), the per-tenant switch (ADR 0030), and
 * the numbers that bound it.
 *
 * <p>Answering needs all three of: the plan holds {@code assistant.answering.enabled},
 * the tenant holds {@code telegram.conversations.enabled} -- the assistant is a
 * participant in ADR 0059's conversations and has nowhere to speak without them --
 * and the switch {@code assistant.enabled} is on for the brand. Each defaults off.
 * An entitlement is never an authorization decision (ADR 0021); nothing here
 * grants a capability and nothing the assistant does escapes one.
 */
@Component
public class AssistantSettings {

    private final ConfigurationResolver configuration;
    private final EntitlementService entitlements;

    AssistantSettings(ConfigurationResolver configuration, EntitlementService entitlements) {
        this.configuration = configuration;
        this.entitlements = entitlements;
    }

    /** The plan includes the assistant and the conversations it speaks in. */
    public boolean entitled(UUID tenantId) {
        return entitlements.featureEnabled(tenantId, EntitlementKeys.ASSISTANT_ANSWERING_ENABLED)
                && entitlements.featureEnabled(tenantId, EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED);
    }

    /** The per-tenant switch, resolved down to the brand. */
    public boolean switchedOn(UUID tenantId, UUID brandId) {
        return Boolean.TRUE.equals(
                configuration.value(AssistantConfigurationKeys.ENABLED, ResourceScope.brand(tenantId, brandId)));
    }

    public long monthlySpendCeilingUsdCents(UUID tenantId) {
        Long configured = configuration.value(
                AssistantConfigurationKeys.MONTHLY_SPEND_CEILING_USD_CENTS, ResourceScope.tenant(tenantId));
        return configured == null ? 0 : configured;
    }

    public int conversationTurnCap(UUID tenantId, UUID brandId) {
        Integer configured = configuration.value(
                AssistantConfigurationKeys.CONVERSATION_TURN_CAP, ResourceScope.brand(tenantId, brandId));
        return configured == null ? 0 : configured;
    }

    public String priceChannelCode(UUID tenantId, UUID brandId) {
        String configured = configuration.value(
                AssistantConfigurationKeys.PRICE_CHANNEL_CODE, ResourceScope.brand(tenantId, brandId));
        return configured == null || configured.isBlank() ? "STOREFRONT" : configured;
    }
}
