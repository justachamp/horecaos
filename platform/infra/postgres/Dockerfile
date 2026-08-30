# PostgreSQL 18 with PostGIS.
#
# The official postgis/postgis images publish linux/amd64 only, and running one
# under emulation on Apple Silicon crashes the server on CREATE EXTENSION. Half
# this team develops on Apple Silicon and the whole test suite runs against
# Testcontainers, so an emulated database is not an option.
#
# Alpine packages PostGIS 3.6 built against PostgreSQL 18 for aarch64 and amd64
# alike. The official postgres image is also PostgreSQL 18 on Alpine, so the two
# are the same major on the same libc — they differ only in install prefix. This
# copies the extension into the prefix the official image uses.
#
# That pairing is verified rather than assumed: the build fails if the extension
# does not load and report its version.
#
# Both inputs are pinned, because this image is the production database and
# rebuilding it on a rescue host has to produce the server that was running an
# hour ago rather than whatever `18-alpine` moved to overnight. The digest below
# is PostgreSQL 18.6 on Alpine 3.24; `postgis=3.6.3-r0` is the PostGIS build
# Alpine ships against that release.
#
# The pins go stale on purpose. When Alpine replaces 3.6.3-r0 this build stops
# with "unable to select packages", which is a one-token change made by somebody
# who then watches the verification below pass. The alternative is a silent minor
# upgrade of the extension that owns every geometry index — discovered when a
# restored backup and a running server disagree about one.
FROM postgres:18-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2

RUN set -eux; \
    apk add --no-cache postgis=3.6.3-r0; \
    cp -a /usr/lib/postgresql18/*.so "$(pg_config --pkglibdir)/"; \
    cp -a /usr/share/postgresql18/extension/postgis* "$(pg_config --sharedir)/extension/" 2>/dev/null || true; \
    cp -a /usr/share/postgresql18/extension/address_standardizer* "$(pg_config --sharedir)/extension/" 2>/dev/null || true; \
    ls "$(pg_config --sharedir)/extension/" | grep -q '^postgis.control$'
