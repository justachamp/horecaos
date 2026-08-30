package uz.horecaos.platform.fiscal.application;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import uz.horecaos.platform.fiscal.domain.FiscalReportingPolicy;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Resolves how long a provider is given to report a receipt (ADR 0038, ADR 0030).
 *
 * <p>Precedence is not implemented here. It comes from the shared ADR 0030
 * mechanism, so a fiscal deadline resolves by exactly the same rule as order
 * acceptance and everything else scoped in this platform.
 *
 * <p>Resolved at {@code TENANT} rather than at {@code LOCATION}, and that is a
 * choice worth stating. The value is a property of the tenant's arrangement with
 * a provider, not of a branch — two branches of one company share a Payme cashbox
 * and therefore share whatever Payme's reporting behaviour is. Making it settable
 * lower would invite a per-branch deadline that no branch manager has the
 * information to set, and the sweep would then need a branch on every document,
 * which is exactly the join it is designed to survive without.
 */
@Service
public class FiscalReportingPolicyService {

    private static final Logger log = LoggerFactory.getLogger(FiscalReportingPolicyService.class);

    /**
     * Settable at the tenant and above. Brand and location are absent from the
     * settable set rather than merely unused: a scope that can be written and is
     * never read is a configuration screen that lies to whoever fills it in.
     */
    public static final PolicyKey<FiscalReportingPolicy> REPORTING_DEADLINE = new PolicyKey<>(
            "fiscal.reporting_deadline",
            FiscalReportingPolicy.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT),
            "fiscal",
            false,
            "How long a payment partner is given to report a fiscal receipt before the "
                    + "document is blocked for an operator.");

    private final PolicyResolver policies;

    public FiscalReportingPolicyService(PolicyResolver policies) {
        this.policies = policies;
    }

    /**
     * The policy in force for a tenant.
     *
     * <p>A stored document that no longer matches the record type answers the
     * platform default rather than throwing. This is the one place in the module
     * where that is the right direction: the caller is a sweep, and a sweep that
     * dies on one tenant's malformed policy stops chasing every other tenant's
     * missing receipts. The misconfiguration is logged with the tenant on it and
     * the sixty-minute default keeps the sweep running.
     */
    public FiscalReportingPolicy forTenant(UUID tenantId) {
        try {
            return policies.resolve(REPORTING_DEADLINE, ResourceScope.tenant(tenantId))
                    .map(ResolvedPolicy::document)
                    .orElseGet(FiscalReportingPolicy::platformDefault);
        } catch (RuntimeException unreadable) {
            log.error("Tenant {} has an unreadable {} policy; the platform default of {} minutes "
                            + "applies to its fiscal sweep until it is fixed.",
                    tenantId, REPORTING_DEADLINE.code(), FiscalReportingPolicy.DEFAULT_MINUTES,
                    unreadable);
            return FiscalReportingPolicy.platformDefault();
        }
    }
}
