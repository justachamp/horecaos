package uz.horecaos.platform.ordering.application;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * Resolves the {@code ordering.lateness} policy (ADR 0030, orders.md §2.7,
 * gap map rows {@code 1.1g}/{@code X.39}/{@code 10.3b}).
 *
 * <p>Precedence is not implemented here, matching {@code
 * OrderAcceptancePolicyService}'s own doc: it comes from the shared ADR 0030
 * mechanism, so lateness resolves by exactly the same rule as every other
 * scoped behavior in the platform. This service adds the platform default and
 * the one rule that joins the document to a setting.
 *
 * <p>Authoring is {@link OrderLatenessPolicyAuthoringService}'s; nothing here
 * writes. What is authored is an {@link OrderLatenessDocument}, and what this
 * service answers with -- the same answer both boards, the kitchen queue, the
 * VDUs and {@code GET .../{orderId}/lateness} read -- is the concrete {@link
 * OrderLatenessPolicy} resolved from it:
 *
 * <ul>
 *   <li>each fulfilment mode's at-risk window is <em>its own</em> when the
 *       document sets one. When it does not, the window is {@link
 *       OrderingConfigurationKeys#AT_RISK_BEFORE_MINUTES} -- the batch 15 scalar,
 *       which stays as the default for the modes not set -- provided a value was
 *       <em>set</em> somewhere in the chain, and the platform's own five minutes
 *       otherwise. The key's registered default is the same five minutes, so
 *       registering it changed nothing, and a resolved default is never treated
 *       as something a tenant chose;
 *   <li>grace after the promise and the no-promise fallback are the
 *       document's, per mode;
 *   <li>the late colour rides along as-is when it is exactly {@code #rrggbb},
 *       and is dropped otherwise -- it is served to a style binding, so nothing
 *       else is allowed through even if a bad value reached the table some other
 *       way.
 * </ul>
 *
 * <p>ADR 0107's reporting SLA buckets ({@code sla_bucket_set.v1}) are platform
 * fixed and are not read from here; nothing in this service touches them.
 * Until a tenant, brand, or location sets the scalar or publishes a document,
 * {@link OrderLatenessPolicy#platformDefault()} applies everywhere.
 */
@Service
public class OrderLatenessPolicyService {

    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final PolicyResolver policies;
    private final ConfigurationResolver configuration;

    public OrderLatenessPolicyService(PolicyResolver policies, ConfigurationResolver configuration) {
        this.policies = policies;
        this.configuration = configuration;
    }

    /** The policy in force for a location right now. */
    public Effective resolve(UUID tenantId, UUID brandId, UUID locationId) {
        return resolveAt(ResourceScope.location(tenantId, brandId, locationId));
    }

    /**
     * The same resolution at whatever scope a caller already has — mirroring
     * {@code OrderAcceptancePolicyService.resolveAt}'s own reason: a tenant or
     * brand may want to see what is in force without naming one location.
     */
    public Effective resolveAt(ResourceScope scope) {
        Authored authored = authoredAt(scope);
        AtRiskDefault fallback = atRiskDefaultAt(scope);
        return new Effective(
                authored.document().effective(fallback.seconds()),
                authored.policyId(),
                authored.policyVersion(),
                tenantLateColour(scope));
    }

    /**
     * The document as authored -- a mode's own at-risk window still absent where the
     * author left it -- together with which version and which scope supplied it. The
     * editor needs the difference between "this mode says five minutes" and "this
     * mode says nothing and five minutes is the default"; {@link #resolveAt} cannot
     * tell them apart, by design.
     */
    public Authored authoredAt(ResourceScope scope) {
        return policies.resolve(OrderingConfigurationKeys.LATENESS_POLICY, scope)
                .map(resolved -> new Authored(
                        resolved.document(), resolved.policyId(), resolved.policyVersion(), resolved.winningScope()))
                .orElseGet(() -> new Authored(OrderLatenessDocument.platformDefault(), null, 0, null));
    }

    /**
     * The at-risk window a mode without one of its own gets at this scope: the tenant's
     * {@code ordering.at_risk_before_minutes} when one was set somewhere in the chain,
     * the platform's five minutes when not (or when an unusable value got past the
     * write rule -- ignored rather than taking the boards down).
     */
    public AtRiskDefault atRiskDefaultAt(ResourceScope scope) {
        Resolved<Integer> atRisk = configuration.resolve(OrderingConfigurationKeys.AT_RISK_BEFORE_MINUTES, scope);
        Integer minutes = atRisk.value();
        if (atRisk.cameFromDefault() || minutes == null || minutes < 0) {
            return new AtRiskDefault(
                    OrderLatenessPolicy.platformDefault().delivery().atRiskBeforeSeconds(),
                    AtRiskDefault.Source.PLATFORM_DEFAULT);
        }
        return new AtRiskDefault(Math.multiplyExact(minutes, 60), AtRiskDefault.Source.SCALAR);
    }

    private @Nullable String tenantLateColour(ResourceScope scope) {
        String colour = configuration.value(OrderingConfigurationKeys.LATE_COLOUR, scope);
        return colour != null && HEX_COLOUR.matcher(colour).matches()
                ? colour.toLowerCase(java.util.Locale.ROOT)
                : null;
    }

    /**
     * The authored document in force at a scope.
     *
     * @param policyId     null when the platform default applied
     * @param winningScope the scope whose document supplied it, null when none did
     */
    public record Authored(
            OrderLatenessDocument document,
            @Nullable UUID policyId,
            int policyVersion,
            @Nullable ScopeType winningScope) {}

    /** The window a mode with none of its own takes, and where that number came from. */
    public record AtRiskDefault(int seconds, Source source) {

        public enum Source {
            /** {@code ordering.at_risk_before_minutes} was set somewhere in the chain. */
            SCALAR,
            /** Nothing was set: the platform's own five minutes. */
            PLATFORM_DEFAULT
        }
    }

    /**
     * @param policyId null when the platform default applied, which is itself
     *                 a fact worth carrying rather than inventing an
     *                 identifier for
     * @param lateColour the tenant's own {@code #rrggbb} colour for a late order
     *                 (row {@code X.39}), or null to keep the design-system token
     */
    public record Effective(
            OrderLatenessPolicy policy,
            @Nullable UUID policyId,
            int policyVersion,
            @Nullable String lateColour) {

        public Effective(OrderLatenessPolicy policy, @Nullable UUID policyId, int policyVersion) {
            this(policy, policyId, policyVersion, null);
        }

        public boolean isPlatformDefault() {
            return policyId == null;
        }
    }
}
