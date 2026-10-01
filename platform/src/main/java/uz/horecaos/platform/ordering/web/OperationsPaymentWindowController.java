package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
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
import uz.horecaos.platform.ordering.application.PaymentWindowAuthoringService;
import uz.horecaos.platform.ordering.application.PaymentWindowAuthoringService.Editor;
import uz.horecaos.platform.ordering.application.PaymentWindowAuthoringService.Level;
import uz.horecaos.platform.ordering.domain.PaymentWindowPolicy;
import uz.horecaos.platform.ordering.domain.PaymentWindowPolicy.Action;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The unpaid-order window, shown beside the dispatch rules and owned by {@code ordering} (ADR 0142
 * Decision 7, ADR 0019, ADR 0030).
 *
 * <p>The same scope-resolved shape as {@code GET}/{@code PUT .../courier-policy} and the dispatch
 * rules: {@code brandId}/{@code locationId} optional, explicit per-scope authorization, {@code
 * If-Match} carrying the version the {@code GET} returned, {@code Idempotency-Key} on the write.
 * Reading needs the configuration-read grant the order-policy card already uses; writing needs its own,
 * {@link Capability#ORDER_PAYMENT_WINDOW_MANAGE}.
 *
 * <p>{@code CANCEL} is a named action and is refused at publish with {@code VALIDATION_FAILED}: whether an
 * unpaid order should be cancelled at all is ADR 0019's open product input, and until it is answered the
 * window only controls when an order reaches the stuck list.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/payment-window")
@Tag(
        name = "Payment window",
        description = "How long an unpaid order is waited for before it reaches the stuck list (ADR 0142)")
public class OperationsPaymentWindowController {

    private final PaymentWindowAuthoringService authoring;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;
    private final Duration deployFallback;

    public OperationsPaymentWindowController(
            PaymentWindowAuthoringService authoring,
            AuthorizationService authorization,
            CurrentActor currentActor,
            // The deploy property the sweep used before this document existed; the answer when no
            // document is published anywhere in the chain.
            @Value("${horecaos.ordering.workers.payment.stale-after:PT30M}") Duration deployFallback) {
        this.authoring = authoring;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.deployFallback = deployFallback;
    }

    @GetMapping
    @Operation(
            summary = "The unpaid-order window in force at a scope",
            description = "Resolves ordering.payment_window through the ADR 0030 chain (location, brand, tenant). "
                    + "isDefault is true when nothing was published and the deployment's own threshold applies. "
                    + "The ETag, and versionAtScope, are the version authored at exactly this scope (0 when it "
                    + "inherits): send it back as If-Match.")
    public ResponseEntity<WindowResponse> read(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ResourceScope scope = scopeOf(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.TENANT_CONFIGURATION_READ, scope);
        Editor editor = authoring.view(scope, deployFallback);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(editor.versionAtScope()))
                .body(WindowResponse.of(editor));
    }

    @PutMapping
    @Idempotent
    @Operation(
            summary = "Publish the next version of the unpaid-order window at exactly one scope",
            description = "Whole-document replace. windowMinutes is one minute to a day. action FLAG_ONLY is the "
                    + "only one available: CANCEL is refused with VALIDATION_FAILED until product answers ADR "
                    + "0019's open question on checkout payment timing and cancellation. Requires If-Match "
                    + "carrying the version the GET at this same scope returned, or the write is refused with "
                    + "STALE_VERSION naming both versions.")
    public ResponseEntity<WindowResponse> write(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @Valid @RequestBody WindowWriteRequest body,
            HttpServletRequest request) {

        ResourceScope scope = scopeOf(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.ORDER_PAYMENT_WINDOW_MANAGE, scope);
        long expected = AggregateVersion.requireIfMatch(request);
        if (expected < 0 || expected > Integer.MAX_VALUE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "If-Match must carry a document version");
        }
        Editor published = authoring.author(
                scope,
                new PaymentWindowPolicy(body.windowMinutes(), body.action()),
                (int) expected,
                ActorRef.user(currentActor.get().subject(), null),
                body.reason(),
                deployFallback);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(published.versionAtScope()))
                .body(WindowResponse.of(published));
    }

    private static ResourceScope scopeOf(UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        if (locationId != null) {
            if (brandId == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "locationId needs the brandId it belongs to");
            }
            return ResourceScope.location(tenantId, brandId, locationId);
        }
        return brandId != null ? ResourceScope.brand(tenantId, brandId) : ResourceScope.tenant(tenantId);
    }

    /**
     * @param isDefault      nothing was published anywhere in the chain; the deployment's own threshold applies
     * @param versionAtScope the latest version authored at exactly the requested scope (0 when it inherits)
     */
    public record WindowResponse(
            int windowMinutes,
            Action action,
            boolean isDefault,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<Level> levels) {

        static WindowResponse of(Editor editor) {
            return new WindowResponse(
                    editor.document().windowMinutes(),
                    editor.document().action(),
                    editor.isDefault(),
                    editor.winningScope(),
                    editor.policyId(),
                    editor.policyVersion(),
                    editor.versionAtScope(),
                    editor.levels());
        }
    }

    public record WindowWriteRequest(
            @NotNull Integer windowMinutes,
            @NotNull Action action,
            @NotBlank @Size(max = 1000) String reason) {}
}
