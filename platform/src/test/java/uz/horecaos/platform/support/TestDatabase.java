package uz.horecaos.platform.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container per JVM, one migration per JVM, and a cheap private
 * database per test class.
 *
 * <p>This used to hand every caller its own container. Sixty-nine test classes
 * called it, each started a server, each replayed eighty-one migrations, and
 * several replayed them again in {@code @BeforeEach} — so a suite of two thousand
 * tests paid for roughly two thousand full schema builds. On a Docker daemon
 * capped well below the host's memory the containers stopped fitting and twelve
 * classes died with {@code Container startup failed} or an {@code EOFException}
 * part way through a migration. The cap was not the defect; needing that much was.
 *
 * <p>What replaces it:
 *
 * <ol>
 *   <li>A <strong>single container</strong>, started on first use and never
 *       stopped by a test. Ryuk reaps it when the JVM exits, which is the only
 *       moment at which stopping it is correct — a suite that stops it takes the
 *       database away from every class that has not run yet.
 *   <li>A <strong>template database</strong> migrated to LATEST exactly once. The
 *       work is done inside a class initializer, so the JVM's own
 *       initialization lock is the latch: two classes reaching it together is
 *       safe, and neither sees a half-migrated template.
 *   <li>A <strong>clone per caller</strong> — {@code CREATE DATABASE x TEMPLATE
 *       template} — which PostgreSQL performs as a file copy of the template's
 *       directory. It costs roughly what one migration costs, not what
 *       eighty-one cost, and it gives back exactly the isolation the suites'
 *       {@code TRUNCATE} statements were relying on: a private database.
 * </ol>
 *
 * <p><strong>The database is per class, and that is not an optimization to take
 * back.</strong> A shared database would be cheaper again and would break the
 * suite quietly. Fifty-eight classes clean up with {@code TRUNCATE} and six with
 * {@code DELETE}, several of the latter scoped to a tenant and so leaving other
 * tenants' rows standing. Five classes clean up with nothing at all —
 * {@code DatabasePrivilegeTests} and the two storefront authorization suites read
 * only the catalogue, {@code HealthProbeAndMetricTests} has its own container, and
 * {@code AuditPartitionRaceTests} does DDL per test method and counts
 * {@code information_schema.tables} to check it. Every one of those is correct in
 * a private database and wrong in a shared one.
 *
 * <p><strong>What a private database does not isolate is the cluster.</strong>
 * Roles and databases are cluster-wide, and there is now one cluster per JVM
 * rather than one per class. A suite that creates a LOGIN role to probe a
 * privilege has to drop it again, and drop it first as well, because a failure
 * that skips its {@code finally} would otherwise poison every later run in the
 * same process. {@link #onCluster(String)} is where that belongs.
 *
 * <p>Two ways in:
 *
 * <pre>{@code
 * TestDatabase.Handle db = TestDatabase.migrated();  // already at LATEST
 * TestDatabase.Handle db = TestDatabase.empty();     // migrate it yourself
 * }</pre>
 *
 * <p>{@link #migrated()} is what almost every suite wants. {@link #empty()} is
 * for the handful of suites whose subject <em>is</em> a migration: they target a
 * specific version, insert rows under the schema as it was, and then advance. A
 * pre-migrated template is useless to them by definition.
 *
 * <h2>Why the image is built rather than pulled</h2>
 *
 * <p>The image is built from {@code infra/postgres}, the same Dockerfile
 * {@code compose.yaml} uses, so tests and local development run the same
 * PostgreSQL and the same PostGIS. PostGIS is not optional even for tests that
 * never touch geometry: Flyway runs every migration, and ADR 0037's migration
 * creates the extension.
 *
 * <h2>Why reuse is off</h2>
 *
 * <p>{@code withReuse(true)} would keep the container — and the migrated
 * template — alive between runs, turning a cold start into a warm one. It is
 * deliberately not enabled. A reused container can be stale in two ways, and
 * only one of them is detectable: a template behind the migration scripts can be
 * caught with {@code Flyway.validate()}, but a container running an older build
 * of {@code infra/postgres/Dockerfile} cannot, because {@link #IMAGE} names the
 * image without a content tag and the reuse hash is computed from a container
 * configuration that does not change when the Dockerfile does. A suite silently
 * running against last week's engine or last week's schema is precisely the
 * class of defect this repository spends its days finding, so the seconds are
 * not worth it.
 */
public final class TestDatabase {

    /**
     * Built from the checked-in Dockerfile rather than pulled, and the file is
     * added to the build context explicitly: withDockerfile alone transferred an
     * empty context here and the build hung with nothing to build.
     */
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile("horecaos/postgres-postgis", false)
            .withFileFromPath("Dockerfile", Path.of("infra/postgres/Dockerfile"));

    /** The role every suite here connects and migrates as. */
    public static final String MIGRATOR_ROLE = "horecaos_migrator";

    /**
     * The NOLOGIN group role the migrations grant every application privilege to.
     *
     * <p>Nothing logs in as this. A privilege probe creates its own LOGIN role,
     * grants this one to it, and connects as that — which is the only way to
     * observe what the application can actually do, and the reason this constant
     * is here rather than spelled out in each probe.
     */
    public static final String APPLICATION_ROLE = "horecaos_application";

    /**
     * The database the container is born with. Nothing migrates it and nothing
     * runs tests in it; it exists because {@code PostgreSQLContainer} needs a
     * database to wait on.
     */
    private static final String BOOTSTRAP_DATABASE = "horecaos_test";

    /**
     * Where {@code CREATE DATABASE} and {@code DROP DATABASE} are issued from.
     *
     * <p>Neither statement may run inside a transaction, and neither may run
     * while the session is connected to the database it names, so administration
     * happens from the cluster's own {@code postgres} database and never from a
     * test's connection.
     */
    private static final String MAINTENANCE_DATABASE = "postgres";

    /** The migrated original every {@link #migrated()} handle is copied from. */
    private static final String TEMPLATE_DATABASE = "horecaos_template";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private TestDatabase() {}

    // ------------------------------------------------------------------
    // The two ways in
    // ------------------------------------------------------------------

    /**
     * A private database already at the latest migration.
     *
     * <p>The clone carries the template's {@code flyway_schema_history}, so a
     * caller that still runs {@code Flyway.migrate()} gets a no-op rather than a
     * surprise. Removing that call is a saving, not a correctness fix.
     */
    public static Handle migrated() {
        String name = freshName();
        String template = Template.NAME;
        create("CREATE DATABASE " + name + " TEMPLATE " + template);
        copyDatabasePrivileges(template, name);
        return new Handle(name);
    }

    /**
     * A private database with nothing in it, for a caller that migrates it itself.
     *
     * <p>For the suites whose subject is a migration — they run to a named
     * version, write rows under the schema as it was, and then advance. Also for
     * the side database a suite creates mid-test for the same reason; those used
     * to spell {@code CREATE DATABASE} inline, and one of them spelled a fixed
     * name, which is a collision waiting for the first shared container.
     */
    public static Handle empty() {
        String name = freshName();
        create("CREATE DATABASE " + name);
        return new Handle(name);
    }

    /**
     * One statement against the cluster, from outside any test's database.
     *
     * <p>For the objects that are not owned by a database and so outlive one: a
     * role, above all. A privilege probe creates a LOGIN role to observe what the
     * application can do, and that role is visible to every other suite in the
     * JVM from the moment it exists — so the probe has to take it away again, and
     * it cannot do that from a connection to a database it has just dropped.
     *
     * <p>Not a general escape hatch. Anything a migration can express belongs in
     * a migration.
     */
    public static void onCluster(String statement) {
        administer(statement);
    }

    /**
     * A handle on one private database.
     *
     * <p>Connection details rather than a container, because the container is
     * shared and a test has no business stopping it. {@link #close()} drops the
     * database; it is safe to skip and safe to call twice.
     */
    public static final class Handle implements AutoCloseable {

        private final String databaseName;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile DataSource dataSource;

        private Handle(String databaseName) {
            this.databaseName = databaseName;
        }

        /** The database's name, for the statements that have to spell it. */
        public String databaseName() {
            return databaseName;
        }

        public String jdbcUrl() {
            return url(databaseName);
        }

        /** Always {@link TestDatabase#MIGRATOR_ROLE} — the owner, and a superuser. */
        public String username() {
            return MIGRATOR_ROLE;
        }

        public String password() {
            return MIGRATOR_ROLE;
        }

        /**
         * A {@code DriverManagerDataSource} as the migration role.
         *
         * <p>The same instance every time. It pools nothing and holds no state,
         * so there is no reason to build a second one, and one instance means one
         * place a debugger can watch.
         */
        private final java.util.List<HikariDataSource> pools = java.util.concurrent.CopyOnWriteArrayList.class.cast(
                new java.util.concurrent.CopyOnWriteArrayList<HikariDataSource>());

        public DataSource dataSource() {
            DataSource existing = dataSource;
            if (existing != null) {
                return existing;
            }
            synchronized (this) {
                if (dataSource == null) {
                    dataSource = dataSourceAs(username(), password());
                }
                return dataSource;
            }
        }

        /**
         * A {@code DataSource} for some other role on this database.
         *
         * <p>For the privilege probes: the owner's connection bypasses every
         * GRANT the migrations write, so anything asserting a privilege has to
         * create a LOGIN role, grant it {@link TestDatabase#APPLICATION_ROLE},
         * and come back in through here.
         */
        public DataSource dataSourceAs(String role, String password) {
            // Pooled, and that is not a performance nicety — it is what makes
            // parallel forks possible at all.
            //
            // DriverManagerDataSource opens a physical TCP connection per
            // statement and closes it again. Sequentially that is merely slow:
            // 1.37 ms a connection measured against this image, and
            // RoleRegistrySynchronizer alone issues about four hundred and fifty
            // statements. Across four forks it is fatal — the suite exhausted the
            // ephemeral port range and 130 tests died on
            // `java.net.BindException: Can't assign requested address`, every one
            // of them a healthy test that could not get a socket.
            //
            // Small on purpose. A class does not need many connections; it needs
            // to stop making new ones. Registered so close() can shut the pool
            // before the database is dropped, because a pool outliving its class
            // is a connection leak that only shows up as the next fork failing.
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl());
            config.setUsername(role);
            config.setPassword(password);
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(0);
            config.setPoolName("horecaos-test-" + databaseName() + "-" + role);
            HikariDataSource pool = new HikariDataSource(config);
            pools.add(pool);
            return pool;
        }

        /**
         * Drops the database. Best effort, and never a reason for a suite to fail.
         *
         * <p>{@code WITH (FORCE)} terminates whatever is still connected — a
         * Hikari pool that outlived the class, a connection a test forgot — which
         * is the difference between this being reliable and this being a
         * coin toss. A failure here costs disk inside a container that is about
         * to be reaped, so it is reported and swallowed.
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // Before the drop, not after: WITH (FORCE) would terminate these
            // anyway, but a pool that learns its connections died out from under
            // it logs a wall of errors on the way out.
            for (HikariDataSource pool : pools) {
                try {
                    pool.close();
                } catch (RuntimeException ignored) {
                    // A pool that cannot close costs a container that is about to be reaped.
                }
            }
            try {
                administer("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            } catch (RuntimeException failure) {
                System.err.println("TestDatabase: could not drop " + databaseName + " (" + failure.getMessage()
                        + "). The container will take it.");
            }
        }
    }

    // ------------------------------------------------------------------
    // The shared container, and the template built on it
    // ------------------------------------------------------------------

    /**
     * Started on first touch, stopped by nothing.
     *
     * <p>A holder class, so the JVM's class-initialization lock does the
     * latching. Two test classes reaching this at once — which they can, and will
     * the moment anyone turns on parallel execution — get one container between
     * them.
     */
    private static final class Container {

        static final PostgreSQLContainer INSTANCE = start();

        private static PostgreSQLContainer start() {
            PostgreSQLContainer container = new PostgreSQLContainer(
                            DockerImageName.parse(IMAGE.get()).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(BOOTSTRAP_DATABASE)
                    .withUsername(MIGRATOR_ROLE)
                    .withPassword(MIGRATOR_ROLE)
                    // Durability buys nothing in a database that is deleted when
                    // the JVM exits, and it is most of the cost of CREATE
                    // DATABASE, which this design performs once per test class.
                    .withCommand(
                            "postgres",
                            "-c",
                            "fsync=off",
                            "-c",
                            "synchronous_commit=off",
                            "-c",
                            "full_page_writes=off");
            container.start();
            return container;
        }
    }

    /**
     * Migrated to LATEST once, then sealed.
     *
     * <p>A template cannot be copied while another session is connected to it, so
     * the last thing this does is take connections away: {@code ALLOW_CONNECTIONS
     * false} stops new ones, and {@code pg_terminate_backend} clears whatever
     * Flyway or a driver left behind. Doing it in that order closes the window in
     * which a connection could be established between the two statements. From
     * then on the template is unopenable, which makes "the template was busy" not
     * a flake this design can suffer.
     */
    private static final class Template {

        static final String NAME = build();

        private static String build() {
            // Touches the holder, so the container is running before anything
            // tries to connect to it.
            Container.INSTANCE.getHost();

            administer("DROP DATABASE IF EXISTS " + TEMPLATE_DATABASE + " WITH (FORCE)");
            administer("CREATE DATABASE " + TEMPLATE_DATABASE);

            DataSource migrating = new DriverManagerDataSource(url(TEMPLATE_DATABASE), MIGRATOR_ROLE, MIGRATOR_ROLE);
            Flyway.configure().dataSource(migrating).load().migrate();

            administer("ALTER DATABASE " + TEMPLATE_DATABASE + " WITH ALLOW_CONNECTIONS false");
            administer("SELECT pg_terminate_backend(pid) FROM pg_stat_activity" + " WHERE datname = '"
                    + TEMPLATE_DATABASE + "' AND pid <> pg_backend_pid()");
            return TEMPLATE_DATABASE;
        }
    }

    // ------------------------------------------------------------------
    // Administration
    // ------------------------------------------------------------------

    private static String url(String database) {
        PostgreSQLContainer container = Container.INSTANCE;
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    /**
     * {@code CREATE DATABASE}, with one retry.
     *
     * <p>The template is sealed against connections, so the "source database is
     * being accessed by other users" refusal should be unreachable. The retry is
     * there because the cost of being wrong about that is a flaky suite and the
     * cost of the retry is nothing.
     */
    private static void create(String statement) {
        try {
            administer(statement);
        } catch (RuntimeException first) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw first;
            }
            administer(statement);
        }
    }

    private static void administer(String statement) {
        try (Connection connection = maintenanceConnection();
                Statement handle = connection.createStatement()) {
            handle.execute(statement);
        } catch (SQLException failure) {
            throw new IllegalStateException("Test database administration failed: " + statement, failure);
        }
    }

    private static Connection maintenanceConnection() throws SQLException {
        return DriverManager.getConnection(url(MAINTENANCE_DATABASE), MIGRATOR_ROLE, MIGRATOR_ROLE);
    }

    /**
     * Carries the template's database-level grants onto a clone.
     *
     * <p>{@code CREATE DATABASE ... TEMPLATE} copies the database's contents, and
     * a database's own ACL is not among them: it lives in {@code pg_database},
     * a shared catalogue, and the new row is created with the default. So V0080's
     * {@code REVOKE TEMPORARY ... FROM PUBLIC} — the migration that leaves a
     * forged catalogue nowhere to live — would be quietly undone by every clone,
     * and {@code DatabasePrivilegeTests} would be asserting it against a database
     * that never had it.
     *
     * <p>This replays whatever the template's ACL says rather than restating that
     * one revoke, so a migration that changes a database-level grant does not
     * also have to change this file.
     */
    private static void copyDatabasePrivileges(String from, String to) {
        List<String> grants = new ArrayList<>();
        try (Connection connection = maintenanceConnection();
                PreparedStatement query = connection.prepareStatement("""
                        SELECT entry.privilege_type,
                               CASE WHEN entry.grantee = 0 THEN 'PUBLIC'
                                    ELSE quote_ident(pg_get_userbyid(entry.grantee)) END AS grantee
                          FROM pg_database source, aclexplode(source.datacl) AS entry
                         WHERE source.datname = ?
                        """)) {
            query.setString(1, from);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    grants.add("GRANT " + rows.getString("privilege_type") + " ON DATABASE " + to + " TO "
                            + rows.getString("grantee"));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read the database privileges of " + from, failure);
        }

        // An empty ACL means the template still has PostgreSQL's default, and the
        // clone already has the same default. Revoking here would make the clone
        // stricter than the thing it is a copy of.
        if (grants.isEmpty()) {
            return;
        }
        administer("REVOKE ALL ON DATABASE " + to + " FROM PUBLIC");
        grants.forEach(TestDatabase::administer);
    }

    /**
     * A unique, valid, and legible identifier.
     *
     * <p>Named for the class that asked, because the alternative is a container
     * full of {@code db_17} and a {@code pg_stat_activity} that cannot be read.
     * The counter is what makes it unique; the name is only there to be read.
     */
    private static String freshName() {
        String caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(type -> type != TestDatabase.class)
                        .map(Class::getSimpleName)
                        .findFirst()
                        .orElse("test"));
        String legible = caller.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        if (legible.length() > 40) {
            legible = legible.substring(0, 40);
        }
        return "horecaos_" + legible + "_" + SEQUENCE.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // The exception
    // ------------------------------------------------------------------

    /**
     * A container of this suite's own — for the one suite that has to take the
     * database away.
     *
     * <p>{@code HealthProbeAndMetricTests} calls {@code stop()} on PostgreSQL part
     * way through its own run, deliberately, to assert that liveness stays 200
     * while the customer health group goes 503 and leaks no back-end name. Handed
     * the shared container it would take the database from every class that had
     * not run yet, and the resulting failures would look nothing like their cause.
     * It is the only caller, and every other suite uses {@link #migrated()} or
     * {@link #empty()}.
     *
     * <p>Not deprecated, because it is not a leftover: it is the escape hatch for
     * a test whose subject is the database being gone.
     */
    public static PostgreSQLContainer container() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGE.get()).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(BOOTSTRAP_DATABASE)
                .withUsername(MIGRATOR_ROLE)
                .withPassword(MIGRATOR_ROLE);
    }
}
