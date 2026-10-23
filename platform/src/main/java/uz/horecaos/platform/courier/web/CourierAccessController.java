package uz.horecaos.platform.courier.web;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.courier.application.CourierAccessService;
import uz.horecaos.platform.courier.application.CourierAccessService.Inspection;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Where a courier uses the link they were texted (ADR 0178).
 *
 * <p>Unauthenticated by necessity, in the category the staff invitation and password reset endpoints are in: a
 * courier who has no password has no session to present. What authorises both calls is possession of the
 * one-time token, 256 random bits texted to the number the identity provider holds; it travels in the body,
 * never a URL, so it reaches no access log. Both are limited per caller address (ADR 0033), strictly, so an
 * unavailable limiter refuses rather than admits.
 *
 * <p>On the operations prefix because the accept page is, for now, a route of the operations console
 * (ADR 0178); it moves to the courier client with a one-line change of the link's origin.
 */
@RestController
@Tag(name = "Courier access links", description = "A courier setting their password from the link they were texted")
public class CourierAccessController {

    private static final RateLimiter.Policy LIMIT = RateLimiter.Policy.strictPerMinute(10);

    private final CourierAccessService access;
    private final RateLimiter rateLimiter;

    public CourierAccessController(CourierAccessService access, RateLimiter rateLimiter) {
        this.access = access;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/v1/operations/courier-access/inspect")
    @Operation(
            operationId = "inspectCourierAccessLink",
            summary = "Read a courier's texted link before using it",
            description = "Which kind it is (INVITE or RESET), the language to show it in and when it stops "
                    + "working. Answers not-found with a reason of INVALID or EXPIRED.")
    public Inspection inspect(@Valid @RequestBody CourierAccessTokenRequest body, HttpServletRequest request) {
        limit("courier.access.inspect", request);
        return access.inspect(body.token());
    }

    @PostMapping("/api/v1/operations/courier-access/accept")
    @Operation(
            operationId = "acceptCourierAccessLink",
            summary = "Set the courier's password from the texted link",
            description = "Sets the password at the identity provider, spends the link and ends every other "
                    + "session the account holds. The realm's password policy applies; a refusal is "
                    + "VALIDATION_FAILED naming the rule, and the link then still works. Answers 200 with "
                    + "sessionsEnded, which is false when the password was set and the other sessions could "
                    + "not be ended.")
    public CourierAccessAcceptance accept(
            @Valid @RequestBody CourierAccessAcceptRequest body, HttpServletRequest request) {
        limit("courier.access.accept", request);
        return new CourierAccessAcceptance(access.accept(body.token(), body.password()));
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

    public record CourierAccessTokenRequest(
            @NotBlank @Size(max = 128) String token) {

        /** The token is a bearer credential; a record's generated toString would print it. */
        @Override
        public String toString() {
            return "CourierAccessTokenRequest[token=<redacted>]";
        }
    }

    public record CourierAccessAcceptRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "CourierAccessAcceptRequest[<redacted>]";
        }
    }

    public record CourierAccessAcceptance(boolean sessionsEnded) {}
}
