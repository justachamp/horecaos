package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.application.SupportSessionService;
import uz.horecaos.platform.iam.application.SupportSessionService.Access;
import uz.horecaos.platform.iam.application.SupportSessionService.SupportSession;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Support sessions (ADR 0081): HorecaOS staff entering one tenant, and the
 * tenant seeing who did.
 *
 * <p>Two surfaces from one class, as {@link GrantController} spans two: the
 * control plane opens, lists and ends sessions at platform scope; operations
 * shows a tenant's own administrators the visits to their account and lets
 * them end one, and shows the support person inside their own deadline.
 */
@RestController
@Tag(name = "Support sessions", description = "ADR 0081: time-boxed, reasoned entry into one tenant")
public class SupportSessionController {

    private final SupportSessionService sessions;
    private final CurrentActor currentActor;

    public SupportSessionController(SupportSessionService sessions, CurrentActor currentActor) {
        this.sessions = sessions;
        this.currentActor = currentActor;
    }

    @PostMapping("/api/v1/control-plane/tenants/{tenantId}/support-sessions")
    @RequiresCapability(value = Capability.SUPPORT_SESSION_START, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Open a support session into a tenant",
            description = "Confers a support-only role on the caller for this tenant until the "
                    + "session ends: between 15 minutes and 4 hours, never longer. VIEW looks; ASSIST "
                    + "also does the order-floor acts a location manager could. The reason is shown to "
                    + "the tenant and kept in their audit log.")
    ResponseEntity<SupportSessionView> open(@PathVariable UUID tenantId, @Valid @RequestBody OpenRequest body) {
        SupportSession session = sessions.open(
                tenantId,
                subject(),
                body.access(),
                body.reason(),
                body.ticketReference(),
                Duration.ofMinutes(body.minutes()));
        return ResponseEntity.ok(view(session));
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/support-sessions")
    @RequiresCapability(value = Capability.SUPPORT_SESSION_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "A tenant's support sessions, newest first")
    Page<SupportSessionView> listForTenant(
            @PathVariable UUID tenantId, @RequestParam(required = false) @Nullable Integer limit) {
        return Page.last(views(sessions.listForTenant(tenantId, Page.limitOrDefault(limit))));
    }

    @PostMapping("/api/v1/control-plane/tenants/{tenantId}/support-sessions/{sessionId}/end")
    @RequiresCapability(value = Capability.SUPPORT_SESSION_START, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "End a support session now",
            description = "Revokes the grant it conferred. Your own session, or anyone's if you are a "
                    + "platform administrator. Ending one that already ended changes nothing.")
    ResponseEntity<SupportSessionView> end(
            @PathVariable UUID tenantId, @PathVariable UUID sessionId, @Valid @RequestBody EndRequest body) {
        return ResponseEntity.ok(view(sessions.end(tenantId, sessionId, subject(), body.reason())));
    }

    @GetMapping("/api/v1/control-plane/support-sessions/mine")
    @RequiresCapability(value = Capability.SUPPORT_SESSION_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "The caller's support sessions that are still open")
    List<SupportSessionView> mine() {
        return views(sessions.openFor(subject()));
    }

    @GetMapping("/api/v1/operations/tenants/{tenantId}/support-sessions")
    @RequiresCapability(value = Capability.SUPPORT_SESSION_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Who from HorecaOS entered this account",
            description =
                    "Every support session into this tenant, newest first, with its reason " + "and how it ended.")
    Page<SupportSessionView> visitsToTenant(
            @PathVariable UUID tenantId, @RequestParam(required = false) @Nullable Integer limit) {
        return Page.last(views(sessions.listForTenant(tenantId, Page.limitOrDefault(limit))));
    }

    @GetMapping("/api/v1/operations/tenants/{tenantId}/support-sessions/current")
    @RequiresCapability(value = Capability.SUPPORT_SESSION_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The caller's own open support session here, if any",
            description = "What the support person inside sees: how much access they have and "
                    + "until when. Answers 404 for anyone not in a session.")
    ResponseEntity<SupportSessionView> current(@PathVariable UUID tenantId) {
        return sessions.currentFor(subject(), tenantId)
                .map(session -> ResponseEntity.ok(view(session)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/operations/tenants/{tenantId}/support-sessions/{sessionId}/end")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "End a HorecaOS support session in your account",
            description = "The tenant's own administrators may end any support session in their "
                    + "account, at once, with a reason.")
    ResponseEntity<SupportSessionView> endFromTenant(
            @PathVariable UUID tenantId, @PathVariable UUID sessionId, @Valid @RequestBody EndRequest body) {
        return ResponseEntity.ok(view(sessions.end(tenantId, sessionId, subject(), body.reason())));
    }

    private String subject() {
        return currentActor.get().subject();
    }

    private List<SupportSessionView> views(List<SupportSession> rows) {
        return rows.stream().map(this::view).toList();
    }

    private SupportSessionView view(SupportSession session) {
        return new SupportSessionView(
                session.id(),
                session.tenantId(),
                session.principalSubject(),
                session.access().name(),
                session.reason(),
                session.ticketReference(),
                session.startedAt().toString(),
                session.expiresAt().toString(),
                session.endedAt() == null ? null : session.endedAt().toString(),
                session.endedBy(),
                session.endReason(),
                sessions.isOpen(session));
    }

    /**
     * @param minutes how long the session lasts, from 15 to 240
     * @param ticketReference the support ticket this is for, when there is one
     */
    record OpenRequest(
            @NotNull Access access,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 200) @Nullable String ticketReference,
            @Min(15) @Max(240) int minutes) {}

    record EndRequest(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * One support session.
     *
     * @param open whether it is in force now: not ended, and before its deadline
     */
    public record SupportSessionView(
            UUID id,
            UUID tenantId,
            String principalSubject,
            String access,
            String reason,
            @Nullable String ticketReference,
            String startedAt,
            String expiresAt,
            @Nullable String endedAt,
            @Nullable String endedBy,
            @Nullable String endReason,
            boolean open) {}
}
