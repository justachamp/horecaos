package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The write-only secret door, republished on the surface its one caller lives on.
 *
 * <p>Same reasoning as {@link OperationsProviderInstallationController}: the operations app's
 * Integrations screen (ADR 0065) is the door's only real caller, and it should not have to
 * reach a path named for the platform's admin console to use it. This class declares no logic
 * of its own — {@link SecretIngressController} is still the one and only place a secret value
 * is accepted, written through {@code SecretIngressGateway#write}, and kept out of every log
 * line, audit fact, and response body. Duplicating that class instead of forwarding to it is
 * exactly the mistake this move must not make: two independent implementations of "never let
 * the value escape" is two chances for one of them to drift.
 *
 * <p>The original {@code /api/v1/control-plane/tenants/{tenantId}/integrations/secrets} path
 * is kept exactly as it was — {@code OpenApiContractTests} forbids dropping a published path,
 * and nothing in the control-plane app ever called it, so there is nothing to migrate there,
 * only a path to leave alone.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/integrations/secrets")
@Tag(name = "Secret ingress", description = "The ADR 0065 write-only door: values enter, only references leave")
public class OperationsSecretIngressController {

    private final SecretIngressController delegate;

    public OperationsSecretIngressController(SecretIngressController delegate) {
        this.delegate = delegate;
    }

    @PostMapping
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Write a provider credential",
            description = "Writes the value directly into the ADR 0028 secrets manager under a "
                    + "platform-generated reference and returns only that reference. There is no "
                    + "corresponding read endpoint anywhere in the API: pass the returned reference to "
                    + "the installation or merchant-binding call that follows.")
    public ResponseEntity<SecretIngressController.SecretIngressResponse> write(
            @PathVariable UUID tenantId, @Valid @RequestBody SecretIngressController.SecretIngressRequest request) {
        return delegate.write(tenantId, request);
    }
}
