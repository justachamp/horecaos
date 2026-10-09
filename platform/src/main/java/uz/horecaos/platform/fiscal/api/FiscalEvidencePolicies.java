package uz.horecaos.platform.fiscal.api;

import java.util.Set;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyKey;

/**
 * The two ADR 0030 policy keys the fiscal evidence surface owns (ADR 0166).
 *
 * <p>Declared in the module's published interface rather than in {@code fiscal.application}
 * because {@code payments} resolves one of them: the bridge that files a placeholder line is in
 * {@code payments}, and the decision whether it may is the {@code fiscal} module's. Two modules
 * naming one key code from two declarations is how they come to disagree about its scopes.
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
     * Whether the one-line placeholder receipt may be filed at all.
     *
     * <p>{@code PLATFORM} only, so a tenant cannot switch the placeholder on, and honoured only
     * where the provider environment is a sandbox: in a production environment the answer is no
     * whatever this says (ADR 0166, Decision 6).
     */
    public static final PolicyKey<SyntheticLines> SYNTHETIC_LINES_ALLOWED = new PolicyKey<>(
            "fiscal.synthetic_lines_allowed",
            SyntheticLines.class,
            Set.of(ScopeType.PLATFORM),
            "fiscal",
            false,
            "Whether the bridge may file its one-line placeholder receipt to prove a provider wire "
                    + "in a sandbox. Never honoured in a production environment (ADR 0166).");

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

    /** @param allowed false until a platform operator says otherwise, and then only for a sandbox */
    public record SyntheticLines(boolean allowed) {

        public static SyntheticLines platformDefault() {
            return new SyntheticLines(false);
        }
    }

    private FiscalEvidencePolicies() {}
}
