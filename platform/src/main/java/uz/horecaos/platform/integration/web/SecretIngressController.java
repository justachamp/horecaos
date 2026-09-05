package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The write-only secret door (ADR 0065): one ingress endpoint, no matching read.
 *
 * <p>This is the entire reason ADR 0065 exists: ADR 0028 says a secret value
 * "never passes through this API" because that rule was written for an operator
 * who can reach OpenBao directly. A tenant cannot, and the owner directed
 * self-service, so this class is the one deliberate exception — everything else
 * ADR 0028 mandates (references in every row, resolution through {@link
 * uz.horecaos.platform.iam.api.secrets.SecretResolver}, values absent from git,
 * chat, and dumps) stands untouched.
 *
 * <p><strong>What makes this a door and not a leak</strong>, each enforced here
 * and covered by {@code SecretIngressControllerEndpointTests}:
 *
 * <ul>
 *   <li>The value exists only in the request body and the one {@link
 *       SecretIngressGateway#write} call. It is never assigned to a field this
 *       class keeps, never placed in a log statement, never placed in an
 *       exception message, and the response type below has no field it could
 *       occupy.
 *   <li>There is no {@code @GetMapping} anywhere in this class, and no other
 *       class exposes one for this path. A reference can be resolved by a
 *       server-side collaborator that already legitimately holds it; nothing
 *       here, or anywhere, lets an HTTP caller read a value back.
 *   <li>The reference is platform-generated end to end —
 *       {@link SecretIngressGateway} mints the opaque id, this class derives the
 *       owner scope from the authenticated tenant path variable, and the
 *       environment segment comes from configuration. A tenant supplies a
 *       category and a value; it never supplies a path.
 *   <li>The audit fact this class writes names the category, the provider
 *       context, and the resulting reference — never the value, which never
 *       reaches {@link AuditFact.Builder#changed}.
 * </ul>
 *
 * <p><strong>Capability.</strong> Gated by {@link
 * Capability#INTEGRATION_INSTALLATION_MANAGE} rather than a new capability or
 * {@link Capability#PAYMENT_MERCHANT_BINDING_MANAGE}, deliberately the lower of
 * the two ADR 0065 names: reaching this door and writing a value nobody can read
 * back is harmless in isolation, because a freshly-minted reference does
 * nothing until some other write attaches it to a live installation or binding.
 * <em>That</em> attachment is what still costs the higher capability — {@code
 * MerchantBindingController.register} and its own rotate endpoint are unchanged,
 * still gated on {@code payment.merchant-binding.manage} — so a principal who
 * can install a provider but not register a merchant binding can write a
 * candidate credential here and still cannot make it operative for a real
 * payment.
 *
 * <p><strong>This path is kept for the published contract, not for a caller.</strong>
 * The operations app's Integrations screen (ADR 0065) is this door's real caller
 * and reaches it through {@link OperationsSecretIngressController}, a thin
 * forward onto {@link #write} at the operations-prefixed mirror of this path.
 * The control-plane app never called this class. {@code OpenApiContractTests}
 * forbids dropping a published path even with no remaining caller, so this class
 * and its {@code /api/v1/control-plane/...} mapping stay exactly as they were —
 * the one implementation of the write-only door, unmodified by the move.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/integrations/secrets")
@Tag(name = "Secret ingress", description = "The ADR 0065 write-only door: values enter, only references leave")
public class SecretIngressController {

    private static final String WRITE_OPERATION = "integration.secret.write";

    /**
     * Fail-closed and strict: this endpoint's entire purpose is an expensive,
     * sensitive write, unlike the read-mostly traffic ADR 0033's {@code
     * perMinute} default assumes.
     */
    private static final RateLimiter.Policy WRITE_POLICY = RateLimiter.Policy.strictPerMinute(20);

    private final SecretIngressGateway door;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;
    private final RateLimiter rateLimiter;

    public SecretIngressController(
            SecretIngressGateway door,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock,
            RateLimiter rateLimiter) {
        this.door = door;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Write a provider credential",
            description = "Writes the value directly into the ADR 0028 secrets manager under a "
                    + "platform-generated reference and returns only that reference. There is no "
                    + "corresponding read endpoint anywhere in the API: pass the returned reference to "
                    + "the installation or merchant-binding call that follows.")
    public ResponseEntity<SecretIngressResponse> write(
            @PathVariable UUID tenantId, @Valid @RequestBody SecretIngressRequest request) {

        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key(
                        WRITE_OPERATION, tenantId.toString(), currentActor.get().subject()),
                WRITE_POLICY);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many secret writes. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }

        SecretReference reference;
        try {
            reference = door.write(request.category(), ownerScopeFor(tenantId), SecretValue.of(request.value()));
        } catch (IllegalArgumentException notTenantWritable) {
            // Never echoes the underlying message verbatim -- it names the
            // rejected category only, which is not sensitive, but keeping the
            // response text under this class's own control (rather than a
            // dependency's) is the same discipline as every other validation
            // failure here.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This door does not accept the " + request.category() + " category");
        }

        audit.record(AuditFact.of("integration.secret_written", AuditClass.SECURITY)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("Secret", UUID.fromString(reference.opaqueId()))
                .because("Tenant wrote a provider credential through the ADR 0065 secret door")
                // Reference and category only -- never the value, which never
                // reaches this method beyond the one write() call above.
                .changed(Map.of(
                        "category", request.category().name(),
                        "providerType", request.providerType(),
                        "reference", reference.toString()))
                .usingCapability(Capability.INTEGRATION_INSTALLATION_MANAGE.code())
                .correlatedBy(reference.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(new SecretIngressResponse(reference.toString()));
    }

    /**
     * Platform-derived, never a caller-supplied string: a stable per-tenant
     * partition so one tenant can never guess or collide with another's owner
     * scope, matching {@code MerchantBindingController}'s own doc example
     * ({@code tenant-42}).
     */
    private static String ownerScopeFor(UUID tenantId) {
        return "tenant-" + tenantId;
    }

    /**
     * @param category namespace-scoped purpose (ADR 0065): restricted to the
     *                  four {@code PROVIDER_*} categories a tenant may hold a
     *                  credential in. {@link
     *                  SecretCategory#tenantWritable()} is checked again
     *                  server-side regardless of what the enum accepts here.
     * @param providerType the connect flow's provider context (e.g. {@code
     *                      "CLICK"}, {@code "TELEGRAM_BOT_API"}) -- carried
     *                      into the audit fact for traceability, never
     *                      interpreted by this endpoint itself
     * @param value the secret value. Exists only in this request body and the
     *              one write call this method makes; never returned, logged,
     *              or placed in any error message
     */
    public record SecretIngressRequest(
            @NotNull SecretCategory category,
            @NotBlank @Size(max = 64) String providerType,
            @NotBlank @Size(max = 4096) String value) {}

    /** The only thing this door ever returns. There is no field a value could occupy. */
    public record SecretIngressResponse(String reference) {}
}
