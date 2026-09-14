package uz.horecaos.platform.tenancy.application.invitations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.application.GrantAuditListener;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStaffInvitationStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0116, staff-and-access.md §4, gap map row 9.1a: the invite, accept,
 * resend and revoke flows against the migrated schema.
 *
 * <p>Wired the same way {@code GrantManagementServiceTests} wires {@link
 * GrantManagementService} -- plain {@code new}, a real Postgres, no Spring
 * proxy -- because {@link StaffInvitationService} itself never relies on a
 * proxied {@code @Transactional}; it uses {@link TransactionTemplate}
 * explicitly, the same shape {@link OwnerInvitationService} uses, for exactly
 * the property {@link #keycloakCallsNeverHoldAConnection} exists to check.
 */
class StaffInvitationFlowTests {

    private static final UUID TENANT = UUID.fromString("018f9a20-1000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9a20-1000-7000-8000-0000000000a2");
    private static final String ORGANIZATION_ID = "org-legit-123";
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-09-14T09:00:00Z");

    private static final String OWNER = "invite-owner-1";
    private static final String BRAND_MANAGER = "invite-brand-manager-1";

    private static TestDatabase.Handle db;
    private static DataSource dataSource;

    private JdbcClient jdbc;
    private JdbcAuthorizationService authorization;
    private JdbcStaffInvitationStore store;
    private FakeStaffAccounts accounts;
    private FakeOrganizations organizations;
    private FakeMailer mailer;
    private StaffInvitationService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
        dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE tenant.staff_invitations").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
        authorization =
                new JdbcAuthorizationService(
                        jdbc,
                        clock,
                        () -> new AuthenticatedActor("no-request-actor-in-fixture", Set.of(), Map.of()),
                        tenantId -> uz.horecaos.platform.iam.api.TenantAvailability.OPERATING) {
                    @Override
                    public void evictGrants(String subject, @Nullable UUID tenantId) {
                        // no cache in this fixture
                    }
                };
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        // Stands in for Spring's BEFORE_COMMIT dispatch (GrantManagementServiceTests'
        // own setUp does the same): a real ApplicationEventPublisher would
        // route GrantChanged to this listener, which is what actually writes
        // 'iam.grant.granted'/'iam.grant.revoked' -- resendAndRevokeWorkTogether
        // reads that fact back.
        GrantAuditListener auditListener = new GrantAuditListener(audit);
        GrantManagementService grants = new GrantManagementService(
                jdbc,
                authorization,
                authorization,
                event -> {
                    if (event instanceof uz.horecaos.platform.iam.api.GrantChanged change) {
                        auditListener.onGrantChanged(change);
                    }
                },
                clock);
        store = new JdbcStaffInvitationStore(jdbc);
        accounts = new FakeStaffAccounts();
        organizations = new FakeOrganizations();
        mailer = new FakeMailer();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        service = new StaffInvitationService(
                store,
                accounts,
                organizations,
                grants,
                authorization,
                mailer,
                audit,
                transactions,
                clock,
                "http://localhost:4200");

        new RoleRegistrySynchronizer(jdbc).synchronize();
        insertTenant();
        insertGrant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        insertGrant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
    }

    @Test
    @DisplayName("a manager holding only a BRAND grant is refused a TENANT-scope invitation, and nothing is created")
    void aBrandScopedManagerIsRefusedATenantScopeInvitation() {
        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901234567",
                null,
                "location-staff",
                ResourceScope.tenant(TENANT),
                "new hire",
                null,
                "ru");

        assertThatThrownBy(() -> service.invite(TENANT, command, ActorRef.user(BRAND_MANAGER, null), "corr-refused"))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);

        assertThat(accounts.created)
                .as("the account is created only after the actor is confirmed to hold the scope")
                .isEmpty();
        assertThat(store.outstandingForTenant(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("a duplicate phone already in this tenant's own organization is refused with its subject id")
    void aDuplicatePhoneNamesTheExistingSubject() {
        accounts.seedExistingPhone("+998901234567", "already-here-1");
        // Confirmed a member of THIS tenant's own organization -- the fact
        // that lets the subject id be disclosed at all (see
        // aDuplicatePhoneOutsideTheTenantsOrganizationIsRefusedWithoutASubject
        // for the cross-tenant case, where it must not be).
        organizations.addMember(ORGANIZATION_ID, "already-here-1");
        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901234567",
                null,
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");

        ApiException failure = (ApiException)
                catchThrowable(() -> service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-dup"));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(failure.properties()).containsEntry("existingSubjectId", "already-here-1");
        assertThat(failure.getMessage()).as("the message names no phone number").doesNotContain("998901234567");
    }

    /**
     * S02: {@code findByPhone} searches the whole shared Keycloak realm,
     * with no tenant filter -- so a phone belonging to a colleague at an
     * unrelated tenant must never be confirmed to exist, or named by
     * subject id, to a manager who has only proven authority over their own
     * tenant (tenant isolation is the platform's primary security boundary).
     */
    @Test
    @DisplayName("a duplicate phone belonging to a DIFFERENT tenant's organization is refused without a subject id")
    void aDuplicatePhoneOutsideTheTenantsOrganizationIsRefusedWithoutASubject() {
        accounts.seedExistingPhone("+998901234567", "someone-elses-staff-1");
        // Deliberately NOT added as a member of ORGANIZATION_ID: this subject
        // belongs to a different tenant's own organization.
        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901234567",
                null,
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");

        ApiException failure = (ApiException)
                catchThrowable(() -> service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-cross-tenant"));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(failure.properties())
                .as("a cross-tenant match's subject id must never be disclosed")
                .doesNotContainKey("existingSubjectId");
        assertThat(failure.getMessage()).as("the message names no phone number").doesNotContain("998901234567");
        assertThat(accounts.created)
                .as("no account is created for an already-taken phone")
                .isEmpty();
    }

    /**
     * S01: two invitations for the same phone can both pass the
     * synchronous {@code findByPhone} pre-check before either calls {@code
     * create} -- Keycloak's own username-uniqueness constraint is the real
     * arbiter, and the losing side must get the identical, tenant-scoped
     * {@code RESOURCE_CONFLICT} the winner's pre-check would have thrown,
     * never an unhandled 500.
     */
    @Test
    @DisplayName("the losing side of a create-time race gets the same tenant-scoped conflict as the pre-check")
    void aRaceOnCreateStillGetsTheTenantScopedConflict() {
        accounts.loseRaceOn("+998901234567", "race-winner-1");
        organizations.addMember(ORGANIZATION_ID, "race-winner-1");
        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901234567",
                null,
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");

        ApiException failure = (ApiException)
                catchThrowable(() -> service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-race"));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(failure.properties()).containsEntry("existingSubjectId", "race-winner-1");
        assertThat(accounts.created)
                .as("the loser's own account is never left behind")
                .isEmpty();
    }

    @Test
    @DisplayName("accept sets a password and the grant is effective in JdbcAuthorizationService.viewFor")
    void acceptSetsThePasswordAndTheGrantIsEffective() {
        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901234567",
                null,
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");

        StaffInvitationService.Created created =
                service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-invite");

        assertThat(created.principalSubject()).isNotBlank();
        assertThat(authorization.has(
                        created.principalSubject(),
                        Capability.ORDER_APPROVE,
                        ResourceScope.location(TENANT, BRAND, BRAND)))
                .as("the grant took effect at invite time, before acceptance")
                .isTrue();
        assertThat(accounts.find(created.principalSubject()).orElseThrow().hasPassword())
                .isFalse();

        String token = tokenFrom(created.inviteLink());
        StaffInvitationService.Accepted accepted =
                service.accept(token, "Aziza", "Karimova", "a-long-enough-pass", "corr-accept");

        assertThat(accepted.signInName())
                .isEqualTo(
                        accounts.find(created.principalSubject()).orElseThrow().username());
        assertThat(accounts.find(created.principalSubject()).orElseThrow().hasPassword())
                .as("completeSetup ran")
                .isTrue();

        CapabilityView view = authorization.viewFor(created.principalSubject(), TENANT);
        assertThat(view.capabilities())
                .as("the grant is effective in the exact view a signed-in session reads")
                .contains(Capability.ORDER_APPROVE);
    }

    @Test
    @DisplayName(
            "no name, phone, email or token reaches an audit fact, and the phone stays out of the invited-by field too")
    void noPiiOrTokenReachesAnAuditFact() {
        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998907654321",
                "aziza@example.uz",
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");

        StaffInvitationService.Created created =
                service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-audit");
        String token = tokenFrom(created.inviteLink());
        service.accept(token, "Aziza", "Karimova", "a-long-enough-pass", "corr-audit-accept");

        List<String> everything = new ArrayList<>();
        everything.addAll(jdbc.sql(
                        "SELECT change_document::text FROM audit.audit_events WHERE action_code LIKE 'tenant.staff_invitation%'")
                .query(String.class)
                .list());
        everything.addAll(
                jdbc.sql("SELECT reason FROM audit.audit_events WHERE action_code LIKE 'tenant.staff_invitation%'")
                        .query(String.class)
                        .list());
        everything.addAll(jdbc.sql(
                        "SELECT actor_subject FROM audit.audit_events WHERE action_code LIKE 'tenant.staff_invitation%'")
                .query(String.class)
                .list());

        assertThat(everything)
                .as("no audit fact carries the person's name, phone, email or the raw token")
                .noneMatch(text -> text.contains("Aziza"))
                .noneMatch(text -> text.contains("Karimova"))
                .noneMatch(text -> text.contains("998907654321"))
                .noneMatch(text -> text.contains("aziza@example.uz"))
                .noneMatch(text -> text.contains(token));

        assertThat(jdbc.sql("SELECT count(*) FROM tenant.staff_invitations WHERE token_hash = :hash")
                        .param("hash", StaffInvitationService.hash(token))
                        .query(Long.class)
                        .single())
                .as("only the hash is stored, and accepting spends it (token_hash goes to NULL)")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("Keycloak and the mailer are never called with a database connection checked out")
    void keycloakCallsNeverHoldAConnection() {
        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901112233",
                "aziza2@example.uz",
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");

        service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-boundary");

        assertThat(accounts.transactionActiveDuringCreate)
                .as("StaffAccounts#create never runs inside a transaction")
                .containsExactly(false);
        assertThat(organizations.transactionActiveDuringEnsureMembership)
                .as("OrganizationProvisioner#ensureMembership never runs inside a transaction")
                .containsExactly(false);
        assertThat(mailer.transactionActiveDuringSend)
                .as("PlatformMailer#send never runs inside a transaction")
                .containsExactly(false);
    }

    @Test
    @DisplayName("resend issues a fresh link and revoke cancels the invitation and the grant, one audit fact each")
    void resendAndRevokeWorkTogether() {
        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901239876",
                null,
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");
        StaffInvitationService.Created created = service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-rr");

        String resent = service.resend(
                TENANT, created.invitationId(), ActorRef.user(OWNER, null), "still onboarding", "corr-resend");
        assertThat(tokenFrom(resent)).isNotEqualTo(tokenFrom(created.inviteLink()));
        assertThat(catchThrowable(() -> service.accept(
                        tokenFrom(created.inviteLink()), "Aziza", "Karimova", "a-long-enough-pass", "corr-old-token")))
                .as("the earlier link stopped working")
                .isInstanceOf(ApiException.class);

        service.revoke(
                TENANT, created.invitationId(), ActorRef.user(OWNER, null), "left before starting", "corr-revoke");

        assertThat(authorization.has(
                        created.principalSubject(),
                        Capability.ORDER_APPROVE,
                        ResourceScope.location(TENANT, BRAND, BRAND)))
                .as("revoke also revoked the grant")
                .isFalse();
        assertThat(jdbc.sql("SELECT status FROM tenant.staff_invitations WHERE id = :id")
                        .param("id", created.invitationId())
                        .query(String.class)
                        .single())
                .isEqualTo("CANCELLED");
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'tenant.staff_invitation.cancelled'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'iam.grant.revoked'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    /**
     * S04: {@code aBrandScopedManagerIsRefusedATenantScopeInvitation} above
     * proves only that {@code BRAND_MANAGER} lacks {@code IAM_GRANT_MANAGE}
     * everywhere -- it would pass identically if {@link StaffInvitationService#invite}'s
     * scope-aware {@code authorization.require} were replaced by any
     * fixed-scope or scope-blind check. No {@link PlatformRole} grants
     * {@code IAM_GRANT_MANAGE} below TENANT scope, so this fixture inserts a
     * raw grant naming {@code TENANT_ADMIN}'s role id -- bypassing the
     * normal catalog wiring, the only way to put the capability at BRAND
     * scope at all -- to prove actual scope narrowing: refused reaching
     * upward to TENANT, allowed at the actor's own scope.
     */
    @Test
    @DisplayName(
            "an actor genuinely holding IAM_GRANT_MANAGE only at BRAND scope is refused upward and allowed at its own scope")
    void aGenuinelyBrandScopedGrantManagerIsRefusedUpwardAndAllowedAtItsOwnScope() {
        String actor = "brand-scoped-grant-manager-1";
        insertGrant(actor, PlatformRole.TENANT_ADMIN, "BRAND", BRAND);

        StaffInvitationService.InviteCommand tenantScopeAttempt = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901112200",
                null,
                "location-staff",
                ResourceScope.tenant(TENANT),
                "new hire",
                null,
                "ru");
        assertThatThrownBy(
                        () -> service.invite(TENANT, tenantScopeAttempt, ActorRef.user(actor, null), "corr-narrow-up"))
                .as("holding the capability only at BRAND scope must not reach upward to TENANT")
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
        assertThat(accounts.created)
                .as("nothing is created for the refused attempt")
                .isEmpty();

        StaffInvitationService.InviteCommand ownScopeAttempt = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901112200",
                null,
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");
        StaffInvitationService.Created created =
                service.invite(TENANT, ownScopeAttempt, ActorRef.user(actor, null), "corr-narrow-own");
        assertThat(created.principalSubject())
                .as("the same actor is allowed at a scope its own BRAND grant actually covers")
                .isNotBlank();
    }

    /**
     * S03: {@code grants.grant}'s finer {@code requireGrantable} check --
     * whether the actor's own capabilities cover every capability the
     * chosen job carries -- can still refuse after the Keycloak account and
     * organization membership already exist. Before this test, that left a
     * permanent orphan: {@code accounts.findByPhone}'s duplicate check would
     * find it forever, blocking every future invitation for that phone, by
     * anybody, even a correctly-authorized actor.
     */
    @Test
    @DisplayName("a refused grant cleans up the orphaned account, so the same phone can be invited again")
    void aRefusedGrantDoesNotPermanentlyBlockThePhone() {
        // A tenant-defined role holding IAM_GRANT_MANAGE and nothing else:
        // no PlatformRole combines the coarse capability with a narrow
        // operational set, so requireGrantable needs a role built for
        // exactly this refusal, the same way GrantManagementServiceTests
        // builds one of its own for the identical reason.
        UUID narrowRoleId = UUID.fromString("018f9a20-1000-7000-8000-0000000000e1");
        insertCustomRole(narrowRoleId, TENANT, "grant-manager-only");
        jdbc.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:roleId, :capability)")
                .param("roleId", narrowRoleId)
                .param("capability", Capability.IAM_GRANT_MANAGE.code())
                .update();
        String narrowActor = "narrow-grant-manager-1";
        jdbc.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform,
                             scope_type, scope_id, status, granted_by, reason, valid_from)
                        VALUES (:id, :tenantId, :subject, :roleId, false, 'BRAND', :scopeId,
                                'ACTIVE', 'fixture', 'fixture', :validFrom)
                        """)
                .param("id", UUID.randomUUID())
                .param("validFrom", CLOCK_INSTANT.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("tenantId", TENANT)
                .param("subject", narrowActor)
                .param("roleId", narrowRoleId)
                .param("scopeId", BRAND)
                .update();

        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901112299",
                null,
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");

        assertThatThrownBy(
                        () -> service.invite(TENANT, command, ActorRef.user(narrowActor, null), "corr-orphan-refused"))
                .as("the actor holds IAM_GRANT_MANAGE but not every capability location-staff carries")
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);

        assertThat(accounts.created)
                .as("the account was created before the grant was refused")
                .hasSize(1);
        assertThat(accounts.deleted)
                .as("the orphaned account is cleaned up rather than left behind")
                .isEqualTo(accounts.created);
        assertThat(accounts.findByPhone("+998901112299"))
                .as("the phone is free again once the orphan is cleaned up")
                .isEmpty();

        StaffInvitationService.Created retried =
                service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-orphan-retry");
        assertThat(retried.principalSubject())
                .as("a correctly-authorized actor can invite the same phone afterwards")
                .isNotBlank();
    }

    /**
     * S05: {@code JdbcStaffInvitationStore#byId} filters by {@code (id,
     * tenant_id)} correctly by inspection, but nothing exercised it against
     * a second tenant before this test -- an invitation created under one
     * tenant must be invisible, never actionable, from another.
     */
    @Test
    @DisplayName("an invitation is invisible and unactionable from a different tenant")
    void anInvitationIsIsolatedFromADifferentTenant() {
        // No fixture row for this tenant is needed: store.byId's WHERE
        // clause filters on tenant_id alone, so any tenant id this
        // invitation was not created under proves the same isolation --
        // this is simply one nobody at TENANT can reach it from.
        UUID tenantB = UUID.fromString("018f9a20-1000-7000-8000-0000000000b1");
        String ownerB = "invite-owner-b-1";

        StaffInvitationService.InviteCommand command = new StaffInvitationService.InviteCommand(
                "Aziza",
                "Karimova",
                "+998901112277",
                null,
                "location-staff",
                ResourceScope.location(TENANT, BRAND, BRAND),
                "new hire",
                null,
                "ru");
        StaffInvitationService.Created created =
                service.invite(TENANT, command, ActorRef.user(OWNER, null), "corr-isolation-invite");

        assertThat(store.byId(tenantB, created.invitationId()))
                .as("a store lookup under a different tenant id finds nothing")
                .isEmpty();
        assertThatThrownBy(() -> service.resend(
                        tenantB,
                        created.invitationId(),
                        ActorRef.user(ownerB, null),
                        "trying another tenant's id",
                        "corr-isolation-resend"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.revoke(
                        tenantB,
                        created.invitationId(),
                        ActorRef.user(ownerB, null),
                        "trying another tenant's id",
                        "corr-isolation-revoke"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        assertThat(jdbc.sql("SELECT status FROM tenant.staff_invitations WHERE id = :id")
                        .param("id", created.invitationId())
                        .query(String.class)
                        .single())
                .as("the invitation under its own tenant is left completely unaffected")
                .isEqualTo("QUEUED");
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        return org.assertj.core.api.Assertions.catchThrowable(callable);
    }

    private static String tokenFrom(String inviteLink) {
        return inviteLink.substring(inviteLink.indexOf("token=") + "token=".length());
    }

    private void insertTenant() {
        jdbc.sql("""
                        INSERT INTO tenant.tenants
                            (id, slug, legal_name, display_name, default_currency, default_timezone,
                             status, keycloak_organization_id, version)
                        VALUES (:id, 'invite-flow-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                                'ACTIVE', :orgId, 0)
                        """).param("id", TENANT).param("orgId", ORGANIZATION_ID).update();
        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                        VALUES (:id, :tenantId, 'BRAND_A', 'brand-a', 'Brand A', 'ACTIVE', 0)
                        """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                        INSERT INTO tenant.locations
                            (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                        VALUES (:id, :tenantId, :brandId, 'LOC_A', 'loc-a', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                        """)
                .param("id", BRAND)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private void insertGrant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform,
                             scope_type, scope_id, status, granted_by, reason, valid_from)
                        VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                                'ACTIVE', 'fixture', 'fixture', :validFrom)
                        """)
                .param("id", UUID.randomUUID())
                .param("validFrom", CLOCK_INSTANT.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .update();
    }

    /** A tenant-defined role with no capabilities of its own yet -- the caller adds them via {@code iam.role_capabilities}. */
    private void insertCustomRole(UUID roleId, UUID tenantId, String code) {
        jdbc.sql("""
                        INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                        VALUES (:id, :tenantId, :code, :code, 'TENANT', 'ACTIVE', false)
                        """)
                .param("id", roleId)
                .param("tenantId", tenantId)
                .param("code", code)
                .update();
    }

    /** In-memory: no real Keycloak. Records whether a transaction was active on each external call. */
    private static final class FakeStaffAccounts implements StaffAccounts {

        private final Map<String, StaffAccount> byId = new HashMap<>();
        private final Map<String, String> phoneToSubject = new HashMap<>();

        /** {@code phone (normalised) -> winning subject id} -- see {@link #loseRaceOn}. */
        private final Map<String, String> pendingRaceWinners = new HashMap<>();

        final List<String> created = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<Boolean> transactionActiveDuringCreate = new ArrayList<>();

        void seedExistingPhone(String phone, String subjectId) {
            phoneToSubject.put(KeycloakStaffAccountsPhone.normalize(phone), subjectId);
            byId.put(
                    subjectId,
                    new StaffAccount(subjectId, null, false, false, KeycloakStaffAccountsPhone.normalize(phone)));
        }

        /**
         * Makes the next {@link #create} for this phone throw {@link
         * StaffAccountAlreadyExistsException} once, simulating the losing
         * side of a check-then-act race past a caller's own {@code
         * findByPhone} pre-check: the winning account is invisible to {@link
         * #findByPhone} until {@link #create} is actually called for this
         * phone -- exactly when Keycloak's own username-uniqueness
         * constraint would first be discovered -- and only then becomes
         * visible, the same way a concurrent request's write would.
         */
        void loseRaceOn(String phone, String winningSubjectId) {
            pendingRaceWinners.put(KeycloakStaffAccountsPhone.normalize(phone), winningSubjectId);
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            return Optional.ofNullable(byId.get(subjectId));
        }

        @Override
        public StaffAccount create(String firstName, String lastName, String phone, @Nullable String email) {
            transactionActiveDuringCreate.add(TransactionSynchronizationManager.isActualTransactionActive());
            String username = KeycloakStaffAccountsPhone.normalize(phone);
            String winner = pendingRaceWinners.remove(username);
            if (winner != null) {
                byId.put(winner, new StaffAccount(winner, null, false, false, username));
                phoneToSubject.put(username, winner);
                throw new StaffAccountAlreadyExistsException("lost the race for " + username);
            }
            String subjectId = "staff-" + UUID.randomUUID();
            StaffAccount account = new StaffAccount(subjectId, email, false, false, username);
            byId.put(subjectId, account);
            phoneToSubject.put(username, subjectId);
            created.add(subjectId);
            return account;
        }

        @Override
        public void delete(String subjectId) {
            byId.remove(subjectId);
            phoneToSubject.values().removeIf(id -> id.equals(subjectId));
            deleted.add(subjectId);
        }

        @Override
        public Optional<StaffAccount> findByPhone(String phone) {
            String subjectId = phoneToSubject.get(KeycloakStaffAccountsPhone.normalize(phone));
            return subjectId == null ? Optional.empty() : find(subjectId);
        }

        @Override
        public void completeSetup(String subjectId, String firstName, String lastName, String password) {
            StaffAccount current = byId.get(subjectId);
            if (current != null) {
                byId.put(
                        subjectId,
                        new StaffAccount(current.subjectId(), current.email(), true, true, current.username()));
            }
        }

        @Override
        public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
            throw new UnsupportedOperationException("not part of this fixture");
        }

        @Override
        public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
            throw new UnsupportedOperationException("not part of this fixture");
        }

        @Override
        public void setPassword(String subjectId, String password) {
            throw new UnsupportedOperationException("not part of this fixture");
        }

        @Override
        public void logoutEverywhere(String subjectId) {
            throw new UnsupportedOperationException("not part of this fixture");
        }
    }

    /** Digits-only normalisation, mirroring {@code KeycloakStaffAccounts#normalizePhone} for this fixture's own lookups. */
    private static final class KeycloakStaffAccountsPhone {
        static String normalize(String phone) {
            return phone.replaceAll("[^0-9]", "");
        }
    }

    private static final class FakeOrganizations implements OrganizationProvisioner {

        final List<Boolean> transactionActiveDuringEnsureMembership = new ArrayList<>();

        /** {@code organizationId -> member subject ids}, for {@link #isMember}. */
        private final java.util.Map<String, java.util.Set<String>> members = new HashMap<>();

        /** Seeds a subject as an already-confirmed member of this organization (never through {@link #ensureMembership}). */
        void addMember(String organizationId, String subjectId) {
            members.computeIfAbsent(organizationId, ignored -> new java.util.HashSet<>())
                    .add(subjectId);
        }

        @Override
        public OrganizationRef ensureOrganization(EnsureOrganization command) {
            throw new UnsupportedOperationException("not part of this fixture");
        }

        @Override
        public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
            throw new UnsupportedOperationException("not part of this fixture");
        }

        @Override
        public MembershipRef ensureMembership(EnsureMembership command) {
            transactionActiveDuringEnsureMembership.add(TransactionSynchronizationManager.isActualTransactionActive());
            String subjectId = java.util.Objects.requireNonNull(
                    command.existingSubjectId(), "this fixture is only called with an existing subject");
            addMember(command.organizationId(), subjectId);
            return new MembershipRef(command.organizationId(), subjectId, false);
        }

        @Override
        public void setOrganizationEnabled(String organizationId, boolean enabled) {
            throw new UnsupportedOperationException("not part of this fixture");
        }

        @Override
        public boolean isMember(String organizationId, String subjectId) {
            return members.getOrDefault(organizationId, java.util.Set.of()).contains(subjectId);
        }
    }

    private static final class FakeMailer implements PlatformMailer {

        final List<Boolean> transactionActiveDuringSend = new ArrayList<>();
        final List<OutgoingMail> sent = new ArrayList<>();

        @Override
        public MailOutcome send(OutgoingMail mail) {
            transactionActiveDuringSend.add(TransactionSynchronizationManager.isActualTransactionActive());
            sent.add(mail);
            return new MailOutcome.Sent();
        }

        @Override
        public boolean configured() {
            return true;
        }
    }
}
