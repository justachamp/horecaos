-- ADR 0165, Decision 3: HorecaOS holds no customer funds, so the one back door the
-- structure does not already close -- a sale disguised as a manual points adjustment --
-- narrows by making the operator's reason code a closed list.
--
-- The list is the ADR 0030 policy `loyalty.adjustment_reason_codes`, settable at PLATFORM
-- scope only (a tenant that could widen it could add a code that means "paid in cash").
-- It ships in REPORT_ONLY mode: a code off the list is accepted and counted for one
-- release, then the stored document is flipped to ENFORCE by a platform write.
--
-- Seeded here so the audit fact of every adjustment can persist the policy id and version
-- it was permitted under from the first day (ADR 0030: a durable decision persists the
-- policy it used), rather than only after somebody first authors a document. No table, no
-- column: V0042 fixes `loyalty.entries` and the application role may only insert into it.
-- The hash is the SHA-256 of the document text exactly as the policy author serialises it,
-- so a later authoring of an identical document is recognisable as identical.

INSERT INTO tenant.policies (
    id, key_code, scope_type, version, status, document, document_hash,
    valid_from, created_by, approved_by)
VALUES (
    '018fe800-1650-7000-8000-000000000165',
    'loyalty.adjustment_reason_codes',
    'PLATFORM',
    1,
    'ACTIVE',
    CAST('{"mode":"REPORT_ONLY","codes":["GOODWILL","SERVICE_RECOVERY","CORRECTION","ACCOUNT_MERGE","LEGACY_OPENING_BALANCE"]}' AS jsonb),
    '8f0996df55ca2a8b283ae9c20e69e58b42dbbc08dcc9f2e21a1d6b24c99ec474',
    now(),
    'migration:V0552',
    'migration:V0552');

INSERT INTO tenant.policy_current (
    key_code, scope_type, policy_id, policy_version, activated_by)
VALUES (
    'loyalty.adjustment_reason_codes',
    'PLATFORM',
    '018fe800-1650-7000-8000-000000000165',
    1,
    'migration:V0552');
