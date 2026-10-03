-- ADR 0143: walk-in guests open their own table session.
--
-- A guest who scans a free table may seat themselves through an explicit,
-- authenticated action. What that action opens is the same dinein.table_sessions
-- row staff open, marked as a CLAIM: it occupies the table through the same
-- partial unique index (ux_session_table_occupied), and it either becomes an
-- ordinary session once a round the restaurant has accepted is on it, or lapses.
--
-- The claim is columns on the session, not a new status. Every place that means
-- "live" -- SessionStatus.live(), findLiveSessionAtTable, ix_sessions_live, the
-- occupancy predicates, the guest routes' status checks -- keeps meaning what it
-- meant, and the state machine (DineInStateMachine) is untouched.
--
-- The capability ships OFF everywhere: walk_in_self_seat defaults to false, so
-- this migration changes no venue's behaviour until a manager turns it on, with a
-- reason, through the settings endpoint that already carries qr_mode.

-- ---------------------------------------------------------------------------
-- The branch's switch and its numbers
-- ---------------------------------------------------------------------------
--
-- All defaults are the record's own proposals (ADR 0143, Specification). The
-- numbers are per-branch settings rather than constants because the right dwell
-- time, claim window and cap differ between a cafe and a banquet hall, and
-- because the branch cap and the daily cap are what the abuse design rests on.
ALTER TABLE dinein.location_settings
    ADD COLUMN walk_in_self_seat boolean NOT NULL DEFAULT false,
    ADD COLUMN walk_in_claim_ttl_minutes integer NOT NULL DEFAULT 15,
    ADD COLUMN walk_in_horizon_minutes integer NOT NULL DEFAULT 90,
    ADD COLUMN walk_in_max_unconfirmed integer NOT NULL DEFAULT 5,
    ADD COLUMN walk_in_daily_claims_per_account integer NOT NULL DEFAULT 3,
    ADD COLUMN walk_in_payment_defer_minutes integer NOT NULL DEFAULT 30,
    -- Interim, ADR 0055's single-currency pilot: there is no location-currency
    -- read yet, and a guest-opened session has no operator to type one. Replaced
    -- by that read when it exists.
    ADD COLUMN session_currency char(3) NOT NULL DEFAULT 'UZS',
    ADD CONSTRAINT ck_dinein_walk_in_claim_ttl CHECK (walk_in_claim_ttl_minutes BETWEEN 2 AND 60),
    ADD CONSTRAINT ck_dinein_walk_in_horizon CHECK (walk_in_horizon_minutes BETWEEN 0 AND 480),
    ADD CONSTRAINT ck_dinein_walk_in_max_unconfirmed CHECK (walk_in_max_unconfirmed BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_dinein_walk_in_daily_claims CHECK (walk_in_daily_claims_per_account BETWEEN 1 AND 20),
    ADD CONSTRAINT ck_dinein_walk_in_payment_defer CHECK (walk_in_payment_defer_minutes BETWEEN 0 AND 120),
    ADD CONSTRAINT ck_dinein_session_currency CHECK (session_currency ~ '^[A-Z]{3}$');

COMMENT ON COLUMN dinein.location_settings.walk_in_self_seat IS
    'ADR 0143. Whether a guest holding a table''s guest token may open a provisional session (a claim) there. Off by default; meaningful only while qr_mode = ORDER_AND_PAY.';
COMMENT ON COLUMN dinein.location_settings.walk_in_horizon_minutes IS
    'ADR 0143. A walk-in may not take a table a CONFIRMED booking holds for any part of [now, now + this). Proposed 90; the venue''s typical dwell is the right number.';
COMMENT ON COLUMN dinein.location_settings.walk_in_max_unconfirmed IS
    'ADR 0143. Branch-wide cap on simultaneous live unconfirmed claims, counted only while the transaction holds this row''s lock.';
COMMENT ON COLUMN dinein.location_settings.walk_in_payment_defer_minutes IS
    'ADR 0143. How long past claim_expires_at the sweeper defers a claim whose round is still in flight (payment authorizing). Anchored on the claim''s own expiry and not renewable.';

-- ---------------------------------------------------------------------------
-- The claim, on the session
-- ---------------------------------------------------------------------------
ALTER TABLE dinein.table_sessions
    ADD COLUMN origin varchar(12) NOT NULL DEFAULT 'STAFF',
    -- The claimant. An identifier, not the name, phone or address ADR 0029
    -- protects -- but one more place an account id lives, so erasure (ADR 0015)
    -- clears it (dinein.application.DineInErasureParticipant).
    ADD COLUMN opened_by_account_id uuid,
    ADD COLUMN claim_expires_at timestamptz,
    ADD COLUMN confirmed_at timestamptz,
    -- 'round:<orderId>' or the staff subject.
    ADD COLUMN confirmed_by varchar(128),
    ADD CONSTRAINT ck_session_origin CHECK (origin IN ('STAFF', 'GUEST_QR')),
    -- A staff session carries no claim columns at all. A guest session names its
    -- claimant while the claim is live and says either that a round confirmed it or
    -- when it lapses.
    --
    -- The claimant id may be NULL only once nobody can be harmed by its absence:
    -- the session is confirmed or closed. Erasure (ADR 0015) nulls it on such rows
    -- and the record's own check, which requires it unconditionally, would make
    -- that impossible for every guest-opened row there will ever be. A LIVE
    -- UNCONFIRMED claim still always names its claimant, because the unique index
    -- below is what bounds an account to one of them.
    ADD CONSTRAINT ck_session_claim_shape CHECK (
        (origin = 'STAFF'
            AND opened_by_account_id IS NULL
            AND claim_expires_at IS NULL
            AND confirmed_at IS NULL
            AND confirmed_by IS NULL)
        OR
        (origin = 'GUEST_QR'
            AND (opened_by_account_id IS NOT NULL OR confirmed_at IS NOT NULL OR closed_at IS NOT NULL)
            AND (confirmed_at IS NOT NULL OR claim_expires_at IS NOT NULL)
            AND ((confirmed_at IS NULL) = (confirmed_by IS NULL)))),
    ADD CONSTRAINT fk_session_claimant FOREIGN KEY (opened_by_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id);

COMMENT ON COLUMN dinein.table_sessions.origin IS
    'ADR 0143. STAFF: opened by a person with dinein.session.manage. GUEST_QR: opened by a guest holding a table''s guest token and a signed-in customer session -- a claim until a round the restaurant accepted is on it.';
COMMENT ON COLUMN dinein.table_sessions.claim_expires_at IS
    'ADR 0143. When an unconfirmed claim lapses and frees the table. Kept after confirmation as the record of the original window.';

-- The sweeper's one read: live unconfirmed guest claims, soonest first. Not a
-- status list: ck_session_closed_at makes "closed_at IS NULL" exactly "not CLOSED
-- or FORCE_CLOSED", and a status list would go stale the way ix_sessions_live's does
-- the day a sixth status is added. A guest can move a session to BILL_REQUESTED
-- with the table token alone, so a sweeper indexed on status = 'OPEN' would never
-- see a claim one tap had moved out of OPEN.
CREATE INDEX ix_sessions_unconfirmed_claims
    ON dinein.table_sessions (claim_expires_at)
    WHERE origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL;

-- One live unconfirmed claim per account per branch, enforced by the database
-- whatever the application read. The (tenant_id, location_id) prefix also serves
-- the branch-cap count.
CREATE UNIQUE INDEX ux_claim_account_branch
    ON dinein.table_sessions (tenant_id, location_id, opened_by_account_id)
    WHERE origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL;

-- The daily-cap count: claims this account opened at this branch in the last 24h.
CREATE INDEX ix_sessions_claims_by_account
    ON dinein.table_sessions (tenant_id, location_id, opened_by_account_id, opened_at)
    WHERE origin = 'GUEST_QR';

-- No new table, so no new GRANT: horecaos_application already holds
-- SELECT, INSERT, UPDATE, DELETE on both tables altered above (V0034).
