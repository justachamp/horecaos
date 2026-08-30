package uz.horecaos.platform.marketing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.customers.api.RecipientContactDirectory.ContactMethod;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.RefusalReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;

/**
 * The five subtractions, in one place, run twice (ADR 0044).
 *
 * <p>Once at snapshot build, so the approver sees truthful reach and cost, and
 * again per recipient at send, so the unsubscribe that arrived in between wins.
 * They are the same five checks in the same order because two implementations of
 * "may we message this person" is how the number an approver signed off and the
 * number a customer experiences come apart.
 *
 * <p>Consent is read and never re-decided. ADR 0015 owns the append-only record;
 * this class asks {@link ConsentDirectory} and does nothing with the answer but
 * act on it. Absence of a decision is withheld rather than permitted — "nobody
 * ever asked" and "they said yes" are the two states a default-true would merge,
 * and the migrated base carries no marketing consent at all because no legacy
 * table records one.
 *
 * <p>Marketing consent is not transactional consent, in law and not merely in
 * tone. An order confirmation is a receipt for money the customer spent and needs
 * no marketing decision; everything this class gates is a promotion and needs one.
 * That is why there is no bypass parameter: nothing routed through here is ever
 * transactional.
 *
 * <p>Every "no" is returned as a {@link RefusalReason} rather than as a boolean.
 * The caller writes it down. A filtered-out recipient leaves no row and no answer
 * to "why did this customer not get it", which is the question a tenant actually
 * asks and the one the competitor's design cannot answer at all.
 */
@Service
public class MarketingEligibility {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration MONTH = Duration.ofDays(30);

    private final ConsentDirectory consent;
    private final RecipientContactDirectory contacts;
    private final JdbcEngagementStore engagement;

    public MarketingEligibility(ConsentDirectory consent, RecipientContactDirectory contacts,
            JdbcEngagementStore engagement) {
        this.consent = consent;
        this.contacts = contacts;
        this.engagement = engagement;
    }

    /**
     * Whether this customer may be sent this campaign's message right now.
     *
     * @param accountReachable whether the account is {@code ACTIVE}, unmerged, and
     *                         not anonymised. Passed in because the caller has
     *                         already read it — the candidate query joins it, and
     *                         re-reading it per account would be a second query per
     *                         customer for a fact already in hand
     * @return empty when the message may go, or the reason it may not
     */
    public Optional<RefusalReason> refusalFor(UUID tenantId, UUID brandId, UUID accountId,
            MarketingChannel channel, String consentPurpose, EngagementPolicy policy,
            boolean accountReachable, Instant now) {

        if (!accountReachable) {
            return Optional.of(RefusalReason.ACCOUNT_NOT_ACTIVE);
        }

        boolean granted = consent
                .consentFor(tenantId, accountId, brandId, consentPurpose, channel.name())
                .map(ConsentDirectory.ConsentState::granted)
                .orElse(false);
        if (!granted) {
            return Optional.of(RefusalReason.CONSENT_WITHHELD);
        }

        // Third rather than second, and it matters that it is after consent rather
        // than before: suppression outranks consent, so a customer carrying both a
        // positive decision and a complaint is refused here, and the recorded
        // reason is the complaint rather than the permission.
        if (engagement.hasActiveSuppression(tenantId, brandId, accountId, channel.name(), now)) {
            return Optional.of(RefusalReason.SUPPRESSED);
        }

        if (engagement.sendsWithin(tenantId, brandId, accountId, now.minus(WEEK))
                >= policy.messagesPer7Days()
                || engagement.sendsWithin(tenantId, brandId, accountId, now.minus(MONTH))
                >= policy.messagesPer30Days()) {
            return Optional.of(RefusalReason.FREQUENCY_CAP_REACHED);
        }

        ContactMethod method = contactMethodFor(channel);
        if (method != null) {
            boolean verified = contacts.primaryContact(tenantId, accountId, method)
                    .map(RecipientContactDirectory.ContactEndpoint::isVerified)
                    .orElse(false);
            if (!verified) {
                return Optional.of(RefusalReason.NO_VERIFIED_ENDPOINT);
            }
        }

        return Optional.empty();
    }

    /**
     * The ADR 0015 contact kind a channel addresses, or null where none applies.
     *
     * <p>Push and Telegram address a device or a chat rather than a contact point,
     * and ADR 0020 owns both. Returning null here rather than inventing a
     * {@code ContactMethod} for them means this module never learns that such a
     * thing exists, which is the point: it holds no push token and no chat id.
     */
    private static ContactMethod contactMethodFor(MarketingChannel channel) {
        return switch (channel) {
            case SMS -> ContactMethod.PHONE;
            case EMAIL -> ContactMethod.EMAIL;
            case PUSH, MESSAGING_APP -> null;
        };
    }
}
