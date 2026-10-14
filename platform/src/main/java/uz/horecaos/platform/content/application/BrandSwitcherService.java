package uz.horecaos.platform.content.application;

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
import uz.horecaos.platform.content.application.HomeDocument.Media;
import uz.horecaos.platform.content.application.HomeDocument.SwitcherEntry;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcBrandSwitcherStore;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcBrandSwitcherStore.EntryRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.media.api.MediaCreativeLookup.Creative;
import uz.horecaos.platform.tenancy.api.BrandLogoLookup;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The brand switcher (ADR 0157 Release 4, built without waiting for its trigger by ADR 0183 D17): the strip a brand's storefront
 * shows so a visitor can reach the tenant's other brands.
 *
 * <p><strong>An entry names a channel, never a hostname.</strong> What a visitor is sent to is the <em>verified</em> hostname of
 * that WEB channel, looked up on every read, so a hostname that is released or un-verified stops being linkable the moment it
 * changes and nobody has to remember to edit an entry. An entry whose target brand is no longer active, or whose hostname is
 * not verified, is not shown (and the console says so). With no entry the home document is byte-for-byte what it was.
 *
 * <p>The mutations need {@code content.manage} at the brand and leave an audit fact each, in the transaction that made them.
 */
@Service
public class BrandSwitcherService {

    private final JdbcBrandSwitcherStore store;
    private final ContentEnvironment environment;
    private final BrandLogoLookup logos;
    private final MediaCreativeLookup media;
    private final AuditRecorder audit;
    private final Clock clock;

    public BrandSwitcherService(
            JdbcBrandSwitcherStore store,
            ContentEnvironment environment,
            BrandLogoLookup logos,
            MediaCreativeLookup media,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.environment = environment;
        this.logos = logos;
        this.media = media;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * One entry as the console shows it.
     *
     * @param hostname the target channel's verified hostname, absent while it has none
     * @param shown whether a visitor would see this entry right now: active, the target brand active, a verified hostname
     */
    public record EntryView(
            UUID id,
            UUID targetBrandId,
            String targetBrandName,
            UUID channelId,
            @Nullable String channelCode,
            @Nullable String hostname,
            int sortKey,
            String state,
            boolean shown,
            int version) {}

    /** One sibling brand and website channel an entry can name; {@code hostname} is null while it has no verified one. */
    public record CandidateView(
            UUID brandId,
            String brandName,
            UUID channelId,
            String channelCode,
            @Nullable String hostname) {}

    /**
     * The tenant's other brands and their website channels, for the console's picker: so a brand manager can offer a sibling
     * brand without being able to list the tenant's brands or channels. Reading it needs the same {@code content.read}
     * as the entries; it reveals names and verified hostnames, which the switcher would show a visitor anyway.
     */
    @Transactional(readOnly = true)
    public List<CandidateView> candidates(UUID tenantId, UUID brandId) {
        requireBrand(tenantId, brandId);
        return environment.brand().siblingStorefronts(tenantId, brandId).stream()
                .map(sibling -> new CandidateView(
                        sibling.brandId(),
                        sibling.brandName(),
                        sibling.channelId(),
                        sibling.channelCode(),
                        sibling.hostname()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EntryView> list(UUID tenantId, UUID brandId) {
        requireBrand(tenantId, brandId);
        List<EntryView> views = new ArrayList<>();
        for (EntryRow row : store.list(tenantId, brandId)) {
            views.add(view(row));
        }
        return views;
    }

    @Transactional
    public EntryView add(UUID tenantId, UUID brandId, UUID targetBrandId, UUID channelId, String actor) {
        requireBrand(tenantId, brandId);
        if (brandId.equals(targetBrandId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A brand does not offer a way to itself");
        }
        if (!environment.brand().brandExists(tenantId, targetBrandId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That brand does not belong to this tenant");
        }
        Optional<SalesChannel> channel = environment.channelById(tenantId, channelId);
        if (channel.isEmpty() || channel.get().systemType() != SalesChannelSystemType.WEB) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The entry must name one of this tenant's website channels");
        }
        Instant now = clock.instant();
        UUID id = uz.horecaos.platform.configuration.Ids.newId();
        if (!store.insert(
                id, tenantId, brandId, targetBrandId, channelId, store.nextSortKey(tenantId, brandId), actor, now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That brand and channel is already in the switcher");
        }
        recordAudit(
                "content.switcher_entry.added",
                tenantId,
                brandId,
                id,
                actor,
                "Brand switcher entry added",
                Map.of(),
                snapshot(targetBrandId, channelId, "ACTIVE"),
                now);
        return view(store.find(tenantId, brandId, id).orElseThrow());
    }

    @Transactional
    public EntryView setState(
            UUID tenantId, UUID brandId, UUID entryId, String state, int expectedVersion, String actor) {
        if (!Set.of("ACTIVE", "HIDDEN").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "state must be ACTIVE or HIDDEN");
        }
        EntryRow before = store.find(tenantId, brandId, entryId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such switcher entry"));
        Instant now = clock.instant();
        if (!store.setState(tenantId, brandId, entryId, state, expectedVersion, now)) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        recordAudit(
                "content.switcher_entry.updated",
                tenantId,
                brandId,
                entryId,
                actor,
                state.equals("HIDDEN") ? "Brand switcher entry hidden" : "Brand switcher entry shown",
                snapshot(before.targetBrandId(), before.channelId(), before.state()),
                snapshot(before.targetBrandId(), before.channelId(), state),
                now);
        return view(store.find(tenantId, brandId, entryId).orElseThrow());
    }

    @Transactional
    public void remove(UUID tenantId, UUID brandId, UUID entryId, String actor) {
        EntryRow before = store.find(tenantId, brandId, entryId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such switcher entry"));
        if (!store.delete(tenantId, brandId, entryId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such switcher entry");
        }
        recordAudit(
                "content.switcher_entry.removed",
                tenantId,
                brandId,
                entryId,
                actor,
                "Brand switcher entry removed",
                snapshot(before.targetBrandId(), before.channelId(), before.state()),
                Map.of(),
                clock.instant());
    }

    /** Replaces the order with exactly the entries named, once each; anything missing or foreign is refused. */
    @Transactional
    public List<EntryView> reorder(UUID tenantId, UUID brandId, List<UUID> orderedIds, String actor) {
        requireBrand(tenantId, brandId);
        Set<UUID> have = new HashSet<>();
        store.list(tenantId, brandId).forEach(row -> have.add(row.id()));
        if (orderedIds.size() != have.size() || !have.equals(new HashSet<>(orderedIds))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The order must name every entry of this brand's switcher exactly once");
        }
        Instant now = clock.instant();
        store.reorder(tenantId, brandId, orderedIds, now);
        recordAudit(
                "content.switcher_entry.reordered",
                tenantId,
                brandId,
                brandId,
                actor,
                "Brand switcher reordered",
                Map.of(),
                Map.of("count", orderedIds.size()),
                now);
        return list(tenantId, brandId);
    }

    /**
     * What a storefront shows: the entries that would be shown now, in order, each with the target's name, its logo as a
     * servable image or none, and the address. Empty for a brand with no entry, a hidden one, or none that resolve.
     */
    @Transactional(readOnly = true)
    public List<SwitcherEntry> forStorefront(UUID tenantId, UUID brandId) {
        List<EntryRow> rows = store.list(tenantId, brandId).stream()
                .filter(row -> "ACTIVE".equals(row.state()))
                .toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, UUID> logoByBrand = new LinkedHashMap<>();
        for (EntryRow row : rows) {
            logos.logoAssetId(tenantId, row.targetBrandId())
                    .ifPresent(asset -> logoByBrand.put(row.targetBrandId(), asset));
        }
        Map<UUID, Creative> creatives =
                logoByBrand.isEmpty() ? Map.of() : media.describe(tenantId, new HashSet<>(logoByBrand.values()));
        List<SwitcherEntry> entries = new ArrayList<>();
        for (EntryRow row : rows) {
            if (!environment.brand().brandIsActive(tenantId, row.targetBrandId())) {
                continue;
            }
            Optional<String> hostname = environment.brand().verifiedWebHostname(tenantId, row.channelId());
            Optional<String> name = environment.brand().brandDisplayName(tenantId, row.targetBrandId());
            if (hostname.isEmpty() || name.isEmpty()) {
                continue;
            }
            UUID logoAsset = logoByBrand.get(row.targetBrandId());
            Media logo = logoAsset == null ? null : MediaViews.image(tenantId, logoAsset, name.get(), creatives);
            entries.add(new SwitcherEntry(row.targetBrandId(), name.get(), logo, "https://" + hostname.get() + "/"));
        }
        return entries;
    }

    private EntryView view(EntryRow row) {
        String name = environment
                .brand()
                .brandDisplayName(row.tenantId(), row.targetBrandId())
                .orElse("");
        Optional<SalesChannel> channel = environment.channelById(row.tenantId(), row.channelId());
        Optional<String> hostname = environment.brand().verifiedWebHostname(row.tenantId(), row.channelId());
        boolean shown = "ACTIVE".equals(row.state())
                && hostname.isPresent()
                && environment.brand().brandIsActive(row.tenantId(), row.targetBrandId());
        return new EntryView(
                row.id(),
                row.targetBrandId(),
                name,
                row.channelId(),
                channel.map(SalesChannel::code).orElse(null),
                hostname.orElse(null),
                row.sortKey(),
                row.state(),
                shown,
                row.version());
    }

    private void requireBrand(UUID tenantId, UUID brandId) {
        if (!environment.brand().brandExists(tenantId, brandId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such brand");
        }
    }

    private static Map<String, Object> snapshot(UUID targetBrandId, UUID channelId, String state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("targetBrandId", targetBrandId.toString());
        snapshot.put("channelId", channelId.toString());
        snapshot.put("state", state);
        return snapshot;
    }

    private void recordAudit(
            String action,
            UUID tenantId,
            UUID brandId,
            UUID target,
            String actor,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            Instant now) {
        audit.record(AuditFact.of(action, AuditClass.BUSINESS)
                .by(ActorRef.user(actor, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("content.brand_switcher_entry", target)
                .usingCapability(Capability.CONTENT_MANAGE.code())
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(brandId.toString())
                .occurredAt(now)
                .build());
    }
}
