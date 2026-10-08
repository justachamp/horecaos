package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.api.staff.StaffSelfAuthorized;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.Reach;
import uz.horecaos.platform.iam.application.staff.StaffTerminalFacts;
import uz.horecaos.platform.iam.application.terminals.PinService;
import uz.horecaos.platform.iam.application.terminals.PinService.ActivationVia;
import uz.horecaos.platform.iam.application.terminals.PinService.IssuedCode;
import uz.horecaos.platform.iam.application.terminals.PinService.PinState;
import uz.horecaos.platform.iam.application.terminals.TerminalSessionService;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.SessionHistoryRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The PIN seen from a console: a person's own «Мой PIN», and the two things a manager may do to somebody else's -- issue an
 * activation code, and reset (ADR 0155, Decision 4 and 7).
 *
 * <p>A manager can neither choose nor read a PIN. The code they issue is shown to them once and is the person's way of
 * setting a first PIN at a terminal; the person's own profile says that one was issued, by whom, and when it lapses, and
 * lists each unlock afterwards -- the control that makes a shared PIN visible. Setting, changing and unlocking happen only at a
 * terminal: no endpoint here accepts a PIN.
 *
 * <p>The issuing endpoints carry a secret in their response, so they are not {@code @Idempotent}; they are listed by exact
 * path in {@code EndpointCapabilityDeclarationTests} with that reason. The reset carries none and keeps its {@code
 * Idempotency-Key}.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(
        name = "Staff PIN",
        description = "ADR 0155: a terminal PIN names the person at a shared screen; it never signs anyone in")
public class StaffPinController {

    private final PinService pins;
    private final TerminalSessionService sessions;
    private final StaffMemberService members;
    private final StaffTerminalFacts staff;
    private final StaffDirectory directory;
    private final CurrentActor currentActor;
    private final Clock clock;

    public StaffPinController(
            PinService pins,
            TerminalSessionService sessions,
            StaffMemberService members,
            StaffTerminalFacts staff,
            StaffDirectory directory,
            CurrentActor currentActor,
            Clock clock) {
        this.pins = pins;
        this.sessions = sessions;
        this.members = members;
        this.staff = staff;
        this.directory = directory;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------- my own PIN

    @GetMapping("/staff/me/pin")
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Operation(
            summary = "My PIN: its state and my own last twenty unlocks",
            description =
                    "Resolved from the signed-in subject and this tenant; there is no member id to supply. The state "
                            + "(not set, set, locked), whether an activation code is waiting and who issued it, and which device I "
                            + "was unlocked at, when, and how each ended. If I see an unlock at a tablet I was not at, that is the "
                            + "reason to ask for a reset.")
    public ResponseEntity<MyPinResponse> mine(@PathVariable UUID tenantId) {
        StaffTerminalFacts.Person person = me(tenantId);
        PinState state = pins.state(tenantId, person.memberId());
        List<SessionHistoryRow> recent = sessions.recentSessionsOf(tenantId, person.memberId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new MyPinResponse(
                        state.status(),
                        state.setAt(),
                        state.lockedAt(),
                        pendingOf(tenantId, state),
                        recent.stream()
                                .map(row -> OwnUnlock.of(row, clock.instant()))
                                .toList()));
    }

    @PostMapping("/staff/me/pin-activations")
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Operation(
            summary = "Ask for my own activation code",
            description =
                    "A single-use eight-digit code, valid fifteen minutes, returned once and kept nowhere. The person "
                            + "types it at a terminal and chooses a PIN there. A second code supersedes the first. No "
                            + "Idempotency-Key: the response carries a secret.")
    public ResponseEntity<CodeResponse> issueMine(@PathVariable UUID tenantId) {
        StaffTerminalFacts.Person person = me(tenantId);
        IssuedCode issued = pins.issueActivation(
                tenantId, person.memberId(), person.subject(), ActivationVia.SELF, "I asked for a PIN activation code");
        return code(issued);
    }

    // ------------------------------------------------------------------- a manager, at the tenant

    @GetMapping("/staff/members/{memberId}/pin")
    @RequiresCapability(value = Capability.STAFF_PROFILE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The state of a person's terminal PIN",
            description =
                    "Not set, set or locked, whether an activation code is waiting. Never a digest, and no way to read "
                            + "a PIN.")
    public ResponseEntity<PinStateResponse> stateAtTenant(@PathVariable UUID tenantId, @PathVariable UUID memberId) {
        members.detail(tenantId, Reach.tenant(null, null), memberId);
        return state(tenantId, memberId);
    }

    @PostMapping("/staff/members/{memberId}/pin-activations")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.TENANT)
    @Operation(
            summary = "Issue an activation code for a person",
            description =
                    "Single-use, eight digits, valid four hours, shown to the issuer once and audited with the issuer "
                            + "and the reason; the person is told in their profile that one was issued and by whom. It is the weaker "
                            + "of the two routes (the issuer could type the code first), which is why the person's own profile shows "
                            + "each PIN set and unlock afterwards. No Idempotency-Key: the response carries a secret.")
    public ResponseEntity<CodeResponse> issueAtTenant(
            @PathVariable UUID tenantId, @PathVariable UUID memberId, @Valid @RequestBody PinReasonRequest body) {
        members.requireChangeable(tenantId, Reach.tenant(null, null), memberId);
        return issue(tenantId, memberId, body);
    }

    @PostMapping("/staff/members/{memberId}/pin-reset")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Reset a person's PIN",
            description =
                    "Clears the credential and ends the person's terminal sessions; the person then activates again. "
                            + "The manager never sees or chooses a PIN.")
    public ResponseEntity<Void> resetAtTenant(
            @PathVariable UUID tenantId, @PathVariable UUID memberId, @Valid @RequestBody PinReasonRequest body) {
        members.requireChangeable(tenantId, Reach.tenant(null, null), memberId);
        pins.reset(tenantId, memberId, currentActor.get().subject(), body.reason());
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- a branch manager, at one branch

    @GetMapping("/brands/{brandId}/locations/{locationId}/staff/members/{memberId}/pin")
    @RequiresCapability(value = Capability.STAFF_PROFILE_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "The state of the terminal PIN of a person of this branch")
    public ResponseEntity<PinStateResponse> stateAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID memberId) {
        members.detail(tenantId, Reach.location(brandId, locationId), memberId);
        return state(tenantId, memberId);
    }

    @PostMapping("/brands/{brandId}/locations/{locationId}/staff/members/{memberId}/pin-activations")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Issue an activation code for a person of this branch",
            description =
                    "Refused unless every active job the person holds is inside this branch: a person who also works "
                            + "elsewhere is not the branch's to change. No Idempotency-Key: the response carries a secret.")
    public ResponseEntity<CodeResponse> issueAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID memberId,
            @Valid @RequestBody PinReasonRequest body) {
        members.requireChangeable(tenantId, Reach.location(brandId, locationId), memberId);
        return issue(tenantId, memberId, body);
    }

    @PostMapping("/brands/{brandId}/locations/{locationId}/staff/members/{memberId}/pin-reset")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(summary = "Reset the PIN of a person of this branch")
    public ResponseEntity<Void> resetAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID memberId,
            @Valid @RequestBody PinReasonRequest body) {
        members.requireChangeable(tenantId, Reach.location(brandId, locationId), memberId);
        pins.reset(tenantId, memberId, currentActor.get().subject(), body.reason());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------------------ helpers

    private StaffTerminalFacts.Person me(UUID tenantId) {
        return staff.personBySubject(tenantId, currentActor.get().subject())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This tenant keeps no staff record for the signed-in account"));
    }

    private ResponseEntity<PinStateResponse> state(UUID tenantId, UUID memberId) {
        PinState state = pins.state(tenantId, memberId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PinStateResponse(
                        state.status(), state.setAt(), state.lockedAt(), pendingOf(tenantId, state)));
    }

    private ResponseEntity<CodeResponse> issue(UUID tenantId, UUID memberId, PinReasonRequest body) {
        IssuedCode issued = pins.issueActivation(
                tenantId, memberId, currentActor.get().subject(), ActivationVia.MANAGER, body.reason());
        return code(issued);
    }

    private static ResponseEntity<CodeResponse> code(IssuedCode issued) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CodeResponse(
                        issued.code(), issued.expiresAt(), issued.via().name()));
    }

    private @Nullable PendingPinCode pendingOf(UUID tenantId, PinState state) {
        PinService.PendingCode pending = state.pendingCode();
        if (pending == null) {
            return null;
        }
        String by = "SELF".equals(pending.issuedVia())
                ? null
                : pending.issuedBy() == null ? null : directory.nameOf(tenantId, pending.issuedBy());
        return new PendingPinCode(pending.issuedVia(), by, pending.expiresAt());
    }

    // ------------------------------------------------------------------------------------ bodies

    record PinReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    /** The code is shown once; it is not stored and not logged. */
    record CodeResponse(String code, Instant expiresAt, String issuedVia) {

        @Override
        public String toString() {
            return "CodeResponse[code=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }

    /** @param issuedBy the issuing manager's display name, for display only; null when I issued it myself */
    record PendingPinCode(String issuedVia, @Nullable String issuedBy, Instant expiresAt) {}

    record PinStateResponse(
            String status,
            @Nullable Instant setAt,
            @Nullable Instant lockedAt,
            @Nullable PendingPinCode pending) {}

    record OwnUnlock(
            UUID sessionId,
            UUID deviceId,
            String deviceName,
            Instant openedAt,
            @Nullable Instant endedAt,
            @Nullable String endedReason) {

        static OwnUnlock of(SessionHistoryRow row, Instant now) {
            return new OwnUnlock(
                    row.id(), row.deviceId(), row.deviceName(), row.openedAt(), row.endedAt(), row.endedReasonAt(now));
        }
    }

    record MyPinResponse(
            String status,
            @Nullable Instant setAt,
            @Nullable Instant lockedAt,
            @Nullable PendingPinCode pending,
            List<OwnUnlock> unlocks) {}

    @SuppressWarnings("unused")
    private static Map<String, Object> none() {
        return Map.of();
    }
}
