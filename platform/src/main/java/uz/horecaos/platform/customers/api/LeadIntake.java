package uz.horecaos.platform.customers.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How another module hands the call centre a guest who is not yet an account (ADR 0111 §4).
 *
 * <p>The storefront, the Telegram bot, a reservation request, an aggregator's first order and a
 * campaign scenario's call step ARE the intake -- there is no separate lead service -- so each of
 * them calls this rather than keeping a queue of its own. Not a command from a customer: whoever
 * calls it has already decided the guest wants to be contacted (a callback request), or that the
 * call centre should (a scenario step).
 *
 * <p>Idempotent for a campaign scenario step: the same campaign, step and number is one lead, so a
 * redelivered {@code EnqueueCallTaskCommand} under ADR 0005's inbox collapses.
 */
public interface LeadIntake {

    /**
     * @return the lead's id; for a scenario step that already produced one, the existing lead's id
     * @throws IllegalArgumentException when the phone is not a number this platform can normalize
     */
    UUID registerLead(Registration registration);

    /**
     * @param phone             as the guest gave it; normalized, hashed and encrypted on the way in
     * @param actor             a stable, non-personal identifier of who or what handed this over
     *                          ({@code storefront-customer:<accountId>}, {@code telegram-bot},
     *                          {@code campaign-scenario})
     * @param originCampaignId  set only for {@link LeadSource#CAMPAIGN_SCENARIO}
     * @param originStepSequence set only for {@link LeadSource#CAMPAIGN_SCENARIO}
     */
    record Registration(
            UUID tenantId,
            UUID brandId,
            LeadSource source,
            String phone,
            @Nullable String displayName,
            @Nullable String notes,
            @Nullable UUID customerAccountId,
            @Nullable UUID originCampaignId,
            @Nullable Integer originStepSequence,
            String actor) {}
}
