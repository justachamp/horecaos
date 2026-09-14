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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner.EnsureMembership;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner.MembershipRef;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantCommand;
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
 * screen's Add-job uses ({@link GrantManagementService#grant}, same refusal
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

    /** How long an emailed or copied link works -- the same window ADR 0097 chose. */
    public static final Duration LINK_LIFETIME = Duration.ofHours(72);

    public static final Set<String> LOCALES = Set.of("uz", "ru", "en");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcStaffInvitationStore store;
    private final StaffAccounts accounts;
    private final OrganizationProvisioner organizations;
    private final GrantManagementService grants;
    private final PlatformMailer mailer;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String operationsOrigin;

    public StaffInvitationService(
            JdbcStaffInvitationStore store,
            StaffAccounts accounts,
            OrganizationProvisioner organizations,
            GrantManagementService grants,
            PlatformMailer mailer,
            AuditRecorder audit,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${horecaos.frontends.operations-origin:http://localhost:4200}") String operationsOrigin) {
        this.store = store;
        this.accounts = accounts;
        this.organizations = organizations;
        this.grants = grants;
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
     * @throws ApiException {@code RESOURCE_CONFLICT} naming the existing
     *                      subject when the phone is already registered;
     *                      whatever {@link GrantManagementService#grant}
     *                      throws when the actor cannot confer this job at
     *                      this scope (staff-and-access.md §0's corollary)
     */
    public Created invite(UUID tenantId, InviteCommand command, ActorRef actor, String correlationId) {
        Optional<StaffAccount> duplicate = accounts.findByPhone(command.phone());
        if (duplicate.isPresent()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This phone number already has an account",
                    Map.of(
                            "field",
                            "phone",
                            "existingSubjectId",
                            duplicate.get().subjectId()));
        }

        String organizationId = store.keycloakOrganizationId(tenantId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This tenant has no organization to invite a colleague into"));

        StaffAccount account =
                accounts.create(command.firstName(), command.lastName(), command.phone(), command.email());

        // EnsureMembership.email is not annotated @Nullable in iam.api (outside
        // this change's scope), but existingSubjectId is set, so the create
        // branch that would read email never runs -- OnboardingStepHandlers'
        // own doc on ensureMembership names this same one-of-two shape.
        @SuppressWarnings("NullAway")
        MembershipRef membership =
                organizations.ensureMembership(new EnsureMembership(organizationId, "", account.subjectId()));

        UUID grantId = grants.grant(
                new GrantCommand(
                        membership.subjectId(),
                        command.roleCode(),
                        command.scope(),
                        command.reason(),
                        command.validUntil()),
                actor.subject());

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
