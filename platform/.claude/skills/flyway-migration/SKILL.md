---
name: flyway-migration
description: Use whenever adding or changing a database schema in Qoida Platform — any file under src/main/resources/db/migration, any CREATE TABLE, ALTER TABLE, index, constraint, or backfill. Flyway is the schema authority and migrations are append-only.
---

# Flyway migrations

`src/main/resources/db/migration/` is **append-only**. An applied migration is history.
Changing one desynchronises every environment that already ran it.

## Writing one

1. Take the next free version: `V00NN__<snake_case_description>.sql`. Never reuse a
   number — a duplicate silently shadows the other migration.
2. Explicit SQL only. Flyway is the schema authority; Spring JDBC reads it. There is no
   ORM and adding one requires an ADR, not a dependency.
3. **End the migration with a `GRANT` block for every table it creates.** A
   `GRANT ... ON ALL TABLES IN SCHEMA` covers only tables that exist when it runs, so an
   earlier blanket grant does not cover your new table. Nine migrations got this wrong;
   V0035 exists to repair them, and the failure is invisible until production starts.
4. Constraints encode invariants: tenant scope, positive quantities, uniqueness, valid
   ranges, mutually exclusive fields. Prefer a constraint over a service-layer check.

## Column rules

- Money: `bigint` minor units plus an ISO currency code. Never `real`, `double
  precision`, `numeric`, or `money`.
- Instants: `timestamptz`, UTC. A location's IANA timezone is a separate column. Never
  `timestamp without time zone`, never a formatted string.
- `JSONB` only for raw provider payloads, event payloads, and genuinely flexible
  metadata. Searchable core business state gets real columns.
- Legacy identifiers live in explicit `legacy_id` columns or mapping tables.
- PostGIS types for points and zones once fulfillment geography is implemented.

## Changing an existing table

Expand → migrate → verify → cut over → contract, as separate migrations. Never rename or
drop in the same migration that starts writing the replacement. Backfills are restartable
and idempotent, with recorded checkpoints — they run against production volumes, not
fixtures.

## Before saying it is done

- [ ] Version number is free and the filename is `V00NN__snake_case.sql`
- [ ] Every new table has an explicit `GRANT` in this migration
- [ ] Every tenant-owned table has non-null `tenant_id` in its keys and constraints
- [ ] Applies cleanly against a populated database, not just an empty one
- [ ] Testcontainers test covers it; cross-tenant insert is rejected
- [ ] `python3 tools/checks/repo_hygiene.py` passes

## Reject

Editing an applied migration. Add a new one — including to fix a mistake in the one you
just wrote, if it has left your machine.
