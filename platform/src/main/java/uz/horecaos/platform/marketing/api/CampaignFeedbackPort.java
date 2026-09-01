package uz.horecaos.platform.marketing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The seam from the ADR 0020 delivery path back to a running ADR 0044
 * campaign — the opposite direction from {@link CampaignMessagePort}.
 *
 * <p>{@code notifications} already depends on {@code marketing.api} to
 * implement {@link CampaignMessagePort}; this interface rides that same,
 * already-established one-way edge rather than opening a second one. It is
 * declared here and implemented by {@code marketing} for the same reason
 * {@code CampaignMessagePort} is declared here and implemented by {@code
 * notifications}: the module that owns the decision declares the seam,
 * whichever side calls it.
 *
 * <p>Two questions cross here, both keyed on the campaign rather than on a
 * single message. {@link #isSending} is asked once per claimed message, right
 * before it would be marked ready to send, so a pause reaches every message
 * already sitting on a future pacing slot and not only the batches not yet
 * expanded. {@link #recordBlocked} is called once per recipient whose
 * delivery retired their Telegram binding — the same signal {@code
 * CustomerProviderBindingSyncService} already treats as consent revocation —
 * so the campaign's own block-rate guard can act on it independently of what
 * the consent record now says.
 */
public interface CampaignFeedbackPort {

    /**
     * Whether {@code campaignId} is still in a state that should keep sending.
     *
     * @return false once the campaign is paused, halted, cancelled, or
     *         finished — true only for {@code SENDING}, and false (never an
     *         exception) for a campaign id this tenant does not own, which a
     *         stale or forged subject id on a notification row is a data fault
     *         the caller records as a suppression rather than propagates as one
     */
    boolean isSending(UUID tenantId, UUID campaignId);

    /**
     * Counts one blocked recipient against the campaign's block-rate guard, and
     * pauses the campaign — {@code SENDING -> PAUSED}, {@code CampaignStatus}'s
     * own resumable stop — when the configured threshold is crossed.
     *
     * @return what the guard did, so the caller can decide whether to raise an
     *         operator alert and a Micrometer counter without asking a second
     *         question
     */
    BlockOutcome recordBlocked(UUID tenantId, UUID campaignId, UUID customerAccountId, Instant now);

    /**
     * @param blockedCount the campaign's running total after this call
     * @param attempted how many recipients had actually been queued for send
     *                  when the guard evaluated — the percentage denominator,
     *                  read live from {@code campaign_recipients} rather than
     *                  a second counter, so it can never drift from the rows
     *                  it describes
     * @param pausedByThisCall true only on the one call whose threshold check
     *                         actually flipped the campaign to {@code PAUSED};
     *                         every call after that on an already-paused
     *                         campaign still counts the block but answers
     *                         false, so an operator is alerted once rather
     *                         than once per straggling recipient
     */
    record BlockOutcome(int blockedCount, int attempted, boolean pausedByThisCall) {}
}
