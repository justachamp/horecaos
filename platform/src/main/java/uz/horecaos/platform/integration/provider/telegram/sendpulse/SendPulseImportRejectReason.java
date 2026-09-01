package uz.horecaos.platform.integration.provider.telegram.sendpulse;

/**
 * Why one row of a SendPulse contact export was not imported (ADR 0059 stage
 * 3). A fixed, short vocabulary rather than a free-text message: the report
 * is read by an operator deciding whether to fix the export and re-run, and a
 * code they can search for is worth more than a sentence that differs on
 * every row.
 *
 * <p>Never customer data. Every value here describes what the row's shape or
 * the platform's own state prevented, never what the row said about a person
 * — the reject reason itself must be as safe to log as any other outcome
 * code (ADR 0029).
 */
public enum SendPulseImportRejectReason {

    /** The row carried no chat id under any recognised column name. */
    MISSING_CHAT_ID,

    /** The chat id column existed but did not parse as an integer. */
    MALFORMED_CHAT_ID,

    /**
     * No recognised subscription-status column had a recognised value.
     * "Never a silent default" (ADR 0059's own words) — a row this import
     * cannot honestly classify as subscribed or not is rejected rather than
     * guessed.
     */
    UNRECOGNIZED_SUBSCRIPTION_STATUS,

    /**
     * A phone column was present but did not parse as a phone number.
     * Produced during resolution ({@code SendPulseContactImportRowService}),
     * not parsing — {@code PhoneNumber} normalization lives in the
     * {@code customers} module, which {@code integration} may only reach
     * through its {@code api} package, and phone validation happens exactly
     * there rather than in {@code SendPulseContactFileParser}.
     */
    MALFORMED_PHONE,

    /**
     * The row's phone number is already held by more than one customer
     * account. ADR 0015 never auto-merges on a shared contact, and an import
     * is not the place to start.
     */
    AMBIGUOUS_PHONE_MATCH,

    /**
     * The row's phone matched exactly one customer account, but that account
     * already holds an active Telegram link to a <em>different</em> chat.
     * {@code TelegramCustomerLinkService#link}'s own model admits at most one
     * active binding per customer account (the same rule the wave-7
     * handshake enforces), so this row's chat cannot become a second one —
     * two people who share a household phone but message the bot from their
     * own separate Telegram accounts are the case this guards, and reporting
     * it as a silent {@code MATCHED_CUSTOMER} would leave the second chat
     * never actually bound while claiming success.
     */
    ACCOUNT_ALREADY_LINKED_TO_ANOTHER_CHAT
}
