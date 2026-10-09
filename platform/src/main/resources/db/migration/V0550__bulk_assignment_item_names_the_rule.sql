-- ADR 0172 Decision 6: «the item records the rule the plan matched». A person's bulk assignment overrides
-- a dispatch rule and says so; the audit fact of the assignment already names the rule, and this puts the
-- same id on the item, so the result panel and a replay can say which rule a courier was put ahead of
-- without reading the audit log. An id only, never the rule's name or content, and a reference with no
-- foreign key: a rule is a line of a versioned policy document, not a row.

ALTER TABLE ordering.bulk_operation_items
    ADD COLUMN rule_id varchar(64);

COMMENT ON COLUMN ordering.bulk_operation_items.rule_id IS
    'ADR 0172. The id of the dispatch rule the plan''s stored decision matched when this assignment was made (DEFAULT when the document''s own default decided). Recorded, never evaluated: an assignment by a person does not re-run or edit a rule.';

-- The existing grants (SELECT, INSERT, UPDATE on both tables, V0193) cover the new column.
