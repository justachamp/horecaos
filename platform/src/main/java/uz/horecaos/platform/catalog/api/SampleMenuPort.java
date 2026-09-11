package uz.horecaos.platform.catalog.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The sample menu a new tenant may be onboarded with (ADR 0099).
 *
 * <p>Catalog owns what the sample <em>is</em> — the items, their three locales,
 * their category structure — because that is menu content and menu content
 * lives here. It does not own the sequencing: a sample menu is only useful once
 * it is also priced ({@code pricing}) and stocked ({@code inventory}), and the
 * step that orders those three is {@code SAMPLE_MENU_PUBLISH} in {@code
 * ordering}, the one module that already depends on all three.
 *
 * <p>Every method is idempotent. The step can die between any two calls, and
 * {@code OnboardingService} will run it again from the top.
 */
public interface SampleMenuPort {

    /** The catalog code the sample always uses, and the key a retry finds it by. */
    String SAMPLE_CATALOG_CODE = "SAMPLE-MENU";

    /** This brand's sample catalog, when a previous attempt already created it. */
    Optional<UUID> sampleCatalogId(UUID tenantId, UUID brandId);

    /** The catalog behind the brand's live publication on this channel, if any. */
    Optional<UUID> publishedCatalogId(UUID tenantId, UUID brandId, String channel);

    /**
     * Creates the sample draft, or returns the one that is already there.
     *
     * <p>Every item is offered {@code AVAILABLE} for pickup and delivery at
     * every location given — but only where no offering exists yet. An offering
     * row is operator state: a sample dish somebody set {@code UNAVAILABLE} or
     * {@code HIDDEN} stays that way through every later attempt and every later
     * run, the same rule {@code StockListingPort.ensureListed} applies to a
     * deliberately sold-out stock item. Nothing is published: publishing is
     * {@link #publishSample}, after the variants have prices.
     *
     * <p>The authoring locale is deliberately not a parameter. Catalog
     * validation requires a name in the locale {@code CatalogSnapshotLoader}
     * calls default, which is catalog's own configuration
     * ({@code horecaos.catalog.default-locale}) — a caller that passed the
     * owner's language instead would author a menu that then failed to publish
     * with {@code MISSING_TRANSLATION}. All three locales are written either
     * way; only which one is the authoring locale is at stake, and that is not
     * the caller's to decide.
     *
     * @param locationIds every location the menu should be offered at
     */
    SampleMenu installSample(UUID tenantId, UUID brandId, List<UUID> locationIds);

    /**
     * Publishes the sample catalog to a channel, or returns the publication that
     * is already live there.
     *
     * <p>Deliberately returns an existing live publication rather than taking a
     * second snapshot of identical content: a republish would retire a perfectly
     * good publication and change the menu's ETag for every customer holding it.
     */
    SamplePublication publishSample(UUID tenantId, UUID brandId, UUID catalogId, String channel);

    /**
     * The sample draft, as it now stands.
     *
     * @param variants          what needs a price, with the amount the sample
     *                          suggests
     * @param offeringsCreated  how many location offerings this call created,
     *                          zero on an attempt that found every one already
     *                          there. Recorded in the step's result snapshot so
     *                          a run says when it touched offerings, which is
     *                          the only place the answer is visible
     * @param created           false when a previous attempt had already built it
     */
    record SampleMenu(
            UUID catalogId,
            String catalogCode,
            int categories,
            int products,
            List<SampleVariant> variants,
            int offeringsCreated,
            boolean created) {}

    /** One sellable sample item and what it should cost, in minor units. */
    record SampleVariant(UUID variantId, String sku, long amountMinor) {}

    /**
     * What {@link #publishSample} found or did.
     *
     * @param blockers the validation codes that refused a publication, empty
     *                 when it succeeded. Codes only — a blocker's own message
     *                 names entities, and this travels into a step's error detail
     */
    record SamplePublication(UUID publicationId, boolean created, List<String> blockers) {}
}
