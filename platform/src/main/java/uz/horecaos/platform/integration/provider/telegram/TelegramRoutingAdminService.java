package uz.horecaos.platform.integration.provider.telegram;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore.AdminBindingRow;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore.EventSubscriptionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The admin surface over ADR 0058's Telegram bindings the console never had
 * (gap map row {@code 10.9b}, wave P36): which event classes a bound chat
 * receives, its topic, and unbinding it. Creating a binding stays the
 * {@code /link <code>} handshake's own job — this is management of a binding
 * that already exists.
 */
@Service
public class TelegramRoutingAdminService {

    private final TelegramBindingStore bindings;

    public TelegramRoutingAdminService(TelegramBindingStore bindings) {
        this.bindings = bindings;
    }

    @Transactional(readOnly = true)
    public List<BindingWithSubscriptions> list(UUID tenantId, UUID brandId) {
        return bindings.listOperationsBindings(tenantId, brandId).stream()
                .map(row -> new BindingWithSubscriptions(row, bindings.eventSubscriptionsOf(tenantId, row.bindingId())))
                .toList();
    }

    @Transactional
    public void setSubscription(UUID tenantId, UUID brandId, UUID bindingId, String eventClass, boolean enabled) {
        requireOwnedByBrand(tenantId, brandId, bindingId);
        if (Arrays.stream(TelegramEventClass.values())
                .noneMatch(known -> known.name().equals(eventClass))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown event class " + eventClass);
        }
        bindings.setSubscription(tenantId, bindingId, eventClass, enabled);
    }

    @Transactional
    public void changeTopic(UUID tenantId, UUID brandId, UUID bindingId, @Nullable Integer topicId) {
        requireOwnedByBrand(tenantId, brandId, bindingId);
        bindings.changeTopic(tenantId, bindingId, topicId);
    }

    /** {@code MANUAL} — the one retirement reason an operator, rather than Telegram, produces. */
    @Transactional
    public void unbind(UUID tenantId, UUID brandId, UUID bindingId, String actorSubject) {
        requireOwnedByBrand(tenantId, brandId, bindingId);
        bindings.retire(
                tenantId,
                bindingId,
                "MANUAL",
                ActorRef.user(actorSubject, null),
                "Unbound by an operator through the notification routing settings screen");
    }

    private void requireOwnedByBrand(UUID tenantId, UUID brandId, UUID bindingId) {
        UUID owningBrand = bindings.brandOf(tenantId, bindingId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such binding"));
        if (!owningBrand.equals(brandId)) {
            // Read as "not found" rather than "forbidden": an actor scoped to
            // this brand has no legitimate reason to learn that a binding id
            // exists under a different one.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such binding for this brand");
        }
    }

    public record BindingWithSubscriptions(AdminBindingRow binding, List<EventSubscriptionRow> subscriptions) {}
}
