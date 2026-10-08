package uz.horecaos.platform.content.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.domain.ContentPlacement;
import uz.horecaos.platform.content.domain.ItemState;
import uz.horecaos.platform.content.domain.MediaRole;

/**
 * {@code content.items}, {@code item_drafts}, {@code item_revisions}, {@code revision_media} and
 * {@code allowed_hosts} (V0534).
 *
 * <p>Every statement names the tenant and the brand, in the {@code WHERE} and not in a filter
 * applied afterwards: an item id is a UUID a caller may have received from anywhere, and a lookup
 * that matched on the id alone would answer another tenant's item. There is no {@code UPDATE} or
 * {@code DELETE} on a revision here and the application role has no grant for either; the class
 * cannot grow one without a migration.
 */
@Repository
public class JdbcContentStore {

    private static final String ITEM_COLUMNS = """
            i.id, i.tenant_id, i.brand_id, i.kind, i.placement, i.internal_name, i.display_title, i.slug, i.state,
            i.live_revision_no, i.sort_key, i.version, i.created_by, i.created_at, i.updated_at
            """;

    private static final String REVISION_COLUMNS = """
            r.item_id, r.revision_no, r.document::text AS document, r.channel_codes, r.starts_at, r.ends_at,
            r.promotion_id, r.location_id, r.content_hash, r.published_by, r.published_at
            """;

    private final JdbcClient jdbc;

    public JdbcContentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- items

    public void insertItem(ItemRow item) {
        jdbc.sql("""
                INSERT INTO content.items
                    (id, tenant_id, brand_id, kind, placement, internal_name, display_title, slug, state,
                     live_revision_no, sort_key, version, created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :kind, :placement, :internalName, :displayTitle, :slug, :state,
                        NULL, :sortKey, 1, :createdBy, :now, :now)
                """)
                .param("id", item.id())
                .param("tenantId", item.tenantId())
                .param("brandId", item.brandId())
                .param("kind", item.kind().name())
                .param(
                        "placement",
                        item.placement() == null ? null : item.placement().name())
                .param("internalName", item.internalName())
                .param("displayTitle", item.displayTitle())
                .param("slug", item.slug())
                .param("state", item.state().name())
                .param("sortKey", item.sortKey())
                .param("createdBy", item.createdBy())
                .param("now", utc(item.createdAt()))
                .update();
    }

    public Optional<ItemRow> findItem(UUID tenantId, UUID brandId, UUID itemId) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM content.items i"
                        + " WHERE i.tenant_id = :tenantId AND i.brand_id = :brandId AND i.id = :id")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", itemId)
                .query(JdbcContentStore::mapItem)
                .optional();
    }

    /** The item locked for the rest of the transaction: the serialisation point of two concurrent publishes. */
    public Optional<ItemRow> lockItem(UUID tenantId, UUID brandId, UUID itemId) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM content.items i"
                        + " WHERE i.tenant_id = :tenantId AND i.brand_id = :brandId AND i.id = :id FOR UPDATE")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", itemId)
                .query(JdbcContentStore::mapItem)
                .optional();
    }

    /**
     * Items for the console, in the order a placement is read in: placement, sort key, creation
     * time, id. Keyset-paged on that tuple, so a list that changes while it is paged neither skips
     * nor repeats a row.
     *
     * @param after the last row of the previous page, or null for the first
     */
    public List<ItemRow> listItems(
            UUID tenantId,
            UUID brandId,
            @Nullable ContentKind kind,
            @Nullable ContentPlacement placement,
            @Nullable ItemState state,
            boolean includeArchived,
            @Nullable ItemRow after,
            int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + ITEM_COLUMNS + " FROM content.items i"
                + " WHERE i.tenant_id = :tenantId AND i.brand_id = :brandId");
        if (kind != null) {
            sql.append(" AND i.kind = :kind");
        }
        if (placement != null) {
            sql.append(" AND i.placement = :placement");
        }
        if (state != null) {
            sql.append(" AND i.state = :state");
        } else if (!includeArchived) {
            sql.append(" AND i.state <> 'ARCHIVED'");
        }
        if (after != null) {
            sql.append(" AND (COALESCE(i.placement, '~'), i.sort_key, i.created_at, i.id)"
                    + " > (COALESCE(:afterPlacement, '~'), :afterSortKey, :afterCreatedAt, :afterId)");
        }
        sql.append(" ORDER BY COALESCE(i.placement, '~'), i.sort_key, i.created_at, i.id LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("limit", limit);
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        if (placement != null) {
            spec = spec.param("placement", placement.name());
        }
        if (state != null) {
            spec = spec.param("state", state.name());
        }
        if (after != null) {
            spec = spec.param(
                            "afterPlacement",
                            after.placement() == null ? null : after.placement().name())
                    .param("afterSortKey", after.sortKey())
                    .param("afterCreatedAt", utc(after.createdAt()))
                    .param("afterId", after.id());
        }
        return spec.query(JdbcContentStore::mapItem).list();
    }

    /** The next place at the end of a placement. */
    public int nextSortKey(UUID tenantId, UUID brandId, @Nullable ContentPlacement placement) {
        Integer highest = jdbc.sql("""
                SELECT max(sort_key) FROM content.items
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND placement IS NOT DISTINCT FROM :placement AND state <> 'ARCHIVED'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("placement", placement == null ? null : placement.name())
                .query(Integer.class)
                .optional()
                .orElse(null);
        return highest == null ? 10 : highest + 10;
    }

    public int countNonArchived(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT count(*) FROM content.items
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND state <> 'ARCHIVED'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Integer.class)
                .single();
    }

    /** Items in front of customers (stored state PUBLISHED) in a placement, whatever their window. */
    public int countPublishedInPlacement(
            UUID tenantId, UUID brandId, ContentPlacement placement, @Nullable UUID excludingItem) {
        return jdbc.sql("""
                SELECT count(*) FROM content.items
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND placement = :placement
                  AND state = 'PUBLISHED' AND (CAST(:excluded AS uuid) IS NULL OR id <> CAST(:excluded AS uuid))
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("placement", placement.name())
                .param("excluded", excludingItem)
                .query(Integer.class)
                .single();
    }

    /**
     * Writes a change to the item's own columns, if nobody changed it since {@code expectedVersion}.
     *
     * @return false when the version has moved on
     */
    public boolean updateItem(
            UUID tenantId,
            UUID brandId,
            UUID itemId,
            int expectedVersion,
            ItemState state,
            @Nullable Integer liveRevisionNo,
            @Nullable String internalName,
            @Nullable String displayTitle,
            Instant now) {
        return jdbc.sql("""
                UPDATE content.items
                SET state = :state,
                    live_revision_no = :liveRevisionNo,
                    internal_name = COALESCE(:internalName, internal_name),
                    display_title = COALESCE(:displayTitle, display_title),
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND version = :expected
                """)
                        .param("state", state.name())
                        .param("liveRevisionNo", liveRevisionNo)
                        .param("internalName", internalName)
                        .param("displayTitle", displayTitle)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", itemId)
                        .param("expected", expectedVersion)
                        .update()
                == 1;
    }

    /** The order of a placement is a number on the item and is not a revision: no version bump. */
    public void setSortKey(UUID tenantId, UUID brandId, UUID itemId, int sortKey, Instant now) {
        jdbc.sql("""
                UPDATE content.items SET sort_key = :sortKey, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("sortKey", sortKey)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", itemId)
                .update();
    }

    /** The non-archived items of a placement, in the order they are read in. */
    public List<ItemRow> placementItems(UUID tenantId, UUID brandId, ContentPlacement placement) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM content.items i"
                        + " WHERE i.tenant_id = :tenantId AND i.brand_id = :brandId AND i.placement = :placement"
                        + " AND i.state <> 'ARCHIVED' ORDER BY i.sort_key, i.created_at, i.id")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("placement", placement.name())
                .query(JdbcContentStore::mapItem)
                .list();
    }

    /** {@link #placementItems} locked for the transaction, in id order so two reorders cannot deadlock. */
    public List<ItemRow> placementItemsForUpdate(UUID tenantId, UUID brandId, ContentPlacement placement) {
        jdbc.sql("""
                SELECT id FROM content.items
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND placement = :placement AND state <> 'ARCHIVED'
                ORDER BY id FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("placement", placement.name())
                .query(UUID.class)
                .list();
        return placementItems(tenantId, brandId, placement);
    }

    // --------------------------------------------------------------- drafts

    public Optional<DraftRow> findDraft(UUID tenantId, UUID brandId, UUID itemId) {
        return jdbc.sql("""
                SELECT item_id, based_on_revision_no, document::text AS document, channel_codes, starts_at, ends_at,
                       promotion_id, location_id, updated_by, updated_at, version
                FROM content.item_drafts
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND item_id = :itemId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("itemId", itemId)
                .query(JdbcContentStore::mapDraft)
                .optional();
    }

    /** Inserts or replaces the one draft of an item. */
    public void upsertDraft(UUID tenantId, UUID brandId, DraftRow draft) {
        jdbc.sql("""
                INSERT INTO content.item_drafts
                    (item_id, tenant_id, brand_id, based_on_revision_no, document, channel_codes, starts_at, ends_at,
                     promotion_id, location_id, updated_by, updated_at, version)
                VALUES (:itemId, :tenantId, :brandId, :basedOn, CAST(:document AS jsonb),
                        CAST(:channelCodes AS varchar[]), :startsAt, :endsAt,
                        :promotionId, :locationId, :updatedBy, :updatedAt, 1)
                ON CONFLICT (item_id) DO UPDATE SET
                    based_on_revision_no = EXCLUDED.based_on_revision_no,
                    document = EXCLUDED.document,
                    channel_codes = EXCLUDED.channel_codes,
                    starts_at = EXCLUDED.starts_at,
                    ends_at = EXCLUDED.ends_at,
                    promotion_id = EXCLUDED.promotion_id,
                    location_id = EXCLUDED.location_id,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at,
                    version = content.item_drafts.version + 1
                """)
                .param("itemId", draft.itemId())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("basedOn", draft.basedOnRevisionNo())
                .param("document", draft.documentJson())
                .param("channelCodes", draft.channelCodes().toArray(String[]::new))
                .param("startsAt", utc(draft.startsAt()))
                .param("endsAt", utc(draft.endsAt()))
                .param("promotionId", draft.promotionId())
                .param("locationId", draft.locationId())
                .param("updatedBy", draft.updatedBy())
                .param("updatedAt", utc(draft.updatedAt()))
                .update();
    }

    public boolean deleteDraft(UUID tenantId, UUID brandId, UUID itemId) {
        return jdbc.sql("""
                DELETE FROM content.item_drafts
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND item_id = :itemId
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("itemId", itemId)
                        .update()
                > 0;
    }

    /** The drafts of several items of one brand, in one read. */
    public List<DraftRow> findDrafts(UUID tenantId, UUID brandId, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT item_id, based_on_revision_no, document::text AS document, channel_codes, starts_at, ends_at,
                       promotion_id, location_id, updated_by, updated_at, version
                FROM content.item_drafts
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND item_id = ANY(:itemIds)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("itemIds", itemIds.toArray(UUID[]::new))
                .query(JdbcContentStore::mapDraft)
                .list();
    }

    // ------------------------------------------------------------ revisions

    public int latestRevisionNo(UUID tenantId, UUID brandId, UUID itemId) {
        Integer latest = jdbc.sql("""
                SELECT max(revision_no) FROM content.item_revisions
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND item_id = :itemId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("itemId", itemId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return latest == null ? 0 : latest;
    }

    public void insertRevision(UUID tenantId, UUID brandId, RevisionRow revision) {
        jdbc.sql("""
                INSERT INTO content.item_revisions
                    (item_id, revision_no, tenant_id, brand_id, document, channel_codes, starts_at, ends_at,
                     promotion_id, location_id, content_hash, published_by, published_at)
                VALUES (:itemId, :revisionNo, :tenantId, :brandId, CAST(:document AS jsonb),
                        CAST(:channelCodes AS varchar[]), :startsAt, :endsAt,
                        :promotionId, :locationId, :contentHash, :publishedBy, :publishedAt)
                """)
                .param("itemId", revision.itemId())
                .param("revisionNo", revision.revisionNo())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("document", revision.documentJson())
                .param("channelCodes", revision.channelCodes().toArray(String[]::new))
                .param("startsAt", utc(revision.startsAt()))
                .param("endsAt", utc(revision.endsAt()))
                .param("promotionId", revision.promotionId())
                .param("locationId", revision.locationId())
                .param("contentHash", revision.contentHash())
                .param("publishedBy", revision.publishedBy())
                .param("publishedAt", utc(revision.publishedAt()))
                .update();
    }

    public void insertRevisionMedia(
            UUID tenantId, UUID itemId, int revisionNo, UUID assetId, MediaRole role, int position) {
        jdbc.sql("""
                INSERT INTO content.revision_media (item_id, revision_no, tenant_id, media_asset_id, role, position)
                VALUES (:itemId, :revisionNo, :tenantId, :assetId, :role, :position)
                ON CONFLICT (item_id, revision_no, media_asset_id, role) DO NOTHING
                """)
                .param("itemId", itemId)
                .param("revisionNo", revisionNo)
                .param("tenantId", tenantId)
                .param("assetId", assetId)
                .param("role", role.name())
                .param("position", position)
                .update();
    }

    public Optional<RevisionRow> findRevision(UUID tenantId, UUID brandId, UUID itemId, int revisionNo) {
        return jdbc.sql("SELECT " + REVISION_COLUMNS + " FROM content.item_revisions r"
                        + " WHERE r.tenant_id = :tenantId AND r.brand_id = :brandId AND r.item_id = :itemId"
                        + " AND r.revision_no = :revisionNo")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("itemId", itemId)
                .param("revisionNo", revisionNo)
                .query(JdbcContentStore::mapRevision)
                .optional();
    }

    /** Newest first. Bounded by the number of times one item can be published, which is human. */
    public List<RevisionRow> listRevisions(UUID tenantId, UUID brandId, UUID itemId) {
        return jdbc.sql("SELECT " + REVISION_COLUMNS + " FROM content.item_revisions r"
                        + " WHERE r.tenant_id = :tenantId AND r.brand_id = :brandId AND r.item_id = :itemId"
                        + " ORDER BY r.revision_no DESC LIMIT 500")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("itemId", itemId)
                .query(JdbcContentStore::mapRevision)
                .list();
    }

    /** The live revisions of several items of one brand, in one read. */
    public List<RevisionRow> findLiveRevisions(UUID tenantId, UUID brandId, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + REVISION_COLUMNS + " FROM content.item_revisions r"
                        + " JOIN content.items i ON i.id = r.item_id AND i.live_revision_no = r.revision_no"
                        + " AND i.tenant_id = r.tenant_id"
                        + " WHERE r.tenant_id = :tenantId AND r.brand_id = :brandId AND r.item_id = ANY(:itemIds)")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("itemIds", itemIds.toArray(UUID[]::new))
                .query(JdbcContentStore::mapRevision)
                .list();
    }

    public List<UUID> revisionAssetIds(UUID tenantId, UUID itemId, int revisionNo) {
        return jdbc.sql("""
                SELECT DISTINCT media_asset_id FROM content.revision_media
                WHERE tenant_id = :tenantId AND item_id = :itemId AND revision_no = :revisionNo
                """)
                .param("tenantId", tenantId)
                .param("itemId", itemId)
                .param("revisionNo", revisionNo)
                .query(UUID.class)
                .list();
    }

    // -------------------------------------------------------------- the read

    /**
     * The brand's stored-published items with their live revisions, for the assembler. The window
     * is NOT applied here: it is decided in code against the injected clock, so one assembler
     * serves the live read and the preview, and a test controls time without touching SQL.
     *
     * @param placements the placements wanted; empty for every item with a live revision (editorial)
     */
    public List<LiveItem> publishedItems(UUID tenantId, UUID brandId, Collection<ContentPlacement> placements) {
        String filter = placements.isEmpty() ? "" : " AND i.placement = ANY(CAST(:placements AS varchar[]))";
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT " + ITEM_COLUMNS + ", " + REVISION_COLUMNS
                        + " FROM content.items i JOIN content.item_revisions r"
                        + " ON r.item_id = i.id AND r.revision_no = i.live_revision_no AND r.tenant_id = i.tenant_id"
                        + " WHERE i.tenant_id = :tenantId AND i.brand_id = :brandId AND i.state = 'PUBLISHED'"
                        + filter
                        + " ORDER BY COALESCE(i.placement, '~'), i.sort_key, i.created_at, i.id")
                .param("tenantId", tenantId)
                .param("brandId", brandId);
        if (!placements.isEmpty()) {
            spec = spec.param("placements", placements.stream().map(Enum::name).toArray(String[]::new));
        }
        return spec.query((row, number) -> new LiveItem(mapItem(row, number), mapRevision(row, number)))
                .list();
    }

    /**
     * Every non-archived item with its draft and its live revision, for the preview: what the
     * storefront would show if every draft were published now.
     */
    public List<PreviewItem> previewItems(UUID tenantId, UUID brandId, Collection<ContentPlacement> placements) {
        String filter = placements.isEmpty() ? "" : " AND i.placement = ANY(CAST(:placements AS varchar[]))";
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT " + ITEM_COLUMNS + ","
                        + " d.document::text AS d_document, d.channel_codes AS d_channel_codes,"
                        + " d.starts_at AS d_starts_at, d.ends_at AS d_ends_at,"
                        + " d.promotion_id AS d_promotion_id, d.location_id AS d_location_id,"
                        + " d.based_on_revision_no AS d_based_on, d.updated_by AS d_updated_by,"
                        + " d.updated_at AS d_updated_at, d.version AS d_version,"
                        + " r.document::text AS document, r.channel_codes, r.starts_at, r.ends_at,"
                        + " r.promotion_id, r.location_id, r.content_hash, r.published_by, r.published_at,"
                        + " r.item_id, r.revision_no"
                        + " FROM content.items i"
                        + " LEFT JOIN content.item_drafts d ON d.item_id = i.id AND d.tenant_id = i.tenant_id"
                        + " LEFT JOIN content.item_revisions r ON r.item_id = i.id"
                        + " AND r.revision_no = i.live_revision_no AND r.tenant_id = i.tenant_id"
                        + " WHERE i.tenant_id = :tenantId AND i.brand_id = :brandId AND i.state <> 'ARCHIVED'"
                        + filter
                        + " ORDER BY COALESCE(i.placement, '~'), i.sort_key, i.created_at, i.id")
                .param("tenantId", tenantId)
                .param("brandId", brandId);
        if (!placements.isEmpty()) {
            spec = spec.param("placements", placements.stream().map(Enum::name).toArray(String[]::new));
        }
        return spec.query((row, number) -> {
                    ItemRow item = mapItem(row, number);
                    DraftRow draft = row.getString("d_document") == null
                            ? null
                            : new DraftRow(
                                    item.id(),
                                    (Integer) row.getObject("d_based_on"),
                                    row.getString("d_document"),
                                    codes(row, "d_channel_codes"),
                                    instant(row, "d_starts_at"),
                                    instant(row, "d_ends_at"),
                                    row.getObject("d_promotion_id", UUID.class),
                                    row.getObject("d_location_id", UUID.class),
                                    row.getString("d_updated_by"),
                                    row.getObject("d_updated_at", OffsetDateTime.class)
                                            .toInstant(),
                                    row.getInt("d_version"));
                    RevisionRow live = row.getString("document") == null ? null : mapRevision(row, number);
                    return new PreviewItem(item, draft, live);
                })
                .list();
    }

    /**
     * The assets used by every live revision of the brand's other items: the candidates for "how
     * many videos are live" (the caller asks media which of them are videos).
     */
    public List<UUID> liveRevisionAssetIds(UUID tenantId, UUID brandId, @Nullable UUID excludingItem) {
        return jdbc.sql("""
                SELECT DISTINCT m.media_asset_id
                FROM content.items i
                JOIN content.revision_media m
                  ON m.item_id = i.id AND m.revision_no = i.live_revision_no AND m.tenant_id = i.tenant_id
                WHERE i.tenant_id = :tenantId AND i.brand_id = :brandId AND i.state = 'PUBLISHED'
                  AND (CAST(:excluded AS uuid) IS NULL OR i.id <> CAST(:excluded AS uuid))
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("excluded", excludingItem)
                .query(UUID.class)
                .list();
    }

    // -------------------------------------------------------- allowed hosts

    public List<AllowedHostRow> allowedHosts(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT host, created_by, created_at FROM content.allowed_hosts
                WHERE tenant_id = :tenantId AND brand_id = :brandId ORDER BY host
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new AllowedHostRow(
                        row.getString("host"),
                        row.getString("created_by"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** @return false when the host was already on the list */
    public boolean addAllowedHost(UUID tenantId, UUID brandId, String host, String createdBy, Instant now) {
        return jdbc.sql("""
                INSERT INTO content.allowed_hosts (tenant_id, brand_id, host, created_by, created_at)
                VALUES (:tenantId, :brandId, :host, :createdBy, :now)
                ON CONFLICT (tenant_id, brand_id, host) DO NOTHING
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("host", host)
                        .param("createdBy", createdBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** @return false when the host was not on the list */
    public boolean removeAllowedHost(UUID tenantId, UUID brandId, String host) {
        return jdbc.sql("""
                DELETE FROM content.allowed_hosts
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND host = :host
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("host", host)
                        .update()
                == 1;
    }

    // -------------------------------------------------------------- mapping

    private static ItemRow mapItem(ResultSet row, int number) throws SQLException {
        String placement = row.getString("placement");
        return new ItemRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                ContentKind.valueOf(row.getString("kind")),
                placement == null ? null : ContentPlacement.valueOf(placement),
                row.getString("internal_name"),
                row.getString("display_title"),
                row.getString("slug"),
                ItemState.valueOf(row.getString("state")),
                (Integer) row.getObject("live_revision_no"),
                row.getInt("sort_key"),
                row.getInt("version"),
                row.getString("created_by"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static DraftRow mapDraft(ResultSet row, int number) throws SQLException {
        return new DraftRow(
                row.getObject("item_id", UUID.class),
                (Integer) row.getObject("based_on_revision_no"),
                row.getString("document"),
                codes(row, "channel_codes"),
                instant(row, "starts_at"),
                instant(row, "ends_at"),
                row.getObject("promotion_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("updated_by"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                row.getInt("version"));
    }

    private static RevisionRow mapRevision(ResultSet row, int number) throws SQLException {
        return new RevisionRow(
                row.getObject("item_id", UUID.class),
                row.getInt("revision_no"),
                row.getString("document"),
                codes(row, "channel_codes"),
                instant(row, "starts_at"),
                instant(row, "ends_at"),
                row.getObject("promotion_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("content_hash"),
                row.getString("published_by"),
                row.getObject("published_at", OffsetDateTime.class).toInstant());
    }

    private static List<String> codes(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<String> codes = new ArrayList<>(values.length);
        for (Object value : values) {
            codes.add(String.valueOf(value));
        }
        return List.copyOf(codes);
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ----------------------------------------------------------------- rows

    public record ItemRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            ContentKind kind,
            @Nullable ContentPlacement placement,
            String internalName,
            String displayTitle,
            @Nullable String slug,
            ItemState state,
            @Nullable Integer liveRevisionNo,
            int sortKey,
            int version,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    public record DraftRow(
            UUID itemId,
            @Nullable Integer basedOnRevisionNo,
            String documentJson,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId,
            String updatedBy,
            Instant updatedAt,
            int version) {

        public DraftRow {
            channelCodes = List.copyOf(channelCodes);
        }
    }

    public record RevisionRow(
            UUID itemId,
            int revisionNo,
            String documentJson,
            List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId,
            String contentHash,
            String publishedBy,
            Instant publishedAt) {

        public RevisionRow {
            channelCodes = List.copyOf(channelCodes);
        }
    }

    /** An item with the revision it is live on. */
    public record LiveItem(ItemRow item, RevisionRow revision) {}

    /** An item with its draft and its live revision, either of which may be absent. */
    public record PreviewItem(
            ItemRow item,
            @Nullable DraftRow draft,
            @Nullable RevisionRow live) {}

    public record AllowedHostRow(String host, String createdBy, Instant createdAt) {}
}
