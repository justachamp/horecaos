package uz.horecaos.platform.assistant.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The assistant's ADR 0030 configuration keys (ADR 0069).
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator
 * consults lives in {@code tenancy.domain.configuration}, internal to the tenancy
 * module, and this module cannot import it; a reference the other way would make
 * the two cyclic. The registry carries an identical declaration, and {@code
 * AssistantConfigurationKeysTests} fails the build if the two ever drift apart --
 * the arrangement {@code CustomerConfigurationKeys} already uses.
 */
public final class AssistantConfigurationKeys {

    public static final String ENABLED_CODE = "assistant.enabled";
    public static final String MONTHLY_SPEND_CEILING_USD_CENTS_CODE = "assistant.monthly_spend_ceiling_usd_cents";
    public static final String CONVERSATION_TURN_CAP_CODE = "assistant.conversation_turn_cap";
    public static final String PRICE_CHANNEL_CODE_CODE = "assistant.price_channel_code";
    public static final String DISCLOSURE_TEXT_EN_CODE = "assistant.disclosure_text_en";
    public static final String DISCLOSURE_TEXT_RU_CODE = "assistant.disclosure_text_ru";
    public static final String DISCLOSURE_TEXT_UZ_CODE = "assistant.disclosure_text_uz";

    /** The longest disclosure a tenant may author: one first message, not a policy document. */
    public static final int DISCLOSURE_TEXT_MAXIMUM_CHARACTERS = 500;

    /**
     * The per-tenant switch ADR 0069's rollout names ("ships behind an entitlement
     * and a per-tenant switch, defaulting off"). Rollback is this going false:
     * conversations revert to flows and the operator inbox, which never knew the
     * assistant existed.
     *
     * <p>Settable down to a brand: one tenant's brands run different bots, and a
     * pilot is one brand's bot first.
     */
    public static final ConfigurationKey<Boolean> ENABLED = ConfigurationKey.of(ENABLED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("assistant")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Whether the grounded assistant answers customers' questions in this scope's "
                    + "conversations (ADR 0069). Off until switched on; the plan entitlement "
                    + "assistant.answering.enabled must also hold.")
            .build();

    /**
     * What the platform will pay the model provider for one tenant in one calendar
     * month (UTC), in US cents. A turn that would start past it is refused and the
     * customer is handed to a person.
     *
     * <p>2 500 cents -- twenty-five dollars -- is the default: at the ledger's
     * current price book and a typical grounded turn it is several thousand
     * answers a month, far more than a pilot brand's bot receives and well under
     * what an unattended loop could cost. It is deliberately <em>not</em>
     * {@code tenantVisible}: it caps what HorecaOS pays a processor, so it is the
     * platform's to set per tenant and not the tenant's to raise.
     *
     * <p>The ceiling is checked before a call from committed spend, so concurrent
     * turns can overshoot it by at most the cost of the turns in flight; it is a
     * ceiling on a month, not a hard stop on a single request.
     */
    public static final ConfigurationKey<Long> MONTHLY_SPEND_CEILING_USD_CENTS = ConfigurationKey.of(
                    MONTHLY_SPEND_CEILING_USD_CENTS_CODE, Long.class)
            .defaultValue(2_500L)
            .ownedBy("assistant")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("The most the platform pays the assistant's model provider for this tenant in one "
                    + "calendar month (UTC), in US cents. Reaching it hands customers to a person "
                    + "(ADR 0069).")
            .build();

    /**
     * How many turns the assistant takes in one conversation in a rolling day
     * before it hands the conversation to a person ("repeated failure to help" and
     * "a conversation that never ends" are the same defect to a customer).
     */
    public static final ConfigurationKey<Integer> CONVERSATION_TURN_CAP = ConfigurationKey.of(
                    CONVERSATION_TURN_CAP_CODE, Integer.class)
            .defaultValue(20)
            .ownedBy("assistant")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Assistant turns in one conversation in a rolling 24 hours before the "
                    + "conversation is handed to a person (ADR 0069).")
            .build();

    /**
     * The sales channel whose prices and menu the assistant quotes: the one the
     * customer would order through after the chat. {@code STOREFRONT} by default
     * because ADR 0075's bot builds its carts there -- a price quoted on any other
     * channel would not be the price the customer is then charged.
     */
    public static final ConfigurationKey<String> PRICE_CHANNEL_CODE = ConfigurationKey.of(
                    PRICE_CHANNEL_CODE_CODE, String.class)
            .defaultValue("STOREFRONT")
            .ownedBy("assistant")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("The sales channel code whose menu and prices the assistant quotes. Defaults to "
                    + "STOREFRONT, the channel the Telegram bot's carts are built on (ADR 0075).")
            .build();

    /**
     * What the tenant tells its customers, in English, ahead of the assistant's first answer in
     * a conversation: that it is automated and that the question is processed by an outside
     * service (ADR 0069 -- "what a tenant must disclose to its customers" is the tenant's own
     * obligation, not the platform's to decide). Blank keeps the platform's default wording,
     * {@code CustomerWording#disclosure}, which is a default and not legal advice.
     *
     * <p>Blank is a real, resolved value, never "unset": a tenant cannot switch the disclosure
     * off by clearing it, only replace what it says. The text is the tenant's own business
     * content, rendered locally into the reply after the model returns and never sent to the
     * model; a write is refused above {@link #DISCLOSURE_TEXT_MAXIMUM_CHARACTERS} characters or
     * with a control character in it (see {@code ConfigurationValueRules}).
     */
    public static final ConfigurationKey<String> DISCLOSURE_TEXT_EN = disclosureKey(DISCLOSURE_TEXT_EN_CODE, "English");

    /** {@link #DISCLOSURE_TEXT_EN}, in Russian. */
    public static final ConfigurationKey<String> DISCLOSURE_TEXT_RU = disclosureKey(DISCLOSURE_TEXT_RU_CODE, "Russian");

    /** {@link #DISCLOSURE_TEXT_EN}, in Uzbek (Latin). */
    public static final ConfigurationKey<String> DISCLOSURE_TEXT_UZ = disclosureKey(DISCLOSURE_TEXT_UZ_CODE, "Uzbek");

    /** The disclosure key for a reply locale ({@code en}, {@code ru} or {@code uz}); English for any other. */
    public static ConfigurationKey<String> disclosureKeyFor(String locale) {
        return switch (locale) {
            case "ru" -> DISCLOSURE_TEXT_RU;
            case "uz" -> DISCLOSURE_TEXT_UZ;
            default -> DISCLOSURE_TEXT_EN;
        };
    }

    private static ConfigurationKey<String> disclosureKey(String code, String language) {
        return ConfigurationKey.of(code, String.class)
                .defaultValue("")
                .ownedBy("assistant")
                .tenantVisible()
                .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
                .describedAs("The " + language + " sentence the assistant says ahead of its first answer in a "
                        + "conversation, telling the customer it is automated and that the question is "
                        + "processed by an outside service (ADR 0069). Blank keeps the platform's default wording.")
                .build();
    }

    private AssistantConfigurationKeys() {}
}
