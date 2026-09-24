-- ADR 0039: the seven financial amendment commands (gap map rows 1.2c, 2.1d).
--
-- V0029's own comment on ordering.order_lines said this grant "arrives with"
-- the first line-changing command: "Closing a line at a revision boundary is
-- what the first line-changing command needs, and the grant arrives with it.
-- Until then the append-only property V0022 argues for is still true of every
-- row in the table." ADD_LINES and CHANGE_LINE_QUANTITY are that command —
-- both close a superseded line by writing its revision_to rather than editing
-- its amount in place, exactly the pattern V0022's own comment already
-- described for a future quantity change. No row is ever edited outside that
-- one column: quantity, prices and names stay exactly as they were snapshotted,
-- for every line at every revision.

GRANT UPDATE (revision_to) ON ordering.order_lines TO horecaos_application;

COMMENT ON COLUMN ordering.order_lines.revision_to IS
    'ADR 0039. The revision at which this line stopped being part of the order; null while it is still current. Written for the first time by ADD_LINES/CHANGE_LINE_QUANTITY (wave 10): a changed line is closed here and a new row is appended at the next line number, never edited in place.';
