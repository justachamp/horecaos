package uz.horecaos.platform.support;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Preset comments as the schema holds them since V0678: a coded row and its wording in the
 * per-locale tables, nothing in three fixed columns. A test that needs a preset or an order line's
 * snapshot of one without going through the services writes it with these, so no fixture names a
 * column that no longer exists and none writes a wording to one table and forgets the other.
 */
public final class CommentPresetRows {

    private CommentPresetRows() {}

    /** A catalog preset (V0378) and its wording (V0430), the way the preset service leaves one. */
    public static void insertPreset(
            JdbcClient jdbc, UUID presetId, UUID tenantId, String code, Map<String, String> labels) {
        jdbc.sql("""
                INSERT INTO catalog.comment_presets (id, tenant_id, code, sort_order, status, version)
                VALUES (:id, :tenantId, :code, 0, 'ACTIVE', 1)
                """)
                .param("id", presetId)
                .param("tenantId", tenantId)
                .param("code", code)
                .update();
        labels.forEach((locale, label) -> jdbc.sql("""
                        INSERT INTO catalog.comment_preset_translations (tenant_id, preset_id, locale, label)
                        VALUES (:tenantId, :presetId, :locale, :label)
                        """)
                .param("tenantId", tenantId)
                .param("presetId", presetId)
                .param("locale", locale)
                .param("label", label)
                .update());
    }

    /** The wording of an order line's preset snapshot (V0433), one row a locale. */
    public static void insertSnapshotLabels(
            JdbcClient jdbc, UUID snapshotId, UUID tenantId, Map<String, String> labels) {
        labels.forEach((locale, label) -> jdbc.sql("""
                        INSERT INTO ordering.order_line_comment_preset_labels (
                            tenant_id, order_line_comment_preset_id, locale, label)
                        VALUES (:tenantId, :snapshotId, :locale, :label)
                        """)
                .param("tenantId", tenantId)
                .param("snapshotId", snapshotId)
                .param("locale", locale)
                .param("label", label)
                .update());
    }

    /** The platform triple's wording as a labels map, for the many fixtures that only need those three. */
    public static Map<String, String> triple(String ru, String uz, String en) {
        return Map.of("ru", ru, "uz-Latn", uz, "en", en);
    }
}
