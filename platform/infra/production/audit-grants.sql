-- Fails if the application role cannot reach a table that exists.
--
-- Run by infra/production/deploy.sh immediately after the migration job, so a
-- migration that creates a table and forgets its GRANT block fails the deploy —
-- loudly, with the table named — instead of failing at 3am as `permission
-- denied for schema x` inside a stack trace nobody wants to read at 3am.
--
-- It is the only thing standing between the convention and a repeat of what the
-- first production start found: nine migrations that predate the GRANT block,
-- and 24 tables the application could not read. V0035 closed that gap, and this
-- runs on every deploy so the next one fails here instead of at 3am.
--
-- SELECT is the probe rather than every privilege, because a table the
-- application cannot read is unambiguously wrong, whereas a table it can read
-- but not write may be deliberate — audit and event tables are append-only by
-- design (ADR 0027), and reporting facts are read-only for some roles.

\set ON_ERROR_STOP on

DO $$
DECLARE
    missing text;
    missing_count integer;
BEGIN
    SELECT count(*), string_agg(name, E'\n  ' ORDER BY name)
      INTO missing_count, missing
      FROM (
          SELECT t.table_schema || '.' || t.table_name AS name
            FROM information_schema.tables t
           WHERE t.table_type = 'BASE TABLE'
             AND t.table_schema NOT IN (
                 'pg_catalog', 'information_schema', 'public',
                 -- PostGIS ships its own schemas and the application never
                 -- touches them directly.
                 'topology', 'tiger', 'tiger_data')
             AND NOT has_table_privilege(
                     'qoida_application',
                     quote_ident(t.table_schema) || '.' || quote_ident(t.table_name),
                     'SELECT')
      ) gaps;

    IF missing_count > 0 THEN
        RAISE EXCEPTION E'% table(s) exist that qoida_application cannot read:\n  %\n\nThe GRANT belongs in the migration that created the table, or in a new forward migration where that one cannot be edited. Never run it by hand on the server: grants live with the objects, and the next restore drops it.',
            missing_count, missing;
    END IF;

    RAISE NOTICE 'grant audit: qoida_application can read every table';
END
$$;
