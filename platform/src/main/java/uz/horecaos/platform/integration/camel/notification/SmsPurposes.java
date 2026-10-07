package uz.horecaos.platform.integration.camel.notification;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What an SMS may be for, as far as a gateway account is concerned (ADR 0146).
 *
 * <p>A gateway account is a commercial relationship, and the provider may have
 * cleared it for sign-in codes and order messages without having agreed to carry
 * promotions or courier traffic. ADR 0146's default is VAS for the first two and
 * a stated refusal for the others until the owner answers in writing, so the
 * answer is a per-installation fact (the {@code permittedPurposes} configuration
 * key), not a code change, and the refusal has a stable code.
 */
public final class SmsPurposes {

    public static final String VERIFICATION = "VERIFICATION";
    public static final String TRANSACTIONAL = "TRANSACTIONAL";
    public static final String MARKETING = "MARKETING";
    public static final String COURIER = "COURIER";

    /** The installation configuration key holding a comma-separated list. */
    public static final String CONFIGURATION_KEY = "permittedPurposes";

    /** The stable code a refused purpose answers with. */
    public static final String REFUSAL_CODE = "SMS_PURPOSE_NOT_PERMITTED";

    private static final Set<String> KNOWN = Set.of(VERIFICATION, TRANSACTIONAL, MARKETING, COURIER);

    private SmsPurposes() {}

    /**
     * The purposes an installation may carry: its own list when it has one,
     * otherwise {@code defaults}. An unknown word in the list is ignored rather
     * than read as a permission, because a typo must never widen what an account
     * carries.
     */
    public static Set<String> permitted(AccountContext account, Set<String> defaults) {
        String configured = account.value(CONFIGURATION_KEY);
        if (configured == null) {
            return defaults;
        }
        return Stream.of(configured.split(","))
                .map(String::trim)
                .map(word -> word.toUpperCase(java.util.Locale.ROOT))
                .filter(KNOWN::contains)
                .collect(Collectors.toUnmodifiableSet());
    }
}
