package uz.horecaos.platform.kitchen.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.kitchen.application.PolicyEditorView;
import uz.horecaos.platform.kitchen.application.StationProductivityPolicies;
import uz.horecaos.platform.kitchen.domain.StationProductivityPolicy;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The tenant's own statement of how many portions one cook produces in an hour (ADR 0163 Decision 4).
 *
 * <p>The platform ships no number: the built-in document is the empty one, and a station with no figure
 * answers {@code NO_POLICY} in the headcount plan. Settable at the tenant, at a brand (where a cuisine's
 * different pace is stated) and at a location (where one branch's stove is). Replace-not-merge, published
 * whole under {@code If-Match}. Reading needs {@code kitchen.headcount.read}; writing needs {@code
 * kitchen.productivity.manage}.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/kitchen-productivity")
@Tag(name = "Kitchen productivity", description = "Portions one cook produces per hour, by station (ADR 0163)")
public class KitchenProductivityController {

    private final StationProductivityPolicies policies;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;

    public KitchenProductivityController(
            StationProductivityPolicies policies, AuthorizationService authorization, CurrentActor currentActor) {
        this.policies = policies;
        this.authorization = authorization;
        this.currentActor = currentActor;
    }

    @GetMapping
    @Operation(
            summary = "The productivity figures in force at a scope",
            description = "Resolves kitchen.station_productivity through the ADR 0030 chain (location, brand, "
                    + "tenant). isDefault is true when nothing was published: the empty document, and no cook "
                    + "count for any station. The ETag, and versionAtScope, are the version authored at exactly "
                    + "this scope (0 when it inherits): send it back as If-Match.")
    public ResponseEntity<ProductivityResponse> read(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ResourceScope scope = KitchenPolicyScopes.of(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.KITCHEN_HEADCOUNT_READ, scope);
        PolicyEditorView<StationProductivityPolicy> editor = policies.view(scope);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(editor.versionAtScope()))
                .body(ProductivityResponse.of(editor));
    }

    @PutMapping
    @Idempotent
    @Operation(
            summary = "Publish the next version of the productivity figures at exactly one scope",
            description = "Whole-document replace. roles maps a station role (HOT, COLD, GRILL, BAR, BAKERY, "
                    + "PACKING; never EXPO) and stations maps a station code to whole portions per cook-hour, one "
                    + "to a thousand. A station resolves to its code's figure, else its role's, else none. Requires "
                    + "If-Match carrying the version the GET at this same scope returned, or STALE_VERSION.")
    public ResponseEntity<ProductivityResponse> write(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @Valid @RequestBody ProductivityWriteRequest body,
            HttpServletRequest request) {

        ResourceScope scope = KitchenPolicyScopes.of(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.KITCHEN_PRODUCTIVITY_MANAGE, scope);
        long expected = AggregateVersion.requireIfMatch(request);
        if (expected < 0 || expected > Integer.MAX_VALUE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "If-Match must carry a document version");
        }
        PolicyEditorView<StationProductivityPolicy> published = policies.author(
                scope,
                new StationProductivityPolicy(
                        StationProductivityPolicy.SCHEMA,
                        body.roles() == null ? Map.of() : body.roles(),
                        body.stations() == null ? Map.of() : body.stations()),
                (int) expected,
                ActorRef.user(currentActor.get().subject(), null),
                body.reason());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(published.versionAtScope()))
                .body(ProductivityResponse.of(published));
    }

    /**
     * @param isDefault      nothing was published anywhere in the chain; no station has a figure
     * @param versionAtScope the latest version authored at exactly the requested scope (0 when it inherits)
     */
    public record ProductivityResponse(
            int schema,
            Map<String, Integer> roles,
            Map<String, Integer> stations,
            boolean isDefault,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<PolicyEditorView.Level> levels) {

        static ProductivityResponse of(PolicyEditorView<StationProductivityPolicy> editor) {
            return new ProductivityResponse(
                    editor.document().schema(),
                    editor.document().roles(),
                    editor.document().stations(),
                    editor.isDefault(),
                    editor.winningScope(),
                    editor.policyId(),
                    editor.policyVersion(),
                    editor.versionAtScope(),
                    editor.levels());
        }
    }

    public record ProductivityWriteRequest(
            @Nullable Map<String, Integer> roles,
            @Nullable Map<String, Integer> stations,
            @NotBlank @Size(max = 1000) String reason) {}
}
