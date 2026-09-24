-- Row 2.1b: the coded kitchen-instruction presets a customer or operator
-- chose for one cart line, out of the subset catalog.product_comment_presets
-- (V0378) offers for that line's product.
--
-- Codes, not preset ids. A cart line already stores its modifier choices as a
-- jsonb snapshot rather than a foreign key (selected_modifier_snapshot's own
-- comment: "a jsonb document rather than a child table because nothing
-- queries into it"), but a preset's code is exactly what CommentPresetService
-- already treats as the stable, operator-typed identity -- unique per tenant,
-- never translated (V0378) -- so a plain text[] of codes is enough to answer
-- "which presets does this line carry" without a join, and it is what
-- CheckoutOrderWriter resolves against catalog.comment_presets once, at
-- checkout, to snapshot the label text of the moment onto the order line
-- (V0397).
--
-- No validation constraint here: CartService checks a code against the
-- product's own offered subset before ever writing a row, the same division
-- of labour selected_modifier_snapshot's own selection-rule check already
-- keeps between the column and CartService.putLine.
ALTER TABLE ordering.cart_lines
    ADD COLUMN comment_preset_codes text[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN ordering.cart_lines.comment_preset_codes IS
    'Row 2.1b. catalog.comment_presets.code values chosen for this line, in display order. Snapshotted onto ordering.order_line_comment_presets (V0397) at checkout.';
