package uz.horecaos.platform.fiscal.api;

import java.util.Set;
import java.util.UUID;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * The ADR 0030 policy key the fiscal evidence surface owns (ADR 0166).
 *
 * <p>Declared in the module's published interface rather than in {@code fiscal.application} so that
 * any module resolving it names one declaration; two modules naming one key code from two declarations is
 * how they come to disagree about its scopes. (A second key, {@code fiscal.synthetic_lines_allowed}, said
 * whether the bridge's one-line placeholder receipt could be filed in a sandbox. The placeholder is
 * deleted, and the key with it.)
 */
public final class FiscalEvidencePolicies {

    /**
     * How long a legal entity's fiscal evidence is kept. Seven years, ADR 0029's provisional period
     * for the {@code FINANCIAL} class and above the five-year floor the provider notes cite.
     *
     * <p>It is the one retention key for fiscal evidence: the document rows, the protected
     * references, and the marking evidence of ADR 0161 follow it and declare none of their own.
     * Offboarding a tenant does not shorten it, because the obligation is the tenant's. Settable at
     * {@code PLATFORM} and {@code TENANT}: a tenant's accountant may lengthen it, and no
     * deployment can shorten it below what the platform states.
     */
    public static final PolicyKey<FiscalEvidenceRetention> EVIDENCE_RETENTION = new PolicyKey<>(
            "fiscal.evidence_retention_years",
            FiscalEvidenceRetention.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT),
            "fiscal",
            false,
            "How many years a legal entity's fiscal evidence is retained, and whether that figure is "
                    + "still the provisional default nobody has confirmed (ADR 0166, ADR 0029).");

    /**
     * @param years       whole years
     * @param provisional true until someone with the authority confirms the figure; reported on the
     *                    evidence read so the surface does not present a guess as a rule
     */
    public record FiscalEvidenceRetention(int years, boolean provisional) {

        /** ADR 0166's proposed default. */
        public static final int DEFAULT_YEARS = 7;

        public FiscalEvidenceRetention {
            if (years < 5 || years > 50) {
                throw new IllegalArgumentException("Fiscal evidence is kept between 5 and 50 years");
            }
        }

        public static FiscalEvidenceRetention platformDefault() {
            return new FiscalEvidenceRetention(DEFAULT_YEARS, true);
        }
    }

    /**
     * The retention that applies to a tenant's fiscal evidence: the tenant's figure, never below the
     * platform's (ADR 0166 Decision 10).
     *
     * <p>The key is settable at both scopes and the resolver returns the most specific, which on its own would let
     * a tenant write five years under a platform that states seven. The record says the opposite: «a tenant's
     * accountant may lengthen it, and no deployment can shorten it below what the platform states», because the
     * obligation is the tenant's but the platform answers for having kept what it was handed. So every reader
     * (the evidence screen, the retention report, ADR 0161's marking evidence, which declares no key of its own)
     * resolves the key here and gets the longer of the two.
     *
     * <p>An unreadable policy is the platform default, loudly: a tenant's evidence is never kept for less because a
     * document could not be parsed.
     */
    public static FiscalEvidenceRetention effectiveRetention(PolicyResolver policies, UUID tenantId) {
        FiscalEvidenceRetention platform = readOrDefault(policies, ResourceScope.platform());
        FiscalEvidenceRetention tenant = readOrDefault(policies, ResourceScope.tenant(tenantId));
        // On a tie the tenant's own is the more specific statement, and it may be the one that was confirmed.
        return tenant.years() >= platform.years() ? tenant : platform;
    }

    private static FiscalEvidenceRetention readOrDefault(PolicyResolver policies, ResourceScope scope) {
        try {
            return policies.resolve(EVIDENCE_RETENTION, scope)
                    .map(ResolvedPolicy::document)
                    .orElseGet(FiscalEvidenceRetention::platformDefault);
        } catch (RuntimeException unreadable) {
            return FiscalEvidenceRetention.platformDefault();
        }
    }

    private FiscalEvidencePolicies() {}
}
