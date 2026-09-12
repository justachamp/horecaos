-- 10.10d, continued: which branches carry which tags. Separate from V0256's
-- registry for the reason every other tag-shaped table in this schema keeps
-- them apart — a tag can be renamed or archived without touching a single
-- assignment row, and a branch can be re-tagged without touching the
-- registry.
CREATE TABLE tenant.location_branch_tags (
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    tag_id uuid NOT NULL,

    assigned_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, location_id, tag_id),
    CONSTRAINT fk_location_branch_tags_location FOREIGN KEY (tenant_id, location_id)
        REFERENCES tenant.locations (tenant_id, id),
    CONSTRAINT fk_location_branch_tags_tag FOREIGN KEY (tenant_id, tag_id)
        REFERENCES tenant.branch_tags (tenant_id, id)
);

-- The filter-and-group direction: "every branch carrying this tag".
CREATE INDEX ix_location_branch_tags_tag ON tenant.location_branch_tags (tenant_id, tag_id);

COMMENT ON TABLE tenant.location_branch_tags IS
    '10.10d. Which branch carries which tag. A branch may carry any number of tags; a tag may be archived out from under an assignment, which is why reads join through V0256''s status column rather than assuming every row here is live.';

GRANT SELECT, INSERT, DELETE ON tenant.location_branch_tags TO horecaos_application;
