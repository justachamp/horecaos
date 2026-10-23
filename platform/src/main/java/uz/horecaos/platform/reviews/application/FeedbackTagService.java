package uz.horecaos.platform.reviews.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.TenantImageCheck;
import uz.horecaos.platform.media.api.TenantImageCheck.Verdict;
import uz.horecaos.platform.reviews.domain.TagCategory;
import uz.horecaos.platform.reviews.domain.TagSentiment;
import uz.horecaos.platform.reviews.domain.TagStatus;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcFeedbackTagStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcFeedbackTagStore.TagRow;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The tenant's feedback tag library (ADR 0159, Decisions 2 and 3): which one-tap tags a customer is
 * offered, authored in Settings > Feedback.
 *
 * <p>One library per tenant, not one per brand. A tag has a stable {@code code}, a category and a
 * sentiment (both frozen once a review carries it), a label per locale, an optional icon, a sort order
 * and a status. It is <em>retired, never deleted</em>: a retired tag is no longer offered and stays on
 * every review that carries it. At most {@value #MAXIMUM_ACTIVE_TAGS} are active.
 *
 * <p>Rating 4-5 offers positive tags, 1-2 negative and 3 both; at most {@value #MAXIMUM_OFFERED_PER_SENTIMENT}
 * per sentiment are shown, in sort order; a review carries at most {@value #MAXIMUM_TAGS_PER_REVIEW}. The
 * server refuses a tag that is retired, belongs to another tenant or does not match the rating.
 *
 * <p>A tenant may import the starter pack once and edit it. The wording is the product's, written in
 * Russian, Uzbek (Latin) and English and never machine-translated.
 */
@Service
public class FeedbackTagService {

    public static final int MAXIMUM_ACTIVE_TAGS = 40;
    public static final int MAXIMUM_OFFERED_PER_SENTIMENT = 8;
    public static final int MAXIMUM_TAGS_PER_REVIEW = 5;

    /** A label fits {@code reviews.feedback_tag_translations.label}. */
    static final int MAXIMUM_LABEL_LENGTH = 40;

    /** An icon is 3:2 and at most one megabyte (ADR 0159, open input 9). */
    public static final int ICON_RATIO_WIDTH = 3;

    public static final int ICON_RATIO_HEIGHT = 2;
    public static final long ICON_MAXIMUM_BYTES = 1_048_576L;

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{1,47}$");

    public static final String AUDIT_CREATE = "reviews.tag.create";
    public static final String AUDIT_UPDATE = "reviews.tag.update";
    public static final String AUDIT_RETIRE = "reviews.tag.retire";

    private final JdbcFeedbackTagStore tags;
    private final TenantImageCheck images;
    private final BrandLocaleLookup locales;
    private final AuditRecorder audit;
    private final Clock clock;

    public FeedbackTagService(
            JdbcFeedbackTagStore tags,
            TenantImageCheck images,
            BrandLocaleLookup locales,
            AuditRecorder audit,
            Clock clock) {
        this.tags = tags;
        this.images = images;
        this.locales = locales;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * A tag with its wording.
     *
     * @param used whether any review carries it, which freezes its code, category and sentiment
     */
    public record TagView(TagRow row, Map<String, String> labels, boolean used) {}

    /** What an author supplies to register a tag. */
    public record NewTag(
            String code,
            TagCategory category,
            TagSentiment sentiment,
            @Nullable UUID iconAssetId,
            int sortOrder,
            Map<String, String> labels) {}

    /**
     * What an author supplies to change a tag. The code is not here: it is immutable.
     *
     * @param iconAssetId the icon after the edit; null removes it
     * @param labels      the locales the editor showed; every other locale keeps its wording
     */
    public record TagEdit(
            TagCategory category,
            TagSentiment sentiment,
            @Nullable UUID iconAssetId,
            int sortOrder,
            TagStatus status,
            Map<String, String> labels,
            int expectedVersion) {}

    /** The locale set the tenant's tag editor offers. */
    public TenantLocaleSet localeSet(UUID tenantId) {
        return locales.tenantLocaleSet(tenantId);
    }

    @Transactional(readOnly = true)
    public List<TagView> list(UUID tenantId) {
        Map<UUID, Map<String, String>> labels = tags.translationsForTenant(tenantId);
        return tags.list(tenantId).stream()
                .map(row -> new TagView(row, labels.getOrDefault(row.id(), Map.of()), tags.isUsed(tenantId, row.id())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TagView get(UUID tenantId, UUID tagId) {
        TagRow row = tags.find(tenantId, tagId).orElseThrow(FeedbackTagService::noSuchTag);
        return new TagView(row, tags.translationsFor(tenantId, tagId), tags.isUsed(tenantId, tagId));
    }

    @Transactional
    public TagView create(UUID tenantId, NewTag command, String actorSubject) {
        if (!CODE.matcher(command.code()).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A tag code is lower case letters, digits and underscores, starting with a letter "
                            + "(2 to 48 characters)");
        }
        Map<String, String> labels = suppliedLabels(command.labels());
        requireDefaultLocaleLabel(tenantId, labels);
        if (tags.countActive(tenantId) >= MAXIMUM_ACTIVE_TAGS) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A tenant has at most %d active feedback tags; retire one first".formatted(MAXIMUM_ACTIVE_TAGS));
        }
        requireIcon(tenantId, command.iconAssetId());

        Instant now = clock.instant();
        TagRow row = new TagRow(
                Ids.newId(),
                tenantId,
                command.code(),
                command.category(),
                command.sentiment(),
                command.iconAssetId(),
                command.sortOrder(),
                TagStatus.ACTIVE,
                1,
                now,
                now);
        try {
            tags.insert(row, actorSubject);
        } catch (DuplicateKeyException clash) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A feedback tag with the code '%s' already exists".formatted(command.code()));
        }
        tags.upsertTranslations(tenantId, row.id(), labels, now);

        audit.record(AuditFact.of(AUDIT_CREATE, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("FeedbackTag", row.id())
                .because("Created a feedback tag")
                .usingCapability(Capability.TENANT_CONFIGURATION_WRITE.code())
                .changed(ChangeDocuments.created(snapshot(row, labels)))
                .correlatedBy(row.id().toString())
                .occurredAt(now)
                .build());
        return new TagView(row, labels, false);
    }

    /**
     * Imports the twelve starter tags (ADR 0159, Specification), once.
     *
     * @throws ApiException {@code RESOURCE_CONFLICT} when the library already holds any of them, which is
     *                      how "once" is enforced: a starter tag's code is immutable, so it is still
     *                      there however it has since been edited or retired
     */
    @Transactional
    public List<TagView> importStarterPack(UUID tenantId, String actorSubject) {
        Set<String> existing = new LinkedHashSet<>();
        tags.list(tenantId).forEach(tag -> existing.add(tag.code()));
        List<String> clashes = StarterPack.TAGS.stream()
                .map(StarterPack.Tag::code)
                .filter(existing::contains)
                .toList();
        if (!clashes.isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The starter pack was already imported (the library holds %s)".formatted(clashes));
        }
        if (tags.countActive(tenantId) + StarterPack.TAGS.size() > MAXIMUM_ACTIVE_TAGS) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The starter pack would take the library past %d active tags".formatted(MAXIMUM_ACTIVE_TAGS));
        }
        List<TagView> created = new ArrayList<>();
        int order = 10;
        for (StarterPack.Tag tag : StarterPack.TAGS) {
            created.add(create(
                    tenantId,
                    new NewTag(tag.code(), tag.category(), tag.sentiment(), null, order, tag.labels()),
                    actorSubject));
            order += 10;
        }
        return created;
    }

    @Transactional
    public TagView update(UUID tenantId, UUID tagId, TagEdit command, String actorSubject) {
        TagRow existing = tags.find(tenantId, tagId).orElseThrow(FeedbackTagService::noSuchTag);
        if (existing.version() != command.expectedVersion()) {
            throw ApiException.staleVersion(command.expectedVersion(), existing.version());
        }
        boolean used = tags.isUsed(tenantId, tagId);
        if (used && (command.category() != existing.category() || command.sentiment() != existing.sentiment())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A tag that a review carries cannot change its category or sentiment: a report groups by them");
        }
        Map<String, String> labels = suppliedLabels(command.labels());
        Map<String, String> before = tags.translationsFor(tenantId, tagId);
        if (!labels.isEmpty() || before.isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(before);
            merged.putAll(labels);
            requireDefaultLocaleLabel(tenantId, merged);
        }
        boolean reactivating = existing.status() == TagStatus.RETIRED && command.status() == TagStatus.ACTIVE;
        if (reactivating && tags.countActive(tenantId) >= MAXIMUM_ACTIVE_TAGS) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A tenant has at most %d active feedback tags; retire one first".formatted(MAXIMUM_ACTIVE_TAGS));
        }
        if (command.iconAssetId() != null && !command.iconAssetId().equals(existing.iconAssetId())) {
            requireIcon(tenantId, command.iconAssetId());
        }

        Instant now = clock.instant();
        int newVersion = tags.update(
                        tenantId,
                        tagId,
                        command.category(),
                        command.sentiment(),
                        command.iconAssetId(),
                        command.sortOrder(),
                        command.status(),
                        command.expectedVersion(),
                        actorSubject,
                        now)
                .orElseThrow(() -> ApiException.staleVersion(
                        command.expectedVersion(),
                        tags.find(tenantId, tagId).map(TagRow::version).orElse(-1)));
        tags.upsertTranslations(tenantId, tagId, labels, now);

        TagRow updated = new TagRow(
                existing.id(),
                existing.tenantId(),
                existing.code(),
                command.category(),
                command.sentiment(),
                command.iconAssetId(),
                command.sortOrder(),
                command.status(),
                newVersion,
                existing.createdAt(),
                now);
        Map<String, String> after = new LinkedHashMap<>(before);
        after.putAll(labels);

        boolean retired = existing.status() == TagStatus.ACTIVE && command.status() == TagStatus.RETIRED;
        audit.record(AuditFact.of(retired ? AUDIT_RETIRE : AUDIT_UPDATE, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("FeedbackTag", tagId)
                .targetVersion((long) newVersion)
                .because(retired ? "Retired a feedback tag" : "Edited a feedback tag")
                .usingCapability(Capability.TENANT_CONFIGURATION_WRITE.code())
                .changed(ChangeDocuments.diff(snapshot(existing, before), snapshot(updated, after)))
                .correlatedBy(tagId.toString())
                .occurredAt(now)
                .build());
        return new TagView(updated, after, used);
    }

    // ----------------------------------------------------------- what is offered

    /** The tags a customer may be offered, split by sentiment, each side capped and in the tenant's order. */
    public record Offered(List<TagView> positive, List<TagView> negative) {

        public List<TagView> all() {
            List<TagView> all = new ArrayList<>(positive);
            all.addAll(negative);
            return all;
        }
    }

    @Transactional(readOnly = true)
    public Offered offered(UUID tenantId) {
        Map<UUID, Map<String, String>> labels = tags.translationsForTenant(tenantId);
        List<TagView> positive = new ArrayList<>();
        List<TagView> negative = new ArrayList<>();
        for (TagRow row : tags.listActive(tenantId)) {
            TagView view = new TagView(row, labels.getOrDefault(row.id(), Map.of()), false);
            List<TagView> side = row.sentiment() == TagSentiment.POSITIVE ? positive : negative;
            if (side.size() < MAXIMUM_OFFERED_PER_SENTIMENT) {
                side.add(view);
            }
        }
        return new Offered(positive, negative);
    }

    /**
     * Checks the tags a customer attached to a rating and returns them.
     *
     * @throws ApiException {@code VALIDATION_FAILED} for more than {@value #MAXIMUM_TAGS_PER_REVIEW}, a
     *                      repeat, a tag that is retired or another tenant's, or one whose sentiment does
     *                      not match the rating
     */
    @Transactional(readOnly = true)
    public List<TagRow> validateForReview(UUID tenantId, int rating, Collection<UUID> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> distinct = new LinkedHashSet<>(tagIds);
        if (distinct.size() != tagIds.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A tag can be attached to a review once");
        }
        if (distinct.size() > MAXIMUM_TAGS_PER_REVIEW) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A review carries at most %d tags".formatted(MAXIMUM_TAGS_PER_REVIEW));
        }
        Map<UUID, TagRow> found = new LinkedHashMap<>();
        tags.findAll(tenantId, distinct).forEach(row -> found.put(row.id(), row));
        Set<TagSentiment> allowed = TagSentiment.offeredFor(rating);
        List<TagRow> accepted = new ArrayList<>();
        for (UUID id : distinct) {
            TagRow row = found.get(id);
            // One answer for "no such tag", "another tenant's" and "retired": the customer was only ever
            // offered active tags of this tenant, and a refusal must not confirm that another exists.
            if (row == null || row.status() != TagStatus.ACTIVE) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "That tag is not available");
            }
            if (!allowed.contains(row.sentiment())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "That tag does not match the rating");
            }
            accepted.add(row);
        }
        return accepted;
    }

    /** The labels of these tags by id, for the reads that show a review's tags back. */
    @Transactional(readOnly = true)
    public Map<UUID, TagView> viewsOf(UUID tenantId, Collection<UUID> tagIds) {
        Map<UUID, TagView> views = new LinkedHashMap<>();
        if (tagIds.isEmpty()) {
            return views;
        }
        Map<UUID, Map<String, String>> labels = tags.translationsForTenant(tenantId);
        for (TagRow row : tags.findAll(tenantId, new LinkedHashSet<>(tagIds))) {
            views.put(row.id(), new TagView(row, labels.getOrDefault(row.id(), Map.of()), true));
        }
        return views;
    }

    // ----------------------------------------------------------------- helpers

    private void requireIcon(UUID tenantId, @Nullable UUID iconAssetId) {
        if (iconAssetId == null) {
            return;
        }
        Verdict verdict = images.check(tenantId, iconAssetId, ICON_RATIO_WIDTH, ICON_RATIO_HEIGHT, ICON_MAXIMUM_BYTES);
        if (verdict != Verdict.ACCEPTED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That image cannot be a tag icon: " + explain(verdict));
        }
    }

    private static String explain(Verdict verdict) {
        return switch (verdict) {
            case ACCEPTED -> "accepted";
            case UNKNOWN_ASSET -> "no such image";
            case NOT_TENANT_OWNED -> "it is owned by a brand or a location, not by the tenant";
            case NOT_PUBLIC -> "it is private, so a customer could never see it";
            case NOT_AVAILABLE -> "it has not finished uploading and being checked";
            case NOT_A_RASTER_IMAGE -> "it is not a raster image";
            case WRONG_ASPECT_RATIO -> "it is not 3:2";
            case TOO_LARGE -> "it is larger than 1 MB";
        };
    }

    private Map<String, String> suppliedLabels(@Nullable Map<String, String> byLocale) {
        try {
            return LocalizedLabels.supplied(byLocale, MAXIMUM_LABEL_LENGTH);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    private void requireDefaultLocaleLabel(UUID tenantId, Map<String, String> labels) {
        String defaultLocale = locales.tenantLocaleSet(tenantId).defaultLocale();
        if (!labels.containsKey(defaultLocale)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A tag needs its wording in the tenant's default language (%s)".formatted(defaultLocale));
        }
    }

    private static Map<String, Object> snapshot(TagRow row, Map<String, String> labels) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", row.code());
        snapshot.put("category", row.category().name());
        snapshot.put("sentiment", row.sentiment().name());
        snapshot.put(
                "iconAssetId",
                row.iconAssetId() == null ? null : row.iconAssetId().toString());
        snapshot.put("sortOrder", row.sortOrder());
        snapshot.put("status", row.status().name());
        labels.forEach((locale, label) -> snapshot.put("label." + locale, label));
        return snapshot;
    }

    private static ApiException noSuchTag() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such feedback tag");
    }

    /** Whether a tag exists, for a caller that must not distinguish "yours" from "not yours". */
    public Optional<TagRow> find(UUID tenantId, UUID tagId) {
        return tags.find(tenantId, tagId);
    }
}
