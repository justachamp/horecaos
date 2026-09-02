package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The canonical capability vocabulary a tenant role is assembled from
 * (control-plane IA 7.2, ADR 0025).
 *
 * <p>{@link Capability} is code-owned on purpose — "a capability is a verb on
 * a resource type... adding one is a release," per that enum's own javadoc —
 * and until now nothing served the whole list over HTTP: {@code GET
 * /api/v1/session/context} answers only which of them one signed-in principal
 * holds. A console that lets platform staff read the registry itself (to
 * write an ADR 0027 approval policy's {@code requiredApproverCapability}, or
 * simply to know what exists before granting a role) needs the full list, not
 * one principal's slice of it.
 *
 * <p>Read-only and static within a build: this reflects over {@link
 * Capability#values()}, which cannot change without a release, so there is
 * nothing here to cache invalidate or paginate.
 */
@RestController
@RequestMapping("/api/v1/control-plane/capabilities")
@Tag(name = "Capability registry", description = "The full ADR 0025 capability vocabulary")
public class CapabilityRegistryController {

    @GetMapping
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "List every capability this build declares",
            description = "The code-owned registry, not one session's slice of it. Platform-admin "
                    + "only: the vocabulary itself is what an approval policy's "
                    + "requiredApproverCapability and a grant's roleCode are built from.")
    List<CapabilityDescriptor> list() {
        return java.util.Arrays.stream(Capability.values())
                .map(CapabilityDescriptor::of)
                .toList();
    }

    /** One capability, as the registry screen renders it. */
    public record CapabilityDescriptor(String code, String resourceType, String action) {

        static CapabilityDescriptor of(Capability capability) {
            return new CapabilityDescriptor(capability.code(), capability.resourceType(), capability.action());
        }
    }
}
