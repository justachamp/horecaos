package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore.MediaOverrideRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * The channel-scoped media override (ADR 0138): the images one channel shows for
 * a product, variant or category instead of its own.
 *
 * <p>Sparse and default-is-the-normal-thing, like the exclusions it mirrors: no
 * row means the entity shows what it always showed, and replacing a set with an
 * empty one is how an override is removed. It rides the draft — a preview reads
 * it at once, and a live menu is untouched until something is published to the
 * channel, which then serves it ({@link ChannelMediaLayers} is the one function
 * both go through) — so, unlike an offering toggle, nothing here needs to take
 * effect mid-service.
 *
 * <p><strong>Products only reach a live menu.</strong> A published menu carries a
 * product's images and no variant's or category's ({@code MenuVariant} and {@code
 * MenuCategory} have no image field), so a variant or category override is stored
 * and drawn by the preview but served by nothing until ADR 0040's push gives a
 * consumer for it.
 *
 * <p>Separate from {@link CatalogAuthoringService} because that class is
 * constructed directly, with five arguments, by dozens of tests that have no use
 * for a media port; widening its constructor to reach one new write would be an
 * unrelated mass edit.
 */
@Service
public class ChannelMediaOverrideService {

    /** More than this on one entity is a gallery nobody is going to review on an aggregator card. */
    static final int MAXIMUM_IMAGES = 20;

    private static final Set<EntityType> OVERRIDABLE =
            Set.of(EntityType.PRODUCT, EntityType.VARIANT, EntityType.CATEGORY);

    private final JdbcCatalogStore store;
    private final JdbcChannelProjectionStore projections;
    private final MediaAvailability media;
    private final SalesChannelLookup channels;
    private final AuditRecorder audit;
    private final Clock clock;

    public ChannelMediaOverrideService(
            JdbcCatalogStore store,
            JdbcChannelProjectionStore projections,
            MediaAvailability media,
            SalesChannelLookup channels,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.projections = projections;
        this.media = media;
        this.channels = channels;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Replaces the whole override set for one entity on one channel, under the version the editor
     * read (ADR 0031).
     *
     * <p>The whole set every time, matching the photo editor's own whole-set save: what the
     * screen shows is what is stored, never a delta applied to a state the caller has not seen.
     * An empty list removes every override.
     *
     * <p>The versioning is {@code PhysicalAttributesAuthoringService}'s: a set that does not exist
     * reads as version {@code 0}, the first write expects {@code 0}, a later one expects what the
     * read returned, and emptying the set removes it so "no rows" stays the single representation of
     * "shows its own images". Saving the set that is already stored changes nothing and keeps its
     * version, so a double click is not an edit another editor is then told about. The set's version
     * restarts at 1 once it has been emptied, which a stale editor could in principle hit again; the
     * price of having nowhere to keep a version for a set that has no rows.
     *
     * @param expectedVersion {@code 0} when the item has no override on this channel
     * @return the set now in force and its version
     * @throws UnknownOverrideTargetException the channel or the entity is not this tenant's or brand's
     * @throws InvalidOverrideException the set is malformed or names an asset that is not ready to show
     * @throws StaleOverrideException the set has changed since the editor read it
     */
    @Transactional
    public OverrideSet replace(
            UUID tenantId,
            UUID brandId,
            UUID channelId,
            EntityType entityType,
            UUID entityId,
            List<Image> images,
            int expectedVersion,
            String actorSubject) {

        requireTarget(tenantId, brandId, channelId, entityType, entityId);
        validate(tenantId, images);

        projections.lockMediaOverrideSet(tenantId, channelId, entityType, entityId);
        List<MediaOverrideRow> previous =
                projections.mediaOverridesFor(tenantId, brandId, channelId, entityType, entityId);
        int currentVersion = versionOf(previous);
        if (currentVersion != expectedVersion) {
            throw new StaleOverrideException(expectedVersion, currentVersion);
        }

        List<MediaOverrideRow> requested = images.stream()
                .map(image -> new MediaOverrideRow(
                        entityType, entityId, image.mediaAssetId(), image.role(), image.sortOrder(), 0))
                .toList();
        if (sorted(describe(previous)).equals(sorted(describe(requested)))) {
            return new OverrideSet(previous, currentVersion);
        }

        projections.replaceMediaOverrides(tenantId, brandId, channelId, entityType, entityId, requested);
        List<MediaOverrideRow> now = projections.mediaOverridesFor(tenantId, brandId, channelId, entityType, entityId);

        audit.record(AuditFact.of("catalog.channelMediaOverride.replaced", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ChannelMediaOverride", entityId)
                .because(now.isEmpty() ? "Removed the channel images" : "Replaced the channel images")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(
                        snapshotOf(channelId, entityType, previous), snapshotOf(channelId, entityType, now)))
                .correlatedBy(entityId.toString())
                .occurredAt(clock.instant())
                .build());
        return new OverrideSet(now, versionOf(now));
    }

    /** One item's override set on one channel, with the version to quote when replacing it. */
    @Transactional(readOnly = true)
    public OverrideSet read(UUID tenantId, UUID brandId, UUID channelId, EntityType entityType, UUID entityId) {
        if (channels.byId(tenantId, channelId).isEmpty()) {
            throw new UnknownOverrideTargetException("No sales channel " + channelId);
        }
        List<MediaOverrideRow> rows = projections.mediaOverridesFor(tenantId, brandId, channelId, entityType, entityId);
        return new OverrideSet(rows, versionOf(rows));
    }

    private void requireTarget(UUID tenantId, UUID brandId, UUID channelId, EntityType entityType, UUID entityId) {
        if (!OVERRIDABLE.contains(entityType)) {
            throw new InvalidOverrideException(
                    "Only a product, a variant or a category can carry a channel image, not " + entityType);
        }
        if (channels.byId(tenantId, channelId).isEmpty()) {
            throw new UnknownOverrideTargetException("No sales channel " + channelId);
        }
        // Checked against this brand explicitly, as setChannelOffering does: the
        // entity id is polymorphic, so no foreign key backs it, and a write
        // against another brand's product would otherwise succeed and surface
        // nowhere.
        if (!store.entityExistsInBrand(tenantId, brandId, entityType, entityId)) {
            throw new UnknownOverrideTargetException("No %s %s in this brand".formatted(entityType, entityId));
        }
    }

    private static int versionOf(List<MediaOverrideRow> rows) {
        return rows.stream().mapToInt(MediaOverrideRow::version).max().orElse(0);
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    /** One channel's overrides, brand-wide or narrowed to one entity. */
    @Transactional(readOnly = true)
    public List<MediaOverrideRow> list(
            UUID tenantId, UUID brandId, UUID channelId, @Nullable EntityType entityType, @Nullable UUID entityId) {
        if (channels.byId(tenantId, channelId).isEmpty()) {
            throw new UnknownOverrideTargetException("No sales channel " + channelId);
        }
        if (entityType != null && entityId != null) {
            return projections.mediaOverridesFor(tenantId, brandId, channelId, entityType, entityId);
        }
        return projections.mediaOverrides(tenantId, brandId, channelId);
    }

    private void validate(UUID tenantId, List<Image> images) {
        if (images.size() > MAXIMUM_IMAGES) {
            throw new InvalidOverrideException(
                    "A channel can show at most %d images for one item".formatted(MAXIMUM_IMAGES));
        }
        long primaries =
                images.stream().filter(image -> "PRIMARY".equals(image.role())).count();
        if (primaries > 1) {
            throw new InvalidOverrideException("An item has one primary image per channel, not " + primaries);
        }
        Set<UUID> distinct = images.stream().map(Image::mediaAssetId).collect(Collectors.toSet());
        if (distinct.size() != images.size()) {
            throw new InvalidOverrideException("An image appears more than once");
        }
        for (Image image : images) {
            if (!"PRIMARY".equals(image.role()) && !"GALLERY".equals(image.role())) {
                throw new InvalidOverrideException("An image's role is PRIMARY or GALLERY, not " + image.role());
            }
            if (image.sortOrder() < 0) {
                throw new InvalidOverrideException("An image's sort order cannot be negative");
            }
            // The attach endpoint for the universal image accepts any id at all and
            // surfaces the gap wherever the media is later rendered. A channel image
            // is refused up front: a menu pushed to an aggregator with a broken
            // picture is the failure this whole record exists to catch earlier.
            if (!media.allDisplayable(tenantId, Set.of(new MediaAssetId(image.mediaAssetId())))) {
                throw new InvalidOverrideException(
                        "Image %s is not a verified image of this tenant".formatted(image.mediaAssetId()));
            }
        }
    }

    private static List<String> describe(List<MediaOverrideRow> rows) {
        return rows.stream()
                .map(row -> "%s:%s:%d".formatted(row.role(), row.mediaAssetId(), row.sortOrder()))
                .toList();
    }

    /** A {@code {channelId, entityType, images}} snapshot for the audit diff. */
    private static Map<String, Object> snapshotOf(UUID channelId, EntityType entityType, List<MediaOverrideRow> rows) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("channelId", channelId.toString());
        map.put("entityType", entityType.name());
        map.put("images", describe(rows));
        return map;
    }

    /** One image of a replacement set. */
    public record Image(UUID mediaAssetId, String role, int sortOrder) {}

    /** The images one item shows on one channel and the version of that set; {@code 0} when there are none. */
    public record OverrideSet(List<MediaOverrideRow> images, int version) {}

    /** The version the editor quoted is no longer the stored one. */
    public static final class StaleOverrideException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int expected;
        private final int actual;

        public StaleOverrideException(int expected, int actual) {
            super("The channel images are at version " + actual + ", not " + expected);
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }

    /** The channel or the entity is not this tenant's or brand's. */
    public static final class UnknownOverrideTargetException extends RuntimeException {

        public UnknownOverrideTargetException(String message) {
            super(message);
        }
    }

    /** The set is malformed, or names an image that is not ready to be shown. */
    public static final class InvalidOverrideException extends RuntimeException {

        public InvalidOverrideException(String message) {
            super(message);
        }
    }
}
