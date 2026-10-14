-- ADR 0184 (row 10.9a): two columns, no new table.
--
-- 1. A template version can show the price of each item in the product list.
--
--    Delever has a «Показывать цены товаров» switch on its order-status templates and the console's
--    editor has no place for one. It changes what the merge variable {{productList}} renders as, so
--    it belongs to the version (the wording a customer reads) and not to the template (the key and
--    channel it is sent on): switching it on is a new version that is approved and activated like any
--    other, and the version a notification froze keeps the setting it was frozen with.
--
--    One value per version, repeated on each of its locale rows, because a version is "a set of rows,
--    one per locale" (NotificationTemplateService) and a switch that could differ between languages
--    would let a customer reading Uzbek see prices a customer reading Russian does not. The service
--    writes the same value on every row; it is not a per-locale choice.
--
--    Default false: every existing version keeps rendering exactly what it rendered.
ALTER TABLE notifications.template_versions
    ADD COLUMN show_item_prices boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN notifications.template_versions.show_item_prices IS
    'ADR 0184. Whether {{productList}} follows each item with its line total. One value per version, written on every locale row of it. Part of the content hash when true.';

-- 2. A notification can be sent from the platform's own wording.
--
--    ADR 0020 names a platform default beneath the brand and the tenant. It is bundled in code
--    (PlatformDefaultTemplates, the shape ADR 0068 gave the default terms) and not stored as rows
--    with no tenant, so a notification sent from it has no template row to point at. It points at the
--    version of the bundled wording instead, as the default terms' acceptance points at "default-v1":
--    what a customer was sent must keep meaning exactly that sentence after a later release edits the
--    class, and the version is the only handle on it.
--
--    Exactly one of the two is ever set. A notification that names a template row names the version
--    of that row (ck_notification_template_pair already ties those two together); a notification sent
--    from the platform's wording names this and no template at all.
ALTER TABLE notifications.notifications
    ADD COLUMN platform_default_version integer;

ALTER TABLE notifications.notifications
    ADD CONSTRAINT ck_notification_platform_default CHECK (
        platform_default_version IS NULL OR (template_id IS NULL AND platform_default_version >= 1));

COMMENT ON COLUMN notifications.notifications.platform_default_version IS
    'ADR 0184. The version of the bundled platform wording this message was sent from, when no brand or tenant template applied. Null when template_id is set.';

-- Both tables are already granted to the application role (V0026); a column inherits its table's grants.
