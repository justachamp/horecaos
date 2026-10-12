package uz.horecaos.platform.content.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The storefront's content document (ADR 0157, Storefront API): one document, the same for every
 * visitor of a brand, a channel and a language.
 *
 * <p>It carries no personal data and no timestamp -- the body's digest is the {@code ETag}, and it
 * moves only when content does. Release 2 adds {@code stories}, {@code promoGrid} and {@code popup}
 * and Release 3 a {@code VIDEO} media kind; they are omitted when empty, so a brand that uses none
 * of them is served exactly the Release 1 document. A client skips a field, a kind or a block it
 * does not know.
 *
 * <p>A carousel item is <em>content</em>, not an offer: the brand chose it for everyone and it has no
 * guest, decision log or contact policy, so it is deliberately a different representation from ADR
 * 0112's {@code OfferItem}, which stays an authenticated, per-guest read (ADR 0157, Decision 5).
 */
public record HomeDocument(
        String locale,
        List<CarouselItem> carousel,
        List<Group> groups,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<StoryGroup> stories,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CarouselItem> promoGrid,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Popup popup) {

    public HomeDocument {
        carousel = List.copyOf(carousel);
        groups = List.copyOf(groups);
        stories = List.copyOf(stories);
        promoGrid = List.copyOf(promoGrid);
    }

    /** A banner or a promo card. {@code revisionNo} is what the anonymous counters are filed under. */
    public record CarouselItem(
            UUID id,
            String kind,
            int revisionNo,
            String title,
            @Nullable String subtitle,
            @Nullable String ctaLabel,
            String textLocale,
            Media media,
            Link link) {}

    /**
     * An image or a video. An image lists the renditions that exist (a client falls back to the
     * largest) and never the original, so a 10 MiB source cannot reach a phone's home screen. A
     * video plays muted and inline from {@code url}, with its poster.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(
            String kind,
            @Nullable String alt,
            @Nullable Integer widthPx,
            @Nullable Integer heightPx,
            List<Rendition> renditions,
            @Nullable String url,
            @Nullable String posterUrl,
            @Nullable Integer durationMs) {

        public Media {
            renditions = List.copyOf(renditions);
        }
    }

    public record Rendition(int widthPx, String url) {}

    /** Where a tap goes. A degraded link is {@code NONE}; the item stays live. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Link(
            String type, @Nullable UUID targetId, @Nullable String url) {

        public static Link none() {
            return new Link("NONE", null, null);
        }
    }

    /**
     * A product group for the home screen. A manual group lists the product ids the brand's active
     * publication still holds; a by-category group names its category, an order and a limit for the
     * storefront to join against the menu it already holds.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(
            UUID id,
            int revisionNo,
            String title,
            String textLocale,
            List<UUID> productIds,
            @Nullable UUID categoryId,
            @Nullable String order,
            @Nullable Integer limit) {

        public Group {
            productIds = List.copyOf(productIds);
        }
    }

    public record StoryGroup(
            UUID id,
            int revisionNo,
            String title,
            String textLocale,
            @Nullable Media avatar,
            List<Slide> slides) {

        public StoryGroup {
            slides = List.copyOf(slides);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Slide(
            Media media, int durationSeconds, @Nullable String caption, Link link) {}

    /**
     * An entry pop-up. The frequency cap is a <em>hint enforced on the device</em> and published here,
     * because an anonymous browser has no identity the server could count against; another storefront
     * may ignore it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Popup(
            UUID id,
            int revisionNo,
            String title,
            @Nullable String body,
            @Nullable String ctaLabel,
            String textLocale,
            @Nullable Media media,
            Link link,
            int showAfterSeconds,
            @Nullable Daily daily,
            Cap cap,
            String dismiss) {}

    public record Daily(String from, String until, List<Integer> daysOfWeek) {

        public Daily {
            daysOfWeek = List.copyOf(daysOfWeek);
        }
    }

    public record Cap(String mode, int n) {}
}
