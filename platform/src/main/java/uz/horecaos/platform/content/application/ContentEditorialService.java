package uz.horecaos.platform.content.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import uz.horecaos.platform.content.api.ContentPolicies.EditorialPolicy;
import uz.horecaos.platform.content.application.HomeDocument.Media;
import uz.horecaos.platform.content.domain.ContentDocuments;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.domain.ContentVisibility;
import uz.horecaos.platform.content.domain.DocumentProblems;
import uz.horecaos.platform.content.domain.ExternalUrls;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.LiveItem;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.media.api.MediaCreativeLookup.Creative;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.web.api.Cursor;

/**
 * The editorial tier as a storefront reads it (ADR 0157, Decision 7; Release 5): news, gallery
 * albums, recipes and a vacancy <em>listing</em>.
 *
 * <p>Every route exists for a brand only when <em>both</em> the {@code content.editorial.enabled}
 * entitlement and the brand's {@code content.editorial} policy say yes for that kind; otherwise it is
 * a 404, as if the route did not exist, and the rows stay. A body is a typed block document, never
 * markup; it is rendered here with each {@code https} link checked against the brand's allowlist
 * <em>again</em> (a link whose host has left it is served as plain text) and each image replaced by
 * the renditions that exist. A vacancy carries one outbound contact and nothing of a candidate: the
 * platform has no application form and stores no applicant data, which is what keeps the recruitment
 * exclusion standing.
 */
@Service
public class ContentEditorialService {

    private static final TypeReference<List<Map<String, Object>>> BLOCKS = new TypeReference<>() {};
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    /** Which of the four kinds this brand runs. */
    public record Features(boolean news, boolean gallery, boolean recipes, boolean vacancies) {}

    /** A row of a listing: enough to draw a card. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntrySummary(
            String slug,
            String title,
            @Nullable String summary,
            String textLocale,
            @Nullable Media cover,
            Instant publishedAt,
            @Nullable String employmentType) {}

    public record EntryPage(
            List<EntrySummary> items, @Nullable String nextCursor) {

        public EntryPage {
            items = List.copyOf(items);
        }
    }

    /** One entry in full. Only the fields that belong to its kind are set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntryDetail(
            String slug,
            String kind,
            String title,
            @Nullable String summary,
            String textLocale,
            @Nullable Media cover,
            Instant publishedAt,
            @Nullable String bodyLocale,
            List<Map<String, Object>> blocks,
            List<AlbumImage> images,
            @Nullable Vacancy vacancy) {

        public EntryDetail {
            blocks = List.copyOf(blocks);
            images = List.copyOf(images);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlbumImage(Media media, @Nullable String caption) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Vacancy(
            String employmentType,
            @Nullable Apply apply,
            @Nullable Branch branch) {}

    /** The one outbound contact the tenant chose to publish. */
    public record Apply(String type, String value) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Branch(
            UUID locationId,
            String name,
            @Nullable String addressLine,
            @Nullable String city) {}

    private final JdbcContentStore store;
    private final ContentEnvironment environment;
    private final ContentItemViewer viewer;
    private final MediaCreativeLookup media;
    private final BranchDirectory branches;
    private final ObjectMapper json;
    private final Clock clock;

    public ContentEditorialService(
            JdbcContentStore store,
            ContentEnvironment environment,
            ContentItemViewer viewer,
            MediaCreativeLookup media,
            BranchDirectory branches,
            ObjectMapper json,
            Clock clock) {
        this.store = store;
        this.environment = environment;
        this.viewer = viewer;
        this.media = media;
        this.branches = branches;
        this.json = json;
        this.clock = clock;
    }

    // ================================================================= features

    /** Empty for an unknown or inactive brand; otherwise which kinds are on (all false without the entitlement). */
    @Transactional(readOnly = true)
    public Optional<Features> features(UUID tenantId, UUID brandId) {
        if (!environment.brand().brandIsActive(tenantId, brandId)) {
            return Optional.empty();
        }
        if (!environment.editorialEntitled(tenantId)) {
            return Optional.of(new Features(false, false, false, false));
        }
        EditorialPolicy policy = environment.editorialPolicy(tenantId, brandId);
        return Optional.of(new Features(policy.newsOn(), policy.galleryOn(), policy.recipesOn(), policy.vacanciesOn()));
    }

    private boolean enabled(UUID tenantId, UUID brandId, ContentKind kind) {
        return features(tenantId, brandId)
                .map(f -> switch (kind) {
                    case NEWS -> f.news();
                    case GALLERY_ALBUM -> f.gallery();
                    case RECIPE -> f.recipes();
                    case VACANCY -> f.vacancies();
                    default -> false;
                })
                .orElse(false);
    }

    // ===================================================================== list

    /**
     * A listing, newest first, cursor-paged. Empty (a 404) when the kind is not on for the brand or
     * the channel is unknown.
     */
    @Transactional(readOnly = true)
    public Optional<EntryPage> list(
            UUID tenantId,
            UUID brandId,
            ContentKind kind,
            String channelCode,
            @Nullable String locale,
            @Nullable String cursor,
            @Nullable Integer requestedLimit) {
        Optional<SalesChannel> channel = channel(tenantId, brandId, kind, channelCode);
        if (channel.isEmpty()) {
            return Optional.empty();
        }
        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        Instant now = clock.instant();
        List<String> chain = ContentHomeAssembler.languageChain(
                locale, environment.locales(tenantId, brandId).defaultLocale());

        List<Entry> live = liveEntries(tenantId, brandId, kind, channel.get(), now);
        live.sort(Comparator.comparing(Entry::sortInstant)
                .reversed()
                .thenComparing(e -> e.row().item().id().toString(), Comparator.reverseOrder()));

        String filterHash = kind + "|" + channelCode;
        Cursor after = Cursor.decodeUnsigned(cursor, filterHash).orElse(null);
        List<Entry> page = new ArrayList<>();
        boolean more = false;
        for (Entry entry : live) {
            if (after != null && !isAfter(entry, after)) {
                continue;
            }
            if (page.size() == limit) {
                more = true;
                break;
            }
            page.add(entry);
        }

        Set<UUID> assets = new LinkedHashSet<>();
        page.forEach(e -> e.coverAsset().ifPresent(assets::add));
        Map<UUID, Creative> creatives = assets.isEmpty() ? Map.of() : media.describe(tenantId, assets);
        List<EntrySummary> summaries = new ArrayList<>();
        for (Entry entry : page) {
            titled(entry, chain)
                    .ifPresent(title -> summaries.add(new EntrySummary(
                            entry.slug(),
                            title.title(),
                            title.summary(),
                            title.locale(),
                            entry.coverAsset()
                                    .map(a -> MediaViews.image(tenantId, a, title.title(), creatives))
                                    .orElse(null),
                            entry.row().revision().publishedAt(),
                            kind == ContentKind.VACANCY
                                    ? entry.document()
                                            .path("vacancy")
                                            .path("employmentType")
                                            .asString(null)
                                    : null)));
        }
        String next = more
                ? new Cursor(
                                page.getLast().sortInstant().toEpochMilli() + ":"
                                        + page.getLast().row().item().id(),
                                filterHash)
                        .encodeUnsigned()
                : null;
        return Optional.of(new EntryPage(summaries, next));
    }

    private static boolean isAfter(Entry entry, Cursor cursor) {
        String[] parts = cursor.sortKey().split(":", 2);
        if (parts.length != 2) {
            return true;
        }
        long millis;
        try {
            millis = Long.parseLong(parts[0]);
        } catch (NumberFormatException malformed) {
            return true;
        }
        int byTime = Long.compare(entry.sortInstant().toEpochMilli(), millis);
        if (byTime != 0) {
            return byTime < 0;
        }
        return entry.row().item().id().toString().compareTo(parts[1]) < 0;
    }

    // ==================================================================== detail

    @Transactional(readOnly = true)
    public Optional<EntryDetail> detail(
            UUID tenantId, UUID brandId, ContentKind kind, String slug, String channelCode, @Nullable String locale) {
        Optional<SalesChannel> channel = channel(tenantId, brandId, kind, channelCode);
        if (channel.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Entry entry = liveEntries(tenantId, brandId, kind, channel.get(), now).stream()
                .filter(e -> e.slug().equals(slug))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return Optional.empty();
        }
        List<String> chain = ContentHomeAssembler.languageChain(
                locale, environment.locales(tenantId, brandId).defaultLocale());
        Titled title = titled(entry, chain).orElse(null);
        if (title == null) {
            return Optional.empty();
        }

        Set<UUID> assets = new HashSet<>();
        entry.facts().assets().forEach(use -> assets.add(use.assetId()));
        Map<UUID, Creative> creatives = assets.isEmpty() ? Map.of() : media.describe(tenantId, assets);
        Set<String> allowlist = entry.facts().externalRefs().isEmpty() && !bodyHasLinks(entry.document())
                ? Set.of()
                : environment.effectiveAllowlist(tenantId, brandId);

        Media cover = entry.coverAsset()
                .map(a -> MediaViews.image(tenantId, a, title.title(), creatives))
                .orElse(null);
        String bodyLocale = null;
        List<Map<String, Object>> blocks = List.of();
        JsonNode bodies = entry.document().path("body");
        for (String tag : chain) {
            JsonNode body = bodies.path(tag).path("blocks");
            if (body.isArray() && !body.isEmpty()) {
                bodyLocale = tag;
                blocks = json.convertValue(render(tenantId, body, creatives, allowlist), BLOCKS);
                break;
            }
        }

        List<AlbumImage> images = new ArrayList<>();
        for (JsonNode image : entry.document().path("albumImages")) {
            UUID asset = uuid(image.path("assetId"));
            if (asset == null) {
                continue;
            }
            Media view = MediaViews.image(tenantId, asset, null, creatives);
            if (view != null) {
                images.add(new AlbumImage(view, caption(image.path("captions"), chain)));
            }
        }

        Vacancy vacancy = null;
        if (kind == ContentKind.VACANCY) {
            vacancy = vacancy(tenantId, brandId, entry, allowlist);
        }
        return Optional.of(new EntryDetail(
                slug,
                kind.name(),
                title.title(),
                title.summary(),
                title.locale(),
                cover,
                entry.row().revision().publishedAt(),
                bodyLocale,
                blocks,
                images,
                vacancy));
    }

    private @Nullable Vacancy vacancy(UUID tenantId, UUID brandId, Entry entry, Set<String> allowlist) {
        JsonNode node = entry.document().path("vacancy");
        Apply apply = null;
        String type = node.path("apply").path("type").asString("");
        String value = node.path("apply").path("value").asString("");
        if (!value.isEmpty()) {
            boolean web = type.equals("URL") || type.equals("TELEGRAM");
            if (!web) {
                apply = new Apply(type, value);
            } else {
                Optional<ExternalUrls.Parsed> parsed = ExternalUrls.parseHttps(value);
                if (parsed.isPresent() && allowlist.contains(parsed.get().host())) {
                    apply = new Apply(type, parsed.get().url());
                }
            }
        }
        Branch branch = null;
        UUID location = entry.row().revision().locationId();
        if (location != null) {
            branch = branches.activeBranches(tenantId, brandId).stream()
                    .filter(b -> b.locationId().equals(location))
                    .findFirst()
                    .map(b -> new Branch(b.locationId(), b.name(), b.addressLine(), b.city()))
                    .orElse(null);
        }
        return new Vacancy(node.path("employmentType").asString("FULL_TIME"), apply, branch);
    }

    // =================================================================== rendering

    private static boolean bodyHasLinks(JsonNode document) {
        return document.path("body").toString().contains("\"href\":\"https:");
    }

    private ArrayNode render(UUID tenantId, JsonNode blocks, Map<UUID, Creative> creatives, Set<String> allowlist) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ArrayNode out = nodes.arrayNode();
        for (JsonNode block : blocks) {
            String type = block.path("type").asString("");
            ObjectNode copy = nodes.objectNode();
            copy.put("type", type);
            switch (type) {
                case "PARAGRAPH", "QUOTE" -> copy.set("inlines", inlines(block.path("inlines"), allowlist));
                case "HEADING" -> {
                    copy.put("level", block.path("level").asInt(2));
                    copy.set("inlines", inlines(block.path("inlines"), allowlist));
                }
                case "LIST" -> {
                    copy.put("ordered", block.path("ordered").asBoolean(false));
                    ArrayNode items = copy.putArray("items");
                    for (JsonNode item : block.path("items")) {
                        items.add(inlines(item, allowlist));
                    }
                }
                case "IMAGE" -> {
                    UUID asset = uuid(block.path("assetId"));
                    Media view = asset == null ? null : MediaViews.image(tenantId, asset, null, creatives);
                    if (view == null) {
                        continue;
                    }
                    copy.set("media", json.valueToTree(view));
                    if (block.path("caption").isArray()) {
                        copy.set("caption", inlines(block.path("caption"), allowlist));
                    }
                }
                case "DIVIDER" -> {
                    // nothing but its type
                }
                default -> {
                    // a block type this reader does not know is skipped; clients skip what they do not know too
                    continue;
                }
            }
            out.add(copy);
        }
        return out;
    }

    private ArrayNode inlines(JsonNode runs, Set<String> allowlist) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ArrayNode out = nodes.arrayNode();
        for (JsonNode run : runs) {
            ObjectNode copy = nodes.objectNode();
            copy.put("text", run.path("text").asString(""));
            if (run.path("bold").asBoolean(false)) {
                copy.put("bold", true);
            }
            if (run.path("italic").asBoolean(false)) {
                copy.put("italic", true);
            }
            String href = run.path("href").asString("");
            if (!href.isEmpty()) {
                if (href.startsWith("https://")) {
                    // Checked again on every read: a host that left the allowlist is served as plain text.
                    Optional<ExternalUrls.Parsed> parsed = ExternalUrls.parseHttps(href);
                    if (parsed.isPresent() && allowlist.contains(parsed.get().host())) {
                        copy.put("href", parsed.get().url());
                    }
                } else {
                    copy.put("href", href);
                }
            }
            out.add(copy);
        }
        return out;
    }

    // ====================================================================== plumbing

    /** A live entry with its parsed document. */
    private record Entry(LiveItem row, JsonNode document, uz.horecaos.platform.content.domain.DocumentFacts facts) {

        String slug() {
            return java.util.Objects.requireNonNullElse(row.item().slug(), "");
        }

        Instant sortInstant() {
            return row.revision().startsAt() != null
                    ? row.revision().startsAt()
                    : row.revision().publishedAt();
        }

        Optional<UUID> coverAsset() {
            return Optional.ofNullable(uuid(document.path("coverAssetId")));
        }
    }

    private record Titled(String title, @Nullable String summary, String locale) {}

    private Optional<Titled> titled(Entry entry, List<String> chain) {
        JsonNode texts = entry.document().path("texts");
        for (String tag : chain) {
            JsonNode text = texts.path(tag);
            String title = text.path("title").asString("");
            if (!title.isEmpty()) {
                String summary = text.path("summary").asString("");
                return Optional.of(new Titled(title, summary.isEmpty() ? null : summary, tag));
            }
        }
        return Optional.empty();
    }

    private static @Nullable String caption(JsonNode captions, List<String> chain) {
        for (String tag : chain) {
            String caption = captions.path(tag).asString("");
            if (!caption.isEmpty()) {
                return caption;
            }
        }
        return null;
    }

    private Optional<SalesChannel> channel(UUID tenantId, UUID brandId, ContentKind kind, String channelCode) {
        if (!enabled(tenantId, brandId, kind)) {
            return Optional.empty();
        }
        return environment
                .channel(tenantId, channelCode)
                .filter(c -> ContentEnvironment.CONTENT_SURFACES.contains(c.systemType()));
    }

    private List<Entry> liveEntries(UUID tenantId, UUID brandId, ContentKind kind, SalesChannel channel, Instant now) {
        List<Entry> entries = new ArrayList<>();
        for (LiveItem row : store.publishedByKind(tenantId, brandId, kind)) {
            if (!row.revision().channelCodes().isEmpty()
                    && !row.revision().channelCodes().contains(channel.code())) {
                continue;
            }
            if (ContentVisibility.of(row.revision().startsAt(), row.revision().endsAt(), now)
                    != ContentVisibility.LIVE) {
                continue;
            }
            try {
                var validated = ContentDocuments.validate(
                        kind, viewer.parse(row.revision().documentJson()));
                entries.add(new Entry(row, validated.document(), validated.facts()));
            } catch (DocumentProblems.InvalidDocumentException stale) {
                // a stored document the current grammar refuses is not shown
            }
        }
        return entries;
    }

    private static @Nullable UUID uuid(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        try {
            return UUID.fromString(node.stringValue());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The default language of a brand, for a caller that wants to say which it answered in. */
    String defaultLocale(UUID tenantId, UUID brandId) {
        TenantLocaleSet locales = environment.locales(tenantId, brandId);
        return locales.defaultLocale();
    }

    /** Stable view of the languages: used by tests. */
    static Map<String, String> languageNames(List<String> chain) {
        Map<String, String> names = new LinkedHashMap<>();
        chain.forEach(tag -> names.put(tag, tag));
        return names;
    }
}
