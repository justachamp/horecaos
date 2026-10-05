package uz.horecaos.platform.catalog.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * Turns a draft into a live menu (ADR 0016).
 *
 * <p>Three steps, in one transaction: snapshot, validate, activate. The snapshot
 * is a copy rather than a reference, which is the mechanism that stops a later
 * draft edit from changing what customers are already seeing — not a convention
 * anyone has to remember.
 *
 * <p><strong>Going live and rolling back leave an audit fact</strong> (ADR 0027, staff row
 * {@code 9.3a}) in the transaction that did it: {@code catalog.published} and {@code
 * catalog.publication.rolled_back}, each with the publication that was live before and the one
 * that is live now. A rejected publication changes nothing a customer sees and is already its own
 * row with the report that refused it, so it writes no fact. The publication row names who
 * published; it never named who rolled back, and a rollback is the action an incident review asks
 * about first.
 */
@Service
public class CatalogPublicationService {

    private static final Logger log = LoggerFactory.getLogger(CatalogPublicationService.class);

    private final JdbcCatalogStore store;
    private final CatalogValidator validator;
    private final CatalogSnapshotLoader snapshots;
    private final SalesChannelLookup channels;
    private final AuditRecorder audit;
    private final Clock clock;

    public CatalogPublicationService(
            JdbcCatalogStore store,
            CatalogValidator validator,
            CatalogSnapshotLoader snapshots,
            SalesChannelLookup channels,
            Clock clock,
            AuditRecorder audit) {
        this.store = store;
        this.validator = validator;
        this.snapshots = snapshots;
        this.channels = channels;
        this.audit = audit;
        this.clock = clock;
    }

    /** Validates without publishing, so an operator can see problems before committing. */
    @Transactional(readOnly = true)
    public ValidationFinding.Report validate(UUID tenantId, UUID brandId, UUID catalogId) {
        requireOwnership(tenantId, brandId, catalogId);
        return validator.validate(snapshots.load(tenantId, brandId, catalogId));
    }

    /**
     * The content hash the draft would publish as right now, without writing
     * anything (operations gap map row {@code 4.6}).
     *
     * <p>Composes exactly the pieces {@link #publish} already uses on its own draft
     * -- {@code snapshots.toPublicationItems} and {@link #contentHashOf} -- and stops
     * before the part that writes a row. A channel card compares this against the hash
     * of its own last {@code PUBLISHED} history entry to answer "does the draft differ
     * from what is live", which nothing before this method could answer without
     * actually publishing to find out.
     *
     * <p>One hash per live channel, because the channels no longer publish the same
     * items: an image that belongs to a channel is published to that channel alone
     * (ADR 0138 step 4), so the draft hashes differently for each channel that has such
     * an image. {@link DraftPreview#contentHash()} is the channel-agnostic draft;
     * {@link DraftPreview#contentHashFor} is the one to compare a channel's live hash
     * with.
     */
    @Transactional(readOnly = true)
    public DraftPreview previewDraft(UUID tenantId, UUID brandId, UUID catalogId) {
        requireOwnership(tenantId, brandId, catalogId);
        CatalogValidator.Snapshot snapshot = snapshots.load(tenantId, brandId, catalogId);
        List<PublicationItem> items = snapshots.toPublicationItems(snapshot);

        Map<String, String> byChannel = new TreeMap<>();
        for (String code : store.channelsWithLivePublication(tenantId, brandId)) {
            channels.byCode(tenantId, code)
                    .ifPresent(channel -> byChannel.put(
                            code,
                            contentHashOf(snapshots
                                    .toPublicationItems(snapshot, tenantId, brandId, channel)
                                    .items())));
        }
        return new DraftPreview(contentHashOf(items), items.size(), byChannel);
    }

    /**
     * What the draft would hash and how many items it carries, as of right now.
     *
     * @param contentHash the channel-agnostic draft
     * @param channelContentHashes the hash the draft would publish as on each channel that has a
     *     live menu, by channel code
     */
    public record DraftPreview(String contentHash, int itemCount, Map<String, String> channelContentHashes) {

        public DraftPreview {
            channelContentHashes = Map.copyOf(channelContentHashes);
        }

        /** The hash to compare {@code channelCode}'s live publication with. */
        public String contentHashFor(String channelCode) {
            return channelContentHashes.getOrDefault(channelCode, contentHash);
        }
    }

    /**
     * Snapshots, validates, and — if clean — makes the result the live menu.
     *
     * <p>A rejected publication is still recorded. An operator asking "why did
     * publishing fail an hour ago" needs the report to still exist, and a
     * rejection that leaves no row is a support conversation with no evidence.
     */
    @Transactional
    public PublicationResult publish(
            UUID tenantId, UUID brandId, UUID catalogId, String channel, @Nullable UUID actorId) {

        requireOwnership(tenantId, brandId, catalogId);
        SalesChannel registered = requireRegisteredChannel(tenantId, channel);

        CatalogValidator.Snapshot snapshot = snapshots.load(tenantId, brandId, catalogId);

        // The channel's own items, not the draft's: its images over the item's own and none
        // that belong to another channel (ADR 0138 step 4), the very list the channel preview
        // draws. An image the channel chose that is no longer showable stops the publication
        // with the catalog's own blockers, rather than reaching a customer as a broken picture.
        CatalogSnapshotLoader.ChannelItems channelItems =
                snapshots.toPublicationItems(snapshot, tenantId, brandId, registered);
        List<PublicationItem> items = channelItems.items();
        ValidationFinding.Report catalogReport = validator.validate(snapshot);
        ValidationFinding.Report report = channelItems.findings().isEmpty()
                ? catalogReport
                : new ValidationFinding.Report(java.util.stream.Stream.concat(
                                catalogReport.findings().stream(), channelItems.findings().stream())
                        .toList());
        String contentHash = hash(items);
        UUID publicationId = UUID.randomUUID();
        Instant now = clock.instant();

        if (!report.publishable()) {
            store.insertPublication(
                    publicationId,
                    tenantId,
                    brandId,
                    catalogId,
                    channel,
                    PublicationStatus.REJECTED,
                    contentHash,
                    report,
                    actorId,
                    now,
                    null);
            log.info(
                    "Catalog {} publication rejected with {} blockers",
                    catalogId,
                    report.blockers().size());
            return new PublicationResult(publicationId, PublicationStatus.REJECTED, contentHash, report);
        }

        store.insertPublication(
                publicationId,
                tenantId,
                brandId,
                catalogId,
                channel,
                PublicationStatus.READY,
                contentHash,
                report,
                actorId,
                now,
                null);
        store.insertPublicationItems(publicationId, tenantId, brandId, items);

        // Retire the outgoing publication before promoting this one. The partial
        // unique index permits only one PUBLISHED row per brand and channel, so
        // doing it the other way round would fail on the index — which is the
        // protection working, and the reason the order here is not arbitrary.
        // Read before the outgoing publication is retired: it is the "before" of this change.
        Map<String, Object> outgoing = liveSnapshot(tenantId, brandId, channel);
        store.retireActivePublication(tenantId, brandId, channel, now);
        store.activatePublication(publicationId, now);

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("channel", channel);
        incoming.put("publicationId", publicationId.toString());
        incoming.put("catalogId", catalogId.toString());
        incoming.put("contentHash", contentHash);
        incoming.put("itemCount", items.size());
        recordAudit(
                AuditFact.of("catalog.published", AuditClass.BUSINESS),
                actorId,
                tenantId,
                brandId,
                publicationId,
                "Catalog published",
                outgoing,
                incoming);

        log.info("Catalog {} published as {} ({} items)", catalogId, publicationId, items.size());
        return new PublicationResult(publicationId, PublicationStatus.PUBLISHED, contentHash, report);
    }

    /**
     * Makes a previous snapshot live again.
     *
     * <p>Republishes rather than editing history: the rolled-back-to publication
     * keeps its own id and content, and the rollback is visible as an event in
     * the sequence rather than as a menu that silently changed.
     */
    @Transactional
    public PublicationResult rollbackTo(UUID tenantId, UUID brandId, UUID publicationId, @Nullable UUID actorId) {
        var target = store.findPublication(tenantId, brandId, publicationId)
                .orElseThrow(() -> new IllegalArgumentException("No such publication"));

        if (target.status() == PublicationStatus.REJECTED) {
            // Rolling back to a snapshot that never passed validation would put
            // a menu live that we already know is broken.
            throw new IllegalStateException("Cannot roll back to a rejected publication");
        }

        // The publication's own channel, not one the caller names. Taking a
        // channel parameter here meant a caller could retire the storefront's
        // live menu and activate a publication belonging to a different channel,
        // leaving customers with no menu and the other channel with two.
        String channel = target.channel();

        Instant now = clock.instant();
        Map<String, Object> outgoing = liveSnapshot(tenantId, brandId, channel);
        store.retireActivePublication(tenantId, brandId, channel, now);
        store.activatePublication(publicationId, now);

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("channel", channel);
        incoming.put("publicationId", publicationId.toString());
        incoming.put("catalogId", target.catalogId().toString());
        incoming.put("contentHash", target.contentHash());
        recordAudit(
                AuditFact.of("catalog.publication.rolled_back", AuditClass.BUSINESS),
                actorId,
                tenantId,
                brandId,
                publicationId,
                "Catalog rolled back to an earlier publication",
                outgoing,
                incoming);

        log.info("Rolled brand {} back to publication {}", brandId, publicationId);
        return new PublicationResult(
                publicationId,
                PublicationStatus.PUBLISHED,
                target.contentHash(),
                new ValidationFinding.Report(List.of()));
    }

    /** The publication live on this channel right now, or nothing for the first one. */
    private Map<String, Object> liveSnapshot(UUID tenantId, UUID brandId, String channel) {
        Map<String, Object> live = new LinkedHashMap<>();
        store.findActivePublicationId(tenantId, brandId, channel)
                .flatMap(id -> store.findPublication(tenantId, brandId, id))
                .ifPresent(row -> {
                    live.put("channel", channel);
                    live.put("publicationId", row.id().toString());
                    live.put("catalogId", row.catalogId().toString());
                    live.put("contentHash", row.contentHash());
                });
        return live;
    }

    /**
     * One fact per write, in the caller's transaction. A publication made by no signed-in person
     * (the onboarding sample menu) is recorded as the job it is; a person's needs a reason, and
     * the console has no field for one, so it is a plain statement of the action.
     */
    private void recordAudit(
            AuditFact.Builder fact,
            @Nullable UUID actorId,
            UUID tenantId,
            UUID brandId,
            UUID publicationId,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(fact.by(
                        actorId == null
                                ? ActorRef.systemJob("catalog-publication")
                                : ActorRef.user(actorId.toString(), null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("catalog.publication", publicationId)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(publicationId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> activePublicationId(UUID tenantId, UUID brandId, String channel) {
        return store.findActivePublicationId(tenantId, brandId, channel);
    }

    /**
     * ADR 0036 corrects ADR 0016: the publication channel is a registered sales
     * channel, not free text.
     *
     * <p>V0020 adds the foreign key, so this check exists for the message rather
     * than the protection — without it the operator receives a constraint
     * violation naming {@code fk_publication_channel}. The archived case is only
     * catchable here: the database can tell that a channel exists and not that a
     * tenant stopped selling on it, and publishing a menu to an archived channel
     * produces a live publication no customer can ever reach.
     */
    private SalesChannel requireRegisteredChannel(UUID tenantId, String channel) {
        SalesChannel registered = channels.byCode(tenantId, channel)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No sales channel \"%s\" is registered for this tenant".formatted(channel)));
        if (registered.status() == SalesChannel.Status.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Sales channel \"%s\" is archived and cannot receive a publication".formatted(channel));
        }
        return registered;
    }

    /**
     * Refuses a catalog belonging to another brand.
     *
     * <p>The composite foreign key on {@code publications} already makes this
     * impossible, so this check exists for the message rather than the
     * protection: without it the caller receives a constraint-violation stack
     * trace naming {@code fk_publication_catalog}, which tells an operator
     * nothing about what they did wrong.
     */
    private void requireOwnership(UUID tenantId, UUID brandId, UUID catalogId) {
        if (!store.catalogBelongsTo(tenantId, brandId, catalogId)) {
            throw new IllegalArgumentException("Catalog %s does not belong to brand %s".formatted(catalogId, brandId));
        }
    }

    /**
     * The content fingerprint for a set of items, reproducible across processes.
     *
     * <p>Exposed so a test can prove the hash depends only on content and not on
     * how the maps were built — the defect that made it unreproducible across
     * processes in the first place.
     *
     * <p>Every source of ordering is pinned, because the schema comment promises
     * that two publications with the same hash are the same menu — and a rollback
     * check, and every downstream cache, believes it.
     *
     * <p>Items are sorted, and each item's content is written in canonical form
     * with map keys sorted. The first version hashed {@code Map.toString()}, and
     * the maps were built with {@code Map.of(...)}, whose iteration order is
     * randomised per JVM by an internal salt. The same unchanged menu therefore
     * hashed differently after a restart, and the test that was supposed to catch
     * it compared two hashes computed in one process, where the salt is constant.
     */
    public static String contentHashOf(List<PublicationItem> items) {
        return hash(items);
    }

    private static String hash(List<PublicationItem> items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            items.stream()
                    .sorted(java.util.Comparator.comparing(
                                    (PublicationItem item) -> item.entityType().name())
                            .thenComparing(item -> item.entityId().toString()))
                    .forEach(item ->
                            digest.update((item.entityType() + ":" + item.entityId() + ":" + canonical(item.content()))
                                    .getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    /**
     * Renders a value with every map key sorted, so the text depends only on
     * content and never on how the map happened to be built.
     */
    private static String canonical(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(String::valueOf)))
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof java.util.List<?> list) {
            // List order is meaningful — it is the order the menu is shown in —
            // so it is preserved rather than sorted.
            return list.stream()
                    .map(CatalogPublicationService::canonical)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }

    public record PublicationResult(
            UUID publicationId, PublicationStatus status, String contentHash, ValidationFinding.Report report) {}
}
