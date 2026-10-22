package uz.horecaos.platform.notifications.application;

import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore.AttemptEvidence;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore.MessageEvidence;

/**
 * The word a message's evidence earns (ADR 0146 Decision 5, ADR 0182).
 *
 * <p>One mapping, used by the recipient row and by the campaign's counts, so the two can
 * never disagree about what a message is. "Handed to the operator" is what a gateway that
 * accepted a message and has reported nothing on it deserves: not "delivered", which
 * nobody said, and not a failure, which nobody said either.
 *
 * <p>The attempt speaks first. A message whose latest attempt is still in flight (requested,
 * uncertain, a retryable failure) is described by where its notification stands: given up on
 * is failed, lapsed is withheld, withheld by a rule is withheld with the rule's name, and
 * anything else is pending. Ending a retry loop must not leave a recipient «pending» forever.
 */
final class DeliveryStates {

    private DeliveryStates() {}

    /** A state and, for the ones that need it, the bounded code that says why. */
    record Classified(String state, @Nullable String reason) {}

    static Classified classify(MessageEvidence evidence) {
        AttemptEvidence attempt = evidence.attempt();
        if (attempt != null) {
            switch (attempt.status()) {
                case "DELIVERED" -> {
                    return new Classified(
                            attempt.read() ? CampaignMessagePort.STATE_READ : CampaignMessagePort.STATE_DELIVERED,
                            null);
                }
                case "FAILED" -> {
                    return new Classified(CampaignMessagePort.STATE_FAILED, attempt.failureCode());
                }
                case "REJECTED" -> {
                    return new Classified(CampaignMessagePort.STATE_REJECTED, attempt.failureCode());
                }
                case "ACCEPTED" -> {
                    return new Classified(
                            "NO_RECEIPT".equals(attempt.receiptState())
                                    ? CampaignMessagePort.STATE_NO_RECEIPT
                                    : CampaignMessagePort.STATE_HANDED_TO_OPERATOR,
                            null);
                }
                default -> {
                    // Requested, uncertain, a retryable failure: the notification says the rest.
                }
            }
        }
        return switch (evidence.notificationStatus()) {
            case "SUPPRESSED" ->
                new Classified(
                        CampaignMessagePort.STATE_WITHHELD,
                        evidence.suppressionReason() == null ? "SUPPRESSED" : evidence.suppressionReason());
            case "EXPIRED" -> new Classified(CampaignMessagePort.STATE_WITHHELD, "EXPIRED");
            case "FAILED_TERMINAL" ->
                new Classified(CampaignMessagePort.STATE_FAILED, attempt == null ? null : attempt.failureCode());
            case "MANUAL_REVIEW" -> new Classified(CampaignMessagePort.STATE_PENDING, "MANUAL_REVIEW");
            default -> new Classified(CampaignMessagePort.STATE_PENDING, null);
        };
    }
}
