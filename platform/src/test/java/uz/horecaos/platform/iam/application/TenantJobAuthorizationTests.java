package uz.horecaos.platform.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantCommand;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantableRole;
import uz.horecaos.platform.iam.application.TenantRoleService.Draft;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant's own job in the grant path (ADR 0186 with ADR 0103): giving it away obeys the rules
 * the code-owned jobs obey, the picker agrees with the button, and a capability the build no
 * longer declares costs a job that capability and nothing else.
 */
class TenantJobAuthorizationTests extends TenantJobFixture {

    private static final String REASON = "Needed for the evening shift";

    private static final ResourceScope BRANCH = ResourceScope.location(TENANT, BRAND, LOCATION);

    private TenantJob job(String name, ScopeType level, String author, Capability... capabilities) {
        return jobs.create(
                TENANT,
                new Draft(
                        name,
                        level,
                        java.util.Arrays.stream(capabilities)
                                .map(Capability::code)
                                .toList(),
                        null),
                REASON,
                author);
    }

    @Test
    void anOwnerGivesACustomJobAndTheHolderHoldsExactlyItsCapabilities() {
        TenantJob cashier =
                job("Старший кассир", ScopeType.LOCATION, OWNER, Capability.ORDER_READ, Capability.ORDER_CANCEL);

        grants.grant(new GrantCommand("cashier-1", cashier.code(), BRANCH, "New hire", null), OWNER);

        assertThat(authorization.has("cashier-1", Capability.ORDER_CANCEL, BRANCH))
                .isTrue();
        assertThat(authorization.has("cashier-1", Capability.ORDER_READ, BRANCH))
                .isTrue();
        assertThat(authorization.has("cashier-1", Capability.REFUND_REQUEST, BRANCH))
                .as("nothing the job does not carry")
                .isFalse();
        assertThat(authorization.has(
                        "cashier-1", Capability.ORDER_CANCEL, ResourceScope.location(TENANT, BRAND, SIBLING_LOCATION)))
                .as("scope still covers downward only")
                .isFalse();
        assertThat(authorization.held("cashier-1", BRANCH))
                .containsExactlyInAnyOrder(Capability.ORDER_READ, Capability.ORDER_CANCEL);
    }

    @Test
    void theSessionViewNamesAJobTheCompanyDefinedAndNoCodeOwnedOne() {
        TenantJob cashier = job("Старший кассир", ScopeType.LOCATION, OWNER, Capability.ORDER_READ);
        grants.grant(new GrantCommand("cashier-1", cashier.code(), BRANCH, "New hire", null), OWNER);

        var cashierView = authorization.viewFor("cashier-1", TENANT);
        var cookView = authorization.viewFor(COOK, TENANT);

        assertThat(cashierView.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.roleCode()).isEqualTo(cashier.code());
            assertThat(scope.roleName())
                    .as("the person holding it cannot read the company's job list, and must not be shown its code")
                    .isEqualTo("Старший кассир");
        });
        assertThat(cookView.scopes())
                .singleElement()
                .satisfies(scope -> assertThat(scope.roleName())
                        .as("a code-owned job's name is a translation the console owns")
                        .isNull());
    }

    @Test
    void aBranchManagerGivesABranchLevelJobAtHerBranchWhenSheHoldsEveryCapabilityInIt() {
        TenantJob cashier =
                job("Старший кассир", ScopeType.LOCATION, OWNER, Capability.ORDER_READ, Capability.ORDER_CANCEL);
        assertThat(authorization.held(BRANCH_MANAGER, BRANCH)).contains(Capability.ORDER_READ, Capability.ORDER_CANCEL);

        grants.grant(new GrantCommand("cashier-1", cashier.code(), BRANCH, "Hired this morning", null), BRANCH_MANAGER);

        assertThat(authorization.has("cashier-1", Capability.ORDER_CANCEL, BRANCH))
                .isTrue();
    }

    @Test
    void aBranchManagerCannotGiveACompanyLevelCustomJobAtHerBranchEvenHoldingEveryCapabilityInIt() {
        TenantJob companyWide = job("Аналитик", ScopeType.TENANT, OWNER, Capability.ORDER_READ);

        assertThatThrownBy(() -> grants.grant(
                        new GrantCommand("analyst-1", companyWide.code(), BRANCH, "Sideways", null), BRANCH_MANAGER))
                .as(
                        "ADR 0103: a job broader than the place it is given at is the granter's to give only if her grant is company-wide")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CAPABILITY));
    }

    @Test
    void aBranchManagerCannotGiveAJobCarryingWhatSheDoesNotHold() {
        TenantJob exporter =
                job("Выгрузка", ScopeType.LOCATION, OWNER, Capability.ORDER_READ, Capability.CUSTOMER_PII_EXPORT);

        assertThatThrownBy(() -> grants.grant(
                        new GrantCommand("someone", exporter.code(), BRANCH, "Escalation", null), BRANCH_MANAGER))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }

    @Test
    void thePickerAndTheButtonAgreeForEveryJobAndEveryGranter() {
        job("Старший кассир", ScopeType.LOCATION, OWNER, Capability.ORDER_READ, Capability.ORDER_CANCEL);
        job("Аналитик", ScopeType.TENANT, OWNER, Capability.ORDER_READ);
        job("Выгрузка", ScopeType.LOCATION, OWNER, Capability.ORDER_READ, Capability.CUSTOMER_PII_EXPORT);
        job("Бренд-кассир", ScopeType.BRAND, OWNER, Capability.ORDER_READ, Capability.ORDER_CANCEL);

        record Case(String granter, ResourceScope scope) {}
        List<Case> cases = List.of(
                new Case(OWNER, ResourceScope.tenant(TENANT)),
                new Case(OWNER, BRANCH),
                new Case(ADMIN, ResourceScope.tenant(TENANT)),
                new Case(BRANCH_MANAGER, BRANCH));

        int compared = 0;
        int refusals = 0;
        for (Case c : cases) {
            List<GrantableRole> picker = grants.grantableRoles(c.scope(), c.granter());
            assertThat(picker).extracting(GrantableRole::custom).contains(true, false);
            for (GrantableRole role : picker) {
                String candidate = "candidate-" + UUID.randomUUID();
                boolean granted;
                try {
                    grants.grant(new GrantCommand(candidate, role.code(), c.scope(), REASON, null), c.granter());
                    granted = true;
                } catch (AuthorizationService.AccessDeniedException | ApiException refused) {
                    granted = false;
                }
                assertThat(granted)
                        .as(
                                "%s giving %s at %s: the picker said grantable=%s",
                                c.granter(), role.code(), c.scope().type(), role.grantable())
                        .isEqualTo(role.grantable());
                compared++;
                refusals += granted ? 0 : 1;
            }
        }
        assertThat(compared).isGreaterThan(40);
        assertThat(refusals)
                .as("a comparison that never saw a refusal would prove nothing")
                .isGreaterThan(5);
    }

    @Test
    void theListCarriesTheCompanysOwnJobsWithTheirNameAndVersionAndNeverAnotherCompanys() {
        TenantJob cashier = job("Старший кассир", ScopeType.LOCATION, OWNER, Capability.ORDER_READ);
        jobs.create(
                OTHER_TENANT, new Draft("Чужой", ScopeType.LOCATION, List.of("order.read"), null), REASON, OTHER_OWNER);

        List<GrantableRole> mine = grants.grantableRoles(ResourceScope.tenant(TENANT), OWNER);

        assertThat(mine).filteredOn(GrantableRole::custom).singleElement().satisfies(role -> {
            assertThat(role.code()).isEqualTo(cashier.code());
            assertThat(role.name()).isEqualTo("Старший кассир");
            assertThat(role.version()).isEqualTo(1L);
            assertThat(role.status()).isEqualTo("ACTIVE");
            assertThat(role.grantable()).isTrue();
        });
        assertThat(mine).filteredOn(role -> !role.custom()).hasSize(8).allSatisfy(role -> {
            assertThat(role.name()).isNull();
            assertThat(role.version()).isNull();
        });
        assertThat(mine).extracting(GrantableRole::code).doesNotContain("job-other");
    }

    @Test
    void aClosedJobLeavesThePicker() {
        TenantJob cashier = job("Старший кассир", ScopeType.LOCATION, OWNER, Capability.ORDER_READ);
        jobs.retire(TENANT, cashier.code(), cashier.version(), "Closing", OWNER);

        assertThat(grants.grantableRoles(ResourceScope.tenant(TENANT), OWNER))
                .extracting(GrantableRole::code)
                .doesNotContain(cashier.code());
    }

    @Test
    void aCapabilityTheBuildNoLongerDeclaresGrantsNothingAndBreaksNothingElse() {
        TenantJob cashier =
                job("Старший кассир", ScopeType.LOCATION, OWNER, Capability.ORDER_READ, Capability.ORDER_CANCEL);
        grants.grant(new GrantCommand("cashier-1", cashier.code(), BRANCH, "New hire", null), OWNER);
        jdbc.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:id, 'legacy.since.removed')")
                .param("id", jobId(cashier.code()))
                .update();

        assertThat(authorization.has("cashier-1", Capability.ORDER_CANCEL, BRANCH))
                .as("one unknown code must not take away every capability the holder has")
                .isTrue();
        assertThat(authorization.held("cashier-1", BRANCH))
                .containsExactlyInAnyOrder(Capability.ORDER_READ, Capability.ORDER_CANCEL);
        assertThat(grants.grantableRoles(ResourceScope.tenant(TENANT), OWNER))
                .filteredOn(GrantableRole::custom)
                .singleElement()
                .satisfies(role -> assertThat(role.grantable()).isTrue());
    }

    @Test
    void theSynchronizerPrunesARemovedCodeFromATenantJobAndTouchesNothingElse() {
        TenantJob mine =
                job("Старший кассир", ScopeType.LOCATION, OWNER, Capability.ORDER_READ, Capability.ORDER_CANCEL);
        TenantJob theirs = jobs.create(
                OTHER_TENANT, new Draft("Чужой", ScopeType.LOCATION, List.of("order.read"), null), REASON, OTHER_OWNER);
        jdbc.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:id, 'legacy.since.removed')")
                .param("id", jobId(mine.code()))
                .update();
        long platformRowsBefore = count(
                "SELECT count(*) FROM iam.role_capabilities rc JOIN iam.roles r ON r.id = rc.role_id WHERE r.tenant_id IS NULL");

        new RoleRegistrySynchronizer(jdbc).synchronize();

        assertThat(jobs.get(TENANT, mine.code()).capabilities()).containsExactly("order.cancel", "order.read");
        assertThat(jobs.get(OTHER_TENANT, theirs.code()).capabilities()).containsExactly("order.read");
        assertThat(jobs.get(TENANT, mine.code()).version())
                .as("the synchronizer never edits a tenant's job, it only removes a code that no longer exists")
                .isEqualTo(mine.version());
        assertThat(
                        count(
                                "SELECT count(*) FROM iam.role_capabilities rc JOIN iam.roles r ON r.id = rc.role_id WHERE r.tenant_id IS NULL"))
                .isEqualTo(platformRowsBefore);
        assertThat(count("SELECT count(*) FROM iam.roles WHERE tenant_id IS NOT NULL"))
                .isEqualTo(2L);
        assertThat(PlatformRole.values()).hasSizeGreaterThan(8);
    }

    private UUID jobId(String code) {
        return jdbc.sql("SELECT id FROM iam.roles WHERE code = :code")
                .param("code", code)
                .query(UUID.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
