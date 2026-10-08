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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.application.StaffAuthService;
import uz.horecaos.platform.iam.application.StaffAuthService.StaffSession;

/**
 * Staff sign-in on a first-party page inside each app, with the backend taking
 * the credentials to Keycloak (ADR 0062).
 *
 * <p>One controller mounted under both staff surfaces on purpose, the way
 * {@link GrantController} already spans {@code /control-plane/} and a second
 * prefix from one class: {@code control-plane} and {@code operations} each get
 * their own path so the endpoint lands in exactly one ADR 0057 OpenAPI surface
 * group per prefix and therefore in that app's own generated typed client —
 * the {@code OpenApiContractTests} invariant that every published path belongs
 * to exactly one group would otherwise be unsatisfiable by a single shared
 * path. Both prefixes are thin wrappers over the same {@link StaffAuthService}
 * call; there is exactly one {@code horecaos-staff-login} Keycloak client
 * behind them, not two.
 *
 * <p>All six methods are unauthenticated by design, the same category
 * {@code StorefrontCustomerIdentityController}'s sign-in surface is in: sign-in
 * is authorised by the credentials themselves, and refresh/sign-out are
 * authorised by possession of the refresh token carried in the request body —
 * never by a bearer, because the whole point of the refresh endpoint is to
 * work when the access token has already expired. {@code SecurityConfiguration}
 * permits exactly these six paths, one at a time, with a comment explaining
 * why each is safe to leave open.
 */
@RestController
@Tag(name = "Staff sign-in", description = "First-party staff sign-in against Keycloak (ADR 0062)")
public class StaffSessionController {

    private final StaffAuthService auth;

    public StaffSessionController(StaffAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/api/v1/control-plane/auth/sessions")
    @Operation(
            summary = "Sign in to the control plane",
            description = "Exchanges a username and password with Keycloak over the OAuth2 direct "
                    + "grant on the backend's own confidential client; the browser never talks to "
                    + "Keycloak. Wrong password and unknown username answer identically to avoid "
                    + "user enumeration. An account with a second factor sends its six-digit code "
                    + "as otp: a missing code answers 401 MFA_REQUIRED and a wrong one 401 "
                    + "MFA_CODE_INVALID, both only once the password is confirmed; the code budget "
                    + "answers 429 RATE_LIMIT_EXCEEDED. An account that must have a second factor "
                    + "and has none answers 403 MFA_ENROLMENT_REQUIRED with an enrolmentTicket (ADR 0148).")
    public ResponseEntity<StaffSessionResponse> signInControlPlane(
            @Valid @RequestBody StaffSignInRequest body, HttpServletRequest request) {
        return signIn(body, request);
    }

    @PostMapping("/api/v1/operations/auth/sessions")
    @Operation(
            summary = "Sign in to operations",
            description = "Exchanges a username and password with Keycloak over the OAuth2 direct "
                    + "grant on the backend's own confidential client; the browser never talks to "
                    + "Keycloak. Wrong password and unknown username answer identically to avoid "
                    + "user enumeration. The optional otp field and the MFA_REQUIRED, "
                    + "MFA_CODE_INVALID and MFA_ENROLMENT_REQUIRED answers are the control-plane "
                    + "endpoint's (ADR 0148).")
    public ResponseEntity<StaffSessionResponse> signInOperations(
            @Valid @RequestBody StaffSignInRequest body, HttpServletRequest request) {
        return signIn(body, request);
    }

    @PostMapping("/api/v1/control-plane/auth/sessions/refresh")
    @Operation(summary = "Refresh a control-plane session", description = "Proxies the refresh grant.")
    public ResponseEntity<StaffSessionResponse> refreshControlPlane(@Valid @RequestBody StaffRefreshRequest body) {
        return refresh(body);
    }

    @PostMapping("/api/v1/operations/auth/sessions/refresh")
    @Operation(summary = "Refresh an operations session", description = "Proxies the refresh grant.")
    public ResponseEntity<StaffSessionResponse> refreshOperations(@Valid @RequestBody StaffRefreshRequest body) {
        return refresh(body);
    }

    @DeleteMapping("/api/v1/control-plane/auth/sessions/current")
    @Operation(
            summary = "Sign out of the control plane",
            description = "Revokes the refresh token at Keycloak. Always succeeds from the caller's "
                    + "point of view, whatever Keycloak answers.")
    public ResponseEntity<Void> signOutControlPlane(@Valid @RequestBody StaffLogoutRequest body) {
        return signOut(body);
    }

    @DeleteMapping("/api/v1/operations/auth/sessions/current")
    @Operation(
            summary = "Sign out of operations",
            description = "Revokes the refresh token at Keycloak. Always succeeds from the caller's "
                    + "point of view, whatever Keycloak answers.")
    public ResponseEntity<Void> signOutOperations(@Valid @RequestBody StaffLogoutRequest body) {
        return signOut(body);
    }

    private ResponseEntity<StaffSessionResponse> signIn(StaffSignInRequest body, HttpServletRequest request) {
        StaffSession session = auth.signIn(
                body.username(),
                body.password(),
                body.otp(),
                rateLimitKey(request, body.username()),
                // Read only to be reduced to a closed device label (ADR 0176); never stored or logged.
                request.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(StaffSessionResponse.of(session));
    }

    private ResponseEntity<StaffSessionResponse> refresh(StaffRefreshRequest body) {
        StaffSession session = auth.refresh(body.refreshToken());
        return ResponseEntity.ok(StaffSessionResponse.of(session));
    }

    private ResponseEntity<Void> signOut(StaffLogoutRequest body) {
        auth.signOut(body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * A per-IP-plus-username handle for the ADR 0033 limiter (ADR 0029): the
     * address and the username are hashed together into one opaque bucket key
     * rather than kept as two live values, mirroring
     * {@code StorefrontCustomerIdentityController.callerKey}. Combining both
     * into a single key is deliberate — a limiter keyed on the address alone
     * lets an attacker spray many usernames from one machine at full speed
     * once any one budget is exhausted by an unrelated username, and a limiter
     * keyed on the username alone lets a botnet spray one username from many
     * addresses; keying on the pair bounds exactly the attempt this endpoint
     * exists to slow down.
     *
     * <p>{@code getRemoteAddr} is the real client because {@code
     * server.forward-headers-strategy: framework} is set (see the identical
     * note on {@code StorefrontCustomerIdentityController}).
     */
    private static String rateLimitKey(HttpServletRequest request, String username) {
        String address = request.getRemoteAddr();
        String key = (address == null || address.isBlank() ? "unattributed" : address) + " "
                + username.toLowerCase(Locale.ROOT).strip();
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * @param otp the six-digit code from an authenticator app, for an account that holds one
     *     (ADR 0148). Absent on a first attempt: the answer {@code MFA_REQUIRED} is what tells the
     *     page to ask for it. A boxed, optional field, because Jackson 3 refuses a missing
     *     primitive and a client that does not know about the second step must keep working.
     */
    public record StaffSignInRequest(
            @NotBlank @Size(max = 255) String username,
            @NotBlank @Size(max = 255) String password,

            @Nullable @Pattern(regexp = "^[0-9]{6}$", message = "must be six digits")
            String otp) {

        /** A record's generated {@code toString} would print the password and the code. */
        @Override
        public String toString() {
            return "StaffSignInRequest[username=<redacted>]";
        }
    }

    public record StaffRefreshRequest(
            @NotBlank @Size(max = 4096) String refreshToken) {

        /** A record's generated {@code toString} would print the token. */
        @Override
        public String toString() {
            return "StaffRefreshRequest[refreshToken=<redacted>]";
        }
    }

    public record StaffLogoutRequest(
            @NotBlank @Size(max = 4096) String refreshToken) {

        /** A record's generated {@code toString} would print the token. */
        @Override
        public String toString() {
            return "StaffLogoutRequest[refreshToken=<redacted>]";
        }
    }

    /**
     * The Keycloak-issued session a signed-in staff member holds.
     *
     * @param tokenType              typically {@code Bearer}, straight from
     *                               Keycloak — the value both apps already
     *                               send back in their {@code Authorization}
     *                               header
     * @param refreshTokenExpiresAt null when the refresh token has no fixed
     *                              expiry to report (verified live against the
     *                              dev realm: an offline-scoped refresh token
     *                              answers {@code refresh_expires_in: 0}
     *                              rather than a duration). Absent, not zero
     *                              — a client that defaulted a missing value
     *                              to "now" would treat every fresh session as
     *                              already over.
     */
    public record StaffSessionResponse(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            @Nullable Instant refreshTokenExpiresAt,
            String tokenType,
            boolean mfaEnrolmentOffered) {

        static StaffSessionResponse of(StaffSession session) {
            return new StaffSessionResponse(
                    session.accessToken(),
                    session.refreshToken(),
                    session.accessTokenExpiresAt(),
                    session.refreshTokenExpiresAt(),
                    session.tokenType(),
                    session.mfaEnrolmentOffered());
        }

        /** A record's generated {@code toString} would print both tokens. */
        @Override
        public String toString() {
            return "StaffSessionResponse[accessTokenExpiresAt=%s, refreshTokenExpiresAt=%s]"
                    .formatted(accessTokenExpiresAt, refreshTokenExpiresAt);
        }
    }
}
