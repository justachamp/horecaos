package uz.horecaos.platform.migration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.api.MigrationOwnershipPort;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Who owns writes for a capability, right now (ADR 0024).
 *
 * <p>Read-only, and it is the only read of the migration control plane that has
 * to give the same answer as the gate rather than a reconstruction of it. During
 * an incident the first question is which system accepted the last order for a
 * branch, and an operator who answers it by reading the scope table and applying
 * the precedence rule by hand will eventually apply it wrongly — the whole reason
 * location-then-brand-then-tenant resolution exists is that the three levels
 * coexist. So this endpoint asks {@link MigrationOwnershipPort} the same question
 * every other module's write path is stopped by.
 *
 * <p>A separate resource from the scope it resolves to, because the two are not
 * the same fact. A scope is a row somebody opened; ownership is what that row,
 * its state, and the rows above it currently mean, and a capability no scope
 * covers still has an owner.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/migration/ownership")
@Tag(name = "Migration ownership", description = "Which system may write a capability, at a resolved scope")
public class MigrationOwnershipController {

    private final MigrationOwnershipPort ownership;

    public MigrationOwnershipController(MigrationOwnershipPort ownership) {
        this.ownership = ownership;
    }

    /**
     * @param brandId    omit to ask at tenant level
     * @param locationId omit to ask above the branch
     */
    @GetMapping
    @RequiresCapability(value = Capability.MIGRATION_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Resolve capability ownership at a tenant, brand, or branch",
            description = "Most specific scope wins: location, then brand, then tenant. A "
                    + "capability no scope covers resolves to legacy ownership, never to unowned, "
                    + "because a capability the migration has not reached is not thereby unowned.")
    OwnershipView resolve(
            @RequestParam UUID tenantId,
            @RequestParam MigrationCapability capability,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID locationId) {

        return OwnershipView.of(ownership.ownershipOf(tenantId, capability, brandId, locationId));
    }
}
