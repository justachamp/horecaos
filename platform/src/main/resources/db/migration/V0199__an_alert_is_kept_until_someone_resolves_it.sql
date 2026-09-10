-- ADR 0085: a control-plane alert used to be a log line and a counter, so
-- nothing could list it, and nobody could say they had seen it or dealt with
-- it. Each alert is now kept as an incident: raised again while it is still
-- open, it counts another occurrence instead of opening a second one;
-- acknowledged and resolved by name, with a note.
CREATE TABLE notifications.control_plane_alerts (
    id uuid PRIMARY KEY,
    event_class varchar(64) NOT NULL,
    subject_type varchar(64) NOT NULL,
    subject_id varchar(255) NOT NULL,
    -- The alert's own allowlisted vocabulary (ControlPlaneAlert's variables):
    -- a metric id and its numbers, a run id and its step. Never a person.
    variables jsonb NOT NULL DEFAULT '{}'::jsonb,
    first_raised_at timestamptz NOT NULL,
    last_raised_at timestamptz NOT NULL,
    occurrences integer NOT NULL DEFAULT 1,
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    acknowledged_by varchar(255),
    acknowledged_at timestamptz,
    resolved_by varchar(255),
    resolved_at timestamptz,
    resolution_note varchar(1000),
    CONSTRAINT ck_control_plane_alert_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_control_plane_alert_occurrences CHECK (occurrences >= 1),
    CONSTRAINT ck_control_plane_alert_acknowledged CHECK (
        (acknowledged_at IS NULL) = (acknowledged_by IS NULL)),
    CONSTRAINT ck_control_plane_alert_resolved CHECK (
        (status = 'RESOLVED') = (resolved_at IS NOT NULL)
        AND (resolved_at IS NULL) = (resolved_by IS NULL)
        AND (resolved_at IS NULL) = (resolution_note IS NULL))
);

-- One live incident per thing that is wrong: a repeat raise lands on it.
CREATE UNIQUE INDEX ux_control_plane_alert_live ON notifications.control_plane_alerts (event_class, subject_type, subject_id)
    WHERE status <> 'RESOLVED';
CREATE INDEX ix_control_plane_alert_recent ON notifications.control_plane_alerts (last_raised_at DESC);

COMMENT ON TABLE notifications.control_plane_alerts IS
    'ADR 0085. Control-plane alerts kept as incidents: platform-owned, never tenant data, carrying only the allowlisted variables each alert class renders with.';

GRANT SELECT, INSERT, UPDATE ON notifications.control_plane_alerts TO horecaos_application;
