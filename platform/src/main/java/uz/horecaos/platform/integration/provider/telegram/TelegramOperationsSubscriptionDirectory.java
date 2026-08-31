package uz.horecaos.platform.integration.provider.telegram;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;

/** {@link OperationsSubscriptionDirectory} over Telegram bindings (ADR 0058), the only channel this slice has. */
@Component
public class TelegramOperationsSubscriptionDirectory implements OperationsSubscriptionDirectory {

    private final TelegramBindingStore bindings;

    public TelegramOperationsSubscriptionDirectory(TelegramBindingStore bindings) {
        this.bindings = bindings;
    }

    @Override
    public List<UUID> subscribedBindings(UUID tenantId, UUID brandId, UUID locationId, String eventClass) {
        return bindings.subscribedBindings(tenantId, brandId, locationId, eventClass);
    }
}
