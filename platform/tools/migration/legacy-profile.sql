-- Legacy profiling export (ADR 0024, Phase 0).
--
-- Run this against a READ-ONLY connection to the legacy database and export the
-- single result set at the bottom as CSV. It creates one temporary table and
-- reads nothing else; it writes nothing to the legacy schema and holds no locks
-- beyond the read.
--
--     psql "$LEGACY_URL" -v ON_ERROR_STOP=1 -f legacy-profile.sql \
--          --csv -o legacy-profile.csv
--
-- ---------------------------------------------------------------------------
-- What this deliberately does NOT export
-- ---------------------------------------------------------------------------
--
-- No personal data leaves the database. Not a phone number, not a name, not an
-- address, not a username, not an image path. Where a column matters, this
-- exports how many rows have it, how many are null, and how many are distinct —
-- never a value.
--
-- `configs` and `vendors.tg_chat_id` get the same treatment for a different
-- reason: a configuration table in a system this age holds credentials, and a
-- profile that pastes them into a CSV has just moved every secret in the
-- platform into a file somebody will email. Keys only, values never.
--
-- JSON columns export their *key sets*, because the key set is the schema and
-- the values are the data. `vendors.work_time` cannot be migrated until its
-- shape is known; it also cannot be exported, because it is per-branch business
-- data. Keys answer the migration question without carrying the payload.
--
-- ---------------------------------------------------------------------------

\set ON_ERROR_STOP on

CREATE TEMP TABLE profile (
    ord      integer,
    section  text,
    subject  text,
    metric   text,
    value    text
) ON COMMIT DROP;

DO $$
DECLARE
    section_sql text;
    section_ord integer;
    section_name text;
    sections text[][] := ARRAY[

    -- ---------------------------------------------------------------- volumes
    -- Every base table and its row count, discovered rather than named, so a
    -- table added since the models were checked in still appears. This is the
    -- list ADR 0024's coverage register must reconcile against: anything here
    -- and not in the register is an unexamined source.
    ['10', 'volumes', $q$
        SELECT 10, 'volumes', c.relname, 'row_count',
               (xpath('/row/c/text()',
                      query_to_xml(format('SELECT count(*) AS c FROM %I.%I',
                                          n.nspname, c.relname),
                                   false, true, '')))[1]::text
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind = 'r' AND n.nspname = 'public'
    $q$],

    -- The age of each table, which is how retention and archive scope get
    -- decided, and how a dead feature announces itself: a table whose newest
    -- row is two years old is not a feature anyone is using.
    ['11', 'volumes', $q$
        SELECT 11, 'volumes', c.relname, 'created_at_range',
               (xpath('/row/c/text()',
                      query_to_xml(format(
                          'SELECT coalesce(min(created_at)::date::text, ''none'')
                                  || '' .. '' ||
                                  coalesce(max(created_at)::date::text, ''none'') AS c
                           FROM %I.%I', n.nspname, c.relname),
                          false, true, '')))[1]::text
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'created_at'
                           AND a.attnum > 0 AND NOT a.attisdropped
        WHERE c.relkind = 'r' AND n.nspname = 'public'
    $q$],

    -- ------------------------------------------------------------- reference
    -- The lookup tables, in full. These are reference data rather than business
    -- data: every one of their values has to be mapped explicitly, and ADR 0024
    -- quarantines the order that carries an unmapped one. Small, safe, and
    -- useless to guess at.
    ['20', 'reference', $q$
        SELECT 20, 'reference', 'statuses', id::text, coalesce(name::text, '')
        FROM statuses
    $q$],
    ['21', 'reference', $q$
        SELECT 21, 'reference', 'order_statuses', id::text, coalesce(name::text, '')
        FROM order_statuses
    $q$],
    ['22', 'reference', $q$
        SELECT 22, 'reference', 'order_types', id::text, coalesce(name::text, '')
        FROM order_types
    $q$],
    ['23', 'reference', $q$
        SELECT 23, 'reference', 'delivery_methods', id::text, coalesce(name::text, '')
        FROM delivery_methods
    $q$],
    ['24', 'reference', $q$
        SELECT 24, 'reference', 'payment_methods', id::text, coalesce(name::text, '')
        FROM payment_methods
    $q$],
    ['25', 'reference', $q$
        SELECT 25, 'reference', 'cities', id::text, coalesce(name::text, '')
        FROM cities
    $q$],

    -- --------------------------------------------------------------- tenancy
    -- The brand inventory. Slugs and locale keys only: a brand's slug is public
    -- and its locale key set is schema, while its description is content.
    ['30', 'tenancy', $q$
        SELECT 30, 'tenancy', 'companies', slug,
               'name_locales=' || coalesce((SELECT string_agg(k, '|' ORDER BY k)
                                            FROM jsonb_object_keys(name) k), 'none')
               || ' image=' || (image IS NOT NULL)::text
               || ' background=' || (background_image IS NOT NULL)::text
        FROM companies
    $q$],

    -- Branch counts per brand, which is what decides whether "one tenant, many
    -- brands" is a sane shape or whether one brand dominates the estate.
    ['31', 'tenancy', $q$
        SELECT 31, 'tenancy', c.slug, 'vendor_count', count(v.id)::text
        FROM companies c LEFT JOIN vendors v ON v.company_id = c.id
        GROUP BY c.slug
    $q$],

    -- The completeness of what V0023 just added to tenant.locations. Every null
    -- here is a branch that cannot originate an ADR 0037 delivery zone, cannot
    -- be navigated to, and cannot be telephoned.
    ['32', 'tenancy', $q$
        SELECT 32, 'tenancy', 'vendors', 'field_completeness',
               'rows=' || count(*)
               || ' lat_null=' || count(*) FILTER (WHERE latitude IS NULL)
               || ' lon_null=' || count(*) FILTER (WHERE longitude IS NULL)
               || ' half_pair=' || count(*) FILTER (WHERE (latitude IS NULL) <> (longitude IS NULL))
               || ' phone_null=' || count(*) FILTER (WHERE phone IS NULL)
               || ' address_null=' || count(*) FILTER (WHERE address IS NULL)
               || ' city_null=' || count(*) FILTER (WHERE city_id IS NULL)
               || ' tin_null=' || count(*) FILTER (WHERE tin IS NULL)
        FROM vendors
    $q$],

    -- Coordinates that are present but not on the earth, or sitting at 0,0 —
    -- a real point in the Gulf of Guinea that every distance calculation will
    -- accept. These quarantine rather than migrate.
    ['33', 'tenancy', $q$
        SELECT 33, 'tenancy', 'vendors', 'coordinate_sanity',
               'out_of_range=' || count(*) FILTER (
                    WHERE latitude IS NOT NULL AND (latitude NOT BETWEEN -90 AND 90
                          OR longitude NOT BETWEEN -180 AND 180))
               || ' at_null_island=' || count(*) FILTER (
                    WHERE latitude = 0 AND longitude = 0)
        FROM vendors
    $q$],

    ['34', 'tenancy', $q$
        SELECT 34, 'tenancy', 'vendors', 'status_' || status_id::text, count(*)::text
        FROM vendors GROUP BY status_id
    $q$],

    -- Orphans. ADR 0024 quarantines a row without provable ownership rather
    -- than assigning it to a convenient parent, so these counts are the size of
    -- the quarantine queue before the first run.
    ['35', 'tenancy', $q$
        SELECT 35, 'tenancy', 'vendors', 'orphaned_company', count(*)::text
        FROM vendors v LEFT JOIN companies c ON c.id = v.company_id
        WHERE c.id IS NULL
    $q$],

    -- The JSON columns nothing can be migrated without. Key sets and how many
    -- branches share each shape; a single dominant shape is a migration, a long
    -- tail is a profiling problem.
    ['36', 'tenancy', $q$
        SELECT 36, 'tenancy', 'vendors.work_time', 'keyset:' ||
               coalesce((SELECT string_agg(k, '|' ORDER BY k)
                         FROM jsonb_object_keys(work_time) k), 'empty'),
               count(*)::text
        FROM vendors WHERE work_time IS NOT NULL
        GROUP BY 3
    $q$],
    ['37', 'tenancy', $q$
        SELECT 37, 'tenancy', 'vendors.delivery', 'keyset:' ||
               coalesce((SELECT string_agg(k, '|' ORDER BY k)
                         FROM jsonb_object_keys(delivery) k), 'empty'),
               count(*)::text
        FROM vendors WHERE delivery IS NOT NULL
        GROUP BY 3
    $q$],
    -- Managers: how many, and which keys each entry carries. Never the entries.
    ['38', 'tenancy', $q$
        SELECT 38, 'tenancy', 'vendors.managers', 'entry_count=' ||
               coalesce(jsonb_array_length(managers), 0)::text, count(*)::text
        FROM vendors WHERE managers IS NOT NULL
        GROUP BY 3
    $q$],

    -- --------------------------------------------------------------- identity
    -- The crosswalk question. customers.company is a hardcoded string enum with
    -- five values and is NOT a foreign key to companies — so which customers
    -- belong to which brand cannot be resolved by a join, and this distribution
    -- is the only evidence for how the two relate.
    ['40', 'identity', $q$
        SELECT 40, 'identity', 'customers.company', company, count(*)::text
        FROM customers GROUP BY company
    $q$],

    -- Does every enum value correspond to a company slug? A value here with no
    -- matching slug is a customer partition with no brand to attach to.
    ['41', 'identity', $q$
        SELECT 41, 'identity', 'customers.company', 'unmatched_by_company_slug:' || c.company,
               count(*)::text
        FROM customers c
        WHERE NOT EXISTS (SELECT 1 FROM companies co WHERE co.slug = c.company)
        GROUP BY c.company
    $q$],

    -- The decision this drives: whether one Qoida tenant may share customer
    -- accounts across brands, or must keep them isolated. A phone that appears
    -- under two partitions is two people today. ADR 0015 and ADR 0024 both
    -- forbid auto-merging them, so a nonzero count here settles the identity
    -- mode by itself. Counts only — no phone leaves the database.
    ['42', 'identity', $q$
        SELECT 42, 'identity', 'customers', 'phones_in_multiple_partitions',
               count(*)::text
        FROM (SELECT phone FROM customers
              WHERE phone IS NOT NULL AND phone <> ''
              GROUP BY phone HAVING count(DISTINCT company) > 1) d
    $q$],
    ['43', 'identity', $q$
        SELECT 43, 'identity', 'customers', 'duplicate_phones_within_one_partition',
               count(*)::text
        FROM (SELECT phone, company FROM customers
              WHERE phone IS NOT NULL AND phone <> ''
              GROUP BY phone, company HAVING count(*) > 1) d
    $q$],
    ['44', 'identity', $q$
        SELECT 44, 'identity', 'customers', 'archived_chain_rows', count(*)::text
        FROM customers WHERE archive_id IS NOT NULL
    $q$],
    ['45', 'identity', $q$
        SELECT 45, 'identity', 'customers.extra', 'keyset:' ||
               coalesce((SELECT string_agg(k, '|' ORDER BY k)
                         FROM jsonb_object_keys(extra) k), 'empty'),
               count(*)::text
        FROM customers WHERE extra IS NOT NULL
        GROUP BY 3
    $q$],

    -- Addresses: completeness and geocoding coverage, never content.
    ['46', 'identity', $q$
        SELECT 46, 'identity', 'customer_addresses', 'completeness',
               'rows=' || count(*)
               || ' lat_null=' || count(*) FILTER (WHERE latitude IS NULL)
               || ' half_pair=' || count(*) FILTER (WHERE (latitude IS NULL) <> (longitude IS NULL))
        FROM customer_addresses
    $q$],

    -- ----------------------------------------------------------------- money
    -- The financial reconciliation baseline. ADR 0024 requires exact money
    -- totals by currency, provider, and status before any cutover, and these are
    -- the numbers those rules will be written against.
    ['50', 'money', $q$
        SELECT 50, 'money', 'orders', 'status_' || status_id::text,
               'count=' || count(*) || ' sum=' || coalesce(sum(total)::text, '0')
        FROM orders GROUP BY status_id
    $q$],
    ['51', 'money', $q$
        SELECT 51, 'money', 'orders', 'orphaned_vendor', count(*)::text
        FROM orders o LEFT JOIN vendors v ON v.id = o.vendor_id
        WHERE v.id IS NULL
    $q$],
    ['52', 'money', $q$
        SELECT 52, 'money', 'payments', 'status_' || coalesce(status::text, 'null'),
               'count=' || count(*) || ' sum=' || coalesce(sum(amount)::text, '0')
        FROM payments GROUP BY status
    $q$],
    ['53', 'money', $q$
        SELECT 53, 'money', 'transactions', 'kind_' || coalesce(type::text, 'null'),
               'count=' || count(*) || ' sum=' || coalesce(sum(amount)::text, '0')
        FROM transactions GROUP BY type
    $q$],
    -- Orders whose line items do not reconstitute the order total. Every one is
    -- a row that cannot be imported as an immutable snapshot without deciding
    -- which number is true.
    ['54', 'money', $q$
        SELECT 54, 'money', 'orders', 'line_sum_disagrees_with_total', count(*)::text
        FROM (
            SELECT o.id
            FROM orders o JOIN order_line_items li ON li.order_id = o.id
            GROUP BY o.id, o.total
            HAVING coalesce(sum(li.price * li.quantity), 0) <> o.total
        ) d
    $q$],
    ['55', 'money', $q$
        SELECT 55, 'money', 'tax_receipts', 'rows_and_linkage',
               'rows=' || count(*)
               || ' unlinked_order=' || count(*) FILTER (WHERE order_id IS NULL)
        FROM tax_receipts
    $q$],

    -- ------------------------------------------------------- dead or alive
    -- The nine sources sitting at DECIDE in the coverage register. Row count and
    -- newest row settle "retire unless the storefront uses it" on evidence: a
    -- table with rows but nothing written in a year is a feature that has
    -- already been retired by everyone except the schema.
    ['60', 'disposition', $q$
        SELECT 60, 'disposition', t.tbl, 'rows_and_last_write',
               (xpath('/row/c/text()', query_to_xml(format(
                    'SELECT ''rows='' || count(*) ||
                            '' last='' || coalesce(max(created_at)::date::text, ''none'') AS c
                     FROM public.%I', t.tbl), false, true, '')))[1]::text
        FROM (VALUES ('favourite_products'), ('search_histories'), ('black_lists'),
                     ('customer_invitations'), ('tags'), ('product_tags'),
                     ('recommended_products'), ('ui_elements'), ('ui_element_items'),
                     ('ui_offers'), ('faqs'), ('faq_categories'), ('social_medias'),
                     ('kitchens'), ('ratings'), ('incidents'), ('offers')) AS t(tbl)
        WHERE to_regclass('public.' || t.tbl) IS NOT NULL
    $q$],

    -- ------------------------------------------------------- configuration
    -- Keys only, and the shape of each value — never a value. A config table
    -- this old holds provider credentials, and this file must not become the
    -- place they leaked from.
    ['70', 'configuration', $q$
        SELECT 70, 'configuration', 'configs', key,
               'type=' || jsonb_typeof(to_jsonb(value))
               || CASE WHEN jsonb_typeof(to_jsonb(value)) = 'object'
                       THEN ' keys=' || coalesce((SELECT string_agg(k, '|' ORDER BY k)
                                                  FROM jsonb_object_keys(to_jsonb(value)) k), '')
                       ELSE '' END
        FROM configs
    $q$],

    -- --------------------------------------------------------------- catalog
    ['80', 'catalog', $q$
        SELECT 80, 'catalog', 'products', 'per_company',
               'companies_with_products=' || count(DISTINCT company_id)
               || ' products=' || count(*)
        FROM products
    $q$],
    ['81', 'catalog', $q$
        SELECT 81, 'catalog', 'variants', 'stock_and_price',
               'rows=' || count(*)
               || ' price_null=' || count(*) FILTER (WHERE price IS NULL)
        FROM variants
    $q$],
    ['82', 'catalog', $q$
        SELECT 82, 'catalog', 'stocks', 'rows', count(*)::text FROM stocks
    $q$],
    -- Media that the migration has to find on a filesystem. A path recorded here
    -- with no file behind it is a quarantine item, and the count is how much of
    -- ADR 0010's inventory step is actually at risk.
    ['83', 'catalog', $q$
        SELECT 83, 'catalog', 'media_paths', 'declared',
               'company_images=' || (SELECT count(*) FROM companies WHERE image IS NOT NULL)
               || ' vendor_images=' || (SELECT count(*) FROM vendors WHERE image IS NOT NULL)
               || ' product_images=' || (SELECT count(*) FROM products WHERE image IS NOT NULL)
    $q$]

    ];
    i integer;
BEGIN
    FOR i IN 1 .. array_length(sections, 1) LOOP
        section_ord  := sections[i][1]::integer;
        section_name := sections[i][2];
        section_sql  := sections[i][3];
        BEGIN
            EXECUTE 'INSERT INTO profile (ord, section, subject, metric, value) ' || section_sql;
        EXCEPTION WHEN OTHERS THEN
            -- A section that cannot run is itself a finding: the column or table
            -- this profile assumed does not exist in production, which means the
            -- checked-in models and the real schema have diverged. Recorded
            -- rather than aborting, so one missing column does not cost the whole
            -- export.
            INSERT INTO profile (ord, section, subject, metric, value)
            VALUES (section_ord, section_name, '(section failed)',
                    'sqlstate_' || SQLSTATE, SQLERRM);
        END;
    END LOOP;
END $$;

SELECT section, subject, metric, value
FROM profile
ORDER BY ord, subject, metric;
