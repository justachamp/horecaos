package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.InvitationAccepted;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.InvitationInspection;
import uz.horecaos.platform.tenancy.application.invitations.StaffInvitationService;
import uz.horecaos.platform.tenancy.application.invitations.StaffInvitationService.InviteCommand;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Two families of endpoint on one controller, and the name was chosen for the
 * second before it existed: an owner's or a staff member's side of an
 * invitation (ADR 0097, ADR 0116) -- unauthenticated, the token proves
 * possession -- and, since ADR 0116, a manager's own side of inviting a
 * colleague (staff-and-access.md §4, gap map row 9.1a), authenticated and
 * capability-gated.
 *
 * <p>{@link #inspect} and {@link #accept} serve either kind of token: an
 * owner's invitation is tried first (the surface this endpoint has always
 * served), and only a token that {@link OwnerInvitationService} does not
 * recognise at all -- {@code reason=INVALID}, never {@code EXPIRED} -- falls
 * through to {@link StaffInvitationService}. A token that resolves in one
 * store and is merely expired is never retried against the other: that would
 * turn an owner's expired link into a staff "not found" instead of the
 * EXPIRED page the owner accept flow has always shown.
 */
@RestController
@Tag(
        name = "Staff invitations",
        description = "Invite a colleague, and either side of accepting it (ADR 0097, ADR 0116)")
public class StaffInvitationController {

    private static final RateLimiter.Policy LIMIT = RateLimiter.Policy.strictPerMinute(10);

    private final OwnerInvitationService ownerInvitations;
    private final StaffInvitationService staffInvitations;
    private final CurrentActor currentActor;
    private final RateLimiter rateLimiter;

    public StaffInvitationController(
            OwnerInvitationService ownerInvitations,
            StaffInvitationService staffInvitations,
            CurrentActor currentActor,
            RateLimiter rateLimiter) {
        this.ownerInvitations = ownerInvitations;
        this.staffInvitations = staffInvitations;
        this.currentActor = currentActor;
        this.rateLimiter = rateLimiter;
    }

    // ------------------------------------------------------- the invited person's side (public)

    @PostMapping("/api/v1/operations/invitations/inspect")
    @Operation(
            summary = "Read an invitation before accepting it",
            description = "Whose invitation it is and, for a staff invitation, the job it names -- never "
                    + "the phone or email either way. Answers not-found with a reason of INVALID or "
                    + "EXPIRED for a link that cannot be used.")
    public InvitationInspectionResponse inspect(
            @Valid @RequestBody InvitationTokenRequest body, HttpServletRequest request) {
        limit("tenancy.invitation.inspect", request);
        try {
            InvitationInspection owner = ownerInvitations.inspect(body.token());
            return new InvitationInspectionResponse(
                    owner.tenantName(), owner.emailMasked(), null, owner.expiresAt(), owner.locale());
        } catch (ApiException notOwner) {
            if (!isUnrecognised(notOwner)) {
                throw notOwner;
            }
            StaffInvitationService.Inspection staff = staffInvitations.inspect(body.token());
            return new InvitationInspectionResponse(
                    staff.tenantName(), null, staff.jobName(), staff.expiresAt(), staff.locale());
        }
    }

    @PostMapping("/api/v1/operations/invitations/accept")
    @Operation(
            summary = "Set up the invited account",
            description = "Sets the person's name and password at the identity provider and spends the "
                    + "link. The realm's password policy applies; a refusal names the rule.")
    public ResponseEntity<InvitationAcceptResponse> accept(
            @Valid @RequestBody InvitationAcceptRequest body, HttpServletRequest request) {
        limit("tenancy.invitation.accept", request);
        String correlationId = UUID.randomUUID().toString();
        try {
            InvitationAccepted owner = ownerInvitations.accept(
                    body.token(), body.firstName(), body.lastName(), body.password(), correlationId);
            return ResponseEntity.ok(new InvitationAcceptResponse(owner.signInName()));
        } catch (ApiException notOwner) {
            if (!isUnrecognised(notOwner)) {
                throw notOwner;
            }
            StaffInvitationService.Accepted staff = staffInvitations.accept(
                    body.token(), body.firstName(), body.lastName(), body.password(), correlationId);
            return ResponseEntity.ok(new InvitationAcceptResponse(staff.signInName()));
        }
    }

    /** {@code reason=INVALID}: this store has never heard of the token. {@code EXPIRED} is a real answer, not a miss. */
    private static boolean isUnrecognised(ApiException failure) {
        return failure.errorCode() == ErrorCode.RESOURCE_NOT_FOUND
                && "INVALID".equals(failure.properties().get("reason"));
    }

    private void limit(String operation, HttpServletRequest request) {
        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(operation, null, callerKey(request)), LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many attempts. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    /** The caller's address, hashed: the limiter needs a key, not an address to keep (ADR 0029). */
    private static String callerKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        String key = address == null || address.isBlank() ? "unattributed" : address;
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    // ------------------------------------------------------- the inviter's side (authenticated)

    @PostMapping("/api/v1/operations/tenants/{tenantId}/staff/invitations")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, mutating = true)
    @Operation(
            summary = "Invite a colleague with a job",
            description = "Creates the account, links the tenant's organization, and grants the chosen "
                    + "job through the same path the People screen's Add-job uses -- a granter may only "
                    + "confer a job whose capabilities it already holds, at a scope it already covers "
                    + "(staff-and-access.md §0). The response carries the invite link once, for the "
                    + "toast's copy button; it is delivered by email too when one was given.")
    public ResponseEntity<StaffInvitationCreatedResponse> invite(
            @PathVariable UUID tenantId, @Valid @RequestBody StaffInvitationRequest body, HttpServletRequest request) {
        String correlationId = UUID.randomUUID().toString();
        StaffInvitationService.Created created = staffInvitations.invite(
                tenantId,
                new InviteCommand(
                        body.firstName().strip(),
                        body.lastName().strip(),
                        body.phone().strip(),
                        blankToNull(body.email()),
                        body.roleCode(),
                        scopeOf(tenantId, body),
                        body.reason(),
                        body.validUntil(),
                        body.locale() == null ? "ru" : body.locale()),
                ActorRef.user(currentActor.get().subject(), null),
                correlationId);
        return ResponseEntity.ok(new StaffInvitationCreatedResponse(
                created.invitationId(), created.principalSubject(), created.grantId(), created.inviteLink()));
    }

    @PostMapping("/api/v1/operations/tenants/{tenantId}/staff/invitations/{invitationId}/resend")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, mutating = true)
    @Operation(
            summary = "Resend a staff invitation",
            description = "A fresh link on the same invitation; the one already out stops working. "
                    + "Refused once the person has set up their account, or the invitation was revoked.")
    public ResponseEntity<StaffInvitationLinkResponse> resend(
            @PathVariable UUID tenantId, @PathVariable UUID invitationId, @Valid @RequestBody ReasonRequest body) {
        String link = staffInvitations.resend(
                tenantId,
                invitationId,
                ActorRef.user(currentActor.get().subject(), null),
                body.reason(),
                UUID.randomUUID().toString());
        return ResponseEntity.ok(new StaffInvitationLinkResponse(link));
    }

    @DeleteMapping("/api/v1/operations/tenants/{tenantId}/staff/invitations/{invitationId}")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, mutating = true)
    @Operation(
            summary = "Revoke a staff invitation",
            description = "Cancels the invitation and revokes the job it was for, one audit fact each.")
    public ResponseEntity<Map<String, Object>> revoke(
            @PathVariable UUID tenantId, @PathVariable UUID invitationId, @Valid @RequestBody ReasonRequest body) {
        staffInvitations.revoke(
                tenantId,
                invitationId,
                ActorRef.user(currentActor.get().subject(), null),
                body.reason(),
                UUID.randomUUID().toString());
        return ResponseEntity.ok(Map.of("changed", true));
    }

    @GetMapping("/api/v1/operations/tenants/{tenantId}/staff/invitations")
    @RequiresCapability(Capability.IAM_GRANT_MANAGE)
    @Operation(
            summary = "Every staff invitation this tenant has open",
            description = "Not accepted, not cancelled -- the People screen's «Приглашён» pill and filter.")
    public java.util.List<StaffInvitationService.Outstanding> outstanding(@PathVariable UUID tenantId) {
        return staffInvitations.outstanding(tenantId);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Mirrors {@code GrantController.scopeOf}: {@code locationId} decides LOCATION, then {@code brandId} BRAND, else TENANT. */
    private static ResourceScope scopeOf(UUID tenantId, StaffInvitationRequest request) {
        if (request.locationId() != null) {
            return ResourceScope.location(tenantId, request.brandId(), request.locationId());
        }
        if (request.brandId() != null) {
            return ResourceScope.brand(tenantId, request.brandId());
        }
        return ResourceScope.tenant(tenantId);
    }

    public record InvitationTokenRequest(
            @NotBlank @Size(max = 128) String token) {

        /** A record's generated {@code toString} would print the token. */
        @Override
        public String toString() {
            return "InvitationTokenRequest[token=<redacted>]";
        }
    }

    public record InvitationAcceptRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(min = 12, max = 128) String password) {

        /** A record's generated {@code toString} would print the token and the password. */
        @Override
        public String toString() {
            return "InvitationAcceptRequest[token=<redacted>, password=<redacted>]";
        }
    }

    public record InvitationInspectionResponse(
            String tenantName,
            @Nullable String emailMasked,
            @Nullable String jobName,
            String expiresAt,
            String locale) {}

    public record InvitationAcceptResponse(String signInName) {

        /** A record's generated {@code toString} would print the sign-in name. */
        @Override
        public String toString() {
            return "InvitationAcceptResponse[signInName=<redacted>]";
        }
    }

    /**
     * @param phone the identifier this market actually uses (staff-and-access.md §4); required
     * @param email optional and never required
     * @param roleCode one of the jobs {@code GET .../roles} lists; the server refuses one the actor
     *                 cannot confer, the same refusal People's Add-job gives (staff-and-access.md §0)
     * @param locale the inviting manager's own console language, so the invitation email and the
     *               accept screen speak it -- there is no language field on staff-and-access.md §4's form
     */
    public record StaffInvitationRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(min = 9, max = 20) String phone,
            @Email @Size(max = 255) @Nullable String email,
            @NotBlank @Size(max = 64) String roleCode,
            // Not @Nullable, matching GrantController.GrantRequest's own two
            // fields exactly: both are genuinely optional (no @NotBlank/@NotNull),
            // left unannotated here for the same reason that record is -- so
            // scopeOf's location(tenantId, brandId, locationId) call, which
            // only ever runs once locationId() != null, is not itself flagged
            // for a brandId a LOCATION scope requires but a JSON body cannot
            // promise NullAway a schema-level guarantee for.
            UUID brandId,
            UUID locationId,
            @NotBlank @Size(max = 1000) String reason,
            @Nullable Instant validUntil,
            @Nullable String locale) {

        /** A record's generated {@code toString} would print the name, phone and email. */
        @Override
        public String toString() {
            return "StaffInvitationRequest[roleCode=" + roleCode + ", brandId=" + brandId + ", locationId=" + locationId
                    + ", validUntil=" + validUntil + "]";
        }
    }

    public record StaffInvitationCreatedResponse(
            UUID invitationId,
            String principalSubject,
            UUID grantId,

            @Classified(value = DataClass.PERSONAL, reason = "a bearer link that sets up the account, ADR 0029")
            String inviteLink) {

        /** A record's generated {@code toString} would print the link. */
        @Override
        public String toString() {
            return "StaffInvitationCreatedResponse[invitationId=" + invitationId + ", principalSubject="
                    + principalSubject + ", grantId=" + grantId + ", inviteLink=<redacted>]";
        }
    }

    public record StaffInvitationLinkResponse(
            @Classified(value = DataClass.PERSONAL, reason = "a bearer link that sets up the account, ADR 0029")
            String inviteLink) {

        /** A record's generated {@code toString} would print the link. */
        @Override
        public String toString() {
            return "StaffInvitationLinkResponse[inviteLink=<redacted>]";
        }
    }

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
}
