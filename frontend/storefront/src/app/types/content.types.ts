/**
 * What the content read gives the home screen (ADR 0157, Storefront API).
 *
 * These are the shapes the storefront works with *after* `normalizeHomeDocument` has been over the
 * wire document, not the wire document itself: that one is read defensively (a client skips an
 * unknown field, kind or block rather than failing), so everything here is already known to be well
 * formed and every media URL is already resolved against the API origin.
 *
 * Deliberately not `OfferItem` / `CustomerUiOffer` from `home.types.ts`. Those are ADR 0112's
 * personalised-offer wire type; a carousel item is brand-chosen content shown to everybody, with no
 * guest or decision log, and the two are composed on the screen rather than merged (ADR 0157,
 * Decision 5).
 */

/** The link kinds the document may carry. `PROMOTION` is read but this storefront has no screen for it. */
export type ContentLinkType = 'NONE' | 'PRODUCT' | 'CATEGORY' | 'CART' | 'PROMOTION' | 'EXTERNAL';

export interface ContentLink {
  readonly type: ContentLinkType;
  /** A product or category id; present for `PRODUCT` and `CATEGORY` only. */
  readonly targetId?: string;
  /** An `https:` URL; present for `EXTERNAL` only, and already checked. */
  readonly url?: string;
}

export interface ContentRendition {
  readonly widthPx: number;
  readonly url: string;
}

export interface ContentImageMedia {
  readonly kind: 'IMAGE';
  readonly alt: string;
  readonly widthPx?: number;
  readonly heightPx?: number;
  /** Ascending by width, never empty. The original is never among them. */
  readonly renditions: readonly ContentRendition[];
}

export interface ContentVideoMedia {
  readonly kind: 'VIDEO';
  readonly alt: string;
  readonly widthPx?: number;
  readonly heightPx?: number;
  /** The mp4. */
  readonly url: string;
  readonly posterUrl?: string;
  readonly durationMs?: number;
}

export type ContentMedia = ContentImageMedia | ContentVideoMedia;

export type CarouselItemKind = 'BANNER' | 'PROMO_CARD';

export interface ContentCarouselItem {
  readonly id: string;
  readonly kind: CarouselItemKind;
  readonly revisionNo: number;
  readonly title: string;
  readonly subtitle?: string;
  readonly ctaLabel?: string;
  /** The language the texts are actually in (the brand's fallback chain may differ from the page's). */
  readonly textLocale: string;
  readonly media: ContentMedia;
  readonly link: ContentLink;
}

export type GroupOrder = 'MENU_ORDER' | 'NEWEST';

export interface ContentGroup {
  readonly id: string;
  readonly revisionNo: number;
  readonly title: string;
  readonly textLocale: string;
  /** A manual group lists product ids; a by-category group names its category. */
  readonly source:
    | { readonly kind: 'MANUAL'; readonly productIds: readonly string[] }
    | {
        readonly kind: 'BY_CATEGORY';
        readonly categoryId: string;
        readonly order: GroupOrder;
        readonly limit?: number;
      };
}

export interface ContentSlide {
  readonly media: ContentMedia;
  /** 3 to 15. */
  readonly durationSeconds: number;
  readonly caption?: string;
  readonly link: ContentLink;
}

export interface ContentStoryGroup {
  readonly id: string;
  readonly revisionNo: number;
  readonly title: string;
  readonly textLocale: string;
  /** The round picture in the rail; absent means the first slide's image stands in. */
  readonly avatar?: ContentMedia;
  /** Never empty. */
  readonly slides: readonly ContentSlide[];
}

export type PopupCapMode = 'ONCE_EVER' | 'ONCE_PER_DAY' | 'EVERY_N_DAYS';

export interface ContentPopupDaily {
  /** `HH:mm`, inclusive, the device's local clock. */
  readonly from: string;
  /** `HH:mm`, exclusive; earlier than `from` means the window runs past midnight. */
  readonly until: string;
  /** 1 (Monday) to 7 (Sunday); never empty. */
  readonly daysOfWeek: readonly number[];
}

export interface ContentPopup {
  readonly id: string;
  readonly revisionNo: number;
  readonly title: string;
  readonly body?: string;
  readonly ctaLabel?: string;
  readonly textLocale: string;
  readonly media?: ContentMedia;
  readonly link: ContentLink;
  /** 3 to 60. */
  readonly showAfterSeconds: number;
  readonly daily?: ContentPopupDaily;
  readonly cap: { readonly mode: PopupCapMode; readonly n: number };
}

/** The whole `content/home` document, normalised. */
export interface ContentHome {
  readonly locale: string;
  readonly carousel: readonly ContentCarouselItem[];
  readonly groups: readonly ContentGroup[];
  readonly stories: readonly ContentStoryGroup[];
  readonly promoGrid: readonly ContentCarouselItem[];
  readonly popup: ContentPopup | null;
}

/** The anonymous counters (ADR 0157, Decision 10). */
export type ContentEventType = 'IMPRESSION' | 'CLICK' | 'DISMISSAL' | 'COMPLETION';

export interface ContentEvent {
  readonly itemId: string;
  readonly revisionNo: number;
  readonly event: ContentEventType;
}

/** What a page hands a content component so a category link can carry its name to the category page. */
export interface ContentCategoryRef {
  readonly id: string;
  readonly name: string;
}
