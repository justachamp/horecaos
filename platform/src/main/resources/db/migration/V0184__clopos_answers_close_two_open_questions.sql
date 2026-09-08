-- Clopos answered six of docs/providers/clopos-api.md's open questions to the
-- platform owner on 2026-09-08. Most of that answer lives in code (the
-- adapter, the normalizer, the difference engine) and in the doc itself; this
-- migration carries the two corrections that live in a database comment,
-- which an ALTER of an applied migration is never how those get fixed.
--
-- No schema changes. Both statements below replace what a fresh
-- COMMENT ON ... IS overwrites in the catalog (pg_description) — they do not
-- edit V0036 or V0037, which stay exactly as they were run and remain the
-- true record of what was known at the time.

-- Q1/Q18: "Is POST /orders idempotent?" — yes, for an identical payload.
-- V0036's own table comment said flatly "Clopos provides none", which the
-- answer disproves. What does not change is which idempotency this platform
-- relies on: uq_pos_export_per_order is ours, stays ours, and nothing here
-- reconstructs and resends a stored request body byte-for-byte, so Clopos's
-- own dedupe is not a retry path this platform exploits.
COMMENT ON TABLE integration.pos_order_exports IS
    'ADR 0011. One row per order per POS. The uniqueness on (tenant_id, order_id) is this platform''s own idempotency and does not depend on the provider''s: Clopos confirmed 2026-09-08 (Q1/Q18, docs/providers/clopos-api.md) that a repeated POST /orders is deduplicated for a byte-identical payload, but nothing here resends a stored body, so that guarantee is recorded rather than relied on.';

-- Q15: "What does gov_code hold?" — the ИКПУ/MXIK, confirmed. V0037's column
-- comment said the opposite was still open ("may not be treated as an MXIK
-- until somebody has established what it holds for an Uzbek brand"). It has
-- been established. This stays staged evidence for a reviewed import into
-- catalog.fiscal_classifications (ADR 0038) — never applied automatically —
-- which is why the column itself is untouched and only the comment changes.
COMMENT ON COLUMN integration.pos_staged_products.government_code IS
    'ADR 0038. Clopos confirmed 2026-09-08 (Q15, docs/providers/clopos-api.md) that gov_code is the ИКПУ/MXIK — the same code FiscalReceiptLine.mxikCode sends onward as Click''s SPIC and Payme''s code, and catalog.mxik_reference holds as reference data. Staged evidence for a reviewed import into catalog.fiscal_classifications; never written there automatically.';
