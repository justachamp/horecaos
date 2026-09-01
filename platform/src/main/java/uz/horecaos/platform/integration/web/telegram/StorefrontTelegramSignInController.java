package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CustomerTelegramSignIn;
import uz.horecaos.platform.integration.provider.telegram.TelegramAuthLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramAuthLinkService.ClaimResult;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotIdentityResolver;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotIdentityResolver.Installation;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * "Continue with Telegram" (ADR 0063): mints the deep-link code a customer with
 * no account yet uses to sign in through the brand's bot, and polls it back into
 * a session once the bot's own {@code request_contact} handshake redeems it.
 *
 * <p>Both endpoints are unauthenticated, and unavoidably so — the same reasoning
 * {@code StorefrontCustomerIdentityController}'s own class doc gives for its
 * three identity endpoints: a customer with no account has no token to present,
 * and requiring one here would mean requiring an account in order to get one.
 * What authorises the poll is possession of an unguessable code this same
 * caller minted moments earlier; nothing about the code, the phone or the
 * session is ever exposed to a caller who does not already hold it.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/telegram")
@Tag(
        name = "Telegram sign-in",
        description = "Continue with Telegram: mint a sign-in code and poll it into a session (ADR 0063)")
public class StorefrontTelegramSignInController {

    private static final String MINT_OPERATION = "integration.telegram.auth.mint";

    /** ADR 0033: an outer wall against one caller minting codes without limit — the code's own short TTL bounds the rest. */
    private static final RateLimiter.Policy MINT_PER_CALLER = RateLimiter.Policy.strictPerMinute(6);

    private final TelegramAuthLinkService authLinks;
    private final CustomerTelegramSignIn signIn;
    private final TelegramBotIdentityResolver botIdentity;
    private final RateLimiter rateLimiter;

    public StorefrontTelegramSignInController(
            TelegramAuthLinkService authLinks,
            CustomerTelegramSignIn signIn,
            TelegramBotIdentityResolver botIdentity,
            RateLimiter rateLimiter) {
        this.authLinks = authLinks;
        this.signIn = signIn;
        this.botIdentity = botIdentity;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/sign-in-codes")
    @Operation(
            summary = "Mint a Telegram sign-in code",
            description = "Open the returned deep link (or send it to the bot as \"/start <code>\") to "
                    + "continue in Telegram. The code expires shortly and is single-use.")
    public ResponseEntity<SignInCodeResponse> issueCode(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, HttpServletRequest request) {

        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key(MINT_OPERATION, tenantId.toString(), callerKey(request)), MINT_PER_CALLER);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many sign-in links requested. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }

        String username = requireBotUsername(tenantId);
        String code = authLinks.issueCode(tenantId, brandId);
        return ResponseEntity.ok(
                new SignInCodeResponse(code, "https://t.me/%s?start=auth_%s".formatted(username, code)));
    }

    @GetMapping("/sign-in-codes/{code}")
    @Operation(
            summary = "Poll a Telegram sign-in code",
            description = "PENDING until the bot's request_contact handshake redeems the code, then "
                    + "SIGNED_IN with a session bearer -- returned once, exactly as POST .../identity/sessions "
                    + "returns one, just arriving through a poll instead of a request/response round trip.")
    public ResponseEntity<SignInPollResponse> poll(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable String code) {

        ClaimResult claim = authLinks.claimSession(tenantId, brandId, code);
        return switch (claim) {
            case ClaimResult.Unknown ignored ->
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such sign-in code");
            case ClaimResult.Pending ignored -> ResponseEntity.ok(SignInPollResponse.pending());
            case ClaimResult.Expired ignored -> ResponseEntity.ok(SignInPollResponse.expired());
            case ClaimResult.AlreadyClaimed ignored -> ResponseEntity.ok(SignInPollResponse.alreadyClaimed());
            case ClaimResult.Ready ready -> {
                CustomerTelegramSignIn.Session session =
                        signIn.establishSession(tenantId, brandId, ready.accountId(), ready.accountCreated());
                yield ResponseEntity.ok(SignInPollResponse.signedIn(session));
            }
        };
    }

    private String requireBotUsername(UUID tenantId) {
        Installation installation = requireInstallation(tenantId);
        return botIdentity
                .resolveUsername(tenantId, installation)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTERNAL_ERROR, "Could not reach Telegram to resolve the bot's own identity"));
    }

    private Installation requireInstallation(UUID tenantId) {
        return botIdentity
                .activeInstallation(tenantId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.UNPROCESSABLE_STATE, "No Telegram bot is configured yet"));
    }

    /**
     * {@code StorefrontCustomerIdentityController.callerKey}'s own construction,
     * repeated here rather than shared: an opaque, hashed, never-stored handle
     * for the ADR 0033 limiter's bucket, and nothing about the caller's address
     * ever reaches this module beyond that.
     */
    private static String callerKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            return "unattributed";
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record SignInCodeResponse(String code, String deepLink) {}

    /**
     * @param status PENDING, EXPIRED, ALREADY_CLAIMED, or SIGNED_IN
     * @param token  the session bearer. Present only for SIGNED_IN, and only
     *               once — the same "returned once, in the response that
     *               established it, and never again" discipline
     *               {@code CustomerSessionService.Established} states for the
     *               OTP path, held here across a poll instead of a single
     *               request/response
     */
    public record SignInPollResponse(
            String status,
            @Nullable String token,
            @Nullable Instant expiresAt,
            @Nullable UUID accountId,
            @Nullable Boolean accountCreated) {

        static SignInPollResponse pending() {
            return new SignInPollResponse("PENDING", null, null, null, null);
        }

        static SignInPollResponse expired() {
            return new SignInPollResponse("EXPIRED", null, null, null, null);
        }

        static SignInPollResponse alreadyClaimed() {
            return new SignInPollResponse("ALREADY_CLAIMED", null, null, null, null);
        }

        static SignInPollResponse signedIn(CustomerTelegramSignIn.Session session) {
            return new SignInPollResponse(
                    "SIGNED_IN", session.token(), session.expiresAt(), session.accountId(), session.accountCreated());
        }

        /** A record's generated {@code toString} would print the session token. */
        @Override
        public String toString() {
            return "SignInPollResponse[status=%s]".formatted(status);
        }
    }
}
