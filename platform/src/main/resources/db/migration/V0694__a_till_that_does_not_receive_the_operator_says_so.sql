-- Gap-map row 9.2c: the operator a colleague is paired with on a till is sent to no till.
--
-- A staff member's pairing with the till's own operator id has had a writer (the OPERATOR tab of
-- an installation's mapping pane) and a reader (PosOrderExportService resolves it onto
-- OrderExport#operatorExternalId) and no sender: CloposAdapter never reads the field, because
-- Clopos's documented CreateOrderRequest has no waiter, cashier or user field. This migration adds
-- the vendor-ceiling half of the sender: a capability, ORDER_OPERATOR_ATTRIBUTION, that is
-- SUPPORTED only for a vendor whose order API carries an operator.
--
-- integration.pos_capability_is_supported() (V0036) refuses a POS capability nobody has assessed,
-- so the new code needs a ceiling row for every provider the build wires before any binding can
-- name it, and it is a ceiling row and not a column or a CHECK because capability_code is a free
-- varchar in both integration.pos_provider_capabilities and integration.binding_capabilities (the
-- enum PosCapability is the vocabulary, as it was for ADR 0171's LOYALTY_BALANCE_SYNC in V0555,
-- which also needed only a row).
--
--   clopos   UNSUPPORTED  docs/providers/clopos-api.md section 6.5 (read 2026-08-23). The row is the
--                         reason a binding cannot enable it and the reason the console says
--                         "this till does not receive an operator yet". If Clopos answers Q23 with a
--                         field, this row becomes SUPPORTED in a new migration (never edited here)
--                         and CloposAdapter gains one line.
--   fake-pos SUPPORTED    the controlled fake of ADR 0007. It puts the operator on its recorded wire
--                         body, so the contract "an adapter that declares it sends it" is proved
--                         against something that does, and the refusal path against Clopos.

INSERT INTO integration.pos_provider_capabilities
    (provider_type, capability_code, support_level, rationale, evidence_source, assessed_on)
VALUES
    ('clopos', 'ORDER_OPERATOR_ATTRIBUTION', 'UNSUPPORTED',
     'CreateOrderRequest names sale_type_id, venue_id, customer and products, and nothing that identifies the person who took the order; the documentation says every call acts on behalf of the Staff user the credential was created for, which points to no per-order operator. GET /users lists the brand''s users, so a colleague can be paired from the till''s own list, but the pairing reaches no order. Question Q23 asks Clopos whether POST /orders accepts a user, waiter or cashier.',
     'clopos openapi.json CreateOrderRequest; docs/providers/clopos-api.md section 6.5', DATE '2026-08-23'),
    ('fake-pos', 'ORDER_OPERATOR_ATTRIBUTION', 'SUPPORTED',
     'The controlled fake puts the operator id on the body it records for a create, so the adapter contract can be proved against a till that carries one.',
     'ADR 0007 controlled fake provider', DATE '2026-10-14');
