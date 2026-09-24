-- Row 2.1b: a cart line's chosen comment presets (V0396), copied onto the
-- order line at checkout with the label text of that moment -- the same
-- "names come from the catalog ... copied rather than referenced" discipline
-- CheckoutOrderWriter's own class doc states for ordering.order_line_modifiers,
-- so a preset later renamed, re-coded or archived cannot change a receipt or a
-- kitchen ticket that already printed it.
--
-- Shaped after ordering.order_line_modifiers (V0022): one row per preset per
-- line, no foreign key back to catalog.comment_presets (a snapshot does not
-- follow the row it was copied from), and the same SELECT, INSERT-only grant
-- -- an order line's presets are written once, at checkout, and never edited.
CREATE TABLE ordering.order_line_comment_presets (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_line_id uuid NOT NULL,

    -- catalog.comment_presets.id at the moment of checkout -- not a foreign
    -- key, for the identical reason order_line_modifiers.source_option_id is
    -- not one, and this is also what the POS export (row 2.1b's own "map to a
    -- provider modifier code") resolves through
    -- integration.provider_entity_mappings.
    source_preset_id uuid NOT NULL,

    code_snapshot varchar(32) NOT NULL,
    label_ru_snapshot varchar(120) NOT NULL,
    label_uz_snapshot varchar(120) NOT NULL,
    label_en_snapshot varchar(120) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,

    CONSTRAINT fk_order_line_comment_preset_line FOREIGN KEY (order_line_id, tenant_id)
        REFERENCES ordering.order_lines (id, tenant_id)
);

CREATE INDEX ix_order_line_comment_presets_line ON ordering.order_line_comment_presets (order_line_id);

COMMENT ON TABLE ordering.order_line_comment_presets IS
    'Row 2.1b. A line''s chosen kitchen-instruction presets, label text copied at checkout. Rendered by the KDS as chips ahead of the free note, shown in the order detail line, and mapped to a POS modifier code through integration.provider_entity_mappings keyed by source_preset_id.';

GRANT SELECT, INSERT ON ordering.order_line_comment_presets TO horecaos_application;
