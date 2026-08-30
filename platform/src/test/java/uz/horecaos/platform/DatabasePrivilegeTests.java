package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import javax.sql.DataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.DockerClientFactory;

import uz.horecaos.platform.audit.infrastructure.persistence.AuditPartitionManager;
import uz.horecaos.platform.configuration.DatabasePrivilegeGuard;
import uz.horecaos.platform.reporting.infrastructure.persistence.ReportingPartitionManager;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.TrackRetentionSweeper;

/**
 * What the application role can and cannot do, asserted under that role.
 *
 * <p>This suite exists because the platform had sixty-one migrations' worth of
 * {@code GRANT} and {@code REVOKE} and nothing that ran as the role they name.
 * The application connected as the superuser that owns the database on every
 * laptop and in every test, so each of those statements was bypassed at runtime
 * and every guarantee resting on one was a sentence in a comment. The audit
 * trail's immutability, the column-level grant that stops an approval policy
 * being rewritten, the reporting role's read-only separation: all of them held
 * only in production, where nothing checked them either.
 *
 * <p>The shape here is {@code AuditImmutabilityTests}': the container's login role
 * is the migrator, migrations run as it, and every assertion below is made through
 * a second connection belonging to a login role whose only privilege is
 * {@code horecaos_application}. A test that asserts a REVOKE from the owner's
 * connection asserts nothing at all, so the first test is the one that proves this
 * connection is not the owner's — without it every other test here could pass
 * vacuously.
 */
class DatabasePrivilegeTests {

    private static final String APP_PROBE = "privilege_probe_app";
    private static final String APP_PROBE_PASSWORD = "privilege-probe-app";
    private static final String REPORTING_PROBE = "privilege_probe_reporting";
    private static final String REPORTING_PROBE_PASSWORD = "privilege-probe-reporting";

    private static TestDatabase.Handle db;
    private static DataSource asOwner;
    private static DataSource asApplication;
    private static DataSource asReporting;
    private static JdbcClient owner;
    private static JdbcClient application;
    private static JdbcClient reporting;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the database privilege probes");
        db = TestDatabase.migrated();

        asOwner = db.dataSource();
        owner = JdbcClient.create(asOwner);

        createLoginRole(APP_PROBE, APP_PROBE_PASSWORD, TestDatabase.APPLICATION_ROLE);
        createLoginRole(REPORTING_PROBE, REPORTING_PROBE_PASSWORD, "horecaos_reporting_read");

        asApplication = db.dataSourceAs(APP_PROBE, APP_PROBE_PASSWORD);
        application = JdbcClient.create(asApplication);
        asReporting = db.dataSourceAs(REPORTING_PROBE, REPORTING_PROBE_PASSWORD);
        reporting = JdbcClient.create(asReporting);
    }

    /**
     * The database goes, and then the two roles it created go with it.
     *
     * <p>A role is a property of the cluster rather than of a database, and the
     * cluster is now shared by every suite in the JVM. Two probes that name their
     * login role the same thing would collide — these two do not, and neither
     * does {@code AuditImmutabilityTests}, but a LOGIN role with a known password
     * left standing for the rest of the run is an invitation to write the third
     * one that does. The DROP is deliberately after the database is dropped:
     * {@code GRANT TEMPORARY ON DATABASE} above records a dependency in that
     * database's ACL, and a role with a dependency cannot be dropped.
     */
    @AfterAll
    static void stopDatabase() {
        if (db == null) {
            return;
        }
        db.close();
        for (String probe : new String[] {APP_PROBE, REPORTING_PROBE}) {
            try {
                TestDatabase.onCluster("DROP ROLE IF EXISTS " + probe);
            } catch (RuntimeException leftover) {
                System.err.println("DatabasePrivilegeTests: " + probe + " outlived the suite ("
                        + leftover.getMessage() + ")");
            }
        }
    }

    /**
     * Everything else here is vacuous if this fails.
     *
     * <p>PostgreSQL checks no privilege for a superuser and lets an owner re-grant
     * to itself at will, so a REVOKE asserted from either connection proves
     * nothing. This is the test that says the probe below is a real one.
     */
    @Test
    @DisplayName("the probe connection is neither a superuser nor the database owner")
    void theProbeConnectionHoldsOnlyWhatWasGrantedToIt() {
        Map<String, Object> identity = application.sql("""
                SELECT current_user AS role_name,
                       (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user) AS is_superuser,
                       pg_get_userbyid(d.datdba) = current_user AS owns_database,
                       pg_has_role(current_user, 'pg_read_all_data', 'MEMBER') AS reads_everything,
                       pg_has_role(current_user, 'horecaos_application', 'MEMBER') AS in_application_role
                  FROM pg_database d
                 WHERE d.datname = current_database()
                """).query().singleRow();

        assertThat(identity)
                .containsEntry("role_name", APP_PROBE)
                .containsEntry("is_superuser", false)
                .containsEntry("owns_database", false)
                .containsEntry("reads_everything", false)
                .containsEntry("in_application_role", true);

        assertThat(owner.sql("SELECT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)")
                .query(Boolean.class).single())
                .as("the migration role is the owner, which is exactly why it cannot be the one under test")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // V0007 — the audit trail is evidence (ADR 0027)
    // -----------------------------------------------------------------------

    /**
     * {@code AuditImmutabilityTests} proves this for the parent table. The
     * partitions are where a row physically lives, and a REVOKE on the parent does
     * not reach one that a later migration attached, so each is asserted by name.
     * {@code AuditPartitionManager} creates next year's partition and issues its
     * own GRANT of INSERT and SELECT; anything wider than that would make the
     * parent's REVOKE decorative.
     */
    @Test
    @DisplayName("V0007: no audit partition can be rewritten, deleted from, or truncated")
    void everyAuditPartitionIsAppendOnlyForTheApplication() {
        List<String> tables = owner.sql("""
                SELECT 'audit.' || tablename
                  FROM pg_tables
                 WHERE schemaname = 'audit' AND tablename LIKE 'audit_events%'
                 ORDER BY 1
                """).query(String.class).list();

        assertThat(tables)
                .as("the parent and its partitions must all be present for this probe to mean anything")
                .contains("audit.audit_events", "audit.audit_events_2026", "audit.audit_events_default");

        for (String table : tables) {
            assertThat(privilege(table, "INSERT"))
                    .as("%s must stay writable — evidence that cannot be recorded is not a control", table)
                    .isTrue();
            assertThat(privilege(table, "SELECT"))
                    .as("%s must stay readable", table)
                    .isTrue();
            assertThat(privilege(table, "UPDATE"))
                    .as("%s must not be rewritable by the application (V0007)", table)
                    .isFalse();
            assertThat(privilege(table, "DELETE"))
                    .as("%s must not be deletable by the application (V0007)", table)
                    .isFalse();
            assertThat(privilege(table, "TRUNCATE"))
                    .as("%s must not be truncatable by the application (V0007)", table)
                    .isFalse();
        }

        assertThatThrownBy(() -> application.sql("UPDATE audit.audit_events_2026 SET reason = 'x'").update())
                .as("and the refusal is real, not only a catalogue entry")
                .isInstanceOf(DataAccessException.class);
    }

    // -----------------------------------------------------------------------
    // V0059 — a snapshotted approval policy cannot be rewritten (ADR 0027)
    // -----------------------------------------------------------------------

    /**
     * {@code ApprovalDecisionService} tells an approver that the policy version
     * recorded on a request "cannot be rewritten". That sentence is true because of
     * one column-level grant in V0059 and nothing else — the application may set
     * {@code valid_until} to retire a version and may touch no other column. Until
     * this test the guarantee rested on a connection that ignored the grant.
     */
    @Test
    @DisplayName("V0059: the application may end an approval policy and may not rewrite one")
    void anApprovalPolicyCanBeRetiredButNeverRestated() {
        UUID policyId = UUID.randomUUID();
        owner.sql("""
                INSERT INTO audit.approval_policies (
                    id, tenant_id, action_code, scope_type, threshold_json,
                    required_approver_capability, valid_from, valid_until, version,
                    approved_by, created_at)
                VALUES (:id, NULL, 'refund.execute', 'PLATFORM',
                        '{"description":"Any refund over 1 000 000 UZS"}'::jsonb,
                        'refund.approve', TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, 1,
                        'platform-admin', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """).param("id", policyId).update();

        assertThat(application.sql("""
                UPDATE audit.approval_policies
                   SET valid_until = TIMESTAMPTZ '2026-09-01 00:00:00+00'
                 WHERE id = :id
                """).param("id", policyId).update())
                .as("retiring a version is the one mutation V0059 grants")
                .isEqualTo(1);

        for (String column : List.of(
                "action_code = 'refund.waive'",
                "threshold_json = '{\"description\":\"Any refund at all\"}'::jsonb",
                "required_approver_capability = 'refund.execute'",
                "valid_from = TIMESTAMPTZ '2020-01-01 00:00:00+00'",
                "approved_by = 'somebody-else'",
                "version = 99")) {
            assertThatThrownBy(() -> application
                    .sql("UPDATE audit.approval_policies SET " + column + " WHERE id = :id")
                    .param("id", policyId).update())
                    .as("a snapshotted policy's terms must not be rewritable: %s", column)
                    .isInstanceOf(DataAccessException.class);
        }

        assertThatThrownBy(() -> application
                .sql("DELETE FROM audit.approval_policies WHERE id = :id").param("id", policyId).update())
                .as("nor may a version be made to have never existed")
                .isInstanceOf(DataAccessException.class);

        assertThat(privilege("audit.approval_policies", "INSERT"))
                .as("publishing the next version is how a policy changes (V0059)")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // V0031 — the reporting role reads and never writes (ADR 0023, ADR 0043)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("V0031: the reporting role can read every fact and write none of them")
    void theReportingRoleIsSeparatedByGrantAndNotByConvention() {
        List<String> tables = owner.sql("""
                SELECT 'reporting.' || tablename FROM pg_tables
                 WHERE schemaname = 'reporting' ORDER BY 1
                """).query(String.class).list();

        assertThat(tables).as("V0031 must have run for this probe to mean anything").isNotEmpty();

        for (String table : tables) {
            assertThat(privilege(REPORTING_PROBE, table, "SELECT"))
                    .as("%s must be readable by the reporting role", table)
                    .isTrue();
            for (String write : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(privilege(REPORTING_PROBE, table, write))
                        .as("the reporting role must not be able to %s %s", write, table)
                        .isFalse();
            }
        }

        assertThatThrownBy(() -> reporting
                .sql("DELETE FROM reporting.fact_order").update())
                .isInstanceOf(DataAccessException.class);

        assertThat(privilege(REPORTING_PROBE, "ordering.orders", "SELECT"))
                .as("the reporting role reads the reporting schema, not the operational one")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // The schema is the migration role's, and only the migration role's
    // -----------------------------------------------------------------------

    /**
     * The separation item 2 of the fix asks for, asserted rather than assumed.
     * Flyway creates tables, grants and indexes; if the application role can do any
     * of that, then running migrations under a separate role buys nothing, and SQL
     * injection through the application's pool stops being a read problem.
     */
    @Test
    @DisplayName("the application role cannot change the schema in any way")
    void theApplicationRoleHoldsNoDdl() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("create a table", "CREATE TABLE audit.probe_should_not_exist (id uuid PRIMARY KEY)");
        refused.put("create a schema", "CREATE SCHEMA probe_should_not_exist");
        refused.put("drop a table", "DROP TABLE audit.approval_requests");
        refused.put("add a column", "ALTER TABLE audit.approval_requests ADD COLUMN probe text");
        refused.put("build an index", "CREATE INDEX probe_idx ON audit.approval_requests (id)");
        refused.put("create a role", "CREATE ROLE probe_should_not_exist");

        refused.forEach((what, sql) -> assertThatThrownBy(() -> application.sql(sql).update())
                .as("the application role must not be able to %s", what)
                .isInstanceOf(DataAccessException.class));

        // Widening its own grant is the one attempt that does not raise. PostgreSQL
        // answers a GRANT from a role holding no grant option with a WARNING and
        // grants nothing, so the statement "succeeds" and the privilege does not
        // move. Asserted on the privilege rather than on an exception, because
        // asserting on the exception is how this case would be written wrongly and
        // then read as covered.
        application.sql("GRANT UPDATE ON audit.audit_events TO horecaos_application").update();
        assertThat(privilege("audit.audit_events", "UPDATE"))
                .as("a GRANT issued by a role with no grant option must move nothing")
                .isFalse();

        assertThat(owner.sql("""
                SELECT count(*) FROM pg_tables
                 WHERE tablename = 'probe_should_not_exist'
                """).query(Long.class).single()).isZero();
    }

    // -----------------------------------------------------------------------
    // V0075 — the two scheduled jobs that still did DDL
    // -----------------------------------------------------------------------

    /**
     * The regression, run as the role production runs as.
     *
     * <p>Both of these ship {@code @Scheduled} and enabled by default, and after
     * the switch to {@code horecaos_application} neither could complete a single
     * pass: the {@code CREATE TABLE ... PARTITION OF} was {@code permission denied
     * for schema}, and the self-issued {@code GRANT} that followed it did not even
     * raise — PostgreSQL answers a grant from a role with no grant option with a
     * warning and moves nothing. So the last assertion in each half is the one
     * that matters most. It is on the privilege, not on the absence of an
     * exception, because the absence of an exception is exactly what the bug
     * looked like.
     */
    @Test
    @DisplayName("V0075: the audit and telemetry partition jobs complete under the application role")
    void theScheduledMaintenanceJobsRunUnderTheApplicationRole() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"), ZoneOffset.UTC);

        // --- ADR 0027. A year past everything the migrations provisioned.
        AuditPartitionManager audit = new AuditPartitionManager(application, clock);

        assertThat(audit.ensurePartition(2087))
                .as("the application must be able to add an audit partition without holding DDL")
                .isTrue();
        assertThat(audit.ensurePartition(2087))
                .as("and a second pass must be a no-op rather than a failure")
                .isFalse();
        assertThat(privilege("audit.audit_events_2087", "INSERT"))
                .as("the grant the job used to issue itself must actually have moved")
                .isTrue();
        assertThat(privilege("audit.audit_events_2087", "SELECT")).isTrue();
        assertThat(privilege("audit.audit_events_2087", "UPDATE"))
                .as("and must not have widened past what V0007 revokes on the parent")
                .isFalse();
        assertThat(privilege("audit.audit_events_2087", "DELETE")).isFalse();
        assertThat(privilege("audit.audit_events_2087", "TRUNCATE")).isFalse();

        assertThatCode(audit::ensurePartitions)
                .as("the whole daily pass, not only the one call")
                .doesNotThrowAnyException();

        // --- ADR 0045. The full hourly sweep: provision, expire pins, drop
        // expired partitions. It threw on its first statement, which is why the
        // two after it stopped running at all.
        TrackRetentionSweeper tracks = new TrackRetentionSweeper(
                application, new JdbcTelemetryStore(application), clock, false, 30);

        LocalDate wellAhead = databaseToday().plusDays(400);
        assertThat(tracks.ensurePartition(wellAhead)).isTrue();
        assertThat(tracks.ensurePartition(wellAhead)).isFalse();
        assertThat(privilege("fulfillment." + trackPartition(wellAhead), "INSERT"))
                .as("a track partition the application cannot insert into is not a partition")
                .isTrue();
        assertThat(privilege("fulfillment." + trackPartition(wellAhead), "SELECT")).isTrue();
        assertThat(privilege("fulfillment." + trackPartition(wellAhead), "UPDATE")).isFalse();

        assertThatCode(tracks::sweep)
                .as("ensurePartitions, expireLivePositions and dropExpiredPartitions in one pass")
                .doesNotThrowAnyException();

        // --- ADR 0043. The job V0070 did fix, which had no probe of its own: its
        // function was made SECURITY DEFINER and nothing ran it as the role the
        // change was for, so the same class of gap could have reopened silently.
        ReportingPartitionManager facts = new ReportingPartitionManager(application, clock);
        assertThatCode(() -> facts.ensurePartitionsFor(LocalDate.of(2087, 3, 1)))
                .doesNotThrowAnyException();
        assertThat(privilege("reporting.fact_order_208703", "INSERT")).isTrue();
        assertThat(privilege(REPORTING_PROBE, "reporting.fact_order_208703", "SELECT")).isTrue();
        assertThat(privilege(REPORTING_PROBE, "reporting.fact_order_208703", "INSERT"))
                .as("and the reporting role's read-only separation reaches a partition made at "
                        + "runtime, not only the ones a migration made")
                .isFalse();
    }

    /**
     * The sweep is the one thing here that destroys personal data on purpose, so
     * what it refuses is the interesting half.
     *
     * <p>Two properties, and both are structural rather than validated. The
     * function takes no table name, so there is nothing to point at another table;
     * and the retention argument is one term of a {@code GREATEST} with the ADR
     * 0045 floor, so a caller can lengthen the window and can never shorten it. A
     * compromised application asking for a sweep with a retention of zero gets the
     * same sweep a healthy one gets.
     */
    @Test
    @DisplayName("V0075: the retention sweep drops only what is expired, and takes no table name")
    void theRetentionSweepCannotBeTalkedIntoDroppingSomethingElse() {
        TrackRetentionSweeper tracks = new TrackRetentionSweeper(
                application, new JdbcTelemetryStore(application),
                Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"), ZoneOffset.UTC), false, 30);

        // The database's clock, not a fixture's — which is the point. This job's
        // window is deliberately not movable by the Clock injected above.
        LocalDate today = databaseToday();
        LocalDate expired = today.minusDays(400);
        LocalDate young = today.minusDays(5);
        tracks.ensurePartition(expired);
        tracks.ensurePartition(young);

        assertThat(sweep(0))
                .as("a caller asking for a retention of nothing must still not shorten the window")
                .contains(trackPartition(expired))
                .doesNotContain(trackPartition(young), "courier_location_tracks_default");
        assertThat(sweep(-100_000))
                .as("nor a negative one")
                .doesNotContain(trackPartition(young));

        assertThatThrownBy(() -> application
                .sql("SELECT fulfillment.sweep_expired_track_partitions('fulfillment.courier_track_summaries', 30)")
                .query(String.class).list())
                .as("there is no table-name parameter to abuse, so naming one is a type error")
                .isInstanceOf(DataAccessException.class);

        // A partition whose NAME says 2020 and whose BOUND says next year. Expiry
        // is read from the bound, so this is not expired and a name cannot lie its
        // way into a drop.
        owner.sql("""
                CREATE TABLE fulfillment.courier_location_tracks_20200101
                    PARTITION OF fulfillment.courier_location_tracks
                    FOR VALUES FROM ('2029-03-01 00:00:00+00') TO ('2029-03-02 00:00:00+00')
                """).update();
        assertThat(sweep(30))
                .as("a partition is expired when its own bound is old, not when its name looks old")
                .doesNotContain("courier_location_tracks_20200101");

        assertThat(tableExists("fulfillment", "courier_location_tracks_default"))
                .as("the default has no upper bound to be older than the cutoff, so it survives")
                .isTrue();
        assertThat(tableExists("fulfillment", "courier_track_summaries"))
                .as("and nothing outside the partitioned parent was ever reachable")
                .isTrue();
        assertThat(tableExists("fulfillment", trackPartition(young))).isTrue();
        assertThat(tableExists("fulfillment", trackPartition(expired)))
                .as("what was genuinely expired is gone")
                .isFalse();
    }

    /**
     * The three properties that make a {@code SECURITY DEFINER} function a narrow
     * grant rather than a back door, asserted for every one the application may
     * call. V0070 wrote them into one function's DDL; nothing checked that the
     * next one would be written the same way.
     */
    @Test
    @DisplayName("every function the application may call is SECURITY DEFINER, pinned, and not PUBLIC")
    void theMaintenanceFunctionsAreNarrowGrantsAndNotBackDoors() {
        List<String> functions = List.of(
                "audit.ensure_event_partition(integer)",
                "fulfillment.ensure_track_partition(date)",
                "fulfillment.sweep_expired_track_partitions(integer, boolean)",
                "reporting.ensure_fact_partition(text, date)");

        for (String function : functions) {
            Map<String, Object> shape = owner.sql("""
                    SELECT p.prosecdef AS definer,
                           pg_get_userbyid(p.proowner) AS owner_role,
                           coalesce(array_to_string(p.proconfig, ','), '') AS settings,
                           has_function_privilege('horecaos_application', :function, 'EXECUTE') AS app_may_call,
                           has_function_privilege('public', :function, 'EXECUTE') AS public_may_call
                      FROM pg_proc p
                     WHERE p.oid = :function::regprocedure
                    """).param("function", function).query().singleRow();

            assertThat(shape)
                    .as("%s must run as its owner; SECURITY INVOKER makes the EXECUTE grant "
                            + "buy the right to call a function that cannot do its job", function)
                    .containsEntry("definer", true);
            assertThat(shape)
                    .as("%s must be owned by the migration role", function)
                    .containsEntry("owner_role", TestDatabase.MIGRATOR_ROLE);
            assertThat((String) shape.get("settings"))
                    .as("%s must pin search_path, or an unqualified name inside it is the "
                            + "caller's to bend", function)
                    .contains("search_path=pg_catalog");
            assertThat(shape)
                    .as("%s is granted to the application by name", function)
                    .containsEntry("app_may_call", true);
            assertThat(shape)
                    .as("%s must not be callable by PUBLIC — that is the default on a new "
                            + "function and it is a privilege escalation waiting for a role "
                            + "nobody thought about", function)
                    .containsEntry("public_may_call", false);
        }
    }

    /**
     * The property the four above were all missing, asserted over whatever the
     * catalogue holds rather than over a list.
     *
     * <p>The test above names its four functions, which is why V0070's and V0075's
     * shared defect could sit under a green suite: it checked the declaration each
     * of them had, not the one none of them had. {@code search_path = pg_catalog}
     * reads like a pin and is not one. PostgreSQL searches the session's temporary
     * schema BEFORE every entry in {@code search_path} — {@code pg_catalog}
     * included — for relation and type names, unless {@code pg_temp} appears in the
     * path explicitly, which moves it to wherever it is written. So a caller
     * holding {@code TEMPORARY} could create a table called {@code pg_class} and
     * that table, not the catalogue, is what the function read. All four read the
     * catalogue through unqualified names; the sweep read it to decide what to
     * drop.
     *
     * <p>Three assertions per function, and the list of functions comes from
     * {@code pg_proc}, so a fifth one written next year is held to them without
     * anybody remembering to add it here:
     *
     * <ul>
     *   <li>{@code pg_temp} is named LAST in {@code search_path} — first would be
     *       the same hazard spelled out loud.
     *   <li>every catalogue relation the body reads is {@code pg_catalog}-qualified
     *       anyway, so one mistyped declaration is not the whole defence.
     *   <li>owned by the migration role and not callable by PUBLIC, which is what
     *       makes it a narrow grant rather than a back door.
     * </ul>
     */
    @Test
    @DisplayName("V0080: every SECURITY DEFINER function names pg_temp last and qualifies its "
            + "catalogue reads")
    void noSecurityDefinerFunctionCanBeRedirectedThroughTheTemporarySchema() {
        List<Map<String, Object>> definers = owner.sql("""
                SELECT p.oid::regprocedure::text AS signature,
                       pg_get_userbyid(p.proowner) AS owner_role,
                       coalesce(array_to_string(p.proconfig, ','), '') AS settings,
                       has_function_privilege('public', p.oid, 'EXECUTE') AS public_may_call,
                       p.prosrc AS body
                  FROM pg_proc p
                 WHERE p.prosecdef
                   AND NOT EXISTS (SELECT 1 FROM pg_depend d
                                    WHERE d.objid = p.oid
                                      AND d.classid = 'pg_proc'::regclass
                                      AND d.deptype = 'e')
                 ORDER BY 1
                """).query().listOfRows();

        assertThat(definers)
                .as("no SECURITY DEFINER function found at all, which means this probe is "
                        + "broken rather than satisfied")
                .isNotEmpty();
        assertThat(definers.stream().map(row -> (String) row.get("signature")))
                .as("the four the application may call must be among them")
                .contains("audit.ensure_event_partition(integer)",
                        "fulfillment.ensure_track_partition(date)",
                        "fulfillment.sweep_expired_track_partitions(integer,boolean)",
                        "reporting.ensure_fact_partition(text,date)");

        for (Map<String, Object> function : definers) {
            String signature = (String) function.get("signature");
            String settings = (String) function.get("settings");

            assertThat(function)
                    .as("%s must be owned by the migration role", signature)
                    .containsEntry("owner_role", TestDatabase.MIGRATOR_ROLE);
            assertThat(function)
                    .as("%s must not be callable by PUBLIC", signature)
                    .containsEntry("public_may_call", false);

            Matcher path = SEARCH_PATH.matcher(settings);
            assertThat(path.find())
                    .as("%s must pin search_path; without one it inherits the caller's, and "
                            + "SECURITY DEFINER makes that the caller's choice of what this "
                            + "function reads (settings were %s)", signature, settings)
                    .isTrue();
            List<String> entries = Stream.of(path.group(1).split(","))
                    .map(String::trim).filter(entry -> !entry.isEmpty()).toList();
            assertThat(entries)
                    .as("""
                            %s must name pg_temp LAST in search_path. Omitting it does not \
                            leave the temporary schema out — PostgreSQL then searches it \
                            FIRST, ahead of pg_catalog, for every relation and type name in \
                            the body. That is not a theoretical ordering: with pg_temp \
                            unnamed, a caller holding TEMPORARY created a table called \
                            pg_class carrying today's partition name and an expired \
                            partition's bound, and fulfillment.sweep_expired_track_partitions \
                            dropped a live day of ADR 0029 courier tracks. Writing pg_temp \
                            last is the documented remedy (V0080).""", signature)
                    .isNotEmpty()
                    .last().isEqualTo("pg_temp");

            assertThat(unqualifiedCatalogReads((String) function.get("body")))
                    .as("""
                            %s reads a catalogue relation through an unqualified name. The \
                            search_path pin above is supposed to make that safe and it is the \
                            declaration that was wrong in all four functions at once, so the \
                            body does not get to depend on it: write pg_catalog.pg_class, \
                            pg_catalog.pg_inherits, pg_catalog.pg_namespace (V0080).""",
                            signature)
                    .isEmpty();
        }
    }

    /**
     * The attack itself, driven the way it was first driven, and refused twice.
     *
     * <p>Two independent stops, asserted independently, because either alone is one
     * declaration away from being the whole defence again:
     *
     * <ol>
     *   <li>the application cannot create a temporary table at all — V0080 revokes
     *       {@code TEMPORARY} from PUBLIC, which is the default grant nothing in
     *       this repository had ever taken back;
     *   <li>and with that privilege handed back for the length of this test, the
     *       forgery no longer redirects anything, because the functions name
     *       {@code pg_temp} last and read {@code pg_catalog.pg_class} by name.
     * </ol>
     *
     * <p>The second half is the one that matters. It proves the fix rather than the
     * absence of the opportunity, and it is the half that still holds the day
     * somebody grants a login role {@code TEMPORARY} for a reason that looks good.
     */
    @Test
    @DisplayName("V0080: a forged pg_class cannot make the retention sweep drop a live partition")
    void theRetentionSweepCannotBeRedirectedThroughAForgedCatalogue() {
        assertThatThrownBy(() -> application.sql("CREATE TEMP TABLE forgery (oid oid)").update())
                .as("the application role has no business creating temporary tables, and "
                        + "TEMPORARY is granted to PUBLIC by default until a migration says "
                        + "otherwise (V0080)")
                .isInstanceOf(DataAccessException.class)
                // On the refusal itself rather than on the word. Spring translates
                // this SQLSTATE to BadSqlGrammarException, so the wrapper says "bad
                // SQL grammar" and only the cause says why; asserting the wrapper's
                // wording would pass just as happily if the statement had failed for
                // a syntax error, which is the opposite of what this proves.
                .rootCause()
                .hasMessageContaining("permission denied");

        LocalDate today = databaseToday();
        LocalDate expired = today.minusDays(500);
        new TrackRetentionSweeper(application, new JdbcTelemetryStore(application),
                Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"), ZoneOffset.UTC), false, 30)
                .ensurePartition(today);
        application.sql("SELECT fulfillment.ensure_track_partition(:day)")
                .param("day", expired).query(Boolean.class).single();
        assertThat(tableExists("fulfillment", trackPartition(today))).isTrue();
        assertThat(tableExists("fulfillment", trackPartition(expired))).isTrue();

        owner.sql("GRANT TEMPORARY ON DATABASE " + db.databaseName()
                + " TO " + APP_PROBE).update();
        List<String> swept = new ArrayList<>();
        try (Connection session = asApplication.getConnection();
                Statement statement = session.createStatement()) {
            // One connection for the whole forgery, because a temporary table lives
            // in the session that made it. Everything else here goes through
            // JdbcClient over a DriverManagerDataSource, which opens a connection
            // per statement — an attack written that way would create its pg_class
            // in a session that ends before the sweep runs and would pass for the
            // wrong reason.
            //
            // The forgery as it was reproduced: one row carrying TODAY's relation
            // name and an EXPIRED partition's oid and bound, with pg_inherits and
            // pg_namespace shadowed to match so the sweep's own WHERE clause is
            // satisfied by tables the caller owns.
            statement.execute("""
                    CREATE TEMP TABLE pg_class AS
                    SELECT old.oid,
                           'courier_location_tracks_' || to_char(current_date, 'YYYYMMDD') AS relname,
                           old.relpartbound,
                           old.relnamespace
                      FROM pg_catalog.pg_class old
                      JOIN pg_catalog.pg_namespace n ON n.oid = old.relnamespace
                     WHERE n.nspname = 'fulfillment' AND old.relname = '%s'
                    UNION ALL
                    SELECT p.oid, p.relname, p.relpartbound, p.relnamespace
                      FROM pg_catalog.pg_class p
                      JOIN pg_catalog.pg_namespace n ON n.oid = p.relnamespace
                     WHERE n.nspname = 'fulfillment' AND p.relname = 'courier_location_tracks'
                    """.formatted(trackPartition(expired)));
            statement.execute("""
                    CREATE TEMP TABLE pg_inherits AS
                    SELECT (SELECT oid FROM pg_temp.pg_class
                             WHERE relname <> 'courier_location_tracks') AS inhrelid,
                           (SELECT oid FROM pg_temp.pg_class
                             WHERE relname = 'courier_location_tracks') AS inhparent
                    """);
            statement.execute("""
                    CREATE TEMP TABLE pg_namespace AS
                    SELECT (SELECT relnamespace FROM pg_temp.pg_class
                             WHERE relname = 'courier_location_tracks') AS oid,
                           'fulfillment'::name AS nspname
                    """);

            try (ResultSet dropped = statement.executeQuery(
                    "SELECT fulfillment.sweep_expired_track_partitions(30, false)")) {
                while (dropped.next()) {
                    swept.add(dropped.getString(1));
                }
            }
        } catch (SQLException refused) {
            throw new IllegalStateException("the forgery could not be driven at all", refused);
        } finally {
            owner.sql("REVOKE TEMPORARY ON DATABASE " + db.databaseName()
                    + " FROM " + APP_PROBE).update();
        }

        assertThat(swept)
                .as("""
                        The sweep must read the real catalogue. Before V0080 this call \
                        answered with TODAY's partition name and dropped it — a live day of \
                        courier GPS tracks — because the forged pg_class above was searched \
                        ahead of pg_catalog, while the partition that was genuinely expired \
                        went untouched.""")
                .contains(trackPartition(expired))
                .doesNotContain(trackPartition(today));

        assertThat(tableExists("fulfillment", trackPartition(today)))
                .as("today's tracks are still there")
                .isTrue();
        assertThat(tableExists("fulfillment", trackPartition(expired)))
                .as("and the sweep did the job it was asked for while refusing the one it "
                        + "was pointed at")
                .isFalse();
    }

    /**
     * The regression the role switch caused on its own, with no attacker involved.
     *
     * <p>{@code JdbcTelemetryStore.upsertTrackWindow} is ADR 0045's ingest: every
     * courier's position, folded into one row a minute, idempotent on the window
     * through {@code ON CONFLICT ... DO UPDATE}. V0041 granted {@code SELECT,
     * INSERT} on the table and no UPDATE, which was right for a table nothing
     * rewrites and wrong for the statement the code issues. The privilege is
     * checked when the statement is planned, so under {@code horecaos_application}
     * this failed on every call and not only on a conflicting one — field
     * telemetry refused outright, from the first minute of the first shift.
     *
     * <p>Both halves are exercised: the insert that finds no conflict, and the one
     * that finds its own row and replaces it.
     */
    @Test
    @DisplayName("V0080: the telemetry upsert writes and replaces a track window under the "
            + "application role")
    void theTrackUpsertCanActuallyUpdateOnConflict() {
        JdbcTelemetryStore store = new JdbcTelemetryStore(application);
        LocalDate today = databaseToday();
        new TrackRetentionSweeper(application, store,
                Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"), ZoneOffset.UTC), false, 30)
                .ensurePartition(today);

        UUID tenantId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant windowStart = today.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(36_000);

        assertThat(store.upsertTrackWindow(new JdbcTelemetryStore.TrackWindowRow(
                UUID.randomUUID(), tenantId, courierId, sessionId,
                windowStart, windowStart.plusSeconds(60), "u9ded", "u9ded",
                6, 120, "ciphertext-v1", Instant.parse("2026-08-26T10:01:00Z"))))
                .as("""
                        An ON CONFLICT ... DO UPDATE needs the UPDATE privilege on its target \
                        whether or not a row ever conflicts, and V0041 granted SELECT and \
                        INSERT only. Under the owner connection this passed; under \
                        horecaos_application it was 'permission denied for table \
                        courier_location_tracks' on every telemetry write there has ever \
                        been (V0080).""")
                .isTrue();

        assertThat(store.upsertTrackWindow(new JdbcTelemetryStore.TrackWindowRow(
                UUID.randomUUID(), tenantId, courierId, sessionId,
                windowStart, windowStart.plusSeconds(60), "u9ded", "u9dej",
                9, 210, "ciphertext-v2", Instant.parse("2026-08-26T10:01:30Z"))))
                .as("and the conflicting write — a later batch completing a minute an earlier "
                        + "one only started — must replace it")
                .isTrue();

        assertThat(store.trackWindows(tenantId, courierId, windowStart, windowStart.plusSeconds(120)))
                .singleElement()
                .satisfies(window -> {
                    assertThat(window.observationCount()).isEqualTo(9);
                    assertThat(window.geohash5Last()).isEqualTo("u9dej");
                });

        assertThat(privilege("fulfillment.courier_location_tracks", "UPDATE"))
                .as("granted on the partitioned parent, which is where PostgreSQL checks a "
                        + "write routed through it")
                .isTrue();
        assertThat(privilege("fulfillment." + trackPartition(today), "UPDATE"))
                .as("and not on the daily partitions, so a statement naming one directly "
                        + "still cannot rewrite a track")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Every privilege the application's own SQL relies on
    // -----------------------------------------------------------------------

    /**
     * The systematic half, and the one that stops this reopening.
     *
     * <p>{@code infra/production/audit-grants.sql} asks whether the application can
     * read every table that exists, which catches a migration that forgot its GRANT
     * block entirely. It cannot catch a table the application writes to and was
     * granted only SELECT on — which is how {@code marketing.customer_metrics}
     * reached V0069 with an ADR 0029 erasure path and no DELETE, invisible for as
     * long as the connection was the owner's.
     *
     * <p>So the statements are read from the source. Every {@code INSERT INTO},
     * {@code UPDATE}, {@code DELETE FROM}, {@code TRUNCATE}, {@code FROM} and
     * {@code JOIN} naming a schema-qualified table in {@code src/main/java} becomes
     * one privilege this role has to hold. Names that resolve to nothing — a
     * function, a CTE, a table a later migration will add — are skipped, because
     * this is a privilege check and not a schema check, and
     * {@code audit-grants.sql} already covers the other direction.
     */
    @Test
    @DisplayName("every table the application's SQL touches is granted to the application role")
    void theGrantsCoverWhatTheCodeActuallyDoes() {
        Map<Requirement, String> required = requiredPrivileges();
        assertThat(required)
                .as("the scan found no SQL at all, which means it is broken rather than satisfied")
                .hasSizeGreaterThan(100);

        List<String> gaps = new ArrayList<>();
        required.forEach((requirement, source) -> {
            Boolean granted = owner.sql("""
                    SELECT CASE
                             WHEN to_regclass(:object) IS NULL THEN TRUE
                             WHEN has_table_privilege('horecaos_application', :object, :privilege) THEN TRUE
                             ELSE EXISTS (
                                 SELECT 1 FROM information_schema.column_privileges cp
                                  WHERE cp.grantee = 'horecaos_application'
                                    AND cp.table_schema || '.' || cp.table_name = :object
                                    AND cp.privilege_type = :privilege)
                           END
                    """)
                    .param("object", requirement.object())
                    .param("privilege", requirement.privilege())
                    .query(Boolean.class).single();
            if (!Boolean.TRUE.equals(granted)) {
                gaps.add("%s needs %s (%s)".formatted(requirement.object(), requirement.privilege(), source));
            }
        });

        assertThat(gaps)
                .as("""
                        The application's SQL uses a privilege no migration granted. Under the owner \
                        connection this succeeds and nobody notices; under horecaos_app it is \
                        'permission denied', at whatever hour the code path first runs. The fix is a \
                        GRANT in a forward migration, never a statement typed on the server — grants \
                        live with the objects, and the next restore drops anything typed by hand.""")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Every privilege the application's SQL does NOT hold and must not need
    // -----------------------------------------------------------------------

    /**
     * The other half of the systematic check, and the half whose absence let this
     * happen twice.
     *
     * <p>{@link #theGrantsCoverWhatTheCodeActuallyDoes} asks whether every table
     * the code reads and writes is granted. It cannot catch a statement that no
     * {@code GRANT} could ever satisfy, because {@code horecaos_application} holds no
     * DDL on anything and is never going to: a {@code CREATE TABLE}, a {@code DROP
     * TABLE}, a {@code TRUNCATE} or a {@code GRANT} in application code is not a
     * missing privilege, it is code in the wrong place. V0070 found one such job
     * by reading; two more shipped enabled by default and were found by a skeptic
     * with a real PostgreSQL. Reading is not a control.
     *
     * <p>So the rule is flat: {@code src/main/java} issues no schema change. If a
     * code path genuinely needs one, it goes behind a {@code SECURITY DEFINER}
     * function owned by the migration role — V0075's three and V0070's one are the
     * worked examples — and the Java calls the function.
     */
    @Test
    @DisplayName("no statement in src/main/java needs a privilege the application role cannot hold")
    void theApplicationIssuesNoSchemaChangeOfItsOwn() {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            sources.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> ddlViolations(read(path))
                            .forEach(found -> violations.add(path + ": " + found)));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }

        assertThat(violations)
                .as("""
                        Application code is issuing a statement the application role cannot run. \
                        horecaos_application holds USAGE and no CREATE on every schema, no DDL of any \
                        kind, and no grant option, so this is not fixed by a GRANT: a CREATE or a \
                        DROP raises 'permission denied', and a GRANT does not raise at all — \
                        PostgreSQL answers it with 'WARNING: no privileges were granted', moves \
                        nothing, and returns success, which is how one of these ran unnoticed on a \
                        scheduled thread. Put the statement in a migration, or behind a SECURITY \
                        DEFINER function owned by horecaos_migrator whose identifiers come from an \
                        allowlist or a formatted date and never from a caller's string \
                        (V0075, V0070).""")
                .isEmpty();
    }

    /**
     * The scanner above, held to the five statements that caused this.
     *
     * <p>A source scan that silently stops matching is worse than no scan, because
     * the suite goes on being green. These are the exact lines that were shipping,
     * plus the statements the application legitimately does issue, so a pattern
     * that decays in either direction fails here rather than in production.
     */
    @Test
    @DisplayName("the schema-change scan recognises the statements that caused this")
    void theScanFailsOnAReintroducedViolation() {
        assertThat(ddlViolations("""
                jdbc.sql(""\"
                        CREATE TABLE IF NOT EXISTS audit.%s PARTITION OF audit.audit_events
                            FOR VALUES FROM ('%d-01-01 00:00:00+00') TO ('%d-01-01 00:00:00+00')
                        ""\".formatted(table, year, year + 1)).update();
                """)).isNotEmpty();
        assertThat(ddlViolations(
                "jdbc.sql(\"GRANT INSERT, SELECT ON audit.%s TO horecaos_application\").update();"))
                .isNotEmpty();
        assertThat(ddlViolations("""
                jdbc.sql(""\"
                        CREATE TABLE fulfillment.%s PARTITION OF fulfillment.courier_location_tracks
                        ""\").update();
                """)).isNotEmpty();
        assertThat(ddlViolations(
                "jdbc.sql(\"GRANT SELECT, INSERT ON fulfillment.%s TO horecaos_application\")"))
                .isNotEmpty();
        assertThat(ddlViolations(
                "jdbc.sql(\"DROP TABLE fulfillment.%s\".formatted(table)).update();"))
                .isNotEmpty();

        for (String alsoRefused : List.of(
                "TRUNCATE TABLE audit.audit_events",
                "ALTER TABLE ordering.orders ADD COLUMN probe text",
                "CREATE INDEX ix_probe ON ordering.orders (id)",
                "CREATE SCHEMA probe",
                "CREATE ROLE probe",
                "SET ROLE horecaos_migrator",
                "REVOKE SELECT ON ordering.orders FROM horecaos_application",
                "REFRESH MATERIALIZED VIEW reporting.agg_branch_day",
                "LOCK TABLE ordering.orders IN ACCESS EXCLUSIVE MODE")) {
            assertThat(ddlViolations("jdbc.sql(\"" + alsoRefused + "\")"))
                    .as("%s needs a privilege the application role does not hold", alsoRefused)
                    .isNotEmpty();
        }

        // And the statements the application does issue, including the prose that
        // surrounds them. A scan that flags these is a scan somebody will disable.
        for (String allowed : List.of(
                "jdbc.sql(\"SELECT audit.ensure_event_partition(:year)\")",
                "jdbc.sql(\"SELECT fulfillment.sweep_expired_track_partitions(:days, :reportOnly)\")",
                "jdbc.sql(\"SELECT * FROM ordering.orders WHERE tenant_id = :tenantId\")",
                "jdbc.sql(\"DELETE FROM marketing.customer_metrics WHERE customer_id = :id\")",
                "jdbc.sql(\"UPDATE audit.approval_policies SET valid_until = :until\")",
                "@Operation(summary = \"Grant a role at a scope\")",
                "@Operation(summary = \"Revoke a grant\")",
                "// Every GRANT and REVOKE the migrations write is bypassed on that connection",
                "// A withdrawal supersedes a grant without erasing it",
                "String message = \"...will fail with 'permission denied'. Grant it: GRANT %s TO"
                        + " <the login role>.\";")) {
            assertThat(ddlViolations(allowed))
                    .as("%s is not a schema change and must not be flagged", allowed)
                    .isEmpty();
        }
    }

    /**
     * The privilege scan, held to the statements that hide from it.
     *
     * <p>{@link #theGrantsCoverWhatTheCodeActuallyDoes} walked every
     * schema-qualified table in {@code src/main/java} and pronounced the grants
     * sufficient while telemetry ingest was being refused on every call. It missed
     * it because it reads the verb at the head of a statement, and the head of an
     * upsert says INSERT. That is a whole family, not one case: a statement can
     * need UPDATE without the word appearing in it, and it can need SELECT on a
     * table its verb never names.
     *
     * <p>So the three rules are exercised against exact statements — the one that
     * shipped, and the near-misses that a rule written too loosely would sweep up
     * with it. The first assertion is the mutation: the same source, run through
     * the head-verb rules alone, yields INSERT and not UPDATE, which is the scan as
     * it was on the morning telemetry stopped working.
     */
    @Test
    @DisplayName("the privilege scan recognises the privileges a statement does not spell")
    void theScanRecognisesThePrivilegesAStatementDoesNotSpell() {
        String upsert = """
                jdbc.sql(""\"
                        INSERT INTO fulfillment.courier_location_tracks (
                            id, tenant_id, courier_id, window_start, observation_count)
                        VALUES (:id, :tenantId, :courierId, :windowStart, :count)
                        ON CONFLICT (window_start, tenant_id, courier_id) DO UPDATE
                           SET observation_count = excluded.observation_count
                         WHERE excluded.observation_count
                               > fulfillment.courier_location_tracks.observation_count
                        ""\").update();
                """;
        Requirement update = new Requirement("fulfillment.courier_location_tracks", "UPDATE");

        Set<Requirement> headVerbsOnly = new LinkedHashSet<>();
        for (Map.Entry<Pattern, String> statement : STATEMENTS) {
            Matcher matcher = statement.getKey().matcher(upsert);
            while (matcher.find()) {
                owned(matcher.group(1), matcher.group(2)).ifPresent(table ->
                        headVerbsOnly.add(new Requirement(table, statement.getValue())));
            }
        }
        assertThat(headVerbsOnly)
                .as("the scan as it was: an upsert reads as an INSERT, which the grant "
                        + "satisfied, which is why nothing failed here while every telemetry "
                        + "write failed in production")
                .contains(new Requirement("fulfillment.courier_location_tracks", "INSERT"))
                .doesNotContain(update);

        assertThat(statementPrivileges(upsert))
                .as("ON CONFLICT ... DO UPDATE needs UPDATE on its target, and SELECT because "
                        + "the conflict clause reads the stored row")
                .contains(update, new Requirement("fulfillment.courier_location_tracks", "SELECT"));

        // Built by cutting the conflict action off the string above rather than by
        // String.replace on a second text block. The two blocks strip different
        // amounts of incidental indentation — eleven spaces before SET here, three
        // there — so the replace matched nothing, silently left DO UPDATE in place,
        // and this assertion spent its life re-checking the case above it.
        String doNothing = upsert.substring(0, upsert.indexOf("ON CONFLICT"))
                + "ON CONFLICT (window_start, tenant_id, courier_id) DO NOTHING\n"
                + "                \"\"\").update();\n";
        assertThat(doNothing)
                .as("the variant has to differ from the original, or this proves nothing")
                .doesNotContain("DO UPDATE");
        assertThat(statementPrivileges(doNothing))
                .as("DO NOTHING writes nothing and must not be made to ask for UPDATE — a scan "
                        + "that demands grants the code does not need is a scan somebody turns off")
                .doesNotContain(update);

        assertThat(statementPrivileges("""
                jdbc.sql(""\"
                        INSERT INTO integration.pos_staged_availability (run_id, tenant_id)
                        VALUES (:runId, :tenantId)
                        ON CONFLICT (run_id, external_entity_id) DO NOTHING
                        ""\");
                jdbc.sql(""\"
                        INSERT INTO integration.pos_absence_observations (run_id, streak)
                        VALUES (:runId, :streak)
                        ON CONFLICT (binding_id, entity_type) DO UPDATE SET streak = 1
                        ""\");
                """))
                .as("an idempotent bulk insert must not inherit the upsert two statements below "
                        + "it; this exact pair is why the gap between the clauses may cross "
                        + "neither another INSERT nor a DO NOTHING")
                .contains(new Requirement("integration.pos_absence_observations", "UPDATE"))
                .doesNotContain(new Requirement("integration.pos_staged_availability", "UPDATE"));

        for (String locking : List.of(
                "SELECT id FROM ordering.order_timers WHERE fires_at < :now FOR UPDATE SKIP LOCKED",
                "SELECT id FROM ordering.order_timers WHERE fires_at < :now FOR NO KEY UPDATE",
                "SELECT id FROM ordering.order_timers WHERE tenant_id = :tenantId FOR SHARE")) {
            assertThat(statementPrivileges("jdbc.sql(\"\"\"\n" + locking + "\n\"\"\")"))
                    .as("%s takes a row lock, and every lock strength needs UPDATE — FOR SHARE "
                            + "included, which is not what its name suggests", locking)
                    .contains(new Requirement("ordering.order_timers", "UPDATE"));
        }

        assertThat(statementPrivileges("""
                    /**
                     * <p>{@code FOR UPDATE SKIP LOCKED} so two workers never fire one timer twice.
                     */
                    public List<UUID> due(Instant now) {
                        return jdbc.sql("SELECT id FROM ordering.order_timers WHERE fires_at < :now")
                """))
                .as("a paragraph about locking is not a locking clause; the scan attributed one "
                        + "to loyalty.entries this way before the comment lines were skipped")
                .doesNotContain(new Requirement("ordering.order_timers", "UPDATE"));

        assertThat(statementPrivileges("""
                jdbc.sql(""\"
                        DELETE FROM fulfillment.courier_positions_live live
                         USING fulfillment.courier_duty_sessions session
                         WHERE session.id = live.duty_session_id
                        ""\").update();
                """))
                .as("DELETE ... USING reads the table it joins, and the verb at the head of the "
                        + "statement says DELETE")
                .contains(new Requirement("fulfillment.courier_duty_sessions", "SELECT"),
                        new Requirement("fulfillment.courier_positions_live", "DELETE"));

        assertThat(privilege("fulfillment.courier_location_tracks", "UPDATE"))
                .as("and the grant the scan now asks for is the one V0080 writes; drop it and "
                        + "theGrantsCoverWhatTheCodeActuallyDoes goes red instead of production")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // The guard that stops the gap reopening
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the startup guard refuses a deployment connected as the database owner")
    void theGuardRefusesAPrivilegedConnectionOnANonLocalProfile() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertThatThrownBy(() -> new DatabasePrivilegeGuard(production, asOwner).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(db.username())
                .hasMessageContaining("horecaos_app");

        assertThatCode(() -> new DatabasePrivilegeGuard(production, asApplication).run(null))
                .as("and accepts the role the deployment is supposed to use")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the startup guard reports a privileged connection on a local profile and starts anyway")
    void theGuardDoesNotBreakLocalDevelopment() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> new DatabasePrivilegeGuard(local, asOwner).run(null))
                .doesNotThrowAnyException();

        MockEnvironment noProfile = new MockEnvironment();
        assertThatCode(() -> new DatabasePrivilegeGuard(noProfile, asOwner).run(null))
                .as("the test suite runs with no profile at all and must keep starting")
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------

    private static void createLoginRole(String name, String password, String groupRole) {
        owner.sql("DROP ROLE IF EXISTS " + name).update();
        owner.sql("CREATE ROLE " + name + " LOGIN PASSWORD '" + password + "'").update();
        owner.sql("ALTER ROLE " + name
                + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT").update();
        owner.sql("GRANT " + groupRole + " TO " + name).update();
    }

    private static boolean privilege(String object, String privilege) {
        return privilege(TestDatabase.APPLICATION_ROLE, object, privilege);
    }

    private static boolean privilege(String role, String object, String privilege) {
        return Boolean.TRUE.equals(owner
                .sql("SELECT has_table_privilege(:role, :object, :privilege)")
                .param("role", role)
                .param("object", object)
                .param("privilege", privilege)
                .query(Boolean.class).single());
    }

    /** One schema-qualified table and one privilege the application's SQL needs on it. */
    private record Requirement(String object, String privilege) implements Comparable<Requirement> {
        @Override
        public int compareTo(Requirement other) {
            int byObject = object.compareTo(other.object);
            return byObject != 0 ? byObject : privilege.compareTo(other.privilege);
        }
    }

    /**
     * The schemas a migration creates. Anything else that looks like
     * {@code word.word} in a statement — {@code cp.table_name}, an alias, a JSON
     * path — is not a table of ours and is left alone.
     */
    private static final Set<String> OWNED_SCHEMAS = Set.of(
            "audit", "catalog", "commercial", "courier", "customer", "dinein", "fiscal",
            "fulfillment", "iam", "integration", "inventory", "kitchen", "loyalty",
            "marketing", "media", "migration", "notifications", "ordering", "partner",
            "payments", "platform", "pos", "pricing", "reporting", "telemetry", "tenant");

    private static final List<Map.Entry<Pattern, String>> STATEMENTS = List.of(
            Map.entry(Pattern.compile("\\bINSERT\\s+INTO\\s+([a-z_]+)\\.([a-z_0-9]+)",
                    Pattern.CASE_INSENSITIVE), "INSERT"),
            Map.entry(Pattern.compile("\\bUPDATE\\s+([a-z_]+)\\.([a-z_0-9]+)",
                    Pattern.CASE_INSENSITIVE), "UPDATE"),
            Map.entry(Pattern.compile("\\bDELETE\\s+FROM\\s+([a-z_]+)\\.([a-z_0-9]+)",
                    Pattern.CASE_INSENSITIVE), "DELETE"),
            Map.entry(Pattern.compile("\\bTRUNCATE\\s+(?:TABLE\\s+)?([a-z_]+)\\.([a-z_0-9]+)",
                    Pattern.CASE_INSENSITIVE), "TRUNCATE"),
            Map.entry(Pattern.compile("\\bFROM\\s+([a-z_]+)\\.([a-z_0-9]+)",
                    Pattern.CASE_INSENSITIVE), "SELECT"),
            Map.entry(Pattern.compile("\\bJOIN\\s+([a-z_]+)\\.([a-z_0-9]+)",
                    Pattern.CASE_INSENSITIVE), "SELECT"),
            // DELETE ... USING joins a second table and reads it. The verb at the
            // head of the statement is DELETE and the privilege this needs is
            // SELECT, on a table the head never names.
            Map.entry(Pattern.compile("\\bUSING\\s+([a-z_]+)\\.([a-z_0-9]+)",
                    Pattern.CASE_INSENSITIVE), "SELECT"));

    // -----------------------------------------------------------------------
    // The privileges a statement needs and does not spell
    // -----------------------------------------------------------------------

    /**
     * {@code INSERT INTO x ... ON CONFLICT ... DO UPDATE}, which needs UPDATE on
     * {@code x}.
     *
     * <p>This is the shape that got past the scan above. It reads the verb at the
     * head of the statement, the head says INSERT, and
     * {@code fulfillment.courier_location_tracks} had INSERT — so telemetry ingest
     * shipped needing a privilege nothing had granted and nothing had asked for.
     * The privilege is checked when the statement is planned, so it is not the
     * conflicting calls that failed, it is all of them.
     *
     * <p>The two guards in the gap are what make the attribution trustworthy in a
     * file holding several statements. Neither gap may cross another
     * {@code INSERT INTO} or another {@code ON CONFLICT} — that pairs each insert
     * with its own conflict clause rather than with a later one — and the second
     * may not cross a {@code DO NOTHING}, which is what stops
     * {@code integration.pos_staged_availability}'s idempotent bulk insert from
     * being read as an upsert because a different statement further down the file
     * has a {@code DO UPDATE}. Both were caught by writing the loose version first
     * and checking what it claimed.
     */
    private static final String NOT_INTO_THE_NEXT_STATEMENT =
            "(?:(?!\\bINSERT\\s+INTO\\b|\\bON\\s+CONFLICT\\b)[\\s\\S])*?";

    private static final Pattern UPSERT = Pattern.compile(
            "\\bINSERT\\s+INTO\\s+([a-z_]+)\\.([a-z_0-9]+)" + NOT_INTO_THE_NEXT_STATEMENT
                    + "\\bON\\s+CONFLICT\\b"
                    + "(?:(?!\\bINSERT\\s+INTO\\b|\\bON\\s+CONFLICT\\b|\\bDO\\s+NOTHING\\b)[\\s\\S])*?"
                    + "\\bDO\\s+UPDATE\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A row-locking clause, which needs UPDATE on what it locks — {@code FOR SHARE}
     * as much as {@code FOR UPDATE}.
     *
     * <p>The second half of that sentence is the surprising one and it was checked
     * against the engine rather than read off a page: as {@code horecaos_application},
     * which holds SELECT and INSERT on {@code audit.audit_events},
     * {@code SELECT id FROM audit.audit_events LIMIT 1 FOR SHARE} answers
     * {@code permission denied}. Every lock strength maps to the UPDATE privilege
     * inside the executor. This platform claims a queue with {@code FOR UPDATE SKIP
     * LOCKED} in nine stores and takes {@code FOR SHARE} in a tenth, and every one
     * of them is a scheduled worker whose failure is a stack trace on a background
     * thread rather than a request anybody sees.
     */
    private static final Pattern ROW_LOCK = Pattern.compile(
            "\\bFOR\\s+(?:NO\\s+KEY\\s+)?UPDATE\\b|\\bFOR\\s+(?:KEY\\s+)?SHARE\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LOCK_CANDIDATE = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([a-z_]+)\\.([a-z_0-9]+)(?:\\s+(?:AS\\s+)?([a-z][a-z_0-9]*))?",
            Pattern.CASE_INSENSITIVE);

    /** {@code FOR UPDATE OF d SKIP LOCKED} — the aliases the lock actually names. */
    private static final Pattern LOCK_NAMES = Pattern.compile(
            "\\AFOR\\s+(?:NO\\s+KEY\\s+)?UPDATE\\s+OF\\s+([a-z_0-9,\\s]*?)"
                    + "(?:\\s+SKIP\\b|\\s+NOWAIT\\b|\\n)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Every privilege one Java source's SQL needs, the three implied ones included.
     *
     * <p>Separated from the file walk so the rules can be exercised against an exact
     * statement — see {@link #theScanRecognisesThePrivilegesAStatementDoesNotSpell}.
     * A scan that silently stops matching is worse than no scan, because the suite
     * stays green while the coverage leaves.
     */
    static Set<Requirement> statementPrivileges(String source) {
        Set<Requirement> found = new LinkedHashSet<>();
        for (Map.Entry<Pattern, String> statement : STATEMENTS) {
            Matcher matcher = statement.getKey().matcher(source);
            while (matcher.find()) {
                owned(matcher.group(1), matcher.group(2))
                        .ifPresent(table -> found.add(new Requirement(table, statement.getValue())));
            }
        }

        Matcher upsert = UPSERT.matcher(source);
        while (upsert.find()) {
            owned(upsert.group(1), upsert.group(2)).ifPresent(table -> {
                found.add(new Requirement(table, "UPDATE"));
                // The conflict clause reads the stored row — `WHERE excluded.x >
                // table.x` is the staleness rule every upsert here is built on.
                found.add(new Requirement(table, "SELECT"));
            });
        }

        Matcher lock = ROW_LOCK.matcher(source);
        while (lock.find()) {
            if (insideAComment(source, lock.start())) {
                // This codebase explains its locking in Javadoc — `{@code FOR UPDATE
                // SKIP LOCKED} so two workers never fire one timer twice` — and prose
                // is not a statement. Without this the scan attributes a lock to
                // whatever table the paragraph above happened to mention, which is
                // how it first claimed loyalty.entries needed UPDATE.
                continue;
            }
            for (String table : lockedTables(source, lock.start())) {
                found.add(new Requirement(table, "UPDATE"));
            }
        }
        return found;
    }

    /** The table, if the schema is one a migration of ours creates. */
    private static Optional<String> owned(String schema, String table) {
        String lowered = schema.toLowerCase();
        return OWNED_SCHEMAS.contains(lowered)
                ? Optional.of(lowered + "." + table.toLowerCase())
                : Optional.empty();
    }

    private static boolean insideAComment(String source, int at) {
        int lineStart = source.lastIndexOf('\n', at) + 1;
        String before = source.substring(lineStart, at).stripLeading();
        return before.startsWith("*") || before.startsWith("//") || before.startsWith("/*");
    }

    /**
     * What a locking clause locks: the relations of the query it closes, narrowed to
     * the ones it names if it names any.
     *
     * <p>The query is taken as the text back to the nearest {@code SELECT}, which is
     * blunt and deliberately so — a locking clause that cannot be attributed to a
     * relation asks for UPDATE on every relation in reach rather than on none, and
     * a false alarm here is a migration writing a grant the code already needs.
     */
    private static List<String> lockedTables(String source, int at) {
        String query = source.substring(Math.max(0, at - 3_000), at);
        int head = query.toUpperCase().lastIndexOf("SELECT ");
        if (head >= 0) {
            query = query.substring(head);
        }

        Map<String, String> byAlias = new LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        Matcher candidate = LOCK_CANDIDATE.matcher(query);
        while (candidate.find()) {
            owned(candidate.group(1), candidate.group(2)).ifPresent(table -> {
                all.add(table);
                if (candidate.group(3) != null) {
                    byAlias.put(candidate.group(3).toLowerCase(), table);
                }
            });
        }

        Matcher named = LOCK_NAMES.matcher(source.substring(at,
                Math.min(source.length(), at + 200)));
        if (named.find()) {
            List<String> targets = Stream.of(named.group(1).split(","))
                    .map(alias -> byAlias.get(alias.trim().toLowerCase()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!targets.isEmpty()) {
                return targets;
            }
        }
        return all;
    }

    private static Map<Requirement, String> requiredPrivileges() {
        Map<Requirement, String> found = new LinkedHashMap<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            sources.filter(path -> path.getFileName().toString().endsWith(".java")).forEach(path ->
                    statementPrivileges(read(path)).forEach(requirement ->
                            found.putIfAbsent(requirement, path.getFileName().toString())));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return new TreeMap<>(found);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    // -----------------------------------------------------------------------
    // The statements no GRANT can ever make legal for this role
    // -----------------------------------------------------------------------

    /** {@code (?:audit|catalog|…)\s*\.} — one of ours, and nothing else. */
    private static final String OWNED =
            "(?:" + String.join("|", new TreeSet<>(OWNED_SCHEMAS)) + ")\\s*\\.";

    /** Every privilege name that can stand after GRANT or REVOKE. */
    private static final String PRIVILEGES =
            "(?:ALL(?:\\s+PRIVILEGES)?|SELECT|INSERT|UPDATE|DELETE|TRUNCATE|REFERENCES|TRIGGER"
                    + "|USAGE|EXECUTE|CREATE|CONNECT|TEMPORARY|TEMP|MAINTAIN|SET|ALTER\\s+SYSTEM)";

    /**
     * A privilege on the left, the shape that needs it on the right.
     *
     * <p>Two rules kept these free of the prose that surrounds them in a codebase
     * this commented. A statement naming a table has to name it in one of our own
     * schemas, so {@code DROP TABLE} in a sentence matches nothing; and a statement
     * that names no table has to carry a word only SQL uses in that position, so
     * "Grant a role at a scope" is not a {@code GRANT} and "Revoke a grant" is not
     * a {@code REVOKE}.
     */
    private static final List<Map.Entry<Pattern, String>> FORBIDDEN = List.of(
            Map.entry(Pattern.compile(
                    "\\bCREATE\\s+(?:UNLOGGED\\s+|GLOBAL\\s+|LOCAL\\s+|TEMP\\w*\\s+)*TABLE\\s+"
                            + "(?:IF\\s+NOT\\s+EXISTS\\s+)?" + OWNED,
                    Pattern.CASE_INSENSITIVE), "CREATE TABLE"),
            Map.entry(Pattern.compile(
                    "\\bDROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?" + OWNED,
                    Pattern.CASE_INSENSITIVE), "DROP TABLE"),
            Map.entry(Pattern.compile(
                    "\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?" + OWNED,
                    Pattern.CASE_INSENSITIVE), "ALTER TABLE"),
            Map.entry(Pattern.compile(
                    "\\bTRUNCATE\\s+(?:TABLE\\s+)?(?:ONLY\\s+)?" + OWNED,
                    Pattern.CASE_INSENSITIVE), "TRUNCATE"),
            Map.entry(Pattern.compile(
                    "\\bLOCK\\s+(?:TABLE\\s+)?" + OWNED,
                    Pattern.CASE_INSENSITIVE), "LOCK TABLE"),
            Map.entry(Pattern.compile(
                    "\\bREFRESH\\s+MATERIALIZED\\s+VIEW\\b", Pattern.CASE_INSENSITIVE),
                    "REFRESH MATERIALIZED VIEW"),
            Map.entry(Pattern.compile(
                    "\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:UNIQUE\\s+)?"
                            + "(?:INDEX|SCHEMA|ROLE|USER|VIEW|MATERIALIZED\\s+VIEW|SEQUENCE"
                            + "|TRIGGER|FUNCTION|PROCEDURE|EXTENSION|TYPE|DATABASE|TABLESPACE"
                            + "|PUBLICATION|SUBSCRIPTION)\\b",
                    Pattern.CASE_INSENSITIVE), "CREATE"),
            Map.entry(Pattern.compile(
                    "\\bDROP\\s+(?:INDEX|SCHEMA|ROLE|USER|VIEW|MATERIALIZED\\s+VIEW|SEQUENCE"
                            + "|TRIGGER|FUNCTION|PROCEDURE|EXTENSION|TYPE|DATABASE|CONSTRAINT"
                            + "|COLUMN|PUBLICATION|SUBSCRIPTION)\\b",
                    Pattern.CASE_INSENSITIVE), "DROP"),
            Map.entry(Pattern.compile(
                    "\\bALTER\\s+(?:ROLE|USER|SCHEMA|SEQUENCE|INDEX|FUNCTION|DATABASE|SYSTEM|TYPE)\\b",
                    Pattern.CASE_INSENSITIVE), "ALTER"),
            Map.entry(Pattern.compile(
                    "\\b(?:REINDEX|CLUSTER|VACUUM)\\s+(?:TABLE\\s+|VERBOSE\\s+|FULL\\s+)*" + OWNED,
                    Pattern.CASE_INSENSITIVE), "maintenance DDL"),
            Map.entry(Pattern.compile(
                    "\\bCOMMENT\\s+ON\\s+(?:TABLE|COLUMN|FUNCTION|SCHEMA|INDEX)\\b",
                    Pattern.CASE_INSENSITIVE), "COMMENT ON"),
            Map.entry(Pattern.compile(
                    "\\bSET\\s+(?:ROLE|SESSION\\s+AUTHORIZATION)\\b", Pattern.CASE_INSENSITIVE),
                    "SET ROLE"),
            // The one that does not raise. A grant from a role with no grant
            // option is a WARNING and a no-op, so it has to be caught here or it
            // is caught nowhere.
            //
            // The privilege list has to be followed by ON or TO, which is what
            // keeps "Grant references location %s outside tenant %s" — a sentence
            // in an authorization error — from reading as a GRANT statement.
            Map.entry(Pattern.compile(
                    "\\bGRANT\\s+" + PRIVILEGES + "(?:\\s*,\\s*" + PRIVILEGES + ")*\\s+(?:ON|TO)\\b",
                    Pattern.CASE_INSENSITIVE), "GRANT"),
            Map.entry(Pattern.compile(
                    "\\bREVOKE\\s+(?:GRANT\\s+OPTION\\s+FOR\\s+)?" + PRIVILEGES
                            + "(?:\\s*,\\s*" + PRIVILEGES + ")*\\s+(?:ON|FROM)\\b",
                    Pattern.CASE_INSENSITIVE), "REVOKE"),
            Map.entry(Pattern.compile(
                    "\\b(?:GRANT|REVOKE)\\s+horecaos_\\w+\\s+(?:TO|FROM)\\b", Pattern.CASE_INSENSITIVE),
                    "role membership"));

    /**
     * Every schema change one Java source issues, as
     * {@code "<privilege> — <the matched text>"}.
     *
     * <p>Separated from the file walk so the patterns themselves are testable
     * against the exact statements that shipped; a scan nothing exercises is a
     * scan that can quietly stop matching.
     */
    private static List<String> ddlViolations(String source) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<Pattern, String> forbidden : FORBIDDEN) {
            Matcher matcher = forbidden.getKey().matcher(source);
            while (matcher.find()) {
                found.add("%s — %s".formatted(forbidden.getValue(),
                        matcher.group().replaceAll("\\s+", " ").trim()));
            }
        }
        return found;
    }

    // -----------------------------------------------------------------------
    // What a SECURITY DEFINER function's declaration and body have to say
    // -----------------------------------------------------------------------

    /** {@code search_path=pg_catalog, pg_temp} out of {@code proconfig}. */
    private static final Pattern SEARCH_PATH = Pattern.compile("search_path=([^,]*(?:,[^,]*)*)");

    /**
     * The catalogue relations a SECURITY DEFINER body must never name bare.
     *
     * <p>Functions and operators are safe: PostgreSQL never searches the temporary
     * schema for those. Relations and types are the exposure, so this is the list of
     * catalogue tables and views that actually appear in bodies here, plus the ones
     * a partition or privilege helper would reach for next.
     */
    private static final Pattern UNQUALIFIED_CATALOG = Pattern.compile(
            "(?<!pg_catalog\\.)\\bpg_(class|namespace|inherits|proc|attribute|constraint|index"
                    + "|indexes|depend|type|tables|views|matviews|roles|database|partitioned_table"
                    + "|authid|shdepend|rewrite|trigger|extension)\\b");

    /**
     * Every bare catalogue name in a function body, with SQL line comments removed
     * first so that a body explaining what {@code pg_inherits} is for does not read
     * as a body querying it.
     */
    private static List<String> unqualifiedCatalogReads(String body) {
        Matcher matcher = UNQUALIFIED_CATALOG.matcher(body.replaceAll("--[^\n]*", ""));
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    // -----------------------------------------------------------------------

    /** The clock the retention sweep is judged by, which is not a fixture's. */
    private static LocalDate databaseToday() {
        return owner.sql("SELECT current_date").query(LocalDate.class).single();
    }

    private static String trackPartition(LocalDate day) {
        return "courier_location_tracks_" + day.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** The sweep as the application asks for it, with a caller-supplied window. */
    private static List<String> sweep(int retentionDays) {
        return application
                .sql("SELECT fulfillment.sweep_expired_track_partitions(:days, false)")
                .param("days", retentionDays)
                .query(String.class)
                .list();
    }

    private static boolean tableExists(String schema, String table) {
        return Boolean.TRUE.equals(owner.sql("""
                SELECT EXISTS (SELECT 1 FROM pg_tables
                                WHERE schemaname = :schema AND tablename = :table)
                """)
                .param("schema", schema)
                .param("table", table)
                .query(Boolean.class).single());
    }
}
