package uz.qoida.platform.ordering.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * The order acceptance policy document (ADR 0002, stored under ADR 0030).
 *
 * <p>Identity, scope, and version are deliberately absent: they belong to
 * {@code ResolvedPolicy}, which the shared mechanism supplies. Carrying a second
 * copy here is exactly how the specialised table and the shared mechanism could
 * have disagreed about which version applied.
 *
 * <p>The timeout is stored in seconds rather than as a duration so the stored
 * JSON is a stable contract that does not depend on a serializer's choice of
 * representation.
 */
public record OrderAcceptancePolicy(
        AcceptanceMode mode,
        ApprovalChannel approvalChannel,
        int approvalTimeoutSeconds,
        ApprovalTimeoutAction timeoutAction,
        boolean rejectionReasonRequired,
        boolean notifyCustomerWhilePending) {

    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 1_800;

    public OrderAcceptancePolicy {
        Objects.requireNonNull(mode, "Acceptance mode is required");
        Objects.requireNonNull(approvalChannel, "Approval channel is required");
        Objects.requireNonNull(timeoutAction, "Timeout action is required");

        if (mode == AcceptanceMode.AUTO_CONFIRM && approvalChannel != ApprovalChannel.NONE) {
            throw new IllegalArgumentException("Auto-confirm policies cannot have an approval channel");
        }
        if (mode == AcceptanceMode.RESTAURANT_APPROVAL) {
            if (approvalChannel == ApprovalChannel.NONE) {
                throw new IllegalArgumentException("Restaurant approval requires an approval channel");
            }
            if (approvalTimeoutSeconds < MIN_TIMEOUT_SECONDS
                    || approvalTimeoutSeconds > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException(
                        "Approval timeout must be between 30 seconds and 30 minutes");
            }
        }
    }

    public Duration approvalTimeout() {
        return Duration.ofSeconds(approvalTimeoutSeconds);
    }

    /**
     * The platform default applied when no tenant, brand, or location policy
     * exists. Auto-confirm is the safe default: it never leaves a customer
     * waiting on a decision nobody has been asked to make.
     */
    public static OrderAcceptancePolicy platformDefault() {
        return new OrderAcceptancePolicy(
                AcceptanceMode.AUTO_CONFIRM,
                ApprovalChannel.NONE,
                0,
                ApprovalTimeoutAction.AUTO_REJECT,
                false,
                false);
    }
}
