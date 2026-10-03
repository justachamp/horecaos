package uz.horecaos.platform.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code PLATFORM_ACTIONS} is the only filter the platform approvals queue
 * applies, and it is derived from each action's declared
 * {@link ApprovalAction.Worklist}.
 *
 * <p>A {@code PLATFORM}-scope request carries no tenant by construction, so the
 * tenant worklist — keyed on {@code tenant_id} — can never show it. If its
 * action code is missing from that list, the request is raised {@code PENDING}
 * and appears in no queue at all: decidable only by someone who already holds
 * the identifier, and otherwise left to lapse after a day, whereupon the maker
 * repeats the cycle. Nothing in the suite enumerated the list, so removing a
 * line from it broke no test.
 *
 * <p>Not every platform-scope <em>policy</em> governs a platform-decided
 * <em>request</em>, though. {@code V0461} seeds a platform-scope floor for
 * {@code pricing.promotion.activate}, whose requests are the tenant's own
 * decision, raised at brand scope and listed on the tenant worklist; putting
 * that code on the platform queue would hand HorecaOS staff a tenant's
 * promotions. So this asserts the seeded policies against the declared
 * worklists rather than against a second written-down copy of the list: every
 * seeded action must be declared; a platform-decided one must be on the
 * platform queue; a tenant-decided one must not be, and must name an approver
 * capability some tenant-scoped role holds, or no tenant approver could exist
 * and the floor would be the same gap in another shape.
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
    void everyPlatformScopePolicyAMigrationSeedsReachesTheWorklistItsActionDeclares() {
        // Only the migrations' own policies: a test elsewhere may write a
        // PLATFORM policy of its own for an action that is deliberately not a
        // platform-queue action, and this must not become a test about those.
        List<SeededPolicy> seeded = JdbcClient.create(db.dataSource())
                .sql("""
                        SELECT DISTINCT action_code, required_approver_capability FROM audit.approval_policies
                         WHERE scope_type = 'PLATFORM' AND tenant_id IS NULL
                           AND approved_by LIKE 'migration %'
                         ORDER BY action_code
                        """)
                .query((rs, rowNumber) ->
                        new SeededPolicy(rs.getString("action_code"), rs.getString("required_approver_capability")))
                .list();

        assertThat(seeded)
                .as("a fail-closed policy nobody seeded would make this vacuous")
                .isNotEmpty();

        Map<ApprovalAction.Worklist, List<String>> byWorklist = seeded.stream()
                .collect(Collectors.groupingBy(
                        policy -> ApprovalAction.require(policy.actionCode()).worklist(),
                        Collectors.mapping(SeededPolicy::actionCode, Collectors.toList())));

        List<String> platformDecided = byWorklist.getOrDefault(ApprovalAction.Worklist.PLATFORM, List.of());
        assertThat(platformDecided)
                .as("the wallet and tenant floors are the reason the platform queue exists")
                .isNotEmpty();
        assertThat(ApprovalRequestController.PLATFORM_ACTIONS)
                .as("every action decided at platform scope has to reach the queue an approver works "
                        + "from; a request that reaches neither queue is not a control, it is a delay")
                .containsAll(platformDecided);

        List<String> tenantDecided = byWorklist.getOrDefault(ApprovalAction.Worklist.TENANT, List.of());
        assertThat(ApprovalRequestController.PLATFORM_ACTIONS)
                .as("a tenant's own decision never lists on HorecaOS's queue")
                .allSatisfy(code -> assertThat(tenantDecided).doesNotContain(code));
        for (SeededPolicy policy : seeded) {
            if (!tenantDecided.contains(policy.actionCode())) {
                continue;
            }
            Capability approver = Capability.find(policy.approverCapability()).orElseThrow();
            assertThat(Arrays.stream(PlatformRole.values())
                            .filter(role -> role.scopeType() != ScopeType.PLATFORM)
                            .anyMatch(role -> role.grants(approver)))
                    .as(
                            "%s is decided on the tenant worklist, so some tenant-scoped role must hold its "
                                    + "approver capability %s",
                            policy.actionCode(), policy.approverCapability())
                    .isTrue();
        }
    }

    private record SeededPolicy(String actionCode, String approverCapability) {}
}
