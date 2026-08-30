-- ADR 0045: field telemetry — duty sessions, the live position working set, the
-- encrypted daily track, and the summary that outlives it.
--
-- The real-time push half of ADR 0045 has no schema. A stream is process-local
-- state that dies with its replica, which is exactly why the client reconnects
-- and resyncs rather than reading a subscription row, and the ADR says so in as
-- many words: "There is no subscription table."
--
-- ---------------------------------------------------------------------------
-- Why these tables are in `fulfillment` and the code is in `telemetry`
-- ---------------------------------------------------------------------------
--
-- ADR 0045 names them `fulfillment.*` and that is right: a duty session, a
-- position, and a track are facts about a courier and a shipment, which is
-- fulfillment's subject, and ADR 0042's shift and settlement tables land in the
-- same schema. The Java lives in a `telemetry` module because the collection
-- gate, the retention floor, and the reveal audit are one concern with one owner
-- and no business logic in common with delivery fees or zones. A schema is an
-- ownership statement about data; a module is an ownership statement about code,
-- and they are allowed to differ when the data outlives the module that writes
-- it.
--
-- ---------------------------------------------------------------------------
-- Three retention tiers, and the reason each is a different shape
-- ---------------------------------------------------------------------------
--
-- The live row is a working set. It holds one row per courier with an open duty
-- session and is deleted an hour after that session closes, which is what pays
-- for the ADR 0029 exception below.
--
-- The track is a history, so it is envelope encrypted, partitioned by day, and
-- dropped by dropping a partition. A DELETE sweep over a hundred couriers at six
-- observations a minute — of the order of 360,000 rows a day per tenant — on the
-- one box that also runs Kafka, Keycloak, MinIO, and OpenBao is a nightly
-- vacuum problem this platform does not need to have.
--
-- The summary is money's evidence and survives with the shipment. It is two
-- coordinates and three numbers, not a path.

-- ---------------------------------------------------------------------------
-- 1. Duty sessions
-- ---------------------------------------------------------------------------
--
-- The window in which collection happens at all. Nothing else in this file may
-- be written without an open row here, which is the whole of ADR 0045's
-- "collection that continues after a courier signs off is the failure this
-- section exists to prevent".
--
-- `shift_id` carries no foreign key yet. ADR 0042 owns `fulfillment.courier_shifts`
-- and has not landed; a nullable column would say a session may exist without a
-- shift, which is the opposite of the decision, so the column is NOT NULL and the
-- reference is added by ADR 0042's migration. Until then the application refuses
-- to open a session at all, because the port that checks the shift and the
-- registration is unwired and fails closed rather than open.
CREATE TABLE fulfillment.courier_duty_sessions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant.tenants (id),
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    shift_id uuid NOT NULL,
    device_id varchar(128) NOT NULL,
    status varchar(16) NOT NULL,
    collection_gate varchar(16) NOT NULL,
    registration_checked_at timestamptz NOT NULL,
    registration_valid_until date NOT NULL,
    opened_by_subject varchar(255) NOT NULL,
    started_at timestamptz NOT NULL,
    suspended_at timestamptz,
    ended_at timestamptz,
    end_reason varchar(32),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_duty_session_status CHECK (status IN ('OPEN', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_duty_session_gate CHECK (collection_gate IN ('ON_DUTY', 'ON_ASSIGNMENT')),
    CONSTRAINT ck_duty_session_version CHECK (version >= 1),
    -- Pair completeness stated as an equality rather than as a disjunction: an
    -- OR of two positives is satisfied by a row with one of them set, which is
    -- the three-valued-logic hole this codebase keeps out of its CHECKs.
    CONSTRAINT ck_duty_session_closed CHECK ((status = 'CLOSED') = (ended_at IS NOT NULL)),
    CONSTRAINT ck_duty_session_end_reason CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
    -- A break suspends collection (ADR 0042); resuming clears the timestamp, so
    -- the flag and the time are one fact and never two.
    CONSTRAINT ck_duty_session_suspended CHECK ((status = 'SUSPENDED') = (suspended_at IS NOT NULL)),
    CONSTRAINT ck_duty_session_window CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT fk_duty_session_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- Lets the live row and the track name a session and a tenant in one key, so
    -- a position can never be attached to another tenant's session.
    CONSTRAINT uq_duty_session_identity UNIQUE (id, tenant_id, courier_id)
);

COMMENT ON TABLE fulfillment.courier_duty_sessions IS
    'ADR 0045. The window in which courier telemetry is collected. Opened only from an ADR 0042 shift whose self-employment registration is valid, suspended by a break, and closed at sign-off; nothing else in this schema may be written without an open row here.';
COMMENT ON COLUMN fulfillment.courier_duty_sessions.shift_id IS
    'ADR 0042 shift this session was opened from. No foreign key yet because fulfillment.courier_shifts does not exist; ADR 0042''s migration adds it. NOT NULL rather than nullable because a session without a shift is the thing being refused.';
COMMENT ON COLUMN fulfillment.courier_duty_sessions.collection_gate IS
    'ADR 0045, resolved through ADR 0030. ON_DUTY collects for the whole session so a dispatcher can see idle couriers to assign them; ON_ASSIGNMENT collects only while carrying an order. Both are implemented, so a narrower answer from legal is a configuration change rather than a redesign.';
COMMENT ON COLUMN fulfillment.courier_duty_sessions.registration_valid_until IS
    'ADR 0042''s registration expiry, copied here at open time. An expired registration turns a compliant arrangement into an undeclared one, and this column is the evidence that somebody checked before collection started.';
COMMENT ON COLUMN fulfillment.courier_duty_sessions.suspended_at IS
    'When the current break began. A courier on break is not assignable, so the pin has no operational use and collection stops; resuming clears this.';

-- One open session per courier. Two would make "is this courier being tracked"
-- depend on which row a query read first, and would let a closed session leave a
-- second one collecting.
CREATE UNIQUE INDEX ux_duty_session_open_per_courier
    ON fulfillment.courier_duty_sessions (tenant_id, courier_id)
    WHERE ended_at IS NULL;

-- One open session per shift, so a repeated open from a reconnecting device
-- rejoins rather than forking.
CREATE UNIQUE INDEX ux_duty_session_open_per_shift
    ON fulfillment.courier_duty_sessions (tenant_id, shift_id)
    WHERE ended_at IS NULL;

-- The dispatcher board's question: who is on duty at this branch right now.
CREATE INDEX ix_duty_session_open_at_location
    ON fulfillment.courier_duty_sessions (tenant_id, location_id)
    WHERE ended_at IS NULL;

-- The sweeper's question: which closed sessions are past their live-row hour.
CREATE INDEX ix_duty_session_ended
    ON fulfillment.courier_duty_sessions (ended_at)
    WHERE ended_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. The live position
-- ---------------------------------------------------------------------------
--
-- One row per courier, overwritten in place. This is the table ADR 0045 makes a
-- deliberate exception to ADR 0029 for, and the exception should be the first
-- thing a security review objects to.
--
-- What buys it: envelope-encrypted coordinates cannot be queried, and "which
-- couriers are inside this zone, nearest this branch" is the dispatcher board's
-- central question — a difficulty ADR 0029 names among its own negative
-- consequences. What pays for it: only couriers with an open session are here,
-- the rows are deleted an hour after the session closes, reading needs
-- `courier.position.read` at the location scope, and no reporting or support
-- database role is granted anything on it at the bottom of this file.
CREATE TABLE fulfillment.courier_positions_live (
    tenant_id uuid NOT NULL REFERENCES tenant.tenants (id),
    courier_id uuid NOT NULL,
    duty_session_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    position geography(Point, 4326) NOT NULL,
    accuracy_meters numeric(7, 1) NOT NULL,
    heading_degrees numeric(5, 2),
    speed_mps numeric(6, 2),
    battery_percent smallint,
    device_charging boolean,
    active_assignment_count integer NOT NULL DEFAULT 0,
    captured_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_courier_positions_live PRIMARY KEY (tenant_id, courier_id),
    CONSTRAINT ck_live_accuracy CHECK (accuracy_meters >= 0),
    CONSTRAINT ck_live_heading CHECK (heading_degrees IS NULL
        OR (heading_degrees >= 0 AND heading_degrees < 360)),
    CONSTRAINT ck_live_speed CHECK (speed_mps IS NULL OR speed_mps >= 0),
    CONSTRAINT ck_live_battery CHECK (battery_percent IS NULL
        OR (battery_percent BETWEEN 0 AND 100)),
    -- Battery percent and charging state are one reading from one handset. A row
    -- carrying one of them describes a phone nobody can act on.
    CONSTRAINT ck_live_device_pair CHECK ((battery_percent IS NULL) = (device_charging IS NULL)),
    CONSTRAINT ck_live_assignments CHECK (active_assignment_count >= 0),
    CONSTRAINT fk_live_duty_session FOREIGN KEY (duty_session_id, tenant_id, courier_id)
        REFERENCES fulfillment.courier_duty_sessions (id, tenant_id, courier_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_live_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id)
);

COMMENT ON TABLE fulfillment.courier_positions_live IS
    'ADR 0045. The dispatcher''s working set, never a history: one row per courier with an open duty session, overwritten in place, deleted an hour after the session closes. Coordinates are cleartext, a named exception to ADR 0029 whose compensating controls are the session gate, the deletion, the LOCATION-scoped capability, and the absence of any reporting grant.';
COMMENT ON COLUMN fulfillment.courier_positions_live.accuracy_meters IS
    'ADR 0045. Worse than 100 m is stored in the track and never drawn on the map: a 900 m accuracy circle rendered as a pin is a confident lie.';
COMMENT ON COLUMN fulfillment.courier_positions_live.battery_percent IS
    'ADR 0045. Live row only, never written to the track. A dispatcher needs to know a phone will die mid-delivery; a battery history is a work-pattern archive with no operational use.';
COMMENT ON COLUMN fulfillment.courier_positions_live.captured_at IS
    'When the handset took the reading. An observation older than the stored one never overwrites it, or a courier''s pin jumps back across Tashkent when their phone reconnects.';
COMMENT ON COLUMN fulfillment.courier_positions_live.received_at IS
    'When the platform stored it. Kept apart from captured_at so a late batch from a basement kitchen is visibly late rather than silently current.';

-- The dispatcher board reads by branch; the map draws the couriers of one
-- location and never of a tenant.
CREATE INDEX ix_live_position_location
    ON fulfillment.courier_positions_live (tenant_id, location_id);

-- "Which couriers are near this pickup" is a distance ordering, which is the
-- reason these coordinates are queryable at all.
CREATE INDEX ix_live_position_geography
    ON fulfillment.courier_positions_live USING gist (position);

-- ---------------------------------------------------------------------------
-- 3. The track
-- ---------------------------------------------------------------------------
--
-- One row per courier per window, holding the observations of that window as a
-- single ADR 0029 protected value. Rows are never read individually by
-- coordinate; a reveal decrypts the windows a declared time range covers, and
-- the two five-character geohashes exist so that range lookup does not have to
-- decrypt every row of the day to find out which ones are relevant. Five
-- characters is about 1.2 km, which locates a window to a district and to
-- nothing narrower.
--
-- Partitioned daily on window_start so retention is a DROP TABLE. That is the
-- difference between a retention rule that runs and one that is documented.
CREATE TABLE fulfillment.courier_location_tracks (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    duty_session_id uuid NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    geohash5_first char(5) NOT NULL,
    geohash5_last char(5) NOT NULL,
    observation_count integer NOT NULL,
    distance_meters integer NOT NULL,
    protected_track text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_courier_location_tracks PRIMARY KEY (window_start, id),
    CONSTRAINT ck_track_window CHECK (window_end >= window_start),
    CONSTRAINT ck_track_observations CHECK (observation_count > 0),
    CONSTRAINT ck_track_distance CHECK (distance_meters >= 0),
    CONSTRAINT ck_track_geohash CHECK (
        geohash5_first ~ '^[0-9b-hjkmnp-z]{5}$' AND geohash5_last ~ '^[0-9b-hjkmnp-z]{5}$')
) PARTITION BY RANGE (window_start);

COMMENT ON TABLE fulfillment.courier_location_tracks IS
    'ADR 0045. The coordinate history, envelope encrypted and partitioned daily. Retained 30 days — the derived floor is settlement period plus statement dispute window, checked at startup — then the partition is dropped whole. Reading one is an audited reveal under courier.track.reveal with a declared purpose.';
COMMENT ON COLUMN fulfillment.courier_location_tracks.protected_track IS
    'ADR 0029 ProtectedValue: the window''s observations serialized and envelope encrypted, bound to this row so a ciphertext copied elsewhere fails to decrypt rather than revealing the wrong courier.';
COMMENT ON COLUMN fulfillment.courier_location_tracks.geohash5_first IS
    'Five characters, about 1.2 km, cleartext and for lookup only. It exists so a time-bounded reveal finds the right rows without decrypting every one; it locates a window to a district and to nothing narrower.';
COMMENT ON COLUMN fulfillment.courier_location_tracks.distance_meters IS
    'Observed telemetry, and deliberately NOT the distance_meters ADR 0042 accrues against — that one is the routing distance quoted at assignment and lives on the earning row. The columns share a name and answer different questions, so no accrual, statement, or settlement job may read this one.';

-- A default partition so an observation can never fail for want of one, plus a
-- fortnight of real partitions to start. The manager keeps them ahead of the
-- clock, exactly as ADR 0027's audit partitions are kept; rows in the default
-- are a symptom rather than a design, because a default partition cannot be
-- dropped by the retention job.
CREATE TABLE fulfillment.courier_location_tracks_default
    PARTITION OF fulfillment.courier_location_tracks DEFAULT;

DO $$
DECLARE
    v_day date := current_date - 1;
BEGIN
    WHILE v_day <= current_date + 14 LOOP
        -- Bounds are written as explicit UTC literals rather than as a cast of a
        -- date, because a cast takes the session's timezone and Flyway's session
        -- is not guaranteed to be the one the sweeper later runs in. A partition
        -- whose day starts at 19:00 the previous evening is dropped five hours
        -- early, which is a retention rule that quietly deletes evidence.
        EXECUTE format(
            'CREATE TABLE fulfillment.courier_location_tracks_%s '
            'PARTITION OF fulfillment.courier_location_tracks '
            'FOR VALUES FROM (%L) TO (%L)',
            to_char(v_day, 'YYYYMMDD'),
            to_char(v_day, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char(v_day + 1, 'YYYY-MM-DD') || ' 00:00:00+00');
        EXECUTE format(
            'GRANT SELECT, INSERT ON fulfillment.courier_location_tracks_%s TO horecaos_application',
            to_char(v_day, 'YYYYMMDD'));
        v_day := v_day + 1;
    END LOOP;
END
$$;

-- ADR 0045 exempts telemetry ingest from the mandatory ADR 0031 idempotency
-- record and makes it idempotent on a natural key instead, because an
-- idempotency row per beacon adds six rows a minute per courier to that table
-- for no benefit. The ADR writes the key as the observation's
-- (tenant_id, courier_id, captured_at); the stored grain here is a one-minute
-- window rather than a row per reading, so the key is the window's start and the
-- upsert keeps whichever write carries more of that minute. A replayed batch
-- therefore changes nothing, and a batch that completes a partially written
-- minute replaces it rather than duplicating it.
CREATE UNIQUE INDEX ux_track_window_natural_key
    ON fulfillment.courier_location_tracks (window_start, tenant_id, courier_id);

-- The reveal's lookup: one courier, one window, ordered.
CREATE INDEX ix_track_courier_window
    ON fulfillment.courier_location_tracks (tenant_id, courier_id, window_start);

CREATE INDEX ix_track_duty_session
    ON fulfillment.courier_location_tracks (tenant_id, duty_session_id);

-- ---------------------------------------------------------------------------
-- 4. The summary that survives the track
-- ---------------------------------------------------------------------------
--
-- ADR 0045 calls this out rather than burying it, because it is the line a
-- security review should question: two coordinates per delivery outlive the
-- 30-day track, under ADR 0029's FINANCIAL rules, because they are the evidence
-- behind a figure somebody was paid. They are envelope encrypted; the distance
-- and the two timestamps are not personal data and are what a dispute is
-- actually argued with.
CREATE TABLE fulfillment.courier_track_summaries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant.tenants (id),
    courier_id uuid NOT NULL,
    duty_session_id uuid NOT NULL,
    shipment_id uuid,
    distance_meters integer NOT NULL,
    observation_count integer NOT NULL,
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    protected_pickup_point text,
    protected_delivery_point text,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_summary_distance CHECK (distance_meters >= 0),
    CONSTRAINT ck_summary_observations CHECK (observation_count > 0),
    CONSTRAINT ck_summary_window CHECK (last_observed_at >= first_observed_at),
    -- A summary with confirmation points but no shipment describes a delivery
    -- that does not exist, and a reviewer cannot tell which of the two is wrong.
    CONSTRAINT ck_summary_pickup_needs_shipment CHECK (
        protected_pickup_point IS NULL OR shipment_id IS NOT NULL),
    CONSTRAINT ck_summary_delivery_needs_shipment CHECK (
        protected_delivery_point IS NULL OR shipment_id IS NOT NULL),
    CONSTRAINT fk_summary_duty_session FOREIGN KEY (duty_session_id, tenant_id, courier_id)
        REFERENCES fulfillment.courier_duty_sessions (id, tenant_id, courier_id)
);

COMMENT ON TABLE fulfillment.courier_track_summaries IS
    'ADR 0045. What survives the track: observed distance, the first and last observation times, and the two confirmation points, kept with the shipment under ADR 0029 FINANCIAL retention. Two coordinates per delivery, not a path.';
COMMENT ON COLUMN fulfillment.courier_track_summaries.distance_meters IS
    'Observed telemetry. No ADR 0042 accrual, statement, or settlement figure reads it: an earning is computed from the routing distance quoted at assignment, so it is byte-identical whether the telemetry behind it is complete, partial, or absent.';
COMMENT ON COLUMN fulfillment.courier_track_summaries.shipment_id IS
    'Null for a session-level summary written when a duty session closes with no shipment attached. The confirmation-point columns are refused without it, because a point without a delivery is evidence of nothing.';

-- One summary per shipment, and one session-level summary per session. Written
-- as two partial indexes rather than one UNIQUE constraint because a NULL
-- shipment_id makes a three-column UNIQUE apply to nothing, which is how a
-- session that closed twice ends up with two contradictory distances.
CREATE UNIQUE INDEX ux_summary_per_shipment
    ON fulfillment.courier_track_summaries (tenant_id, duty_session_id, shipment_id)
    WHERE shipment_id IS NOT NULL;
CREATE UNIQUE INDEX ux_summary_per_session
    ON fulfillment.courier_track_summaries (tenant_id, duty_session_id)
    WHERE shipment_id IS NULL;

CREATE INDEX ix_summary_courier ON fulfillment.courier_track_summaries (tenant_id, courier_id);
CREATE INDEX ix_summary_shipment ON fulfillment.courier_track_summaries (tenant_id, shipment_id)
    WHERE shipment_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. Grants
-- ---------------------------------------------------------------------------
--
-- DELETE is granted on the live table and on duty sessions because deletion is
-- the retention rule: the live row goes an hour after its session closes, and the
-- session row goes with the track window it belongs to. It is not granted on the
-- track, whose retention is a dropped partition rather than a sweep, nor on the
-- summary, which is financial evidence that only stops being true when the
-- shipment's own retention expires.
--
-- Nothing is granted to `horecaos_reporting_read`, and that is the enforceable half
-- of ADR 0045's "the reporting and support database roles hold no grant on either
-- table, so a position cannot be reached by writing SQL against the reporting
-- path". ADR 0043 gave that role USAGE on the `reporting` schema alone and
-- default privileges only there, so silence here is genuinely a refusal and not
-- an omission — but the REVOKE below states it anyway, so that a future
-- schema-wide grant cannot pick these tables up by accident.
GRANT USAGE ON SCHEMA fulfillment TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.courier_duty_sessions TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.courier_positions_live TO horecaos_application;
GRANT SELECT, INSERT ON fulfillment.courier_location_tracks TO horecaos_application;
GRANT SELECT, INSERT ON fulfillment.courier_location_tracks_default TO horecaos_application;
GRANT SELECT, INSERT ON fulfillment.courier_track_summaries TO horecaos_application;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'horecaos_reporting_read') THEN
        REVOKE ALL ON fulfillment.courier_duty_sessions FROM horecaos_reporting_read;
        REVOKE ALL ON fulfillment.courier_positions_live FROM horecaos_reporting_read;
        REVOKE ALL ON fulfillment.courier_location_tracks FROM horecaos_reporting_read;
        REVOKE ALL ON fulfillment.courier_track_summaries FROM horecaos_reporting_read;
    END IF;
END
$$;
