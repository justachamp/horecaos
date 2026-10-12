package uz.horecaos.platform.kitchen.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import uz.horecaos.platform.kitchen.application.KitchenPaidHoldPolicies;
import uz.horecaos.platform.kitchen.application.PolicyEditorView;
import uz.horecaos.platform.kitchen.domain.KitchenPaidHoldPolicy;
import uz.horecaos.platform.kitchen.domain.KitchenPaidHoldPolicy.Mode;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The paid-only hold's policy, at a tenant, a brand or a location (ADR 0168 Decision 8, ADR 0030).
 *
 * <p>The scope-resolved shape of {@code GET}/{@code PUT .../payment-window} and {@code .../courier-policy}:
 * {@code brandId}/{@code locationId} optional, explicit per-scope authorization, {@code If-Match} carrying
 * the version the {@code GET} returned, {@code Idempotency-Key} on the write. Reading needs {@code
 * kitchen.ticket.read}; writing needs its own capability, {@code kitchen.paid-hold.manage}. The platform-wide
 * document is not written here: the owner publishes it through {@code PolicyAuthor} at platform scope, and no
 * platform-scoped endpoint exists.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/kitchen-paid-hold")
@Tag(name = "Kitchen paid hold", description = "Whether a buffered ticket waits for its order's payment (ADR 0168)")
public class KitchenPaidHoldController {

    private final KitchenPaidHoldPolicies policies;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;

    public KitchenPaidHoldController(
            KitchenPaidHoldPolicies policies, AuthorizationService authorization, CurrentActor currentActor) {
        this.policies = policies;
        this.authorization = authorization;
        this.currentActor = currentActor;
    }

    @GetMapping
    @Operation(
            summary = "The paid-hold policy in force at a scope",
            description = "Resolves kitchen.paid_hold through the ADR 0030 chain (location, brand, tenant, "
                    + "platform). isDefault is true when nothing was published and the built-in SHADOW applies: it "
                    + "counts what it would hold and holds nothing. The ETag, and versionAtScope, are the version "
                    + "authored at exactly this scope (0 when it inherits): send it back as If-Match.")
    public ResponseEntity<PaidHoldResponse> read(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ResourceScope scope = KitchenPolicyScopes.of(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.KITCHEN_TICKET_READ, scope);
        PolicyEditorView<KitchenPaidHoldPolicy> editor = policies.view(scope);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(editor.versionAtScope()))
                .body(PaidHoldResponse.of(editor));
    }

    @PutMapping
    @Idempotent
    @Operation(
            summary = "Publish the next version of the paid-hold policy at exactly one scope",
            description = "Whole-document replace. mode is SHADOW, HOLD_UNTIL_PAID or OFF; overdueAfterMinutes is "
                    + "one minute to a day and defaults to ten. Requires If-Match carrying the version the GET at "
                    + "this same scope returned, or the write is refused with STALE_VERSION.")
    public ResponseEntity<PaidHoldResponse> write(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @Valid @RequestBody PaidHoldWriteRequest body,
            HttpServletRequest request) {

        ResourceScope scope = KitchenPolicyScopes.of(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.KITCHEN_PAID_HOLD_MANAGE, scope);
        long expected = AggregateVersion.requireIfMatch(request);
        if (expected < 0 || expected > Integer.MAX_VALUE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "If-Match must carry a document version");
        }
        PolicyEditorView<KitchenPaidHoldPolicy> published = policies.author(
                scope,
                new KitchenPaidHoldPolicy(
                        KitchenPaidHoldPolicy.SCHEMA,
                        body.mode(),
                        body.overdueAfterMinutes() == null
                                ? KitchenPaidHoldPolicy.DEFAULT_OVERDUE_AFTER_MINUTES
                                : body.overdueAfterMinutes()),
                (int) expected,
                ActorRef.user(currentActor.get().subject(), null),
                body.reason());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(published.versionAtScope()))
                .body(PaidHoldResponse.of(published));
    }

    /**
     * @param isDefault      nothing was published anywhere in the chain; the built-in SHADOW applies
     * @param versionAtScope the latest version authored at exactly the requested scope (0 when it inherits)
     */
    public record PaidHoldResponse(
            int schema,
            Mode mode,
            int overdueAfterMinutes,
            boolean isDefault,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<PolicyEditorView.Level> levels) {

        static PaidHoldResponse of(PolicyEditorView<KitchenPaidHoldPolicy> editor) {
            return new PaidHoldResponse(
                    editor.document().schema(),
                    editor.document().mode(),
                    editor.document().overdueAfterMinutes(),
                    editor.isDefault(),
                    editor.winningScope(),
                    editor.policyId(),
                    editor.policyVersion(),
                    editor.versionAtScope(),
                    editor.levels());
        }
    }

    public record PaidHoldWriteRequest(
            @NotNull Mode mode,
            @Nullable Integer overdueAfterMinutes,
            @NotBlank @Size(max = 1000) String reason) {}
}
