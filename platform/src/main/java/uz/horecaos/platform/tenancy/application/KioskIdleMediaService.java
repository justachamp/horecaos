package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.api.MediaPurpose;
import uz.horecaos.platform.media.api.PurposedImages;
import uz.horecaos.platform.media.api.PurposedImages.Verdict;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcKioskDeviceStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcKioskIdleMediaStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcKioskIdleMediaStore.ItemRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A KIOSK channel's idle media: up to eight 9:16 pictures of at most 1 MB, in a chosen order, that every paired
 * kiosk on the channel plays on its status screen (ADR 0187, D25).
 *
 * <p>Managed under {@code kiosk.device.manage} at a branch where the channel sells (the capability branch managers
 * hold; they hold neither {@code channel.manage} nor {@code media.upload}), so the upload the card needs is lent by the
 * media module through {@link PurposedImages}. A picture is checked three times: when the upload is requested (the
 * size), when the pipeline verifies it (the shape, on the header's own dimensions) and again here when it is attached,
 * because the attach is where a guessed asset id would be tried. Idle media is channel content: a manager of any branch
 * the channel sells at changes what every kiosk on that channel shows, and the audit fact names the branch they acted
 * from.
 */
@Service
public class KioskIdleMediaService {

    /** The most pictures on a channel (also the database's position check). */
    public static final int MAXIMUM_ITEMS = 8;

    /** The anonymous route that serves a PUBLIC displayable asset by redirect (ADR 0010). */
    private static final String MEDIA_ROUTE = "/api/v1/storefront/tenants/%s/media/%s";

    public static final String AUDIT_ATTACHED = "kiosk.idle_media.attached";
    public static final String AUDIT_REORDERED = "kiosk.idle_media.reordered";
    public static final String AUDIT_REMOVED = "kiosk.idle_media.removed";

    private final JdbcKioskIdleMediaStore store;
    private final JdbcKioskDeviceStore devices;
    private final SalesChannelLookup channels;
    private final PurposedImages images;
    private final ConfigurationResolver configuration;
    private final AuditRecorder audit;
    private final Clock clock;

    public KioskIdleMediaService(
            JdbcKioskIdleMediaStore store,
            JdbcKioskDeviceStore devices,
            SalesChannelLookup channels,
            PurposedImages images,
            ConfigurationResolver configuration,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.devices = devices;
        this.channels = channels;
        this.images = images;
        this.configuration = configuration;
        this.audit = audit;
        this.clock = clock;
    }

    /** One picture, as the console and the kiosk read it. {@code url} is a path on the API's own origin. */
    public record IdleMediaItem(UUID id, UUID assetId, int position, String url) {}

    /** A channel's list, with the version the next change is sent against and the limits the screen shows. */
    public record IdleMediaList(
            UUID channelId,
            int version,
            List<IdleMediaItem> items,
            int maximumCount,
            long maximumBytes,
            String ratio) {}

    // ---------------------------------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public IdleMediaList list(UUID tenantId, UUID locationId, UUID channelId) {
        requireKioskChannelAt(tenantId, channelId, locationId);
        return listOf(tenantId, channelId);
    }

    /** The list a paired kiosk reads with its configuration. No branch check: the caller is the device itself. */
    @Transactional(readOnly = true)
    public IdleMediaList listFor(UUID tenantId, UUID channelId) {
        return listOf(tenantId, channelId);
    }

    // --------------------------------------------------------------------------------------------- upload

    @Transactional
    public PurposedImages.UploadTicket requestUpload(
            UUID tenantId,
            UUID locationId,
            UUID channelId,
            String contentType,
            long sizeBytes,
            @Nullable String filename,
            @Nullable UUID actorId) {
        requireEnabled(tenantId);
        requireKioskChannelAt(tenantId, channelId, locationId);
        try {
            return images.requestUpload(tenantId, MediaPurpose.KIOSK_IDLE, contentType, sizeBytes, filename, actorId);
        } catch (IllegalArgumentException refused) {
            // The media module's own sentence: the type, or the 1 MB cap, named before a byte is uploaded.
            String reason = sizeBytes > MediaPurpose.KIOSK_IDLE.maximumBytes() ? "TOO_LARGE" : "NOT_A_RASTER_IMAGE";
            throw refusal(reason, refused.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PurposedImages.UploadState uploadState(UUID tenantId, UUID locationId, UUID channelId, UUID assetId) {
        requireKioskChannelAt(tenantId, channelId, locationId);
        return images.uploadState(tenantId, assetId, MediaPurpose.KIOSK_IDLE)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such media asset"));
    }

    @Transactional
    public MediaAssetStatus finalizeUpload(UUID tenantId, UUID locationId, UUID channelId, UUID assetId) {
        requireEnabled(tenantId);
        requireKioskChannelAt(tenantId, channelId, locationId);
        return images.finalizeUpload(tenantId, assetId, MediaPurpose.KIOSK_IDLE)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such media asset"));
    }

    // --------------------------------------------------------------------------------------------- changes

    @Transactional
    public IdleMediaList attach(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            UUID assetId,
            int expectedVersion,
            String actorSubject) {
        requireEnabled(tenantId);
        SalesChannel channel = requireKioskChannelAt(tenantId, channelId, locationId);
        Verdict verdict = images.check(tenantId, assetId, MediaPurpose.KIOSK_IDLE);
        if (verdict != Verdict.ACCEPTED) {
            throw refusal(verdict.name(), "That picture cannot go on the idle loop: " + verdict);
        }
        Instant now = clock.instant();
        int version = bumpOrStale(tenantId, channelId, expectedVersion, now);

        List<ItemRow> before = store.items(tenantId, channelId);
        if (before.size() >= MAXIMUM_ITEMS) {
            throw refusal("LIMIT", "A channel's idle loop holds at most " + MAXIMUM_ITEMS + " pictures");
        }
        if (before.stream().anyMatch(item -> item.assetId().equals(assetId))) {
            throw refusal("DUPLICATE", "That picture is already on the idle loop");
        }
        int position = before.size();
        // Two attaches at the same version serialise on the list's row in bumpOrStale: the slower one is stale
        // there, before it reaches the (deferred) position key.
        store.insert(tenantId, Ids.newId(), channelId, assetId, position, actorSubject, now);

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("channel", channel.code());
        created.put("assetId", assetId.toString());
        created.put("position", position);
        audit.record(fact(
                        AUDIT_ATTACHED,
                        tenantId,
                        brandId,
                        locationId,
                        channelId,
                        version,
                        "A picture was added to the idle loop",
                        actorSubject,
                        now)
                .changed(ChangeDocuments.created(created))
                .build());
        return listOf(tenantId, channelId);
    }

    @Transactional
    public IdleMediaList reorder(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            List<UUID> orderedItemIds,
            int expectedVersion,
            String actorSubject) {
        requireEnabled(tenantId);
        SalesChannel channel = requireKioskChannelAt(tenantId, channelId, locationId);
        Instant now = clock.instant();
        int version = bumpOrStale(tenantId, channelId, expectedVersion, now);

        List<ItemRow> before = store.items(tenantId, channelId);
        Set<UUID> current = new HashSet<>();
        before.forEach(item -> current.add(item.id()));
        if (orderedItemIds.size() != before.size() || !current.equals(new HashSet<>(orderedItemIds))) {
            // Not a permutation of what is there: a stale screen, or a made-up list. Nothing is placed.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The order must list every picture on the idle loop exactly once");
        }
        store.place(tenantId, channelId, orderedItemIds);

        List<ItemRow> after = store.items(tenantId, channelId);
        audit.record(fact(
                        AUDIT_REORDERED,
                        tenantId,
                        brandId,
                        locationId,
                        channelId,
                        version,
                        "The idle loop of " + channel.code() + " was put in another order",
                        actorSubject,
                        now)
                .changed(ChangeDocuments.diff(assetOrder(before), assetOrder(after)))
                .build());
        return listOf(tenantId, channelId);
    }

    @Transactional
    public IdleMediaList remove(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            UUID itemId,
            int expectedVersion,
            String actorSubject) {
        requireEnabled(tenantId);
        SalesChannel channel = requireKioskChannelAt(tenantId, channelId, locationId);
        Instant now = clock.instant();
        int version = bumpOrStale(tenantId, channelId, expectedVersion, now);

        List<ItemRow> before = store.items(tenantId, channelId);
        if (!store.remove(tenantId, channelId, itemId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such picture on this idle loop");
        }
        List<ItemRow> after = store.items(tenantId, channelId);
        audit.record(fact(
                        AUDIT_REMOVED,
                        tenantId,
                        brandId,
                        locationId,
                        channelId,
                        version,
                        "A picture was removed from the idle loop of " + channel.code(),
                        actorSubject,
                        now)
                .changed(ChangeDocuments.diff(assetOrder(before), assetOrder(after)))
                .build());
        return listOf(tenantId, channelId);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private IdleMediaList listOf(UUID tenantId, UUID channelId) {
        List<IdleMediaItem> items = new ArrayList<>();
        for (ItemRow row : store.items(tenantId, channelId)) {
            items.add(new IdleMediaItem(
                    row.id(), row.assetId(), row.position(), MEDIA_ROUTE.formatted(tenantId, row.assetId())));
        }
        return new IdleMediaList(
                channelId,
                store.version(tenantId, channelId),
                items,
                MAXIMUM_ITEMS,
                MediaPurpose.KIOSK_IDLE.maximumBytes(),
                MediaPurpose.KIOSK_IDLE.ratioWidth() + ":" + MediaPurpose.KIOSK_IDLE.ratioHeight());
    }

    private int bumpOrStale(UUID tenantId, UUID channelId, int expectedVersion, Instant now) {
        Optional<Integer> next = store.bump(tenantId, channelId, expectedVersion, now);
        if (next.isEmpty()) {
            throw ApiException.staleVersion(expectedVersion, store.version(tenantId, channelId));
        }
        return next.get();
    }

    private static Map<String, Object> assetOrder(List<ItemRow> rows) {
        List<String> assets = new ArrayList<>();
        rows.forEach(row -> assets.add(row.assetId().toString()));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("assetIds", assets);
        return document;
    }

    /**
     * The fact every idle-media change writes, short of its change document: each call site builds that itself, as {@code
     * ChangeDocuments.created} or {@code diff}, so the document is never a flat after-only map (Staff 9.3a).
     */
    private AuditFact.Builder fact(
            String action,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            int version,
            String because,
            String actorSubject,
            Instant now) {
        return AuditFact.of(action, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("tenant.sales_channel", channelId)
                .targetVersion((long) version)
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because(because)
                .usingCapability("kiosk.device.manage")
                .correlatedBy(channelId.toString())
                .occurredAt(now);
    }

    private void requireEnabled(UUID tenantId) {
        boolean on = Boolean.TRUE.equals(configuration
                .resolve(ConfigurationKeys.FEATURE_KIOSK_DEVICES, ResourceScope.tenant(tenantId))
                .value());
        if (!on) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Kiosk devices are not enabled for this tenant (feature.kiosk_devices)");
        }
    }

    /** An ACTIVE KIOSK channel of this tenant with an ACTIVE binding at the branch; one answer for every other case. */
    private SalesChannel requireKioskChannelAt(UUID tenantId, UUID channelId, UUID locationId) {
        SalesChannel channel = channels.byId(tenantId, channelId).orElse(null);
        if (channel == null
                || channel.systemType() != SalesChannelSystemType.KIOSK
                || !channel.sellable()
                || !devices.channelSellsAt(tenantId, channelId, locationId)) {
            throw new ApiException(
                    ErrorCode.KIOSK_CHANNEL_NOT_AVAILABLE,
                    "That is not an active KIOSK channel selling at this branch",
                    Map.of("channelId", channelId.toString()));
        }
        return channel;
    }

    private static ApiException refusal(String reason, @Nullable String detail) {
        return new ApiException(ErrorCode.IDLE_MEDIA_REFUSED, detail, Map.of("reason", reason));
    }
}
