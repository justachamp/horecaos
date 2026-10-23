package uz.horecaos.platform.content.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.content.api.ContentPolicies;
import uz.horecaos.platform.content.api.ContentTargetLookup;
import uz.horecaos.platform.content.api.PromotionLiveLookup;
import uz.horecaos.platform.content.application.HomeDocument.CarouselItem;
import uz.horecaos.platform.content.application.HomeDocument.Group;
import uz.horecaos.platform.content.application.HomeDocument.Link;
import uz.horecaos.platform.content.application.HomeDocument.Media;
import uz.horecaos.platform.content.application.HomeDocument.Popup;
import uz.horecaos.platform.content.application.HomeDocument.Slide;
import uz.horecaos.platform.content.application.HomeDocument.StoryGroup;
import uz.horecaos.platform.content.application.HomeDocument.SwitcherEntry;
import uz.horecaos.platform.content.domain.ContentDocuments;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.domain.ContentPlacement;
import uz.horecaos.platform.content.domain.ContentVisibility;
import uz.horecaos.platform.content.domain.DocumentFacts;
import uz.horecaos.platform.content.domain.DocumentProblems;
import uz.horecaos.platform.content.domain.ExternalUrls;
import uz.horecaos.platform.content.domain.ItemState;
import uz.horecaos.platform.content.domain.LinkType;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.ItemRow;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.LiveItem;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.PreviewItem;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.media.api.MediaCreativeLookup.Creative;
import uz.horecaos.platform.tenancy.api.PlatformLocales;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;

/**
 * Builds the storefront document from items (ADR 0157, Storefront API: "How the document is
 * built, once, by one assembler with two sources").
 *
 * <p>Two sources -- {@link Source#LIVE} and {@link Source#DRAFT_OVERLAY} for the preview -- and one
 * implementation, so the preview cannot drift from production. The pipeline, in the ADR's order:
 *
 * <ol>
 *   <li>select the brand's published items joined to their live revisions where the channel set is
 *       empty or holds the channel and the window contains <em>now</em> (start inclusive, end
 *       exclusive, from the injected clock);
 *   <li>drop a card bound to a promotion that is not live;
 *   <li>resolve link targets against the brand's active publication, degrading an unresolved
 *       {@code PRODUCT}/{@code CATEGORY} link to {@code NONE} and keeping the item;
 *   <li>re-check the host of every {@code EXTERNAL} link against the effective allowlist, degrading a
 *       link whose host has since left it to {@code NONE} the same way: the allowlist is the
 *       open-redirect control at the moment a customer is shown the link, not only on the day it
 *       was authored;
 *   <li>resolve each text through the visitor's language, the brand's default, and the registry
 *       fallback, and say which it was;
 *   <li>attach only the renditions that exist, and drop an item whose image cannot be served;
 *   <li>order by placement, sort key, creation time and id.
 * </ol>
 *
 * <p>A failure to read content is the caller's to isolate: the storefront renders without it and
 * the menu is never blanked.
 */
@Component
public class ContentHomeAssembler {

    /** Where the items come from. */
    public enum Source {
        /** What customers are shown: published items on their live revisions. */
        LIVE,
        /** What customers would be shown if every draft were published now. */
        DRAFT_OVERLAY
    }

    private static final List<ContentPlacement> HOME_PLACEMENTS = List.of(
            ContentPlacement.HOME_CAROUSEL,
            ContentPlacement.HOME_GROUPS,
            ContentPlacement.STORY_RAIL,
            ContentPlacement.PROMO_GRID,
            ContentPlacement.ENTRY_MODAL);

    private final JdbcContentStore store;
    private final ContentItemViewer viewer;
    private final ContentEnvironment environment;
    private final MediaCreativeLookup media;
    private final ContentTargetLookup targets;
    private final PromotionLiveLookup promotions;
    private final BrandSwitcherService switcher;

    public ContentHomeAssembler(
            JdbcContentStore store,
            ContentItemViewer viewer,
            ContentEnvironment environment,
            MediaCreativeLookup media,
            ContentTargetLookup targets,
            PromotionLiveLookup promotions,
            BrandSwitcherService switcher) {
        this.store = store;
        this.viewer = viewer;
        this.environment = environment;
        this.media = media;
        this.targets = targets;
        this.promotions = promotions;
        this.switcher = switcher;
    }

    /** One item, ready to be read: its document and what it refers to. */
    private record Snapshot(
            ItemRow item,
            int revisionNo,
            JsonNode document,
            DocumentFacts facts,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId) {}

    public HomeDocument assemble(
            UUID tenantId,
            UUID brandId,
            SalesChannel channel,
            @Nullable String requestedLocale,
            Instant now,
            Source source) {
        TenantLocaleSet locales = environment.locales(tenantId, brandId);
        List<String> chain = languageChain(requestedLocale, locales.defaultLocale());
        String documentLocale = chain.getFirst();

        if (!ContentEnvironment.CONTENT_SURFACES.contains(channel.systemType())) {
            // A QR_TABLE channel shows the menu only, and a kiosk, an aggregator, a POS and a call
            // centre have no content surface until a decision gives them one.
            return new HomeDocument(documentLocale, List.of(), List.of(), List.of(), List.of(), null);
        }

        List<Snapshot> snapshots = snapshots(tenantId, brandId, channel, now, source);

        // ---- one batch each of the lookups, for the whole document
        Set<UUID> assetIds = new LinkedHashSet<>();
        Set<UUID> productIds = new HashSet<>();
        Set<UUID> categoryIds = new HashSet<>();
        Set<UUID> promotionIds = new HashSet<>();
        boolean anyExternal = false;
        for (Snapshot snapshot : snapshots) {
            snapshot.facts().assets().forEach(use -> assetIds.add(use.assetId()));
            productIds.addAll(snapshot.facts().productIds());
            categoryIds.addAll(snapshot.facts().categoryIds());
            promotionIds.addAll(snapshot.facts().promotionIds());
            if (snapshot.promotionId() != null) {
                promotionIds.add(snapshot.promotionId());
            }
            anyExternal |= !snapshot.facts().externalRefs().isEmpty();
        }
        Map<UUID, Creative> creatives = assetIds.isEmpty() ? Map.of() : media.describe(tenantId, assetIds);
        ContentTargetLookup.Existing existing = productIds.isEmpty() && categoryIds.isEmpty()
                ? ContentTargetLookup.Existing.none()
                : targets.existing(tenantId, brandId, channel.code(), productIds, categoryIds);
        Set<UUID> livePromotions =
                promotionIds.isEmpty() ? Set.of() : promotions.live(tenantId, brandId, promotionIds, now);
        // Read only when a live item carries a link that is checked against it.
        Set<String> allowlist = anyExternal ? environment.effectiveAllowlist(tenantId, brandId) : Set.of();

        Context context = new Context(tenantId, chain, creatives, existing, livePromotions, allowlist);
        List<CarouselItem> carousel = new ArrayList<>();
        List<CarouselItem> promoGrid = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        List<StoryGroup> stories = new ArrayList<>();
        Popup popup = null;
        boolean videosDecided = false;
        boolean videoEntitled = false;

        for (Snapshot snapshot : snapshots) {
            boolean hasVideo = snapshot.facts().assets().stream().anyMatch(use -> {
                Creative creative = creatives.get(use.assetId());
                return creative != null && creative.kind() == MediaCreativeLookup.Kind.VIDEO;
            });
            if (hasVideo) {
                if (!videosDecided) {
                    videoEntitled = environment.videoEntitled(tenantId);
                    videosDecided = true;
                }
                if (!videoEntitled) {
                    // Rollback of Release 3 is the entitlement going false: live videos stop being
                    // returned, uploads are refused, nothing is deleted.
                    continue;
                }
            }
            ContentKind kind = snapshot.item().kind();
            switch (kind) {
                case BANNER, PROMO_CARD -> {
                    // A card bound to a promotion is visible only while that promotion is live.
                    if (snapshot.promotionId() != null && !livePromotions.contains(snapshot.promotionId())) {
                        continue;
                    }
                    Optional<CarouselItem> item = card(snapshot, context);
                    if (item.isPresent()) {
                        if (snapshot.item().placement() == ContentPlacement.PROMO_GRID) {
                            promoGrid.add(item.get());
                        } else {
                            carousel.add(item.get());
                        }
                    }
                }
                case PRODUCT_GROUP -> group(snapshot, context).ifPresent(groups::add);
                case STORY_GROUP -> story(snapshot, context).ifPresent(stories::add);
                case POPUP -> {
                    if (popup == null) {
                        popup = popup(snapshot, context).orElse(null);
                    }
                }
                default -> {
                    // Editorial kinds are not on the home screen.
                }
            }
        }
        // Release 4 (ADR 0183 D17): a switcher only on a website channel (its links leave the Mini App and the apps),
        // and a
        // layout only when it is not the standard one, so a brand that uses neither is served the document it always
        // was.
        List<SwitcherEntry> brandSwitcher = channel.systemType() == SalesChannelSystemType.WEB
                ? switcher.forStorefront(tenantId, brandId)
                : List.of();
        String layout = environment.storefrontLayout(tenantId, brandId);
        return new HomeDocument(
                documentLocale,
                carousel,
                groups,
                stories,
                promoGrid,
                popup,
                brandSwitcher,
                ContentPolicies.StorefrontLayoutPolicy.STANDARD.equals(layout) ? null : layout);
    }

    // ----------------------------------------------------------------- selection

    private List<Snapshot> snapshots(UUID tenantId, UUID brandId, SalesChannel channel, Instant now, Source source) {
        List<Snapshot> all = new ArrayList<>();
        if (source == Source.LIVE) {
            for (LiveItem live : store.publishedItems(tenantId, brandId, HOME_PLACEMENTS)) {
                snapshot(
                                live.item(),
                                live.revision().revisionNo(),
                                live.revision().documentJson(),
                                live.revision().channelCodes(),
                                live.revision().startsAt(),
                                live.revision().endsAt(),
                                live.revision().promotionId())
                        .ifPresent(all::add);
            }
        } else {
            for (PreviewItem preview : store.previewItems(tenantId, brandId, HOME_PLACEMENTS)) {
                ItemRow item = preview.item();
                if (preview.draft() != null) {
                    snapshot(
                                    item,
                                    0,
                                    preview.draft().documentJson(),
                                    preview.draft().channelCodes(),
                                    preview.draft().startsAt(),
                                    preview.draft().endsAt(),
                                    preview.draft().promotionId())
                            .ifPresent(all::add);
                } else if (preview.live() != null && item.state() == ItemState.PUBLISHED) {
                    snapshot(
                                    item,
                                    preview.live().revisionNo(),
                                    preview.live().documentJson(),
                                    preview.live().channelCodes(),
                                    preview.live().startsAt(),
                                    preview.live().endsAt(),
                                    preview.live().promotionId())
                            .ifPresent(all::add);
                }
            }
        }
        return all.stream()
                .filter(s -> s.channelCodes().isEmpty() || s.channelCodes().contains(channel.code()))
                .filter(s -> ContentVisibility.of(s.startsAt(), s.endsAt(), now) == ContentVisibility.LIVE)
                .sorted(Comparator.comparing((Snapshot s) -> s.item().placement() == null
                                ? "~"
                                : s.item().placement().name())
                        .thenComparingInt(s -> s.item().sortKey())
                        .thenComparing(s -> s.item().createdAt())
                        // The id as text: lexicographic hex is PostgreSQL's unsigned byte order, which is
                        // the order the live query sorts in. UUID.compareTo compares signed halves and
                        // would disagree for an id whose first bit is set.
                        .thenComparing(s -> s.item().id().toString()))
                .toList();
    }

    private Optional<Snapshot> snapshot(
            ItemRow item,
            int revisionNo,
            String documentJson,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId) {
        try {
            ContentDocuments.Validated validated = ContentDocuments.validate(item.kind(), viewer.parse(documentJson));
            return Optional.of(new Snapshot(
                    item,
                    revisionNo,
                    validated.document(),
                    validated.facts(),
                    channelCodes,
                    startsAt,
                    endsAt,
                    promotionId));
        } catch (DocumentProblems.InvalidDocumentException stale) {
            // A stored document the current grammar refuses is not shown: the console's health read
            // reports it, and a customer never sees half of one.
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------- builders

    private record Context(
            UUID tenantId,
            List<String> chain,
            Map<UUID, Creative> creatives,
            ContentTargetLookup.Existing existing,
            Set<UUID> livePromotions,
            Set<String> allowlist) {}

    private Optional<CarouselItem> card(Snapshot snapshot, Context context) {
        JsonNode document = snapshot.document();
        Chosen text = choose(document.path("texts"), context.chain(), "title");
        if (text == null) {
            return Optional.empty();
        }
        UUID asset = assetFor(document, text.node());
        if (asset == null) {
            return Optional.empty();
        }
        String alt = stringOrNull(text.node().path("alt"));
        Media media = mediaOf(
                asset,
                alt != null ? alt : text.node().path("title").asString(""),
                document.path("media").path("posterAssetId"),
                context);
        if (media == null) {
            return Optional.empty();
        }
        return Optional.of(new CarouselItem(
                snapshot.item().id(),
                snapshot.item().kind().name(),
                snapshot.revisionNo(),
                text.node().path("title").asString(""),
                stringOrNull(text.node().path("subtitle")),
                stringOrNull(text.node().path("ctaLabel")),
                text.tag(),
                media,
                link(document.path("link"), context)));
    }

    private Optional<Group> group(Snapshot snapshot, Context context) {
        JsonNode document = snapshot.document();
        Chosen text = choose(document.path("texts"), context.chain(), "title");
        if (text == null) {
            return Optional.empty();
        }
        String mode = document.path("mode").asString("");
        String title = text.node().path("title").asString("");
        if ("BY_CATEGORY".equals(mode)) {
            UUID category = uuidOrNull(document.path("categoryId"));
            if (category == null || !context.existing().categoryIds().contains(category)) {
                return Optional.empty();
            }
            return Optional.of(new Group(
                    snapshot.item().id(),
                    snapshot.revisionNo(),
                    title,
                    text.tag(),
                    List.of(),
                    category,
                    document.path("order").asString("MENU_ORDER"),
                    document.path("limit").isInt() ? document.path("limit").intValue() : 8));
        }
        // The server drops any id absent from the brand's active publication for the channel, so the
        // content read and the menu read cannot disagree about existence. Availability (a stopped
        // dish) stays the menu's business and is not folded into this read.
        List<UUID> members = new ArrayList<>();
        for (JsonNode member : document.path("members")) {
            UUID id = uuidOrNull(member.path("id"));
            if (id != null && context.existing().productIds().contains(id)) {
                members.add(id);
            }
        }
        if (members.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new Group(snapshot.item().id(), snapshot.revisionNo(), title, text.tag(), members, null, null, null));
    }

    private Optional<StoryGroup> story(Snapshot snapshot, Context context) {
        JsonNode document = snapshot.document();
        Chosen text = choose(document.path("texts"), context.chain(), "title");
        if (text == null) {
            return Optional.empty();
        }
        List<Slide> slides = new ArrayList<>();
        for (JsonNode slide : document.path("slides")) {
            UUID asset = uuidOrNull(slide.path("media").path("assetId"));
            if (asset == null) {
                continue;
            }
            Media media = mediaOf(
                    asset,
                    text.node().path("title").asString(""),
                    slide.path("media").path("posterAssetId"),
                    context);
            if (media == null) {
                continue;
            }
            Chosen caption = choose(slide.path("texts"), context.chain(), "caption");
            slides.add(new Slide(
                    media,
                    slide.path("durationSeconds").isInt()
                            ? slide.path("durationSeconds").intValue()
                            : 5,
                    caption == null ? null : caption.node().path("caption").asString(""),
                    link(slide.path("link"), context)));
        }
        if (slides.isEmpty()) {
            return Optional.empty();
        }
        UUID avatarAsset = uuidOrNull(document.path("avatarAssetId"));
        Media avatar = avatarAsset == null
                ? null
                : mediaOf(avatarAsset, text.node().path("title").asString(""), null, context);
        return Optional.of(new StoryGroup(
                snapshot.item().id(),
                snapshot.revisionNo(),
                text.node().path("title").asString(""),
                text.tag(),
                avatar,
                slides));
    }

    private Optional<Popup> popup(Snapshot snapshot, Context context) {
        JsonNode document = snapshot.document();
        Chosen text = choose(document.path("texts"), context.chain(), "title");
        if (text == null) {
            return Optional.empty();
        }
        UUID asset = uuidOrNull(document.path("media").path("primaryAssetId"));
        Media media = asset == null
                ? null
                : mediaOf(
                        asset,
                        text.node().path("title").asString(""),
                        document.path("media").path("posterAssetId"),
                        context);
        if (asset != null && media == null) {
            return Optional.empty();
        }
        HomeDocument.Daily daily = null;
        JsonNode dailyNode = document.path("daily");
        if (dailyNode.isObject()) {
            List<Integer> days = new ArrayList<>();
            for (JsonNode day : dailyNode.path("daysOfWeek")) {
                days.add(day.intValue());
            }
            daily = new HomeDocument.Daily(
                    dailyNode.path("from").asString(""), dailyNode.path("until").asString(""), days);
        }
        JsonNode cap = document.path("cap");
        return Optional.of(new Popup(
                snapshot.item().id(),
                snapshot.revisionNo(),
                text.node().path("title").asString(""),
                stringOrNull(text.node().path("body")),
                stringOrNull(text.node().path("ctaLabel")),
                text.tag(),
                media,
                link(document.path("link"), context),
                document.path("showAfterSeconds").isInt()
                        ? document.path("showAfterSeconds").intValue()
                        : 3,
                daily,
                new HomeDocument.Cap(
                        cap.path("mode").asString("ONCE_PER_DAY"),
                        cap.path("n").isInt() ? cap.path("n").intValue() : 1),
                document.path("dismiss").asString("DISMISSIBLE")));
    }

    // ----------------------------------------------------------------------- parts

    /** The picture of a card in the chosen language: that language's own, or the one every language shares. */
    private static @Nullable UUID assetFor(JsonNode document, JsonNode chosenText) {
        UUID own = uuidOrNull(chosenText.path("mediaAssetId"));
        return own != null ? own : uuidOrNull(document.path("media").path("primaryAssetId"));
    }

    /**
     * A servable image or video, or null when the asset cannot be served: a missing, unverified or
     * private asset, an image with no rendition yet. The storefront redirect answers 404 for all of
     * them, so an item built on one would show every customer a broken image.
     */
    private @Nullable Media mediaOf(UUID asset, String alt, @Nullable JsonNode posterNode, Context context) {
        // One implementation for the home read and the editorial reads, so an asset cannot be servable
        // on one and broken on the other.
        return MediaViews.of(
                context.tenantId(),
                asset,
                alt,
                posterNode == null ? null : uuidOrNull(posterNode),
                context.creatives());
    }

    private Link link(JsonNode node, Context context) {
        if (!node.isObject()) {
            return Link.none();
        }
        Optional<LinkType> type = LinkType.find(node.path("type").asString(""));
        if (type.isEmpty()) {
            return Link.none();
        }
        UUID target = uuidOrNull(node.path("targetId"));
        return switch (type.get()) {
            case NONE -> Link.none();
            case CART -> new Link("CART", null, null);
            case PRODUCT ->
                target != null && context.existing().productIds().contains(target)
                        ? new Link("PRODUCT", target, null)
                        : Link.none();
            case CATEGORY ->
                target != null && context.existing().categoryIds().contains(target)
                        ? new Link("CATEGORY", target, null)
                        : Link.none();
            case PROMOTION ->
                target != null && context.livePromotions().contains(target)
                        ? new Link("PROMOTION", target, null)
                        : Link.none();
            case EXTERNAL -> {
                String url = node.path("url").asString("");
                Optional<ExternalUrls.Parsed> parsed = ExternalUrls.parseHttps(url);
                yield parsed.isPresent()
                                && context.allowlist().contains(parsed.get().host())
                        ? new Link("EXTERNAL", null, parsed.get().url())
                        : Link.none();
            }
        };
    }

    // ---------------------------------------------------------------- languages

    /**
     * The order a text is looked for in: the visitor's language, the brand's default, the registry
     * fallback. Never another arbitrary language and never an error (ADR 0157, Decision 4).
     */
    static List<String> languageChain(@Nullable String requested, String brandDefault) {
        List<String> chain = new ArrayList<>();
        PlatformLocales.parseActive(requested, uz.horecaos.platform.tenancy.api.PlatformLocale.Tier.CONTENT)
                .ifPresent(locale -> chain.add(locale.tag()));
        if (!chain.contains(brandDefault)) {
            chain.add(brandDefault);
        }
        String fallback = TenantLocaleSet.platformDefaultLocale();
        if (!chain.contains(fallback)) {
            chain.add(fallback);
        }
        return chain;
    }

    private record Chosen(String tag, JsonNode node) {}

    private static @Nullable Chosen choose(JsonNode texts, List<String> chain, String field) {
        for (String tag : chain) {
            JsonNode candidate = texts.path(tag);
            if (candidate.isObject() && !candidate.path(field).asString("").isEmpty()) {
                return new Chosen(tag, candidate);
            }
        }
        return null;
    }

    private static @Nullable String stringOrNull(JsonNode node) {
        return node.isString() && !node.stringValue().isEmpty() ? node.stringValue() : null;
    }

    private static @Nullable UUID uuidOrNull(@Nullable JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return UUID.fromString(node.stringValue());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
