package uz.qoida.platform.helpcenter.infrastructure.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.qoida.platform.helpcenter.domain.SupportContent.FaqCategory;
import uz.qoida.platform.helpcenter.domain.SupportContent.FaqEntry;
import uz.qoida.platform.helpcenter.domain.SupportContent.SocialLink;

/**
 * Reads a brand's published support content.
 *
 * <p>Both queries resolve text with the same fallback: the requested locale
 * first, then any other published translation, and never the authoring code. A
 * brand that has translated its FAQ into Uzbek but not English should show a
 * Russian-speaking customer the Uzbek answer rather than the string
 * "DELIVERY_HOURS" -- the first is imperfect and the second is broken.
 *
 * <p>Only PUBLISHED rows are ever returned. Draft help is half-written by
 * definition and archived help is wrong on purpose.
 */
@Component
public class JdbcSupportStore {

    private final JdbcClient jdbc;

    public JdbcSupportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every published category with its published entries, in authored order. */
    public List<FaqCategory> faq(UUID tenantId, UUID brandId, String locale) {
        // One query for the whole FAQ. A category-then-entries pair would be two
        // round trips for a screen that always shows all of it.
        //
        // DISTINCT ON picks one translation row per entity: the requested locale
        // when there is one, otherwise whichever sorts first. The ORDER BY is
        // what makes that deterministic rather than whatever the planner returns.
        record Row(UUID categoryId, String categoryCode, String categoryName, int categorySort,
                UUID entryId, String entryCode, String question, String answer, int entrySort) { }

        List<Row> rows = jdbc.sql("""
                SELECT c.id AS category_id, c.code AS category_code, c.sort_order AS category_sort,
                       ct.title AS category_name,
                       e.id AS entry_id, e.code AS entry_code, e.sort_order AS entry_sort,
                       et.title AS question, et.body AS answer
                FROM support.faq_categories c
                LEFT JOIN LATERAL (
                    SELECT t.title FROM support.faq_translations t
                    WHERE t.tenant_id = c.tenant_id AND t.entity_type = 'CATEGORY'
                      AND t.entity_id = c.id
                    ORDER BY (t.locale = :locale) DESC, t.locale
                    LIMIT 1
                ) ct ON true
                LEFT JOIN support.faq_entries e
                       ON e.category_id = c.id AND e.tenant_id = c.tenant_id
                      AND e.status = 'PUBLISHED'
                LEFT JOIN LATERAL (
                    SELECT t.title, t.body FROM support.faq_translations t
                    WHERE t.tenant_id = e.tenant_id AND t.entity_type = 'ENTRY'
                      AND t.entity_id = e.id
                    ORDER BY (t.locale = :locale) DESC, t.locale
                    LIMIT 1
                ) et ON true
                WHERE c.tenant_id = :tenantId AND c.brand_id = :brandId
                  AND c.status = 'PUBLISHED'
                ORDER BY c.sort_order, c.id, e.sort_order, e.id
                """)
                .param("tenantId", tenantId).param("brandId", brandId).param("locale", locale)
                .query((row, number) -> new Row(
                        row.getObject("category_id", UUID.class),
                        row.getString("category_code"),
                        row.getString("category_name"),
                        row.getInt("category_sort"),
                        row.getObject("entry_id", UUID.class),
                        row.getString("entry_code"),
                        row.getString("question"),
                        row.getString("answer"),
                        row.getInt("entry_sort")))
                .list();

        // LinkedHashMap, so the category order the SQL established survives.
        Map<UUID, List<FaqEntry>> entriesByCategory = new LinkedHashMap<>();
        Map<UUID, Row> categories = new LinkedHashMap<>();
        for (Row row : rows) {
            categories.putIfAbsent(row.categoryId(), row);
            entriesByCategory.computeIfAbsent(row.categoryId(), key -> new ArrayList<>());
            // A category with no published entries arrives as one row with null
            // entry columns, from the LEFT JOIN. It keeps its place and its
            // heading; dropping it would hide a section an operator published.
            if (row.entryId() != null && row.question() != null) {
                entriesByCategory.get(row.categoryId()).add(new FaqEntry(
                        row.entryId(), row.entryCode(), row.question(), row.answer(),
                        row.entrySort()));
            }
        }

        List<FaqCategory> result = new ArrayList<>(categories.size());
        for (Row row : categories.values()) {
            result.add(new FaqCategory(
                    row.categoryId(), row.categoryCode(),
                    // Untranslated in every locale. The code is never shown, so
                    // the heading is empty and the entries still read.
                    row.categoryName() == null ? "" : row.categoryName(),
                    row.categorySort(),
                    List.copyOf(entriesByCategory.get(row.categoryId()))));
        }
        return List.copyOf(result);
    }

    public List<SocialLink> socialLinks(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT id, platform, url, media_asset_id, sort_order
                FROM support.social_links
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND status = 'PUBLISHED'
                ORDER BY sort_order, platform
                """)
                .param("tenantId", tenantId).param("brandId", brandId)
                .query((row, number) -> {
                    UUID assetId = row.getObject("media_asset_id", UUID.class);
                    return new SocialLink(
                            row.getObject("id", UUID.class),
                            row.getString("platform"),
                            row.getString("url"),
                            // The same storefront media path the menu uses, so an
                            // operator's own icon resolves the same way a dish
                            // photo does and neither needs a token.
                            assetId == null
                                    ? null
                                    : "/api/v1/storefront/tenants/%s/media/%s"
                                            .formatted(tenantId, assetId),
                            row.getInt("sort_order"));
                })
                .list();
    }
}
