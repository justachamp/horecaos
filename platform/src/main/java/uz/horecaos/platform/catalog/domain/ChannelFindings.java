package uz.horecaos.platform.catalog.domain;

/**
 * The finding codes a channel preview can emit beyond the ordinary publication
 * ones (ADR 0138).
 *
 * <p>Code-owned, like {@code CatalogValidator}'s: a stable string an operator
 * console translates and a support engineer can search for. Two families, and
 * the split is the point.
 *
 * <p><strong>Projection codes</strong> are mechanical facts about what one channel
 * would receive at one branch — the channel is archived, the branch does not sell
 * on it, nothing survives the gates, a variant has no price on this channel's
 * plane. They do not depend on any marketplace's rules, so they fire for a
 * storefront preview as readily as for an aggregator's.
 *
 * <p><strong>Marketplace codes</strong> carry the {@value #MARKETPLACE_PREFIX}
 * prefix and belong to a partner's ruleset. The four reserved ones exist with no
 * firing logic until a ruleset is authored (ADR 0138 leaves the per-marketplace
 * content rules to product and partnerships); the two bookkeeping ones —
 * {@link #MARKETPLACE_RULESET_NOT_ASSIGNED} and {@link #MARKETPLACE_RULESET_UNKNOWN}
 * — are the preview's own, and say that a check <em>did not run</em>. That is a
 * different and more alarming thing than a check that failed, and the reason
 * they exist: with every ruleset empty, "no findings" would otherwise read as
 * "the marketplace will accept this menu".
 */
public final class ChannelFindings {

    private ChannelFindings() {}

    // ------------------------------------------------------------- projection

    /** Blocker: publication to an archived channel is refused, so there is no live menu to preview. */
    public static final String CHANNEL_ARCHIVED = "CHANNEL_ARCHIVED";

    /** Warning: the channel exists but is switched off, so nothing is being sent to it. */
    public static final String CHANNEL_INACTIVE = "CHANNEL_INACTIVE";

    /** Blocker: the branch does not sell on this channel ({@code tenant.sales_channel_locations}). */
    public static final String CHANNEL_NOT_ENABLED_AT_LOCATION = "CHANNEL_NOT_ENABLED_AT_LOCATION";

    /** Blocker: every item was gated out, so the channel would be sent an empty menu. */
    public static final String PROJECTION_EMPTY = "PROJECTION_EMPTY";

    /** Blocker: no active price book resolves for this branch and channel, so every amount is missing. */
    public static final String CHANNEL_PRICE_BOOK_MISSING = "CHANNEL_PRICE_BOOK_MISSING";

    /**
     * Blocker: a variant, a modifier option or a combo component is priced somewhere in the brand
     * but not on the plane this channel resolves to — the case the brand-wide {@code
     * VARIANT_HAS_NO_ACTIVE_PRICE} and {@code COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE} cannot see. The
     * finding's entity type says which of the three.
     */
    public static final String CHANNEL_PRICE_MISSING = "CHANNEL_PRICE_MISSING";

    /** Blocker: a channel media override names an asset that is not verified and displayable. */
    public static final String CHANNEL_MEDIA_NOT_AVAILABLE = "CHANNEL_MEDIA_NOT_AVAILABLE";

    // ------------------------------------------------------------ marketplace

    public static final String MARKETPLACE_PREFIX = "MARKETPLACE_";

    // Reserved by ADR 0138; no firing logic until a ruleset is authored.
    public static final String MARKETPLACE_IMAGE_REQUIREMENT_UNMET = "MARKETPLACE_IMAGE_REQUIREMENT_UNMET";
    public static final String MARKETPLACE_DESCRIPTION_REQUIREMENT_UNMET = "MARKETPLACE_DESCRIPTION_REQUIREMENT_UNMET";
    public static final String MARKETPLACE_CATEGORY_DEPTH_EXCEEDED = "MARKETPLACE_CATEGORY_DEPTH_EXCEEDED";
    public static final String MARKETPLACE_PRICE_PARITY_VIOLATION = "MARKETPLACE_PRICE_PARITY_VIOLATION";

    /** Warning: the binding names no ruleset, so no partner-specific check ran. */
    public static final String MARKETPLACE_RULESET_NOT_ASSIGNED = "MARKETPLACE_RULESET_NOT_ASSIGNED";

    /** Warning: the binding names a ruleset this build does not carry, so no partner-specific check ran. */
    public static final String MARKETPLACE_RULESET_UNKNOWN = "MARKETPLACE_RULESET_UNKNOWN";

    /** The four codes ADR 0138 reserves, in the order the record lists them. */
    public static final java.util.List<String> RESERVED_MARKETPLACE_CODES = java.util.List.of(
            MARKETPLACE_IMAGE_REQUIREMENT_UNMET,
            MARKETPLACE_DESCRIPTION_REQUIREMENT_UNMET,
            MARKETPLACE_CATEGORY_DEPTH_EXCEEDED,
            MARKETPLACE_PRICE_PARITY_VIOLATION);
}
