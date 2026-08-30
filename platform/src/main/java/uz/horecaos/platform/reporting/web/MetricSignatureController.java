package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reporting.application.MetricSigningService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Finance's signature over a metric definition (ADR 0043).
 *
 * <p>Platform-scoped, and deliberately not under a tenant path. A metric
 * definition is the same on every screen in the platform, so a tenant signing its
 * own version of average check would turn the registry into a per-tenant setting
 * — which is the failure the registry exists to prevent.
 *
 * <p>This endpoint cannot change a definition. Definitions are code; a change to
 * one is a new version and a release, and the startup check refuses to run
 * against a row that was edited in place.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/reporting/metric-signatures")
@Tag(name = "Metric signatures", description = "Recording finance sign-off on a metric version")
public class MetricSignatureController {

    private final MetricSigningService signing;
    private final CurrentActor currentActor;

    public MetricSignatureController(MetricSigningService signing, CurrentActor currentActor) {
        this.signing = signing;
        this.currentActor = currentActor;
    }

    @PostMapping("/{metricCode}")
    @RequiresCapability(value = Capability.METRIC_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Record that finance has signed this metric version",
            description = "Refused if the version is already signed. A second signature over the "
                    + "same words says nothing new, and replacing the first would lose who "
                    + "actually decided; a changed definition is a new version instead.")
    public ResponseEntity<SignatureResponse> sign(
            @PathVariable String metricCode, @Valid @RequestBody SignatureRequest body) {

        var actor = ActorRef.user(currentActor.get().subject(), null);
        var definition = signing.sign(metricCode, actor, body.reason());

        return ResponseEntity.ok(new SignatureResponse(definition.id().code(), definition.digest(), actor.subject()));
    }

    /**
     * @param reason why this definition is being signed. ADR 0027 refuses a
     *               user-initiated action without one, and "finance agreed" with
     *               no meeting or document named is not an answer anyone can use
     *               a year later
     */
    public record SignatureRequest(
            @NotBlank @Size(max = 512) String reason) {}

    /**
     * @param definitionDigest the exact wording that was signed, so the signature
     *                         can be proved to cover the text in that release
     */
    public record SignatureResponse(String metricCode, String definitionDigest, String signedBy) {}
}
