package uz.horecaos.platform.content.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.content.api.ContentEntitlements;
import uz.horecaos.platform.content.api.PromotionLiveLookup;
import uz.horecaos.platform.content.application.ContentViews.ItemPage;
import uz.horecaos.platform.content.application.ContentViews.ItemView;
import uz.horecaos.platform.content.application.ContentViews.RevisionSummary;
import uz.horecaos.platform.content.application.ContentViews.RevisionView;
import uz.horecaos.platform.content.domain.ContentDocuments;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.domain.ContentPlacement;
import uz.horecaos.platform.content.domain.DocumentFacts;
import uz.horecaos.platform.content.domain.DocumentProblems;
import uz.horecaos.platform.content.domain.FieldProblem;
import uz.horecaos.platform.content.domain.FieldProblem.Codes;
import uz.horecaos.platform.content.domain.ItemState;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.DraftRow;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.ItemRow;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.RevisionRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.Cursor;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;

/**
 * Authoring a brand's storefront content (ADR 0157, Decisions 1, 2 and 4): creating an item and its
 * first draft, replacing or discarding the draft, cloning an older revision into it, ordering a
 * placement, and reading all of it back.
 *
 * <p>Nothing here reaches a customer. A draft is {@code INTERNAL} and in front of nobody; what is
 * live is decided by {@link ContentPublicationService}. Every change takes the item's version in
 * {@code If-Match} (ADR 0031) and bumps it; ordering a placement has its own token, because moving a
 * banner is not an edit of the banner next to it.
 */
@Service
public class ContentAuthoringService {

    /** Non-archived items one brand may hold: a platform guardrail, a code constant (ADR 0157). */
    static final int MAX_ITEMS_PER_BRAND = 500;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int MAX_CHANNELS = 20;

    private final JdbcContentStore store;
    private final ContentEnvironment environment;
    private final ContentItemViewer viewer;
    private final PromotionLiveLookup promotions;
    private final BranchDirectory branches;
    private final MediaCreativeLookup media;
    private final AuditRecorder audit;
    private final Clock clock;

    public ContentAuthoringService(
            JdbcContentStore store,
            ContentEnvironment environment,
            ContentItemViewer viewer,
            PromotionLiveLookup promotions,
            BranchDirectory branches,
            MediaCreativeLookup media,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.environment = environment;
        this.viewer = viewer;
        this.promotions = promotions;
        this.branches = branches;
        this.media = media;
        this.audit = audit;
        this.clock = clock;
    }

    /** What an author sends for a draft. */
    public record DraftInput(
            Map<String, Object> document,
            @Nullable List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {}

    /** What an author sends to start an item. */
    public record CreateInput(
            ContentKind kind,
            @Nullable ContentPlacement placement,
            String internalName,
            @Nullable String slug,
            DraftInput draft) {}

    // ============================================================ create

    @Transactional
    public ItemView create(UUID tenantId, UUID brandId, String actor, CreateInput input) {
        requireBrand(tenantId, brandId);
        ContentKind kind = input.kind();
        ContentPlacement placement = resolvePlacement(kind, input.placement());
        requireKindEnabled(tenantId, brandId, kind);

        String slug = input.slug() == null || input.slug().isBlank()
                ? null
                : input.slug().strip();
        if (kind.editorial()) {
            if (slug == null || slug.length() > 80 || !SLUG.matcher(slug).matches()) {
                throw invalid(
                        "slug",
                        "BAD_SLUG",
                        "An editorial item needs a slug of lower-case letters, digits and hyphens (at most 80)");
            }
        } else if (slug != null) {
            throw invalid("slug", "NOT_FOR_KIND", kind + " has no slug");
        }
        String internalName =
                input.internalName() == null ? "" : input.internalName().strip();
        if (internalName.isEmpty() || internalName.length() > 120) {
            throw invalid("internalName", "BAD_NAME", "A staff-facing name of 1 to 120 characters is required");
        }
        if (store.countNonArchived(tenantId, brandId) >= MAX_ITEMS_PER_BRAND) {
            throw gate(new FieldProblem(
                    "items",
                    Codes.PLACEMENT_FULL,
                    "A brand holds at most " + MAX_ITEMS_PER_BRAND + " items that are not archived"));
        }

        Prepared prepared = prepare(tenantId, brandId, kind, input.draft());
        Instant now = clock.instant();
        UUID itemId = Ids.newId();
        try {
            store.insertItem(new ItemRow(
                    itemId,
                    tenantId,
                    brandId,
                    kind,
                    placement,
                    internalName,
                    displayTitle(tenantId, brandId, prepared),
                    slug,
                    ItemState.DRAFT,
                    null,
                    store.nextSortKey(tenantId, brandId, placement),
                    1,
                    actor,
                    now,
                    now));
        } catch (org.springframework.dao.DuplicateKeyException taken) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "That slug is already used by another " + kind + " of this brand");
        }
        store.upsertDraft(tenantId, brandId, draftRow(itemId, null, prepared, actor, now));
        return viewer.view(store.findItem(tenantId, brandId, itemId).orElseThrow());
    }

    // ============================================================= draft

    @Transactional
    public ItemView replaceDraft(
            UUID tenantId,
            UUID brandId,
            String actor,
            UUID itemId,
            long expectedVersion,
            @Nullable String internalName,
            DraftInput input) {
        ItemRow item = lock(tenantId, brandId, itemId, expectedVersion);
        requireNotArchived(item);
        requireKindEnabled(tenantId, brandId, item.kind());
        Prepared prepared = prepare(tenantId, brandId, item.kind(), input);
        Instant now = clock.instant();
        DraftRow existing = store.findDraft(tenantId, brandId, itemId).orElse(null);
        store.upsertDraft(
                tenantId,
                brandId,
                draftRow(itemId, existing == null ? null : existing.basedOnRevisionNo(), prepared, actor, now));
        String name = internalName == null || internalName.isBlank() ? null : internalName.strip();
        if (name != null && name.length() > 120) {
            throw invalid("internalName", "BAD_NAME", "At most 120 characters");
        }
        bump(item, name, displayTitle(tenantId, brandId, prepared), now);
        return viewer.view(store.findItem(tenantId, brandId, itemId).orElseThrow());
    }

    @Transactional
    public ItemView discardDraft(UUID tenantId, UUID brandId, UUID itemId, long expectedVersion) {
        ItemRow item = lock(tenantId, brandId, itemId, expectedVersion);
        requireNotArchived(item);
        store.deleteDraft(tenantId, brandId, itemId);
        bump(item, null, null, clock.instant());
        return viewer.view(store.findItem(tenantId, brandId, itemId).orElseThrow());
    }

    /**
     * Clones an older revision into the draft (ADR 0157, Decision 2: <em>republish</em> clones an
     * older revision into a new draft). Publishing it is a separate act. A revision whose media was
     * released is refused with a stated reason, because republishing it would put a broken image in
     * front of customers.
     */
    @Transactional
    public ItemView cloneRevisionIntoDraft(
            UUID tenantId, UUID brandId, String actor, UUID itemId, int revisionNo, long expectedVersion) {
        ItemRow item = lock(tenantId, brandId, itemId, expectedVersion);
        requireNotArchived(item);
        requireKindEnabled(tenantId, brandId, item.kind());
        RevisionRow revision = store.findRevision(tenantId, brandId, itemId, revisionNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such revision"));

        List<UUID> assetIds = store.revisionAssetIds(tenantId, itemId, revisionNo);
        if (!assetIds.isEmpty()) {
            Map<UUID, MediaCreativeLookup.Creative> described = media.describe(tenantId, assetIds);
            List<FieldProblem> released = new ArrayList<>();
            for (UUID asset : assetIds) {
                MediaCreativeLookup.Creative creative = described.get(asset);
                if (creative == null || !creative.status().isDisplayable()) {
                    released.add(new FieldProblem(
                            "revision",
                            Codes.REVISION_MEDIA_RELEASED,
                            "Asset " + asset + " of revision " + revisionNo
                                    + " has been released and cannot be shown again"));
                }
            }
            if (!released.isEmpty()) {
                throw gate(released.toArray(FieldProblem[]::new));
            }
        }

        Instant now = clock.instant();
        store.upsertDraft(
                tenantId,
                brandId,
                new DraftRow(
                        itemId,
                        revisionNo,
                        revision.documentJson(),
                        revision.channelCodes(),
                        revision.startsAt(),
                        revision.endsAt(),
                        revision.promotionId(),
                        revision.locationId(),
                        actor,
                        now,
                        1));
        bump(item, null, titleOf(tenantId, brandId, viewer.parse(revision.documentJson())), now);
        return viewer.view(store.findItem(tenantId, brandId, itemId).orElseThrow());
    }

    // ============================================================== reads

    @Transactional(readOnly = true)
    public ItemView get(UUID tenantId, UUID brandId, UUID itemId) {
        return viewer.view(store.findItem(tenantId, brandId, itemId).orElseThrow(ContentAuthoringService::noSuchItem));
    }

    /**
     * The console's list. {@code visibility} (LIVE, SCHEDULED, EXPIRED) is computed, so it filters the
     * page after the rows are read; a page may then hold fewer than {@code limit} rows and still have a
     * next cursor, which is the honest cost of filtering on something that is never stored.
     */
    @Transactional(readOnly = true)
    public ItemPage list(
            UUID tenantId,
            UUID brandId,
            @Nullable ContentKind kind,
            @Nullable ContentPlacement placement,
            @Nullable ItemState state,
            @Nullable String visibility,
            @Nullable String cursor,
            @Nullable Integer requestedLimit) {
        int limit = Page.limitOrDefault(requestedLimit);
        String filterHash = "%s|%s|%s|%s".formatted(kind, placement, state, visibility);
        ItemRow after = null;
        if (cursor != null && !cursor.isBlank()) {
            Cursor decoded = Cursor.decodeUnsigned(cursor, filterHash)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INVALID_REQUEST, "That cursor is not usable with these filters"));
            UUID afterId;
            try {
                afterId = UUID.fromString(decoded.sortKey());
            } catch (IllegalArgumentException malformed) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "That cursor is not usable with these filters");
            }
            after = store.findItem(tenantId, brandId, afterId).orElse(null);
        }
        List<ItemRow> rows = store.listItems(tenantId, brandId, kind, placement, state, false, after, limit + 1);
        boolean more = rows.size() > limit;
        List<ItemRow> page = more ? rows.subList(0, limit) : rows;
        var summaries = viewer.summaries(tenantId, brandId, page);
        if (visibility != null && !visibility.isBlank()) {
            summaries = summaries.stream()
                    .filter(row ->
                            row.visibility() != null && row.visibility().name().equalsIgnoreCase(visibility.strip()))
                    .toList();
        }
        String next = more ? new Cursor(page.getLast().id().toString(), filterHash).encodeUnsigned() : null;
        return new ItemPage(
                summaries,
                next,
                placement == null ? null : orderToken(store.placementItems(tenantId, brandId, placement)));
    }

    @Transactional(readOnly = true)
    public List<RevisionSummary> revisions(UUID tenantId, UUID brandId, UUID itemId) {
        ItemRow item = store.findItem(tenantId, brandId, itemId).orElseThrow(ContentAuthoringService::noSuchItem);
        return store.listRevisions(tenantId, brandId, itemId).stream()
                .map(revision -> new RevisionSummary(
                        revision.revisionNo(),
                        revision.contentHash(),
                        revision.channelCodes(),
                        revision.startsAt(),
                        revision.endsAt(),
                        revision.promotionId(),
                        revision.publishedBy(),
                        revision.publishedAt(),
                        item.liveRevisionNo() != null && item.liveRevisionNo() == revision.revisionNo()))
                .toList();
    }

    @Transactional(readOnly = true)
    public RevisionView revision(UUID tenantId, UUID brandId, UUID itemId, int revisionNo) {
        return store.findRevision(tenantId, brandId, itemId, revisionNo)
                .map(viewer::revisionView)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such revision"));
    }

    // ============================================================= order

    /** The result of ordering a placement. */
    public record Ordered(List<UUID> ids, String orderToken) {}

    /**
     * Orders a placement. Takes effect at once and is not a revision (ADR 0157, Decision 2). The
     * token is a digest of the order the author last read; if somebody moved something since, the
     * answer is {@code STALE_VERSION} and nothing is written.
     */
    @Transactional
    public Ordered reorder(
            UUID tenantId,
            UUID brandId,
            String actor,
            ContentPlacement placement,
            List<UUID> ids,
            String presentedToken,
            @Nullable String reason) {
        List<ItemRow> current = store.placementItemsForUpdate(tenantId, brandId, placement);
        String currentToken = orderToken(current);
        if (!currentToken.equals(presentedToken)) {
            throw new ApiException(
                    ErrorCode.STALE_VERSION,
                    "The order of " + placement + " has changed since it was read",
                    Map.of("currentOrderToken", currentToken));
        }
        Set<UUID> expected = new TreeSet<>();
        current.forEach(row -> expected.add(row.id()));
        if (ids.size() != expected.size() || !new TreeSet<>(ids).equals(expected)) {
            throw invalid("ids", "NOT_THE_PLACEMENT", "The list must name every item of the placement exactly once");
        }

        Instant now = clock.instant();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (int i = 0; i < current.size(); i++) {
            before.put(current.get(i).id().toString(), String.valueOf(i + 1));
        }
        int position = 1;
        for (UUID id : ids) {
            after.put(id.toString(), String.valueOf(position));
            store.setSortKey(tenantId, brandId, id, position * 10, now);
            position++;
        }
        audit.record(AuditFact.of("content.placement.reordered", AuditClass.BUSINESS)
                .by(ActorRef.user(actor, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("content.item", ids.isEmpty() ? current.getFirst().id() : ids.getFirst())
                .usingCapability(Capability.CONTENT_PUBLISH.code())
                .because(ContentPublicationService.reasonOr(reason, "Placement reordered"))
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(tenantId + ":" + placement)
                .occurredAt(now)
                .build());
        return new Ordered(ids, orderToken(store.placementItems(tenantId, brandId, placement)));
    }

    /** The token a client presents back: a digest of the order, so any move changes it. */
    public static String orderToken(List<ItemRow> inOrder) {
        StringBuilder text = new StringBuilder();
        for (ItemRow row : inOrder) {
            text.append(row.id()).append(':').append(row.sortKey()).append('\n');
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(text.toString().getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    // ============================================================ helpers

    /** A draft whose grammar, shape and references have passed; ready to store. */
    record Prepared(
            tools.jackson.databind.node.ObjectNode document,
            DocumentFacts facts,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {}

    Prepared prepare(UUID tenantId, UUID brandId, ContentKind kind, DraftInput input) {
        JsonNode raw = viewer.toTree(input.document());
        ContentDocuments.Validated validated;
        try {
            validated = ContentDocuments.validate(kind, raw);
        } catch (DocumentProblems.InvalidDocumentException invalid) {
            throw badDocument(invalid.problems());
        }
        if (input.startsAt() != null
                && input.endsAt() != null
                && !input.endsAt().isAfter(input.startsAt())) {
            throw invalid("endsAt", "BAD_WINDOW", "The window must end after it starts");
        }
        if (input.promotionId() != null && kind != ContentKind.PROMO_CARD) {
            throw invalid("promotionId", "NOT_FOR_KIND", "Only a promo card is bound to a promotion");
        }
        if (input.locationId() != null && kind != ContentKind.VACANCY) {
            throw invalid("locationId", "NOT_FOR_KIND", "Only a vacancy names a branch");
        }
        List<String> channels = new ArrayList<>(new TreeSet<>(normalizeChannels(input.channelCodes())));

        Set<UUID> promotionIds = new TreeSet<>(validated.facts().promotionIds());
        if (input.promotionId() != null) {
            promotionIds.add(input.promotionId());
        }
        if (!promotionIds.isEmpty()) {
            Set<UUID> known = promotions.existing(tenantId, brandId, promotionIds);
            for (UUID promotion : promotionIds) {
                if (!known.contains(promotion)) {
                    throw gate(new FieldProblem(
                            "promotionId",
                            Codes.PROMOTION_NOT_FOUND,
                            "Promotion " + promotion + " does not exist for this brand"));
                }
            }
        }
        if (input.locationId() != null) {
            boolean known = branches.activeBranches(tenantId, brandId).stream()
                    .anyMatch(branch -> branch.locationId().equals(input.locationId()));
            if (!known) {
                throw invalid("locationId", "NOT_FOUND", "No such active branch in this brand");
            }
        }
        return new Prepared(
                validated.document(),
                validated.facts(),
                channels,
                input.startsAt(),
                input.endsAt(),
                input.promotionId(),
                input.locationId());
    }

    private static List<String> normalizeChannels(@Nullable List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        if (codes.size() > MAX_CHANNELS) {
            throw invalid("channelCodes", "TOO_MANY", "At most " + MAX_CHANNELS + " channels");
        }
        List<String> normalized = new ArrayList<>();
        for (String code : codes) {
            String stripped = code == null ? "" : code.strip();
            if (stripped.isEmpty() || stripped.length() > 32) {
                throw invalid("channelCodes", "BAD_CHANNEL", "A channel code is 1 to 32 characters");
            }
            normalized.add(stripped);
        }
        return normalized;
    }

    private DraftRow draftRow(UUID itemId, @Nullable Integer basedOn, Prepared prepared, String actor, Instant now) {
        return new DraftRow(
                itemId,
                basedOn,
                viewer.write(prepared.document()),
                prepared.channelCodes(),
                prepared.startsAt(),
                prepared.endsAt(),
                prepared.promotionId(),
                prepared.locationId(),
                actor,
                now,
                1);
    }

    private String displayTitle(UUID tenantId, UUID brandId, Prepared prepared) {
        return titleOf(tenantId, brandId, prepared.document());
    }

    /** The brand-default-locale title, for lists; the first language there is text in otherwise. */
    private String titleOf(UUID tenantId, UUID brandId, JsonNode document) {
        String defaultLocale = environment.locales(tenantId, brandId).defaultLocale();
        JsonNode texts = document.path("texts");
        String title = texts.path(defaultLocale).path("title").asString("");
        if (title.isEmpty()) {
            for (var entry : texts.properties()) {
                String candidate = entry.getValue().path("title").asString("");
                if (!candidate.isEmpty()) {
                    title = candidate;
                    break;
                }
            }
        }
        return title.length() > 200 ? title.substring(0, 200) : title;
    }

    private @Nullable ContentPlacement resolvePlacement(ContentKind kind, @Nullable ContentPlacement requested) {
        if (kind.editorial()) {
            if (requested != null) {
                throw invalid("placement", "NOT_FOR_KIND", kind + " is not placed on the home screen");
            }
            return null;
        }
        if (requested == null) {
            return kind.defaultPlacement()
                    .orElseThrow(() -> invalid("placement", "REQUIRED", kind + " can sit in " + kind.placements()));
        }
        if (!kind.placements().contains(requested)) {
            throw invalid("placement", "NOT_FOR_KIND", kind + " cannot sit in " + requested);
        }
        return requested;
    }

    /**
     * An editorial kind exists for a brand only when the entitlement and the brand's policy both say
     * yes (ADR 0157, Decision 7). No entitlement is a 403 {@code ENTITLEMENT_REQUIRED}; the
     * entitlement without the tenant's choice is a 422.
     */
    void requireKindEnabled(UUID tenantId, UUID brandId, ContentKind kind) {
        if (!kind.editorial()) {
            return;
        }
        environment.requireEntitled(tenantId, ContentEntitlements.EDITORIAL_ENABLED);
        var policy = environment.editorialPolicy(tenantId, brandId);
        boolean on =
                switch (kind) {
                    case NEWS -> policy.newsOn();
                    case GALLERY_ALBUM -> policy.galleryOn();
                    case RECIPE -> policy.recipesOn();
                    case VACANCY -> policy.vacanciesOn();
                    default -> true;
                };
        if (!on) {
            throw gate(new FieldProblem(
                    "kind",
                    Codes.KIND_NOT_ENABLED,
                    "This brand has not switched " + kind.editorialFeature().orElse("this kind") + " on"));
        }
    }

    private void requireBrand(UUID tenantId, UUID brandId) {
        if (!environment.brand().brandExists(tenantId, brandId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such brand");
        }
    }

    private ItemRow lock(UUID tenantId, UUID brandId, UUID itemId, long expectedVersion) {
        ItemRow item = store.lockItem(tenantId, brandId, itemId).orElseThrow(ContentAuthoringService::noSuchItem);
        AggregateVersion.requireMatch(expectedVersion, item.version());
        return item;
    }

    private static void requireNotArchived(ItemRow item) {
        if (item.state() == ItemState.ARCHIVED) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This item is archived");
        }
    }

    private void bump(ItemRow item, @Nullable String internalName, @Nullable String displayTitle, Instant now) {
        if (!store.updateItem(
                item.tenantId(),
                item.brandId(),
                item.id(),
                item.version(),
                item.state(),
                item.liveRevisionNo(),
                internalName,
                displayTitle,
                now)) {
            throw ApiException.staleVersion(item.version(), item.version() + 1);
        }
    }

    static ApiException noSuchItem() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such content item");
    }

    static ApiException invalid(String field, String code, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                message,
                Map.of("errors", List.of(new ApiProblem.FieldError(field, code, message))));
    }

    static ApiException badDocument(List<FieldProblem> problems) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "The document is not valid for this kind",
                Map.of(
                        "errors",
                        problems.stream()
                                .map(p -> new ApiProblem.FieldError(
                                        "document" + (p.field().isEmpty() ? "" : "." + p.field()),
                                        p.code(),
                                        p.message()))
                                .toList()));
    }

    /** A 422 carrying the codes of ADR 0157's closed list. */
    static ApiException gate(FieldProblem... problems) {
        return new ApiException(
                ErrorCode.UNPROCESSABLE_STATE,
                "The content cannot be published as it stands",
                Map.of(
                        "errors",
                        java.util.Arrays.stream(problems)
                                .map(p -> new ApiProblem.FieldError(p.field(), p.code(), p.message()))
                                .toList()));
    }
}
