package uz.horecaos.platform.tenancy.application.invitations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.iam.api.grants.GrantAuthority;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner.EnsureMembership;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner.MembershipRef;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStaffInvitationStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStaffInvitationStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Invites a staff member with a job in one act (ADR 0116, staff-and-access.md
 * §4, gap map row 9.1a) -- unlike {@link OwnerInvitationService}, which only
 * ever chases an account onboarding already linked.
 *
 * <p>The account and the grant are created up front, before this ever writes
 * a row: {@link #invite} creates the Keycloak account, links the tenant's
 * organization, grants the chosen job through the exact path the People
 * screen's Add-job uses ({@link GrantAuthority#grant}, same refusal
 * when the actor cannot confer it), and only then writes the one-time link
 * this class owns. So a staff invitation's row is never "waiting for an
 * account" the way an owner's is -- it is a receipt for work already done,
 * plus a password-setup link.
 *
 * <p>Every external call -- Keycloak, the mailer -- happens with no
 * transaction bound, the same shape {@link OwnerInvitationService} uses and
 * for the same reason ({@link #transactions}): a slow identity provider or
 * mail server must never hold a pooled connection.
 *
 * <p>The invitation row stores {@code subject_id} and {@code locale} and
 * nothing else about the person -- {@link OwnerInvitationService}'s own
 * stance, repeated here: no name, phone or email is ever written to this
 * table (ADR 0029). The token exists once, in this method's return value and
 * in whatever the mailer sends; it is never logged, audited, or read back.
 */
@Service
public class StaffInvitationService {

    private static final Logger log = LoggerFactory.getLogger(StaffInvitationService.class);

    /** How long an emailed or copied link works -- the same window ADR 0097 chose. */
    public static final Duration LINK_LIFETIME = Duration.ofHours(72);

    public static final Set<String> LOCALES = Set.of("uz", "ru", "en");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcStaffInvitationStore store;
    private final StaffAccounts accounts;
    private final OrganizationProvisioner organizations;
    private final GrantAuthority grants;
    private final AuthorizationService authorization;
    private final PlatformMailer mailer;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String operationsOrigin;

    public StaffInvitationService(
            JdbcStaffInvitationStore store,
            StaffAccounts accounts,
            OrganizationProvisioner organizations,
            GrantAuthority grants,
            AuthorizationService authorization,
            PlatformMailer mailer,
            AuditRecorder audit,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${horecaos.frontends.operations-origin:http://localhost:4200}") String operationsOrigin) {
        this.store = store;
        this.accounts = accounts;
        this.organizations = organizations;
        this.grants = grants;
        this.authorization = authorization;
        this.mailer = mailer;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
        this.operationsOrigin = operationsOrigin.endsWith("/")
                ? operationsOrigin.substring(0, operationsOrigin.length() - 1)
                : operationsOrigin;
    }

    /**
     * Creates the account, the membership, the grant, and the invitation, in
     * that order -- the order matters: a grant for a subject Keycloak has not
     * linked yet would be authority resting on nothing, and an invitation row
     * for a grant that was never made would offer a link to nobody's job.
     *
     * <p>The coarse half of {@link GrantAuthority#grant}'s own
     * authorization check -- does the actor hold {@code IAM_GRANT_MANAGE} at
     * the chosen scope at all -- is repeated here, first, before any Keycloak
     * account exists: without it, a manager who holds the capability only at
     * BRAND scope trying to invite someone at TENANT scope would already have
     * a new, grant-less Keycloak account by the time {@code grant} refused
     * them. The finer half -- whether the actor's own capabilities cover
     * every capability the chosen job carries ({@code requireGrantable}) --
     * is not duplicated and still runs only inside {@code grant} itself,
     * after the account exists; a role-specific escalation attempt is refused
     * there, at the cost of an orphaned, grant-less account for that one
     * refused case.
     *
     * @throws AuthorizationService.AccessDeniedException when the actor does
     *                      not hold {@code IAM_GRANT_MANAGE} at the chosen scope
     * @throws ApiException {@code RESOURCE_CONFLICT} naming the existing
     *                      subject when the phone already has an account in
     *                      this tenant's own organization (a phone belonging
     *                      to a different tenant is refused the same way,
     *                      but never named -- see {@link #rejectIfPhoneTaken});
     *                      whatever {@link GrantAuthority#grant}
     *                      throws when the actor cannot confer this specific
     *                      job at this scope (staff-and-access.md §0's corollary)
     */
    public Created invite(UUID tenantId, InviteCommand command, ActorRef actor, String correlationId) {
        authorization.require(actor.subject(), Capability.IAM_GRANT_MANAGE, command.scope());

        // Resolved before the phone is ever looked up: the duplicate check
        // below needs the inviting tenant's own organization to decide what
        // it may disclose about a match (tenant isolation is the platform's
        // primary security boundary -- see rejectIfPhoneTaken).
        String organizationId = store.keycloakOrganizationId(tenantId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This tenant has no organization to invite a colleague into"));

        rejectIfPhoneTaken(organizationId, accounts.findByPhone(command.phone()));

        StaffAccount account;
        try {
            account = accounts.create(command.firstName(), command.lastName(), command.phone(), command.email());
        } catch (StaffAccounts.StaffAccountAlreadyExistsException lostRace) {
            // Two invitations for the same phone raced past the check above;
            // Keycloak's own username-uniqueness constraint is the real
            // arbiter of "taken", so re-resolve through the losing side and
            // answer with the exact same, tenant-scoped conflict the
            // winner's synchronous pre-check would have thrown.
            rejectIfPhoneTaken(organizationId, accounts.findByPhone(command.phone()));
            // findByPhone came back empty even though Keycloak just refused
            // the create as a duplicate -- stale read or a since-deleted
            // account either way. Refuse the same conflict without a
            // subject id rather than silently retrying a create that has
            // already failed once.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This phone number already has an account");
        }

        MembershipRef membership;
        UUID grantId;
        try {
            // EnsureMembership.email is not annotated @Nullable in iam.api
            // (outside this change's scope), but existingSubjectId is set,
            // so the create branch that would read email never runs --
            // OnboardingStepHandlers' own doc on ensureMembership names this
            // same one-of-two shape.
            @SuppressWarnings("NullAway")
            MembershipRef ensured =
                    organizations.ensureMembership(new EnsureMembership(organizationId, "", account.subjectId()));
            membership = ensured;

            grantId = grants.grant(
                    membership.subjectId(),
                    command.roleCode(),
                    command.scope(),
                    command.reason(),
                    command.validUntil(),
                    actor.subject());
        } catch (RuntimeException refused) {
            // The account (and possibly its organization membership) already
            // exist at this point; without cleanup, accounts.findByPhone
            // above would find this account forever and refuse every future
            // invitation for this phone, by anybody, for good.
            abandonOrphanedAccount(tenantId, account, refused, actor, command.reason(), correlationId);
            throw refused;
        }

        String token = newToken();
        String tokenHash = hash(token);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(LINK_LIFETIME);
        UUID invitationId = Ids.newId();
        String language = LOCALES.contains(command.locale()) ? command.locale() : "ru";
        boolean emailGiven = command.email() != null;

        transactions.executeWithoutResult(ignored -> {
            store.insert(
                    invitationId,
                    tenantId,
                    account.subjectId(),
                    grantId,
                    language,
                    tokenHash,
                    expiresAt,
                    emailGiven,
                    actor.subject(),
                    now);
            audit.record(AuditFact.of("tenant.staff_invitation.invited", AuditClass.SECURITY)
                    .by(actor)
                    .at(command.scope())
                    .target("tenant.staff_invitation", invitationId)
                    .because(command.reason())
                    .changed(Map.of(
                            "roleCode", command.roleCode(),
                            "scopeType", command.scope().type().name(),
                            "emailGiven", emailGiven))
                    .usingCapability(Capability.IAM_GRANT_MANAGE.code())
                    .correlatedBy(correlationId)
                    .occurredAt(now)
                    .build());
        });

        String link = operationsOrigin + "/invite#token=" + token;
        if (emailGiven) {
            sendMail(invitationId, tenantId, account, command.email(), command.roleCode(), language, link, now);
        }
        return new Created(invitationId, account.subjectId(), grantId, link);
    }

    /**
     * Refuses an invitation whose phone is already taken -- but names the
     * existing subject only when it is confirmed to belong to the inviting
     * tenant's own organization.
     *
     * <p>{@link StaffAccounts#findByPhone} searches the whole shared
     * Keycloak realm, with no tenant filter of any kind (a staff account is
     * one global identity keyed by phone, never tenant-scoped). Disclosing a
     * match's existence, or its subject id, to a manager who has only proven
     * authority over their own tenant would let any tenant enumerate
     * arbitrary phone numbers across the platform and learn who already
     * holds an account elsewhere -- crossing the tenant isolation boundary
     * this platform treats as primary. So a match outside this tenant's own
     * organization is refused the identical {@code RESOURCE_CONFLICT} an
     * in-tenant match gets, but without {@code existingSubjectId}: Keycloak's
     * own username uniqueness means a second account cannot be created for
     * the same phone either way, so there is nothing to gain by letting the
     * create attempt run and fail on its own.
     */
    private void rejectIfPhoneTaken(String organizationId, Optional<StaffAccount> duplicate) {
        if (duplicate.isEmpty()) {
            return;
        }
        if (organizations.isMember(organizationId, duplicate.get().subjectId())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This phone number already has an account",
                    Map.of(
                            "field",
                            "phone",
                            "existingSubjectId",
                            duplicate.get().subjectId()));
        }
        throw new ApiException(
                ErrorCode.RESOURCE_CONFLICT, "This phone number already has an account", Map.of("field", "phone"));
    }

    /**
     * Undoes {@link StaffAccounts#create} after the membership or grant step
     * refused, so a mid-flow failure never leaves an account that blocks
     * every future invite for that phone forever (ADR 0116's accepted
     * "orphaned, grant-less account" trade-off does not extend to a phone
     * that can never be invited again).
     *
     * <p>Best-effort: the caller still needs to see the original refusal --
     * that is the one an actor can act on -- so this never replaces {@code
     * cause} with its own exception. When the identity provider will not
     * take the delete either, the account is now a permanent orphan; that is
     * logged and audited distinctly so an operator can remove it by hand,
     * the same shape {@code PasswordResetService#recordPasswordNotSet} uses
     * for its own un-repairable dead end.
     */
    private void abandonOrphanedAccount(
            UUID tenantId,
            StaffAccount account,
            RuntimeException cause,
            ActorRef actor,
            String reason,
            String correlationId) {
        try {
            accounts.delete(account.subjectId());
            log.warn(
                    "A staff invitation's job grant was refused after the account was created; the orphaned "
                            + "account was removed (subject {}, correlation {})",
                    account.subjectId(),
                    correlationId,
                    cause);
        } catch (RuntimeException deleteFailed) {
            log.error(
                    "A staff invitation's job grant was refused after the account was created, and removing the "
                            + "orphaned account also failed; it now permanently blocks this phone until an operator "
                            + "removes it by hand (subject {}, correlation {})",
                    account.subjectId(),
                    correlationId,
                    deleteFailed);
            Instant now = clock.instant();
            transactions.executeWithoutResult(
                    ignored -> audit.record(AuditFact.of("tenant.staff_invitation.orphan_left", AuditClass.SECURITY)
                            .by(actor)
                            .at(ResourceScope.tenant(tenantId))
                            .target("iam.staff_account", Ids.newId())
                            .because(reason)
                            .changed(Map.of(
                                    "subjectId",
                                    account.subjectId(),
                                    "refusalReason",
                                    cause.getClass().getSimpleName(),
                                    "cleanupFailure",
                                    deleteFailed.getClass().getSimpleName()))
                            .usingCapability(Capability.IAM_GRANT_MANAGE.code())
                            .correlatedBy(correlationId)
                            .occurredAt(now)
                            .build()));
        }
    }

    /**
     * Sends the email and records the outcome, entirely outside a
     * transaction ({@link PlatformMailer}'s own contract) -- a slow or
     * unreachable mail server costs this call its own latency, never a
     * pooled connection, and never the invitation itself: the link the
     * caller was already handed back works either way.
     */
    private void sendMail(
            UUID invitationId,
            UUID tenantId,
            StaffAccount account,
            @Nullable String email,
            String roleCode,
            String language,
            String link,
            Instant now) {
        Objects.requireNonNull(email, "sendMail is only called when an email was given");
        MailOutcome outcome = mailer.send(StaffInvitationEmail.render(
                email,
                language,
                store.tenantName(tenantId),
                StaffRoleNames.of(roleCode, language),
                link,
                LINK_LIFETIME.toHours()));
        switch (outcome) {
            case MailOutcome.Sent ignored -> store.markSent(invitationId, now);
            case MailOutcome.NotConfigured ignored -> store.markSendFailed(invitationId, "NOT_CONFIGURED");
            case MailOutcome.Rejected rejected -> store.markSendFailed(invitationId, "REJECTED:" + rejected.code());
            case MailOutcome.Failed failed -> store.markSendFailed(invitationId, "FAILED:" + failed.code());
        }
    }

    /**
     * A fresh link on the same invitation -- staff-access-dialog's «Отправить
     * повторно». Refused once accepted or cancelled, the same guard {@link
     * OwnerInvitationService#resend} applies.
     */
    public String resend(UUID tenantId, UUID invitationId, ActorRef actor, String reason, String correlationId) {
        Row row = store.byId(tenantId, invitationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such invitation"));
        if ("ACCEPTED".equals(row.status()) || "CANCELLED".equals(row.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "ACCEPTED".equals(row.status())
                            ? "This person has already set up their account; they sign in instead"
                            : "This invitation was revoked");
        }
        String token = newToken();
        String tokenHash = hash(token);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(LINK_LIFETIME);
        boolean requeued = Boolean.TRUE.equals(transactions.execute(ignored -> {
            if (!store.requeue(invitationId, tokenHash, expiresAt, now)) {
                return false;
            }
            audit.record(AuditFact.of("tenant.staff_invitation.resent", AuditClass.SECURITY)
                    .by(actor)
                    .at(ResourceScope.tenant(tenantId))
                    .target("tenant.staff_invitation", invitationId)
                    .because(reason)
                    .changed(Map.of("previousStatus", row.status()))
                    .usingCapability(Capability.IAM_GRANT_MANAGE.code())
                    .correlatedBy(correlationId)
                    .occurredAt(now)
                    .build());
            return true;
        }));
        if (!requeued) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This invitation changed while it was being resent");
        }

        String link = operationsOrigin + "/invite#token=" + token;
        if (row.emailGiven()) {
            accounts.find(row.subjectId()).ifPresent(account -> {
                if (account.email() != null) {
                    String roleCode = store.roleCodeOfGrant(row.grantId())
                            .orElse(row.grantId().toString());
                    sendMail(invitationId, tenantId, account, account.email(), roleCode, row.locale(), link, now);
                }
            });
        }
        return link;
    }

    /**
     * Cancels the invitation and revokes the grant it was for, one audit
     * fact each -- two different acts even though one button starts both, the
     * same reasoning {@code StaffJobDialog}'s own doc gives for keeping grant
     * and revoke apart.
     */
    public void revoke(UUID tenantId, UUID invitationId, ActorRef actor, String reason, String correlationId) {
        Row row = store.byId(tenantId, invitationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such invitation"));
        if ("ACCEPTED".equals(row.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This person has already set up their account; revoke their job instead");
        }
        Instant now = clock.instant();
        transactions.executeWithoutResult(ignored -> {
            if (store.markCancelled(invitationId, actor.subject(), now)) {
                audit.record(AuditFact.of("tenant.staff_invitation.cancelled", AuditClass.SECURITY)
                        .by(actor)
                        .at(ResourceScope.tenant(tenantId))
                        .target("tenant.staff_invitation", invitationId)
                        .because(reason)
                        .changed(Map.of("previousStatus", row.status()))
                        .usingCapability(Capability.IAM_GRANT_MANAGE.code())
                        .correlatedBy(correlationId)
                        .occurredAt(now)
                        .build());
            }
        });
        // Its own audited act, deliberately separate from cancelling the
        // invitation above -- one button, two independent operations, the
        // same reasoning StaffJobDialog's own doc gives for never folding a
        // grant and a revoke into one call.
        grants.revoke(tenantId, row.grantId(), actor.subject(), reason);
    }

    /** Every invitation this tenant has open -- the People screen's «Приглашён» pill. */
    public java.util.List<Outstanding> outstanding(UUID tenantId) {
        return store.outstandingForTenant(tenantId).stream()
                .map(row -> new Outstanding(
                        row.id(),
                        row.subjectId(),
                        stateOf(row, clock.instant()),
                        row.invitedAt().toString()))
                .toList();
    }

    /**
     * What the invited person is shown before they set a password: the
     * tenant's name and the job's name, never the phone or the email
     * (staff-and-access.md §4's own answer to "what does inspect return").
     */
    public Inspection inspect(String token) {
        Instant now = clock.instant();
        Row row = live(hash(token.strip()), now);
        transactions.executeWithoutResult(ignored -> store.markOpened(row.id(), now));
        String roleCode = store.roleCodeOfGrant(row.grantId()).orElse(null);
        return new Inspection(
                store.tenantName(row.tenantId()),
                roleCode == null ? null : StaffRoleNames.of(roleCode, row.locale()),
                Objects.requireNonNull(row.expiresAt()).toString(),
                row.locale());
    }

    /**
     * Sets the account's name and password and spends the link -- the same
     * three-step shape {@link OwnerInvitationService#accept} uses and for the
     * same reason: nothing here holds a database connection while Keycloak is
     * asked anything.
     */
    public Accepted accept(String token, String firstName, String lastName, String password, String correlationId) {
        Instant now = clock.instant();
        String tokenHash = hash(token.strip());
        Row row = live(tokenHash, now);
        StaffAccount account = accounts.find(row.subjectId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "This invitation's account no longer exists; ask for a new invitation",
                        Map.of("reason", "ACCOUNT_MISSING")));
        if (!Boolean.TRUE.equals(transactions.execute(ignored -> store.markAccepted(row.id(), now)))) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This invitation changed while it was being accepted");
        }
        try {
            accounts.completeSetup(row.subjectId(), firstName.strip(), lastName.strip(), password);
        } catch (StaffAccounts.PasswordRejectedException refused) {
            transactions.executeWithoutResult(ignored -> store.restoreLink(row.id(), tokenHash, now));
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The password does not meet the policy",
                    Map.of("field", "password", "policy", refused.policy()));
        }
        transactions.executeWithoutResult(
                ignored -> audit.record(AuditFact.of("tenant.staff_invitation.accepted", AuditClass.SECURITY)
                        .by(ActorRef.user(row.subjectId(), null))
                        .at(ResourceScope.tenant(row.tenantId()))
                        .target("tenant.staff_invitation", row.id())
                        .because("The invited person set up their account (ADR 0116)")
                        .changed(Map.of("status", "ACCEPTED"))
                        .correlatedBy(correlationId)
                        .occurredAt(now)
                        .build()));
        return new Accepted(account.username());
    }

    private Row live(String tokenHash, Instant now) {
        Row row = store.byTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "This invitation link is not valid. If you already set your password, sign in.",
                        Map.of("reason", "INVALID")));
        Instant expiresAt = row.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "This invitation link has expired. Ask for a new one.",
                    Map.of("reason", "EXPIRED"));
        }
        return row;
    }

    private static String stateOf(Row row, Instant now) {
        Instant expiresAt = row.expiresAt();
        if (!"ACCEPTED".equals(row.status())
                && !"CANCELLED".equals(row.status())
                && expiresAt != null
                && !expiresAt.isAfter(now)) {
            return "EXPIRED";
        }
        return row.status();
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** A request to invite one colleague with one job, at one scope. */
    public record InviteCommand(
            String firstName,
            String lastName,
            String phone,
            @Nullable String email,
            String roleCode,
            ResourceScope scope,
            String reason,
            @Nullable Instant validUntil,
            String locale) {}

    /** The link is returned once, here -- never logged, never audited, never stored anywhere but this response. */
    public record Created(UUID invitationId, String principalSubject, UUID grantId, String inviteLink) {

        /** A record's generated {@code toString} would print the link. */
        @Override
        public String toString() {
            return "Created[invitationId=" + invitationId + ", principalSubject=" + principalSubject + ", grantId="
                    + grantId + ", inviteLink=<redacted>]";
        }
    }

    public record Outstanding(UUID invitationId, String principalSubject, String state, String invitedAt) {}

    public record Inspection(String tenantName, @Nullable String jobName, String expiresAt, String locale) {}

    public record Accepted(String signInName) {

        /** A record's generated {@code toString} would print the sign-in name. */
        @Override
        public String toString() {
            return "Accepted[signInName=<redacted>]";
        }
    }
}
