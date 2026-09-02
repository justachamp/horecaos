package uz.horecaos.platform.payments.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.payments.application.MerchantBindingService;
import uz.horecaos.platform.payments.application.MerchantBindingService.RegisterMerchantBindingCommand;
import uz.horecaos.platform.payments.domain.MerchantBinding;
import uz.horecaos.platform.payments.domain.MerchantBindingStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A legal entity's Click service or Payme cashbox, over HTTP (ADR 0013, ADR 0026,
 * ADR 0028).
 *
 * <p>A thin adapter over {@link MerchantBindingService}, the same shape
 * {@code tenancy.web.LegalEntityController} is over {@code LegalEntityService}.
 * Before this controller, nothing wrote {@code payments.merchant_bindings} over
 * HTTP: {@code JdbcPaymentBindingResolver} only reads it, so every binding was
 * hand-written SQL. Every decision — which legal entity may be named, the
 * DRAFT-before-ACTIVE lifecycle, that a live binding is suspended before it is
 * retired — is the service's and the domain's; nothing here duplicates a rule
 * they already enforce.
 *
 * <p>Every endpoint, reads included, requires
 * {@link Capability#PAYMENT_MERCHANT_BINDING_MANAGE}. There is no separate read
 * capability the way {@code legal-entity.read} exists beside
 * {@code legal-entity.manage} — this capability's own javadoc calls it "the
 * highest-consequence configuration action in the module", held by
 * {@code tenant-owner} alone among the tenant bundles, and a binding response
 * names the merchant account a restaurant settles under, which is exactly the
 * fact nobody else is meant to read either. {@code
 * integration.web.ProviderInstallationController.list} is the precedent: it
 * guards its own listing with {@code integration.installation.manage} for the
 * same reason.
 *
 * <p><strong>A secret value never appears in a request or a response body,
 * here or anywhere in this class.</strong> {@code secretReference} is an ADR
 * 0028 handle; the value it names is written to the secrets manager directly,
 * this API only ever carries the reference string, and a response echoes it
 * back exactly as it was sent — never resolved, never logged.
 *
 * <p>{@code @RequiresCapability(mutating = true)} is what carries ADR 0031's
 * {@code Idempotency-Key} requirement, picked up by {@code
 * IdempotencyInterceptor}; no endpoint here declares the header itself.
 * Listing is a plain, unpaginated collection: a tenant's merchant bindings are a
 * small, bounded, administrative registry, not the kind of table ADR 0031's
 * cursor pagination exists for — the same call {@code LegalEntityController}
 * makes for a tenant's legal entities.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/merchant-bindings")
@Tag(
        name = "Merchant bindings",
        description = "Which legal entity settles under which provider account (ADR 0013, ADR 0026)")
public class MerchantBindingController {

    private final MerchantBindingService bindings;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public MerchantBindingController(
            MerchantBindingService bindings, AuditRecorder audit, CurrentActor currentActor, Clock clock) {
        this.bindings = bindings;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @PostMapping
    @RequiresCapability(value = Capability.PAYMENT_MERCHANT_BINDING_MANAGE, mutating = true)
    @Operation(
            summary = "Register a legal entity's merchant account",
            description = "Registered in DRAFT. A draft binding never resolves for a payment -- "
                    + "JdbcPaymentBindingResolver only returns an ACTIVE row -- so typing in an "
                    + "account reference never immediately puts it in front of a customer. The "
                    + "referenced legal entity must belong to this tenant and be ACTIVE.")
    ResponseEntity<MerchantBindingView> register(
            @PathVariable UUID tenantId, @Valid @RequestBody RegisterMerchantBindingRequest request) {

        MerchantBinding binding = bindings.register(tenantId, request.toCommand());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{bindingId}")
                .buildAndExpand(binding.id())
                .toUri();
        return ResponseEntity.created(location).body(MerchantBindingView.of(binding));
    }

    @GetMapping
    @RequiresCapability(Capability.PAYMENT_MERCHANT_BINDING_MANAGE)
    @Operation(summary = "List the tenant's registered merchant bindings")
    List<MerchantBindingView> list(@PathVariable UUID tenantId) {
        return bindings.list(tenantId).stream().map(MerchantBindingView::of).toList();
    }

    @GetMapping("/{bindingId}")
    @RequiresCapability(Capability.PAYMENT_MERCHANT_BINDING_MANAGE)
    @Operation(summary = "Get a merchant binding")
    MerchantBindingView get(@PathVariable UUID tenantId, @PathVariable UUID bindingId) {
        return MerchantBindingView.of(bindings.require(tenantId, bindingId));
    }

    @PostMapping("/{bindingId}/activate")
    @RequiresCapability(value = Capability.PAYMENT_MERCHANT_BINDING_MANAGE, mutating = true)
    @Operation(
            summary = "Activate a merchant binding",
            description = "Only an ACTIVE binding resolves for a payment. Permitted from DRAFT or "
                    + "SUSPENDED; refused if this legal entity already has a different ACTIVE "
                    + "binding for the same provider.")
    MerchantBindingView activate(
            @PathVariable UUID tenantId, @PathVariable UUID bindingId, @RequestParam int expectedVersion) {
        return MerchantBindingView.of(bindings.activate(tenantId, bindingId, expectedVersion));
    }

    @PostMapping("/{bindingId}/suspend")
    @RequiresCapability(value = Capability.PAYMENT_MERCHANT_BINDING_MANAGE, mutating = true)
    @Operation(
            summary = "Suspend a merchant binding",
            description = "Stops the binding from resolving for a new payment. Nothing about a "
                    + "payment it already settled changes. Permitted from ACTIVE only.")
    MerchantBindingView suspend(
            @PathVariable UUID tenantId, @PathVariable UUID bindingId, @RequestParam int expectedVersion) {
        return MerchantBindingView.of(bindings.suspend(tenantId, bindingId, expectedVersion));
    }

    @PostMapping("/{bindingId}/archive")
    @RequiresCapability(value = Capability.PAYMENT_MERCHANT_BINDING_MANAGE, mutating = true)
    @Operation(
            summary = "Retire a merchant binding",
            description = "Ends its life on the platform; the row survives so every payment it "
                    + "ever settled still resolves an account reference. Permitted from DRAFT or "
                    + "SUSPENDED -- an ACTIVE binding is suspended first, so retiring one is never "
                    + "the first anyone hears that it stopped resolving.")
    MerchantBindingView archive(
            @PathVariable UUID tenantId, @PathVariable UUID bindingId, @RequestParam int expectedVersion) {
        return MerchantBindingView.of(bindings.archive(tenantId, bindingId, expectedVersion));
    }

    @PostMapping("/{bindingId}/secret-rotations")
    @RequiresCapability(value = Capability.PAYMENT_MERCHANT_BINDING_MANAGE, mutating = true)
    @Operation(
            summary = "Rotate this binding's credential through the write-only door",
            description = "ADR 0065: writes the new value into the ADR 0028 secrets manager under a "
                    + "freshly-minted reference and swaps this binding onto it under its expected "
                    + "version. Neither Click's nor Payme's Merchant API offers a harmless outbound call "
                    + "to verify a credential before committing to it -- the same absence "
                    + "ProviderCapabilityReconciliationService's own doc comment records -- so unlike "
                    + "the Telegram installation rotation this cannot verify before flipping; whether the "
                    + "new value is right is proven the next time a real payment settles through it.")
    ResponseEntity<MerchantBindingView> rotateSecret(
            @PathVariable UUID tenantId,
            @PathVariable UUID bindingId,
            @RequestParam int expectedVersion,
            @Valid @RequestBody RotateMerchantBindingSecretRequest request) {

        MerchantBinding rotated = bindings.rotateSecret(tenantId, bindingId, expectedVersion, request.value());

        audit.record(AuditFact.of("payment.merchant_binding_secret_rotated", AuditClass.SECURITY)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("MerchantBinding", bindingId)
                .because(request.reason())
                // Reference only, never the value -- the same discipline
                // ProviderInstallationController.rotateSecret's own audit
                // comment documents for the installation path.
                .changed(Map.of("newReference", rotated.secretReference().toString()))
                .usingCapability(Capability.PAYMENT_MERCHANT_BINDING_MANAGE.code())
                .correlatedBy(bindingId.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(MerchantBindingView.of(rotated));
    }

    /**
     * @param secretReference an ADR 0028 reference in the
     *                        {@code horecaos:{environment}:provider_payment:{owner}:{id}}
     *                        format the {@code payments.merchant_bindings} check
     *                        constraint enforces. The value it names is written to
     *                        the secrets manager directly and never passes through
     *                        this API -- this field is the handle, never the
     *                        credential
     * @param callbackPathSegment the segment that names this binding in the
     *                             provider's inbound callback URL. Not a secret and
     *                             must not be treated as one: it is guessable by
     *                             design, and the provider's own signature or Basic
     *                             credential is what authenticates the callback
     * @param effectiveFrom        when this binding starts resolving. Backdating is
     *                              permitted
     */
    record RegisterMerchantBindingRequest(
            @NotNull UUID legalEntityId,
            @NotNull PaymentProviderType providerType,
            @NotNull UUID installationId,
            @NotNull UUID integrationBindingId,
            @NotBlank @Size(max = 255) String merchantAccountReference,
            @Size(max = 255) @Nullable String merchantUserReference,
            @Size(max = 255) @Nullable String merchantIdReference,

            @NotBlank
            @Size(max = 512)
            @Pattern(regexp = "horecaos:[^:]+:provider_payment:[^:]+:[^:]+")
            @Schema(
                    description = "An ADR 0028 secret reference. Never a secret value.",
                    example = "horecaos:prod:provider_payment:tenant-42:click-service-1")
            String secretReference,

            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{7,63}")
            String callbackPathSegment,

            boolean supportsReversal,
            boolean supportsPartnerFiscalization,
            @NotNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveUntil) {

        RegisterMerchantBindingCommand toCommand() {
            return new RegisterMerchantBindingCommand(
                    legalEntityId,
                    providerType,
                    installationId,
                    integrationBindingId,
                    merchantAccountReference,
                    merchantUserReference,
                    merchantIdReference,
                    secretReference,
                    callbackPathSegment,
                    supportsReversal,
                    supportsPartnerFiscalization,
                    effectiveFrom,
                    effectiveUntil);
        }
    }

    /** Never carries a secret value, only its ADR 0028 reference. */
    record MerchantBindingView(
            UUID id,
            UUID legalEntityId,
            PaymentProviderType providerType,
            UUID installationId,
            UUID integrationBindingId,
            String merchantAccountReference,
            @Nullable String merchantUserReference,
            @Nullable String merchantIdReference,
            String secretReference,
            String callbackPathSegment,
            boolean supportsReversal,
            boolean supportsPartnerFiscalization,
            MerchantBindingStatus status,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveUntil,
            int version,
            @Nullable OffsetDateTime lastSecretRotatedAt) {

        static MerchantBindingView of(MerchantBinding binding) {
            return new MerchantBindingView(
                    binding.id(),
                    binding.legalEntityId(),
                    binding.providerType(),
                    binding.installationId(),
                    binding.integrationBindingId(),
                    binding.merchantAccountReference(),
                    binding.merchantUserReference().orElse(null),
                    binding.merchantIdReference().orElse(null),
                    binding.secretReference().toString(),
                    binding.callbackPathSegment(),
                    binding.supportsReversal(),
                    binding.supportsPartnerFiscalization(),
                    binding.status(),
                    binding.effectiveFrom(),
                    binding.effectiveUntil(),
                    binding.version(),
                    binding.lastSecretRotatedAt());
        }
    }

    /**
     * @param value the new credential, ADR 0065's door. Exists only in this
     *              request body and the one write call this endpoint makes with
     *              it; never returned, logged, or placed in an error message
     */
    record RotateMerchantBindingSecretRequest(
            @NotBlank @Size(max = 4096) String value,
            @NotBlank @Size(max = 1000) String reason) {}
}
