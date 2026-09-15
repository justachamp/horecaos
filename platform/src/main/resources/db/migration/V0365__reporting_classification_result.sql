-- T14 (7.7a/7.7b, ADR 0134): one product's ABC and XYZ classification under
-- one classification_run -- see that table's own comment for why a run is
-- not a fact_* table. Every row here is exactly what the console's ABC tab,
-- XYZ tab and the AX/CZ matrix filter all read.

CREATE TABLE reporting.classification_result (
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    variant_id uuid NOT NULL,

    category_id uuid,
    -- Snapshotted at run time, the same reason fact_order_line keeps
    -- product_name_snapshot: a renamed or deleted product must not turn a
    -- disputed run's own table blank.
    product_name varchar(255) NOT NULL,

    revenue_gross_som bigint NOT NULL,
    revenue_share_basis_points integer NOT NULL,
    cumulative_share_basis_points integer NOT NULL,
    abc_class char(1) NOT NULL,

    quantity_total integer NOT NULL,
    mean_quantity_per_bucket double precision NOT NULL,
    stddev_quantity_per_bucket double precision NOT NULL,
    coefficient_of_variation_basis_points integer NOT NULL,
    xyz_class char(1) NOT NULL,

    CONSTRAINT pk_classification_result PRIMARY KEY (tenant_id, run_id, variant_id),
    CONSTRAINT fk_classification_result_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES reporting.classification_run (tenant_id, id),
    CONSTRAINT ck_classification_result_revenue CHECK (revenue_gross_som >= 0),
    CONSTRAINT ck_classification_result_share CHECK (
        revenue_share_basis_points BETWEEN 0 AND 10000
        AND cumulative_share_basis_points BETWEEN 0 AND 10000),
    CONSTRAINT ck_classification_result_abc_class CHECK (abc_class IN ('A', 'B', 'C')),
    CONSTRAINT ck_classification_result_quantity CHECK (quantity_total > 0),
    CONSTRAINT ck_classification_result_cv CHECK (coefficient_of_variation_basis_points >= 0),
    CONSTRAINT ck_classification_result_xyz_class CHECK (xyz_class IN ('X', 'Y', 'Z'))
);

COMMENT ON TABLE reporting.classification_result IS
    'T14/ADR 0134. One product row under one reporting.classification_run. abc_class is cumulative revenue share against the run''s own recorded thresholds; xyz_class is the coefficient of variation of quantity sold per bucket_days-wide bucket against the run''s own recorded thresholds.';

-- The matrix filter's own read: every AX (or any cell) row of one run.
CREATE INDEX ix_classification_result_matrix ON reporting.classification_result
    (tenant_id, run_id, abc_class, xyz_class);

GRANT SELECT, INSERT ON reporting.classification_result TO horecaos_application;
