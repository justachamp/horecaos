package uz.horecaos.platform.reviews.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.reviews.application.FeedbackTagService.TagView;
import uz.horecaos.platform.reviews.domain.PublicVisibility;
import uz.horecaos.platform.reviews.domain.TagCategory;
import uz.horecaos.platform.reviews.domain.TagSentiment;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore.PublicRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.tenancy.api.BranchDirectory;

/**
 * What a brand's public page may show (ADR 0159, Decision 5): a summary and a list, both anonymous, both
 * off until the tenant switches visibility on, and both read through the cache of {@link
 * PublicReviewCache}.
 *
 * <p>Four independent gates stand between a customer's words and a stranger: a tenant switch
 * ({@code feedback.public_visibility}), a minimum count ({@code feedback.public_minimum_reviews}), the
 * customer's tick, and a moderator's approval. The number is never edited: the average counts
 * <em>every</em> review of the brand -- consented or not, comment hidden or not -- because a rating is
 * not words and a number that leaves out the unwelcome ones is not a rating.
 *
 * <p>The list shows only published comments, newest first, with the rating, the tags (labels and icons),
 * the date to the day and the branch name. No name, no customer id, no order number, no time of day: the
 * response carries no review id either, because a version-7 id is a timestamp to the millisecond. A
 * tenant cannot filter the list by rating, pin, reorder or feature a comment, or reply to one.
 *
 * <p>The decrypted comment exists only in the response and in the brand's list cache entry (at most
 * sixty seconds); every path that stops a comment being public evicts that entry in the same request.
 */
@Service
public class PublicReviewService {

    /** An anonymous caller reads at most this far down a brand's comments. */
    public static final int MAXIMUM_OFFSET = 500;

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAXIMUM_LIMIT = 50;

    private final ReviewsSettings settings;
    private final JdbcReviewStore reviews;
    private final JdbcPublicationStore publications;
    private final ReviewCommentVault vault;
    private final FeedbackTagService tags;
    private final BranchDirectory branches;
    private final PublicReviewCache cache;

    public PublicReviewService(
            ReviewsSettings settings,
            JdbcReviewStore reviews,
            JdbcPublicationStore publications,
            ReviewCommentVault vault,
            FeedbackTagService tags,
            BranchDirectory branches,
            PublicReviewCache cache) {
        this.settings = settings;
        this.reviews = reviews;
        this.publications = publications;
        this.vault = vault;
        this.tags = tags;
        this.branches = branches;
        this.cache = cache;
    }

    /**
     * The brand's summary, or nothing but a flag.
     *
     * @param ready        false until the brand has the minimum count of reviews; then every figure is null
     * @param count        every review of the brand
     * @param average      the mean rating to one decimal, half-up: the sum of ratings over the count, which
     *                     is exactly {@code reviews.average_rating}
     * @param distribution five counts, the first for one star
     */
    public record PublicSummary(
            PublicVisibility visibility,
            boolean ready,
            @Nullable Long count,
            @Nullable BigDecimal average,
            @Nullable List<Long> distribution) {

        static PublicSummary hidden(PublicVisibility visibility) {
            return new PublicSummary(visibility, false, null, null, null);
        }
    }

    /** A tag on a public comment: its wording in every locale and its icon, nothing about who tapped it. */
    public record PublicTag(
            String code,
            TagCategory category,
            TagSentiment sentiment,
            Map<String, String> labels,
            @Nullable String iconUrl) {}

    /**
     * One published comment. The rating is a number from a completed order, so it is declared public
     * against the name heuristic that would read "rating" as something else; the comment is the one
     * personal field, and it is present only because its author said yes and a moderator (or the brand's
     * own setting) said yes.
     *
     * @param date       the day it was written in the branch's time zone; never a time
     * @param branchName the branch's published name, or null if the branch is no longer active
     */
    public record PublicComment(
            @Classified(value = DataClass.PUBLIC, reason = "a rating comes from a completed order and is not words")
            int rating,

            @Classified(DataClass.PERSONAL) String comment,
            List<PublicTag> tags,
            LocalDate date,
            @Nullable String branchName) {}

    /** One window of the list; {@code nextCursor} is opaque and null at the end. */
    public record PublicList(
            PublicVisibility visibility,
            List<PublicComment> items,
            @Nullable String nextCursor) {}

    /** The raw figures the summary cache holds: counts and nothing else. */
    record Stats(long[] distribution) {}

    @Transactional(readOnly = true)
    public PublicSummary summary(UUID tenantId, UUID brandId) {
        PublicVisibility visibility = settings.publicVisibility(tenantId, brandId);
        if (!visibility.showsSummary()) {
            return PublicSummary.hidden(visibility);
        }
        Stats stats =
                cache.summary(tenantId, brandId, () -> new Stats(reviews.distributionForBrand(tenantId, brandId)));
        long count = 0;
        long sum = 0;
        for (int i = 0; i < stats.distribution().length; i++) {
            count += stats.distribution()[i];
            sum += stats.distribution()[i] * (i + 1L);
        }
        if (count == 0 || count < settings.publicMinimumReviews(tenantId, brandId)) {
            return PublicSummary.hidden(visibility);
        }
        List<Long> distribution = new ArrayList<>();
        for (long bar : stats.distribution()) {
            distribution.add(bar);
        }
        return new PublicSummary(visibility, true, count, averageOf(sum, count), List.copyOf(distribution));
    }

    /**
     * The mean of the ratings to one decimal, half-up -- the single definition the live summary and the
     * {@code reviews.average_rating} metric share, so the two agree by construction and a test pins it.
     */
    public static BigDecimal averageOf(long sumOfRatings, long count) {
        if (count == 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(sumOfRatings).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public PublicList list(UUID tenantId, UUID brandId, @Nullable String cursor, @Nullable Integer limit) {
        PublicVisibility visibility = settings.publicVisibility(tenantId, brandId);
        if (!visibility.showsComments()) {
            return new PublicList(visibility, List.of(), null);
        }
        int offset = offsetOf(cursor);
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAXIMUM_LIMIT));
        return cache.listPage(
                tenantId,
                brandId,
                offset + ":" + pageSize,
                () -> load(tenantId, brandId, visibility, offset, pageSize));
    }

    private PublicList load(UUID tenantId, UUID brandId, PublicVisibility visibility, int offset, int pageSize) {
        List<PublicRow> rows = publications.publishedComments(tenantId, brandId, offset, pageSize);
        if (rows.isEmpty()) {
            return new PublicList(visibility, List.of(), null);
        }
        Map<UUID, List<UUID>> tagIds = reviews.tagIdsFor(
                tenantId, rows.stream().map(PublicRow::reviewId).toList());
        Map<UUID, TagView> views = tags.viewsOf(
                tenantId, tagIds.values().stream().flatMap(Collection::stream).toList());
        Map<UUID, BranchDirectory.Branch> byLocation = new LinkedHashMap<>();
        branches.activeBranches(tenantId, brandId).forEach(branch -> byLocation.put(branch.locationId(), branch));

        List<PublicComment> items = new ArrayList<>();
        for (PublicRow row : rows) {
            BranchDirectory.Branch branch = byLocation.get(row.locationId());
            List<PublicTag> publicTags = new ArrayList<>();
            for (UUID tagId : tagIds.getOrDefault(row.reviewId(), List.of())) {
                TagView view = views.get(tagId);
                if (view != null) {
                    publicTags.add(new PublicTag(
                            view.row().code(),
                            view.row().category(),
                            view.row().sentiment(),
                            view.labels(),
                            view.row().iconAssetId() == null
                                    ? null
                                    : iconUrl(tenantId, view.row().iconAssetId())));
                }
            }
            items.add(new PublicComment(
                    row.rating(),
                    vault.revealForPublicDisplay(
                            tenantId, row.reviewId(), row.commentState(), row.publicConsent(), row.commentProtected()),
                    List.copyOf(publicTags),
                    row.submittedAt().atZone(zoneOf(branch)).toLocalDate(),
                    branch == null ? null : branch.name()));
        }
        String next = rows.size() < pageSize ? null : Integer.toString(offset + rows.size());
        return new PublicList(visibility, List.copyOf(items), next);
    }

    private static int offsetOf(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor);
            return Math.max(0, Math.min(offset, MAXIMUM_OFFSET));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static ZoneId zoneOf(BranchDirectory.@Nullable Branch branch) {
        if (branch == null) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(branch.timezone());
        } catch (RuntimeException unknownZone) {
            return ZoneOffset.UTC;
        }
    }

    /** The storefront media route a tag icon is served from: a redirect for a PUBLIC, displayable asset only. */
    static String iconUrl(UUID tenantId, UUID assetId) {
        return "/api/v1/storefront/tenants/%s/media/%s".formatted(tenantId, assetId);
    }
}
