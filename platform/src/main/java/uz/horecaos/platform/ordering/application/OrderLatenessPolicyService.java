package uz.horecaos.platform.ordering.application;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessThresholds;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * Resolves the {@code ordering.lateness} policy (ADR 0030, orders.md §2.7,
 * gap map rows {@code 1.1g}/{@code X.39}).
 *
 * <p>Precedence is not implemented here, matching {@code
 * OrderAcceptancePolicyService}'s own doc: it comes from the shared ADR 0030
 * mechanism, so lateness resolves by exactly the same rule as every other
 * scoped behavior in the platform. This service adds only the platform
 * default.
 *
 * <p>No {@code author} method: the {@code ordering.lateness} document itself
 * still has no editor, and this service makes no write of its own -- there is
 * nothing here for an audit fact to describe. What a tenant <em>can</em> set is
 * two scalars, through the ordinary ADR 0030 configuration surface (already
 * {@code TENANT_CONFIGURATION_WRITE}, already audited by the value author with
 * a before/after): {@link OrderingConfigurationKeys#AT_RISK_BEFORE_MINUTES} and
 * {@link OrderingConfigurationKeys#LATE_COLOUR} (gap map row {@code X.39}). This
 * service overlays them on the resolved document, so both boards -- and {@code
 * GET .../{orderId}/lateness} -- see one answer:
 *
 * <ul>
 *   <li>the at-risk minutes replace {@code atRiskBeforeSeconds} in every
 *       fulfilment mode, but only when a value was <em>set</em> somewhere in the
 *       chain. The key's default is the platform default's own five minutes;
 *       an authored per-mode document is never overwritten by a default that
 *       merely happens to be resolved;
 *   <li>the late colour rides along as-is when it is exactly {@code #rrggbb},
 *       and is dropped otherwise -- it is served to a style binding, so nothing
 *       else is allowed through even if a bad value reached the table some other
 *       way.
 * </ul>
 *
 * <p>ADR 0107's reporting SLA buckets ({@code sla_bucket_set.v1}) are platform
 * fixed and are not read from here; nothing in this service touches them.
 * Until a tenant, brand, or location sets either scalar or publishes an
 * override, {@link OrderLatenessPolicy#platformDefault()} applies everywhere.
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
        Effective document = policies.resolve(OrderingConfigurationKeys.LATENESS_POLICY, scope)
                .map(resolved -> new Effective(resolved.document(), resolved.policyId(), resolved.policyVersion()))
                .orElseGet(() -> new Effective(OrderLatenessPolicy.platformDefault(), null, 0));
        return new Effective(
                withTenantAtRiskMinutes(document.policy(), scope),
                document.policyId(),
                document.policyVersion(),
                tenantLateColour(scope));
    }

    private OrderLatenessPolicy withTenantAtRiskMinutes(OrderLatenessPolicy document, ResourceScope scope) {
        Resolved<Integer> atRisk = configuration.resolve(OrderingConfigurationKeys.AT_RISK_BEFORE_MINUTES, scope);
        Integer minutes = atRisk.value();
        if (atRisk.cameFromDefault() || minutes == null || minutes < 0) {
            // Nothing was set (or an unusable value got past the write rule):
            // whatever the document says stands.
            return document;
        }
        int seconds = Math.multiplyExact(minutes, 60);
        return new OrderLatenessPolicy(
                withAtRiskSeconds(document.delivery(), seconds),
                withAtRiskSeconds(document.pickup(), seconds),
                withAtRiskSeconds(document.dineIn(), seconds));
    }

    private static LatenessThresholds withAtRiskSeconds(LatenessThresholds thresholds, int atRiskBeforeSeconds) {
        return new LatenessThresholds(
                atRiskBeforeSeconds, thresholds.lateAfterSeconds(), thresholds.noPromiseFallbackSeconds());
    }

    private @Nullable String tenantLateColour(ResourceScope scope) {
        String colour = configuration.value(OrderingConfigurationKeys.LATE_COLOUR, scope);
        return colour != null && HEX_COLOUR.matcher(colour).matches()
                ? colour.toLowerCase(java.util.Locale.ROOT)
                : null;
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
