package uz.horecaos.platform.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code PLATFORM_ACTIONS} is the only filter the platform approvals queue
 * applies, and it is a hand-kept literal list.
 *
 * <p>A {@code PLATFORM}-scope request carries no tenant by construction, so the
 * tenant worklist — keyed on {@code tenant_id} — can never show it. If its
 * action code is missing from that list, the request is raised {@code PENDING}
 * and appears in no queue at all: decidable only by someone who already holds
 * the identifier, and otherwise left to lapse after a day, whereupon the maker
 * repeats the cycle. Nothing in the suite enumerated the list, so removing a
 * line from it broke no test.
 *
 * <p>This asserts the list against the seeded policies rather than against a
 * second written-down copy of itself. A copy would catch a deletion and nothing
 * else; this also catches the next {@code PLATFORM}-scope action a migration
 * seeds without adding it here, which is how the gap arose in the first place.
 */
class PlatformApprovalActionCoverageTests {

    private static TestDatabase.Handle db;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void everyPlatformScopePolicyAMigrationSeedsIsListedOnThePlatformQueue() {
        // Only the migrations' own policies: a test elsewhere may write a
        // PLATFORM policy of its own for an action that is deliberately not a
        // platform-queue action, and this must not become a test about those.
        List<String> seeded =
                JdbcClient.create(db.dataSource()).sql("""
                        SELECT DISTINCT action_code FROM audit.approval_policies
                         WHERE scope_type = 'PLATFORM' AND tenant_id IS NULL
                           AND approved_by LIKE 'migration %'
                         ORDER BY action_code
                        """).query(String.class).list();

        assertThat(seeded)
                .as("a fail-closed policy nobody seeded would make this vacuous")
                .isNotEmpty();
        assertThat(ApprovalRequestController.PLATFORM_ACTIONS)
                .as("every action governed at platform scope has to reach the queue an approver works "
                        + "from; a request that reaches neither queue is not a control, it is a delay")
                .containsAll(seeded);
    }
}
