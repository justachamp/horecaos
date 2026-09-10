package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcGlobalLookup;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcGlobalLookup.Hit;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0083: the control plane's global lookup — one identifier, every
 * tenant, exact matches only, and nothing personal in the answer.
 */
@RestController
@Validated
@Tag(name = "Global lookup", description = "ADR 0083: find an identifier across tenants")
public class GlobalLookupController {

    private final JdbcGlobalLookup lookup;

    public GlobalLookupController(JdbcGlobalLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/api/v1/control-plane/lookup")
    @RequiresCapability(value = Capability.TENANT_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Find an identifier across every tenant",
            description = "An id finds the tenant, brand, location, order, customer, courier, device, "
                    + "installation, POS export or fiscal document it belongs to. Anything else is "
                    + "matched exactly as a tenant slug or name, an order number, a courier's "
                    + "reference, a provider's order or transaction id, or a partner's order "
                    + "reference. Customers and couriers are returned by id only.")
    List<LookupHit> find(@RequestParam @NotBlank @Size(max = 128) String q) {
        return lookup.find(q).stream().map(LookupHit::of).toList();
    }

    public record LookupHit(
            String type,
            String id,
            @org.jspecify.annotations.Nullable String tenantId,
            @org.jspecify.annotations.Nullable String tenantName,
            String matchedOn,
            String label) {

        static LookupHit of(Hit hit) {
            return new LookupHit(
                    hit.type().name(),
                    hit.id().toString(),
                    hit.tenantId() == null ? null : hit.tenantId().toString(),
                    hit.tenantName(),
                    hit.matchedOn().name(),
                    hit.label());
        }
    }
}
