-- ADR 0005 first business consumer, and ADR 0023's read-model principle.
--
-- The control plane answers "what does this tenant look like" by joining three
-- tables and counting rows. This projection is maintained by the tenancy events
-- the outbox already publishes, which makes it the first genuine consumer and
-- exercises the whole poll -> inbox -> commit -> acknowledge path in production
-- shape rather than only in tests.
--
-- It is a projection, never an authority. Losing it entirely is a rebuild, not
-- a data loss, which is why nothing writes to it except the projection handler.

CREATE TABLE reporting.tenant_summaries (
    tenant_id uuid PRIMARY KEY,
    slug varchar(63) NOT NULL,
    display_name varchar(255),
    status varchar(24) NOT NULL,
    default_currency char(3),
    default_timezone varchar(64),
    customer_identity_mode varchar(24),
    brand_count integer NOT NULL DEFAULT 0,
    location_count integer NOT NULL DEFAULT 0,
    first_seen_at timestamptz NOT NULL,
    last_event_at timestamptz NOT NULL,
    CONSTRAINT ck_tenant_summary_counts CHECK (brand_count >= 0 AND location_count >= 0)
);

CREATE INDEX ix_tenant_summary_status ON reporting.tenant_summaries (status, slug);

COMMENT ON TABLE reporting.tenant_summaries IS
    'ADR 0005 projection maintained from tenancy.events. A read model, never an authority: rebuild it rather than repair it.';
