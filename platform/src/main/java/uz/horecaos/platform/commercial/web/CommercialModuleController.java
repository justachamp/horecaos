package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.application.ModuleCatalogService;
import uz.horecaos.platform.commercial.domain.BillingUnit;
import uz.horecaos.platform.commercial.domain.SellableModule;
import uz.horecaos.platform.commercial.domain.TenantModule;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Modules sold beside the plans (ADR 0087): the price list, the authoring
 * acts only HorecaOS staff perform, and which modules each tenant has.
 *
 * <p>Like the plan endpoints, every act that names a tenant is declared at
 * platform scope: giving a tenant a module is HorecaOS selling it, and a
 * tenant-scoped grant that reached it would let a restaurant sell itself one.
 */
@RestController
@Tag(name = "Commercial modules", description = "Sellable modules and the tenants that have them")
public class CommercialModuleController {

    private final ModuleCatalogService modules;
    private final CurrentActor currentActor;

    public CommercialModuleController(ModuleCatalogService modules, CurrentActor currentActor) {
        this.modules = modules;
        this.currentActor = currentActor;
    }

    @GetMapping("/api/v1/control-plane/modules")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "The modules on sale", description = "Activated and not retired; drafts are not quotable.")
    public ResponseEntity<List<ModuleView>> onSale() {
        return ResponseEntity.ok(modules.onSale().stream().map(ModuleView::of).toList());
    }

    @GetMapping("/api/v1/platform-admin/commercial/modules")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every module, drafts and retired ones included",
            description = "The authoring view: each draft names who drafted it, because the person "
                    + "who activates it must be somebody else.")
    public ResponseEntity<List<ModuleView>> all() {
        return ResponseEntity.ok(modules.all().stream().map(ModuleView::of).toList());
    }

    @PostMapping("/api/v1/platform-admin/commercial/modules")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Draft a module",
            description = "The price is integer minor units of its currency per billing unit. The "
                    + "entitlements it names must be features; a module never raises a counted limit.")
    public ResponseEntity<ModuleDrafted> draft(@Valid @RequestBody DraftModuleRequest body) {
        BillingUnit unit;
        try {
            unit = BillingUnit.valueOf(body.billingUnit());
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Unknown billing unit %s".formatted(body.billingUnit()));
        }
        UUID id = modules.draft(
                body.code(),
                body.name(),
                body.description(),
                unit,
                body.currency(),
                body.unitPriceMinor(),
                body.featureKeys() == null ? List.of() : body.featureKeys(),
                actor(),
                body.reason(),
                correlationId());
        return ResponseEntity.ok(new ModuleDrafted(id));
    }

    @PostMapping("/api/v1/platform-admin/commercial/modules/{moduleId}/activation")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_ACTIVATE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Put a module on sale",
            description = "Irreversible: its terms become immutable at the database. Refused when the "
                    + "approver drafted it.")
    public ResponseEntity<Void> activate(@PathVariable UUID moduleId, @Valid @RequestBody ReasonRequest body) {
        modules.activate(moduleId, actor(), body.reason(), correlationId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/platform-admin/commercial/modules/{moduleId}/retirement")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Stop selling a module",
            description = "Tenants that have it keep it, and keep being billed for it, until it is ended for them.")
    public ResponseEntity<Void> retire(@PathVariable UUID moduleId, @Valid @RequestBody ReasonRequest body) {
        modules.retire(moduleId, actor(), body.reason(), correlationId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/modules")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.TENANT)
    @Operation(summary = "Every module the tenant has had", description = "Live ones first.")
    public ResponseEntity<List<TenantModuleView>> tenantModules(@PathVariable UUID tenantId) {
        Map<UUID, SellableModule> byId =
                modules.all().stream().collect(Collectors.toMap(SellableModule::id, Function.identity()));
        return ResponseEntity.ok(modules.tenantModules(tenantId).stream()
                .map(held -> TenantModuleView.of(held, byId.get(held.moduleId())))
                .toList());
    }

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/modules")
    @RequiresCapability(value = Capability.COMMERCIAL_SUBSCRIPTION_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Give a tenant a module",
            description = "A quantity is required exactly when the module is billed per unit. Its "
                    + "features switch on while the tenant's subscription is in force.")
    public ResponseEntity<TenantModuleAdded> add(
            @PathVariable UUID tenantId, @Valid @RequestBody AddModuleRequest body) {
        UUID id = modules.add(tenantId, body.moduleId(), body.quantity(), actor(), body.reason(), correlationId());
        return ResponseEntity.ok(new TenantModuleAdded(id));
    }

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/modules/{tenantModuleId}/end")
    @RequiresCapability(value = Capability.COMMERCIAL_SUBSCRIPTION_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "End a tenant's module",
            description = "Its features switch off now; the month it ended in still bills it.")
    public ResponseEntity<Void> end(
            @PathVariable UUID tenantId, @PathVariable UUID tenantModuleId, @Valid @RequestBody ReasonRequest body) {
        modules.end(tenantId, tenantModuleId, actor(), body.reason(), correlationId());
        return ResponseEntity.noContent().build();
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    /** A module as the price list and the authoring view show it. */
    public record ModuleView(
            UUID moduleId,
            String code,
            String name,
            @Nullable String description,
            String billingUnit,
            ApiMoney unitPrice,
            List<String> featureKeys,
            String status,
            String createdBy,
            @Nullable String approvedBy,
            @Nullable String activatedAt,
            @Nullable String retiredAt) {

        static ModuleView of(SellableModule module) {
            return new ModuleView(
                    module.id(),
                    module.code(),
                    module.name(),
                    module.description(),
                    module.billingUnit().name(),
                    ApiMoney.of(module.unitPriceMinor(), module.currency()),
                    module.featureKeys(),
                    module.status(),
                    module.createdBy(),
                    module.approvedBy(),
                    text(module.activatedAt()),
                    text(module.retiredAt()));
        }
    }

    /** One module one tenant has had. */
    public record TenantModuleView(
            UUID tenantModuleId,
            UUID moduleId,
            String moduleCode,
            String moduleName,
            String billingUnit,
            ApiMoney unitPrice,
            @Nullable Integer quantity,
            String startedAt,
            String startedBy,
            String startReason,
            @Nullable String endedAt,
            @Nullable String endedBy,
            @Nullable String endReason) {

        static TenantModuleView of(TenantModule held, @Nullable SellableModule module) {
            if (module == null) {
                throw new IllegalStateException("A tenant module names a missing module");
            }
            return new TenantModuleView(
                    held.id(),
                    module.id(),
                    module.code(),
                    module.name(),
                    module.billingUnit().name(),
                    ApiMoney.of(module.unitPriceMinor(), module.currency()),
                    held.quantity(),
                    held.startedAt().toString(),
                    held.startedBy(),
                    held.startReason(),
                    text(held.endedAt()),
                    held.endedBy(),
                    held.endReason());
        }
    }

    private static @Nullable String text(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** The id a drafted module was filed under. */
    public record ModuleDrafted(UUID moduleId) {}

    /** The id a tenant's module was filed under. */
    public record TenantModuleAdded(UUID tenantModuleId) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record DraftModuleRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotBlank String billingUnit,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Min(0) long unitPriceMinor,
            List<String> featureKeys,
            @NotBlank @Size(max = 1000) String reason) {}

    public record AddModuleRequest(
            @NotNull UUID moduleId,
            @Min(1) Integer quantity,
            @NotBlank @Size(max = 1000) String reason) {}
}
