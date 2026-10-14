package uz.horecaos.platform.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.RoleChanged;
import uz.horecaos.platform.iam.api.TenantJobRules;
import uz.horecaos.platform.iam.application.TenantRoleService.Draft;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0186: a company defines, edits and closes its own jobs.
 *
 * <p>The rules under test are the ones that keep this from being an escalation path: an author
 * cannot write what she does not hold, the deny-list holds against the owner herself, the level
 * is the author's word and does not drift, an edit is live for the holder at the next request,
 * and one company can neither see nor touch another's.
 */
class TenantRoleServiceTests extends TenantJobFixture {

    private static final String REASON = "The evening shift needs a cashier who can refund but not reprice";

    private static Draft draft(String name, @Nullable ScopeType level, Capability... capabilities) {
        return new Draft(
                name,
                level,
                java.util.Arrays.stream(capabilities).map(Capability::code).toList(),
                null);
    }

    private TenantJob cashier() {
        return jobs.create(
                TENANT,
                draft("Старший кассир", ScopeType.LOCATION, Capability.ORDER_READ, Capability.ORDER_CANCEL),
                REASON,
                OWNER);
    }

    @Test
    void anOwnerDefinesAJobAndItIsReadBackWithEverythingTheEditorNeeds() {
        TenantJob created = cashier();

        assertThat(created.code()).startsWith("job-").hasSize(14);
        assertThat(created.name()).isEqualTo("Старший кассир");
        assertThat(created.scopeType()).isEqualTo(ScopeType.LOCATION);
        assertThat(created.capabilities()).containsExactly("order.cancel", "order.read");
        assertThat(created.version()).isEqualTo(1L);
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.createdBy()).isEqualTo(OWNER);
        assertThat(created.holders()).isZero();

        assertThat(jobs.list(TENANT, false)).extracting(TenantJob::code).containsExactly(created.code());
        assertThat(jobs.get(TENANT, created.code()).name()).isEqualTo("Старший кассир");
        assertThat(jdbc.sql("SELECT is_platform_defined FROM iam.roles WHERE code = :code")
                        .param("code", created.code())
                        .query(Boolean.class)
                        .single())
                .as("a tenant's job is a tenant-defined row, which is what fk_grant_role and the policies key on")
                .isFalse();
    }

    @Test
    void creatingAJobIsAnAuditedSecurityFactWithTheReasonAndNoOneElsesData() {
        TenantJob created = cashier();

        assertThat(published).filteredOn(RoleChanged.class::isInstance).hasSize(1);
        var fact = jdbc.sql("""
                SELECT audit_class, actor_subject, reason, capability_used, change_document::text AS change_set
                  FROM audit.audit_events WHERE action_code = 'iam.role.created'
                """)
                .query((rs, n) -> new String[] {
                    rs.getString("audit_class"),
                    rs.getString("actor_subject"),
                    rs.getString("reason"),
                    rs.getString("capability_used"),
                    rs.getString("change_set")
                })
                .single();
        assertThat(fact[0]).isEqualTo("SECURITY");
        assertThat(fact[1]).isEqualTo(OWNER);
        assertThat(fact[2]).isEqualTo(REASON);
        assertThat(fact[3]).isEqualTo("iam.role.manage");
        assertThat(fact[4])
                .contains("Старший кассир")
                .contains("order.cancel")
                .contains(created.scopeType().name());
    }

    @Test
    void theGeneratedCodeIsNotACodeOwnedRoleAndNeverRepeats() {
        List<String> codes = IntStream.range(0, 5)
                .mapToObj(i -> jobs.create(
                                TENANT, draft("Job " + i, ScopeType.LOCATION, Capability.ORDER_READ), REASON, OWNER)
                        .code())
                .toList();

        assertThat(codes).doesNotHaveDuplicates().allSatisfy(code -> {
            assertThat(code).matches("job-[0-9a-f]{10}");
            assertThat(uz.horecaos.platform.iam.api.PlatformRole.find(code)).isEmpty();
        });
    }

    @Test
    void anAdministratorCannotPutACapabilityTheyDoNotHoldInAJob() {
        // customer.pii.export stays with the owner alone (PlatformRole). An administrator who
        // could write it into a job and give the job to herself would hold what her bundle withholds.
        Draft withPii = draft("Выгрузка", ScopeType.TENANT, Capability.ORDER_READ, Capability.CUSTOMER_PII_EXPORT);

        assertThatThrownBy(() -> jobs.create(TENANT, withPii, REASON, ADMIN))
                .isInstanceOfSatisfying(
                        AuthorizationService.AccessDeniedException.class,
                        refused -> assertThat(refused.capability()).isEqualTo(Capability.CUSTOMER_PII_EXPORT));

        assertThat(jobs.create(TENANT, withPii, REASON, OWNER).capabilities()).contains("customer.pii.export");
    }

    @Test
    void theDenyListHoldsAgainstTheOwnerHerself() {
        for (Capability denied : List.of(
                Capability.PLATFORM_ADMIN,
                Capability.IAM_GRANT_MANAGE,
                Capability.IAM_ROLE_MANAGE,
                Capability.PRINT_JOB_CLAIM,
                Capability.KIOSK_DEVICE_READ,
                Capability.COURIER_TRACK_REVEAL)) {
            assertThatThrownBy(() -> jobs.create(
                            TENANT, draft("Denied", ScopeType.TENANT, Capability.ORDER_READ, denied), REASON, OWNER))
                    .as(denied.code() + " must not be composable into a tenant job")
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(jobs.list(TENANT, true))
                .as("a refused job leaves nothing behind")
                .isEmpty();
    }

    @Test
    void theDenyListIsDerivedFromWhatHumanJobsCarryNotWrittenTwice() {
        assertThat(TenantJobRules.composable())
                .doesNotContain(
                        Capability.PLATFORM_ADMIN,
                        Capability.IAM_GRANT_MANAGE,
                        Capability.IAM_ROLE_MANAGE,
                        Capability.IAM_STAFF_MFA_RESET,
                        Capability.PRINT_JOB_CLAIM,
                        Capability.KIOSK_DEVICE_READ,
                        Capability.COMMERCIAL_PLAN_MANAGE);
        assertThat(TenantJobRules.composable()).contains(Capability.ORDER_CANCEL, Capability.REFUND_REQUEST);
        assertThat(TenantJobRules.catalogue())
                .as("the grid shows the owner's iam powers, locked, so it does not pretend she holds less")
                .contains(Capability.IAM_GRANT_MANAGE, Capability.IAM_ROLE_MANAGE);
    }

    @Test
    void anUnknownOrEmptyCapabilitySetIsRefusedRatherThanSavedAsLess() {
        assertThatThrownBy(() -> jobs.create(
                        TENANT,
                        new Draft("Ghost", ScopeType.TENANT, List.of("order.read", "no.such.thing"), null),
                        REASON,
                        OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(
                        () -> jobs.create(TENANT, new Draft("Empty", ScopeType.TENANT, List.of(), null), REASON, OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(jobs.list(TENANT, true)).isEmpty();
    }

    @Test
    void aNameIsOneTrimmedLineOfPlainText() {
        assertThat(jobs.create(
                                TENANT,
                                draft("  Старший   кассир \t ", ScopeType.LOCATION, Capability.ORDER_READ),
                                REASON,
                                OWNER)
                        .name())
                .isEqualTo("Старший кассир");

        for (String bad : List.of("", "   ", "x".repeat(81), "bell\u0007", "esc\u001b[31m")) {
            assertThatThrownBy(() ->
                            jobs.create(TENANT, draft(bad, ScopeType.LOCATION, Capability.ORDER_READ), REASON, OWNER))
                    .as("name %s", bad.length())
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(jobs.create(TENANT, draft("two\nlines", ScopeType.LOCATION, Capability.ORDER_READ), REASON, OWNER)
                        .name())
                .as("a line break is whitespace, collapsed to one space, never stored")
                .isEqualTo("two lines");
        assertThat(jobs.create(TENANT, draft("y".repeat(80), ScopeType.LOCATION, Capability.ORDER_READ), REASON, OWNER)
                        .name())
                .hasSize(80);
    }

    @Test
    void aChangeStatesItsReasonAndTheLevelIsAPlace() {
        assertThatThrownBy(() ->
                        jobs.create(TENANT, draft("No reason", ScopeType.LOCATION, Capability.ORDER_READ), " ", OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> jobs.create(
                        TENANT, draft("Platform", ScopeType.PLATFORM, Capability.ORDER_READ), REASON, OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> jobs.create(TENANT, draft("No level", null, Capability.ORDER_READ), REASON, OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void aBranchManagerAndACookCannotDefineJobsAtAll() {
        for (String subject : List.of(BRANCH_MANAGER, COOK)) {
            assertThatThrownBy(() -> jobs.create(
                            TENANT, draft("Mine", ScopeType.LOCATION, Capability.ORDER_READ), REASON, subject))
                    .isInstanceOfSatisfying(
                            AuthorizationService.AccessDeniedException.class,
                            refused -> assertThat(refused.capability()).isEqualTo(Capability.IAM_ROLE_MANAGE));
        }
    }

    @Test
    void twoActiveJobsCannotShareANameWhateverTheCase() {
        jobs.create(TENANT, draft("Старший кассир", ScopeType.LOCATION, Capability.ORDER_READ), REASON, OWNER);

        assertThatThrownBy(() -> jobs.create(
                        TENANT, draft("СТАРШИЙ КАССИР", ScopeType.BRAND, Capability.ORDER_CANCEL), REASON, OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        // The other company is a different namespace.
        assertThat(jobs.create(
                                OTHER_TENANT,
                                draft("Старший кассир", ScopeType.LOCATION, Capability.ORDER_READ),
                                REASON,
                                OTHER_OWNER)
                        .name())
                .isEqualTo("Старший кассир");
    }

    @Test
    void aCompanyHasAtMostTwentyActiveJobsAndClosingOneMakesRoom() {
        for (int i = 0; i < TenantJobRules.MAX_ACTIVE_JOBS; i++) {
            jobs.create(TENANT, draft("Job " + i, ScopeType.LOCATION, Capability.ORDER_READ), REASON, OWNER);
        }
        assertThatThrownBy(() -> jobs.create(
                        TENANT, draft("One too many", ScopeType.LOCATION, Capability.ORDER_READ), REASON, OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        TenantJob first = jobs.list(TENANT, false).getFirst();
        jobs.retire(TENANT, first.code(), first.version(), "Not used any more", OWNER);

        assertThat(jobs.create(TENANT, draft("One too many", ScopeType.LOCATION, Capability.ORDER_READ), REASON, OWNER))
                .isNotNull();
        assertThat(jobs.list(TENANT, false)).hasSize(TenantJobRules.MAX_ACTIVE_JOBS);
        assertThat(jobs.list(TENANT, true)).hasSize(TenantJobRules.MAX_ACTIVE_JOBS + 1);
    }

    // ------------------------------------------------------------------- edit

    @Test
    void anEditReplacesNameAndCapabilitiesBumpsTheVersionAndWritesADiff() {
        TenantJob created = cashier();

        TenantJob edited = jobs.update(
                TENANT,
                created.code(),
                new Draft("Главный кассир", null, List.of("order.read", "refund.request"), null),
                created.version(),
                "Refunds moved to the senior cashier",
                OWNER);

        assertThat(edited.version()).isEqualTo(2L);
        assertThat(edited.name()).isEqualTo("Главный кассир");
        assertThat(edited.capabilities()).containsExactly("order.read", "refund.request");
        assertThat(edited.updatedBy()).isEqualTo(OWNER);

        String diff = jdbc.sql(
                        "SELECT change_document::text FROM audit.audit_events WHERE action_code = 'iam.role.changed'")
                .query(String.class)
                .single();
        assertThat(diff)
                .contains("Старший кассир")
                .contains("Главный кассир")
                .contains("refund.request")
                .contains("order.cancel");
    }

    @Test
    void theLevelDoesNotMoveOnEditAndTheCodeNever() {
        TenantJob created = cashier();

        TenantJob edited = jobs.update(
                TENANT,
                created.code(),
                new Draft("Старший кассир 2", ScopeType.TENANT, List.of("order.read"), null),
                created.version(),
                REASON,
                OWNER);

        assertThat(edited.scopeType()).as("copy the job to move it").isEqualTo(ScopeType.LOCATION);
        assertThat(edited.code()).isEqualTo(created.code());
    }

    @Test
    void anEditFromAStaleVersionIsRefusedAndChangesNothing() {
        TenantJob created = cashier();
        jobs.update(TENANT, created.code(), new Draft("Renamed", null, List.of("order.read"), null), 1L, REASON, OWNER);

        assertThatThrownBy(() -> jobs.update(
                        TENANT,
                        created.code(),
                        new Draft("Lost update", null, List.of("order.read"), null),
                        1L,
                        REASON,
                        ADMIN))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(jobs.get(TENANT, created.code()).name()).isEqualTo("Renamed");
    }

    @Test
    void anEditIsLiveForTheHolderAtTheNextRequest() {
        TenantJob created = cashier();
        ResourceScope branch = ResourceScope.location(TENANT, BRAND, LOCATION);
        grants.grant(
                new GrantManagementService.GrantCommand("cashier-1", created.code(), branch, "New hire", null), OWNER);
        assertThat(authorization.has("cashier-1", Capability.ORDER_CANCEL, branch))
                .isTrue();
        assertThat(authorization.has("cashier-1", Capability.REFUND_REQUEST, branch))
                .isFalse();

        jobs.update(
                TENANT,
                created.code(),
                new Draft(created.name(), null, List.of("order.read", "refund.request"), null),
                created.version(),
                "Refunds, not cancellations",
                OWNER);

        assertThat(authorization.has("cashier-1", Capability.ORDER_CANCEL, branch))
                .as("a capability taken out of the job is gone for its holder at once")
                .isFalse();
        assertThat(authorization.has("cashier-1", Capability.REFUND_REQUEST, branch))
                .as("and one put in is there at once")
                .isTrue();
    }

    @Test
    void anEditorMustHoldTheWholeResultingSetNotJustWhatSheAdds() {
        TenantJob withPii = jobs.create(
                TENANT,
                draft("Выгрузка", ScopeType.TENANT, Capability.ORDER_READ, Capability.CUSTOMER_PII_EXPORT),
                REASON,
                OWNER);

        assertThatThrownBy(() -> jobs.update(
                        TENANT,
                        withPii.code(),
                        new Draft("Выгрузка 2", null, List.of("order.read", "customer.pii.export"), null),
                        withPii.version(),
                        REASON,
                        ADMIN))
                .as("an administrator cannot re-save what the owner wrote and she does not hold")
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);

        TenantJob trimmed = jobs.update(
                TENANT,
                withPii.code(),
                new Draft("Выгрузка 2", null, List.of("order.read"), null),
                withPii.version(),
                REASON,
                ADMIN);
        assertThat(trimmed.capabilities()).containsExactly("order.read");
    }

    @Test
    void anEditThatChangesNothingBumpsNoVersionAndWritesNoFact() {
        TenantJob created = cashier();

        TenantJob same = jobs.update(
                TENANT,
                created.code(),
                new Draft("  Старший кассир ", null, List.of("order.cancel", "order.read"), null),
                created.version(),
                REASON,
                OWNER);

        assertThat(same.version()).isEqualTo(created.version());
        assertThat(published)
                .filteredOn(e -> e instanceof RoleChanged change && change.change() == RoleChanged.Change.EDITED)
                .isEmpty();
    }

    // ------------------------------------------------------------------ retire

    @Test
    void aJobWithAHolderCannotBeClosedAndOneWithoutCanAndItsNameIsFreeAgain() {
        TenantJob created = cashier();
        ResourceScope branch = ResourceScope.location(TENANT, BRAND, LOCATION);
        var grantId = grants.grant(
                new GrantManagementService.GrantCommand("cashier-1", created.code(), branch, "New hire", null), OWNER);

        assertThatThrownBy(() -> jobs.retire(TENANT, created.code(), created.version(), "Closing", OWNER))
                .isInstanceOfSatisfying(ApiException.class, problem -> {
                    assertThat(problem.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(problem.getMessage()).contains("1 person");
                });
        assertThat(jobs.get(TENANT, created.code()).status()).isEqualTo("ACTIVE");

        grants.revoke(TENANT, grantId, OWNER, "Left the company");
        TenantJob closed = jobs.retire(TENANT, created.code(), created.version(), "Not needed any more", OWNER);

        assertThat(closed.status()).isEqualTo("RETIRED");
        assertThat(closed.retiredAt()).isEqualTo(CLOCK_INSTANT);
        assertThat(jobs.list(TENANT, false)).isEmpty();
        assertThat(jobs.list(TENANT, true)).extracting(TenantJob::code).containsExactly(created.code());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'iam.role.retired'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);

        assertThat(cashier().name()).as("a closed job's name is free again").isEqualTo("Старший кассир");
    }

    @Test
    void aClosedJobIsNotEditedNotClosedTwiceAndNotGivenAway() {
        TenantJob created = cashier();
        TenantJob closed = jobs.retire(TENANT, created.code(), created.version(), "Closing", OWNER);

        assertThatThrownBy(() -> jobs.update(
                        TENANT,
                        created.code(),
                        new Draft("Back", null, List.of("order.read"), null),
                        closed.version(),
                        REASON,
                        OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> jobs.retire(TENANT, created.code(), closed.version(), "Again", OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> grants.grant(
                        new GrantManagementService.GrantCommand(
                                "cashier-1",
                                created.code(),
                                ResourceScope.location(TENANT, BRAND, LOCATION),
                                "Late",
                                null),
                        OWNER))
                .isInstanceOf(GrantManagementService.NoSuchRoleException.class);
    }

    @Test
    void aRetireFromAStaleVersionIsRefused() {
        TenantJob created = cashier();
        jobs.update(TENANT, created.code(), new Draft("Renamed", null, List.of("order.read"), null), 1L, REASON, OWNER);

        assertThatThrownBy(() -> jobs.retire(TENANT, created.code(), 1L, "Closing", OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
    }

    // -------------------------------------------------------------- isolation

    @Test
    void anotherCompanyCanNeitherSeeNorEditNorCloseAJobAndGetsTheAnswerForNoSuchJob() {
        TenantJob created = cashier();

        assertThat(jobs.list(OTHER_TENANT, true)).isEmpty();
        assertThatThrownBy(() -> jobs.get(OTHER_TENANT, created.code()))
                .isInstanceOf(TenantRoleService.NoSuchJobException.class);
        assertThatThrownBy(() -> jobs.update(
                        OTHER_TENANT,
                        created.code(),
                        new Draft("Stolen", null, List.of("order.read"), null),
                        created.version(),
                        REASON,
                        OTHER_OWNER))
                .isInstanceOf(TenantRoleService.NoSuchJobException.class);
        assertThatThrownBy(() -> jobs.retire(OTHER_TENANT, created.code(), created.version(), "Closing", OTHER_OWNER))
                .isInstanceOf(TenantRoleService.NoSuchJobException.class);
        assertThatThrownBy(() -> grants.grant(
                        new GrantManagementService.GrantCommand(
                                "someone", created.code(), ResourceScope.tenant(OTHER_TENANT), "Borrowed", null),
                        OTHER_OWNER))
                .as("a grant in another company cannot name this company's job (V0089, now with a creation path)")
                .isInstanceOf(GrantManagementService.NoSuchRoleException.class);

        assertThat(jobs.get(TENANT, created.code()).name()).isEqualTo("Старший кассир");
    }

    @Test
    void aCodeOwnedRoleIsNotAJobACompanyDefinedWhateverItIsCalled() {
        assertThatThrownBy(() -> jobs.get(TENANT, "tenant-owner"))
                .isInstanceOf(TenantRoleService.NoSuchJobException.class);
        assertThatThrownBy(() -> jobs.update(
                        TENANT,
                        "tenant-owner",
                        new Draft("Mine now", null, List.of("order.read"), null),
                        0L,
                        REASON,
                        OWNER))
                .isInstanceOf(TenantRoleService.NoSuchJobException.class);
        assertThatThrownBy(() -> jobs.retire(TENANT, "location-staff", 0L, "Closing", OWNER))
                .isInstanceOf(TenantRoleService.NoSuchJobException.class);
    }

    // -------------------------------------------------------------- catalogue

    @Test
    void theCatalogueTellsAnAdministratorWhatSheHoldsAndWhatAJobMayCarry() {
        var entries = jobs.catalogue(TENANT, ADMIN);
        var byCode = entries.stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.capability().code(), entry -> entry));

        assertThat(entry(byCode, "order.cancel").held()).isTrue();
        assertThat(entry(byCode, "order.cancel").composable()).isTrue();
        assertThat(entry(byCode, "customer.pii.export").held()).as("owner-only").isFalse();
        assertThat(entry(byCode, "customer.pii.export").composable()).isTrue();
        assertThat(entry(byCode, "iam.grant.manage").held()).isTrue();
        assertThat(entry(byCode, "iam.grant.manage").composable())
                .as("held but never composable")
                .isFalse();
        assertThat(byCode)
                .as("a restaurant is not shown HorecaOS staff's powers")
                .doesNotContainKeys("platform.admin", "commercial.plan.manage", "print.job.claim");
    }

    private static TenantRoleService.CatalogueEntry entry(
            java.util.Map<String, TenantRoleService.CatalogueEntry> byCode, String code) {
        return java.util.Objects.requireNonNull(byCode.get(code), code);
    }
}
