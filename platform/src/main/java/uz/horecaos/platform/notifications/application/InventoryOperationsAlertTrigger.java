package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.catalog.api.ItemDisplayLookup;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;

/**
 * Inventory's operations Telegram trigger (ADR 0058): an item 86'd — its
 * availability toggled off — so the whole staff group knows the stop-list
 * changed, naming the item.
 *
 * <p>Same placement as {@link OrderNotificationTrigger} — {@code inventory}
 * does not depend on {@code notifications}, so a listener here importing
 * {@link ItemAvailabilityChanged} from {@code inventory.api} is a clean
 * one-way edge (contrast {@code payments.notifications.PaymentOperationsAlertTrigger},
 * which a cycle forced out of this package). Depends on {@link
 * OperationsAlertPort} rather than {@link OperationsAlertFanoutService}
 * directly so a unit test can fake the fan-out call.
 * Narrowed to the off-transition alone: {@link ItemAvailabilityChanged}
 * fires for both directions of the toggle (the fact "availability changed"
 * is symmetric, per that record's own Javadoc), and only "went unavailable"
 * is the stop-list event ADR 0058 names — a dish coming back on the menu is
 * not an incident a staff group needs pinged about.
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}: the toggle and the alert commit
 * together, matching {@code InventoryService.setAvailability}'s own single
 * write.
 */
@Component
public class InventoryOperationsAlertTrigger {

    /** The semantic template key a tenant authors this alert's wording against. */
    public static final String ITEM_86D = "ITEM_86D";

    static final String SUBJECT_TYPE = "Variant";

    private static final Logger log = LoggerFactory.getLogger(InventoryOperationsAlertTrigger.class);

    private final OperationsAlertPort operationsAlerts;
    private final ItemDisplayLookup itemNames;
    private final Duration expiry;

    public InventoryOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            ItemDisplayLookup itemNames,
            // A stop-list notice is stale the moment the item comes back —
            // there is no "still relevant an hour later" reading of "this
            // dish is 86'd" the way an unresolved payment failure has, so
            // the default is short.
            @Value("${horecaos.notifications.telegram.inventory-alert-expiry:PT30M}") Duration expiry) {
        this.operationsAlerts = operationsAlerts;
        this.itemNames = itemNames;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAvailabilityChanged(ItemAvailabilityChanged event) {
        if (event.available()) {
            // Back on the menu. Not this alert's concern — see this class's
            // own Javadoc.
            return;
        }

        String itemName =
                itemNames.displayName(event.tenantId(), event.variantId()).orElse(null);
        if (itemName == null) {
            log.debug(
                    "No catalog translation resolved for variant {}; its stop-list alert will name no item.",
                    event.variantId());
        }

        operationsAlerts.fanOut(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                ITEM_86D,
                ITEM_86D,
                SUBJECT_TYPE,
                event.variantId(),
                event.eventId(),
                // Keyed on the variant plus the moment it went off, not the
                // variant alone: a dish that goes off, comes back, and goes
                // off again is two genuinely separate stop-list events a
                // staff group should hear about twice, not a repeat
                // OrderNotificationTrigger's "one message ever" reasoning
                // would wrongly collapse onto one.
                "%s:%s:%s:%s".formatted(ITEM_86D, SUBJECT_TYPE, event.variantId(), event.occurredAt()),
                itemVariables(itemName, event.reasonCode()),
                expiry);
    }

    /**
     * The entire variable set this alert ever renders with — an item name
     * and a reason code, nothing about who marked it or which order
     * triggered it. Package-visible so {@code
     * TelegramOperationsMessageClassificationTests} asserts that directly.
     *
     * <p>{@code itemName} is a product's own proper noun, the same
     * PII-neutral category an order number already is — never a customer's
     * name or note — but is still allowlisted explicitly rather than passed
     * through unexamined, because a future caller handing this a
     * tenant-authored free-text field would be exactly the drift {@code
     * ClassificationScanner} exists to catch.
     */
    static Map<String, String> itemVariables(@Nullable String itemName, String reasonCode) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("itemName", itemName == null ? "" : itemName);
        variables.put("reasonCode", reasonCode == null ? "UNSPECIFIED" : reasonCode);
        return variables;
    }
}
