-- ADR 0166, Decision 8: the reference a tenant's fiscal agent returned becomes a fiscal document,
-- and the second person signs what is attached, not only that it was asked for.
--
-- V0551 put the second signature on the request ("fiscal.correction.request"). The attachment that
-- follows it was single-handed: whoever held fiscal.document.resolve typed a fiscal sign, a receipt number
-- and a link, and HorecaOS recorded them as an ISSUED CORRECTION or REFUND, moved the original to CORRECTED
-- or VOIDED, and showed the link to the customer on their order page. One person could therefore put any
-- reference at all, and any link at all, in front of a customer, with the approval of a request that said
-- nothing about either.
--
-- "fiscal.correction.attach" is the second signature on that step. Its approval is bound by the
-- parameters hash to the exact reference typed (the request, the fiscal sign, the receipt number, the link,
-- the terminal and the registration time), so the checker approves the thing that is attached and a
-- different one typed afterwards needs its own signature. Fail-closed like its sibling, seeded at platform
-- scope so the action is governed from the first day; a deployment that deleted it should stop attachments,
-- not let one person decide them alone. The approver holds the same capability the attachment needs.
INSERT INTO audit.approval_policies (
    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
    valid_from, version, approved_by)
VALUES
    ('0192d1a0-0000-7000-8000-000000000642', NULL, 'fiscal.correction.attach', 'PLATFORM',
     '{"description": "Every attachment of a fiscal agent''s reference to a correction or void request"}'::jsonb,
     'fiscal.document.resolve', '2026-10-12T00:00:00Z', 1, 'migration V0642');
