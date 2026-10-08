package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.staff.StaffSelfAuthorized;
import uz.horecaos.platform.iam.application.sessions.DeviceLabel;
import uz.horecaos.platform.iam.application.sessions.EndReason;
import uz.horecaos.platform.iam.application.sessions.StaffSessionService;
import uz.horecaos.platform.iam.application.sessions.StaffSessionService.Availability;
import uz.horecaos.platform.iam.application.sessions.StaffSessionService.Item;
import uz.horecaos.platform.iam.application.sessions.StaffSessionService.Reason;
import uz.horecaos.platform.iam.application.sessions.StaffSessionService.SessionList;
import uz.horecaos.platform.iam.application.sessions.StaffSessionService.State;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * «Мой профиль» → «Сеансы» (ADR 0176, gap map row {@code X.5}): the caller's own sessions, and
 * the two acts that end them.
 *
 * <p>Authorised by {@link StaffSelfAuthorized}, exactly as {@link StaffSelfController} is: the
 * capability is held at some scope in the tenant, and <strong>no handler takes an account id, so
 * every one acts on the token's subject</strong>. There is no manager view of a colleague's
 * sessions and no manager act that ends them (ADR 0176 Decision 5): a Keycloak session belongs to
 * the account, which may serve another company too.
 *
 * <p>Rate limits (ADR 0033): the list 30 a minute, the terminations 5 an hour, per person and
 * failing closed. A termination requires an {@code Idempotency-Key}, so a double click ends the
 * sessions once and replays the same answer.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/staff/me/sessions")
@Tag(name = "My sessions", description = "A staff member's own sign-in history and active sessions (ADR 0176)")
public class StaffSessionsController {

    private static final RateLimiter.Policy LIST_LIMIT = RateLimiter.Policy.perMinute(30);
    private static final RateLimiter.Policy TERMINATE_LIMIT = new RateLimiter.Policy(5, Duration.ofHours(1), false);
    private static final int MAXIMUM_SESSION_ID = 64;

    private final StaffSessionService sessions;
    private final CurrentActor currentActor;
    private final RateLimiter rateLimiter;

    public StaffSessionsController(StaffSessionService sessions, CurrentActor currentActor, RateLimiter rateLimiter) {
        this.sessions = sessions;
        this.currentActor = currentActor;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Operation(
            summary = "My sessions",
            description = "The signed-in account's sessions, newest activity first. Keycloak decides which are "
                    + "live; the platform's sign-in record names the device (browser, system, form), and a "
                    + "session with no record has a null deviceLabel and reads «Неизвестное устройство». "
                    + "`current` marks the session whose `sid` is this token's. A session is the account's, "
                    + "shared by every company the person works for. `keycloak: UNAVAILABLE` means Keycloak "
                    + "could not be asked and the list is the platform's own record. No address, header or "
                    + "position is ever returned.")
    public OwnSessionsResponse list(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt token) {
        limit("staff.self.sessions.list", tenantId, LIST_LIMIT);
        SessionList list = sessions.list(tenantId, subject(), sessionIdOf(token));
        return OwnSessionsResponse.of(list);
    }

    @PostMapping("/terminations")
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Idempotent
    @Operation(
            summary = "Sign out everywhere",
            description = "Ends every session of the account at Keycloak, this device included, marks this "
                    + "company's history `SIGNED_OUT_EVERYWHERE` and writes one SECURITY fact with the count of "
                    + "active sessions. Needs an Idempotency-Key and one of the closed reason codes. 204 on "
                    + "success; 503 IDENTITY_PROVIDER_UNAVAILABLE when Keycloak could not be reached, in which "
                    + "case nothing was ended. A request already holding an access token may still succeed "
                    + "for a few minutes. 5 an hour.")
    public ResponseEntity<Void> terminateAll(@PathVariable UUID tenantId, @Valid @RequestBody TerminationRequest body) {
        requireScope(body, Scope.ALL);
        limit("staff.self.sessions.terminate", tenantId, TERMINATE_LIMIT);
        sessions.terminateAll(tenantId, subject(), body.reasonCode(), StaffMemberController.correlation());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/terminations")
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Idempotent
    @Operation(
            summary = "End one session",
            description = "Ends one of the caller's own sessions at Keycloak. A session id that is not the "
                    + "caller's answers 404 as an unknown one. Same body and rules as sign-out-everywhere, "
                    + "with scope ONE. 5 an hour, shared with sign-out-everywhere.")
    public ResponseEntity<Void> terminateOne(
            @PathVariable UUID tenantId, @PathVariable String sessionId, @Valid @RequestBody TerminationRequest body) {
        requireScope(body, Scope.ONE);
        if (sessionId.length() > MAXIMUM_SESSION_ID || sessionId.isBlank()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such session.");
        }
        limit("staff.self.sessions.terminate", tenantId, TERMINATE_LIMIT);
        sessions.terminateOne(tenantId, subject(), sessionId, body.reasonCode(), StaffMemberController.correlation());
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- plumbing

    private String subject() {
        return currentActor.get().subject();
    }

    private static @Nullable String sessionIdOf(@Nullable Jwt token) {
        if (token != null && token.getClaim("sid") instanceof String sid && !sid.isBlank()) {
            return sid;
        }
        return null;
    }

    private static void requireScope(TerminationRequest body, Scope expected) {
        if (body.scope() != expected) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This route takes scope " + expected.name(), Map.of("field", "scope"));
        }
    }

    private void limit(String operation, UUID tenantId, RateLimiter.Policy policy) {
        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(operation, tenantId.toString(), subject()), policy);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many requests. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    // ----------------------------------------------------------------- contracts

    public enum Scope {
        ALL,
        ONE
    }

    /** Every field boxed or nullable-checked: Jackson 3 refuses a missing primitive. */
    public record TerminationRequest(
            @NotNull Scope scope, @NotNull Reason reasonCode) {}

    public record DeviceLabelResponse(DeviceLabel.Browser browser, DeviceLabel.Os os, DeviceLabel.Form form) {}

    public record OwnSessionResponse(
            String id,
            @Nullable DeviceLabelResponse deviceLabel,
            Instant signedInAt,
            Instant lastSeenAt,
            boolean current,
            State state,
            @Nullable EndReason endReason) {

        static OwnSessionResponse of(Item item) {
            DeviceLabel label = item.deviceLabel();
            return new OwnSessionResponse(
                    item.id(),
                    label == null ? null : new DeviceLabelResponse(label.browser(), label.os(), label.form()),
                    item.signedInAt(),
                    item.lastSeenAt(),
                    item.current(),
                    item.state(),
                    item.endReason());
        }
    }

    public record OwnSessionsResponse(Availability keycloak, List<OwnSessionResponse> items) {

        static OwnSessionsResponse of(SessionList list) {
            return new OwnSessionsResponse(
                    list.keycloak(),
                    list.items().stream().map(OwnSessionResponse::of).toList());
        }
    }
}
