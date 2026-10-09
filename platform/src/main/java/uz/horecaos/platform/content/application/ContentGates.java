package uz.horecaos.platform.content.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.content.api.ContentEntitlements;
import uz.horecaos.platform.content.api.ContentPolicies.PublishingPolicy;
import uz.horecaos.platform.content.api.ContentTargetLookup;
import uz.horecaos.platform.content.api.PromotionLiveLookup;
import uz.horecaos.platform.content.domain.ContentDocuments;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.domain.ContentPlacement;
import uz.horecaos.platform.content.domain.DocumentFacts;
import uz.horecaos.platform.content.domain.DocumentFacts.AssetUse;
import uz.horecaos.platform.content.domain.FieldProblem;
import uz.horecaos.platform.content.domain.FieldProblem.Codes;
import uz.horecaos.platform.content.domain.ItemState;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.media.api.MediaCreativeLookup.Creative;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The publication gates (ADR 0157, Publication gates), one implementation for three questions.
 *
 * <ul>
 *   <li>{@link Mode#PUBLISH}: what refuses a publication -- the closed list of codes, a 422;
 *   <li>{@link Mode#DRAFT}: the same checks as <em>warnings</em> on every draft save, feeding the
 *       console's per-item problem list;
 *   <li>{@link Mode#HEALTH}: what is wrong with something already in front of customers -- a live
 *       item with an unresolved target, a promotion that is not live, a link host no longer
 *       allowed, a missing language, an image that has not rendered.
 * </ul>
 *
 * <p>Batched: every lookup is made once for the whole list of subjects, however many items it
 * holds, because the console's list and the health read ask about up to five hundred items and an
 * N+1 against five modules is the first thing that would make them slow.
 */
@Component
public class ContentGates {

    /** Within this fraction of the placement's ratio an image is accepted (ADR 0157: "more than 3% from"). */
    static final double ASPECT_TOLERANCE = 0.03;

    /** Which of the three questions is being asked. */
    public enum Mode {
        PUBLISH,
        DRAFT,
        HEALTH
    }

    /**
     * One thing to check. {@code state} is the item's stored state, because publishing an item that
     * is already live does not add to its placement.
     */
    public record Subject(
            UUID itemId,
            ContentKind kind,
            @Nullable ContentPlacement placement,
            ItemState state,
            JsonNode document,
            DocumentFacts facts,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {

        public Subject {
            channelCodes = List.copyOf(channelCodes);
        }
    }

    private final ContentEnvironment environment;
    private final MediaCreativeLookup media;
    private final ContentTargetLookup targets;
    private final PromotionLiveLookup promotions;
    private final JdbcContentStore store;

    public ContentGates(
            ContentEnvironment environment,
            MediaCreativeLookup media,
            ContentTargetLookup targets,
            PromotionLiveLookup promotions,
            JdbcContentStore store) {
        this.environment = environment;
        this.media = media;
        this.targets = targets;
        this.promotions = promotions;
        this.store = store;
    }

    /** The problems of each subject, keyed by item id; a subject with none maps to an empty list. */
    public Map<UUID, List<FieldProblem>> evaluate(
            UUID tenantId, UUID brandId, List<Subject> subjects, Mode mode, Instant now) {
        Map<UUID, List<FieldProblem>> problems = new LinkedHashMap<>();
        for (Subject subject : subjects) {
            problems.put(subject.itemId(), new ArrayList<>());
        }
        if (subjects.isEmpty()) {
            return problems;
        }

        TenantLocaleSet locales = environment.locales(tenantId, brandId);
        PublishingPolicy policy = environment.publishingPolicy(tenantId, brandId);
        Map<String, SalesChannel> contentChannels = environment.contentChannels(tenantId);

        // One read of the media for the whole list.
        Set<UUID> assetIds = new LinkedHashSet<>();
        for (Subject subject : subjects) {
            subject.facts().assets().forEach(use -> assetIds.add(use.assetId()));
        }
        Map<UUID, Creative> creatives = assetIds.isEmpty() ? Map.of() : media.describe(tenantId, assetIds);
        // A video is a paid feature: refused by entitlement (403), not by a gate code (422), because
        // ADR 0031 keeps INSUFFICIENT_CAPABILITY and ENTITLEMENT_REQUIRED as different codes with
        // different remediation. An empty answer from the gate is read as not entitled.
        if (mode != Mode.HEALTH
                && creatives.values().stream().anyMatch(c -> c.kind() == MediaCreativeLookup.Kind.VIDEO)
                && !environment.videoEntitled(tenantId)) {
            throw ApiException.entitlementRequired(ContentEntitlements.VIDEO_ENABLED);
        }

        // One read of each channel's publication, for the ids every subject names.
        Map<String, Set<UUID>> productsByChannel = new HashMap<>();
        Map<String, Set<UUID>> categoriesByChannel = new HashMap<>();
        Map<String, Set<UUID>> wantedProducts = new HashMap<>();
        Map<String, Set<UUID>> wantedCategories = new HashMap<>();
        for (Subject subject : subjects) {
            for (String channel : effectiveChannels(subject, contentChannels)) {
                wantedProducts
                        .computeIfAbsent(channel, c -> new HashSet<>())
                        .addAll(subject.facts().productIds());
                wantedCategories
                        .computeIfAbsent(channel, c -> new HashSet<>())
                        .addAll(subject.facts().categoryIds());
            }
        }
        for (String channel : wantedProducts.keySet()) {
            Set<UUID> products = wantedProducts.get(channel);
            Set<UUID> categories = wantedCategories.getOrDefault(channel, Set.of());
            if (products == null || (products.isEmpty() && categories.isEmpty())) {
                continue;
            }
            ContentTargetLookup.Existing existing = targets.existing(tenantId, brandId, channel, products, categories);
            productsByChannel.put(channel, existing.productIds());
            categoriesByChannel.put(channel, existing.categoryIds());
        }

        // One read of the promotions every subject binds or links.
        Set<UUID> promotionIds = new HashSet<>();
        for (Subject subject : subjects) {
            if (subject.promotionId() != null) {
                promotionIds.add(subject.promotionId());
            }
            promotionIds.addAll(subject.facts().promotionIds());
        }
        Set<UUID> existingPromotions =
                promotionIds.isEmpty() ? Set.of() : promotions.existing(tenantId, brandId, promotionIds);
        Set<UUID> livePromotions = mode == Mode.HEALTH && !promotionIds.isEmpty()
                ? promotions.live(tenantId, brandId, promotionIds, now)
                : Set.of();

        boolean anyExternal =
                subjects.stream().anyMatch(s -> !s.facts().externalRefs().isEmpty());
        Set<String> allowlist = anyExternal ? environment.effectiveAllowlist(tenantId, brandId) : Set.of();

        Set<UUID> liveVideos = null;
        int videoCap = 0;
        if (mode == Mode.PUBLISH && !subjects.isEmpty()) {
            liveVideos = liveVideoAssets(tenantId, brandId, subjects.getFirst().itemId());
            videoCap = environment.videoCap(tenantId, brandId);
        }

        for (Subject subject : subjects) {
            List<FieldProblem> list = problems.get(subject.itemId());
            if (list == null) {
                continue;
            }
            locale(subject, locales, policy, list);
            channels(subject, contentChannels, list);
            media(subject, creatives, mode, list);
            targets(subject, contentChannels, productsByChannel, categoriesByChannel, list);
            promotions(subject, existingPromotions, livePromotions, mode, list);
            hosts(subject, allowlist, list);
            if (mode == Mode.PUBLISH) {
                placement(tenantId, brandId, subject, list);
                videos(subject, creatives, liveVideos == null ? Set.of() : liveVideos, videoCap, list);
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------ languages

    private static void locale(
            Subject subject, TenantLocaleSet locales, PublishingPolicy policy, List<FieldProblem> out) {
        Set<String> present = subject.facts().locales();
        if (!present.contains(locales.defaultLocale())) {
            out.add(new FieldProblem(
                    "texts." + locales.defaultLocale(),
                    Codes.DEFAULT_LOCALE_MISSING,
                    "The brand's default language (" + locales.defaultLocale() + ") has no text"));
        }
        if (policy.allBrandLocalesRequired()) {
            for (String tag : locales.locales()) {
                if (!present.contains(tag)) {
                    out.add(new FieldProblem(
                            "texts." + tag,
                            Codes.LOCALE_INCOMPLETE,
                            "The brand requires text in every one of its languages and " + tag + " has none"));
                }
            }
        }
    }

    // -------------------------------------------------------------------- channels

    private static void channels(Subject subject, Map<String, SalesChannel> contentChannels, List<FieldProblem> out) {
        for (String code : subject.channelCodes()) {
            if (!contentChannels.containsKey(code)) {
                out.add(new FieldProblem(
                        "channelCodes",
                        Codes.CHANNEL_NOT_A_CONTENT_SURFACE,
                        "Channel " + code + " is not a web, iOS, Android or Telegram channel of this tenant"));
            }
        }
    }

    private static Set<String> effectiveChannels(Subject subject, Map<String, SalesChannel> contentChannels) {
        if (subject.channelCodes().isEmpty()) {
            return contentChannels.keySet();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String code : subject.channelCodes()) {
            if (contentChannels.containsKey(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    // ----------------------------------------------------------------------- media

    private void media(Subject subject, Map<UUID, Creative> creatives, Mode mode, List<FieldProblem> out) {
        ContentKind kind = subject.kind();
        if (requiresPrimaryImage(kind) && !hasPrimary(subject)) {
            out.add(new FieldProblem("media.primaryAssetId", Codes.MEDIA_NOT_DISPLAYABLE, "An image is required"));
        }
        for (AssetUse use : subject.facts().assets()) {
            Creative creative = creatives.get(use.assetId());
            if (creative == null) {
                out.add(new FieldProblem(
                        use.field(), Codes.MEDIA_NOT_DISPLAYABLE, "No such media asset for this tenant"));
                continue;
            }
            if (!creative.status().isDisplayable()) {
                out.add(new FieldProblem(
                        use.field(),
                        Codes.MEDIA_NOT_DISPLAYABLE,
                        "The asset is " + creative.status() + ", not AVAILABLE"));
                continue;
            }
            if (creative.visibility() != MediaCreativeLookup.Visibility.PUBLIC) {
                // The storefront redirect answers 404 for a non-PUBLIC asset and never says which, so a
                // banner on a private asset would pass here and show every customer a broken image.
                out.add(new FieldProblem(
                        use.field(),
                        Codes.MEDIA_NOT_DISPLAYABLE,
                        "The asset is PRIVATE; a creative is uploaded PUBLIC"));
                continue;
            }
            boolean video = creative.kind() == MediaCreativeLookup.Kind.VIDEO;
            if (use.declaredKind() != null && video != "VIDEO".equals(use.declaredKind())) {
                out.add(new FieldProblem(
                        use.field(),
                        Codes.MEDIA_NOT_DISPLAYABLE,
                        "The document says " + use.declaredKind() + " and the asset is " + creative.kind()));
                continue;
            }
            if (video && use.role() != uz.horecaos.platform.content.domain.MediaRole.POSTER) {
                if (use.posterAssetId() == null) {
                    out.add(new FieldProblem(use.field(), Codes.POSTER_REQUIRED, "A video needs a poster image"));
                }
            } else if (video) {
                out.add(new FieldProblem(
                        use.field(), Codes.MEDIA_NOT_DISPLAYABLE, "A poster is an image, not a video"));
            }
            aspect(use, creative, out);
            if (mode == Mode.HEALTH && !video && creative.renditions().isEmpty()) {
                out.add(new FieldProblem(
                        use.field(),
                        Codes.MEDIA_NOT_RENDERED,
                        "The image has no rendition yet, so the storefront does not show it"));
            }
        }
    }

    private static void aspect(AssetUse use, Creative creative, List<FieldProblem> out) {
        if (use.ratio() <= 0 && use.minWidthPx() <= 0) {
            return;
        }
        Integer width = creative.widthPx();
        Integer height = creative.heightPx();
        if (width == null || height == null || height <= 0) {
            out.add(new FieldProblem(use.field(), Codes.MEDIA_ASPECT_MISMATCH, "The asset's size is not known"));
            return;
        }
        if (use.ratio() > 0) {
            double actual = width / (double) height;
            if (Math.abs(actual - use.ratio()) / use.ratio() > ASPECT_TOLERANCE) {
                out.add(new FieldProblem(
                        use.field(),
                        Codes.MEDIA_ASPECT_MISMATCH,
                        "The image is %dx%d; this placement wants a ratio of %.2f within 3%%"
                                .formatted(width, height, use.ratio())));
                return;
            }
        }
        if (use.minWidthPx() > 0 && width < use.minWidthPx()) {
            out.add(new FieldProblem(
                    use.field(),
                    Codes.MEDIA_ASPECT_MISMATCH,
                    "The image is %d px wide; this placement wants at least %d".formatted(width, use.minWidthPx())));
        }
    }

    private static boolean requiresPrimaryImage(ContentKind kind) {
        return kind == ContentKind.BANNER || kind == ContentKind.PROMO_CARD;
    }

    private static boolean hasPrimary(Subject subject) {
        JsonNode primary = subject.document().path("media").path("primaryAssetId");
        return primary.isString();
    }

    // --------------------------------------------------------------------- targets

    private static void targets(
            Subject subject,
            Map<String, SalesChannel> contentChannels,
            Map<String, Set<UUID>> productsByChannel,
            Map<String, Set<UUID>> categoriesByChannel,
            List<FieldProblem> out) {
        Set<String> channels = effectiveChannels(subject, contentChannels);
        for (UUID product : subject.facts().productIds()) {
            boolean found = channels.stream()
                    .anyMatch(c -> productsByChannel.getOrDefault(c, Set.of()).contains(product));
            if (!found) {
                out.add(new FieldProblem(
                        "targets.products",
                        Codes.TARGET_NOT_FOUND,
                        "Product " + product + " is not in the published menu"));
            }
        }
        for (UUID category : subject.facts().categoryIds()) {
            boolean found = channels.stream()
                    .anyMatch(c -> categoriesByChannel.getOrDefault(c, Set.of()).contains(category));
            if (!found) {
                out.add(new FieldProblem(
                        "targets.categories",
                        Codes.TARGET_NOT_FOUND,
                        "Category " + category + " is not in the published menu"));
            }
        }
        if (subject.kind() == ContentKind.PRODUCT_GROUP
                && "MANUAL".equals(subject.document().path("mode").asString(""))
                && subject.facts().productIds().isEmpty()) {
            out.add(new FieldProblem(
                    "members", Codes.TARGET_NOT_FOUND, "A hand-picked group needs at least one product"));
        }
    }

    // ------------------------------------------------------------------ promotions

    private static void promotions(
            Subject subject, Set<UUID> existing, Set<UUID> live, Mode mode, List<FieldProblem> out) {
        Set<UUID> named = new LinkedHashSet<>(subject.facts().promotionIds());
        if (subject.promotionId() != null) {
            named.add(subject.promotionId());
        }
        for (UUID promotion : named) {
            if (!existing.contains(promotion)) {
                out.add(new FieldProblem(
                        "promotionId",
                        Codes.PROMOTION_NOT_FOUND,
                        "Promotion " + promotion + " does not exist for this brand"));
            } else if (mode == Mode.HEALTH && !live.contains(promotion) && promotion.equals(subject.promotionId())) {
                out.add(new FieldProblem(
                        "promotionId",
                        Codes.PROMOTION_NOT_LIVE,
                        "The promotion is not live, so the storefront does not show this card"));
            }
        }
    }

    // ----------------------------------------------------------------------- hosts

    private static void hosts(Subject subject, Set<String> allowlist, List<FieldProblem> out) {
        for (DocumentFacts.ExternalRef ref : subject.facts().externalRefs()) {
            if (!allowlist.contains(ref.host())) {
                out.add(new FieldProblem(
                        ref.field(),
                        Codes.LINK_HOST_NOT_ALLOWED,
                        "The host " + ref.host() + " is not on this brand's allowlist"));
            }
        }
    }

    // ------------------------------------------------------------------- placement

    private void placement(UUID tenantId, UUID brandId, Subject subject, List<FieldProblem> out) {
        ContentPlacement placement = subject.placement();
        if (placement == null || subject.state() == ItemState.PUBLISHED) {
            return;
        }
        int live = store.countPublishedInPlacement(tenantId, brandId, placement, subject.itemId());
        if (live >= placement.limit()) {
            out.add(new FieldProblem(
                    "placement",
                    Codes.PLACEMENT_FULL,
                    placement + " already holds " + live + " live items; the most it holds is " + placement.limit()));
        }
    }

    // ---------------------------------------------------------------------- videos

    private Set<UUID> liveVideoAssets(UUID tenantId, UUID brandId, UUID excludingItem) {
        List<UUID> candidates = store.liveRevisionAssetIds(tenantId, brandId, excludingItem);
        if (candidates.isEmpty()) {
            return Set.of();
        }
        Set<UUID> videos = new HashSet<>();
        media.describe(tenantId, candidates).forEach((id, creative) -> {
            if (creative.kind() == MediaCreativeLookup.Kind.VIDEO) {
                videos.add(id);
            }
        });
        return videos;
    }

    private static void videos(
            Subject subject, Map<UUID, Creative> creatives, Set<UUID> liveVideos, int cap, List<FieldProblem> out) {
        Set<UUID> mine = new HashSet<>();
        for (AssetUse use : subject.facts().assets()) {
            Creative creative = creatives.get(use.assetId());
            if (creative != null && creative.kind() == MediaCreativeLookup.Kind.VIDEO) {
                mine.add(use.assetId());
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        Set<UUID> all = new HashSet<>(liveVideos);
        all.addAll(mine);
        if (all.size() > cap) {
            out.add(new FieldProblem(
                    "media",
                    Codes.VIDEO_LIMIT,
                    "A brand has at most " + cap + " live videos; this publication would make " + all.size()));
        }
    }

    /** Every kind's assets, for a caller that wants the ids of a validated document. */
    public static Collection<UUID> assetIds(DocumentFacts facts) {
        Set<UUID> ids = new LinkedHashSet<>();
        facts.assets().forEach(use -> ids.add(use.assetId()));
        return ids;
    }

    /** Re-validates a stored document; the facts are then the grammar's, not a stale copy. */
    public static ContentDocuments.Validated revalidate(ContentKind kind, JsonNode stored) {
        return ContentDocuments.validate(kind, stored);
    }
}
