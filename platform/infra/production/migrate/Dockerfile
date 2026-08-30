# The one-shot migration job from ADR 0023.
#
# Flyway's own command line at exactly the version Spring Boot 4.1 manages for
# flyway-core. If the tool that writes `flyway_schema_history` and the library
# that would read it ever disagreed about that table's format, the symptom would
# be an application that refuses to start after a successful migration — during a
# deploy, with no obvious cause. Pinning both to 12.4.0 removes the question.
#
# Keep this in step with `mvn dependency:tree | grep flyway-core` on every Spring
# Boot upgrade. It is a two-line change and the failure it prevents is a bad
# evening.
#
# Built as its own image rather than reusing the application image on purpose:
# this job has to be runnable when the application image is broken, which is
# precisely the situation a failed deploy creates.
FROM flyway/flyway:12.4.0-alpine

# The migrations, and nothing else. No jar, no application configuration, no
# credentials — the connection details arrive as environment at run time.
COPY src/main/resources/db/migration/ /flyway/sql/

# Owned by root and read-only. The migration job has no reason to be able to
# rewrite the SQL it is about to apply, and a container that cannot modify its
# own migrations cannot be talked into applying a different one.
USER root
RUN chmod -R a-w /flyway/sql

# And back down again. The base image's own default is uid 0, so leaving it here
# would run the migration as root — for a job whose only privilege should be the
# database credential it is handed. `chmod` above is the one step that needed
# root, and it happens at build time.
#
# Numeric rather than `nobody`, because the drop must not depend on a passwd
# entry that a future base image is free to stop shipping: a `USER nobody` that
# no longer resolves fails at `docker run`, during a deploy, with a message about
# users rather than about migrations.
USER 65534:65534

# `flyway` is already the image's entrypoint; the runbook appends the command
# (`migrate`, `info`, `validate`, `repair`). `info` is the default so that
# starting this container with no arguments reports the state of the database
# rather than changing it.
CMD ["info"]
