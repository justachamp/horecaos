# syntax=docker/dockerfile:1.19

# The Qoida Platform application image.
#
# Why a hand-written Dockerfile rather than `spring-boot:build-image`
# ------------------------------------------------------------------
# Buildpacks were the obvious candidate and were rejected for three reasons that
# matter on this project specifically.
#
# First, reproducibility. A buildpack image is assembled by a builder image that
# is resolved at build time and whose contents are decided outside this
# repository. Reading the pom tells you nothing about what ends up in the image.
# Here every input is a digest, and the two `FROM` lines below are the complete
# answer to "what is in this image".
#
# Second, the JDK. The enforcer plugin pins this build to JDK 25 exactly, and a
# buildpack that silently selects a different JVM major would either fail late or
# — worse — succeed on a JVM nobody tested against.
#
# Third, the entrypoint. This image must not start until a secret has arrived
# from OpenBao, and that handshake lives in `infra/app/entrypoint.sh`. Bolting a
# custom entrypoint onto a buildpack image means overriding the launcher the
# buildpack exists to provide, which is most of the argument for using one.
#
# Reproducibility
# ---------------
# * Both base images are pinned by digest, not by tag.
# * `project.build.outputTimestamp` in pom.xml already makes the jar itself
#   reproducible; the extracted layers inherit that.
# * Every file copied into the runtime stage has its modification time
#   normalised to SOURCE_DATE_EPOCH, so two builds of the same commit produce
#   byte-identical layers.
#
# Build:
#   docker build -t qoida/platform:$(git rev-parse --short HEAD) \
#     --build-arg GIT_SHA=$(git rev-parse HEAD) .

ARG JDK_IMAGE=eclipse-temurin:25-jdk@sha256:e787e08ef76f4c16866108cd7f9fcd96a68eef3ac6cc76866897d4d02d5a2262
ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682


FROM ${JDK_IMAGE} AS build

WORKDIR /build

# The wrapper and the pom are copied on their own so that the dependency
# download is a cached layer for every build where only `src/` changed — which
# is every build except a dependency bump.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# `dependency:go-offline` is a warm-up, not a guarantee: it is well known to miss
# plugin dependencies resolved later in the lifecycle. It is allowed to fail so
# that a partial warm-up still produces a usable cache layer; the `package` run
# below fetches anything it missed.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp -Dmaven.test.skip=true dependency:go-offline || true

COPY src/ src/

# Tests do not run here. The image build is not the place to discover that the
# suite is red — the deploy runbook runs `tools/mvn-serial verify` before it ever
# reaches this file, and an image build that also runs tests just means waiting
# twice for the same answer.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp -Dmaven.test.skip=true package

# Split the fat jar into layers that change at different rates: third-party
# dependencies almost never, application classes on every commit. A one-line
# code change then re-pushes a few hundred kilobytes rather than 90 MB, which on
# a colocated server reached over an ordinary Uzbek uplink is the difference
# between a deploy that takes seconds and one that takes minutes.
RUN set -eux; \
    jar="$(find target -maxdepth 1 -name 'qoida-platform-*.jar' ! -name '*-plain.jar' | head -1)"; \
    test -n "${jar}"; \
    java -Djarmode=tools -jar "${jar}" extract --layers --launcher --destination /build/extracted


FROM ${JRE_IMAGE} AS runtime

# A fixed uid/gid rather than a name lookup: the compose file mounts a tmpfs that
# the OpenBao agent writes secrets into, and the two containers have to agree on
# a number, not on a passwd entry.
ARG APP_UID=10001
ARG APP_GID=10001
ARG GIT_SHA=unknown
ARG SOURCE_DATE_EPOCH=1755475200

LABEL org.opencontainers.image.title="Qoida Platform" \
      org.opencontainers.image.source="https://github.com/qoida-one/qoida-platform" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.base.name="eclipse-temurin:25-jre-alpine"

RUN set -eux; \
    addgroup -S -g "${APP_GID}" qoida; \
    adduser -S -D -H -u "${APP_UID}" -G qoida -s /sbin/nologin qoida

WORKDIR /app

# Least-changing layer first. Each of these is a directory produced by the
# `extract --layers` step above, and the order is the order Spring Boot assigns
# them.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
COPY infra/app/entrypoint.sh /usr/local/bin/qoida-entrypoint

# Ownership is root and the mode is read-only for the application user. The
# container runs with a read-only root filesystem in production; this makes the
# same property hold when someone runs the image by hand to reproduce a problem.
RUN set -eux; \
    chmod 0555 /usr/local/bin/qoida-entrypoint; \
    find /app -exec touch -h -d "@${SOURCE_DATE_EPOCH}" {} +; \
    touch -h -d "@${SOURCE_DATE_EPOCH}" /usr/local/bin/qoida-entrypoint

ENV QOIDA_HTTP_PORT=8080 \
    JAVA_TOOL_OPTIONS="" \
    LANG=C.UTF-8 \
    TZ=Asia/Tashkent

# Not one credential, and not one default that would work in production if the
# operator forgot to set it. The image carries no database URL, no token, and no
# issuer: an unconfigured container fails at startup rather than quietly
# connecting to something.

USER ${APP_UID}:${APP_GID}

EXPOSE 8080

# `readiness` rather than the aggregate `/actuator/health`, because the aggregate
# includes dependency indicators. Compose uses this to decide when dependents may
# start, and it is also what the autoheal watcher restarts on. ADR 0023 is
# explicit that a probe which restarts the process when the database is slow
# converts a degradation into an outage — so nothing here consults the database.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${QOIDA_HTTP_PORT}/actuator/health/readiness" || exit 1

ENTRYPOINT ["/usr/local/bin/qoida-entrypoint"]
