package uz.horecaos.platform.content.domain;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The kinds of brand-authored content (ADR 0157, Decision 1): one item model, two tiers.
 *
 * <p>Tier 1 is bounded merchandising and is built first; tier 2 is editorial, which a brand does
 * not have until the platform owner and the brand both switch it on. A kind that needs more than
 * the shared lifecycle, schedule, locale and audit machinery carries it inside its typed document,
 * which is why there is one table and not nine.
 */
public enum ContentKind {
    BANNER(false, null, ContentPlacement.HOME_CAROUSEL),
    PROMO_CARD(false, null, ContentPlacement.HOME_CAROUSEL, ContentPlacement.PROMO_GRID),
    PRODUCT_GROUP(false, null, ContentPlacement.HOME_GROUPS),
    STORY_GROUP(false, null, ContentPlacement.STORY_RAIL),
    POPUP(false, null, ContentPlacement.ENTRY_MODAL),
    NEWS(true, "news"),
    GALLERY_ALBUM(true, "gallery"),
    RECIPE(true, "recipes"),
    VACANCY(true, "vacancies");

    private final boolean editorial;
    private final @Nullable String editorialFeature;
    private final List<ContentPlacement> placements;

    ContentKind(boolean editorial, @Nullable String editorialFeature, ContentPlacement... placements) {
        this.editorial = editorial;
        this.editorialFeature = editorialFeature;
        this.placements = List.of(placements);
    }

    /** Tier 2: addressed by a slug, placed nowhere, and off until entitlement and policy both say yes. */
    public boolean editorial() {
        return editorial;
    }

    /**
     * The name of this kind in the {@code content.editorial} policy and in the storefront's
     * {@code features} read ({@code news}, {@code gallery}, {@code recipes}, {@code vacancies}).
     * Only an editorial kind has one.
     */
    public Optional<String> editorialFeature() {
        return Optional.ofNullable(editorialFeature);
    }

    /** The placements this kind may sit in; empty for an editorial kind. */
    public List<ContentPlacement> placements() {
        return placements;
    }

    /**
     * The placement a kind has when the author names none: the first it may sit in, which for a
     * promo card is the home carousel. The console's JSON may omit every optional key, so an item
     * must have somewhere to go without being told.
     */
    public Optional<ContentPlacement> defaultPlacement() {
        return placements.isEmpty() ? Optional.empty() : Optional.of(placements.getFirst());
    }

    public static Optional<ContentKind> find(String name) {
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
