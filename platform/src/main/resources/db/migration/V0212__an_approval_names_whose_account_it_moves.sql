-- ADR 0027 / ADR 0095: a PLATFORM-scope approval request says whose account it
-- moves and what it proposes, so the second signature is never given blind.
--
-- Every field such a row carried was either constant for the action or one-way.
-- tenant_id is null by construction (that is the point of platform scope: the
-- tenant neither reads the request nor signs it), scope_id is null,
-- threshold_description is the sentence the policy froze onto every request of
-- that action, and parameters_hash is a one-way SHA-256. So two refunds raised
-- a minute apart -- fifty million out of one tenant's wallet, five million out
-- of another's -- reached the approver's queue as two rows differing only in a
-- timestamp. The hash binds what was proposed; nothing rendered what was bound.
--
-- The subject is stored as identifiers and integer minor units, never as the
-- maker's prose: ADR 0029 keeps the free-text reason off the console, and this
-- must not become a second uncontrolled copy of it. It is written from the same
-- command record the parameters hash is taken from (see ApprovalParameters), so
-- what the checker reads and what the signature covers cannot drift apart.
ALTER TABLE audit.approval_requests
    -- Deliberately NOT tenant_id. That column is the routing key -- the tenant
    -- worklist, the decision route, the ON CONFLICT dedup and the consume
    -- predicate are all keyed on it -- and a platform decision about a tenant's
    -- account must stay out of that tenant's queue. This column only says whom
    -- the decision is about.
    ADD COLUMN subject_tenant_id uuid,
    ADD COLUMN subject_json jsonb,
    ADD CONSTRAINT fk_approval_request_subject_tenant FOREIGN KEY (subject_tenant_id)
        REFERENCES tenant.tenants (id),
    ADD CONSTRAINT ck_approval_request_subject_json CHECK (
        subject_json IS NULL OR jsonb_typeof(subject_json) = 'object'
    );

COMMENT ON COLUMN audit.approval_requests.subject_tenant_id IS
    'ADR 0095. Whose account a PLATFORM-scope decision concerns. Distinct from tenant_id, which stays NULL on such a row so the tenant''s own worklist and decision route can never reach it; this one exists only so the approver, the platform queue and the audit trail can name the tenant whose money moves.';

COMMENT ON COLUMN audit.approval_requests.subject_json IS
    'ADR 0095. What the request proposes, as canonical scalars taken from the same command record the parameters hash covers: entry type, money kind, signed amount in minor units, currency, and the grant or reference where one applies. Never the maker''s free-text reason (ADR 0029).';

-- Whose decisions are waiting about one tenant. Asked by the audit trail and by
-- anyone reconstructing what was proposed against a tenant that disputes it,
-- including the proposals that were declined or lapsed and therefore left no
-- wallet entry behind.
CREATE INDEX ix_approval_request_subject_tenant
    ON audit.approval_requests (subject_tenant_id, requested_at DESC)
    WHERE subject_tenant_id IS NOT NULL;
