-- ADR 0078: a suspended tenant may read, and may not export.
--
-- ADR 0077 refused a suspended tenant everything. The owner's answer on
-- 2026-09-08 narrowed that: a suspended tenant keeps read access -- it can still
-- see its own orders and its own numbers -- and loses everything else. That
-- concession opens the hole this table closes. "You may still read" is an export
-- channel if nobody counts: a listing endpoint called in a loop hands over the
-- whole customer book just as effectively as a download button would, and the
-- tenant whose access is being wound down is exactly the party with a motive.
--
-- So a suspended tenant's reads are rationed: three requests per endpoint per
-- rolling ninety days. Enough to look something up and settle an argument about
-- an invoice; nowhere near enough to enumerate a database.
--
-- Why a table rather than the in-process token bucket the platform already has:
-- that bucket lives in one JVM's heap. It refills continuously, it is empty
-- after a restart, and a second replica has its own. All three are fine for
-- "stop hammering this endpoint" and none of them is fine for a ninety-day
-- allowance, where a deploy would silently hand the tenant three more reads.
-- The window here is long enough that the counter has to outlive the process
-- counting it.
--
-- Append-only rather than a counter column, because the question an auditor asks
-- later is "what did this tenant read while it was suspended, and when", and a
-- decremented integer cannot answer it.
CREATE TABLE tenant.suspended_read_log (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- The handler, not the URL: a URL carries identifiers, and identifiers of a
    -- suspended tenant's own rows are the thing being rationed, not a key to
    -- ration by. Two different orders read through the same endpoint are two
    -- reads of that endpoint.
    endpoint varchar(200) NOT NULL,
    -- Who, for the audit question above. Not part of the quota key: the quota is
    -- the tenant's, or adding a staff account would buy three more reads.
    principal_subject varchar(255) NOT NULL,
    read_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_suspended_read_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id)
);

-- The quota query: rows for one tenant and endpoint inside the window. Ordered
-- by time because the window is rolling and the count is taken from the recent
-- end of it.
CREATE INDEX ix_suspended_read_window
    ON tenant.suspended_read_log (tenant_id, endpoint, read_at DESC);

COMMENT ON TABLE tenant.suspended_read_log IS
    'ADR 0078: one row per read served to a suspended tenant, three per endpoint per ninety days. Retained beyond the window on purpose -- the window bounds the quota, not the evidence.';

GRANT SELECT, INSERT ON tenant.suspended_read_log TO horecaos_application;
