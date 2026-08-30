#!/bin/bash
#
# Creates the login role the application connects as.
#
# Runs once, on an empty data directory, before anything else touches the
# database. It is written to be idempotent anyway: a script that can only be
# tested by destroying the database is a script nobody tests.
#
# Why two roles at all. `POSTGRES_USER` is `qoida_migrator`: it owns every
# object, and it is the only role that can change the schema. The application
# connects as `qoida_app`, which owns nothing and holds only the privileges each
# migration grants it by name — insert-and-select on audit tables, no DDL
# anywhere, and no access at all to a table that no migration mentioned.
#
# The consequence worth stating plainly: SQL injection through the application's
# connection cannot drop a table, cannot read a table the application was never
# granted, and cannot rewrite an audit row. That is not a theoretical benefit on
# a platform whose audit trail is evidence under ADR 0027.
#
# The two NOLOGIN group roles below are also created by migrations V0007 and
# V0031. They are created here as well because `GRANT qoida_application TO
# qoida_app` has to happen after both roles exist, and the migrations run later.
# Both sides guard with IF NOT EXISTS, so whichever runs first wins and the other
# is a no-op.
#
# This script is mounted by compose.production.yaml AND by compose.yaml. That is
# deliberate and it is the entire fix for the defect this file used to document
# without preventing: for sixty-one migrations the local stack ran the
# application as `qoida` — superuser, and owner of the database — so every GRANT
# and REVOKE the migrations wrote was bypassed on every laptop and in every test.
# A privilege that only exists in production is a privilege nobody has tested. The
# two environments differ in exactly one place now, the source of the password,
# and nowhere else.

set -euo pipefail

app_password_file=/run/secrets/platform-db-app-password

# Production hands the password in on a tmpfs, put there by deploy.sh from
# OpenBao. Local development has no OpenBao at initdb time and no secret mount, so
# compose.yaml passes QOIDA_APP_PASSWORD directly — the same worthless-outside-a-
# laptop value as every other credential in that file. The file wins when both are
# present, so a production host that also happens to have the variable set cannot
# be talked into a weaker password.
if [ -s "${app_password_file}" ]; then
    app_password="$(cat "${app_password_file}")"
elif [ -n "${QOIDA_APP_PASSWORD:-}" ]; then
    app_password="${QOIDA_APP_PASSWORD}"
else
    echo "!! Neither ${app_password_file} nor QOIDA_APP_PASSWORD is set." >&2
    echo "!! The application role cannot be created without a password; refusing" >&2
    echo "!! to initialise a database the application would not be able to reach." >&2
    exit 1
fi

# Handed to psql through the environment and picked up with \getenv, rather than
# through --set. A --set value is an argv entry, and argv is world-readable in
# /proc on the host; an environment entry is readable only by root and the
# process itself. psql's :'variable' quoting is what then puts it into the
# statement safely.
export QOIDA_APP_PASSWORD="${app_password}"
unset app_password

psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
     --set ON_ERROR_STOP=1 --no-psqlrc --quiet <<-'SQL'
	\getenv app_password QOIDA_APP_PASSWORD
	\getenv database POSTGRES_DB

	DO $$
	BEGIN
	    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qoida_application') THEN
	        CREATE ROLE qoida_application NOLOGIN;
	    END IF;
	    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qoida_reporting_read') THEN
	        CREATE ROLE qoida_reporting_read NOLOGIN;
	    END IF;
	END
	$$;

	DO $$
	BEGIN
	    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qoida_app') THEN
	        CREATE ROLE qoida_app LOGIN;
	    END IF;
	END
	$$;

	ALTER ROLE qoida_app PASSWORD :'app_password';

	-- Not superuser, not createdb, not createrole, not replication, and not
	-- inheriting anything except the one group role below.
	ALTER ROLE qoida_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT;

	-- The database name rather than a literal, because this script now runs
	-- against the local stack too and a literal would silently grant CONNECT on
	-- a database that is not the one being initialised.
	GRANT CONNECT ON DATABASE :"database" TO qoida_app;
	GRANT qoida_application TO qoida_app;

	-- PostgreSQL 15 and later already revoke CREATE on public from everyone but
	-- the owner. Stated here so that a future restore into an older server, or a
	-- database created by hand, does not quietly reopen it.
	REVOKE CREATE ON SCHEMA public FROM PUBLIC;

	-- A statement that runs for an hour on the application's connection is not a
	-- query, it is an incident holding a lock. The migration role is deliberately
	-- exempt: an index build legitimately takes longer than this.
	--
	-- 60s is chosen to sit above the slowest reporting rollup measured so far and
	-- well below any human's patience. A job that genuinely needs longer raises it
	-- for its own transaction with SET LOCAL rather than raising it for everything.
	ALTER ROLE qoida_app SET statement_timeout = '60s';
	ALTER ROLE qoida_app SET idle_in_transaction_session_timeout = '60s';
	ALTER ROLE qoida_app SET lock_timeout = '5s';
SQL

unset QOIDA_APP_PASSWORD

echo "==> qoida_app created; the application will connect as a non-owner"
