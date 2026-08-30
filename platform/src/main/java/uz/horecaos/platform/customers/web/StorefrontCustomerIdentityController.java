package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.customers.application.CustomerSessionService;
import uz.horecaos.platform.customers.application.CustomerSessionService.Established;
import uz.horecaos.platform.customers.application.CustomerVerificationService;
import uz.horecaos.platform.customers.application.CustomerVerificationService.Challenge;
import uz.horecaos.platform.customers.application.CustomerVerificationService.Grant;
import uz.horecaos.platform.customers.application.CustomerVerificationService.Redemption;
import uz.horecaos.platform.customers.infrastructure.security.CustomerSessionBearerTokenResolver;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * How a customer gets an account (ADR 0015, ADR 0003, ADR 0031).
 *
 * <p>Three of these endpoints are unauthenticated, and unavoidably so: somebody
 * asking for a code has no account and therefore no token. They join the dine-in
 * QR exchange and the payment provider callbacks in that, and, like those, they
 * carry no ADR 0025 capability because there is no principal to hold one. What
 * authorises them instead is possession: of the phone the code is sent to, of the
 * challenge id it was sent against, and of the single-use grant that proves both.
 *
 * <p>{@code POST /sessions} is the last of the three and is what ADR 0051 added: it
 * redeems the grant, finds or creates the account the proven number belongs to,
 * and hands back the bearer the storefront carries afterwards. It is
 * unauthenticated for exactly the same reason as the first two — a customer
 * signing in for the first time has nothing to authenticate with, and the grant
 * <em>is</em> the authorisation. Before it existed there was no way for a
 * storefront customer to obtain a credential this API accepts at all, so the
 * whole surface below was reachable only by a staff token.
 *
 * <p>{@code POST /registrations} is the opposite: it requires a token and exists
 * to turn that token into a durable account. It is {@link CustomerOwned} because
 * the only row it creates is the caller's own. It remains the path for a caller
 * who already holds a realm token — an operator placing an order on somebody's
 * behalf, or a future federated customer — and is not what the storefront uses.
 *
 * <p><strong>The phone number travels in a request body, never in a path or a
 * query.</strong> ADR 0029 forbids personal data in a URL, and a URL is written to
 * every access log, every reverse proxy, and every {@code Referer} the page emits
 * afterwards. For the same reason nothing here returns the number, not even
 * masked: a masked Uzbek mobile is four unknown digits over a known operator
 * prefix, which is not anonymity, and the client already knows what it typed.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/identity")
@Tag(
        name = "Customer identity",
        description = "Requesting a one-time code, proving a phone number, and becoming a customer")
public class StorefrontCustomerIdentityController {

    private final CustomerVerificationService verification;
    private final CustomerSessionService sessions;
    private final CurrentActor currentActor;
    private final String trustedIssuer;

    public StorefrontCustomerIdentityController(
            CustomerVerificationService verification,
            CustomerSessionService sessions,
            CurrentActor currentActor,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String trustedIssuer) {
        this.verification = verification;
        this.sessions = sessions;
        this.currentActor = currentActor;
        this.trustedIssuer = trustedIssuer;
    }

    @PostMapping("/verification-challenges")
    @Operation(
            summary = "Ask for a one-time code",
            description = "Answers the same way whether or not the number already has an account: "
                    + "nothing on this path reads the customer tables, so it cannot leak what it "
                    + "never looks at. Limited per number in the database and per caller in "
                    + "memory, and a new challenge retires any earlier one for the same number.")
    public ResponseEntity<ChallengeResponse> requestCode(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody RequestCodeRequest body,
            HttpServletRequest request) {

        Challenge challenge = verification.issue(tenantId, brandId, body.phone(), callerKey(request));

        // 202 rather than 201. What was created is a challenge, but what the
        // caller cares about is a message that is on its way through somebody
        // else's network, and no status here can promise it arrived.
        return ResponseEntity.accepted()
                .body(new ChallengeResponse(
                        challenge.challengeId(),
                        challenge.expiresAt(),
                        challenge.attemptsAllowed(),
                        challenge.codeLength()));
    }

    @PostMapping("/verification-challenges/{challengeId}/attempts")
    @Operation(
            summary = "Submit a code",
            description = "Costs one of the challenge's attempts however it turns out. A wrong "
                    + "code says how many attempts are left; an unknown, expired, superseded, "
                    + "exhausted or already-used challenge all answer identically. Success "
                    + "returns a single-use grant, which is proof of the number and not a "
                    + "session. POST /sessions is what exchanges it for one (ADR 0051).")
    public ResponseEntity<GrantResponse> submitCode(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID challengeId,
            @Valid @RequestBody SubmitCodeRequest body,
            HttpServletRequest request) {

        Grant grant = verification.verify(tenantId, challengeId, body.code(), callerKey(request));
        return ResponseEntity.ok(new GrantResponse(grant.secret(), grant.expiresAt()));
    }

    @PostMapping("/sessions")
    @Operation(
            summary = "Turn a proven number into a session",
            description = "The step that was missing. Redeems a single-use grant, finds or "
                    + "creates the account the proven number belongs to, and returns an opaque "
                    + "bearer the storefront presents on every later call. Unauthenticated, "
                    + "because a customer signing in for the first time has nothing to "
                    + "authenticate with — the grant is the authorisation, and it is spent here.")
    public ResponseEntity<CustomerSessionResponse> signIn(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody SignInRequest body) {

        Established established = sessions.establish(tenantId, brandId, body.grant());

        return ResponseEntity.status(established.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new CustomerSessionResponse(
                        established.token(), established.expiresAt(), established.accountId(), established.created()));
    }

    @DeleteMapping("/sessions/current")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Sign out",
            description = "Ends the session the caller is holding, and only that one. The "
                    + "session is named by the token in the Authorization header rather than by "
                    + "an id in the path, so there is nothing to edit in order to end somebody "
                    + "else's. Tapping it twice is not an error.")
    public ResponseEntity<Void> signOut(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, HttpServletRequest request) {

        sessions.endCurrent(CustomerSessionBearerTokenResolver.presentedBearer(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/registrations")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Turn a verified number and a token into a customer account",
            description = "The identity comes from the caller's own token — issuer and subject, "
                    + "never a phone number, because a recycled number would otherwise hand one "
                    + "person another's order history. The grant is single-use and is only "
                    + "honoured at the brand it was proved for.")
    public ResponseEntity<RegistrationResponse> register(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody RegisterRequest body) {

        // The subject comes from the verified token and the issuer from
        // configuration, never from the request or from a claim read back out of
        // the token being checked. A subject is unique only within the realm that
        // minted it, so trusting an issuer from the token would let a second
        // trusted realm mint a matching subject and resolve to somebody's account.
        Redemption redemption = verification.redeem(
                tenantId,
                brandId,
                body.grant(),
                trustedIssuer,
                currentActor.get().subject());

        return ResponseEntity.status(redemption.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new RegistrationResponse(redemption.account().accountId(), redemption.created()));
    }

    /**
     * An opaque, stable handle for the caller, for rate limiting and nothing else.
     *
     * <p>Hashed here rather than in the service, so no address reaches the domain
     * module — ADR 0029 keeps that kind of data out, and the module has no use for
     * the address itself. It is never stored: it exists as a key in the ADR 0033
     * limiter's in-memory bucket map.
     *
     * <p>{@code getRemoteAddr} is the real client because
     * {@code server.forward-headers-strategy: framework} is set, so the proxy's
     * {@code X-Forwarded-For} is applied before a handler sees the request. Without
     * that setting every caller behind the edge would share one bucket and the
     * limit would be one limit for the whole platform. It follows that the edge
     * must overwrite that header rather than append to it, which is the ordinary
     * requirement for any address-derived limit.
     */
    private static String callerKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            // A request with no source at all still gets a bucket rather than an
            // exemption. Sharing one bucket is the correct failure here: it is
            // stricter, not looser.
            return "unattributed";
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * @param phone in any of the spellings people here write their own number in.
     *              Canonicalised before it is hashed, so one number cannot get two
     *              rate-limit budgets by being typed two ways
     */
    public record RequestCodeRequest(
            @NotBlank @Size(max = 32) String phone) {}

    /** Never the phone number, and never anything derived from it. */
    public record ChallengeResponse(UUID challengeId, Instant expiresAt, int attemptsAllowed, int codeLength) {}

    public record SubmitCodeRequest(
            @NotBlank @Size(max = 16) String code) {}

    /**
     * @param grant proof of the number, single-use and short-lived. Not a session:
     *              it is what {@code POST /sessions} redeems on the way to one
     *              (ADR 0051)
     */
    public record GrantResponse(String grant, Instant expiresAt) {

        /** A record's generated {@code toString} would print the grant secret. */
        @Override
        public String toString() {
            return "GrantResponse[expiresAt=%s]".formatted(expiresAt);
        }
    }

    public record RegisterRequest(@NotBlank @Size(max = 128) String grant) {}

    public record RegistrationResponse(UUID accountId, boolean created) {}

    public record SignInRequest(@NotBlank @Size(max = 128) String grant) {}

    /**
     * Named {@code CustomerSessionResponse} and not {@code SessionResponse}, and
     * the reason is a bug this cost us once already. OpenAPI schema names are a
     * flat namespace keyed on the simple class name, so a second
     * {@code SessionResponse} — {@code TableSessionController}'s, which is older
     * and published — does not produce two schemas. It produces one, and the
     * generated TypeScript client then describes this endpoint as returning a
     * dine-in table session: {@code businessDate}, {@code partySize},
     * {@code settledTotalMinor}. A client lane told to read the generated client
     * rather than guess read exactly that and coded against a shape no handler
     * has ever returned.
     *
     * @param token     the session bearer, returned once and never again. Presented
     *                  as {@code Authorization: Bearer …} on every later call
     * @param expiresAt when the storefront must ask the customer to prove their
     *                  number again. Returned so a client can see it coming rather
     *                  than discover it mid-basket
     * @param created   true when this sign-in brought the account into existence,
     *                  which is when a storefront should ask for consent rather
     *                  than assume it
     */
    public record CustomerSessionResponse(String token, Instant expiresAt, UUID accountId, boolean created) {

        /** A record's generated {@code toString} would print the session token. */
        @Override
        public String toString() {
            return "CustomerSessionResponse[accountId=%s, expiresAt=%s, created=%s]"
                    .formatted(accountId, expiresAt, created);
        }
    }
}
