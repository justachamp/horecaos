# The operator's shell.
#
# Every command in docs/runbooks/restore.md runs in this container rather than on
# the host, for two reasons. The host is a colocation box that will be rebuilt at
# some point, and a runbook that begins "first install the PostgreSQL 18 client
# and MinIO's mc" is a runbook that fails when it is needed. And `pg_dump` from
# the wrong major version refuses to dump a newer server — which is discovered
# at 3am, not before.
#
# It also means the backup and restore scripts run against exactly the same
# PostgreSQL client version as the server they are talking to, because both come
# from the same upstream image.

FROM minio/mc:RELEASE.2025-07-21T05-28-08Z AS mc

FROM postgres:18-alpine

# `ca-certificates` is needed the moment the off-site destination becomes a real
# HTTPS endpoint rather than the MinIO next door.
# `openssl` is what backup.sh encrypts with; `curl` and `jq` are what
# bao-get.sh reads the backup passphrase with at 2am when nobody is awake to
# type a token.
# `python3` is for infra/keycloak/assign-service-account-roles.sh, which the
# bootstrap runbook runs from in here: Keycloak publishes no port in production,
# so the script has to run from a container on the compose network rather than
# from the host.
RUN apk add --no-cache openssl ca-certificates bash curl jq python3

COPY --from=mc /usr/bin/mc /usr/local/bin/mc

# The backup scripts are copied in rather than mounted, so that the image is a
# self-contained thing that can be pulled onto a rescue host where this
# repository is not checked out.
COPY infra/backup/ /opt/qoida/backup/
COPY infra/production/ops/bao-get.sh /usr/local/bin/bao-get.sh
COPY infra/production/ops/backup-job.sh /usr/local/bin/backup-job.sh
RUN chmod 0555 /opt/qoida/backup/*.sh /usr/local/bin/bao-get.sh /usr/local/bin/backup-job.sh

WORKDIR /opt/qoida/backup

# No credentials, no endpoints, no bucket names. Everything this image needs is
# supplied when it is run.

# Nothing here should run unattended. Landing in a shell means a mistyped
# `docker compose run ops` does nothing at all, which is the right outcome for a
# container that has pg_restore in it.
ENTRYPOINT []
CMD ["/bin/bash"]
