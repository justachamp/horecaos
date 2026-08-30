-- Legacy profiling, third pass (ADR 0024, Phase 0).
--
-- Three questions left, one of which is the highest-consequence unknown in the
-- whole migration. Small and fast.
--
--     psql "$LEGACY_URL" -v ON_ERROR_STOP=1 -f legacy-profile-3.sql \
--          --csv -o legacy-profile-3.csv
--
-- Every branch of the UNION selects exactly four text columns —
-- (section, subject, metric, value). The previous revision of this file had a
-- five-column branch at the end and failed to parse.
--
-- Counts, key sets and opening hours only. Opening hours are published business
-- information; no name, phone, address or configuration value leaves here.

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- Two guards, because the legacy JSON is not the shape its model declares
-- ---------------------------------------------------------------------------
--
-- `jsonb_object_keys`, `jsonb_each`, `jsonb_array_elements` and
-- `jsonb_array_length` all raise on a value of the wrong kind. Wrapping the call
-- in `coalesce` does not help — coalesce substitutes for NULL, it does not catch
-- an error — and neither does filtering in a WHERE, which runs after the lateral
-- join has already evaluated. `vendors.managers` is declared
-- `list[dict]` in the model and is a bare scalar in at least one row, which is
-- precisely the kind of thing this profile exists to discover rather than to
-- crash on.
--
-- So every type-sensitive call carries its own inline guard: a value of the
-- wrong kind becomes an empty object or array and contributes nothing, while
-- `jsonb_typeof` is reported separately so the odd row stays visible rather than
-- being silently dropped.
--
-- Inline rather than a helper function, deliberately. A helper reads better and
-- would need CREATE privilege — which a read-only replica, the connection this
-- script most wants to be run on, does not grant. Nothing here needs more than
-- SELECT.

-- ---------------------------------------------------------------------------
-- 1. What zone are the naive timestamps in?
-- ---------------------------------------------------------------------------
--
-- `created` is a DateTime with no timezone and nothing in the schema says which
-- zone it was written in. Reading Tashkent local as UTC shifts every historical
-- order five hours, across the business-date boundary the daily order number
-- depends on — so a day's orders renumber into the wrong day, and the
-- reconciliation meant to catch it compares two equally wrong numbers.
--
-- Answered by behaviour rather than by asking anyone. A restaurant's orders peak
-- at lunch and at dinner, *local*. Peaks near 12 and 19 mean Asia/Tashkent;
-- peaks near 07 and 14 mean UTC. Orders alone are thin in this database, so
-- carts, OTP requests and sessions are included — all customer-initiated, all
-- far more numerous, and all showing the same daily shape.
SELECT 'timezone' AS section,
       src AS subject,
       'hour_' || lpad(hour::text, 2, '0') AS metric,
       count(*)::text AS value
FROM (
    SELECT 'orders' AS src, extract(hour FROM created)::int AS hour FROM orders
    UNION ALL SELECT 'carts',             extract(hour FROM created)::int FROM carts
    UNION ALL SELECT 'otps',              extract(hour FROM created)::int FROM otps
    UNION ALL SELECT 'customer_sessions', extract(hour FROM created)::int FROM customer_sessions
) h
GROUP BY src, hour

UNION ALL

-- ---------------------------------------------------------------------------
-- 2. The vendors.managers array shape
-- ---------------------------------------------------------------------------
--
-- The branch-to-staff link, and therefore the input to the Keycloak identity
-- crosswalk. ADR 0024 is explicit that it is not authoritative identity — every
-- manager resolves to a Keycloak subject through an approved link process — but
-- the migration cannot propose links without knowing which fields each entry
-- carries. Keys only; whatever names or usernames are inside stay inside.
SELECT 'managers', 'vendors.managers', 'entry_keyset:' || keyset, count(*)::text
FROM (
    SELECT coalesce((SELECT string_agg(k, '|' ORDER BY k)
                     FROM jsonb_object_keys(CASE WHEN jsonb_typeof(e.value) = 'object'
                                            THEN e.value ELSE '{}'::jsonb END) k),
                    'type=' || jsonb_typeof(e.value)) AS keyset
    FROM vendors v
    -- Guarded at the call, not in a WHERE. jsonb_array_elements throws on a
    -- non-array, and the WHERE runs after the lateral — so one malformed row
    -- would abort the whole export rather than be skipped.
    CROSS JOIN LATERAL jsonb_array_elements(
            CASE WHEN jsonb_typeof(v.managers::jsonb) = 'array'
                 THEN v.managers::jsonb ELSE '[]'::jsonb END) AS e(value)
    WHERE v.managers IS NOT NULL
) s
GROUP BY keyset

UNION ALL

SELECT 'managers', 'vendors.managers',
       'type=' || coalesce(jsonb_typeof(managers::jsonb), 'sql_null')
       || ' length=' || CASE WHEN jsonb_typeof(managers::jsonb) = 'array'
                             THEN jsonb_array_length(managers::jsonb)::text
                             ELSE 'n/a' END,
       count(*)::text
FROM vendors
WHERE managers IS NOT NULL
GROUP BY 3

UNION ALL

-- ---------------------------------------------------------------------------
-- 3. One level deeper into work_time
-- ---------------------------------------------------------------------------
--
-- The second pass established the outer shape: `working_days` and
-- `non_working_days`, with all seven weekday keys beneath the first. What it did
-- not reach is what a single day holds.
SELECT 'work_time', 'working_days.<day>', 'keyset:' || keyset, count(*)::text
FROM (
    SELECT coalesce((SELECT string_agg(k, '|' ORDER BY k)
                     FROM jsonb_object_keys(CASE WHEN jsonb_typeof(d.value) = 'object'
                                            THEN d.value ELSE '{}'::jsonb END) k),
                    'type=' || jsonb_typeof(d.value)) AS keyset
    FROM vendors v
    CROSS JOIN LATERAL jsonb_each(
            CASE WHEN jsonb_typeof(v.work_time::jsonb -> 'working_days') = 'object'
                 THEN v.work_time::jsonb -> 'working_days' ELSE '{}'::jsonb END) AS d(key, value)
    WHERE v.work_time IS NOT NULL
) s
GROUP BY keyset

UNION ALL

-- Every key inside a day, with its distinct values and how many days carry each.
--
-- Deliberately not written as `value ->> 'from'` and `->> 'to'`: the profile
-- could not establish those key names, so guessing them would have returned an
-- empty result that looks like "no branch has opening hours" rather than "the
-- query guessed wrong". Reading whatever keys are actually there cannot fail
-- that way, and it answers the shape and the overnight-wrap question together —
-- a closing value numerically below its opening value is a wrap, and ADR 0036's
-- schedule rules allow one while its preparation bands deliberately do not.
SELECT 'work_time', 'working_days.<day>.' || field, 'value:' || val, count(*)::text
FROM (
    SELECT e.key AS field, e.value #>> '{}' AS val
    FROM vendors v
    CROSS JOIN LATERAL jsonb_each(
            CASE WHEN jsonb_typeof(v.work_time::jsonb -> 'working_days') = 'object'
                 THEN v.work_time::jsonb -> 'working_days' ELSE '{}'::jsonb END) AS d(key, value)
    CROSS JOIN LATERAL jsonb_each(CASE WHEN jsonb_typeof(d.value) = 'object'
                                     THEN d.value ELSE '{}'::jsonb END) AS e(key, value)
    WHERE v.work_time IS NOT NULL
) t
GROUP BY field, val

UNION ALL

-- non_working_days: what a closure exception actually looks like. Four columns,
-- with the type and length folded into the metric rather than split across an
-- extra one.
SELECT 'work_time', 'non_working_days', 'shape:' || shape, count(*)::text
FROM (
    SELECT 'type=' || coalesce(jsonb_typeof(work_time::jsonb -> 'non_working_days'), 'absent')
           || ' length=' || coalesce(
                CASE WHEN jsonb_typeof(work_time::jsonb -> 'non_working_days') = 'array'
                     THEN jsonb_array_length(work_time::jsonb -> 'non_working_days') END, 0)::text
           AS shape
    FROM vendors
    WHERE work_time IS NOT NULL
) n
GROUP BY shape

ORDER BY 1, 2, 3;
