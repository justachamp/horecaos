package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore.AdminBindingRow;
import uz.horecaos.platform.integration.provider.telegram.TelegramEventClass;
import uz.horecaos.platform.integration.provider.telegram.TelegramRoutingAdminService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Tab 2 (Маршрутизация): which event classes a bound Telegram chat receives,
 * its topic, and unbinding it (ADR 0058, gap map row {@code 10.9b}).
 *
 * <p>Binding a new chat stays the {@code /link <code>} handshake's own job
 * ({@link TelegramLinkCodeController}) — a chat has to prove the bot's rights
 * in it before anything here can point at it. This is management of a
 * binding that already exists: before this wave, the only place a manager
 * could see or change one was a raw database row.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/notification-routing")
@Tag(name = "Notification routing", description = "ADR 0058: Telegram chat routing admin (gap map 10.9b)")
public class TelegramRoutingController {

    private final TelegramRoutingAdminService routing;
    private final CurrentActor currentActor;

    public TelegramRoutingController(TelegramRoutingAdminService routing, CurrentActor currentActor) {
        this.routing = routing;
        this.currentActor = currentActor;
    }

    @GetMapping("/event-classes")
    @RequiresCapability(value = Capability.NOTIFICATION_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every event class a chat may be subscribed to")
    public ResponseEntity<List<EventClassResponse>> eventClasses() {
        return ResponseEntity.ok(Arrays.stream(TelegramEventClass.values())
                .map(value -> new EventClassResponse(value.name(), value.description()))
                .toList());
    }

    @GetMapping("/bindings")
    @RequiresCapability(value = Capability.NOTIFICATION_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every Telegram chat bound to this brand, and what it hears",
            description = "Active and retired bindings both list, so an operator can see why a chat "
                    + "stopped receiving rather than finding it simply gone.")
    public ResponseEntity<List<BindingResponse>> bindings(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(
                routing.list(tenantId, brandId).stream().map(this::toResponse).toList());
    }

    @PostMapping("/bindings/{bindingId}/subscriptions")
    @RequiresCapability(value = Capability.NOTIFICATION_ROUTING_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Turn one event class on or off for a chat",
            description = "The handshake seeds a default set on link; this is the only place that "
                    + "set can be widened or narrowed afterward.")
    public ResponseEntity<Void> setSubscription(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID bindingId,
            @Valid @RequestBody RoutingSubscriptionRequest request) {
        routing.setSubscription(tenantId, brandId, bindingId, request.eventClass(), request.enabled());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bindings/{bindingId}/topic")
    @RequiresCapability(value = Capability.NOTIFICATION_ROUTING_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Move a binding to a different forum topic, or to the flat chat",
            description = "A null topicId moves the binding back to the chat's own flat, non-topic stream.")
    public ResponseEntity<Void> changeTopic(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID bindingId,
            @Valid @RequestBody TopicRequest request) {
        routing.changeTopic(tenantId, brandId, bindingId, request.topicId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bindings/{bindingId}/unbind")
    @RequiresCapability(value = Capability.NOTIFICATION_ROUTING_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Stop a chat from receiving anything",
            description = "The same retirement taxonomy Telegram itself drives on a 403 or a deleted "
                    + "topic (ADR 0058), attributed here to an operator's own choice (MANUAL) instead.")
    public ResponseEntity<Void> unbind(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID bindingId) {
        routing.unbind(tenantId, brandId, bindingId, currentActor.get().subject());
        return ResponseEntity.noContent().build();
    }

    private BindingResponse toResponse(TelegramRoutingAdminService.BindingWithSubscriptions row) {
        AdminBindingRow binding = row.binding();
        return new BindingResponse(
                binding.bindingId(),
                binding.brandId(),
                binding.locationId(),
                binding.status(),
                binding.chatId(),
                binding.topicId(),
                binding.retiredAt() == null ? null : binding.retiredAt().toString(),
                binding.retiredReason(),
                binding.createdAt().toString(),
                row.subscriptions().stream()
                        .map(sub -> new RoutingSubscriptionResponse(sub.eventClass(), sub.enabled()))
                        .toList());
    }

    public record EventClassResponse(String eventClass, String description) {}

    public record RoutingSubscriptionResponse(String eventClass, boolean enabled) {}

    /**
     * @param locationId null for a binding scoped to the whole brand rather
     *                   than one branch
     * @param retiredAt null while the binding is active
     */
    public record BindingResponse(
            UUID bindingId,
            UUID brandId,
            @Nullable UUID locationId,
            String status,
            long chatId,
            @Nullable Integer topicId,
            @Nullable String retiredAt,
            @Nullable String retiredReason,
            String createdAt,
            List<RoutingSubscriptionResponse> subscriptions) {}

    public record RoutingSubscriptionRequest(@NotBlank String eventClass, boolean enabled) {}

    /** {@code topicId} null moves the binding to the chat's own flat stream. */
    public record TopicRequest(@Nullable Integer topicId) {}
}
