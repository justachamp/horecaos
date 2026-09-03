-- staff-and-access.md §11.12: the operations activity log (Staff 9.3) needs
-- outcome and scope predicates that AuditQueryService.AuditQuery did not carry
-- before this wave, plus a correlation id lookup for the drawer's «Часть
-- массового действия» chip (ADR 0039: a bulk action is N audited operations
-- sharing one correlation_id, never one row).
--
-- ix_audit_correlation (V0007) already covers the chip's own lookup. The two
-- new predicates this wave adds to AuditQueryService.search -- outcome and
-- (scope_type, scope_id) -- have no supporting index: V0007 indexes
-- (tenant_id, recorded_at), (actor_subject, recorded_at), (target_type,
-- target_id, recorded_at) and (action_code, recorded_at), none of which leads
-- with tenant_id plus either new column.
--
-- «Где» (scope) is the more selective of the two on a real tenant's data --
-- filtering the whole log to one branch is the shape a manager reaches for --
-- so it gets the composite index with recorded_at trailing for the sort this
-- screen always applies. Outcome alone is coarse (three values) and stays a
-- residual filter on the existing tenant_id/recorded_at index rather than
-- earning its own.

CREATE INDEX ix_audit_tenant_scope
    ON audit.audit_events (tenant_id, scope_type, scope_id, recorded_at DESC);
