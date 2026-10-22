package uz.horecaos.platform.observability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.configuration.ProvisionalRetentionGuard;
import uz.horecaos.platform.configuration.RetentionWindow;
import uz.horecaos.platform.configuration.RetentionWindowProvider;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What each retention setting does for this tenant today (Settings 10.11, ADR 0109).
 *
 * <p>The card the tenant edits its retention on used to show the number it had stored and nothing about what the platform did
 * with it. This answers per setting: the tenant's own value, the window the job acts on (the same code that does the
 * deleting, asked), the floor below which the platform will not go, whether the window is this tenant's alone or shared with
 * every tenant, and whether the number is still provisional pending legal (ADR 0029). Read only; changing a value is the
 * generic tenant configuration write.
 */
@RestController
@Tag(name = "Retention", description = "What each retention setting does for a tenant")
public class RetentionWindowController {

    private final List<RetentionWindowProvider> providers;
    private final ProvisionalRetentionGuard provisional;

    public RetentionWindowController(List<RetentionWindowProvider> providers, ProvisionalRetentionGuard provisional) {
        this.providers = providers;
        this.provisional = provisional;
    }

    @GetMapping("/api/v1/operations/tenants/{tenantId}/retention-windows")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "The effective retention window and the platform floor behind each retention setting",
            description = "One entry per tenant-visible retention key: the value this tenant stored, the window the "
                    + "job acts on today, the floor a lower value is raised to, whether the window is the tenant's "
                    + "own or shared with every tenant (courier tracks live in shared daily partitions), and "
                    + "whether the number is still provisional pending legal's confirmation.")
    public List<RetentionWindowResponse> windows(@PathVariable UUID tenantId) {
        return providers.stream()
                .map(provider -> provider.windowFor(tenantId))
                .map(window -> RetentionWindowResponse.of(window, provisional.isProvisional(window.code())))
                .sorted(java.util.Comparator.comparing(RetentionWindowResponse::code))
                .toList();
    }

    /** One setting's window, as the card reads it. */
    public record RetentionWindowResponse(
            String code,
            String unit,
            int platformDefault,
            int floor,
            @Nullable Integer configured,
            int effective,
            String appliesTo,
            String enforcedBy,
            boolean provisional) {

        static RetentionWindowResponse of(RetentionWindow window, boolean provisional) {
            return new RetentionWindowResponse(
                    window.code(),
                    window.unit().name(),
                    window.platformDefault(),
                    window.floor(),
                    window.configured(),
                    window.effective(),
                    window.scope().name(),
                    window.enforcedBy(),
                    provisional);
        }
    }
}
