package uz.horecaos.platform.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.content.application.ContentGates.Mode;
import uz.horecaos.platform.content.application.ContentGates.Subject;
import uz.horecaos.platform.content.application.ContentViews.DraftView;
import uz.horecaos.platform.content.application.ContentViews.ItemSummary;
import uz.horecaos.platform.content.application.ContentViews.ItemView;
import uz.horecaos.platform.content.application.ContentViews.RevisionView;
import uz.horecaos.platform.content.domain.ContentDocuments;
import uz.horecaos.platform.content.domain.ContentHash;
import uz.horecaos.platform.content.domain.ContentVisibility;
import uz.horecaos.platform.content.domain.DocumentProblems;
import uz.horecaos.platform.content.domain.FieldProblem;
import uz.horecaos.platform.content.domain.ItemState;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.DraftRow;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.ItemRow;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.RevisionRow;

/**
 * Turns stored rows into what a screen shows (ADR 0157): an item with its draft, its live revision,
 * whether the two differ, its computed visibility and its problems.
 *
 * <p>Visibility is computed here, at the moment of the read, from the live revision's window and the
 * injected clock. It is never read from a column because no column holds it.
 */
@Component
public class ContentItemViewer {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcContentStore store;
    private final ContentGates gates;
    private final ObjectMapper json;
    private final Clock clock;

    public ContentItemViewer(JdbcContentStore store, ContentGates gates, ObjectMapper json, Clock clock) {
        this.store = store;
        this.gates = gates;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- JSON

    public JsonNode parse(String text) {
        return json.readTree(text);
    }

    public Map<String, Object> toMap(String text) {
        return json.readValue(text, MAP);
    }

    /** An author's document, as the tree the grammar reads. */
    public JsonNode toTree(Map<String, Object> document) {
        return json.valueToTree(document);
    }

    public Map<String, Object> toMap(JsonNode node) {
        return json.convertValue(node, MAP);
    }

    public String write(JsonNode node) {
        return json.writeValueAsString(node);
    }

    // ---------------------------------------------------------------- item

    /** One item with its draft, its live revision and its problems (draft-save warnings). */
    public ItemView view(ItemRow item) {
        DraftRow draft =
                store.findDraft(item.tenantId(), item.brandId(), item.id()).orElse(null);
        RevisionRow live = item.liveRevisionNo() == null
                ? null
                : store.findRevision(item.tenantId(), item.brandId(), item.id(), item.liveRevisionNo())
                        .orElse(null);
        List<FieldProblem> problems = draft == null ? List.of() : problemsOfDraft(item, draft);
        return build(item, draft, live, problems);
    }

    ItemView build(ItemRow item, @Nullable DraftRow draft, @Nullable RevisionRow live, List<FieldProblem> problems) {
        return new ItemView(
                item.id(),
                item.kind(),
                item.placement(),
                item.internalName(),
                item.displayTitle(),
                item.slug(),
                item.state(),
                visibility(item, live),
                item.sortKey(),
                item.version(),
                item.createdBy(),
                item.createdAt(),
                item.updatedAt(),
                live == null ? null : revisionView(live),
                draft == null ? null : draftView(draft),
                hasUnpublishedChanges(item, draft, live),
                problems);
    }

    /** The rows of the list, with problems computed in one batch for the whole page. */
    public List<ItemSummary> summaries(UUID tenantId, UUID brandId, List<ItemRow> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = items.stream().map(ItemRow::id).toList();
        Map<UUID, DraftRow> drafts = new HashMap<>();
        store.findDrafts(tenantId, brandId, ids).forEach(d -> drafts.put(d.itemId(), d));
        Map<UUID, RevisionRow> lives = new HashMap<>();
        store.findLiveRevisions(tenantId, brandId, ids).forEach(r -> lives.put(r.itemId(), r));

        List<Subject> subjects = new ArrayList<>();
        for (ItemRow item : items) {
            DraftRow draft = drafts.get(item.id());
            RevisionRow live = lives.get(item.id());
            // What is wrong with the thing the author is about to publish, or with what is already live.
            if (draft != null && item.state() != ItemState.ARCHIVED) {
                subjectOfDraft(item, draft).ifPresent(subjects::add);
            } else if (live != null) {
                subjectOfRevision(item, live).ifPresent(subjects::add);
            }
        }
        Map<UUID, List<FieldProblem>> problems = subjects.isEmpty()
                ? Map.of()
                : gates.evaluate(tenantId, brandId, subjects, Mode.DRAFT, clock.instant());

        List<ItemSummary> rows = new ArrayList<>();
        for (ItemRow item : items) {
            DraftRow draft = drafts.get(item.id());
            RevisionRow live = lives.get(item.id());
            @Nullable RevisionRow shown = live;
            rows.add(new ItemSummary(
                    item.id(),
                    item.kind(),
                    item.placement(),
                    item.internalName(),
                    item.displayTitle(),
                    item.slug(),
                    item.state(),
                    visibility(item, live),
                    item.sortKey(),
                    item.version(),
                    item.liveRevisionNo(),
                    shown != null ? shown.startsAt() : draft != null ? draft.startsAt() : null,
                    shown != null ? shown.endsAt() : draft != null ? draft.endsAt() : null,
                    shown != null ? shown.channelCodes() : draft != null ? draft.channelCodes() : List.of(),
                    hasUnpublishedChanges(item, draft, live),
                    item.updatedAt(),
                    problems.getOrDefault(item.id(), List.of())));
        }
        return rows;
    }

    // ------------------------------------------------------------ problems

    List<FieldProblem> problemsOfDraft(ItemRow item, DraftRow draft) {
        if (item.state() == ItemState.ARCHIVED) {
            return List.of();
        }
        return subjectOfDraft(item, draft)
                .map(subject -> gates.evaluate(
                                item.tenantId(), item.brandId(), List.of(subject), Mode.DRAFT, clock.instant())
                        .getOrDefault(item.id(), List.of()))
                .orElse(List.of());
    }

    java.util.Optional<Subject> subjectOfDraft(ItemRow item, DraftRow draft) {
        return subject(
                item,
                draft.documentJson(),
                draft.channelCodes(),
                draft.startsAt(),
                draft.endsAt(),
                draft.promotionId(),
                draft.locationId());
    }

    java.util.Optional<Subject> subjectOfRevision(ItemRow item, RevisionRow revision) {
        return subject(
                item,
                revision.documentJson(),
                revision.channelCodes(),
                revision.startsAt(),
                revision.endsAt(),
                revision.promotionId(),
                revision.locationId());
    }

    private java.util.Optional<Subject> subject(
            ItemRow item,
            String documentJson,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {
        try {
            ContentDocuments.Validated validated = ContentDocuments.validate(item.kind(), parse(documentJson));
            return java.util.Optional.of(new Subject(
                    item.id(),
                    item.kind(),
                    item.placement(),
                    item.state(),
                    validated.document(),
                    validated.facts(),
                    channelCodes,
                    startsAt,
                    endsAt,
                    promotionId,
                    locationId));
        } catch (DocumentProblems.InvalidDocumentException stale) {
            // A stored document that no longer parses under the current grammar cannot be checked
            // against anything; the editor will refuse to save it, which is how it gets fixed.
            return java.util.Optional.empty();
        }
    }

    // --------------------------------------------------------- derived facts

    /** The visibility of an item right now, computed from the live revision's window; null if nothing is live. */
    @Nullable
    ContentVisibility visibility(ItemRow item, @Nullable RevisionRow live) {
        if (item.state() != ItemState.PUBLISHED || live == null) {
            return null;
        }
        return ContentVisibility.of(live.startsAt(), live.endsAt(), clock.instant());
    }

    boolean hasUnpublishedChanges(ItemRow item, @Nullable DraftRow draft, @Nullable RevisionRow live) {
        if (draft == null) {
            return false;
        }
        if (item.state() == ItemState.DRAFT) {
            return true;
        }
        if (item.state() != ItemState.PUBLISHED || live == null) {
            return false;
        }
        return !hashOf(draft).equals(live.contentHash());
    }

    /** The hash a publication of this draft would store: of its six values, as the database holds them. */
    public String hashOf(DraftRow draft) {
        return ContentHash.of(
                parse(draft.documentJson()),
                draft.channelCodes(),
                draft.startsAt(),
                draft.endsAt(),
                draft.promotionId(),
                draft.locationId());
    }

    public RevisionView revisionView(RevisionRow revision) {
        return new RevisionView(
                revision.revisionNo(),
                revision.contentHash(),
                toMap(revision.documentJson()),
                revision.channelCodes(),
                revision.startsAt(),
                revision.endsAt(),
                revision.promotionId(),
                revision.locationId(),
                revision.publishedBy(),
                revision.publishedAt());
    }

    public DraftView draftView(DraftRow draft) {
        return new DraftView(
                toMap(draft.documentJson()),
                draft.channelCodes(),
                draft.startsAt(),
                draft.endsAt(),
                draft.promotionId(),
                draft.locationId(),
                draft.basedOnRevisionNo(),
                draft.updatedBy(),
                draft.updatedAt());
    }
}
