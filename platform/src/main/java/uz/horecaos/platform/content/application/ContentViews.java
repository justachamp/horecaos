package uz.horecaos.platform.content.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.domain.ContentPlacement;
import uz.horecaos.platform.content.domain.ContentVisibility;
import uz.horecaos.platform.content.domain.FieldProblem;
import uz.horecaos.platform.content.domain.ItemState;

/**
 * What the authoring services hand a controller (ADR 0157, Staff API).
 *
 * <p>Documents are plain maps, not JSON trees: the controller serialises them as the object the
 * console sent in, and the OpenAPI document describes them as an open object, which is what they
 * are (the grammar is the server's, per kind, and is not a schema a generated client could use).
 */
public final class ContentViews {

    private ContentViews() {}

    /** One item with everything a screen needs to edit it. {@code version} is the ETag. */
    public record ItemView(
            UUID id,
            ContentKind kind,
            @Nullable ContentPlacement placement,
            String internalName,
            String displayTitle,
            @Nullable String slug,
            ItemState state,
            @Nullable ContentVisibility visibility,
            int sortKey,
            int version,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            @Nullable RevisionView liveRevision,
            @Nullable DraftView draft,
            boolean hasUnpublishedChanges,
            List<FieldProblem> problems) {

        public ItemView {
            problems = List.copyOf(problems);
        }
    }

    /** The row of the console's list: no documents. */
    public record ItemSummary(
            UUID id,
            ContentKind kind,
            @Nullable ContentPlacement placement,
            String internalName,
            String displayTitle,
            @Nullable String slug,
            ItemState state,
            @Nullable ContentVisibility visibility,
            int sortKey,
            int version,
            @Nullable Integer liveRevisionNo,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            List<String> channelCodes,
            boolean hasUnpublishedChanges,
            Instant updatedAt,
            List<FieldProblem> problems) {

        public ItemSummary {
            channelCodes = List.copyOf(channelCodes);
            problems = List.copyOf(problems);
        }
    }

    /** An immutable revision with its document: what a customer may have been shown. */
    public record RevisionView(
            int revisionNo,
            String contentHash,
            Map<String, Object> document,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId,
            String publishedBy,
            Instant publishedAt) {

        public RevisionView {
            channelCodes = List.copyOf(channelCodes);
        }
    }

    /** A revision in a history list: no document. */
    public record RevisionSummary(
            int revisionNo,
            String contentHash,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            String publishedBy,
            Instant publishedAt,
            boolean live) {

        public RevisionSummary {
            channelCodes = List.copyOf(channelCodes);
        }
    }

    /** The one mutable copy. */
    public record DraftView(
            Map<String, Object> document,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId,
            @Nullable Integer basedOnRevisionNo,
            String updatedBy,
            Instant updatedAt) {

        public DraftView {
            channelCodes = List.copyOf(channelCodes);
        }
    }

    /** A page of the console's list; the token is set when the list is of one placement. */
    public record ItemPage(
            List<ItemSummary> items,
            @Nullable String nextCursor,
            @Nullable String orderToken) {

        public ItemPage {
            items = List.copyOf(items);
        }
    }
}
