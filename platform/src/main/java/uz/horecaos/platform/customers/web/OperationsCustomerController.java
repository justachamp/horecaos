package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.CustomerProfileService.MaskedAddress;
import uz.horecaos.platform.customers.application.OperatorAddressCaptureService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Row 1.3a: create-on-miss, for a caller whose only grant is at {@code
 * LOCATION} scope.
 *
 * <p>{@link CustomerController#createManually} already does exactly this write
 * — the tenant's whole customer base is a tenant-wide surface with no location
 * of its own, so that endpoint is declared at {@code TENANT} scope and there is
 * nowhere in its {@code /api/v1/tenants/{tenantId}/customers} path for a
 * location identifier to come from. {@code LOCATION_STAFF} and {@code
 * LOCATION_MANAGER} — the New order screen's own persona (ADR 0039, orders.md
 * §5.3) — are granted at {@code LOCATION} scope by construction ({@link
 * uz.horecaos.platform.iam.api.PlatformRole#LOCATION_STAFF}'s own {@code
 * scopeType()}), and {@link uz.horecaos.platform.iam.api.ResourceScope#covers}
 * is one-directional: a location grant never reaches its own tenant, the same
 * way {@code JdbcAuthorizationService}'s own doc says a location grant never
 * reaches its brand. Widening {@code createManually} to accept a location scope
 * would misdescribe an endpoint every other caller (Settings' own Customers
 * grid, a tenant administrator with no location context at all) reaches
 * tenant-wide. So this is a second, narrower HTTP surface over the identical
 * write, in the shape {@code OperationsOrderController}'s own {@code
 * customer-lookups} beside it already established for the identical reason —
 * see that endpoint's own doc.
 *
 * <p>Reuses {@link CustomerIdentityService} and {@link CustomerProfileService}
 * directly rather than calling {@link CustomerController} over HTTP: both
 * controllers live in this module, so there is no boundary to cross, and an
 * HTTP call to itself would trade one <code>@Transactional</code> write for two
 * requests with no transaction spanning them.
 *
 * <p><strong>The same shape serves the delivery address (ADR 0177, rows 1.3 and
 * 1.3b).</strong> A delivery order names a saved address of the resolved
 * customer, and {@code CustomerController}'s address routes are {@code TENANT}
 * scoped and gated on {@code CUSTOMER_PII_REVEAL} and {@code CUSTOMER_MANAGE}
 * -- neither of which the New order screen's persona holds, on purpose. The
 * three routes below are the narrow surface {@link
 * Capability#CUSTOMER_ADDRESS_CAPTURE} grants: a <em>masked</em> list of the
 * customer's saved addresses, adding one with its pin, and setting the pin of a
 * saved one. They never return the encrypted lines, and the tenant routes stay
 * as they are for the Customers screen. Each is rate limited per tenant and
 * operator (and fails closed, because the list is a read against the customer
 * base's addresses) and audited by {@link OperatorAddressCaptureService}.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/customers")
@Tag(name = "Customers", description = "Customer accounts, contact details, addresses, and consent")
public class OperationsCustomerController {

    private final CustomerIdentityService identity;
    private final CustomerProfileService profiles;
    private final CurrentActor currentActor;
    private final OperatorAddressCaptureService addressCapture;
    private final RateLimiter rateLimiter;

    static final RateLimiter.Policy LIST_LIMIT = RateLimiter.Policy.strictPerMinute(60);
    static final RateLimiter.Policy ADD_LIMIT = RateLimiter.Policy.strictPerMinute(20);
    static final RateLimiter.Policy PIN_LIMIT = RateLimiter.Policy.strictPerMinute(30);

    public OperationsCustomerController(
            CustomerIdentityService identity,
            CustomerProfileService profiles,
            CurrentActor currentActor,
            OperatorAddressCaptureService addressCapture,
            RateLimiter rateLimiter) {
        this.identity = identity;
        this.profiles = profiles;
        this.currentActor = currentActor;
        this.addressCapture = addressCapture;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @RequiresCapability(value = Capability.CUSTOMER_CREATE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Create a customer by hand, from a location-scoped screen",
            description = "orders.md §5.3's create-on-miss, for LOCATION_STAFF/LOCATION_MANAGER "
                    + "(row 1.3a) — the same account CustomerController#createManually opens, "
                    + "through a path this role's own grant actually covers. No Keycloak "
                    + "principal link (ADR 0015/0039); starts non-contactable, with no consent "
                    + "decision recorded for it, on the same 'absence of a decision is not "
                    + "consent' reasoning ConsentService already applies. V0295's origin = "
                    + "OPERATOR and the staff subject who typed it are recorded exactly as "
                    + "createManually records them, so a marketing export filters this account "
                    + "out the same way regardless of which of the two endpoints opened it.")
    public ResponseEntity<CustomerController.IdResponse> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CreateCustomerRequest request) {
        var account = identity.createAccountWithoutPrincipal(
                tenantId,
                brandId,
                CustomerIdentityService.ORIGIN_OPERATOR,
                currentActor.get().subject());
        profiles.addContactPoint(tenantId, account.accountId(), ContactType.PHONE, request.phone(), true);
        if (request.displayName() != null && !request.displayName().isBlank()) {
            // A brand-new account from insertAccount's own DEFAULT 1, exactly as
            // CustomerController#createManually reads it.
            profiles.updateProfile(tenantId, account.accountId(), 1, request.displayName(), null, null);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new CustomerController.IdResponse(account.accountId()));
    }

    @GetMapping("/{accountId}/addresses")
    @RequiresCapability(value = Capability.CUSTOMER_ADDRESS_CAPTURE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The saved addresses of a customer, masked, for the New order address pane",
            description = "ADR 0177, rows 1.3/1.3b. Label, the street without house number or flat, "
                    + "city, district, the point and where it came from, and the version a pin is set "
                    + "at; never the entrance, floor, flat, landmark, second line, postal code or "
                    + "delivery instructions. The customer must be one this brand may serve "
                    + "(a brand-isolated account of another brand answers 404). Every call is a "
                    + "SECURITY-class audit fact carrying the count only. The unmasked list stays "
                    + "GET /api/v1/tenants/{tenantId}/customers/{accountId}/addresses behind "
                    + "customer.pii.reveal.")
    public ResponseEntity<List<MaskedAddress>> addresses(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID accountId) {
        limit("customer.address.capture.list", tenantId, LIST_LIMIT);
        try {
            return ResponseEntity.ok(addressCapture.list(tenantId, brandId, locationId, accountId, actor()));
        } catch (OperatorAddressCaptureService.CustomerNotVisibleException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        }
    }

    @PostMapping("/{accountId}/addresses")
    @RequiresCapability(value = Capability.CUSTOMER_ADDRESS_CAPTURE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Save a delivery address for a customer, from the New order address pane",
            description = "ADR 0177. The same body CustomerController#addAddress takes, with one "
                    + "restriction: the coordinate source is OPERATOR_PIN with a point, or "
                    + "NOT_GEOCODED / LANDMARK_ONLY without one. GEOCODER, CUSTOMER_PIN and "
                    + "LEGACY_UNSOURCED are refused (a geocoder's answer is a suggestion a person "
                    + "confirms by looking at it, ADR 0145). An address with no pin is saved honestly "
                    + "and refused for delivery until it has one. Returns the new address id.")
    public ResponseEntity<CustomerController.IdResponse> addAddress(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID accountId,
            @Valid @RequestBody CustomerController.AddAddressRequest request) {
        limit("customer.address.capture.add", tenantId, ADD_LIMIT);
        try {
            UUID id = addressCapture.add(
                    tenantId,
                    brandId,
                    locationId,
                    accountId,
                    request.label(),
                    request.fields(),
                    request.deliveryInstructions(),
                    request.latitude(),
                    request.longitude(),
                    request.coordinateSource(),
                    actor());
            return ResponseEntity.status(HttpStatus.CREATED).body(new CustomerController.IdResponse(id));
        } catch (OperatorAddressCaptureService.CustomerNotVisibleException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    @PutMapping("/{accountId}/addresses/{addressId}/pin")
    @RequiresCapability(value = Capability.CUSTOMER_ADDRESS_CAPTURE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Place or move the pin of a saved address, from the New order address pane",
            description = "ADR 0177. Writes the point and the source OPERATOR_PIN of one saved address "
                    + "and nothing else; the address text is neither read back nor rewritten, so a "
                    + "role that may not see it can still place the pin that makes the address "
                    + "deliverable. Requires If-Match (the version the masked list returned); a "
                    + "stale version answers 409. Returns the address, masked, at its new version.")
    public ResponseEntity<MaskedAddress> setPin(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID accountId,
            @PathVariable UUID addressId,
            @Valid @RequestBody PinRequest body,
            HttpServletRequest request) {
        limit("customer.address.capture.pin", tenantId, PIN_LIMIT);
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            MaskedAddress moved = addressCapture.setPin(
                    tenantId,
                    brandId,
                    locationId,
                    accountId,
                    addressId,
                    (int) expected,
                    body.latitude(),
                    body.longitude(),
                    actor());
            return ResponseEntity.ok()
                    .eTag(AggregateVersion.toETag(moved.version()))
                    .body(moved);
        } catch (OperatorAddressCaptureService.CustomerNotVisibleException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        } catch (CustomerProfileService.AddressNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such address");
        } catch (CustomerProfileService.StaleRecordException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private void limit(String operation, UUID tenantId, RateLimiter.Policy policy) {
        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key(
                        operation, tenantId.toString(), currentActor.get().subject()),
                policy);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many address requests. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    /**
     * The point of a pin. Both coordinates are required and boxed: Jackson 3 refuses a missing
     * primitive with a 400 before validation runs, and a missing latitude is a client mistake worth
     * a field-level answer, not a malformed-body one.
     */
    public record PinRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    /**
     * {@code brandId} is the path's, never the body's — the one difference
     * from {@code CustomerController.CreateCustomerRequest}, because this
     * controller's own path already names the brand.
     */
    public record CreateCustomerRequest(
            @NotBlank @Size(max = 32) String phone,
            @Size(max = 200) @Nullable String displayName) {}
}
