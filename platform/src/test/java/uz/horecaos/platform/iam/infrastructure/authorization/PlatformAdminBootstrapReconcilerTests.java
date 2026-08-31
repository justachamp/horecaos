package uz.horecaos.platform.iam.infrastructure.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0025 config-driven platform-admin bootstrap (Gap A of the 2026-08-30
 * proving run).
 */
class PlatformAdminBootstrapReconcilerTests {

    private static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SUBJECT_A = "keycloak-subject-aaaa";
    private static final String SUBJECT_B = "keycloak-subject-bbbb";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.roles CASCADE").update();
    }

    @Test
    void emptyConfigurationCreatesNoGrantAndDoesNotEvenSynchroniseRoles() {
        reconciler(List.of()).reconcile();

        assertThat(activePlatformAdminSubjects()).isEmpty();
        // synchronize() also seeds iam.roles; asserting it never ran when there
        // is nothing to reconcile is what stops an empty configuration turning
        // into a startup side effect nobody asked for.
        assertThat(jdbc.sql("SELECT count(*) FROM iam.roles").query(Long.class).single())
                .isZero();
    }

    @Test
    void aConfiguredSubjectGetsThePlatformAdminGrant() {
        reconciler(List.of(SUBJECT_A)).reconcile();

        assertThat(activePlatformAdminSubjects()).containsExactly(SUBJECT_A);
        assertThat(grantedBy(SUBJECT_A)).isEqualTo(PlatformAdminBootstrapReconciler.SYSTEM_ACTOR);
    }

    @Test
    void runningTwiceProducesExactlyOneGrantNotTwo() {
        PlatformAdminBootstrapReconciler reconciler = reconciler(List.of(SUBJECT_A));
        reconciler.reconcile();
        reconciler.reconcile();

        assertThat(countActivePlatformAdminGrants(SUBJECT_A))
                .as("idempotent: a restart must not accumulate a second grant for the same subject")
                .isEqualTo(1);
    }

    @Test
    void aSecondStartupWithADifferentSubjectAddsWithoutDuplicating() {
        reconciler(List.of(SUBJECT_A)).reconcile();
        reconciler(List.of(SUBJECT_A, SUBJECT_B)).reconcile();

        assertThat(activePlatformAdminSubjects()).containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B);
        assertThat(countActivePlatformAdminGrants(SUBJECT_A)).isEqualTo(1);
    }

    /**
     * The load-bearing property: config absence is not revocation. A subject
     * removed from the list — or a config typo, or an unset env var on a
     * restart — must not lose platform administration, because the failure
     * mode of getting that wrong is a fresh deployment where nobody can reach
     * the platform-admin-only bootstrap any more.
     */
    @Test
    void removingASubjectFromConfigurationNeverRevokesItsExistingGrant() {
        reconciler(List.of(SUBJECT_A, SUBJECT_B)).reconcile();
        assertThat(activePlatformAdminSubjects()).containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B);

        // SUBJECT_B dropped from configuration entirely (a typo, an offboarding
        // that forgot the audited revoke path, a rollback).
        reconciler(List.of(SUBJECT_A)).reconcile();

        assertThat(activePlatformAdminSubjects())
                .as("removal from config must never look like a revocation")
                .containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B);
    }

    /** Same property against the degenerate case: every subject removed. */
    @Test
    void emptyingTheConfigurationEntirelyStillRevokesNothing() {
        reconciler(List.of(SUBJECT_A)).reconcile();

        reconciler(List.of()).reconcile();

        assertThat(activePlatformAdminSubjects())
                .as("an empty list must read as \"nothing more to add\", never \"revoke everyone\"")
                .containsExactly(SUBJECT_A);
    }

    @Test
    void blankAndDuplicateEntriesAreIgnored() {
        var properties = new IamBootstrapProperties(List.of(SUBJECT_A, "", "  ", SUBJECT_A, SUBJECT_A));

        assertThat(properties.bootstrapPlatformAdmins()).containsExactly(SUBJECT_A);
    }

    private PlatformAdminBootstrapReconciler reconciler(List<String> subjects) {
        return new PlatformAdminBootstrapReconciler(
                jdbc, new IamBootstrapProperties(subjects), new RoleRegistrySynchronizer(jdbc), CLOCK);
    }

    private List<String> activePlatformAdminSubjects() {
        return jdbc.sql("""
                SELECT g.principal_subject FROM iam.grants g
                  JOIN iam.roles r ON r.id = g.role_id
                 WHERE r.code = 'platform-admin' AND g.scope_type = 'PLATFORM' AND g.status = 'ACTIVE'
                 ORDER BY g.principal_subject
                """).query(String.class).list();
    }

    private long countActivePlatformAdminGrants(String subject) {
        return jdbc.sql("""
                SELECT count(*) FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                 WHERE r.code = 'platform-admin' AND g.scope_type = 'PLATFORM' AND g.status = 'ACTIVE'
                   AND g.principal_subject = :subject
                """).param("subject", subject).query(Long.class).single();
    }

    private String grantedBy(String subject) {
        return jdbc.sql("""
                SELECT g.granted_by FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                 WHERE r.code = 'platform-admin' AND g.scope_type = 'PLATFORM' AND g.status = 'ACTIVE'
                   AND g.principal_subject = :subject
                """).param("subject", subject).query(String.class).single();
    }
}
