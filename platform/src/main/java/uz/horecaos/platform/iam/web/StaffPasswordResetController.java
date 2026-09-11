package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.application.passwordresets.PasswordResetService;
import uz.horecaos.platform.iam.application.passwordresets.PasswordResetService.ResetInspection;
import uz.horecaos.platform.iam.application.passwordresets.StaffConsole;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * "Forgot password?" for staff (ADR 0098), on both staff surfaces.
 *
 * <p>Mounted under {@code control-plane} and {@code operations} separately for
 * the reason {@link StaffSessionController} spells out at length: ADR 0057's
 * invariant is that every published path belongs to exactly one surface group,
 * so a single shared path would land in neither console's generated client.
 * The two prefixes differ in exactly one thing — the console recorded on the
 * reset, which decides the origin its emailed link points at.
 *
 * <p>Unauthenticated by necessity, the category ADR 0062's sign-in and ADR
 * 0097's invitation endpoints are in: somebody who has forgotten their
 * password has no session to present. What authorises the second and third
 * calls is possession of the one-time token, 256 random bits emailed to the
 * address the identity provider holds; it travels in the body, never a URL, so
 * it reaches no access log. All three are limited per caller address (ADR
 * 0033), strictly, so an unavailable limiter refuses rather than admits.
 *
 * <p><b>The request endpoint answers 202 and nothing else, always.</b> Unknown
 * login, known login, an account with no address, an identity provider that is
 * down: one answer, one shape, no body. Anything else would make this an
 * unauthenticated directory of the platform's staff — the enumeration ADR 0062
 * already refuses on sign-in.
 */
@RestController
@Tag(name = "Staff password resets", description = "A staff member who forgot their password (ADR 0098)")
public class StaffPasswordResetController {

    /**
     * Ten a minute per caller address.
     *
     * <p>This limit bounds one machine walking a list of logins to see which
     * ones produce email. It is not the per-account cap and cannot be: a
     * distributed scan spends one request per address. What bounds an attack on
     * a single account is in the store — one live reset per account, and the
     * cooldown in the upsert that keeps a link already delivered from being
     * replaced by one a stranger triggered (ADR 0098 Decision 3).
     */
    private static final RateLimiter.Policy LIMIT = RateLimiter.Policy.strictPerMinute(10);

    private final PasswordResetService resets;
    private final RateLimiter rateLimiter;

    public StaffPasswordResetController(PasswordResetService resets, RateLimiter rateLimiter) {
        this.resets = resets;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/v1/control-plane/auth/password-resets")
    @Operation(
            summary = "Ask for a control-plane password reset",
            description = "Always answers 202 with no body, whether or not the login names an account, so "
                    + "that this endpoint cannot be used to discover who has an account.")
    public ResponseEntity<Void> requestControlPlane(
            @Valid @RequestBody PasswordResetRequest body, HttpServletRequest request) {
        return requestReset(body, StaffConsole.CONTROL_PLANE, request);
    }

    @PostMapping("/api/v1/operations/auth/password-resets")
    @Operation(
            summary = "Ask for an operations password reset",
            description = "Always answers 202 with no body, whether or not the login names an account, so "
                    + "that this endpoint cannot be used to discover who has an account.")
    public ResponseEntity<Void> requestOperations(
            @Valid @RequestBody PasswordResetRequest body, HttpServletRequest request) {
        return requestReset(body, StaffConsole.OPERATIONS, request);
    }

    @PostMapping("/api/v1/control-plane/auth/password-resets/inspect")
    @Operation(
            summary = "Read a control-plane reset link before using it",
            description = "Which console the link belongs to, the account masked, and when it stops "
                    + "working. Answers not-found with a reason of INVALID or EXPIRED.")
    public ResetInspection inspectControlPlane(
            @Valid @RequestBody PasswordResetTokenRequest body, HttpServletRequest request) {
        return inspect(body, request);
    }

    @PostMapping("/api/v1/operations/auth/password-resets/inspect")
    @Operation(
            summary = "Read an operations reset link before using it",
            description = "Which console the link belongs to, the account masked, and when it stops "
                    + "working. Answers not-found with a reason of INVALID or EXPIRED.")
    public ResetInspection inspectOperations(
            @Valid @RequestBody PasswordResetTokenRequest body, HttpServletRequest request) {
        return inspect(body, request);
    }

    @PostMapping("/api/v1/control-plane/auth/password-resets/accept")
    @Operation(
            summary = "Set a new control-plane password",
            description = "Sets the password at the identity provider, spends the link and ends every "
                    + "other session the account holds. The realm's password policy applies; a refusal "
                    + "names the rule. Answers 204.")
    public ResponseEntity<Void> acceptControlPlane(
            @Valid @RequestBody PasswordResetAcceptRequest body, HttpServletRequest request) {
        return accept(body, request);
    }

    @PostMapping("/api/v1/operations/auth/password-resets/accept")
    @Operation(
            summary = "Set a new operations password",
            description = "Sets the password at the identity provider, spends the link and ends every "
                    + "other session the account holds. The realm's password policy applies; a refusal "
                    + "names the rule. Answers 204.")
    public ResponseEntity<Void> acceptOperations(
            @Valid @RequestBody PasswordResetAcceptRequest body, HttpServletRequest request) {
        return accept(body, request);
    }

    private ResponseEntity<Void> requestReset(
            PasswordResetRequest body, StaffConsole console, HttpServletRequest request) {
        limit("iam.password-reset.request", request);
        // The correlation id here is the hashed caller address rather than a
        // value minted per request: nobody on this path is authenticated, and
        // what an investigator reading a burst of these facts needs to join
        // them by is the machine that asked. The login never travels with them.
        resets.request(body.login().strip(), console, body.locale(), callerKey(request));
        return ResponseEntity.accepted().build();
    }

    private ResetInspection inspect(PasswordResetTokenRequest body, HttpServletRequest request) {
        limit("iam.password-reset.inspect", request);
        return resets.inspect(body.token());
    }

    private ResponseEntity<Void> accept(PasswordResetAcceptRequest body, HttpServletRequest request) {
        limit("iam.password-reset.accept", request);
        resets.accept(body.token(), body.password(), UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
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

    /** A user name or an email address, and the language to write in. */
    public record PasswordResetRequest(
            @NotBlank @Size(max = 255) String login,
            @Pattern(regexp = "uz|ru|en") @Nullable String locale) {

        /** A record's generated {@code toString} would print the login. */
        @Override
        public String toString() {
            return "PasswordResetRequest[login=<redacted>, locale=" + locale + "]";
        }
    }

    public record PasswordResetTokenRequest(
            @NotBlank @Size(max = 128) String token) {

        /** A record's generated {@code toString} would print the token. */
        @Override
        public String toString() {
            return "PasswordResetTokenRequest[token=<redacted>]";
        }
    }

    public record PasswordResetAcceptRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(min = 12, max = 128) String password) {

        /** A record's generated {@code toString} would print the token and the password. */
        @Override
        public String toString() {
            return "PasswordResetAcceptRequest[token=<redacted>, password=<redacted>]";
        }
    }
}
