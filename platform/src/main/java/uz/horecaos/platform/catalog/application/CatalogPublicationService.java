package uz.horecaos.platform.catalog.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * Turns a draft into a live menu (ADR 0016).
 *
 * <p>Three steps, in one transaction: snapshot, validate, activate. The snapshot
 * is a copy rather than a reference, which is the mechanism that stops a later
 * draft edit from changing what customers are already seeing — not a convention
 * anyone has to remember.
 */
@Service
public class CatalogPublicationService {

    private static final Logger log = LoggerFactory.getLogger(CatalogPublicationService.class);

    private final JdbcCatalogStore store;
    private final CatalogValidator validator;
    private final CatalogSnapshotLoader snapshots;
    private final SalesChannelLookup channels;
    private final Clock clock;

    public CatalogPublicationService(
            JdbcCatalogStore store,
            CatalogValidator validator,
            CatalogSnapshotLoader snapshots,
            SalesChannelLookup channels,
            Clock clock) {
        this.store = store;
        this.validator = validator;
        this.snapshots = snapshots;
        this.channels = channels;
        this.clock = clock;
    }

    /** Validates without publishing, so an operator can see problems before committing. */
    @Transactional(readOnly = true)
    public ValidationFinding.Report validate(UUID tenantId, UUID brandId, UUID catalogId) {
        requireOwnership(tenantId, brandId, catalogId);
        return validator.validate(snapshots.load(tenantId, brandId, catalogId));
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
        requireRegisteredChannel(tenantId, channel);

        CatalogValidator.Snapshot snapshot = snapshots.load(tenantId, brandId, catalogId);
        ValidationFinding.Report report = validator.validate(snapshot);

        List<PublicationItem> items = snapshots.toPublicationItems(snapshot);
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
        store.retireActivePublication(tenantId, brandId, channel, now);
        store.activatePublication(publicationId, now);

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
    public PublicationResult rollbackTo(UUID tenantId, UUID brandId, UUID publicationId) {
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
        store.retireActivePublication(tenantId, brandId, channel, now);
        store.activatePublication(publicationId, now);

        log.info("Rolled brand {} back to publication {}", brandId, publicationId);
        return new PublicationResult(
                publicationId,
                PublicationStatus.PUBLISHED,
                target.contentHash(),
                new ValidationFinding.Report(List.of()));
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
    private void requireRegisteredChannel(UUID tenantId, String channel) {
        SalesChannel registered = channels.byCode(tenantId, channel)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No sales channel \"%s\" is registered for this tenant".formatted(channel)));
        if (registered.status() == SalesChannel.Status.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Sales channel \"%s\" is archived and cannot receive a publication".formatted(channel));
        }
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
