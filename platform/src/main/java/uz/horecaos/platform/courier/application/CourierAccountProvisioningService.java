package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
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
     *         CourierEngagementService.NewCourier#principalSubject}
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

        StaffAccount account = reuseOrCreate(firstName, lastName, phone, email);

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

        return new Provisioned(account.subjectId(), account.username());
    }

    /**
     * An existing account for the phone is reused rather than refused: unlike
     * a staff job invitation, provisioning a courier confers no authority
     * beyond row-ownership of the courier record itself, so an operator, a
     * former staff member, or an already-provisioned courier re-registering
     * on the same phone all get the identity they already have rather than a
     * conflict a manager cannot self-serve past.
     */
    private StaffAccount reuseOrCreate(String firstName, String lastName, String phone, @Nullable String email) {
        Optional<StaffAccount> existing = accounts.findByPhone(phone);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return accounts.create(firstName, lastName, phone, email);
        } catch (StaffAccounts.StaffAccountAlreadyExistsException lostRace) {
            // Two registrations for the same phone raced past the check
            // above; re-resolve through the losing side rather than treat a
            // provably-existing account as a hard failure.
            return accounts.findByPhone(phone)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "This phone number already has an account, but it could not be read back"));
        }
    }

    public record Provisioned(String subjectId, String username) {}
}
