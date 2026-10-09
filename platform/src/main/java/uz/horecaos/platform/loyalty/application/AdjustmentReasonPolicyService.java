package uz.horecaos.platform.loyalty.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy;
import uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy.Mode;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The closed list of reason codes for a manual points adjustment (ADR 0165, Decision 3).
 *
 * <p>Resolved at {@code PLATFORM} scope and nowhere lower, and that is the control: a
 * tenant that could widen the list could add a code that means «paid in cash». The key is
 * therefore settable at {@code PLATFORM} only, and a read for a tenant's operator answers
 * the same list for every tenant.
 *
 * <p>It starts in {@link Mode#REPORT_ONLY}: a code off the list is accepted and counted
 * ({@code loyalty.adjustment.reason_code_unlisted}, labelled by mode and nothing else, not
 * by the code, because a code is typed by a person and may contain a name), so a tenant's
 * habits show up in a metric before they show up as a refusal. The next release flips the
 * stored document to {@link Mode#ENFORCE}. No log line carries a code, for the same reason.
 *
 * <p>The answer carries the policy's id and version so the audit fact of the adjustment
 * can persist the policy the decision was made under (ADR 0030). With no stored document
 * the built-in default applies and both are absent.
 */
@Service
public class AdjustmentReasonPolicyService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentReasonPolicyService.class);

    /** Settable at {@code PLATFORM} only: a tenant must not be able to widen the list. */
    public static final PolicyKey<AdjustmentReasonPolicy> REASON_CODES = new PolicyKey<>(
            "loyalty.adjustment_reason_codes",
            AdjustmentReasonPolicy.class,
            Set.of(ScopeType.PLATFORM),
            "loyalty",
            false,
            "The reason codes an operator may put on a manual points adjustment, and whether the "
                    + "list is enforced or only counted. Closes the back door that would let points be "
                    + "sold under a label of the operator's choosing (ADR 0165).");

    /** The one metric ADR 0165 specifies: how often an operator typed something off the list. */
    public static final String UNLISTED_COUNTER_NAME = "loyalty.adjustment.reason_code_unlisted";

    /** The stable machine code a refusal carries in {@code errors[]}. */
    public static final String NOT_PERMITTED = "REASON_CODE_NOT_PERMITTED";

    private final PolicyResolver policies;
    private final PolicyAuthor author;
    private final MeterRegistry meters;

    public AdjustmentReasonPolicyService(PolicyResolver policies, PolicyAuthor author, MeterRegistry meters) {
        this.policies = policies;
        this.author = author;
        this.meters = meters;
    }

    /**
     * The policy in force and the identity a decision under it must persist.
     *
     * @param policyId      null when no document is stored and the built-in default applies
     * @param policyVersion 0 in that case
     */
    public record Effective(
            AdjustmentReasonPolicy policy, @Nullable UUID policyId, int policyVersion) {}

    /** The effective list, for the read the adjustment dialog opens with. */
    public Effective effective() {
        try {
            return policies.resolve(REASON_CODES, ResourceScope.platform())
                    .map(AdjustmentReasonPolicyService::of)
                    .orElseGet(AdjustmentReasonPolicyService::builtIn);
        } catch (RuntimeException unreadable) {
            // A malformed stored document must not stop every operator adjustment; the
            // report-only default is the safe direction, and the failure is loud.
            log.error(
                    "The {} policy is unreadable; the report-only default applies until it is fixed.",
                    REASON_CODES.code(),
                    unreadable);
            return builtIn();
        }
    }

    /**
     * Applies the list to one operator-typed code.
     *
     * <p>Counts an unlisted code in either mode, and refuses it only in {@link Mode#ENFORCE}.
     * The refusal is 400 {@code VALIDATION_FAILED} with one {@code errors[]} entry naming
     * {@code reasonCode}; its {@code detail} does not echo the code.
     *
     * @return the policy the adjustment was permitted under, for its audit fact
     */
    public Effective admit(String reasonCode) {
        Effective effective = effective();
        if (effective.policy().lists(reasonCode)) {
            return effective;
        }
        Mode mode = effective.policy().mode();
        meters.counter(UNLISTED_COUNTER_NAME, "mode", mode.name()).increment();
        if (mode == Mode.ENFORCE) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The reason code is not on the list of codes an adjustment may carry",
                    java.util.Map.of(
                            "errors",
                            List.of(new ApiProblem.FieldError(
                                    "reasonCode", NOT_PERMITTED, "Choose one of the permitted reason codes"))));
        }
        return effective;
    }

    /**
     * Replaces the platform document: the audited write that flips the list to enforcing,
     * or back to report-only (the rollback ADR 0165 names).
     */
    public Effective author(AdjustmentReasonPolicy document, int expectedVersion, ActorRef actor, String reason) {
        ResolvedPolicy<AdjustmentReasonPolicy> published =
                author.author(REASON_CODES, ResourceScope.platform(), document, expectedVersion, actor, reason);
        return of(published);
    }

    /** The version a save is checked against: the latest authored at platform scope, or 0. */
    public int currentVersion() {
        return author.currentVersion(REASON_CODES, ResourceScope.platform());
    }

    private static Effective of(ResolvedPolicy<AdjustmentReasonPolicy> resolved) {
        return new Effective(resolved.document(), resolved.policyId(), resolved.policyVersion());
    }

    private static Effective builtIn() {
        return new Effective(AdjustmentReasonPolicy.platformDefault(), null, 0);
    }
}
