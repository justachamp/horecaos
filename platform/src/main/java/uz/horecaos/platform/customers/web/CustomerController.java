package uz.horecaos.platform.customers.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressFields;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.CustomerProfileService.CoordinateSource;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Customer accounts, personal data, and consent (ADR 0015, ADR 0029).
 *
 * <p>Revealing a decrypted contact detail needs a capability of its own and a
 * stated purpose. Seeing that a customer exists and reading their phone number
 * are different levels of access, and the difference between an agent viewing one
 * customer and exporting fifty thousand is exactly what the purpose captures.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/customers")
@Tag(name = "Customers", description = "Customer accounts, contact details, addresses, and consent")
public class CustomerController {

    private final CustomerIdentityService identity;
    private final CustomerProfileService profiles;
    private final ConsentService consent;
    private final CurrentActor currentActor;
    private final String trustedIssuer;

    public CustomerController(CustomerIdentityService identity, CustomerProfileService profiles,
            ConsentService consent, CurrentActor currentActor,
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String trustedIssuer) {
        this.identity = identity;
        this.profiles = profiles;
        this.consent = consent;
        this.currentActor = currentActor;
        this.trustedIssuer = trustedIssuer;
    }

    @PostMapping("/resolve")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(summary = "Resolve the signed-in principal to a durable account",
            description = "Creates one on first sign-in. The identity comes from the caller's own "
                    + "token; there is no way to resolve an account for someone else. Resolution "
                    + "is on issuer and subject only, because a phone number is a contact method "
                    + "and not an identity key.")
    public ResponseEntity<ResolveResponse> resolve(@PathVariable UUID tenantId,
            @Valid @RequestBody ResolveRequest request) {

        // Subject and issuer come from the verified token, never from the body.
        // Taking them from the request would let any caller holding this
        // capability create or claim an account for an arbitrary identity, which
        // is impersonation with extra steps. A support agent acting for a
        // customer needs its own endpoint that records both identities.
        var resolution = identity.resolve(tenantId, request.brandId(),
                trustedIssuer, currentActor.get().subject());

        return ResponseEntity.ok(new ResolveResponse(
                resolution.account().accountId(), resolution.created(), resolution.policy().name()));
    }

    @PostMapping("/{accountId}/contact-points")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(summary = "Add a phone number or email address",
            description = "Accepts a value another account already holds. Refusing would be wrong: "
                    + "households share numbers, and merging on a match is what ADR 0015 forbids.")
    public ResponseEntity<IdResponse> addContact(@PathVariable UUID tenantId,
            @PathVariable UUID accountId, @Valid @RequestBody AddContactRequest request) {
        try {
            return ResponseEntity.ok(new IdResponse(profiles.addContactPoint(
                    tenantId, accountId, request.type(), request.value(), request.primary())));
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    @GetMapping("/{accountId}/contact-points")
    @RequiresCapability(Capability.CUSTOMER_PII_REVEAL)
    @Operation(summary = "Reveal decrypted contact details",
            description = "Requires a stated purpose, recorded as an audit fact.")
    public ResponseEntity<List<CustomerProfileService.RevealedContact>> contacts(
            @PathVariable UUID tenantId, @PathVariable UUID accountId,
            @RequestParam @NotBlank String purpose) {
        return ResponseEntity.ok(profiles.revealContactPoints(tenantId, accountId, purpose));
    }

    @PostMapping("/{accountId}/addresses")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(summary = "Add a delivery address",
            description = "Address lines — including подъезд, этаж and ориентир — are encrypted; "
                    + "coordinates are not, because a courier cannot be routed to a ciphertext "
                    + "and a coordinate identifies a building. The coordinate source is required: "
                    + "an address awaiting geocoding and one that legitimately has no point are "
                    + "different states, and only one of them is worth retrying.")
    public ResponseEntity<IdResponse> addAddress(@PathVariable UUID tenantId,
            @PathVariable UUID accountId, @Valid @RequestBody AddAddressRequest request) {
        try {
            return ResponseEntity.ok(new IdResponse(profiles.addAddress(tenantId, accountId,
                    request.label(), request.fields(), request.deliveryInstructions(),
                    request.latitude(), request.longitude(), request.coordinateSource())));
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    @GetMapping("/{accountId}/addresses")
    @RequiresCapability(Capability.CUSTOMER_PII_REVEAL)
    @Operation(summary = "Reveal decrypted addresses")
    public ResponseEntity<List<CustomerProfileService.RevealedAddress>> addresses(
            @PathVariable UUID tenantId, @PathVariable UUID accountId,
            @RequestParam @NotBlank String purpose) {
        return ResponseEntity.ok(profiles.revealAddresses(tenantId, accountId, purpose));
    }

    @PostMapping("/{accountId}/consent-decisions")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(summary = "Record a consent decision",
            description = "Append-only. A withdrawal supersedes a grant without erasing it, "
                    + "because proving what someone agreed to and when is the obligation.")
    public ResponseEntity<IdResponse> recordConsent(@PathVariable UUID tenantId,
            @PathVariable UUID accountId, @Valid @RequestBody ConsentRequest request) {
        return ResponseEntity.ok(new IdResponse(consent.record(tenantId, accountId,
                request.brandId(), request.purpose(), request.channel(), request.decision(),
                request.policyVersion(), request.source(), request.evidenceReference(),
                request.decidedAt())));
    }

    @GetMapping("/{accountId}/consent-decisions")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(summary = "The full consent history",
            description = "What a subject-access request produces.")
    public ResponseEntity<List<?>> consentHistory(@PathVariable UUID tenantId,
            @PathVariable UUID accountId) {
        return ResponseEntity.ok(consent.history(tenantId, accountId));
    }

    /** Only the brand. The identity is taken from the caller's token. */
    public record ResolveRequest(@NotNull UUID brandId) { }

    public record ResolveResponse(UUID accountId, boolean created, String identityPolicy) { }

    public record AddContactRequest(@NotNull ContactType type,
            @NotBlank @Size(max = 320) String value, boolean primary) { }

    /**
     * @param coordinateSource required rather than inferred from whether
     *                         coordinates arrived, because inferring it would
     *                         make "not geocoded yet" and "this address has no
     *                         point" the same request
     */
    public record AddAddressRequest(@Size(max = 64) String label, @NotNull AddressFields fields,
            @Size(max = 500) String deliveryInstructions,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull CoordinateSource coordinateSource) { }

    public record ConsentRequest(UUID brandId, @NotBlank String purpose, String channel,
            @NotNull ConsentService.Decision decision, @NotBlank String policyVersion,
            @NotNull ConsentService.Source source, String evidenceReference, Instant decidedAt) { }

    public record IdResponse(UUID id) { }
}
