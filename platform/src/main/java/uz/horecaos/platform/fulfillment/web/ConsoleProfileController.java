package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.application.ConsoleProfileService;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * {@code GET /api/v1/session/console-profile} (ADR 0164): how the operations console opens for the
 * signed-in principal's tenant.
 *
 * <p>No capability is declared, in the category of {@code GET /session/context}: a principal reading
 * its own console learns nothing a 403 would not already have told it. It needs a grant of some
 * kind in the tenant, and a principal with none gets the same {@code 404} as a tenant that does not
 * exist, so the endpoint cannot be used to ask whether a tenant has bought a module.
 *
 * <p>It is a separate read from {@code /session/context} on purpose. That response mirrors
 * {@code CapabilityView}, which the control-plane console also reads; a tenant's commercial
 * entitlement does not belong in a capability contract. And it is not read from the tenant-wide
 * entitlements endpoint, whose capability only an owner and finance hold: a location dispatcher,
 * the user a courier-service console exists for, would never see their profile.
 */
@RestController
@RequestMapping("/api/v1/session")
@Tag(name = "Console profile", description = "How the operations console opens for a tenant (ADR 0164)")
public class ConsoleProfileController {

    private final ConsoleProfileService profiles;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;

    public ConsoleProfileController(
            ConsoleProfileService profiles, AuthorizationService authorization, CurrentActor currentActor) {
        this.profiles = profiles;
        this.authorization = authorization;
        this.currentActor = currentActor;
    }

    @GetMapping("/console-profile")
    @Operation(
            summary = "How the operations console opens for this tenant",
            description = "ADR 0164. variant STANDARD or DELIVERIES, the landing route, the sections the "
                    + "profile puts away and the handovers the New order screen offers. A presentation "
                    + "of the one console: it grants nothing, and a put-away section is still open to "
                    + "anyone who holds its capability. Needs a grant of some kind in the tenant, else 404.")
    public ResponseEntity<ConsoleProfileResponse> consoleProfile(@RequestParam UUID tenantId) {
        var view = authorization.viewFor(currentActor.get().subject(), tenantId);
        if (view.scopes().isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such tenant");
        }
        ConsoleProfileService.ConsoleProfile profile = profiles.profileFor(tenantId);
        return ResponseEntity.ok()
                .eTag("\"" + profile.version() + "\"")
                .body(new ConsoleProfileResponse(
                        profile.variant().name(), profile.landing(), profile.hiddenSections(), profile.handovers()));
    }

    public record ConsoleProfileResponse(
            String variant, String landing, List<String> hiddenSections, List<String> handovers) {}
}
