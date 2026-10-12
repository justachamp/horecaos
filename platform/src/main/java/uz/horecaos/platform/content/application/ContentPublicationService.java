package uz.horecaos.platform.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.content.api.ContentItemPublished;
import uz.horecaos.platform.content.api.ContentItemWithdrawn;
import uz.horecaos.platform.content.application.ContentGates.Mode;
import uz.horecaos.platform.content.application.ContentGates.Subject;
import uz.horecaos.platform.content.application.ContentViews.ItemView;
import uz.horecaos.platform.content.domain.ContentDocuments;
import uz.horecaos.platform.content.domain.ContentHash;
import uz.horecaos.platform.content.domain.DocumentFacts.AssetUse;
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
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Putting content in front of customers, taking it down, and archiving it (ADR 0157, Decision 2 and
 * Publication gates).
 *
 * <p>Publish is one transaction: the item is locked (the serialisation point of two concurrent
 * publishes -- exactly one wins and the other is told the item has moved), the draft is checked
 * against the gates, written as the next revision with its {@code content_hash} and its
 * {@code revision_media} rows, the live pointer moves, and the audit fact and the outbox row commit
 * with it. Nothing here schedules anything: a window is a pair of instants on the revision that
 * every read compares with the clock.
 *
 * <p>The audit change document names the revision, the languages, the window, the channels and the
 * promotion. <strong>The text stays out of the history</strong>, as {@code channel.page.published}
 * keeps it out: the revision row already holds it, and an audit row is the wrong place for a second
 * copy.
 */
@Service
public class ContentPublicationService {

    private final JdbcContentStore store;
    private final ContentEnvironment environment;
    private final ContentItemViewer viewer;
    private final ContentGates gates;
    private final ContentAuthoringService authoring;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ContentPublicationService(
            JdbcContentStore store,
            ContentEnvironment environment,
            ContentItemViewer viewer,
            ContentGates gates,
            ContentAuthoringService authoring,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.store = store;
        this.environment = environment;
        this.viewer = viewer;
        this.gates = gates;
        this.authoring = authoring;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ============================================================ publish

    @Transactional
    public ItemView publish(
            UUID tenantId, UUID brandId, String actor, UUID itemId, long expectedVersion, @Nullable String reason) {
        // The placement and live-video limits count other items, which this item's row lock does not
        // cover; serialize the brand's publications before reading either (ADR 0157, Publication gates).
        store.lockPublications(tenantId, brandId);
        ItemRow item = store.lockItem(tenantId, brandId, itemId).orElseThrow(ContentAuthoringService::noSuchItem);
        AggregateVersion.requireMatch(expectedVersion, item.version());
        if (item.state() == ItemState.ARCHIVED) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This item is archived");
        }
        DraftRow draft = store.findDraft(tenantId, brandId, itemId)
                .orElseThrow(() -> ContentAuthoringService.gate(
                        new FieldProblem("draft", Codes.NOTHING_TO_PUBLISH, "There is no draft to publish")));

        JsonNode stored = viewer.parse(draft.documentJson());
        ContentDocuments.Validated validated;
        try {
            validated = ContentDocuments.validate(item.kind(), stored);
        } catch (DocumentProblems.InvalidDocumentException invalid) {
            throw ContentAuthoringService.badDocument(invalid.problems());
        }
        authoring.requireKindEnabled(tenantId, brandId, item.kind());

        String hash = ContentHash.of(
                validated.document(),
                draft.channelCodes(),
                draft.startsAt(),
                draft.endsAt(),
                draft.promotionId(),
                draft.locationId());
        RevisionRow previous = item.liveRevisionNo() == null
                ? null
                : store.findRevision(tenantId, brandId, itemId, item.liveRevisionNo())
                        .orElse(null);
        if (previous != null && previous.contentHash().equals(hash)) {
            // "Nothing" includes the schedule, the channel set, the promotion and the location, not
            // only the document: extending a banner's end date is an ordinary publish.
            throw ContentAuthoringService.gate(
                    new FieldProblem("draft", Codes.NOTHING_TO_PUBLISH, "The draft is identical to the live revision"));
        }

        Instant now = clock.instant();
        Subject subject = new Subject(
                itemId,
                item.kind(),
                item.placement(),
                item.state(),
                validated.document(),
                validated.facts(),
                draft.channelCodes(),
                draft.startsAt(),
                draft.endsAt(),
                draft.promotionId(),
                draft.locationId());
        List<FieldProblem> problems = gates.evaluate(tenantId, brandId, List.of(subject), Mode.PUBLISH, now)
                .getOrDefault(itemId, List.of());
        if (!problems.isEmpty()) {
            throw ContentAuthoringService.gate(problems.toArray(FieldProblem[]::new));
        }

        int revisionNo = store.latestRevisionNo(tenantId, brandId, itemId) + 1;
        store.insertRevision(
                tenantId,
                brandId,
                new RevisionRow(
                        itemId,
                        revisionNo,
                        viewer.write(validated.document()),
                        draft.channelCodes(),
                        draft.startsAt(),
                        draft.endsAt(),
                        draft.promotionId(),
                        draft.locationId(),
                        hash,
                        actor,
                        now));
        recordMedia(tenantId, itemId, revisionNo, validated.facts().assets());

        String title = displayTitle(tenantId, brandId, validated.document());
        if (!store.updateItem(
                tenantId, brandId, itemId, item.version(), ItemState.PUBLISHED, revisionNo, null, title, now)) {
            throw ApiException.staleVersion(expectedVersion, item.version() + 1);
        }
        // The draft stays, now based on what is live, so the editor always has something to edit and
        // "unpublished changes" is the draft's hash against the live revision's.
        store.upsertDraft(
                tenantId,
                brandId,
                new DraftRow(
                        itemId,
                        revisionNo,
                        draft.documentJson(),
                        draft.channelCodes(),
                        draft.startsAt(),
                        draft.endsAt(),
                        draft.promotionId(),
                        draft.locationId(),
                        draft.updatedBy(),
                        draft.updatedAt(),
                        draft.version()));

        Map<String, Object> after = facts(
                item,
                revisionNo,
                hash,
                validated.facts().locales(),
                draft.channelCodes(),
                draft.startsAt(),
                draft.endsAt(),
                draft.promotionId());
        // A first publication has no prior state to diff against and is a diff against nothing.
        Map<String, Object> before = previous == null ? Map.of() : facts(item, previous);
        audit.record(auditFact(
                        "content.item.published",
                        AuditClass.BUSINESS,
                        tenantId,
                        brandId,
                        itemId,
                        actor,
                        Capability.CONTENT_PUBLISH,
                        now,
                        reasonOr(reason, "Published"))
                .changed(ChangeDocuments.diff(before, after))
                .build());
        events.publishEvent(new ContentItemPublished(
                Ids.newId(),
                tenantId,
                brandId,
                itemId,
                item.kind().name(),
                item.placement() == null ? null : item.placement().name(),
                revisionNo,
                draft.startsAt(),
                draft.endsAt(),
                draft.channelCodes(),
                now));
        return viewer.view(store.findItem(tenantId, brandId, itemId).orElseThrow());
    }

    // ============================================================ withdraw

    @Transactional
    public ItemView withdraw(
            UUID tenantId, UUID brandId, String actor, UUID itemId, long expectedVersion, @Nullable String reason) {
        ItemRow item = store.lockItem(tenantId, brandId, itemId).orElseThrow(ContentAuthoringService::noSuchItem);
        AggregateVersion.requireMatch(expectedVersion, item.version());
        if (item.state() != ItemState.PUBLISHED) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Only a published item can be withdrawn");
        }
        takeDown(
                item,
                ItemState.WITHDRAWN,
                "content.item.withdrawn",
                ContentItemWithdrawn.Reason.WITHDRAWN,
                actor,
                reasonOr(reason, "Withdrawn"));
        return viewer.view(store.findItem(tenantId, brandId, itemId).orElseThrow());
    }

    // ============================================================ archive

    /** Retires an item from lists; withdrawn first if it was live. A platform administrator holds the same capability and may do this as a takedown. */
    @Transactional
    public ItemView archive(
            UUID tenantId, UUID brandId, String actor, UUID itemId, long expectedVersion, @Nullable String reason) {
        ItemRow item = store.lockItem(tenantId, brandId, itemId).orElseThrow(ContentAuthoringService::noSuchItem);
        AggregateVersion.requireMatch(expectedVersion, item.version());
        if (item.state() == ItemState.ARCHIVED) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This item is already archived");
        }
        if (item.state() == ItemState.PUBLISHED) {
            takeDown(
                    item,
                    ItemState.ARCHIVED,
                    "content.item.archived",
                    ContentItemWithdrawn.Reason.ARCHIVED,
                    actor,
                    reasonOr(reason, "Archived"));
        } else {
            Instant now = clock.instant();
            if (!store.updateItem(
                    item.tenantId(),
                    item.brandId(),
                    item.id(),
                    item.version(),
                    ItemState.ARCHIVED,
                    null,
                    null,
                    null,
                    now)) {
                throw ApiException.staleVersion(item.version(), item.version() + 1);
            }
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("state", item.state().name());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("state", ItemState.ARCHIVED.name());
            audit.record(auditFact(
                            "content.item.archived",
                            AuditClass.BUSINESS,
                            tenantId,
                            brandId,
                            itemId,
                            actor,
                            Capability.CONTENT_PUBLISH,
                            now,
                            reasonOr(reason, "Archived"))
                    .changed(ChangeDocuments.diff(before, after))
                    .build());
        }
        return viewer.view(store.findItem(tenantId, brandId, itemId).orElseThrow());
    }

    private void takeDown(
            ItemRow item,
            ItemState to,
            String action,
            ContentItemWithdrawn.Reason reason,
            String actor,
            String because) {
        Instant now = clock.instant();
        if (!store.updateItem(item.tenantId(), item.brandId(), item.id(), item.version(), to, null, null, null, now)) {
            throw ApiException.staleVersion(item.version(), item.version() + 1);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("state", item.state().name());
        before.put("revisionNo", item.liveRevisionNo());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", to.name());
        after.put("revisionNo", null);
        audit.record(auditFact(
                        action,
                        AuditClass.BUSINESS,
                        item.tenantId(),
                        item.brandId(),
                        item.id(),
                        actor,
                        Capability.CONTENT_PUBLISH,
                        now,
                        because)
                .changed(ChangeDocuments.diff(before, after))
                .build());
        events.publishEvent(new ContentItemWithdrawn(
                Ids.newId(),
                item.tenantId(),
                item.brandId(),
                item.id(),
                item.kind().name(),
                item.placement() == null ? null : item.placement().name(),
                reason,
                now));
    }

    // ============================================================ helpers

    private void recordMedia(UUID tenantId, UUID itemId, int revisionNo, List<AssetUse> uses) {
        Set<String> seen = new LinkedHashSet<>();
        for (AssetUse use : uses) {
            if (seen.add(use.assetId() + ":" + use.role())) {
                store.insertRevisionMedia(tenantId, itemId, revisionNo, use.assetId(), use.role(), use.position());
            }
        }
    }

    private String displayTitle(UUID tenantId, UUID brandId, JsonNode document) {
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

    /**
     * What a publication changed, without the text: the revision, the kind, the languages, the
     * window, the channels, the promotion and the hash.
     */
    private static Map<String, Object> facts(
            ItemRow item,
            int revisionNo,
            String hash,
            Set<String> locales,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("revisionNo", revisionNo);
        facts.put("kind", item.kind().name());
        facts.put(
                "placement", item.placement() == null ? null : item.placement().name());
        facts.put("locales", String.join(",", new java.util.TreeSet<>(locales)));
        facts.put("startsAt", startsAt == null ? null : startsAt.toString());
        facts.put("endsAt", endsAt == null ? null : endsAt.toString());
        facts.put("channelCodes", String.join(",", new java.util.TreeSet<>(channelCodes)));
        facts.put("promotionId", promotionId == null ? null : promotionId.toString());
        facts.put("contentHash", hash);
        return facts;
    }

    private Map<String, Object> facts(ItemRow item, RevisionRow revision) {
        Set<String> locales = new java.util.TreeSet<>();
        for (var entry : viewer.parse(revision.documentJson()).path("texts").properties()) {
            locales.add(entry.getKey());
        }
        return facts(
                item,
                revision.revisionNo(),
                revision.contentHash(),
                locales,
                revision.channelCodes(),
                revision.startsAt(),
                revision.endsAt(),
                revision.promotionId());
    }

    /**
     * What the author said, else a stated phrase: ADR 0027 requires a reason on the fact a user
     * produces, and publishing a banner is not worth a mandatory field. The text is the author's
     * and is never a customer's.
     */
    static String reasonOr(@Nullable String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.strip();
    }

    static AuditFact.Builder auditFact(
            String action,
            AuditClass auditClass,
            UUID tenantId,
            UUID brandId,
            UUID itemId,
            String actor,
            Capability capability,
            Instant now,
            String reason) {
        return AuditFact.of(action, auditClass)
                .by(ActorRef.user(actor, null))
                .because(reason)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("content.item", itemId)
                .usingCapability(capability.code())
                .correlatedBy(itemId.toString())
                .occurredAt(now);
    }

    /** For tests and callers that assemble a problem list of their own. */
    static List<FieldProblem> noProblems() {
        return new ArrayList<>();
    }
}
