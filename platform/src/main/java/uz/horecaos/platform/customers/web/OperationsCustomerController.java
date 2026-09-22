package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

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
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/customers")
@Tag(name = "Customers", description = "Customer accounts, contact details, addresses, and consent")
public class OperationsCustomerController {

    private final CustomerIdentityService identity;
    private final CustomerProfileService profiles;
    private final CurrentActor currentActor;

    public OperationsCustomerController(
            CustomerIdentityService identity, CustomerProfileService profiles, CurrentActor currentActor) {
        this.identity = identity;
        this.profiles = profiles;
        this.currentActor = currentActor;
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

    /**
     * {@code brandId} is the path's, never the body's — the one difference
     * from {@code CustomerController.CreateCustomerRequest}, because this
     * controller's own path already names the brand.
     */
    public record CreateCustomerRequest(
            @NotBlank @Size(max = 32) String phone,
            @Size(max = 200) @Nullable String displayName) {}
}
