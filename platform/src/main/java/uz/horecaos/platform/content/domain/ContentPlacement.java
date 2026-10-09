package uz.horecaos.platform.content.domain;

import java.util.Optional;

/**
 * Where on the home screen an item sits (ADR 0157, Physical model).
 *
 * <p>The offer carousel row {@code 6.8} asks for is not a second feature: it is
 * {@link #HOME_CAROUSEL}, which banners and promo cards share. "Home groups" are items of kind
 * {@code PRODUCT_GROUP} in {@link #HOME_GROUPS}. Editorial items have no placement.
 *
 * @param limit the platform guardrail on live items in this placement, a code constant and not a
 *              setting: a carousel of eleven banners is not a better carousel
 */
public enum ContentPlacement {
    HOME_CAROUSEL(10),
    HOME_GROUPS(8),
    STORY_RAIL(20),
    PROMO_GRID(12),
    ENTRY_MODAL(1);

    private final int limit;

    ContentPlacement(int limit) {
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    public static Optional<ContentPlacement> find(String name) {
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
