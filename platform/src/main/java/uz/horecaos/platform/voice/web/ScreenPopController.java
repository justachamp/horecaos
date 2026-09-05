package uz.horecaos.platform.voice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderDirectory.RecentOrder;
import uz.horecaos.platform.voice.application.ScreenPopQueryService;
import uz.horecaos.platform.voice.application.ScreenPopQueryService.Card;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The screen-pop card (ADR 0064): "delivery to the browser uses the
 * operations app's existing polling cadence first" — this is that polling
 * endpoint, meant to be called on the same 10-second cadence every other live
 * screen in this app already uses. No push, no new gateway.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/voice/screen-pop")
@Tag(name = "Voice screen-pop", description = "The inbound-call client card (ADR 0064)")
public class ScreenPopController {

    private final ScreenPopQueryService screenPop;
    private final CurrentActor currentActor;

    public ScreenPopController(ScreenPopQueryService screenPop, CurrentActor currentActor) {
        this.screenPop = screenPop;
        this.currentActor = currentActor;
    }

    @GetMapping("/current")
    @RequiresCapability(value = Capability.VOICE_SCREEN_POP_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The branch's current ringing call, if any",
            description = "Empty when nothing is ringing. An unknown caller comes back with "
                    + "unknownCaller=true and no customer fields, so the operations app can open a "
                    + "blank card with create-customer prefilled from maskedCallerNumber's source.")
    public ResponseEntity<CardResponse> current(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return screenPop
                .current(tenantId, brandId, locationId)
                .map(card -> ResponseEntity.ok(CardResponse.of(card)))
                .orElseGet(() -> ResponseEntity.ok(CardResponse.none()));
    }

    @PostMapping("/{callEventId}/acknowledgement")
    @RequiresCapability(value = Capability.VOICE_SCREEN_POP_ACKNOWLEDGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Claim this ringing call's card",
            description = "A lightweight claim, not a hard lock: once acknowledged, other operators "
                    + "polling the same branch stop seeing this card.")
    public ResponseEntity<Void> acknowledge(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID callEventId) {
        screenPop.acknowledge(tenantId, callEventId, currentActor.get().subject());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{callEventId}/caller-number")
    @RequiresCapability(value = Capability.VOICE_SCREEN_POP_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The unmasked number of an unknown caller, for the create-customer prefill",
            description = "Refused for a resolved caller — that number is revealed through the "
                    + "customer record instead, under its own capability. Audited (ADR 0027) before "
                    + "decryption, every time, since this is a deliberate reveal and not part of the "
                    + "ordinary poll.")
    public ResponseEntity<CallerNumberResponse> callerNumber(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID callEventId) {
        String number = screenPop.revealUnknownCallerNumber(
                tenantId,
                brandId,
                locationId,
                callEventId,
                ActorRef.user(currentActor.get().subject(), null),
                Capability.VOICE_SCREEN_POP_READ.code());
        return ResponseEntity.ok(new CallerNumberResponse(number));
    }

    public record CallerNumberResponse(String number) {}

    public record CardResponse(
            boolean ringing,
            @Nullable UUID callEventId,
            @Nullable String lineDid,
            @Nullable String maskedCallerNumber,
            @Nullable Instant occurredAt,
            boolean unknownCaller,
            @Nullable UUID customerAccountId,
            @Nullable String customerDisplayName,
            List<RecentOrder> recentOrders,
            @Nullable String acknowledgedBy) {

        static CardResponse none() {
            return new CardResponse(false, null, null, null, null, false, null, null, List.of(), null);
        }

        static CardResponse of(Card card) {
            return new CardResponse(
                    true,
                    card.callEventId(),
                    card.lineDid(),
                    card.maskedCallerNumber(),
                    card.occurredAt(),
                    card.unknownCaller(),
                    card.customerAccountId(),
                    card.customerDisplayName(),
                    card.recentOrders(),
                    card.acknowledgedBy());
        }
    }
}
