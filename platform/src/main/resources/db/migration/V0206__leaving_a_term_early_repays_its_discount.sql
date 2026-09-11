-- ADR 0093, as decided on 2026-09-11: a tenant who leaves a 3-, 6- or 12-month
-- term before it ends repays the term discount it received. The statement for
-- the month the subscription ended carries one EARLY_EXIT line: the number of
-- charges made at the discounted price, at the difference between the full and
-- the discounted price. Like every other line it is a charge, so it keeps the
-- non-negative quantity, price and amount checks V0202 wrote.
--
-- `kind` was varchar(8), which fits PLAN, MODULE, OVERAGE and DEPOSIT and not
-- EARLY_EXIT. Widening a varchar is a catalogue change only; no row is
-- rewritten, so the freeze triggers on issued lines never fire.

ALTER TABLE commercial.statement_lines ALTER COLUMN kind TYPE varchar(16);

ALTER TABLE commercial.statement_lines DROP CONSTRAINT ck_statement_line_kind;
ALTER TABLE commercial.statement_lines
    ADD CONSTRAINT ck_statement_line_kind
        CHECK (kind IN ('PLAN', 'MODULE', 'OVERAGE', 'DEPOSIT', 'EARLY_EXIT'));

COMMENT ON CONSTRAINT ck_statement_line_kind ON commercial.statement_lines IS
    'ADR 0088/0093. EARLY_EXIT repays the term discount of a subscription that ended before its term did.';
