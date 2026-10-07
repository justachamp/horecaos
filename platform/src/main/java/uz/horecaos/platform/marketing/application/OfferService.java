package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.loyalty.api.AccrualRuleReferencePort;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.api.OfferPublished;
import uz.horecaos.platform.marketing.domain.ScenarioChannel;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore.NewOffer;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore.OfferRow;
import uz.horecaos.platform.pricing.api.PromotionReferencePort;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring, versioning, publishing and retiring offers (ADR 0112).
 *
 * <p><strong>Marketing never authors a discount or mints a point.</strong> An offer is a
 * reference to exactly one pricing promotion or loyalty accrual rule that already exists
 * for this brand, plus when it may be offered, through which channels, to which audience
 * and with which words. There is no method here that takes an amount, a percentage, a
 * minimum basket or a stacking rule, and no column to put one in; what a promotion is
 * worth is pricing's and what points are worth is loyalty's, and this class reads neither
 * beyond "it exists and is not history".
 *
 * <p>Versions are rows. A published version is never edited: a change is a new draft in
 * the same lineage that supersedes it on publication, because a scenario step names a
 * specific version and an approved scenario must not change meaning underfoot (the same
 * rule ADR 0044 applies to a broadcast's audience snapshot).
 */
@Service
public class OfferService {

    private final JdbcOfferStore offers;
    private final PromotionReferencePort promotions;
    private final AccrualRuleReferencePort accrualRules;
    private final AudienceService audiences;
    private final CampaignMessagePort messages;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OfferService(
            JdbcOfferStore offers,
            PromotionReferencePort promotions,
            AccrualRuleReferencePort accrualRules,
            AudienceService audiences,
            CampaignMessagePort messages,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.offers = offers;
        this.promotions = promotions;
        this.accrualRules = accrualRules;
        this.audiences = audiences;
        this.messages = messages;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** What an author supplies. Everything else is derived, and nothing here can state a discount. */
    public record OfferDraft(
            String displayName,
            @Nullable UUID pricingPromotionId,
            @Nullable UUID loyaltyAccrualRuleId,
            Instant validFrom,
            @Nullable Instant validUntil,
            @Nullable UUID audienceId,
            List<String> allowedChannels,
            String templateKey,
            @Nullable Integer templateVersion,
            @Nullable String bannerImageReference) {}

    /** A new offer: version 1 of a new lineage, as a draft. */
    @Transactional
    public OfferRow create(
            UUID tenantId, UUID brandId, OfferDraft draft, ActorRef actor, UUID authorId, String correlationId) {
        validate(tenantId, brandId, draft);
        Instant now = clock.instant();
        UUID id = Ids.newId();
        offers.insert(newOffer(id, tenantId, brandId, Ids.newId(), 1, draft, authorId, now));
        OfferRow created = require(tenantId, brandId, id);
        record(
                "MARKETING_OFFER_CREATED",
                actor,
                created,
                "A draft offer was created",
                ChangeDocuments.created(snapshot(created)),
                correlationId,
                now);
        return created;
    }

    /**
     * A new draft version of an existing offer, starting from what the caller supplies.
     *
     * <p>The lineage's published version stays in force until this one is published.
     */
    @Transactional
    public OfferRow newVersion(
            UUID tenantId,
            UUID brandId,
            UUID sourceOfferId,
            OfferDraft draft,
            ActorRef actor,
            UUID authorId,
            String correlationId) {
        OfferRow source = require(tenantId, brandId, sourceOfferId);
        validate(tenantId, brandId, draft);
        Instant now = clock.instant();
        UUID id = Ids.newId();
        int next = offers.latestVersionNumber(tenantId, source.lineageId()) + 1;
        offers.insert(newOffer(id, tenantId, brandId, source.lineageId(), next, draft, authorId, now));
        OfferRow created = require(tenantId, brandId, id);
        record(
                "MARKETING_OFFER_VERSION_CREATED",
                actor,
                created,
                "A new draft version of an offer was created",
                ChangeDocuments.diff(snapshot(source), snapshot(created)),
                correlationId,
                now);
        return created;
    }

    /** Rewrites a draft in place. A published version is refused: make a new version instead. */
    @Transactional
    public OfferRow rewriteDraft(
            UUID tenantId,
            UUID brandId,
            UUID offerId,
            int expectedRowVersion,
            OfferDraft draft,
            ActorRef actor,
            String correlationId) {
        OfferRow before = require(tenantId, brandId, offerId);
        if (!"DRAFT".equals(before.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Only a draft offer can be rewritten; version %d is %s, so make a new version instead"
                            .formatted(before.versionNumber(), before.status()));
        }
        validate(tenantId, brandId, draft);
        Instant now = clock.instant();
        NewOffer replacement = newOffer(
                offerId, tenantId, brandId, before.lineageId(), before.versionNumber(), draft, before.createdBy(), now);
        if (!offers.rewriteDraft(tenantId, offerId, expectedRowVersion, replacement, now)) {
            throw ApiException.staleVersion(
                    expectedRowVersion, require(tenantId, brandId, offerId).rowVersion());
        }
        OfferRow after = require(tenantId, brandId, offerId);
        record(
                "MARKETING_OFFER_REWRITTEN",
                actor,
                after,
                "A draft offer was rewritten",
                ChangeDocuments.diff(snapshot(before), snapshot(after)),
                correlationId,
                now);
        return after;
    }

    /**
     * Puts a draft in force, superseding the lineage's previous published version.
     *
     * <p>The fact is appended in the same transaction ({@code BEFORE_COMMIT}), so an offer
     * is never published without anything downstream being able to hear of it.
     */
    @Transactional
    public OfferRow publish(
            UUID tenantId,
            UUID brandId,
            UUID offerId,
            int expectedRowVersion,
            ActorRef actor,
            UUID publisherId,
            String correlationId) {
        OfferRow before = require(tenantId, brandId, offerId);
        if (!"DRAFT".equals(before.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "Only a draft can be published; this version is " + before.status());
        }
        // A reference that has gone stale between authoring and now is refused here
        // rather than published: a promotion archived since the draft was written is
        // the case this exists for.
        validate(tenantId, brandId, asDraft(before));
        requireTemplates(tenantId, brandId, before.templateKey(), before.allowedChannels());

        Instant now = clock.instant();
        if (!offers.publish(tenantId, offerId, before.lineageId(), expectedRowVersion, publisherId, now)) {
            throw ApiException.staleVersion(
                    expectedRowVersion, require(tenantId, brandId, offerId).rowVersion());
        }
        OfferRow after = require(tenantId, brandId, offerId);
        record(
                "MARKETING_OFFER_PUBLISHED",
                actor,
                after,
                "An offer version was published",
                ChangeDocuments.diff(snapshot(before), snapshot(after)),
                correlationId,
                now);
        events.publishEvent(new OfferPublished(Ids.newId(), tenantId, brandId, offerId, after.versionNumber(), now));
        return after;
    }

    /** Takes a version out of use. Every scenario that references it stops applying it at the next step. */
    @Transactional
    public OfferRow retire(
            UUID tenantId,
            UUID brandId,
            UUID offerId,
            int expectedRowVersion,
            ActorRef actor,
            String reason,
            String correlationId) {
        OfferRow before = require(tenantId, brandId, offerId);
        Instant now = clock.instant();
        if (!offers.retire(tenantId, offerId, expectedRowVersion, now)) {
            if (!List.of("DRAFT", "PUBLISHED").contains(before.status())) {
                throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This version is already " + before.status());
            }
            throw ApiException.staleVersion(expectedRowVersion, before.rowVersion());
        }
        OfferRow after = require(tenantId, brandId, offerId);
        record(
                "MARKETING_OFFER_RETIRED",
                actor,
                after,
                reason,
                ChangeDocuments.diff(snapshot(before), snapshot(after)),
                correlationId,
                now);
        return after;
    }

    @Transactional(readOnly = true)
    public List<OfferRow> list(UUID tenantId, UUID brandId) {
        return offers.listByBrand(tenantId, brandId);
    }

    @Transactional(readOnly = true)
    public List<OfferRow> lineage(UUID tenantId, UUID brandId, UUID offerId) {
        OfferRow offer = require(tenantId, brandId, offerId);
        return offers.lineage(tenantId, offer.lineageId());
    }

    public OfferRow require(UUID tenantId, UUID brandId, UUID offerId) {
        return offers.find(tenantId, offerId)
                .filter(offer -> offer.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No offer " + offerId + " belongs to this brand"));
    }

    // -------------------------------------------------------------- validation

    /**
     * Exactly one reference, and it exists for this brand; a window that is a window;
     * channels from the closed set; an audience of this brand's.
     *
     * @throws ApiException {@code VALIDATION_FAILED} naming the first thing wrong, in a
     *         sentence an author can act on
     */
    private void validate(UUID tenantId, UUID brandId, OfferDraft draft) {
        if (draft.displayName() == null || draft.displayName().isBlank()) {
            throw invalid("An offer needs a name a guest can be shown");
        }
        UUID promotionId = draft.pricingPromotionId();
        UUID ruleId = draft.loyaltyAccrualRuleId();
        boolean hasPromotion = promotionId != null;
        boolean hasRule = ruleId != null;
        if (hasPromotion == hasRule) {
            throw invalid("An offer references exactly one of a pricing promotion or a loyalty accrual rule, "
                    + "and never defines one of its own");
        }
        if (promotionId != null) {
            var promotion = promotions
                    .find(tenantId, brandId, promotionId)
                    .orElseThrow(() -> invalid("No promotion " + promotionId + " exists for this brand"));
            if ("ARCHIVED".equals(promotion.status())) {
                throw invalid("Promotion " + promotion.code() + " is archived, so an offer cannot point at it");
            }
        } else if (ruleId != null) {
            var rule = accrualRules
                    .find(tenantId, brandId, ruleId)
                    .orElseThrow(() -> invalid("No accrual rule " + ruleId + " exists for this brand"));
            if ("RETIRED".equals(rule.status())) {
                throw invalid("That accrual rule is retired, so an offer cannot point at it");
            }
        }
        if (draft.validUntil() != null && !draft.validUntil().isAfter(draft.validFrom())) {
            throw invalid("An offer's validity window must end after it starts");
        }
        if (draft.allowedChannels() == null || draft.allowedChannels().isEmpty()) {
            throw invalid("An offer is allowed in at least one channel");
        }
        for (String channel : draft.allowedChannels()) {
            try {
                ScenarioChannel.valueOf(channel);
            } catch (IllegalArgumentException unknown) {
                throw invalid(channel + " is not a channel an offer can be shown in");
            }
        }
        if (draft.templateKey() == null || draft.templateKey().isBlank()) {
            throw invalid("An offer reuses an ADR 0020 template and names its key; it holds no wording of its own");
        }
        if (draft.audienceId() != null) {
            try {
                audiences.get(tenantId, brandId, draft.audienceId());
            } catch (RuntimeException notThisBrands) {
                throw invalid("No audience " + draft.audienceId() + " belongs to this brand");
            }
        }
    }

    /**
     * Wording exists for every channel that sends words. An offer's message is an ADR
     * 0020 template and its three mandatory locales, so a template with no active
     * version is a refusal at publication and not a silent gap at 22:00.
     */
    private void requireTemplates(UUID tenantId, UUID brandId, String templateKey, List<String> channels) {
        List<String> missing = new ArrayList<>();
        for (String channel : channels) {
            ScenarioChannel scenarioChannel = ScenarioChannel.valueOf(channel);
            // Only a channel whose delivery path can say what its wording is: a channel
            // with no delivery in this build has no template to look for.
            boolean looksUp =
                    scenarioChannel == ScenarioChannel.SMS || scenarioChannel == ScenarioChannel.MESSAGING_APP;
            if (looksUp
                    && messages.templateBodies(tenantId, brandId, templateKey, channel)
                            .isEmpty()) {
                missing.add(channel);
            }
        }
        if (!missing.isEmpty()) {
            throw invalid("Template " + templateKey + " has no active wording for " + missing);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static OfferDraft asDraft(OfferRow row) {
        return new OfferDraft(
                row.displayName(),
                row.pricingPromotionId(),
                row.loyaltyAccrualRuleId(),
                row.validFrom(),
                row.validUntil(),
                row.audienceId(),
                row.allowedChannels(),
                row.templateKey(),
                row.templateVersion(),
                row.bannerImageReference());
    }

    private static NewOffer newOffer(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID lineageId,
            int version,
            OfferDraft draft,
            UUID createdBy,
            Instant now) {
        return new NewOffer(
                id,
                tenantId,
                brandId,
                lineageId,
                version,
                draft.displayName().strip(),
                draft.pricingPromotionId(),
                draft.loyaltyAccrualRuleId(),
                draft.validFrom(),
                draft.validUntil(),
                draft.audienceId(),
                List.copyOf(draft.allowedChannels()),
                draft.templateKey(),
                draft.templateVersion(),
                draft.bannerImageReference(),
                createdBy,
                now);
    }

    /** What the change document says about an offer: its references and its state, never wording. */
    private static Map<String, Object> snapshot(OfferRow offer) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("lineageId", offer.lineageId().toString());
        state.put("versionNumber", offer.versionNumber());
        state.put("status", offer.status());
        state.put("displayName", offer.displayName());
        state.put(
                "pricingPromotionId",
                offer.pricingPromotionId() == null
                        ? ""
                        : offer.pricingPromotionId().toString());
        state.put(
                "loyaltyAccrualRuleId",
                offer.loyaltyAccrualRuleId() == null
                        ? ""
                        : offer.loyaltyAccrualRuleId().toString());
        state.put("validFrom", offer.validFrom().toString());
        state.put(
                "validUntil",
                offer.validUntil() == null ? "" : offer.validUntil().toString());
        state.put("allowedChannels", String.join(",", offer.allowedChannels()));
        state.put("templateKey", offer.templateKey());
        return state;
    }

    private void record(
            String action,
            ActorRef actor,
            OfferRow offer,
            String reason,
            Map<String, Object> changes,
            String correlationId,
            Instant now) {
        audit.record(AuditFact.of(action, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(offer.tenantId(), offer.brandId()))
                .target("MarketingOffer", offer.id())
                .targetVersion((long) offer.rowVersion())
                .because(reason)
                .changed(changes)
                .usingCapability("marketing.offer.manage")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }
}
