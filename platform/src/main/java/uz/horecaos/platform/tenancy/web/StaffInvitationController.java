package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.InvitationAccepted;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.InvitationInspection;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The owner's side of an invitation (ADR 0097): the operations console's
 * set-your-password page calls these two.
 *
 * <p>Unauthenticated by necessity, the category ADR 0062's sign-in is in: the
 * owner has no password yet, so there is no session to present. What
 * authorises both is possession of the one-time token, 256 random bits the
 * owner received by email; it travels in the body, never a URL, so it reaches
 * no access log. Both are limited per caller address (ADR 0033), strictly, so
 * an unavailable limiter refuses rather than admits.
 */
@RestController
@Tag(name = "Staff invitations", description = "An invited owner sets up their account (ADR 0097)")
public class StaffInvitationController {

    private static final RateLimiter.Policy LIMIT = RateLimiter.Policy.strictPerMinute(10);

    private final OwnerInvitationService invitations;
    private final RateLimiter rateLimiter;

    public StaffInvitationController(OwnerInvitationService invitations, RateLimiter rateLimiter) {
        this.invitations = invitations;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/v1/operations/invitations/inspect")
    @Operation(
            summary = "Read an invitation before accepting it",
            description = "Whose invitation it is and where it was sent, masked. Answers not-found with a "
                    + "reason of INVALID or EXPIRED for a link that cannot be used.")
    public InvitationInspection inspect(@Valid @RequestBody InvitationTokenRequest body, HttpServletRequest request) {
        limit("tenancy.invitation.inspect", request);
        return invitations.inspect(body.token());
    }

    @PostMapping("/api/v1/operations/invitations/accept")
    @Operation(
            summary = "Set up the invited account",
            description = "Sets the owner's name and password at the identity provider, marks their address "
                    + "verified and spends the link. The realm's password policy applies; a refusal names "
                    + "the rule.")
    public ResponseEntity<InvitationAccepted> accept(
            @Valid @RequestBody InvitationAcceptRequest body, HttpServletRequest request) {
        limit("tenancy.invitation.accept", request);
        return ResponseEntity.ok(invitations.accept(
                body.token(),
                body.firstName(),
                body.lastName(),
                body.password(),
                UUID.randomUUID().toString()));
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
}
