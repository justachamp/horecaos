package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner.EnsureMembership;
import uz.horecaos.platform.tenancy.api.KeycloakOrganizationLookup;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Creates the courier's own account at the identity provider from the
 * console, so registering a courier no longer means an operator typing a
 * Keycloak subject id somebody else already created (gap map row {@code
 * 3.3}: "courier-app access is not provisioned here").
 *
 * <p>Reuses exactly the two pieces {@code StaffInvitationService#invite}
 * already relies on for the same job, at TENANT scope rather than a job
 * grant: {@link StaffAccounts#create}, the phone-first account creation
 * ADR 0116 added for a colleague with no work email (a courier's own
 * registration form has never collected one either), and {@link
 * OrganizationProvisioner#ensureMembership}, so the new account's token
 * carries the organization claim ADR 0003 requires before any tenant-scoped
 * request — including a courier's own duty-session and shift endpoints —
 * will resolve this tenant for it at all. Unlike a staff invitation, nothing
 * here grants an {@code iam.grants} row: a courier is authorized by {@link
 * uz.horecaos.platform.courier.api.CourierSelfAuthorized}'s row-ownership
 * comparison against {@code fulfillment.couriers.principal_subject}, never by
 * a role bundle, so there is no job to confer.
 *
 * <p><strong>What this does not do.</strong> Setting the account's password
 * is deliberately out of scope: {@link StaffAccounts#create} leaves a
 * password-less account by design, and the courier side of finishing that
 * setup is the courier mobile app's own onboarding, which does not exist yet
 * (ADR 0045's own {@code CourierDutyController} class doc: "these are
 * staff-facing today, and that is a rollout fact"). Provisioning the
 * identity here does not invent that flow; it only stops the console asking
 * an operator to fabricate the identifier that flow will need.
 */
@Service
public class CourierAccountProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(CourierAccountProvisioningService.class);

    private final StaffAccounts accounts;
    private final OrganizationProvisioner organizations;
    private final KeycloakOrganizationLookup organizationLookup;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierAccountProvisioningService(
            StaffAccounts accounts,
            OrganizationProvisioner organizations,
            KeycloakOrganizationLookup organizationLookup,
            AuditRecorder audit,
            Clock clock) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.organizationLookup = organizationLookup;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param phone required — ADR 0116's phone-first identifier, the same
     *              one couriers.md's own compliance file already asks for
     * @param email optional; a courier account may have none, the same
     *              standing {@link StaffAccounts#create} already gives a
     *              colleague with no work email
     * @return the identity provider's own subject id, for {@code
     *         CourierEngagementService.NewCourier#principalSubject}; {@link
     *         Provisioned#created()} tells a caller whether this call is what
     *         brought the account into being, for {@link #abandonIfCreated}
     * @throws ApiException {@code RESOURCE_CONFLICT} when this tenant has no
     *              organization to link into yet (onboarding incomplete), or
     *              when the phone already names an account this call cannot
     *              safely reuse (see {@link #reuseOrCreate})
     */
    public Provisioned provision(
            UUID tenantId, String firstName, String lastName, String phone, @Nullable String email, ActorRef actor) {

        String organizationId = organizationLookup
                .keycloakOrganizationId(tenantId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This tenant has no organization to provision a courier into"));

        Resolved resolved = reuseOrCreate(organizationId, firstName, lastName, phone, email);
        StaffAccount account = resolved.account();

        // existingSubjectId set: ensureMembership links the account already
        // created above rather than inviting a new one by email, the same
        // one-of-two shape StaffInvitationService#invite documents on its
        // own identical call.
        organizations.ensureMembership(new EnsureMembership(organizationId, "", account.subjectId()));

        audit.record(AuditFact.of("courier.account.provisioned", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .because("Courier registration")
                .changed(Map.of("organizationId", organizationId, "subjectId", account.subjectId()))
                .correlatedBy(account.subjectId())
                .occurredAt(clock.instant())
                .build());

        return new Provisioned(account.subjectId(), account.username(), resolved.created());
    }

    /**
     * Undoes a fresh {@link StaffAccounts#create} when a later step of the
     * same registration -- {@code CourierEngagementService#register}, most
     * often a duplicate {@code displayReference} hitting {@code
     * uq_courier_reference} -- fails after {@link #provision} already
     * succeeded. Without this, the account (and the organization membership
     * {@link #provision} just granted it) survives the failed registration
     * as a real, tenant-linked identity that {@link StaffAccounts#findByPhone}
     * keeps finding forever, with no courier row and no operator-visible way
     * to know it happened -- the same "orphaned, grant-less account" shape
     * {@link
     * uz.horecaos.platform.tenancy.application.invitations.StaffInvitationService
     * #abandonOrphanedAccount} exists to undo for a staff invitation.
     *
     * <p>A no-op when {@link Provisioned#created()} is false: an account this
     * call reused already existed before this registration attempt and may
     * be in active use elsewhere (a returning courier, a staff member's own
     * login), so a failed registration must never delete it or touch its
     * pre-existing organization membership.
     *
     * <p>Best-effort, exactly like its staff-invitation counterpart: {@code
     * cause} is always what the caller re-throws, never replaced by a
     * cleanup failure, and an unrepairable orphan is logged and audited
     * distinctly so an operator can remove it by hand.
     */
    public void abandonIfCreated(
            UUID tenantId, Provisioned account, RuntimeException cause, ActorRef actor, String reason) {
        if (!account.created()) {
            return;
        }
        try {
            accounts.delete(account.subjectId());
            log.warn(
                    "A courier registration's engagement step was refused after its identity-provider account "
                            + "was created; the orphaned account was removed (subject {})",
                    account.subjectId(),
                    cause);
        } catch (RuntimeException deleteFailed) {
            log.error(
                    "A courier registration's engagement step was refused after its identity-provider account "
                            + "was created, and removing the orphaned account also failed; it now permanently "
                            + "blocks this phone until an operator removes it by hand (subject {})",
                    account.subjectId(),
                    deleteFailed);
            Instant now = clock.instant();
            audit.record(AuditFact.of("courier.account.orphan_left", AuditClass.SECURITY)
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
                    .correlatedBy(account.subjectId())
                    .occurredAt(now)
                    .build());
        }
    }

    /**
     * An existing account for the phone is reused rather than refused only
     * when it already belongs to <em>this</em> tenant's own organization:
     * unlike a staff job invitation, provisioning a courier confers no
     * authority beyond row-ownership of the courier record itself, so an
     * operator, a former staff member, or an already-provisioned courier
     * re-registering on the same phone all get the identity they already
     * have rather than a conflict a manager cannot self-serve past.
     *
     * <p>{@link StaffAccounts#findByPhone} searches the whole shared
     * Keycloak realm, with no tenant filter of any kind (a staff account is
     * one global identity keyed by phone, never tenant-scoped). Reusing a
     * match unconditionally would let any tenant that merely knows or
     * guesses another tenant's staff phone number link that stranger's
     * account into its own organization -- the account's token would then
     * carry this tenant's org claim, and {@code CourierSelfAuthorized}'s
     * row-ownership check would accept it for this tenant's own courier
     * self-service endpoints. So a match that is not already a member of
     * {@code organizationId} is refused the same {@code RESOURCE_CONFLICT}
     * {@link uz.horecaos.platform.tenancy.application.invitations.StaffInvitationService
     * #rejectIfPhoneTaken} gives an out-of-tenant match, mirroring that
     * guard exactly.
     */
    private Resolved reuseOrCreate(
            String organizationId, String firstName, String lastName, String phone, @Nullable String email) {
        Optional<StaffAccount> existing = accounts.findByPhone(phone);
        if (existing.isPresent()) {
            return new Resolved(rejectUnlessAlreadyInThisOrganization(organizationId, existing.get()), false);
        }
        try {
            return new Resolved(accounts.create(firstName, lastName, phone, email), true);
        } catch (StaffAccounts.StaffAccountAlreadyExistsException lostRace) {
            // Two registrations for the same phone raced past the check
            // above; re-resolve through the losing side rather than treat a
            // provably-existing account as a hard failure. The winner of the
            // race is the one that created it, so this side never did.
            StaffAccount raced = accounts.findByPhone(phone)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "This phone number already has an account, but it could not be read back"));
            return new Resolved(rejectUnlessAlreadyInThisOrganization(organizationId, raced), false);
        }
    }

    /**
     * Never discloses whether the match belongs to a different tenant --
     * only that the phone is taken -- the same non-disclosure {@code
     * StaffInvitationService#rejectIfPhoneTaken} keeps for an out-of-tenant
     * match, so an actor who only proved authority over their own tenant
     * cannot use this endpoint to enumerate which phone numbers already have
     * accounts elsewhere on the platform.
     */
    private StaffAccount rejectUnlessAlreadyInThisOrganization(String organizationId, StaffAccount existing) {
        if (organizations.isMember(organizationId, existing.subjectId())) {
            return existing;
        }
        throw new ApiException(
                ErrorCode.RESOURCE_CONFLICT, "This phone number already has an account", Map.of("field", "phone"));
    }

    /** @param created whether this call minted a fresh account rather than reusing one that already existed */
    public record Provisioned(String subjectId, String username, boolean created) {}

    /** Which account {@link #reuseOrCreate} settled on, and whether it minted it. */
    private record Resolved(StaffAccount account, boolean created) {}
}
