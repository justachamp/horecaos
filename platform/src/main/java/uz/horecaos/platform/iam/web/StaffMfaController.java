package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Authenticator;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.MfaStatus;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Requirement;
import uz.horecaos.platform.iam.application.StaffAuthService.StaffSession;
import uz.horecaos.platform.iam.application.mfa.StaffMfaService;
import uz.horecaos.platform.iam.application.mfa.StaffMfaService.Confirmation;
import uz.horecaos.platform.iam.application.mfa.StaffMfaService.Enrolment;
import uz.horecaos.platform.iam.web.StaffSessionController.StaffSessionResponse;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A staff member's own second factor: enrolling an authenticator, listing them, removing one
 * (ADR 0148), on both staff surfaces for the reason {@link StaffSessionController} gives.
 *
 * <p>Two of the four endpoints are reachable without a session, and that is the design. The
 * enrolment pair authenticates a caller by a bearer <em>or</em> by the enrolment ticket a refused
 * sign-in carried, because the account a rule has just locked out of the console has no session
 * to present. Both re-prove the current password, so a ticket alone enrols nothing, and a ticket
 * is useless on any account but the one it was issued for and on any endpoint but these two.
 * {@code SecurityConfiguration} permits exactly those two paths, each with its reason. The list
 * and the removal need a session and get it from the default chain.
 *
 * <p>Neither the secret, the sealed token, a code, a password nor an authenticator's label is
 * ever logged or put in a URL: they travel in request and response bodies only.
 */
@RestController
@Tag(
        name = "Staff second factor",
        description = "A staff member's own TOTP authenticators, held by Keycloak (ADR 0148)")
public class StaffMfaController {

    private final StaffMfaService mfa;

    public StaffMfaController(StaffMfaService mfa) {
        this.mfa = mfa;
    }

    // ------------------------------------------------------------ control plane

    @PostMapping("/api/v1/control-plane/auth/mfa/enrolments")
    @Operation(
            summary = "Begin enrolling an authenticator (control plane)",
            description = "Re-proves the current password, generates a TOTP secret and returns it sealed "
                    + "(10 minutes, this account only) with the otpauth URI for the QR code. Nothing is "
                    + "stored. Authenticated by a session, or by the enrolmentTicket a refused sign-in "
                    + "answered with.")
    public Enrolment beginControlPlane(@Valid @RequestBody BeginEnrolmentRequest body) {
        return begin(body);
    }

    @PostMapping("/api/v1/control-plane/auth/mfa/enrolments/confirm")
    @Operation(
            summary = "Confirm an authenticator with its first code (control plane)",
            description = "Registers the credential with Keycloak, proves it with a real sign-in, and deletes "
                    + "it again if the code does not verify. 204 with a session; 201 with the new session "
                    + "when the enrolment began from a ticket.")
    public ResponseEntity<StaffSessionResponse> confirmControlPlane(@Valid @RequestBody ConfirmEnrolmentRequest body) {
        return confirm(body);
    }

    @GetMapping("/api/v1/control-plane/auth/mfa/authenticators")
    @Operation(summary = "The caller's own authenticators (control plane)")
    public OwnMfaResponse ownControlPlane() {
        return own();
    }

    @DeleteMapping("/api/v1/control-plane/auth/mfa/authenticators/{authenticatorId}")
    @Operation(
            summary = "Remove one of the caller's authenticators (control plane)",
            description = "Needs the password and a valid code; charges the same attempt budget as sign-in; "
                    + "never removes the last authenticator.")
    public ResponseEntity<Void> removeControlPlane(
            @PathVariable String authenticatorId, @Valid @RequestBody RemoveAuthenticatorRequest body) {
        return remove(authenticatorId, body);
    }

    // --------------------------------------------------------------- operations

    @PostMapping("/api/v1/operations/auth/mfa/enrolments")
    @Operation(
            summary = "Begin enrolling an authenticator (operations)",
            description = "As on the control plane: re-proves the current password and returns the sealed "
                    + "secret and the otpauth URI.")
    public Enrolment beginOperations(@Valid @RequestBody BeginEnrolmentRequest body) {
        return begin(body);
    }

    @PostMapping("/api/v1/operations/auth/mfa/enrolments/confirm")
    @Operation(
            summary = "Confirm an authenticator with its first code (operations)",
            description = "As on the control plane: 204 with a session, 201 with the new session from a ticket.")
    public ResponseEntity<StaffSessionResponse> confirmOperations(@Valid @RequestBody ConfirmEnrolmentRequest body) {
        return confirm(body);
    }

    @GetMapping("/api/v1/operations/auth/mfa/authenticators")
    @Operation(summary = "The caller's own authenticators (operations)")
    public OwnMfaResponse ownOperations() {
        return own();
    }

    @DeleteMapping("/api/v1/operations/auth/mfa/authenticators/{authenticatorId}")
    @Operation(
            summary = "Remove one of the caller's authenticators (operations)",
            description = "As on the control plane: password and a valid code, same budget, never the last one.")
    public ResponseEntity<Void> removeOperations(
            @PathVariable String authenticatorId, @Valid @RequestBody RemoveAuthenticatorRequest body) {
        return remove(authenticatorId, body);
    }

    // ------------------------------------------------------------------- shared

    private Enrolment begin(BeginEnrolmentRequest body) {
        Caller caller = caller(body.enrolmentTicket());
        return mfa.begin(caller.subject(), caller.viaTicket(), body.password());
    }

    private ResponseEntity<StaffSessionResponse> confirm(ConfirmEnrolmentRequest body) {
        Caller caller = caller(body.enrolmentTicket());
        Confirmation confirmation = mfa.confirm(
                caller.subject(), caller.viaTicket(), body.sealedSecret(), body.code(), body.password(), body.label());
        StaffSession session = confirmation.session();
        if (session == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(StaffSessionResponse.of(session));
    }

    private OwnMfaResponse own() {
        return OwnMfaResponse.of(mfa.ownStatus(requireSession()));
    }

    private ResponseEntity<Void> remove(String authenticatorId, RemoveAuthenticatorRequest body) {
        mfa.remove(requireSession(), authenticatorId, body.password(), body.code());
        return ResponseEntity.noContent().build();
    }

    /**
     * The caller: the session's subject when there is one, otherwise the account a ticket names.
     * A session wins over a ticket, so a signed-in person is never somebody else because of a
     * ticket left in a request.
     */
    private Caller caller(@Nullable String enrolmentTicket) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String sessionSubject = sessionSubject(authentication);
        if (sessionSubject != null) {
            return new Caller(sessionSubject, false);
        }
        if (enrolmentTicket == null || enrolmentTicket.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in, or present the enrolment ticket.");
        }
        return new Caller(mfa.subjectOfTicket(enrolmentTicket), true);
    }

    private static String requireSession() {
        String subject = sessionSubject(SecurityContextHolder.getContext().getAuthentication());
        if (subject == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        return subject;
    }

    private static @Nullable String sessionSubject(@Nullable Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt && authentication.isAuthenticated()) {
            return jwt.getToken().getSubject();
        }
        return null;
    }

    private record Caller(String subject, boolean viaTicket) {}

    // --------------------------------------------------------------- bodies

    public record BeginEnrolmentRequest(
            @NotBlank @Size(max = 255) String password,
            @Nullable @Size(max = 2048) String enrolmentTicket) {

        @Override
        public String toString() {
            return "BeginEnrolmentRequest[redacted]";
        }
    }

    public record ConfirmEnrolmentRequest(
            @NotBlank @Size(max = 2048) String sealedSecret,

            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be six digits")
            String code,

            @NotBlank @Size(max = 255) String password,
            @Nullable @Size(max = 64) String label,
            @Nullable @Size(max = 2048) String enrolmentTicket) {

        @Override
        public String toString() {
            return "ConfirmEnrolmentRequest[redacted]";
        }
    }

    public record RemoveAuthenticatorRequest(
            @NotBlank @Size(max = 255) String password,

            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be six digits")
            String code) {

        @Override
        public String toString() {
            return "RemoveAuthenticatorRequest[redacted]";
        }
    }

    /** One authenticator, as its owner and an administrator see it: an id, the name given, the date. */
    public record AuthenticatorResponse(
            String id, @Nullable String label, @Nullable Instant createdAt) {

        static AuthenticatorResponse of(Authenticator authenticator) {
            return new AuthenticatorResponse(authenticator.id(), authenticator.label(), authenticator.createdAt());
        }
    }

    public record OwnMfaResponse(
            boolean enrolled, List<AuthenticatorResponse> authenticators, Requirement requirement, int maximum) {

        static OwnMfaResponse of(MfaStatus status) {
            return new OwnMfaResponse(
                    status.enrolled(),
                    status.authenticators().stream()
                            .map(AuthenticatorResponse::of)
                            .toList(),
                    status.requirement(),
                    StaffMfaService.MAXIMUM_AUTHENTICATORS);
        }
    }
}
