-- ADR 0177 decision 5, status addition of 2026-10-23. ordering.preorder_branch_resolution is declared
-- settable at PLATFORM, TENANT and BRAND only, because BranchResolutionQueryService reads it for the whole
-- brand (it ranks a brand's branches against each other, so a branch has no value of its own to give).
--
-- Until that change the key took a value at any scope, and the order-policy page offered it on a branch. A
-- row stored at LOCATION scope is therefore a value somebody set that nothing read. It is removed here
-- because, left in place, the page would show it on that branch as "Set at brand level" next to the brand's
-- real answer and could not clear it: the configuration writer refuses every scope the key is not settable
-- at, and a revert is a write.
--
-- The delete is a no-op on an estate where nobody set the field on a branch (the key had no reader when it
-- was registered, and no environment has run in production), and a real one for a developer's or a
-- reviewer's database where somebody did. The PLATFORM, TENANT and BRAND rows of the key are the ones the
-- resolver reads and stay. Audit facts that mention a branch value stay too: they are append-only evidence
-- of what was once set.
DELETE FROM tenant.configuration_values
 WHERE key_code = 'ordering.preorder_branch_resolution'
   AND scope_type = 'LOCATION';
