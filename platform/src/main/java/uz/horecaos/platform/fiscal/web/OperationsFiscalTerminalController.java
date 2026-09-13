package uz.horecaos.platform.fiscal.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
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
import uz.horecaos.platform.fiscal.application.FiscalTerminalService;
import uz.horecaos.platform.fiscal.application.FiscalTerminalService.RegisterTerminalCommand;
import uz.horecaos.platform.fiscal.domain.FiscalTerminal;
import uz.horecaos.platform.fiscal.domain.FiscalTerminalHealth;
import uz.horecaos.platform.fiscal.domain.FiscalTerminalKind;
import uz.horecaos.platform.fiscal.domain.FiscalTerminalStatus;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The branch's own fiscal-capable equipment, over HTTP (ADR 0038 lines
 * 503-513, Settings 10.7 Tab 2).
 *
 * <p>Without this controller {@code fiscal.fiscal_terminals} was a schema
 * with nothing to call: no operator could register a location's POS box,
 * see whether it was answering, or say which terminal a cash tender prints
 * on — and {@code CheckoutSettlementPlanner.responsibilityOf} had no way to
 * ask whether cash's {@code TERMINAL} responsibility applied anywhere.
 *
 * <p>Register and list live at brand scope, matching {@code
 * CatalogAuthoringController}'s reasoning: a location's own terminals are a
 * small, bounded, IT-administration list, not the kind of table ADR 0031's
 * cursor pagination exists for. The remaining actions are per-terminal.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/fiscal-terminals")
@Tag(name = "Fiscal terminals", description = "ADR 0038 fiscal-capable equipment")
public class OperationsFiscalTerminalController {

    private final FiscalTerminalService terminals;

    public OperationsFiscalTerminalController(FiscalTerminalService terminals) {
        this.terminals = terminals;
    }

    @PostMapping
    @RequiresCapability(value = Capability.FISCAL_TERMINAL_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Register a location's fiscal terminal",
            description = "Registered ACTIVE. A terminal cannot claim IssueFiscalReceipt without "
                    + "a provider binding — the database refuses it, and this endpoint reports the "
                    + "refusal as a Problem Details response rather than a 500.")
    ResponseEntity<FiscalTerminalView> register(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody RegisterTerminalRequest request) {

        FiscalTerminal terminal = terminals.register(
                tenantId,
                new RegisterTerminalCommand(
                        brandId,
                        request.locationId(),
                        request.legalEntityId(),
                        request.kind(),
                        request.providerBindingId(),
                        request.terminalReference(),
                        request.capabilitySnapshot() == null ? Map.of() : request.capabilitySnapshot()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{terminalId}")
                .buildAndExpand(terminal.id())
                .toUri();
        return ResponseEntity.created(location).body(FiscalTerminalView.of(terminal));
    }

    @GetMapping
    @RequiresCapability(value = Capability.FISCAL_TERMINAL_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "List a brand's fiscal terminals",
            description = "Sorted the way settings.md asks: failing health first, never checked "
                    + "next, healthy last — an operator opens this screen to find what is broken.")
    List<FiscalTerminalView> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return terminals.listForBrand(tenantId, brandId).stream()
                .map(FiscalTerminalView::of)
                .toList();
    }

    @GetMapping("/{terminalId}")
    @RequiresCapability(value = Capability.FISCAL_TERMINAL_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Get a fiscal terminal")
    FiscalTerminalView get(@PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID terminalId) {
        return FiscalTerminalView.of(terminals.require(tenantId, brandId, terminalId));
    }

    @PostMapping("/{terminalId}/health-checks")
    @RequiresCapability(value = Capability.FISCAL_TERMINAL_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Record the outcome of a connectivity check",
            description = "Settings 10.7 Tab 2's «Проверить связь». Records what the operator "
                    + "observed; the device/hardware integration itself stays declined (ADR 0038), "
                    + "so this writes evidence rather than performing a live probe.")
    FiscalTerminalView checkHealth(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID terminalId,
            @RequestParam int expectedVersion,
            @Valid @RequestBody HealthCheckRequest request) {
        return FiscalTerminalView.of(
                terminals.checkHealth(tenantId, brandId, terminalId, expectedVersion, request.outcome()));
    }

    @PostMapping("/{terminalId}/suspend")
    @RequiresCapability(value = Capability.FISCAL_TERMINAL_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Suspend a fiscal terminal",
            description = "Settings 10.7 Tab 2's «Отключить». A suspended terminal leaves the "
                    + "channel matrix immediately and no longer counts toward the activation "
                    + "precondition for cash at its location.")
    FiscalTerminalView suspend(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID terminalId,
            @RequestParam int expectedVersion) {
        return FiscalTerminalView.of(terminals.suspend(tenantId, brandId, terminalId, expectedVersion));
    }

    @PostMapping("/{terminalId}/reactivate")
    @RequiresCapability(value = Capability.FISCAL_TERMINAL_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Reactivate a suspended fiscal terminal")
    FiscalTerminalView reactivate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID terminalId,
            @RequestParam int expectedVersion) {
        return FiscalTerminalView.of(terminals.reactivate(tenantId, brandId, terminalId, expectedVersion));
    }

    @PostMapping("/{terminalId}/retire")
    @RequiresCapability(value = Capability.FISCAL_TERMINAL_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Retire a fiscal terminal permanently",
            description = "Never deleted: a fiscal document already issued through this terminal "
                    + "must still resolve which box issued it years later.")
    FiscalTerminalView retire(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID terminalId,
            @RequestParam int expectedVersion) {
        return FiscalTerminalView.of(terminals.retire(tenantId, brandId, terminalId, expectedVersion));
    }

    record RegisterTerminalRequest(
            @NotNull UUID locationId,
            @NotNull UUID legalEntityId,
            @NotNull FiscalTerminalKind kind,
            @Nullable UUID providerBindingId,
            @NotBlank @Size(max = 128) String terminalReference,
            @Nullable Map<String, Boolean> capabilitySnapshot) {}

    record HealthCheckRequest(@NotNull FiscalTerminalHealth outcome) {}

    record FiscalTerminalView(
            UUID id,
            UUID brandId,
            UUID locationId,
            UUID legalEntityId,
            FiscalTerminalKind kind,
            @Nullable UUID providerBindingId,
            String terminalReference,
            Map<String, Boolean> capabilitySnapshot,
            boolean capable,
            FiscalTerminalStatus status,
            @Nullable Instant lastHealthCheckAt,
            @Nullable FiscalTerminalHealth lastHealthStatus,
            int version) {

        static FiscalTerminalView of(FiscalTerminal terminal) {
            return new FiscalTerminalView(
                    terminal.id(),
                    terminal.brandId(),
                    terminal.locationId(),
                    terminal.legalEntityId(),
                    terminal.kind(),
                    terminal.providerBindingId(),
                    terminal.terminalReference(),
                    terminal.capabilitySnapshot(),
                    terminal.capable(),
                    terminal.status(),
                    terminal.lastHealthCheckAt(),
                    terminal.lastHealthStatus(),
                    terminal.version());
        }
    }
}
