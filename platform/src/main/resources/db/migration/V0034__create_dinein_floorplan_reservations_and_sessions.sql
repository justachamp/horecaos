-- ADR 0047: dine-in — table service, reservations, and QR ordering.
--
-- Dine-in already half exists. `FulfillmentMode.DINE_IN` is a code constant,
-- ordering.carts and ordering.orders both carry it in their CHECK constraints
-- since V0022, and ADR 0036's serviceability resolver already answers whether a
-- branch may take a DINE_IN order at this minute. What none of that has is a
-- place to eat: there is no table to sit at, nothing that holds an evening's
-- rounds together, and no way for a guest to reach a menu by pointing a phone at
-- a printed square. This migration adds those three and nothing else.
--
-- ---------------------------------------------------------------------------
-- What the legacy estate has to bring, which is nothing
-- ---------------------------------------------------------------------------
--
-- Checked against the source rather than assumed, because the answer decides
-- whether this schema needs a legacy identifier column on every table.
-- `app/shared/enums/order.py` declares `OrderType` as exactly five values:
-- delivery, express, external, takeaway, on_time. There is no hall, no dine-in,
-- no table service and no reservation among them, and no `tables`, `sections` or
-- `reservations` model anywhere beside them. The legacy application is a delivery
-- and takeaway product.
--
-- Two consequences, and both are scope facts rather than opinions. There is no
-- dine-in history to TRANSFORM, so ADR 0024's migration control plane gains no
-- scope from this ADR and nothing here carries a `legacy_*` column: a nullable
-- external identifier nothing ever writes reads as "this row came from nowhere"
-- for every row, which is exactly as informative as omitting it and one more
-- column every reader has to rule out. And every floor plan in the estate has to
-- be authored by somebody who has stood in that dining room — twelve branches of
-- sections, tables and seat counts, typed once, before a single QR code is
-- printed. That is a rollout cost, and it is visible here rather than discovered
-- on the first Friday.
--
-- ---------------------------------------------------------------------------
-- What is deliberately not created here
-- ---------------------------------------------------------------------------
--
-- `dinein.pos_settlements`, and with it the whole SETTLE_OPEN_TICKET mode.
-- ADR 0047's own implementation checklist opens with "close the fiscal open
-- input before any settle-mode work starts", and its rollout says to leave the
-- mode disabled until one POS adapter passes contract tests for both new
-- ADR 0011 ports. Neither has happened: who issues the fiscal receipt when a
-- POS-owned check is settled through Qoida is undecided, and no adapter declares
-- either capability. A settlement table now would be schema nothing writes,
-- reading as a capability that exists — the same mistake V0022 refused for
-- `ordering.cart_fulfillment` and V0030 refused for `kitchen.devices`. The mode
-- name is still refused explicitly at configuration time (see the CHECK on
-- dinein.location_settings.qr_mode below), so the failure an operator meets is a
-- clear one rather than a QR code that scans to nothing.
--
-- `ordering.cart_fulfillment.dinein_table_id`. ADR 0047 extends ADR 0019 with it
-- and it is ordering's column in ordering's table; that table does not exist yet
-- either (V0022 says why). A dine-in order reaches its session through
-- dinein.session_orders below, which is this module's own row and does not
-- require ordering to change first.

CREATE SCHEMA IF NOT EXISTS dinein;

COMMENT ON SCHEMA dinein IS
    'ADR 0047. The dining room: sections, tables, reservations, the table session that groups an evening''s orders, and the QR entry point. Owns no order and no price.';

-- Already created by V0025 for delivery-zone time windows. Repeated because this
-- file's central guarantee depends on it and a reader of this migration alone
-- must not have to discover that elsewhere: a range exclusion constraint that
-- also equates a plain uuid column needs GiST operator classes for the uuid, and
-- those come from btree_gist rather than from core PostgreSQL.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- Per-location dine-in settings
-- ---------------------------------------------------------------------------
--
-- ADR 0047's physical sketch puts `qr_mode` on each table. Its decision text says
-- the mode "is configured per location". Those are two authorities for one fact,
-- and this file takes the decision text: the mode lives here, once per branch,
-- and dinein.tables carries only the token. A per-table mode would let one square
-- of card in a room order and pay while the square on the next table only reads
-- the menu, which is not a configuration any restaurant means, and it would make
-- "what can a guest do at this branch" a question with as many answers as the
-- branch has tables.
--
-- The turnaround buffer lives here rather than in ADR 0030 configuration for the
-- same reason ADR 0041 kept station roles in code: it is read on the booking path
-- by exactly one module, it is snapshotted onto every hold the moment it is used,
-- and a generic policy key would be one more indirection between a host pressing
-- confirm and the interval PostgreSQL actually excludes on.
CREATE TABLE dinein.location_settings (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- The single authority for what a scanned code does at this branch.
    qr_mode varchar(24) NOT NULL DEFAULT 'VIEW_ONLY',

    -- Minutes added to both ends of a requested booking to produce the effective
    -- hold. Twenty minutes of a table being cleared and relaid is twenty minutes
    -- it cannot be sold to the next party, and a booking system that pretends
    -- otherwise double-sells every table at every turn.
    turnaround_minutes integer NOT NULL DEFAULT 15,

    -- How long a guest token minted from a scan stays valid. Roughly the length
    -- of a long dinner. Longer and a photographed code keeps working after the
    -- party has left; much shorter and a guest is logged out between courses.
    guest_session_ttl_minutes integer NOT NULL DEFAULT 240,

    -- Basis points, snapshotted onto every session opened here. Nothing in this
    -- release computes a charge from it: ADR 0047 places the service charge at
    -- ADR 0018 pipeline stage 5 as a fee of type SERVICE_CHARGE, which is
    -- pricing's stage and pricing's table. This column is the rate's home so that
    -- the session can pin what was in force at the time; a charge derived on read
    -- from a rate that changed in March would restate February's bills.
    service_charge_rate_bp integer NOT NULL DEFAULT 0,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_dinein_location_settings PRIMARY KEY (location_id),
    -- SETTLE_OPEN_TICKET is a name this platform knows and refuses. ADR 0011's
    -- rule is that an unsupported capability may never be the sole business path,
    -- and no POS adapter declares OpenTicketReadCapability or
    -- TicketSettlementCapability; the fiscal question of who issues the receipt
    -- is open besides. Selecting it must fail loudly at configuration time rather
    -- than quietly at a table with a bill in front of it, which is what this
    -- CHECK does. Adding the value back is one line, in the migration that ships
    -- the first adapter.
    CONSTRAINT ck_dinein_qr_mode CHECK (qr_mode IN ('VIEW_ONLY', 'ORDER_AND_PAY')),
    CONSTRAINT ck_dinein_turnaround CHECK (turnaround_minutes BETWEEN 0 AND 240),
    CONSTRAINT ck_dinein_guest_ttl CHECK (guest_session_ttl_minutes BETWEEN 5 AND 1440),
    CONSTRAINT ck_dinein_service_charge CHECK (service_charge_rate_bp BETWEEN 0 AND 10000),
    CONSTRAINT ck_dinein_settings_version CHECK (version >= 1),
    CONSTRAINT fk_dinein_settings_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id)
);

COMMENT ON COLUMN dinein.location_settings.turnaround_minutes IS
    'ADR 0047. Added to both ends of a requested booking to build the effective hold, and snapshotted onto the reservation at confirmation so changing it next month releases no table anybody is sitting at.';
COMMENT ON COLUMN dinein.location_settings.service_charge_rate_bp IS
    'ADR 0047. Basis points. Pinned onto each session; the charge itself is an ADR 0018 stage-5 fee and is not computed in this schema.';

-- ---------------------------------------------------------------------------
-- Sections
-- ---------------------------------------------------------------------------
--
-- A floor plan is a physical property of a branch: the terrace at one location is
-- not the terrace at another, and neither is brand catalogue content. A venue
-- with no sections gets one section rather than a second model — ADR 0047 rejects
-- the free-text table label outright, and "no section" is that label wearing a
-- different name.
CREATE TABLE dinein.sections (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    -- The operator's stable handle. Renaming the display name must not orphan a
    -- printed floor plan or a saved host-stand filter.
    code varchar(32) NOT NULL,
    display_name varchar(120) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_section_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_section_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_section_version CHECK (version >= 1),
    CONSTRAINT fk_section_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT uq_section_code UNIQUE (tenant_id, location_id, code),
    -- Lets a table name its section and its branch in one foreign key, so a table
    -- at one branch can never sit in another branch's terrace.
    CONSTRAINT uq_section_identity UNIQUE (id, tenant_id, location_id)
);

CREATE INDEX ix_sections_location ON dinein.sections (tenant_id, location_id, sort_order);

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
--
-- The QR token deserves the longest note in this file, because it is the one
-- credential in the platform that is printed on card and left in a public room.
--
-- What is stored is a SHA-256 digest of a 128-bit random token, hex-encoded. The
-- token itself exists exactly once, in the response that issued it, and is never
-- written anywhere. Three properties follow, and all three are the reason the
-- column is a digest rather than the token.
--
-- Unguessable: 128 bits from a CSPRNG has no structure to walk. ADR 0047 forbids
-- encoding a table id or a sequence number for the obvious reason — a guest at
-- table 7 who can read `?table=7` can read `?table=8`, and a two-digit number is
-- the whole of the search space. Nothing about this row is derivable from the
-- token and nothing about the token is derivable from this row.
--
-- Revocable per table: rotation writes a new digest here and stamps
-- qr_token_rotated_at. Every guest token minted from the old one is revoked in
-- the same transaction (see dinein.qr_guest_sessions), so the photographed code
-- in somebody's camera roll stops working at the moment the new card reaches the
-- table rather than whenever its last session happened to expire.
--
-- Not a KDF, and that is deliberate rather than an oversight. A password gets
-- Argon2 because it is drawn from a small, human, guessable distribution and the
-- work factor buys time against a dictionary. A uniform 128-bit token has no
-- dictionary; a work factor would buy nothing and would be paid on every scan, at
-- a table, on a phone, on restaurant wifi. A keyed hash would be better than a
-- plain digest against a database-only leak, but the key would have to be one
-- global key: the scan presents a token and nothing else, so there is no tenant
-- to key by until after the lookup, and ADR 0029's lookupHash is per-tenant by
-- construction. One global key held by ADR 0028 secret management for a value
-- that is already uniform randomness is machinery without a threat it changes.
--
-- The lookup by digest is therefore the one query in this schema with no tenant
-- predicate, and it must be: the token is the credential, and the row it finds is
-- what tells the caller which tenant it is talking to. Uniqueness is global for
-- the same reason. Every query downstream of that lookup carries the tenant this
-- row returned.
CREATE TABLE dinein.tables (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    section_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    display_name varchar(120) NOT NULL,
    seats integer NOT NULL,
    -- Whether this table may be pushed together with another for a large party.
    -- Availability reads it; nothing in this release joins tables automatically,
    -- because which tables physically meet is a floor-plan fact nobody has
    -- entered yet.
    joinable boolean NOT NULL DEFAULT false,

    -- Optional, and unused by any code here. ADR 0047 accepts that the first
    -- build has no drag-and-drop plan builder; carrying the coordinates means one
    -- can be added later without a migration, and a null pair reads honestly as
    -- "nobody has placed this table on a canvas".
    layout_x numeric(8, 2),
    layout_y numeric(8, 2),

    status varchar(20) NOT NULL DEFAULT 'ACTIVE',

    qr_token_hash char(64),
    qr_token_rotated_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_table_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_table_seats CHECK (seats > 0 AND seats <= 100),
    CONSTRAINT ck_table_status CHECK (status IN ('ACTIVE', 'OUT_OF_SERVICE', 'ARCHIVED')),
    CONSTRAINT ck_table_version CHECK (version >= 1),
    -- Pair completeness as an equality rather than as a disjunction of two null
    -- tests, so there is no three-valued-logic hole: a table has a token and the
    -- instant it was issued, or it has neither, and no third shape passes. A
    -- digest with no rotation instant would make "when was this code printed"
    -- unanswerable on exactly the tables somebody needs to reprint.
    CONSTRAINT ck_table_qr_pair CHECK ((qr_token_hash IS NULL) = (qr_token_rotated_at IS NULL)),
    CONSTRAINT ck_table_qr_hash_shape CHECK (qr_token_hash IS NULL OR qr_token_hash ~ '^[0-9a-f]{64}$'),
    -- Layout is a point or it is absent; one coordinate places nothing.
    CONSTRAINT ck_table_layout_pair CHECK ((layout_x IS NULL) = (layout_y IS NULL)),
    CONSTRAINT fk_table_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_table_section FOREIGN KEY (section_id, tenant_id, location_id)
        REFERENCES dinein.sections (id, tenant_id, location_id),
    CONSTRAINT uq_table_code UNIQUE (tenant_id, location_id, code),
    -- Global, and deliberately not scoped by tenant: the scan presents a digest
    -- and nothing else, so two tenants sharing one digest would make the lookup
    -- ambiguous and could seat a guest in another company's dining room.
    CONSTRAINT uq_table_qr_token UNIQUE (qr_token_hash),
    CONSTRAINT uq_table_identity UNIQUE (id, tenant_id),
    CONSTRAINT uq_table_location_identity UNIQUE (id, tenant_id, location_id)
);

COMMENT ON COLUMN dinein.tables.qr_token_hash IS
    'ADR 0047. SHA-256 of a 128-bit random bearer token that exists only on printed card. Never the table id, never a sequence: a guessable code lets anyone order to any table and read what strangers are eating.';
COMMENT ON COLUMN dinein.tables.qr_token_rotated_at IS
    'ADR 0047. When the current code was issued. Rotation is the only remedy for a leaked token and it invalidates printed card, so it is audited and never scheduled.';
COMMENT ON COLUMN dinein.tables.status IS
    'ADR 0047. Tables archive, never delete. A reservation whose table row is gone is a booking whose location cannot be rendered, and OUT_OF_SERVICE is the broken leg that is still on the plan.';

CREATE INDEX ix_tables_location ON dinein.tables (tenant_id, location_id, section_id, code);

-- ---------------------------------------------------------------------------
-- Guest sessions minted from a scan
-- ---------------------------------------------------------------------------
--
-- Scanning authorises nothing by itself. The printed token is exchanged for this
-- row, and what the storefront API accepts afterwards is the short-lived token
-- this row hashes — never the printed one again, and never a table id.
--
-- The separation is what makes the printed code revocable at all. A design that
-- accepted the table token on every request would have no revocation short of
-- reprinting, because there would be nothing else to invalidate; here rotation
-- expires every live guest token in the same transaction that writes the new
-- digest.
--
-- Nothing on this row identifies a person. No IP address, no user agent, no
-- device fingerprint: ADR 0029 keeps personal data inside the envelope and out of
-- logs and events, and a table that quietly accumulated the network address of
-- every guest who scanned a menu would be exactly the kind of collection nobody
-- consented to. Volumetric abuse is the edge's problem, per ADR 0033.
CREATE TABLE dinein.qr_guest_sessions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    table_id uuid NOT NULL,

    token_hash char(64) NOT NULL,

    -- What the branch permitted at the moment of the scan. Pinned so that
    -- switching a branch from ORDER_AND_PAY back to VIEW_ONLY mid-service does
    -- not silently widen or narrow what a guest already holding a token may do —
    -- and so that "what could this token do" is answerable after the fact.
    qr_mode_snapshot varchar(24) NOT NULL,

    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revoked_reason varchar(32),

    CONSTRAINT ck_guest_session_hash_shape CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_guest_session_window CHECK (expires_at > issued_at),
    CONSTRAINT ck_guest_session_mode CHECK (qr_mode_snapshot IN ('VIEW_ONLY', 'ORDER_AND_PAY')),
    -- Revocation is an instant and a reason together. A revoked token whose
    -- reason is missing makes "why did the guest at table 4 get logged out"
    -- unanswerable, and that question is asked during service.
    CONSTRAINT ck_guest_session_revocation CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT ck_guest_session_reason CHECK (revoked_reason IS NULL OR revoked_reason IN (
        'TABLE_TOKEN_ROTATED', 'SESSION_CLOSED', 'OPERATOR_REVOKED', 'TABLE_ARCHIVED')),
    CONSTRAINT fk_guest_session_table FOREIGN KEY (table_id, tenant_id, location_id)
        REFERENCES dinein.tables (id, tenant_id, location_id),
    -- Global for the same reason the table digest is: this value is presented on
    -- its own by a caller who has not said who they are.
    CONSTRAINT uq_guest_session_token UNIQUE (token_hash)
);

-- Rotation revokes a table's live tokens in one statement, and this is the index
-- that statement rides.
CREATE INDEX ix_guest_sessions_live
    ON dinein.qr_guest_sessions (table_id, expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE dinein.qr_guest_sessions IS
    'ADR 0047. The short-lived, table-scoped token a scan is exchanged for. Carries nothing a guest supplied and nothing that identifies them; every binding is a column here rather than a claim the client could edit.';

-- ---------------------------------------------------------------------------
-- Reservations
-- ---------------------------------------------------------------------------
--
-- Its own aggregate, never part of an order. The normal case is a booking that
-- becomes no order at all — a future date, a no-show, a cancellation — and the
-- walk-in, which is most covers, is a session attached to no booking. A booking
-- holds no inventory and no pricing quote at any point: reserving a table on
-- Friday reserves no food.
--
-- The guest's name and phone are personal data under ADR 0029 and are stored
-- encrypted, in the same shape customer.contact_points uses — randomized AEAD
-- bound to this row in `*_encrypted`, and a separate keyed per-tenant digest for
-- the one search support actually performs, which is "a guest is on the phone
-- quoting this number". A booking for a guest with no Qoida account creates no
-- customer record and no consent, per ADR 0015, which is why the name lives here
-- rather than as a thin customer row nobody agreed to.
CREATE TABLE dinein.reservations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- Set only when the booking was made by somebody who already has an account.
    customer_account_id uuid,

    guest_name_encrypted text NOT NULL,
    guest_phone_encrypted text NOT NULL,
    guest_phone_lookup_hash varchar(64) NOT NULL,
    secondary_phone_encrypted text,
    note_encrypted text,

    party_size integer NOT NULL,

    -- What the guest asked for, kept apart from the hold that was taken. Changing
    -- the branch's turnaround buffer next month must not retroactively release a
    -- table nobody released, and must not retroactively overlap two bookings that
    -- were both confirmed under the old buffer. Two columns, because one interval
    -- cannot be both the promise and the exclusion.
    requested_from timestamptz NOT NULL,
    requested_to timestamptz NOT NULL,
    turnaround_minutes_snapshot integer NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'REQUESTED',

    -- ADR 0036 owns the channel vocabulary and this ADR adds none. A booking that
    -- arrived by telephone is CALL_CENTRE; one a host typed is POS.
    source_channel_id uuid NOT NULL,

    created_by varchar(128) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_reservation_status CHECK (status IN (
        'REQUESTED', 'CONFIRMED', 'REJECTED', 'SEATED', 'CANCELLED', 'NO_SHOW', 'COMPLETED')),
    CONSTRAINT ck_reservation_party CHECK (party_size > 0 AND party_size <= 200),
    CONSTRAINT ck_reservation_window CHECK (requested_to > requested_from),
    CONSTRAINT ck_reservation_turnaround CHECK (turnaround_minutes_snapshot BETWEEN 0 AND 240),
    CONSTRAINT ck_reservation_version CHECK (version >= 1),
    CONSTRAINT fk_reservation_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_reservation_channel FOREIGN KEY (tenant_id, source_channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id),
    CONSTRAINT fk_reservation_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT uq_reservation_identity UNIQUE (id, tenant_id),
    CONSTRAINT uq_reservation_location_identity UNIQUE (id, tenant_id, location_id)
);

COMMENT ON COLUMN dinein.reservations.guest_phone_lookup_hash IS
    'ADR 0029. Keyed, per-tenant, deterministic. The only way to find a booking by the number a guest quotes, without storing a number anybody holding this table could read.';
COMMENT ON COLUMN dinein.reservations.turnaround_minutes_snapshot IS
    'ADR 0047. The buffer in force when the hold was taken. Stored so that editing the branch setting cannot move an interval PostgreSQL has already excluded on.';

CREATE INDEX ix_reservations_service
    ON dinein.reservations (tenant_id, location_id, requested_from)
    WHERE status IN ('REQUESTED', 'CONFIRMED', 'SEATED');
CREATE INDEX ix_reservations_phone_lookup
    ON dinein.reservations (tenant_id, guest_phone_lookup_hash);

-- ---------------------------------------------------------------------------
-- The hold, and the only constraint in this file that matters on a Friday
-- ---------------------------------------------------------------------------
--
-- Two hosts confirming table 12 for 20:00 in the same second is not a rare race
-- in a busy restaurant; it is Friday. Read-then-write cannot exclude it at any
-- isolation level short of serializable, and holding a lock across a booking form
-- is worse than the race. The exclusion constraint below is the whole guarantee:
-- PostgreSQL refuses the second writer, and the loser sees a conflict rather than
-- a table sold twice.
--
-- `held_during` is the effective hold — the requested interval widened by the
-- snapshotted turnaround at both ends — and it is checked only while the booking
-- is one a restaurant would actually keep a table free for. A cancelled booking
-- and a no-show hold nothing.
--
-- `status` is copied from the parent, and it is the one denormalized column here.
-- It has to be: an exclusion constraint's WHERE predicate can only see the row it
-- is defined on, so a predicate reading dinein.reservations would not compile and
-- a predicate that could would not be checked when the parent changed. Both
-- triggers below exist so that no application code ever writes this column —
-- INSERT takes it from the parent, and the parent's status change propagates. A
-- denormalized column two writers can set is a denormalized column that drifts,
-- and here drift means a table quietly held by a booking that was cancelled last
-- Tuesday.
--
-- The exclusion equates table_id alone and not (tenant_id, table_id). A table id
-- is a primary key, so it already implies its tenant; adding the tenant to the
-- key would make a cross-tenant pair of rows non-conflicting, which is true but
-- costs an index column to state something no id can violate.
CREATE TABLE dinein.reservation_tables (
    reservation_id uuid NOT NULL,
    table_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    held_during tstzrange NOT NULL,
    status varchar(16) NOT NULL,

    CONSTRAINT pk_reservation_tables PRIMARY KEY (reservation_id, table_id),
    CONSTRAINT ck_reservation_table_status CHECK (status IN (
        'REQUESTED', 'CONFIRMED', 'REJECTED', 'SEATED', 'CANCELLED', 'NO_SHOW', 'COMPLETED')),
    CONSTRAINT ck_reservation_table_hold CHECK (NOT isempty(held_during)),
    -- Both parents are matched on (id, tenant_id, location_id), so a booking at
    -- one branch cannot hold a table at another and neither can belong to another
    -- tenant.
    CONSTRAINT fk_reservation_table_reservation
        FOREIGN KEY (reservation_id, tenant_id, location_id)
        REFERENCES dinein.reservations (id, tenant_id, location_id),
    CONSTRAINT fk_reservation_table_table FOREIGN KEY (table_id, tenant_id, location_id)
        REFERENCES dinein.tables (id, tenant_id, location_id),
    CONSTRAINT ex_reservation_table_no_double_booking
        EXCLUDE USING gist (table_id WITH =, held_during WITH &&)
        WHERE (status IN ('CONFIRMED', 'SEATED'))
);

COMMENT ON CONSTRAINT ex_reservation_table_no_double_booking ON dinein.reservation_tables IS
    'ADR 0047. The double-booking guarantee. Application locking and check-on-read cannot exclude two hosts confirming the same table in the same second; this can.';
COMMENT ON COLUMN dinein.reservation_tables.status IS
    'ADR 0047. Copied from the parent booking by trigger and never by application code, because an exclusion predicate can only read the row it is defined on.';

CREATE INDEX ix_reservation_tables_table ON dinein.reservation_tables (table_id, status);

-- The two triggers that make the copied status honest. Written as the only
-- writers of that column: an INSERT takes it from the parent, and a parent status
-- change propagates. Note the ordering this implies for a confirmation — the
-- effective hold is written onto these rows first, then the parent moves to
-- CONFIRMED, and it is that second statement whose propagation the exclusion
-- constraint checks. A confirmation therefore never checks a stale interval.
CREATE FUNCTION dinein.reservation_table_status_from_parent() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    SELECT r.status INTO STRICT NEW.status
      FROM dinein.reservations r
     WHERE r.id = NEW.reservation_id;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dinein.reservation_table_status_from_parent() IS
    'ADR 0047. Seeds the copied booking status on insert so no caller can supply one that disagrees with the booking.';

CREATE TRIGGER tr_reservation_table_status_insert
    BEFORE INSERT ON dinein.reservation_tables
    FOR EACH ROW EXECUTE FUNCTION dinein.reservation_table_status_from_parent();

CREATE FUNCTION dinein.propagate_reservation_status() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dinein.reservation_tables
       SET status = NEW.status
     WHERE reservation_id = NEW.id;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION dinein.propagate_reservation_status() IS
    'ADR 0047. Carries a booking status change onto its holds, which is what makes the exclusion constraint fire on confirmation and release on cancellation.';

CREATE TRIGGER tr_reservation_status_propagate
    AFTER UPDATE OF status ON dinein.reservations
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION dinein.propagate_reservation_status();

-- ---------------------------------------------------------------------------
-- The table session
-- ---------------------------------------------------------------------------
--
-- The third object in the ordering picture, and the reason this ADR exists. A
-- table orders in rounds across an evening and pays once at the end; ADR 0019
-- forbids mutating a confirmed order's lines, for the good reasons that the
-- fiscal receipt, the payment amount, the inventory commitment and the POS export
-- have all already happened against the earlier version. So each round is a
-- normal, immutable, independently priced and fired DINE_IN order, and this row
-- is what holds them together.
--
-- It owns no lines and no pricing logic. Its total is the sum of its member
-- orders and is never recomputed from rules — see dinein.session_orders, and note
-- that there is no subtotal, tax, discount or fee column here. Those exist once,
-- on each order, and a second copy here would be a second answer to "what does
-- table seven owe".
--
-- A reservation and an occupancy are different facts, and this table is where
-- that stops being a slogan: a CONFIRMED booking holds an interval in
-- dinein.reservation_tables and occupies nothing, and a session occupies a table
-- in dinein.session_tables whether or not a booking exists. Seating a booking is
-- a session with reservation_id set. A walk-in is the same row with it null.
CREATE TABLE dinein.table_sessions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    reservation_id uuid,
    party_size integer,

    -- The trading day this evening's takings belong to, resolved once from the
    -- branch's own timezone when the session opens. A session that runs past
    -- midnight otherwise puts one service on two calendar days, and a manager
    -- comparing two totals finds the discrepancy long before anybody finds the
    -- cause. ADR 0043 owns the reporting rule; this column is the fact that rule
    -- needs to exist before there is history to re-key.
    business_date date NOT NULL,

    opened_by varchar(128) NOT NULL,
    opened_at timestamptz NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'OPEN',

    service_charge_rate_bp_snapshot integer,

    currency char(3) NOT NULL,
    -- Whole som for UZS: a minor unit here is one som, and nothing divides this
    -- by a hundred. Written once at settlement from the sum of the member orders,
    -- so a report reading a closed session does not have to re-add an evening.
    settled_total_minor bigint,

    closed_at timestamptz,
    close_reason_code varchar(64),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_session_status CHECK (status IN (
        'OPEN', 'BILL_REQUESTED', 'SETTLING', 'CLOSED', 'FORCE_CLOSED')),
    CONSTRAINT ck_session_party CHECK (party_size IS NULL OR (party_size > 0 AND party_size <= 200)),
    CONSTRAINT ck_session_service_charge CHECK (
        service_charge_rate_bp_snapshot IS NULL
        OR service_charge_rate_bp_snapshot BETWEEN 0 AND 10000),
    CONSTRAINT ck_session_total CHECK (settled_total_minor IS NULL OR settled_total_minor >= 0),
    CONSTRAINT ck_session_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_session_version CHECK (version >= 1),
    -- Both terminal statuses close, and nothing else may carry a closing instant.
    -- An equality rather than a disjunction, so a half-closed session is not
    -- expressible.
    CONSTRAINT ck_session_closed_at CHECK (
        (status IN ('CLOSED', 'FORCE_CLOSED')) = (closed_at IS NOT NULL)),
    -- One-directional on purpose: a force-close must name a reason, and an
    -- ordinary close may also carry one. An unpaid table that quietly disappears
    -- is how a shift's cash shortfall becomes unattributable, and the reason code
    -- is the half of that record a report can group by.
    CONSTRAINT ck_session_force_close_reason CHECK (
        status <> 'FORCE_CLOSED' OR close_reason_code IS NOT NULL),
    CONSTRAINT fk_session_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- Matched on the branch as well, so a booking made at one branch cannot be
    -- seated at another.
    CONSTRAINT fk_session_reservation FOREIGN KEY (reservation_id, tenant_id, location_id)
        REFERENCES dinein.reservations (id, tenant_id, location_id),
    CONSTRAINT uq_session_identity UNIQUE (id, tenant_id),
    CONSTRAINT uq_session_location_identity UNIQUE (id, tenant_id, location_id)
);

COMMENT ON TABLE dinein.table_sessions IS
    'ADR 0047. The settlement unit for a table visit. Holds occupancy, the running balance and the single act of paying; holds no lines and never reprices.';
COMMENT ON COLUMN dinein.table_sessions.settled_total_minor IS
    'ADR 0047. The sum of the member orders at settlement, in minor units — whole som for UZS. Never recomputed from pricing rules, because the orders were already priced and fiscalised.';
COMMENT ON COLUMN dinein.table_sessions.business_date IS
    'ADR 0047 and ADR 0043. The trading day the session belongs to, from the branch timezone at open. Without it one evening straddling midnight lands on two days in every report.';

-- One session per booking. A booking seated twice is two parties charged for one
-- reservation, and the second is somebody else's table.
CREATE UNIQUE INDEX ux_session_reservation
    ON dinein.table_sessions (tenant_id, reservation_id)
    WHERE reservation_id IS NOT NULL;

-- The host stand's only read: what is live in this room right now.
CREATE INDEX ix_sessions_live
    ON dinein.table_sessions (tenant_id, location_id, opened_at)
    WHERE status IN ('OPEN', 'BILL_REQUESTED', 'SETTLING');

CREATE INDEX ix_sessions_business_date
    ON dinein.table_sessions (tenant_id, location_id, business_date);

-- ---------------------------------------------------------------------------
-- Occupancy
-- ---------------------------------------------------------------------------
--
-- Which tables this party is actually sitting at, now. Distinct from the booking
-- hold above in both direction and time: a hold is a claim on the future, this is
-- a fact about the present, and most covers have one without the other.
CREATE TABLE dinein.session_tables (
    session_id uuid NOT NULL,
    table_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    joined_at timestamptz NOT NULL,
    left_at timestamptz,

    CONSTRAINT pk_session_tables PRIMARY KEY (session_id, table_id),
    CONSTRAINT ck_session_table_window CHECK (left_at IS NULL OR left_at >= joined_at),
    CONSTRAINT fk_session_table_session FOREIGN KEY (session_id, tenant_id, location_id)
        REFERENCES dinein.table_sessions (id, tenant_id, location_id),
    CONSTRAINT fk_session_table_table FOREIGN KEY (table_id, tenant_id, location_id)
        REFERENCES dinein.tables (id, tenant_id, location_id)
);

-- A table seats one party at a time. Two live occupancies on one table is two
-- bills for one set of chairs, and whichever waiter fires second wins by
-- accident. Partial on left_at so history accumulates freely underneath it.
CREATE UNIQUE INDEX ux_session_table_occupied
    ON dinein.session_tables (table_id)
    WHERE left_at IS NULL;

-- Closing a session releases its tables in the same statement that closes it, so
-- a table cannot be left occupied by a party that has paid and gone. Doing this
-- in the application would work until the one path that forgot, and that path's
-- symptom is a table nobody can seat with no visible reason why.
CREATE FUNCTION dinein.release_tables_on_session_close() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dinein.session_tables
       SET left_at = NEW.closed_at
     WHERE session_id = NEW.id
       AND left_at IS NULL;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION dinein.release_tables_on_session_close() IS
    'ADR 0047. Frees a closed session''s tables so the partial unique index above cannot strand a table as permanently occupied.';

CREATE TRIGGER tr_session_close_releases_tables
    AFTER UPDATE OF status ON dinein.table_sessions
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status
          AND NEW.status IN ('CLOSED', 'FORCE_CLOSED'))
    EXECUTE FUNCTION dinein.release_tables_on_session_close();

-- ---------------------------------------------------------------------------
-- The rounds
-- ---------------------------------------------------------------------------
--
-- Membership only. No amount, no currency, no line: the order already carries all
-- three and a copy here would be a second answer that drifts the first time an
-- order is amended. The session's balance is a SUM over this join, and that is the
-- whole of the "session as accumulator" model.
--
-- This is also where splitting a bill later stops being a redesign. ADR 0046 is
-- unbuilt and payment is whole-session for now, but the money is not attached to
-- the session — it is attached to a set of orders the session names. A split
-- tender adds rows beside these that address the same set; it does not have to
-- unpick a total that was baked into a single column.
CREATE TABLE dinein.session_orders (
    session_id uuid NOT NULL,
    order_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    -- Which round this was. What a waiter means by "the second round" and what a
    -- printed bill groups by; allocated per session, not per branch.
    sequence integer NOT NULL,
    added_at timestamptz NOT NULL,

    CONSTRAINT pk_session_orders PRIMARY KEY (session_id, order_id),
    CONSTRAINT ck_session_order_sequence CHECK (sequence >= 1),
    CONSTRAINT fk_session_order_session FOREIGN KEY (session_id, tenant_id)
        REFERENCES dinein.table_sessions (id, tenant_id),
    CONSTRAINT fk_session_order_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    -- An order belongs to at most one session. Two would put one meal on two
    -- bills, and every revenue figure covering both would count it twice.
    CONSTRAINT uq_session_order_once UNIQUE (tenant_id, order_id),
    CONSTRAINT uq_session_order_sequence UNIQUE (session_id, sequence)
);

COMMENT ON TABLE dinein.session_orders IS
    'ADR 0047. Membership of a round in a session, and nothing else. The bill is a SUM over the orders this names; no amount is copied here.';

GRANT USAGE ON SCHEMA dinein TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON dinein.location_settings TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON dinein.sections TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON dinein.tables TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON dinein.qr_guest_sessions TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON dinein.reservations TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON dinein.reservation_tables TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON dinein.table_sessions TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON dinein.session_tables TO qoida_application;
-- No DELETE. Removing a round from a settled evening is how a bill and its orders
-- stop reconciling, and the only legitimate reasons to detach one — a void, a
-- transfer to another table — are transitions somebody must be able to read back
-- afterwards rather than an absence.
GRANT SELECT, INSERT, UPDATE ON dinein.session_orders TO qoida_application;
