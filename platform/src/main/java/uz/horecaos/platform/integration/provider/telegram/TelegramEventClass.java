package uz.horecaos.platform.integration.provider.telegram;

/**
 * Every event class a bound Telegram chat may subscribe to today, restated
 * from {@code integration.telegram_binding_events}'s own {@code
 * ck_telegram_binding_event_class} check (currently last widened by
 * {@code V0114}) — kept beside the schema constraint deliberately rather than
 * generated from it, so the admin routing screen (gap map row {@code 10.9b},
 * wave P36) can offer the set without a schema round trip, and a value here
 * that the check does not also admit fails a test rather than a real insert.
 */
public enum TelegramEventClass {
    ORDER_CONFIRMED("Order confirmed"),
    ORDER_REJECTED("Order rejected"),
    ORDER_AWAITING_APPROVAL("Order awaiting approval"),
    ORDER_APPROVAL_DEADLINE_WARNING("Approval deadline warning"),
    DIGEST_15M("15-minute operations digest"),
    DIGEST_HALF_DAY("Half-day operations digest"),
    DIGEST_DAY_CLOSE("Day-close operations digest"),
    PLATFORM_DIGEST_HALF_DAY("Half-day platform digest"),
    PLATFORM_DIGEST_DAY_CLOSE("Day-close platform digest"),
    PAYMENT_ATTEMPT_FAILED("Payment attempt failed"),
    PAYMENT_ATTEMPT_NEEDS_OPERATOR("Payment attempt needs an operator"),
    FISCAL_DOCUMENT_BLOCKED("Fiscal document blocked"),
    ITEM_86D("Item 86'd"),
    DEAD_LETTER_RECORDED("Integration dead letter recorded"),
    POS_EXPORT_AWAITING_OPERATOR("POS export awaiting operator"),
    CAMPAIGN_BLOCK_RATE_PAUSED("Campaign paused by the block-rate guard");

    private final String description;

    TelegramEventClass(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
