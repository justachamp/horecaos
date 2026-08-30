-- Legacy profiling, second pass (ADR 0024, Phase 0).
--
-- Seven sections of the first pass failed against real column names. This is the
-- delta: those sections corrected, plus the questions the first pass's answers
-- raised. Run it the same way and send the CSV.
--
--     psql "$LEGACY_URL" -v ON_ERROR_STOP=1 -f legacy-profile-2.sql \
--          --csv -o legacy-profile-2.csv
--
-- What the first pass got wrong, and why each mattered:
--
--   * The audit columns are `created` and `updated`, not `created_at`. Every
--     "when was this last used" question failed, which is exactly the evidence
--     the RETIRE decisions rest on.
--   * `orders` has no `total`. It has `order_price`, `delivery_price` and
--     `packaging_price` as separate columns, so the order total is a sum nobody
--     stored — and the target's `total_minor` has to be reconstructed from a
--     formula this pass is meant to confirm.
--   * `products` hangs off `vendor_id`, not `company_id`. The catalog is
--     branch-owned in the legacy system and brand-owned in the target, which is
--     the largest single transformation in the migration.
--   * `transactions` has `status`, not `type`; `tax_receipts` hangs off
--     `transaction_id`, not `order_id`; `configs` is keyed by `id`, not `key`.
--   * The JSON key-set queries were malformed: a correlated subquery cannot be
--     grouped by in the same select. Fixed by computing the key set in a derived
--     table first.
--
-- Same discipline as the first pass: counts, key sets and reference data only.
-- No name, phone, address, username or configuration value leaves the database.

\set ON_ERROR_STOP on

CREATE TEMP TABLE profile2 (
    ord integer, section text, subject text, metric text, value text
) ON COMMIT DROP;

DO $$
DECLARE
    section_sql text; section_ord integer; section_name text;
    sections text[][] := ARRAY[

    -- --------------------------------------------------------------- when
    -- Age of every table, now against the real column name. A table with rows
    -- whose newest write is a year old is a feature that has already been
    -- retired by everyone except the schema.
    ['10', 'age', $q$
        SELECT 10, 'age', c.relname, 'created_range',
               (xpath('/row/c/text()', query_to_xml(format(
                   'SELECT coalesce(min(created)::date::text, ''none'') || '' .. '' ||
                           coalesce(max(created)::date::text, ''none'') AS c
                    FROM public.%I', c.relname), false, true, '')))[1]::text
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'created'
                           AND a.attnum > 0 AND NOT a.attisdropped
        WHERE c.relkind = 'r' AND n.nspname = 'public'
    $q$],

    -- The timezone question, answered by behaviour rather than by asking.
    -- `created` is a naive DateTime, so nothing in the schema says which zone it
    -- is in — and guessing wrong shifts every historical order by five hours,
    -- silently, including across the business-date boundary that the daily order
    -- number depends on. A restaurant's orders peak at lunch and dinner *local*.
    -- If this histogram peaks around 12 and 19 the timestamps are Tashkent local;
    -- if it peaks around 07 and 14 they are UTC.
    ['11', 'age', $q$
        SELECT 11, 'age', 'orders', 'hour_' || lpad(extract(hour FROM created)::text, 2, '0'),
               count(*)::text
        FROM orders GROUP BY 3
    $q$],

    -- ------------------------------------------------------------ json shapes
    -- The columns nothing can be migrated without. Key sets and how many rows
    -- share each shape: one dominant shape is a transformation, a long tail is a
    -- profiling problem that has to be resolved before the wave runs.
    ['20', 'json', $q$
        SELECT 20, 'json', 'vendors.work_time', 'keyset:' || keyset, count(*)::text
        FROM (SELECT coalesce((SELECT string_agg(k, '|' ORDER BY k)
                               FROM jsonb_object_keys(v.work_time::jsonb) k), 'empty') AS keyset
              FROM vendors v WHERE v.work_time IS NOT NULL) s
        GROUP BY keyset
    $q$],
    -- One branch's work_time, one level deep, so the per-day shape is visible.
    -- Opening hours are not personal data and this is the only way to learn
    -- whether an overnight interval is represented as a wrap or as two rows.
    ['21', 'json', $q$
        SELECT 21, 'json', 'vendors.work_time', 'sample_day_keyset:' || keyset, count(*)::text
        FROM (SELECT coalesce((SELECT string_agg(k, '|' ORDER BY k)
                               FROM jsonb_object_keys(e.value) k), 'not_an_object') AS keyset
              FROM vendors v, jsonb_each(v.work_time::jsonb) e
              WHERE v.work_time IS NOT NULL AND jsonb_typeof(e.value) = 'object') s
        GROUP BY keyset
    $q$],
    ['22', 'json', $q$
        SELECT 22, 'json', 'vendors.delivery', 'keyset:' || keyset, count(*)::text
        FROM (SELECT coalesce((SELECT string_agg(k, '|' ORDER BY k)
                               FROM jsonb_object_keys(v.delivery::jsonb) k), 'empty') AS keyset
              FROM vendors v WHERE v.delivery IS NOT NULL) s
        GROUP BY keyset
    $q$],
    ['23', 'json', $q$
        SELECT 23, 'json', 'vendors.managers', 'shape', 'type=' ||
               coalesce(jsonb_typeof(managers::jsonb), 'null')
               || ' entries=' || coalesce(
                    CASE WHEN jsonb_typeof(managers::jsonb) = 'array'
                         THEN jsonb_array_length(managers::jsonb) END, 0)::text,
               count(*)::text
        FROM vendors WHERE managers IS NOT NULL GROUP BY 4
    $q$],
    ['24', 'json', $q$
        SELECT 24, 'json', 'customers.extra', 'keyset:' || keyset, count(*)::text
        FROM (SELECT coalesce((SELECT string_agg(k, '|' ORDER BY k)
                               FROM jsonb_object_keys(c.extra::jsonb) k), 'empty') AS keyset
              FROM customers c WHERE c.extra IS NOT NULL) s
        GROUP BY keyset
    $q$],
    ['25', 'json', $q$
        SELECT 25, 'json', 'products.meta', 'keyset:' || keyset, count(*)::text
        FROM (SELECT coalesce((SELECT string_agg(k, '|' ORDER BY k)
                               FROM jsonb_object_keys(p.meta::jsonb) k), 'empty') AS keyset
              FROM products p WHERE p.meta IS NOT NULL) s
        GROUP BY keyset
    $q$],
    -- ui_elements is one of the undecided nine and the only way to classify it
    -- is to know how many distinct payload shapes it actually holds.
    ['26', 'json', $q$
        SELECT 26, 'json', 'ui_elements', 'type_and_keyset:' || t || '::' || keyset,
               count(*)::text
        FROM (SELECT coalesce(type::text, 'null') AS t,
                     coalesce((SELECT string_agg(k, '|' ORDER BY k)
                               FROM jsonb_object_keys(to_jsonb(u) - 'id') k), 'empty') AS keyset
              FROM ui_elements u) s
        GROUP BY t, keyset
    $q$],

    -- ---------------------------------------------------------- configuration
    -- Keyed by `id`, and the value is JSON. Key names and value shapes only —
    -- a config table this age holds provider credentials and this file must not
    -- become where they leaked from.
    ['30', 'configuration', $q$
        SELECT 30, 'configuration', 'configs', id::text,
               'name_present=' || (name IS NOT NULL)::text
               || ' value_type=' || coalesce(jsonb_typeof(value::jsonb), 'null')
               || CASE WHEN jsonb_typeof(value::jsonb) = 'object'
                       THEN ' value_keys=' || coalesce((SELECT string_agg(k, '|' ORDER BY k)
                                                        FROM jsonb_object_keys(value::jsonb) k), '')
                       ELSE '' END
        FROM configs
    $q$],

    -- ----------------------------------------------------------------- money
    -- There is no stored order total. These are the components, per status, so
    -- the reconstruction formula can be agreed before any order is imported —
    -- and so the reconciliation rule has numbers to assert against.
    ['40', 'money', $q$
        SELECT 40, 'money', 'orders', 'status_' || status_id::text,
               'count=' || count(*)
               || ' order_price=' || coalesce(sum(order_price), 0)
               || ' order_price_no_disc=' || coalesce(sum(order_price_without_discount), 0)
               || ' delivery=' || coalesce(sum(delivery_price), 0)
               || ' delivery_no_disc=' || coalesce(sum(delivery_price_without_discount), 0)
               || ' packaging=' || coalesce(sum(packaging_price), 0)
        FROM orders GROUP BY status_id
    $q$],
    -- Does the line sum reconstitute order_price? Every row where it does not is
    -- an order that cannot be imported as an immutable snapshot until somebody
    -- decides which number is true.
    ['41', 'money', $q$
        SELECT 41, 'money', 'orders', 'line_sum_vs_order_price',
               'orders_with_lines=' || count(*)
               || ' disagreeing=' || count(*) FILTER (WHERE line_total <> order_price)
        FROM (SELECT o.id, o.order_price,
                     coalesce(sum(li.price * li.quantity), 0) AS line_total
              FROM orders o JOIN order_line_items li ON li.order_id = o.id
              GROUP BY o.id, o.order_price) d
    $q$],
    ['42', 'money', $q$
        SELECT 42, 'money', 'transactions', 'status_' || coalesce(status::text, 'null'),
               'count=' || count(*) || ' sum=' || coalesce(sum(amount), 0)
        FROM transactions GROUP BY status
    $q$],
    -- Merchant topology: ADR 0013 lists this as an open input, and the data
    -- answers it. How many payment agents, and are they per brand or per branch?
    ['43', 'money', $q$
        SELECT 43, 'money', 'fin_agents', 'inventory',
               'agents=' || (SELECT count(*) FROM fin_agents)
               || ' referenced_by_transactions=' || (SELECT count(DISTINCT fin_agent_id)
                                                     FROM transactions)
    $q$],
    ['44', 'money', $q$
        SELECT 44, 'money', 'tax_receipts', 'status_' || coalesce(status::text, 'null'),
               count(*)::text
        FROM tax_receipts GROUP BY status
    $q$],
    ['45', 'money', $q$
        SELECT 45, 'money', 'orders', 'nullable_ancestry',
               'rows=' || count(*)
               || ' vendor_null=' || count(*) FILTER (WHERE vendor_id IS NULL)
               || ' address_null=' || count(*) FILTER (WHERE address_id IS NULL)
               || ' operator_set=' || count(*) FILTER (WHERE operator_id IS NOT NULL)
               || ' planned_time_set=' || count(*) FILTER (WHERE planned_time IS NOT NULL)
        FROM orders
    $q$],
    -- The channel the order arrived through, which is ADR 0036's sales channel
    -- under a different name.
    ['46', 'money', $q$
        SELECT 46, 'money', 'orders', 'platform_' || coalesce(platform::text, 'null'),
               count(*)::text
        FROM orders GROUP BY platform
    $q$],
    ['47', 'money', $q$
        SELECT 47, 'money', 'orders', 'type_' || coalesce(type_id::text, 'null'),
               count(*)::text
        FROM orders GROUP BY type_id
    $q$],

    -- --------------------------------------------------------------- catalog
    -- The largest transformation in the migration. Legacy products belong to a
    -- *branch*; the target's catalog is brand-owned with per-location offerings.
    -- If the same dish exists once per branch, 1626 products are far fewer real
    -- products, and the migration has to de-duplicate rather than copy.
    ['50', 'catalog', $q$
        SELECT 50, 'catalog', 'products', 'per_vendor',
               'vendors_with_products=' || count(DISTINCT vendor_id)
               || ' products=' || count(*)
        FROM products
    $q$],
    -- The de-duplication estimate: how many distinct dish names exist per brand
    -- versus how many product rows. A large gap is the size of the collapse.
    ['51', 'catalog', $q$
        SELECT 51, 'catalog', c.slug, 'product_rows_vs_distinct_names',
               'rows=' || count(*)
               || ' distinct_uz_names=' || count(DISTINCT p.name->>'uz')
               || ' branches=' || count(DISTINCT p.vendor_id)
        FROM products p JOIN vendors v ON v.id = p.vendor_id
                        JOIN companies c ON c.id = v.company_id
        GROUP BY c.slug
    $q$],
    -- kitchens is on the undecided list, but products.kitchen_id is a non-null
    -- foreign key — so it cannot be retired, only transformed. This confirms it.
    ['52', 'catalog', $q$
        SELECT 52, 'catalog', 'products', 'kitchen_linkage',
               'rows=' || count(*)
               || ' kitchen_null=' || count(*) FILTER (WHERE kitchen_id IS NULL)
               || ' distinct_kitchens=' || count(DISTINCT kitchen_id)
        FROM products
    $q$],
    -- Discounts live on the product row rather than in a promotion engine. ADR
    -- 0018's price books have to absorb these, so their spread matters.
    ['53', 'catalog', $q$
        SELECT 53, 'catalog', 'products', 'discount_and_availability',
               'has_discount=' || count(*) FILTER (WHERE has_discount)
               || ' tag_discount=' || count(*) FILTER (WHERE tag_discount)
               || ' stock_enabled=' || count(*) FILTER (WHERE stock_enabled)
               || ' time_enabled=' || count(*) FILTER (WHERE time_enabled)
        FROM products
    $q$],
    ['54', 'catalog', $q$
        SELECT 54, 'catalog', 'products', 'discount_type_' || coalesce(discount_type::text, 'none'),
               count(*)::text
        FROM products GROUP BY discount_type
    $q$],
    ['55', 'catalog', $q$
        SELECT 55, 'catalog', 'products', 'status_' || coalesce(status_id::text, 'null'),
               count(*)::text
        FROM products GROUP BY status_id
    $q$],
    -- Three tables the checked-in models do not declare and no migration
    -- document mentions. 2333 rows of import history is not nothing.
    ['56', 'catalog', $q$
        SELECT 56, 'catalog', 'product_import_jobs', 'status_' || coalesce(status::text, 'null'),
               count(*)::text
        FROM product_import_jobs GROUP BY status
    $q$],
    ['57', 'catalog', $q$
        SELECT 57, 'catalog', 'variants', 'linkage',
               'rows=' || count(*)
               || ' distinct_products=' || count(DISTINCT product_id)
        FROM variants
    $q$]

    ];
    i integer;
BEGIN
    FOR i IN 1 .. array_length(sections, 1) LOOP
        section_ord := sections[i][1]::integer;
        section_name := sections[i][2];
        section_sql := sections[i][3];
        BEGIN
            EXECUTE 'INSERT INTO profile2 (ord, section, subject, metric, value) ' || section_sql;
        EXCEPTION WHEN OTHERS THEN
            INSERT INTO profile2 (ord, section, subject, metric, value)
            VALUES (section_ord, section_name, '(section failed)',
                    'sqlstate_' || SQLSTATE, SQLERRM);
        END;
    END LOOP;
END $$;

SELECT section, subject, metric, value FROM profile2 ORDER BY ord, subject, metric;
