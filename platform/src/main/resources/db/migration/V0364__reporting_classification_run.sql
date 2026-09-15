-- T14 (7.7a/7.7b, ADR 0134): the persisted ABC/XYZ run. statistics.md S2.7
-- states the whole reason this table exists: "Delever's ABC page states the
-- 80/15/5 split in prose and shows a class letter; a manager who disputes a
-- product being class C has nothing to look at." A run's exact window and
-- thresholds -- the numbers a manager can point to -- have to outlive the
-- request that produced them, or the dispute has nothing to check against.
--
-- Deliberately not a reporting.fact_* table. DayCloseService's facts are
-- rebuildable projections of an operational event, recomputed the same way
-- from the same source every time; a classification run is the opposite -- a
-- deliberate, capability-gated act (reporting.classification.run) whose own
-- record, taken at the instant it was requested, is the point. Re-running it
-- later over the same window can legitimately answer differently (more
-- orders may have closed since), and both runs stay on the shelf rather than
-- one overwriting the other.
--
-- location_ids is a comma-joined list of uuids rather than a native uuid[]
-- or jsonb column -- empty means "every location" -- kept to the simplest
-- representation that is still exactly as inspectable in a dispute, since
-- nothing here ever queries inside it.

CREATE TABLE reporting.classification_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,

    from_date date NOT NULL,
    to_date date NOT NULL,
    location_ids text NOT NULL DEFAULT '',

    -- statistics.md's own printed line: "metric revenue.gross.v1". ABC ranks
    -- by gross, not net -- a promotion given away as a discount still counts
    -- as the sale that earned the product its shelf space.
    metric_code varchar(64) NOT NULL,
    abc_threshold_a_basis_points integer NOT NULL,
    abc_threshold_b_basis_points integer NOT NULL,
    xyz_threshold_x_basis_points integer NOT NULL,
    xyz_threshold_y_basis_points integer NOT NULL,
    -- The sub-window the XYZ coefficient of variation is computed over, and
    -- how many of them fit the requested range. The >=28-day floor is chosen
    -- so a 7-day bucket always has at least four points to vary across.
    bucket_days integer NOT NULL,
    bucket_count integer NOT NULL,

    product_count integer NOT NULL,
    -- A staff Keycloak subject, on the same footing reporting.fact_order's
    -- own operator_principal_id already keeps in the clear (ADR 0029 protects
    -- customer data; this is not a customer).
    requested_by varchar(255) NOT NULL,
    computed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_classification_run PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_classification_run_range CHECK (to_date >= from_date),
    -- The floor itself, enforced by the schema and not only by the service:
    -- statistics.md "the view refuses ranges under 28 days for ABC/XYZ,
    -- because a Pareto over four days is an artefact of one large party
    -- order."
    CONSTRAINT ck_classification_run_window_floor CHECK (to_date - from_date + 1 >= 28),
    CONSTRAINT ck_classification_run_abc_thresholds CHECK (
        abc_threshold_a_basis_points > 0
        AND abc_threshold_a_basis_points < abc_threshold_b_basis_points
        AND abc_threshold_b_basis_points <= 10000),
    CONSTRAINT ck_classification_run_xyz_thresholds CHECK (
        xyz_threshold_x_basis_points > 0
        AND xyz_threshold_x_basis_points < xyz_threshold_y_basis_points),
    CONSTRAINT ck_classification_run_bucket_days CHECK (bucket_days > 0),
    CONSTRAINT ck_classification_run_bucket_count CHECK (bucket_count > 0),
    CONSTRAINT ck_classification_run_product_count CHECK (product_count >= 0)
);

COMMENT ON TABLE reporting.classification_run IS
    'T14/ADR 0134. One row per requested ABC/XYZ classification: the exact window, thresholds and metric a manager can point to when a class-C ruling is disputed. Never updated or deleted -- a re-run over the same window is a new row, not a correction of this one.';

-- The console's own read: a tenant's runs, most recent window first, to find
-- "is there already a run for roughly this range" before starting a new one.
CREATE INDEX ix_classification_run_tenant_window ON reporting.classification_run
    (tenant_id, from_date, to_date, computed_at DESC);

GRANT SELECT, INSERT ON reporting.classification_run TO horecaos_application;
