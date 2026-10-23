package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OperatorOrderingService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What this location lets an operator do while keying an order in (settings.md 10.3 card 5, ADR 0177).
 *
 * <p>The New order screen reads it once so it can hide a control the server would refuse -- the promo
 * field, the call-back flag, the cash-tendered field -- instead of letting the operator fill the form and
 * learn at «Создать». It is a presentation of the same three settings {@code OperatorOrderingService}
 * enforces on every place and every quote; hiding a control grants nothing and un-hiding one cannot get a
 * refused request past the server.
 *
 * <p>A read of a setting, so it needs only the capability the screen already holds ({@code ORDER_PLACE} at
 * the branch) and reveals no customer data.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders")
@Tag(name = "Operations orders", description = "The branch's order queue, approvals, and timeline")
public class OperatorOrderEntryPolicyController {

    private final OperatorOrderingService operatorOrdering;

    public OperatorOrderEntryPolicyController(OperatorOrderingService operatorOrdering) {
        this.operatorOrdering = operatorOrdering;
    }

    @GetMapping("/entry-policy")
    @RequiresCapability(value = Capability.ORDER_PLACE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "What an operator may do while keying an order in at this branch",
            description = "ADR 0177: whether the operator may apply a promo code, flag the order for a call "
                    + "back and record the cash the customer will hand over, as this branch resolves "
                    + "ordering.operator_promo_code_allowed, ordering.operator_callback_flag_allowed and "
                    + "ordering.operator_change_due_allowed. Each defaults to true. The server refuses what "
                    + "this says is off, on place and on quote, whatever the screen shows.")
    public ResponseEntity<EntryPolicyResponse> entryPolicy(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        var policy = operatorOrdering.entryPolicy(tenantId, brandId, locationId);
        return ResponseEntity.ok(new EntryPolicyResponse(
                policy.promoCodeAllowed(), policy.callbackFlagAllowed(), policy.changeDueAllowed()));
    }

    /** The three Card 5 toggles as they resolve at this branch. */
    public record EntryPolicyResponse(
            boolean promoCodeAllowed, boolean callbackFlagAllowed, boolean changeDueAllowed) {}
}
