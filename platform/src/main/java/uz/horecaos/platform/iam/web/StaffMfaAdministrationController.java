package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.MfaStatus;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.ResetCommand;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.ResetResult;
import uz.horecaos.platform.iam.application.mfa.StaffMfaService;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.MemberView;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.Reach;
import uz.horecaos.platform.iam.web.StaffMfaController.OwnMfaResponse;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * An administrator's view of a person's second factor, and the tenant's reset of it (ADR 0148,
 * Decision 5 and 6).
 *
 * <p>State is read from Keycloak and never copied: the status is the identity provider's own
 * credential list, cached for sixty seconds (ADR 0033), and no projection table exists. That
 * answers {@code staff-and-access.md} §11.9.
 *
 * <p>The reset is an audited administrator action and <strong>never a link</strong>. It removes
 * every authenticator, ends every session, writes an ADR 0027 fact carrying the reason, and emails
 * the person. Three rules sit on top of the capability, each a way the reset could otherwise
 * become the bypass it exists to repair:
 * <ul>
 *   <li>nobody resets their own: a stolen session could otherwise remove the factor and enrol
 *       another phone;
 *   <li>a platform account is reset on the control-plane route, where a second signature (ADR
 *       0050, fail-closed) is asked for;
 *   <li>a tenant owner is reset by platform support inside an ADR 0081 session, never by the
 *       tenant's own staff.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(
        name = "Staff second factor (administration)",
        description = "Reading and resetting a person's second factor (ADR 0148)")
public class StaffMfaAdministrationController {

    private static final Logger log = LoggerFactory.getLogger(StaffMfaAdministrationController.class);

    private final StaffMfaService mfa;
    private final StaffMemberService members;
    private final CurrentActor currentActor;

    public StaffMfaAdministrationController(
            StaffMfaService mfa, StaffMemberService members, CurrentActor currentActor) {
        this.mfa = mfa;
        this.members = members;
        this.currentActor = currentActor;
    }

    @GetMapping("/staff/members/{memberId}/mfa")
    @RequiresCapability(value = Capability.IAM_STAFF_MFA_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Whether a person holds a second factor",
            description = "The identity provider's own credential list, cached for sixty seconds: enrolled or "
                    + "not, each authenticator's id, name and date, and whether the person is required, "
                    + "offered or not asked. A person with no Keycloak account answers not-found.")
    public OwnMfaResponse read(@PathVariable UUID tenantId, @PathVariable UUID memberId) {
        MemberView member = members.detail(tenantId, Reach.tenant(null, null), memberId);
        MfaStatus status = mfa.status(member.principalSubject())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This person has no account."));
        return OwnMfaResponse.of(status);
    }

    @GetMapping("/staff/mfa-summary")
    @RequiresCapability(value = Capability.IAM_STAFF_MFA_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The staff list's «Способ входа» column",
            description = "One row per person of the tenant: whether they hold a second factor and how many "
                    + "authenticators. Read from the cached Keycloak credential lists, a few at a time; a "
                    + "person Keycloak could not answer for is `unknown` and the rest still render.")
    public SummaryResponse summary(@PathVariable UUID tenantId) {
        List<MemberView> people = members.list(tenantId, Reach.tenant(null, null), null, null);
        List<SummaryRow> rows = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SummaryRow>> pending = people.stream()
                    .map(person -> pool.submit(() -> rowOf(person)))
                    .toList();
            for (Future<SummaryRow> row : pending) {
                try {
                    rows.add(row.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(ErrorCode.INTERNAL_ERROR, "Interrupted while reading second factors.");
                } catch (java.util.concurrent.ExecutionException failure) {
                    // rowOf catches its own; this is the pool failing, which fails the read.
                    throw new IllegalStateException(failure.getCause());
                }
            }
        }
        return new SummaryResponse(rows);
    }

    private SummaryRow rowOf(MemberView person) {
        try {
            return mfa.status(person.principalSubject())
                    .map(status -> new SummaryRow(
                            person.memberId(),
                            SummaryRow.KNOWN,
                            status.enrolled(),
                            status.authenticators().size()))
                    .orElse(new SummaryRow(person.memberId(), SummaryRow.NO_ACCOUNT, false, 0));
        } catch (RuntimeException unreadable) {
            // One person's lookup failing must not blank the list (the column is an aid, not the screen).
            log.warn(
                    "A second-factor status could not be read for a staff list row ({})",
                    unreadable.getClass().getSimpleName());
            return new SummaryRow(person.memberId(), SummaryRow.UNKNOWN, false, 0);
        }
    }

    @PostMapping("/staff/members/{memberId}/mfa/resets")
    @RequiresCapability(value = Capability.IAM_STAFF_MFA_RESET, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Reset a person's second factor",
            description = "Removes every authenticator, ends every session, writes an audit fact with the "
                    + "reason and emails the person. Needs If-Match with the member's version. Refused for "
                    + "the caller's own account, for a platform account (use the control plane, which asks "
                    + "a second administrator) and for a tenant owner unless the caller is platform support "
                    + "inside a support session.")
    public ResetResponse reset(
            @PathVariable UUID tenantId,
            @PathVariable UUID memberId,
            @Valid @RequestBody ResetRequest body,
            HttpServletRequest request) {
        MemberView member = members.detail(tenantId, Reach.tenant(null, null), memberId);
        AggregateVersion.requireMatch(AggregateVersion.requireIfMatch(request), member.version());

        String actor = currentActor.get().subject();
        String subject = member.principalSubject();
        if (actor.equals(subject)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "Nobody resets their own second factor. Ask another administrator.");
        }
        if (mfa.isPlatformAccount(subject)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This is a platform account. Its second factor is reset from the control plane, "
                            + "with a second administrator's signature.");
        }
        if (mfa.isTenantOwner(subject, tenantId) && !mfa.mayResetTenantOwner(actor, tenantId)) {
            throw new ApiException(
                    ErrorCode.INSUFFICIENT_CAPABILITY,
                    "A tenant owner's second factor is reset by platform support, inside a support session.");
        }
        ResetResult result =
                mfa.reset(new ResetCommand(subject, actor, body.reason().strip(), tenantId));
        return ResetResponse.of(result);
    }

    // --------------------------------------------------------------- bodies

    public record ResetRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record ResetResponse(int authenticatorsRemoved, boolean sessionsEnded, boolean personNotified) {

        public static ResetResponse of(ResetResult result) {
            return new ResetResponse(result.authenticatorsRemoved(), result.sessionsEnded(), result.personNotified());
        }
    }

    public record SummaryResponse(List<SummaryRow> members) {}

    /**
     * @param state {@code KNOWN} when Keycloak answered; {@code NO_ACCOUNT} when it has no account
     *     for the person (an invitation not yet accepted); {@code UNKNOWN} when it could not answer
     */
    public record SummaryRow(UUID memberId, String state, boolean enrolled, int authenticators) {

        static final String KNOWN = "KNOWN";
        static final String NO_ACCOUNT = "NO_ACCOUNT";
        static final String UNKNOWN = "UNKNOWN";
    }
}
