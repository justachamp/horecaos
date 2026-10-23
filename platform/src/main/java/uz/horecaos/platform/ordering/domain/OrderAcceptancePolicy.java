package uz.horecaos.platform.ordering.domain;

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
 *
 * <p><strong>Two shapes of {@code AUTO_CONFIRM} (ADR 0177).</strong> With no approval
 * channel it confirms every order, as it always did. With one it still confirms by
 * default, but an order the {@code ordering.auto_accept_*} gates hold back follows the
 * document's own approval channel, timeout and timeout action instead of confirming.
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

        if (mode == AcceptanceMode.RESTAURANT_APPROVAL && approvalChannel == ApprovalChannel.NONE) {
            throw new IllegalArgumentException("Restaurant approval requires an approval channel");
        }
        // ADR 0177 decision 3: an AUTO_CONFIRM policy may carry the approval path an order that an
        // auto-accept gate holds back follows (a channel it is not in, or a customer with too few
        // successful orders). When it does, the path needs the same bounded timeout restaurant
        // approval needs, because the held order waits on a deadline. NONE is still the ordinary
        // AUTO_CONFIRM: no gate has anywhere to send an order, so none can hold one.
        if (mode == AcceptanceMode.RESTAURANT_APPROVAL || approvalChannel != ApprovalChannel.NONE) {
            if (approvalTimeoutSeconds < MIN_TIMEOUT_SECONDS || approvalTimeoutSeconds > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("Approval timeout must be between 30 seconds and 30 minutes");
            }
        }
    }

    /**
     * Whether this policy says who decides an order that needs a decision: a channel other than
     * {@link ApprovalChannel#NONE}. Always true for {@code RESTAURANT_APPROVAL}; for {@code
     * AUTO_CONFIRM} it is the hold-back path the auto-accept gates send an order down (ADR 0177).
     */
    public boolean hasApprovalPath() {
        return approvalChannel != ApprovalChannel.NONE;
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
                AcceptanceMode.AUTO_CONFIRM, ApprovalChannel.NONE, 0, ApprovalTimeoutAction.AUTO_REJECT, false, false);
    }
}
