package uz.horecaos.platform.pos.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Which {@link PosCapability} each wired POS adapter declares (control-plane
 * IA 3.2, ADR 0011) — the vendor ceiling {@link
 * uz.horecaos.platform.pos.application.PosAdapterRegistry#declares} checks
 * against, read directly rather than one provider type at a time.
 *
 * <p>Honestly thin, the same way {@code PlatformIntegrationAdminController
 * .providers()} is: exactly one {@link PosAdapter} bean is wired in this build
 * (Clopos), against the twelve POS systems the wider parity inventory names.
 * This reflects the real Spring context — whatever {@link PosAdapter} beans
 * exist — rather than a hand-maintained list that could drift from it.
 */
@RestController
@RequestMapping("/api/v1/control-plane/pos-capability-matrix")
@Tag(name = "POS capability matrix", description = "Which capabilities each wired POS adapter declares")
public class PosCapabilityMatrixController {

    private final List<PosAdapter> adapters;

    public PosCapabilityMatrixController(List<PosAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    @GetMapping
    @RequiresCapability(value = Capability.POS_SYNC_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Declared capabilities per wired POS adapter",
            description = "The vendor ceiling from code, not one installation's discovered subset. "
                    + "Compare against ADR 0012's capability-reconciliation, which narrows this per "
                    + "credential.")
    List<AdapterCapabilities> matrix() {
        return adapters.stream()
                .map(adapter ->
                        new AdapterCapabilities(adapter.providerType(), List.copyOf(adapter.declaredCapabilities())))
                .toList();
    }

    public record AdapterCapabilities(String providerType, List<PosCapability> declaredCapabilities) {}
}
