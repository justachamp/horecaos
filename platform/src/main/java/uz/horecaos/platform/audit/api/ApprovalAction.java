package uz.horecaos.platform.audit.api;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The code-owned catalogue of operations that may ask for maker-checker
 * approval (ADR 0050).
 *
 * <p>A missing policy is a decision only when its behaviour is declared here.
 * An unknown action is refused at the call site rather than inheriting an
 * accidental fail-open default. The normal initial mode is
 * {@link MissingPolicyMode#ALLOW_WITHOUT_APPROVAL}; change one action to
 * {@link MissingPolicyMode#REQUIRE_CONFIGURED_POLICY} only after its unresolved
 * resolution signal has been flat and its tenant coverage has been checked.
 */
public enum ApprovalAction {
    PAYMENTS_REMEDY_RECORD("payments.remedy.record", MissingPolicyMode.ALLOW_WITHOUT_APPROVAL),
    PAYMENTS_REMEDY_FUTURE_DISCOUNT("payments.remedy.future-discount", MissingPolicyMode.ALLOW_WITHOUT_APPROVAL),
    LOYALTY_BALANCE_ADJUST("loyalty.balance.adjust", MissingPolicyMode.ALLOW_WITHOUT_APPROVAL),
    COURIER_PAYOUT_AUTHORISE("courier.payout.authorise", MissingPolicyMode.ALLOW_WITHOUT_APPROVAL),
    COURIER_ADJUSTMENT_CREATE("courier.adjustment.create", MissingPolicyMode.ALLOW_WITHOUT_APPROVAL),

    /**
     * ADR 0042 already makes a manual penalty four-eyes unconditionally. Its
     * missing-policy mode is therefore fail closed from the outset; this replaces
     * the former generic "not granted" error with a stable configuration error.
     */
    COURIER_MANUAL_PENALTY("courier.adjustment.create.manual-penalty", MissingPolicyMode.REQUIRE_CONFIGURED_POLICY),

    TENANT_ACTIVATE("tenant.activate", MissingPolicyMode.ALLOW_WITHOUT_APPROVAL),
    INTEGRATION_FAILURE_RESOLVE("integration.failure.resolve", MissingPolicyMode.ALLOW_WITHOUT_APPROVAL),

    /**
     * ADR 0025, Gap A of the 2026-08-30 proving run: granting or revoking a
     * {@code PLATFORM}-scope role — the highest-authority action this
     * platform's own grant model can express, since {@code PLATFORM_ADMIN}
     * covers every capability in {@link uz.horecaos.platform.iam.api.Capability}
     * bar one.
     *
     * <p>{@code ALLOW_WITHOUT_APPROVAL} is the normal initial mode this
     * registry documents for every new action, and there is no pre-existing
     * hard requirement here to preserve the way {@link #COURIER_MANUAL_PENALTY}
     * preserved ADR 0042's — before {@code PlatformGrantController} existed,
     * nothing gated this action at all. A single platform-admin signature is
     * therefore the honest default, exactly as {@link #TENANT_ACTIVATE}'s is,
     * and any deployment wanting a second signature on its own platform
     * grants authors an {@code audit.approval_policies} row naming this code
     * at {@code PLATFORM} scope — the schema already carries that scope (see
     * V0082); tightening the default requires the same reviewed, observed
     * change ADR 0050 requires of every other action.
     */
    IAM_PLATFORM_GRANT_MANAGE("iam.platform-grant.manage", MissingPolicyMode.ALLOW_WITHOUT_APPROVAL);

    /** What an action does when no valid policy resolves at the requested scope. */
    public enum MissingPolicyMode {
        /** Preserve today’s explicit one-signature behaviour. */
        ALLOW_WITHOUT_APPROVAL,

        /** Refuse until a valid policy exists at the target scope or an ancestor. */
        REQUIRE_CONFIGURED_POLICY
    }

    private static final Map<String, ApprovalAction> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ApprovalAction::code, Function.identity()));

    private final String code;
    private final MissingPolicyMode missingPolicyMode;

    ApprovalAction(String code, MissingPolicyMode missingPolicyMode) {
        this.code = code;
        this.missingPolicyMode = missingPolicyMode;
    }

    public String code() {
        return code;
    }

    public MissingPolicyMode missingPolicyMode() {
        return missingPolicyMode;
    }

    public static Optional<ApprovalAction> find(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    public static ApprovalAction require(String code) {
        return find(code).orElseThrow(() -> new UnknownApprovalActionException(code));
    }

    /** Raised before an undeclared approval action can inherit a default. */
    public static final class UnknownApprovalActionException extends IllegalArgumentException {
        public UnknownApprovalActionException(String code) {
            super("Unknown approval action \"%s\". Declare it in ApprovalAction (ADR 0050).".formatted(code));
        }
    }
}
