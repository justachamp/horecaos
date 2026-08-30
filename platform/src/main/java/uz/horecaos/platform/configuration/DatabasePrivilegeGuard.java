package uz.horecaos.platform.configuration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a real environment on a database connection that owns the
 * database or is a superuser.
 *
 * <p>Modelled on {@code SecretsProfileGuard} and {@code VerificationTransportGuard},
 * and for the same reason: a privilege boundary that is only asserted in SQL and
 * checked by nothing is not a boundary.
 *
 * <p>The platform writes {@code GRANT} and {@code REVOKE} into its migrations and
 * relies on them for guarantees it makes elsewhere in as many words — that
 * {@code audit.audit_events} cannot be rewritten (V0007), that a snapshotted
 * approval policy can only have its {@code valid_until} set and never its terms
 * (V0059), that a table nobody granted is a table the application cannot read
 * (V0035), that the reporting role reads and never writes (V0031). Every one of
 * those is bypassed for a superuser, and every one of them is bypassed for the
 * role that owns the object, because an owner may re-grant to itself at any time.
 * So a deployment that connects as either has all of that written down and none
 * of it true, and nothing about the running system looks different.
 *
 * <p>That is not hypothetical. The default connection was the superuser that owns
 * the database for sixty-one migrations. It was correct in production, where
 * compose.production.yaml pins {@code horecaos_app}, and wrong everywhere a test or
 * a laptop ran — which is precisely why no test ever caught it.
 *
 * <p>Local, test and default profiles are exempt, and the check reports what it
 * found instead. Testcontainers has one login role and it is a superuser that
 * owns the database; there is no way to run a suite's migrations without it. What
 * a local profile gets instead is the log line below and the privilege probes in
 * {@code DatabasePrivilegeTests}, which create a login role, grant it
 * {@code horecaos_application}, and assert the guarantees under the role that
 * actually holds them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabasePrivilegeGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabasePrivilegeGuard.class);

    private static final Set<String> LOCAL_PROFILES = Set.of("local", "test", "default");

    private static final String APPLICATION_ROLE = "horecaos_application";

    /**
     * One round trip, four facts. {@code datdba} is the owner and {@code rolsuper}
     * the superuser flag; membership is asked separately because a superuser is
     * reported a member of everything, which makes that answer useless on its own
     * and misleading if read without the other two.
     *
     * <p>Membership is looked up by oid through a subquery rather than by name,
     * because {@code pg_has_role} raises on a role that does not exist and a guard
     * that cannot run against an unmigrated database is a guard somebody turns
     * off. Absent, the subquery is NULL and reads as false, and
     * {@code group_role_exists} is what tells the two cases apart.
     */
    private static final String IDENTITY_SQL = """
            SELECT current_user AS role_name,
                   pg_catalog.pg_has_role(current_user, 'pg_read_all_data', 'MEMBER') AS reads_everything,
                   (SELECT r.rolsuper FROM pg_catalog.pg_roles r
                     WHERE r.rolname = current_user) AS is_superuser,
                   pg_catalog.pg_get_userbyid(d.datdba) = current_user AS owns_database,
                   EXISTS (SELECT 1 FROM pg_catalog.pg_roles g
                            WHERE g.rolname = '%1$s') AS group_role_exists,
                   (SELECT pg_catalog.pg_has_role(current_user, g.oid, 'MEMBER')
                      FROM pg_catalog.pg_roles g
                     WHERE g.rolname = '%1$s') AS in_application_role
              FROM pg_catalog.pg_database d
             WHERE d.datname = pg_catalog.current_database()
            """.formatted(APPLICATION_ROLE);

    private final Environment environment;
    private final DataSource dataSource;

    public DatabasePrivilegeGuard(Environment environment, DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> active = List.of(environment.getActiveProfiles());
        boolean localOnly = active.isEmpty() || active.stream().allMatch(LOCAL_PROFILES::contains);

        Identity identity = read();

        if (identity.privileged()) {
            if (localOnly) {
                // Not a failure here, and not silent either. A laptop whose
                // platform-db volume predates the two-role split lands in exactly
                // this state, and the symptom without this line is a REVOKE that
                // appears not to work.
                log.warn(
                        "Connected as '{}', which {} — every GRANT and REVOKE in the migrations is "
                                + "bypassed on this connection. Expected on a Testcontainers database; "
                                + "on a local stack it means the platform-db volume predates the "
                                + "horecaos_app/horecaos_migrator split (docker compose down -v && up -d).",
                        identity.roleName(),
                        identity.describe());
                return;
            }
            throw new IllegalStateException("""
                    Profile %s is connected to the database as '%s', which %s.

                    Every GRANT and REVOKE the migrations write is bypassed on that connection: the
                    audit trail is not immutable (V0007), a snapshotted approval policy can be
                    rewritten (V0059), and a table nobody granted is readable anyway (V0035). None
                    of that is visible from outside the database.

                    Connect as the least-privileged role instead — horecaos_app, created by
                    infra/production/postgres-init/10-application-role.sh — and leave schema changes
                    to the migration role. compose.production.yaml already sets HORECAOS_DB_USERNAME
                    for the application container; check that nothing is overriding it.""".formatted(active, identity.roleName(), identity.describe()));
        }

        if (!identity.inApplicationRole()) {
            String detail = identity.groupRoleExists()
                    ? "is not a member of it"
                    : "does not exist in this database, so no migration has ever run here";
            String message = """
                    Profile %s is connected to the database as '%s', and %s %s.

                    That role therefore holds no privilege any migration granted, and the first
                    statement against any table will fail with 'permission denied'. Grant it:
                    GRANT %s TO <the login role>.""".formatted(active, identity.roleName(), APPLICATION_ROLE, detail, APPLICATION_ROLE);
            if (localOnly) {
                log.warn(message);
                return;
            }
            throw new IllegalStateException(message);
        }

        log.info(
                "Database connection is '{}': not a superuser, not the database owner, " + "member of {}.",
                identity.roleName(),
                APPLICATION_ROLE);
    }

    private Identity read() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(IDENTITY_SQL)) {
            if (!rows.next()) {
                throw new IllegalStateException(
                        "Could not determine the database role this application is connected as.");
            }
            return new Identity(
                    rows.getString("role_name"),
                    rows.getBoolean("is_superuser"),
                    rows.getBoolean("owns_database"),
                    rows.getBoolean("reads_everything"),
                    rows.getBoolean("group_role_exists"),
                    rows.getBoolean("in_application_role"));
        } catch (SQLException failure) {
            // Deliberately not swallowed. A guard that cannot answer the question
            // has not answered it, and "we could not check" must not read the same
            // as "we checked and it was fine".
            throw new IllegalStateException(
                    "Could not read the database role this application is connected as.", failure);
        }
    }

    private record Identity(
            String roleName,
            boolean superuser,
            boolean owner,
            boolean readsEverything,
            boolean groupRoleExists,
            boolean inApplicationRole) {

        boolean privileged() {
            return superuser || owner || readsEverything;
        }

        /** Reads into the sentence "…as 'x', which <this>". */
        String describe() {
            if (superuser) {
                return "is a superuser";
            }
            if (owner) {
                return "owns this database";
            }
            return "is a member of pg_read_all_data";
        }
    }
}
