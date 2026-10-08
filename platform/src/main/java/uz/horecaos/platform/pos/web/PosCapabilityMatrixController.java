package uz.horecaos.platform.pos.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
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
 *
 * <p>ADR 0171 adds one column to it, {@code loyaltyBalanceSync}: the vendor ceiling for keeping a till's bonus
 * balance a mirror of the loyalty ledger, in four values. {@code UNASSESSED} is what a vendor nobody has read is
 * (Clopos today), and it is a different statement from {@code UNSUPPORTED} on purpose: one says the vendor was read and
 * cannot, the other says nobody has looked, and the database refuses to enable the capability on either.
 */
@RestController
@RequestMapping("/api/v1/control-plane/pos-capability-matrix")
@Tag(name = "POS capability matrix", description = "Which capabilities each wired POS adapter declares")
public class PosCapabilityMatrixController {

    /** The four-valued state a vendor nobody has assessed has. */
    static final String UNASSESSED = "UNASSESSED";

    private final List<PosAdapter> adapters;
    private final Function<String, Map<PosCapability, CapabilitySupport>> ceilings;

    @Autowired
    public PosCapabilityMatrixController(List<PosAdapter> adapters, JdbcPosCapabilityStore capabilities) {
        this(adapters, capabilities::ceiling);
    }

    /** Without a ceiling store: every vendor is unassessed. */
    PosCapabilityMatrixController(List<PosAdapter> adapters) {
        this(adapters, providerType -> Map.of());
    }

    /** A test's way to give each vendor a ceiling without a database. */
    PosCapabilityMatrixController(
            List<PosAdapter> adapters, Function<String, Map<PosCapability, CapabilitySupport>> ceilings) {
        this.adapters = List.copyOf(adapters);
        this.ceilings = ceilings;
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
                .map(adapter -> new AdapterCapabilities(
                        adapter.providerType(),
                        List.copyOf(adapter.declaredCapabilities()),
                        loyaltyBalanceSync(adapter.providerType())))
                .toList();
    }

    /** SUPPORTED, PARTIAL, UNSUPPORTED from the assessed ceiling row, or UNASSESSED when the vendor has none (ADR 0171 Decision 8). */
    private String loyaltyBalanceSync(String providerType) {
        CapabilitySupport support = ceilings.apply(providerType).get(PosCapability.LOYALTY_BALANCE_SYNC);
        return support == null ? UNASSESSED : support.name();
    }

    /**
     * @param loyaltyBalanceSync {@code SUPPORTED}, {@code PARTIAL}, {@code UNSUPPORTED} or {@code UNASSESSED}: can this
     *                           vendor keep a till's bonus balance a mirror of the loyalty ledger (ADR 0171)
     */
    public record AdapterCapabilities(
            String providerType, List<PosCapability> declaredCapabilities, String loyaltyBalanceSync) {}
}
