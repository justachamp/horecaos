-- Finance 8.4: a VARIANCE line has nowhere to record that an operator looked
-- at it and either accepted the partner's charge or disputed it. Without this,
-- a resolved variance and an untouched one both print the same PENDING-forever
-- row, and there is no акт сверки workflow at all -- only a raw MatchStatus.
--
-- Deliberately not folded into match_status: VARIANCE is a claim about the
-- money (invoiced differs from accrued), computed once by matching, and it
-- should keep meaning exactly that. Resolution is a second, later fact -- a
-- human decision about what to do with the variance -- layered on top rather
-- than overwritten into the same column.

ALTER TABLE fulfillment.partner_delivery_invoice_lines
    ADD COLUMN variance_resolution varchar(16),
    ADD COLUMN resolved_by varchar(128),
    ADD COLUMN resolved_at timestamptz;

ALTER TABLE fulfillment.partner_delivery_invoice_lines
    ADD CONSTRAINT ck_partner_line_resolution CHECK (
        variance_resolution IS NULL OR variance_resolution IN ('ACCEPTED', 'DISPUTED')),
    ADD CONSTRAINT ck_partner_line_resolution_pair CHECK (
        (variance_resolution IS NULL) = (resolved_at IS NULL)
        AND (variance_resolution IS NULL) = (resolved_by IS NULL)),
    -- Only a VARIANCE line is ever resolved this way. UNMATCHED_LINE resolves
    -- by re-matching with the correct shipment reference, not by this column.
    ADD CONSTRAINT ck_partner_line_resolution_status CHECK (
        variance_resolution IS NULL OR match_status = 'VARIANCE');

COMMENT ON COLUMN fulfillment.partner_delivery_invoice_lines.variance_resolution IS
    'An operator''s disposition of a VARIANCE line: ACCEPTED pays the partner''s charge as invoiced, DISPUTED flags it for pushback. Never set on any other match_status.';
