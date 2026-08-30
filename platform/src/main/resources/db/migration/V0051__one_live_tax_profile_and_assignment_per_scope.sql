-- Two uniqueness rules the price-authoring service can only narrow in Java.
--
-- Both writers do the correct conditional thing -- close the old row, insert the
-- successor only if nothing else got there first -- and both settle every race
-- except one: the very first write for a brand or a scope. With no existing row
-- to conflict on, two concurrent operators both find nothing, both pass their
-- NOT EXISTS check, and both insert. The result stays deterministic, because
-- resolution orders by valid_from and id, but "deterministic" and "correct" are
-- not the same word: which of two VAT rates a receipt is computed with would be
-- decided by a tiebreak nobody chose.
--
-- With these in place, the Java pre-checks are redundant and should be removed
-- rather than left standing beside a constraint that now states the rule.

-- One open tax profile per brand per jurisdiction.
--
-- `valid_until IS NULL` is what "open" means here: a superseded profile keeps
-- its row and gets an end, because a receipt issued last month must still be
-- explicable by the rate that applied last month.
CREATE UNIQUE INDEX ux_tax_profile_current
    ON pricing.tax_profiles (tenant_id, brand_id, jurisdiction_code)
    WHERE valid_until IS NULL;

-- One assignment per price book per scope.
--
-- scope_id is null for a BRAND assignment, and NULL is distinct from itself in a
-- unique index, so a plain three-column index would let the same brand-wide
-- assignment be inserted without limit. COALESCE to the nil UUID gives those
-- rows a value to collide on. The nil UUID is safe as a sentinel because it is
-- not a valid brand, location or channel identifier anywhere in this schema.
CREATE UNIQUE INDEX ux_assignment_per_scope
    ON pricing.price_book_assignments
       (price_book_id, scope_type, COALESCE(scope_id, '00000000-0000-0000-0000-000000000000'::uuid));
