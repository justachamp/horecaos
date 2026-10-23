-- ADR 0141 Decision 6 and "When the partner is down" (batch 21, ADR 0180 Decision 4): an aggregator order that
-- holds a dish stopped on that channel and branch is ACCEPTED, because the customer has already paid the
-- aggregator, and the line is flagged for the pass.
--
-- The flag is the one ADR 0040 already built for a line the catalogue does not carry: `external_mapping_status`.
-- It had two values, MAPPED and UNMAPPED. A third, STOPPED, says "mapped to a variant, and that variant could not
-- be sold on this channel at this branch when the order arrived". Same column, so every surface that reads the
-- flag reads both; the pairing below is untouched because a STOPPED line has its variant and so is not NULL on
-- source_variant_id, exactly like a MAPPED one.
--
-- Nothing is stored about WHY (sold out, on a stop, not offered): the stop list and the stock count are the
-- authority for that and change by the minute; copying a reason onto the order line would only let it go stale.
-- No name, no partner payload and no personal data is added.

ALTER TABLE ordering.order_lines
    DROP CONSTRAINT ck_order_line_mapping_status;

ALTER TABLE ordering.order_lines
    ADD CONSTRAINT ck_order_line_mapping_status CHECK (
        external_mapping_status IN ('MAPPED', 'UNMAPPED', 'STOPPED'));

COMMENT ON COLUMN ordering.order_lines.external_mapping_status IS
    'ADR 0040, ADR 0141. UNMAPPED: a partner sent an item the catalogue does not carry (no variant). STOPPED: the item is mapped but could not be sold on this channel at this branch when the order arrived; the order was accepted and the line is flagged for the pass. Every HorecaOS-originated line is MAPPED.';
