package uz.horecaos.platform.catalog.domain;

/**
 * The publication findings an auto-add rule can raise (ADR 0158).
 *
 * <p>Code-owned stable strings an operator console translates and a support engineer can search for.
 * Errors block publication; warnings do not. Fiscal classification of an injected variant is already
 * reported by {@code FISCAL_CLASSIFICATION_MISSING} for every variant offered (ADR 0038), so it needs no
 * finding of its own.
 */
public final class AutoAddFindings {

    private AutoAddFindings() {}

    /** Blocker: a product-triggered rule with no live trigger node (none whose product, variant or category is not archived). */
    public static final String AUTO_ADD_TRIGGER_MISSING = "AUTO_ADD_TRIGGER_MISSING";

    /** Blocker: the injected variant is not active, has no price in scope, or is not on this menu. */
    public static final String AUTO_ADD_VARIANT_NOT_SELLABLE = "AUTO_ADD_VARIANT_NOT_SELLABLE";

    /** Blocker: two active rules add the same variant in overlapping modes, channel types and locations. */
    public static final String AUTO_ADD_RULES_OVERLAP = "AUTO_ADD_RULES_OVERLAP";

    /** Blocker: the injected variant is already charged by a hidden modifier in overlapping fulfilment modes. */
    public static final String AUTO_ADD_DUPLICATES_HIDDEN_PACKAGING = "AUTO_ADD_DUPLICATES_HIDDEN_PACKAGING";

    /** Blocker: a mandatory rule while {@code catalog.auto_add_mandatory_allowed} is off. */
    public static final String AUTO_ADD_MANDATORY_NOT_ALLOWED = "AUTO_ADD_MANDATORY_NOT_ALLOWED";

    /** Blocker: a mandatory rule with no customer text in the brand's default locale. */
    public static final String AUTO_ADD_MANDATORY_UNDISCLOSED = "AUTO_ADD_MANDATORY_UNDISCLOSED";

    /** Blocker for an INJECT rule, warning for an OFFER rule: the variant is the gift of an active FREE_ITEM promotion. */
    public static final String AUTO_ADD_VARIANT_IS_PROMOTION_GIFT = "AUTO_ADD_VARIANT_IS_PROMOTION_GIFT";

    /** Warning: a portion-band rule on a brand where no variant carries {@code portions_per_unit > 0}. */
    public static final String AUTO_ADD_PORTION_BAND_COUNTS_NOTHING = "AUTO_ADD_PORTION_BAND_COUNTS_NOTHING";

    /** Warning: a gap between two bands of one rule, a total that falls in it injects nothing. */
    public static final String AUTO_ADD_BAND_GAP = "AUTO_ADD_BAND_GAP";
}
