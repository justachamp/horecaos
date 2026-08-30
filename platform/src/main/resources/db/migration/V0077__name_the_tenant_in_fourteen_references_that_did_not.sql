-- The rest of the sweep V0069 started, and the reason it needs one.
--
-- V0069 fixed one tenant-blind foreign key —
-- `fulfillment.courier_engagements.evidence_media_id` — and added
-- `tools/checks/tenant_scoped_references.py` so the next one would fail the
-- build. The check shipped with an allowlist of twenty-three references that
-- were already tenant-blind on the day it was written. An allowlist is a way of
-- switching a check on before its backlog is paid down; it is not a finding, and
-- twenty-three of them sitting under one constant is how a finding becomes
-- furniture.
--
-- The set was re-derived from `pg_constraint` on a fully migrated database
-- rather than from the migration text, and it agrees with the allowlist exactly:
-- twenty-three foreign keys from a tenant-scoped table into a tenant-scoped
-- table that name only the surrogate id. This migration closes fourteen of them.
-- Which fourteen, and why not the other nine, is stated at the end of this file
-- and in the allowlist that survives it — each remaining entry now carries its
-- reason, because a bare name is what made these invisible.
--
-- The sharpest one is the first one here, and it has V0069's exact tell — a
-- composite reference and a single-column reference on the same table, two lines
-- apart:
--
--     V0026:188  fk_endpoint_account       FOREIGN KEY (customer_account_id, tenant_id)
--     V0026:190  fk_endpoint_contact_point FOREIGN KEY (contact_point_id)
--
-- `customer.contact_points` is where ADR 0029 personal data lives: a customer's
-- phone number and email address, held as randomized AEAD ciphertext beside a
-- per-tenant keyed hash. The two consequences are V0069's, unchanged:
--
--   1. Tenant B can durably store a pointer at tenant A's contact point, and the
--      notifications module then treats that row as its own recipient endpoint.
--   2. The constraint accepts exactly the ids that exist somewhere on the
--      platform and rejects every other uuid, so any write path that reaches
--      this column answers "is this contact point id real anywhere on Qoida" for
--      any uuid a caller submits.
--
-- The Java side of the notifications path is already tenant-correct:
-- NotificationEligibilityService resolves the contact point through
-- CustomerContactDirectory in the notification's own tenant before it calls
-- ensureEndpoint, so nothing reachable today writes a foreign id here. That is
-- the same thing V0069 said about CourierEngagementService, and it is exactly
-- why the constraint matters: it is what holds when the resolved path is
-- bypassed by a background job, a repair script, or a second write path nobody
-- has written yet.
--
-- Ordering in this file is by what the referenced table holds — envelope-
-- encrypted personal data first, then money, then everything else — because if
-- this migration had to be cut short, that is the order in which the cuts should
-- fall.

-- ---------------------------------------------------------------------------
-- 0. Where a reference that cannot survive the new key goes
-- ---------------------------------------------------------------------------

-- V0069's rule, applied fourteen times: move the reference rather than the row,
-- and record it rather than drop it. An ALTER that fails on real data is not a
-- migration, and one that silently discards a row is worse.
--
-- One table rather than fourteen module-local ones. V0069 put its quarantine in
-- `fulfillment` deliberately — it was one module's record of one reference it
-- once held. Fourteen copies of that table across nine schemas would be fourteen
-- grants, fourteen names to learn, and nothing that can answer "what did this
-- migration move" in a single query. The columns below carry the module
-- identity that the table name used to.
--
-- Two dispositions, because the columns differ in whether they can be emptied:
--
--   POINTER_CLEARED  the referencing column is nullable, so the row stays and
--                    only the pointer is lifted out of it. This is V0069's case.
--   ROW_WITHDRAWN    the referencing column is NOT NULL, so there is no such
--                    thing as the row without the parent it names. The whole row
--                    is moved here as jsonb and removed from its table. Nothing
--                    is reconstructed automatically; `withdrawn_row` exists so a
--                    human adjudicating afterwards has the row in front of them.
--
-- No personal data reaches this table, and that was checked column by column
-- rather than assumed. Every table that can produce a ROW_WITHDRAWN here holds
-- identifiers, codes, amounts and timestamps and no plaintext name, number or
-- address; `customer.contact_points`, the one target in this migration that
-- holds ADR 0029 ciphertext, is never itself withdrawn — only the uuid of a row
-- in it is recorded. The single value that came close is
-- `notifications.recipient_endpoints.normalized_hash`, a keyed per-tenant hash of
-- a phone number, and section 2 strips it out of the snapshot rather than
-- carrying it here. A future migration adding a case has to make that judgement
-- again rather than inherit it.
CREATE TABLE tenant.cross_tenant_reference_quarantine (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The tenant of the referencing row — the one that held a pointer it was not
    -- entitled to hold. Never the tenant of the row pointed at: naming that one
    -- here would copy the leak into the record of the leak.
    tenant_id uuid NOT NULL,

    source_table text NOT NULL,
    constraint_name text NOT NULL,
    -- Text, not uuid: three of the tables swept here are keyed on a composite of
    -- their parent and an ordinal rather than on a surrogate, and a column that
    -- can only hold half of such a key would silently record the wrong row.
    source_row_key text NOT NULL,
    referencing_column text NOT NULL,
    referenced_table text NOT NULL,
    referenced_id uuid NOT NULL,

    disposition varchar(16) NOT NULL,
    withdrawn_row jsonb,

    quarantine_reason varchar(64) NOT NULL,
    quarantined_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_quarantine_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT ck_quarantine_disposition CHECK (
        disposition IN ('POINTER_CLEARED', 'ROW_WITHDRAWN')
    ),
    -- Stated as an equality rather than a pair of implications: the disjunctive
    -- form admits a row that is withdrawn and carries nothing.
    CONSTRAINT ck_quarantine_row_present CHECK (
        (disposition = 'ROW_WITHDRAWN') = (withdrawn_row IS NOT NULL)
    )
);

COMMENT ON TABLE tenant.cross_tenant_reference_quarantine IS
    'Cross-tenant foreign key references lifted out of their tables by V0077 when the row they named belonged to another tenant. Evidence of what was once pointed at; never read by the application, and never resolved to the row it names.';

-- SELECT only, and only so an operator surfacing this through the platform's own
-- tooling does not need a database session. Nothing in the application writes
-- here: every row in this table was written by a migration.
GRANT SELECT ON tenant.cross_tenant_reference_quarantine TO qoida_application;

-- ---------------------------------------------------------------------------
-- 1. The keys the composite references need
-- ---------------------------------------------------------------------------

-- A foreign key must reference a unique constraint on exactly its own columns, so
-- every target below needs `(id, tenant_id)` before anything can point at it that
-- way. Three targets already have one and are not repeated here:
-- `ordering.orders.uq_order_identity`, `pricing.quotes.uq_quote_identity`, and
-- `fiscal.fiscal_documents.uq_fiscal_document_tenant_id`. The last is declared
-- `(tenant_id, id)`; PostgreSQL matches a referenced column list against a unique
-- constraint's column *set*, and the order in the REFERENCES clause decides only
-- which column pairs with which, so `(id, tenant_id)` references it correctly.
--
-- None of these can fail: `id` is already the primary key of each table, so a
-- unique on `(id, tenant_id)` is implied by a constraint that already holds.
ALTER TABLE customer.contact_points
    ADD CONSTRAINT uq_contact_point_identity UNIQUE (id, tenant_id);
ALTER TABLE catalog.publications
    ADD CONSTRAINT uq_publication_identity UNIQUE (id, tenant_id);
ALTER TABLE fulfillment.courier_ledger_entries
    ADD CONSTRAINT uq_ledger_entry_identity UNIQUE (id, tenant_id);
ALTER TABLE fulfillment.delivery_cost_lines
    ADD CONSTRAINT uq_cost_line_identity UNIQUE (id, tenant_id);
ALTER TABLE iam.tenant_membership_links
    ADD CONSTRAINT uq_membership_link_identity UNIQUE (id, tenant_id);
ALTER TABLE migration.entity_mappings
    ADD CONSTRAINT uq_entity_mapping_identity UNIQUE (id, tenant_id);
ALTER TABLE reporting.close_runs
    ADD CONSTRAINT uq_close_run_identity UNIQUE (id, tenant_id);
ALTER TABLE tenant.onboarding_runs
    ADD CONSTRAINT uq_onboarding_run_identity UNIQUE (id, tenant_id);
ALTER TABLE tenant.onboarding_steps
    ADD CONSTRAINT uq_onboarding_step_identity UNIQUE (id, tenant_id);

-- ---------------------------------------------------------------------------
-- 2. Personal data — notifications -> customer.contact_points (ADR 0029)
-- ---------------------------------------------------------------------------

-- `contact_point_id` is nullable, so V0069's copy-then-clear looks like the
-- obvious repair here. It is not available, and the reason is worth stating
-- because it is the table telling the truth about itself. Three CHECKs interlock:
--
--   ck_endpoint_destination  exactly one of contact_point_id and
--                            operations_endpoint_reference is present
--   ck_endpoint_owner        customer_account_id is NULL exactly when
--                            operations_endpoint_reference is present
--   ck_endpoint_hash_pair    contact_point_id and normalized_hash are both
--                            present or both absent
--
-- Together they say a customer endpoint MUST name a contact point. An endpoint
-- whose contact point belongs to another tenant therefore has no repaired state:
-- clearing the pointer leaves a customer endpoint with no destination, and there
-- is no operations reference to put there because it has an account. So the row
-- is withdrawn whole, and what stands behind it is withdrawn first.
--
-- `notifications.notifications.recipient_endpoint_id` is nullable and no CHECK
-- binds it, so a message that had been assigned a withdrawn endpoint has its
-- pointer cleared and is recorded here as well — the endpoint is gone, the
-- message is not, and a message pointing at a row that no longer exists would
-- have blocked the DELETE with NO ACTION anyway.
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT notification.tenant_id, 'notifications.notifications', 'fk_notification_endpoint',
       notification.id::text, 'recipient_endpoint_id', 'notifications.recipient_endpoints',
       notification.recipient_endpoint_id,
       'POINTER_CLEARED', 'ENDPOINT_WITHDRAWN_BY_V0077'
FROM notifications.notifications AS notification
JOIN notifications.recipient_endpoints AS endpoint
  ON endpoint.id = notification.recipient_endpoint_id
 AND endpoint.tenant_id = notification.tenant_id
WHERE endpoint.contact_point_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM customer.contact_points AS contact
      WHERE contact.id = endpoint.contact_point_id
        AND contact.tenant_id = endpoint.tenant_id);

UPDATE notifications.notifications AS notification
   SET recipient_endpoint_id = NULL,
       updated_at = now()
 WHERE notification.recipient_endpoint_id IS NOT NULL
   AND EXISTS (
       SELECT 1 FROM notifications.recipient_endpoints AS endpoint
       WHERE endpoint.id = notification.recipient_endpoint_id
         AND endpoint.tenant_id = notification.tenant_id
         AND endpoint.contact_point_id IS NOT NULL
         AND NOT EXISTS (
             SELECT 1 FROM customer.contact_points AS contact
             WHERE contact.id = endpoint.contact_point_id
               AND contact.tenant_id = endpoint.tenant_id));

-- `normalized_hash` is dropped from the recorded row rather than carried into
-- it. It is a per-tenant keyed hash of a phone number or an email address — a
-- pseudonym under ADR 0029, not a plaintext, but the whole point of this table is
-- that nothing here is ever resolved back to a person, and a value that supports
-- equality search is the one column that would let someone try. The uuid of the
-- contact point stays, because that is the fact being recorded.
WITH offending AS (
    DELETE FROM notifications.recipient_endpoints AS endpoint
     WHERE endpoint.contact_point_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM customer.contact_points AS contact
           WHERE contact.id = endpoint.contact_point_id
             AND contact.tenant_id = endpoint.tenant_id)
    RETURNING endpoint.*
)
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, withdrawn_row, quarantine_reason)
SELECT offending.tenant_id, 'notifications.recipient_endpoints', 'fk_endpoint_contact_point',
       offending.id::text, 'contact_point_id', 'customer.contact_points', offending.contact_point_id,
       'ROW_WITHDRAWN', to_jsonb(offending) - 'normalized_hash', 'NO_CONTACT_POINT_IN_TENANT'
FROM offending;

ALTER TABLE notifications.recipient_endpoints
    DROP CONSTRAINT fk_endpoint_contact_point;

-- Composite, against uq_contact_point_identity. `contact_point_id` stays
-- nullable and the default MATCH SIMPLE is what makes that work: with any
-- referenced column NULL the constraint is not checked, so an operations
-- endpoint is still accepted, while a non-null pointer is always checked against
-- both columns because this table's tenant_id is NOT NULL.
--
-- No ON DELETE clause, matching what was there before: a contact point an
-- endpoint stands for is not removed out from under it.
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT fk_endpoint_contact_point FOREIGN KEY (contact_point_id, tenant_id)
        REFERENCES customer.contact_points (id, tenant_id);

COMMENT ON COLUMN notifications.recipient_endpoints.contact_point_id IS
    'ADR 0015 contact point this endpoint stands for; the row behind it holds ADR 0029 envelope-encrypted phone or email. Scoped to this endpoint''s tenant by fk_endpoint_contact_point since V0077; before that a single-column reference let one tenant''s endpoint name another tenant''s contact point.';

-- ---------------------------------------------------------------------------
-- 3. Money
-- ---------------------------------------------------------------------------

-- 3a. fulfillment.courier_ledger_entries.adjusts_entry_id — what a courier is
-- paid. An adjustment naming another tenant's ledger entry is a correction
-- applied to a balance the correcting tenant cannot see, and settlement reads the
-- chain.
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT entry.tenant_id, 'fulfillment.courier_ledger_entries', 'fk_ledger_adjusts',
       entry.id::text, 'adjusts_entry_id', 'fulfillment.courier_ledger_entries', entry.adjusts_entry_id,
       'POINTER_CLEARED', 'NO_LEDGER_ENTRY_IN_TENANT'
FROM fulfillment.courier_ledger_entries AS entry
WHERE entry.adjusts_entry_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM fulfillment.courier_ledger_entries AS adjusted
      WHERE adjusted.id = entry.adjusts_entry_id
        AND adjusted.tenant_id = entry.tenant_id);

UPDATE fulfillment.courier_ledger_entries AS entry
   SET adjusts_entry_id = NULL
 WHERE entry.adjusts_entry_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM fulfillment.courier_ledger_entries AS adjusted
       WHERE adjusted.id = entry.adjusts_entry_id
         AND adjusted.tenant_id = entry.tenant_id);

ALTER TABLE fulfillment.courier_ledger_entries
    DROP CONSTRAINT fk_ledger_adjusts;
ALTER TABLE fulfillment.courier_ledger_entries
    ADD CONSTRAINT fk_ledger_adjusts FOREIGN KEY (adjusts_entry_id, tenant_id)
        REFERENCES fulfillment.courier_ledger_entries (id, tenant_id);

-- 3b. fulfillment.delivery_cost_lines.supersedes_line_id — what a delivery cost.
-- `uq_cost_line_supersedes` is a single-column unique on the referencing side and
-- is untouched: it says a cost line is superseded at most once, which stays true
-- and stays platform-wide.
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT line.tenant_id, 'fulfillment.delivery_cost_lines', 'fk_cost_line_supersedes',
       line.id::text, 'supersedes_line_id', 'fulfillment.delivery_cost_lines', line.supersedes_line_id,
       'POINTER_CLEARED', 'NO_COST_LINE_IN_TENANT'
FROM fulfillment.delivery_cost_lines AS line
WHERE line.supersedes_line_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM fulfillment.delivery_cost_lines AS superseded
      WHERE superseded.id = line.supersedes_line_id
        AND superseded.tenant_id = line.tenant_id);

UPDATE fulfillment.delivery_cost_lines AS line
   SET supersedes_line_id = NULL
 WHERE line.supersedes_line_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM fulfillment.delivery_cost_lines AS superseded
       WHERE superseded.id = line.supersedes_line_id
         AND superseded.tenant_id = line.tenant_id);

ALTER TABLE fulfillment.delivery_cost_lines
    DROP CONSTRAINT fk_cost_line_supersedes;
ALTER TABLE fulfillment.delivery_cost_lines
    ADD CONSTRAINT fk_cost_line_supersedes FOREIGN KEY (supersedes_line_id, tenant_id)
        REFERENCES fulfillment.delivery_cost_lines (id, tenant_id);

-- 3c. fiscal.fiscal_documents.corrects_document_id — the correction chain a tax
-- authority reads. Nullable, so the pointer moves and the document stays; a
-- fiscal document is a retained record and is never withdrawn by this migration.
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT document.tenant_id, 'fiscal.fiscal_documents', 'fk_fiscal_document_corrects',
       document.id::text, 'corrects_document_id', 'fiscal.fiscal_documents', document.corrects_document_id,
       'POINTER_CLEARED', 'NO_FISCAL_DOCUMENT_IN_TENANT'
FROM fiscal.fiscal_documents AS document
WHERE document.corrects_document_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM fiscal.fiscal_documents AS corrected
      WHERE corrected.id = document.corrects_document_id
        AND corrected.tenant_id = document.tenant_id);

UPDATE fiscal.fiscal_documents AS document
   SET corrects_document_id = NULL,
       updated_at = now()
 WHERE document.corrects_document_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM fiscal.fiscal_documents AS corrected
       WHERE corrected.id = document.corrects_document_id
         AND corrected.tenant_id = document.tenant_id);

ALTER TABLE fiscal.fiscal_documents
    DROP CONSTRAINT fk_fiscal_document_corrects;
ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT fk_fiscal_document_corrects FOREIGN KEY (corrects_document_id, tenant_id)
        REFERENCES fiscal.fiscal_documents (id, tenant_id);

-- 3d. fiscal.fiscal_documents.order_id — the order a receipt was issued for.
-- NOT NULL, so a document naming another tenant's order cannot be repaired by
-- clearing the column: a fiscal document with no order is not a fiscal document.
-- It is withdrawn whole into the quarantine table, which is the only disposition
-- that keeps the row readable by the person who has to decide what it was.
WITH offending AS (
    DELETE FROM fiscal.fiscal_documents AS document
     WHERE NOT EXISTS (
         SELECT 1 FROM ordering.orders AS ordered
         WHERE ordered.id = document.order_id
           AND ordered.tenant_id = document.tenant_id)
    RETURNING document.*
)
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, withdrawn_row, quarantine_reason)
SELECT offending.tenant_id, 'fiscal.fiscal_documents', 'fk_fiscal_document_order',
       offending.id::text, 'order_id', 'ordering.orders', offending.order_id,
       'ROW_WITHDRAWN', to_jsonb(offending), 'NO_ORDER_IN_TENANT'
FROM offending;

ALTER TABLE fiscal.fiscal_documents
    DROP CONSTRAINT fk_fiscal_document_order;
ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT fk_fiscal_document_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id);

-- 3e/3f. pricing.quote_lines and pricing.quote_adjustments -> pricing.quotes.
-- Both NOT NULL, both ON DELETE CASCADE, and a line or an adjustment without its
-- quote is not a partial record — it is an amount with no total to belong to.
-- The CASCADE is preserved on the composite key.
WITH offending AS (
    DELETE FROM pricing.quote_lines AS line
     WHERE NOT EXISTS (
         SELECT 1 FROM pricing.quotes AS quote
         WHERE quote.id = line.quote_id AND quote.tenant_id = line.tenant_id)
    RETURNING line.*
)
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, withdrawn_row, quarantine_reason)
SELECT offending.tenant_id, 'pricing.quote_lines', 'fk_quote_line_quote',
       format('quote_id=%s,line_id=%s', offending.quote_id, offending.line_id), 'quote_id', 'pricing.quotes', offending.quote_id,
       'ROW_WITHDRAWN', to_jsonb(offending), 'NO_QUOTE_IN_TENANT'
FROM offending;

ALTER TABLE pricing.quote_lines
    DROP CONSTRAINT fk_quote_line_quote;
ALTER TABLE pricing.quote_lines
    ADD CONSTRAINT fk_quote_line_quote FOREIGN KEY (quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id) ON DELETE CASCADE;

WITH offending AS (
    DELETE FROM pricing.quote_adjustments AS adjustment
     WHERE NOT EXISTS (
         SELECT 1 FROM pricing.quotes AS quote
         WHERE quote.id = adjustment.quote_id AND quote.tenant_id = adjustment.tenant_id)
    RETURNING adjustment.*
)
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, withdrawn_row, quarantine_reason)
SELECT offending.tenant_id, 'pricing.quote_adjustments', 'fk_adjustment_quote',
       format('quote_id=%s,sequence=%s', offending.quote_id, offending.sequence), 'quote_id', 'pricing.quotes', offending.quote_id,
       'ROW_WITHDRAWN', to_jsonb(offending), 'NO_QUOTE_IN_TENANT'
FROM offending;

ALTER TABLE pricing.quote_adjustments
    DROP CONSTRAINT fk_adjustment_quote;
ALTER TABLE pricing.quote_adjustments
    ADD CONSTRAINT fk_adjustment_quote FOREIGN KEY (quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------------
-- 4. Everything else
-- ---------------------------------------------------------------------------

-- 4a. catalog.publication_items -> catalog.publications. NOT NULL and CASCADE:
-- an item belongs to the publication it is in, and a publication is what a
-- storefront reads. An item in another tenant's publication is a product placed
-- on somebody else's menu.
WITH offending AS (
    DELETE FROM catalog.publication_items AS item
     WHERE NOT EXISTS (
         SELECT 1 FROM catalog.publications AS publication
         WHERE publication.id = item.publication_id
           AND publication.tenant_id = item.tenant_id)
    RETURNING item.*
)
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, withdrawn_row, quarantine_reason)
SELECT offending.tenant_id, 'catalog.publication_items', 'fk_publication_item',
       format('publication_id=%s,entity_type=%s,entity_id=%s',
              offending.publication_id, offending.entity_type, offending.entity_id), 'publication_id', 'catalog.publications', offending.publication_id,
       'ROW_WITHDRAWN', to_jsonb(offending), 'NO_PUBLICATION_IN_TENANT'
FROM offending;

ALTER TABLE catalog.publication_items
    DROP CONSTRAINT fk_publication_item;
ALTER TABLE catalog.publication_items
    ADD CONSTRAINT fk_publication_item FOREIGN KEY (publication_id, tenant_id)
        REFERENCES catalog.publications (id, tenant_id) ON DELETE CASCADE;

-- 4b/4c. tenant.onboarding_steps and tenant.readiness_checks -> onboarding_runs.
--
-- These run before the iam.identity_reconciliation_runs section below, and the
-- order is deliberate. `fk_reconciliation_onboarding_step` is ON DELETE SET NULL,
-- so withdrawing an onboarding step here nulls the pointer of any reconciliation
-- run that named it. Doing the steps first means the run section sees the state
-- PostgreSQL left and records only pointers that are genuinely cross-tenant; the
-- withdrawn step's own row, held here as jsonb, is the evidence of what the
-- nulled pointer named.
WITH offending AS (
    DELETE FROM tenant.onboarding_steps AS step
     WHERE NOT EXISTS (
         SELECT 1 FROM tenant.onboarding_runs AS run
         WHERE run.id = step.run_id AND run.tenant_id = step.tenant_id)
    RETURNING step.*
)
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, withdrawn_row, quarantine_reason)
SELECT offending.tenant_id, 'tenant.onboarding_steps', 'fk_onboarding_step_run',
       offending.id::text, 'run_id', 'tenant.onboarding_runs', offending.run_id,
       'ROW_WITHDRAWN', to_jsonb(offending), 'NO_ONBOARDING_RUN_IN_TENANT'
FROM offending;

ALTER TABLE tenant.onboarding_steps
    DROP CONSTRAINT fk_onboarding_step_run;
ALTER TABLE tenant.onboarding_steps
    ADD CONSTRAINT fk_onboarding_step_run FOREIGN KEY (run_id, tenant_id)
        REFERENCES tenant.onboarding_runs (id, tenant_id) ON DELETE CASCADE;

WITH offending AS (
    DELETE FROM tenant.readiness_checks AS check_row
     WHERE NOT EXISTS (
         SELECT 1 FROM tenant.onboarding_runs AS run
         WHERE run.id = check_row.run_id AND run.tenant_id = check_row.tenant_id)
    RETURNING check_row.*
)
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, withdrawn_row, quarantine_reason)
SELECT offending.tenant_id, 'tenant.readiness_checks', 'fk_readiness_check_run',
       offending.id::text, 'run_id', 'tenant.onboarding_runs', offending.run_id,
       'ROW_WITHDRAWN', to_jsonb(offending), 'NO_ONBOARDING_RUN_IN_TENANT'
FROM offending;

ALTER TABLE tenant.readiness_checks
    DROP CONSTRAINT fk_readiness_check_run;
ALTER TABLE tenant.readiness_checks
    ADD CONSTRAINT fk_readiness_check_run FOREIGN KEY (run_id, tenant_id)
        REFERENCES tenant.onboarding_runs (id, tenant_id) ON DELETE CASCADE;

-- 4d. reporting.aggregate_divergences -> reporting.close_runs. NOT NULL: a
-- divergence is a statement about one close run, and attributing it to another
-- tenant's run makes the reconciliation report wrong for both.
WITH offending AS (
    DELETE FROM reporting.aggregate_divergences AS divergence
     WHERE NOT EXISTS (
         SELECT 1 FROM reporting.close_runs AS run
         WHERE run.id = divergence.run_id AND run.tenant_id = divergence.tenant_id)
    RETURNING divergence.*
)
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, withdrawn_row, quarantine_reason)
SELECT offending.tenant_id, 'reporting.aggregate_divergences', 'fk_aggregate_divergence_run',
       offending.id::text, 'run_id', 'reporting.close_runs', offending.run_id,
       'ROW_WITHDRAWN', to_jsonb(offending), 'NO_CLOSE_RUN_IN_TENANT'
FROM offending;

ALTER TABLE reporting.aggregate_divergences
    DROP CONSTRAINT fk_aggregate_divergence_run;
ALTER TABLE reporting.aggregate_divergences
    ADD CONSTRAINT fk_aggregate_divergence_run FOREIGN KEY (run_id, tenant_id)
        REFERENCES reporting.close_runs (id, tenant_id);

-- 4e. migration.entity_mappings.superseded_by_mapping_id — the supersession
-- chain a cutover walks. Nullable, so the pointer moves.
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT mapping.tenant_id, 'migration.entity_mappings', 'fk_entity_mapping_supersession',
       mapping.id::text, 'superseded_by_mapping_id', 'migration.entity_mappings',
       mapping.superseded_by_mapping_id,
       'POINTER_CLEARED', 'NO_ENTITY_MAPPING_IN_TENANT'
FROM migration.entity_mappings AS mapping
WHERE mapping.superseded_by_mapping_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM migration.entity_mappings AS successor
      WHERE successor.id = mapping.superseded_by_mapping_id
        AND successor.tenant_id = mapping.tenant_id);

UPDATE migration.entity_mappings AS mapping
   SET superseded_by_mapping_id = NULL
 WHERE mapping.superseded_by_mapping_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM migration.entity_mappings AS successor
       WHERE successor.id = mapping.superseded_by_mapping_id
         AND successor.tenant_id = mapping.tenant_id);

ALTER TABLE migration.entity_mappings
    DROP CONSTRAINT fk_entity_mapping_supersession;
ALTER TABLE migration.entity_mappings
    ADD CONSTRAINT fk_entity_mapping_supersession
        FOREIGN KEY (superseded_by_mapping_id, tenant_id)
        REFERENCES migration.entity_mappings (id, tenant_id);

-- 4f/4g. iam.identity_reconciliation_runs -> iam.tenant_membership_links and
-- tenant.onboarding_steps.
--
-- This table's own `tenant_id` is nullable, on purpose: V0057 allows a
-- realm-wide sweep that found an organization belonging to no tenant. That
-- nullability is what makes a composite key here need one more piece than the
-- others. Under the default MATCH SIMPLE a foreign key is not checked at all when
-- any of its columns is NULL, so `(membership_link_id, tenant_id)` on a row with
-- no tenant would be unchecked — weaker than the single-column key it replaces,
-- which is the opposite of the point.
--
-- The CHECK below closes that: a run may have no tenant, or it may name a
-- tenant-owned row, and not both. A tenantless sweep is a finding about an
-- organization, and it has no membership link and no onboarding step to name —
-- those belong to a tenant by definition.
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT run.tenant_id, 'iam.identity_reconciliation_runs', 'fk_reconciliation_membership_link',
       run.id::text, 'membership_link_id', 'iam.tenant_membership_links', run.membership_link_id,
       'POINTER_CLEARED', 'NO_MEMBERSHIP_LINK_IN_TENANT'
FROM iam.identity_reconciliation_runs AS run
WHERE run.tenant_id IS NOT NULL
  AND run.membership_link_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM iam.tenant_membership_links AS link
      WHERE link.id = run.membership_link_id AND link.tenant_id = run.tenant_id);

INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT run.tenant_id, 'iam.identity_reconciliation_runs', 'fk_reconciliation_onboarding_step',
       run.id::text, 'onboarding_step_id', 'tenant.onboarding_steps', run.onboarding_step_id,
       'POINTER_CLEARED', 'NO_ONBOARDING_STEP_IN_TENANT'
FROM iam.identity_reconciliation_runs AS run
WHERE run.tenant_id IS NOT NULL
  AND run.onboarding_step_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM tenant.onboarding_steps AS step
      WHERE step.id = run.onboarding_step_id AND step.tenant_id = run.tenant_id);

-- A tenantless run holding either pointer is quarantined too — it is the case the
-- new CHECK forbids, and it is recorded with `tenant_id` taken from the row the
-- pointer names, because there is no other tenant to attribute it to and the
-- quarantine table's own foreign key needs a real one.
INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT link.tenant_id, 'iam.identity_reconciliation_runs', 'fk_reconciliation_membership_link',
       run.id::text, 'membership_link_id', 'iam.tenant_membership_links', run.membership_link_id,
       'POINTER_CLEARED', 'TENANTLESS_RUN_NAMED_TENANT_OWNED_ROW'
FROM iam.identity_reconciliation_runs AS run
JOIN iam.tenant_membership_links AS link ON link.id = run.membership_link_id
WHERE run.tenant_id IS NULL;

INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT step.tenant_id, 'iam.identity_reconciliation_runs', 'fk_reconciliation_onboarding_step',
       run.id::text, 'onboarding_step_id', 'tenant.onboarding_steps', run.onboarding_step_id,
       'POINTER_CLEARED', 'TENANTLESS_RUN_NAMED_TENANT_OWNED_ROW'
FROM iam.identity_reconciliation_runs AS run
JOIN tenant.onboarding_steps AS step ON step.id = run.onboarding_step_id
WHERE run.tenant_id IS NULL;

UPDATE iam.identity_reconciliation_runs AS run
   SET membership_link_id = NULL
 WHERE run.membership_link_id IS NOT NULL
   AND (run.tenant_id IS NULL
        OR NOT EXISTS (
            SELECT 1 FROM iam.tenant_membership_links AS link
            WHERE link.id = run.membership_link_id AND link.tenant_id = run.tenant_id));

UPDATE iam.identity_reconciliation_runs AS run
   SET onboarding_step_id = NULL
 WHERE run.onboarding_step_id IS NOT NULL
   AND (run.tenant_id IS NULL
        OR NOT EXISTS (
            SELECT 1 FROM tenant.onboarding_steps AS step
            WHERE step.id = run.onboarding_step_id AND step.tenant_id = run.tenant_id));

ALTER TABLE iam.identity_reconciliation_runs
    ADD CONSTRAINT ck_reconciliation_tenant_present CHECK (
        tenant_id IS NOT NULL
        OR (membership_link_id IS NULL AND onboarding_step_id IS NULL)
    );

ALTER TABLE iam.identity_reconciliation_runs
    DROP CONSTRAINT fk_reconciliation_membership_link;
ALTER TABLE iam.identity_reconciliation_runs
    ADD CONSTRAINT fk_reconciliation_membership_link
        FOREIGN KEY (membership_link_id, tenant_id)
        REFERENCES iam.tenant_membership_links (id, tenant_id);

ALTER TABLE iam.identity_reconciliation_runs
    DROP CONSTRAINT fk_reconciliation_onboarding_step;
-- `ON DELETE SET NULL (onboarding_step_id)` names the column, which PostgreSQL
-- has allowed since 15. The unqualified form would set every column of the key to
-- NULL — including this row's own `tenant_id`, silently moving the run out of the
-- tenant it belongs to whenever an onboarding step is deleted. That is a worse
-- defect than the one this migration is fixing, and it is one keyword away.
ALTER TABLE iam.identity_reconciliation_runs
    ADD CONSTRAINT fk_reconciliation_onboarding_step
        FOREIGN KEY (onboarding_step_id, tenant_id)
        REFERENCES tenant.onboarding_steps (id, tenant_id)
        ON DELETE SET NULL (onboarding_step_id);

-- ---------------------------------------------------------------------------
-- 5. The nine this migration does not close, and why
-- ---------------------------------------------------------------------------
--
-- Four are references into a table that holds platform-owned rows beside
-- tenant-owned ones, where the tenant column of the target is nullable by design.
-- A composite key cannot express them: under MATCH SIMPLE a NULL in the key
-- disables the check entirely, so pointing a tenant's row at a platform row would
-- become unchecked, and pointing it at another tenant's row would stay possible.
-- These need a resolution rule in the referencing service and, where the target is
-- mixed rather than purely platform-owned, they remain a real hole:
--
--   iam.grants.fk_grant_role -> iam.roles
--   tenant.policy_current.fk_policy_current_policy -> tenant.policies
--   fulfillment.service_zone_versions.fk_zone_version_region -> fulfillment.regions
--   migration.cutover_decisions.fk_cutover_approval_request -> audit.approval_requests
--
-- Five belong to modules under active change by other work in flight, and a
-- constraint added to a table somebody else is migrating is a merge conflict that
-- fails at deploy time rather than at review time:
--
--   ordering.carts.fk_cart_publication          -> catalog.publications
--   ordering.orders.fk_order_publication        -> catalog.publications
--   payments.payment_intents.fk_payment_intent_order -> ordering.orders
--   loyalty.lots.fk_loyalty_lot_source          -> loyalty.entries
--   audit.approval_requests.fk_approval_request_policy -> audit.approval_policies
--
-- The first three of those five are already unblocked by this migration:
-- `uq_publication_identity` is added above, and `uq_order_identity` was already
-- there, so each is a drop-and-re-add away. Every one of the nine is in
-- tools/checks/known_tenant_blind_references.tsv with its verdict and its reason
-- written next to it.

-- ---------------------------------------------------------------------------
-- 6. The one a constraint sweep cannot see at all
-- ---------------------------------------------------------------------------
--
-- Everything above was found by asking `pg_constraint` which foreign keys omit
-- the tenant column. That question has a blind spot with no floor under it: a
-- column that holds a tenant-owned row's id and has NO foreign key at all is
-- invisible to the sweep, because there is no constraint to inspect.
--
-- The catalog was searched for that shape instead — a tenant-scoped table whose
-- primary key is composite, omits tenant_id, and has no foreign key on any of
-- its key columns, so nothing in the database constrains where those ids came
-- from. Four tables match. `audit.audit_events (id, recorded_at)` and
-- `fulfillment.courier_location_tracks (window_start, id)` are a surrogate uuid
-- plus a partition key and are not this shape at all.
-- `catalog.media_relation_orphans` is V0065's quarantine table, written only by
-- a migration. That leaves one:
--
--     catalog.translations  PRIMARY KEY (entity_type, entity_id, locale)
--
-- with `tenant_id NOT NULL` sitting outside the key, and no foreign key on
-- entity_id because it is polymorphic across six catalog tables. The write is an
-- upsert, and this is what that combination does:
--
--     INSERT INTO catalog.translations (tenant_id, brand_id, entity_type,
--                                       entity_id, locale, name, description)
--     VALUES (...)
--     ON CONFLICT (entity_type, entity_id, locale) DO UPDATE
--     SET name = EXCLUDED.name, description = EXCLUDED.description, ...
--
-- Tenant B calls translate() with tenant A's product id. The key it collides on
-- does not mention a tenant, so PostgreSQL finds A's row and takes the DO UPDATE
-- branch. `tenant_id` is not in the SET list, so the row stays A's — and its name
-- and description are now B's text. This is not a dangling pointer or an
-- existence oracle. It is one tenant silently rewriting what another tenant's
-- customers read on the menu, with the victim's own tenant_id on the row
-- afterwards, and it leaves no trace a foreign key sweep could ever find.
--
-- Widening the key is the fix that holds without a foreign key to hang it on. It
-- can only relax uniqueness — two tenants may now each translate the same uuid,
-- where before the second silently overwrote the first — so no existing row can
-- violate it and nothing has to be quarantined.
--
-- This alone stops the overwrite. It does not stop tenant B from writing a
-- translation row of its own against tenant A's product id, and no key can:
-- entity_id is polymorphic and cannot be constrained. That half is closed in
-- CatalogAuthoringService.translate, which resolves the entity in the caller's
-- own tenant first and refuses with one answer that does not distinguish "not
-- yours" from "does not exist" — the same shape V0069 gave the courier evidence
-- path, and for the same reason.
ALTER TABLE catalog.translations
    DROP CONSTRAINT translations_pkey;

ALTER TABLE catalog.translations
    ADD CONSTRAINT translations_pkey
        PRIMARY KEY (tenant_id, entity_type, entity_id, locale);

COMMENT ON TABLE catalog.translations IS
    'ADR 0016 localised names and descriptions. entity_id is polymorphic across six catalog tables and therefore carries no foreign key, so the primary key is the only thing scoping a row to its tenant: it has included tenant_id since V0077, and before that an upsert from one tenant could overwrite another tenant''s text through ON CONFLICT.';
