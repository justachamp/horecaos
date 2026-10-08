package uz.horecaos.platform.reviews.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reviews.application.ReviewModerationService;
import uz.horecaos.platform.reviews.application.ReviewModerationService.Queue;
import uz.horecaos.platform.reviews.application.ReviewModerationService.QueueItem;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.domain.ModerationReason;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore.PublicationRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * §5.4 Reviews, the moderation tab (ADR 0159, Decisions 6 and 7): the comments customers agreed to show
 * publicly and are waiting for a moderator, and the decision to publish or hide one.
 *
 * <p>Moderation hides words and never numbers. A decision moves only the comment's publication state; it
 * can never change a rating or the average. Hiding needs one of five coded reasons and the form offers no
 * free text. Publishing -- approving a waiting comment, or restoring a hidden one -- gives no reason. A
 * comment the customer took back (or whose author was erased) cannot be restored by anyone.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/reviews")
@Tag(name = "Review moderation", description = "Publishing and hiding the comments customers agreed to show publicly")
public class OperationsReviewModerationController {

    private final ReviewModerationService moderation;
    private final CurrentActor currentActor;

    public OperationsReviewModerationController(ReviewModerationService moderation, CurrentActor currentActor) {
        this.moderation = moderation;
        this.currentActor = currentActor;
    }

    @GetMapping("/moderation-queue")
    @RequiresCapability(value = Capability.REVIEW_MODERATE, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's comments in a publication state, longest-waiting first",
            description = "state defaults to PENDING, the comments waiting for a decision. Each entry "
                    + "carries the comment (decrypted for this screen), its rating and tags, and the "
                    + "version to decide against. counts says how many sit in each state, so the tab "
                    + "can show the PENDING number. Cursor-paginated per ADR 0031. Only comments the "
                    + "customer agreed to share are ever queued.")
    public QueueResponse queue(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable CommentState state,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        CommentState wanted = state == null ? CommentState.PENDING : state;
        if (wanted == CommentState.NOT_SHARED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A comment that was never shared is not in the queue");
        }
        Queue queue = moderation.queue(tenantId, brandId, wanted, cursor, pageSize);
        return QueueResponse.of(queue);
    }

    @PostMapping("/{reviewId}/moderation-decisions")
    @RequiresCapability(value = Capability.REVIEW_MODERATE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish or hide a comment",
            description = "to is PUBLISHED or HIDDEN. The allowed moves are PENDING to PUBLISHED, "
                    + "PENDING to HIDDEN, PUBLISHED to HIDDEN and HIDDEN to PUBLISHED; anything else, "
                    + "or restoring a comment the customer took back, is UNPROCESSABLE_STATE. Hiding "
                    + "needs reasonCode: ABUSIVE_LANGUAGE, PERSONAL_DATA_OF_ANOTHER, NAMES_AN_INDIVIDUAL, "
                    + "SPAM_OR_UNRELATED or LEGAL_REQUEST; publishing gives none. Needs If-Match (or "
                    + "expectedVersion) with the version the queue returned. Changes the publication "
                    + "state only: never the rating, never the average. The brand's public list is "
                    + "evicted in this request. The response carries a state, a code and a version, "
                    + "never the comment.")
    public ResponseEntity<DecisionResponse> decide(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody DecisionRequest body,
            HttpServletRequest request) {
        int expected = ExpectedVersions.resolve(request, body.expectedVersion());
        ModerationReason reason = null;
        if (body.reasonCode() != null) {
            reason = ModerationReason.parse(body.reasonCode())
                    .orElseThrow(() ->
                            new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown reason code: " + body.reasonCode()));
        }
        PublicationRow row = moderation.decide(
                tenantId,
                brandId,
                reviewId,
                body.to(),
                reason,
                expected,
                currentActor.get().subject());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(row.version())).body(DecisionResponse.of(row));
    }

    // ------------------------------------------------------------- wire shapes

    /**
     * @param reasonCode a string and not the enum so an unknown code is a clean VALIDATION_FAILED, and a
     *                   system reason (CUSTOMER_WITHDREW) is refused by name
     */
    public record DecisionRequest(
            @NotNull CommentState to,
            @Nullable String reasonCode,
            @Nullable Integer expectedVersion) {}

    public record DecisionResponse(
            UUID reviewId, CommentState state, @Nullable String reasonCode, int version) {

        static DecisionResponse of(PublicationRow row) {
            return new DecisionResponse(row.reviewId(), row.commentState(), row.stateReasonCode(), row.version());
        }
    }

    public record QueueTag(UUID id, String code, String category, String sentiment, Map<String, String> labels) {}

    public record QueueEntry(
            UUID reviewId,
            UUID locationId,
            int rating,
            @Nullable String comment,
            Instant submittedAt,
            CommentState state,
            @Nullable String reasonCode,
            int version,
            Instant updatedAt,
            List<QueueTag> tags) {

        static QueueEntry of(QueueItem item) {
            return new QueueEntry(
                    item.reviewId(),
                    item.locationId(),
                    item.rating(),
                    item.comment(),
                    item.submittedAt(),
                    item.state(),
                    item.reasonCode(),
                    item.version(),
                    item.updatedAt(),
                    item.tags().stream()
                            .map(tag -> new QueueTag(
                                    tag.row().id(),
                                    tag.row().code(),
                                    tag.row().category().name(),
                                    tag.row().sentiment().name(),
                                    tag.labels()))
                            .toList());
        }
    }

    public record QueueResponse(
            List<QueueEntry> items, @Nullable String nextCursor, Map<CommentState, Long> counts) {

        static QueueResponse of(Queue queue) {
            return new QueueResponse(
                    queue.items().stream().map(QueueEntry::of).toList(), queue.nextCursor(), queue.counts());
        }
    }
}
