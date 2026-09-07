-- ADR 0002, ADR 0011: the missing half of RESTAURANT_APPROVAL for a POS
-- approval channel. A tenant may set approval_channel to POS or EITHER
-- (V0022 snapshots it on every order), and Clopos genuinely is an authority
-- for acceptance — but nothing recorded whether one particular export was
-- ever asked to decide, so nothing could poll it and nothing could stop
-- polling once it had answered. These two columns are that missing memory.
--
-- Nothing else about integration.pos_order_exports changes. The export
-- machinery (PENDING/SENT/ACCEPTED/... in ExportState) still answers "did
-- this order reach the till"; these columns answer a second, independent
-- question — "does the till still owe us a clerk's decision" — that only
-- applies to the subset of exports opened while an order was still
-- AWAITING_APPROVAL rather than already CONFIRMED.
ALTER TABLE integration.pos_order_exports
    ADD COLUMN requires_pos_approval boolean NOT NULL DEFAULT false,
    ADD COLUMN pos_approval_decided_at timestamptz;

COMMENT ON COLUMN integration.pos_order_exports.requires_pos_approval IS
    'Set true when this export was sent asking the till to decide (PosAdapter.OrderExport#requireProviderApproval was true at send time) rather than merely telling it about an order already confirmed. Read by the approval poll to decide which ACCEPTED exports are worth asking again.';
COMMENT ON COLUMN integration.pos_order_exports.pos_approval_decided_at IS
    'When the platform last relayed a discovered clerk decision (accept or reject) for this export into ordering.api.PosApprovalDecisionPort. Null while the poll should keep asking; once set, the poll stops — the order has settled one way or another, whether this decision was the one that won the race or not.';

-- A decided instant only means anything for an export that was ever flagged
-- as needing one. Nothing writes decided_at without also having set the flag,
-- and this makes that a fact the schema states rather than a convention
-- every future writer has to remember.
ALTER TABLE integration.pos_order_exports
    ADD CONSTRAINT ck_pos_export_approval_decided_requires_flag CHECK (
        pos_approval_decided_at IS NULL OR requires_pos_approval
    );

-- The poll's own read: requires_pos_approval exports still undecided. Small
-- and cross-tenant by design, matching findStalePending's own index below it —
-- a scheduler sweeps every tenant's due rows in one query rather than one
-- tenant at a time.
CREATE INDEX ix_pos_order_exports_awaiting_pos_approval
    ON integration.pos_order_exports (requested_at)
    WHERE requires_pos_approval AND pos_approval_decided_at IS NULL;
