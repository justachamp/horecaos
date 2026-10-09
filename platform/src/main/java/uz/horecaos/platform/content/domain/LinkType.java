package uz.horecaos.platform.content.domain;

import java.util.Optional;

/**
 * The closed union of what a banner, a card, a slide or a pop-up may link to (ADR 0157, Decision 3).
 *
 * <p>The four actions the legacy storefront knew ({@code open_items}, {@code open_category},
 * {@code open_cart}, {@code open_url}) plus the one the IA adds, a promotion, plus {@link #NONE}.
 * {@code COLLECTION} (ADR 0044) is absent on purpose: the catalogue has no collection entity a link
 * could name, so the type would name nothing.
 */
public enum LinkType {
    NONE(false, false),
    PRODUCT(true, false),
    CATEGORY(true, false),
    CART(false, false),
    PROMOTION(true, false),
    EXTERNAL(false, true);

    private final boolean needsTarget;
    private final boolean needsUrl;

    LinkType(boolean needsTarget, boolean needsUrl) {
        this.needsTarget = needsTarget;
        this.needsUrl = needsUrl;
    }

    /** Names an id: a product, a category or a promotion. */
    public boolean needsTarget() {
        return needsTarget;
    }

    /** Carries an {@code https} URL whose host must be on the brand's allowlist. */
    public boolean needsUrl() {
        return needsUrl;
    }

    public static Optional<LinkType> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.strip().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
