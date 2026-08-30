-- ADR 0026: the approved endpoint for the VAS SMS gateway.
--
-- An installation names an environment code and never a URL, which is where the
-- server-side request forgery path is closed — at the model, not in a validator
-- somebody can forget. The consequence is that an adapter without a row here is
-- an adapter nothing can be configured to reach: the SMS verification route
-- (docs/routes/sms-verification.md) resolves its base URL by joining
-- integration.installations to this table, so until this row exists the whole
-- verification path is unreachable no matter what else is built.
--
-- `provider_type` is 'SMSGW_VAS' because that is the exact string
-- VasSmsGatewayAdapter.PROVIDER_TYPE compares against before it will speak this
-- protocol to a bound endpoint. The gateway refuses any other value by name
-- rather than assuming the SEND_SMS capability implies this provider — ADR 0020's
-- notification path shares that capability code.
--
-- 'NOTIFICATION' is already an accepted value of ck_provider_environment_category
-- (V0013, widened by V0038 for MARKETPLACE), and ProviderCategory.NOTIFICATION
-- exists, so the read path parses it. The base URL satisfies
-- ck_provider_environment_url. No table is created here, so no new GRANT is due:
-- V0035 granted SELECT — and only SELECT — on integration.provider_environments
-- to horecaos_application, which is exactly what the join above needs, and V0036
-- re-granted USAGE on the schema.
--
-- -------------------------------------------------------------- one row, not two
--
-- There is deliberately NO non-production sibling of this row, and that absence
-- is a decision rather than an oversight.
--
-- The provider's document (docs/providers/sms-gateway-vas.md, transcribed from
-- sms_gate_doc_v4.4.pdf) names no sandbox, no test host and no test account. Its
-- "what the document does not say" section records that gap explicitly, and every
-- example in it uses what looks like a live account sending to a real Uzbek
-- number. There is therefore no second base URL to approve.
--
-- Do not resolve that by adding a row with is_production = false pointing at
-- https://smsgw.vas.uz/api/v2. A staging installation carrying a non-production
-- environment code and a live endpoint is an installation whose name says one
-- thing while its behaviour is charged, delivered SMS to whoever is in the phone
-- column — and the first person to discover the difference is a real subscriber.
-- Pre-production exercise of this route runs against ADR 0007's
-- ControlledFakeProvider instead, which is what the route descriptor's rollout
-- section already prescribes.
--
-- If the provider ever documents a sandbox, it arrives as its own migration with
-- its own host in egress_allowlist, and this comment is what says the new row is
-- new information rather than a correction.

INSERT INTO integration.provider_environments
    (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
VALUES
    ('smsgw_vas_production', 'NOTIFICATION', 'SMSGW_VAS',
     'https://smsgw.vas.uz/api/v2', true, 'smsgw.vas.uz',
     'The only host the provider documents. No non-production sibling exists on purpose: the provider names no sandbox and every example in its document uses a live account, so pre-production runs against ADR 0007 ControlledFakeProvider rather than against a staging row pointed at production.')
ON CONFLICT (code) DO NOTHING;
