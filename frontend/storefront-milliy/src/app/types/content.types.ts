/**
 * The storefront content document (ADR 0157): what a brand puts in front of its customers on the
 * home screen -- banners and promo cards, product groups, stories, a promo grid and an entry
 * pop-up -- as `GET .../content/home` serves it.
 *
 * These are the types the screens use, *after* `parseContentHome` has read the wire body: every
 * list is present (empty when the wire omitted it), every media URL is already resolved to an
 * absolute one, and anything the client does not recognise has been left out rather than guessed
 * at. A client skips an unknown field, kind or block instead of failing (ADR 0157, Storefront API).
 *
 * Nothing here is a menu type and nothing here is ADR 0112's `OfferItem`: a carousel item is
 * content the brand chose for everybody, with no guest and no decision log behind it (Decision 5).
 */

export type ContentMediaKind = 'IMAGE' | 'VIDEO';

/** One rendered width of an image. The original is never listed (a 10 MiB source must not reach a phone). */
export interface ContentRendition {
  readonly widthPx: number;
  readonly url: string;
}

export interface ContentMedia {
  readonly kind: ContentMediaKind;
  /** The brand's own description of the image; absent means the image is decorative. */
  readonly alt?: string;
  readonly widthPx?: number;
  readonly heightPx?: number;
  /** Images only: ascending by width, never empty on an image that survived parsing. */
  readonly renditions: readonly ContentRendition[];
  /** Video only: the mp4. */
  readonly url?: string;
  /** Video only: the still shown before play, and instead of playing under reduced motion. */
  readonly posterUrl?: string;
  readonly durationMs?: number;
}

export type ContentLinkType = 'NONE' | 'PRODUCT' | 'CATEGORY' | 'CART' | 'PROMOTION' | 'EXTERNAL';

/** Where a tap goes. Never trusted as it arrives: `resolveContentLink` is the one place that decides. */
export interface ContentLink {
  readonly type: ContentLinkType;
  readonly targetId?: string;
  readonly url?: string;
}

export type CarouselItemKind = 'BANNER' | 'PROMO_CARD';

/** A banner or a promo card. `revisionNo` is what the anonymous counters are filed under. */
export interface ContentCarouselItem {
  readonly id: string;
  readonly kind: CarouselItemKind;
  readonly revisionNo: number;
  readonly title: string;
  readonly subtitle?: string;
  readonly ctaLabel?: string;
  /** The language the texts are in; differs from the page's when the brand had no translation. */
  readonly textLocale: string;
  readonly media: ContentMedia;
  readonly link: ContentLink;
}

export type ContentGroupOrder = 'MENU_ORDER' | 'NEWEST';

/**
 * A product group. A manual group lists `productIds`; a by-category group names `categoryId`. Both
 * are joined against the menu the page already holds -- the server is never asked for products.
 */
export interface ContentGroup {
  readonly id: string;
  readonly revisionNo: number;
  readonly title: string;
  readonly textLocale: string;
  readonly productIds: readonly string[];
  readonly categoryId?: string;
  readonly order?: ContentGroupOrder;
  readonly limit?: number;
}

export interface ContentStorySlide {
  readonly media: ContentMedia;
  /** 3..15, already clamped. */
  readonly durationSeconds: number;
  readonly caption?: string;
  readonly link: ContentLink;
}

export interface ContentStoryGroup {
  readonly id: string;
  readonly revisionNo: number;
  readonly title: string;
  readonly textLocale: string;
  readonly avatar?: ContentMedia;
  /** Never empty on a group that survived parsing. */
  readonly slides: readonly ContentStorySlide[];
}

export type PopupCapMode = 'ONCE_EVER' | 'ONCE_PER_DAY' | 'EVERY_N_DAYS';

export interface PopupCap {
  readonly mode: PopupCapMode;
  /** The number of days for `EVERY_N_DAYS`; at least 1. */
  readonly n: number;
}

/** A time-of-day window on the device's own clock: `from` inclusive, `until` exclusive, may wrap midnight. */
export interface PopupDaily {
  /** `HH:mm`. */
  readonly from: string;
  /** `HH:mm`. */
  readonly until: string;
  /** 1 = Monday ... 7 = Sunday. */
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
  /** 3..60, already clamped. */
  readonly showAfterSeconds: number;
  readonly daily?: PopupDaily;
  readonly cap: PopupCap;
}

/**
 * A way to a sibling brand's storefront (ADR 0157 Release 4, ADR 0183 D17). The address is the
 * target website's verified hostname, resolved by the server on this read and checked again by the
 * parser; it is never copied from anything a person typed.
 */
export interface ContentBrandSwitcherEntry {
  readonly targetBrandId: string;
  readonly name: string;
  /** The brand's logo when it has a servable image; absent means the name stands alone. */
  readonly logo?: ContentMedia;
  /** An `https:` address, already checked. */
  readonly url: string;
}

/** `STANDARD` is today's category rail; `MARKETPLACE` a compact rail plus an overlay (ADR 0183 D17). */
export type StorefrontLayout = 'STANDARD' | 'MARKETPLACE';

/** The whole document, normalised. */
export interface ContentHome {
  readonly locale: string;
  readonly carousel: readonly ContentCarouselItem[];
  readonly groups: readonly ContentGroup[];
  readonly stories: readonly ContentStoryGroup[];
  readonly promoGrid: readonly ContentCarouselItem[];
  readonly popup: ContentPopup | null;
  /** Empty for a brand that offers no other brand: the server then omits the field. */
  readonly brandSwitcher: readonly ContentBrandSwitcherEntry[];
  /** `STANDARD` unless the document says exactly `MARKETPLACE`. */
  readonly layout: StorefrontLayout;
}

/** "No content": what a failed read, and a brand with nothing published, both look like. */
export const NO_CONTENT: ContentHome = Object.freeze({
  locale: '',
  carousel: Object.freeze([]) as readonly ContentCarouselItem[],
  groups: Object.freeze([]) as readonly ContentGroup[],
  stories: Object.freeze([]) as readonly ContentStoryGroup[],
  promoGrid: Object.freeze([]) as readonly ContentCarouselItem[],
  popup: null,
  brandSwitcher: Object.freeze([]) as readonly ContentBrandSwitcherEntry[],
  layout: 'STANDARD',
});

export type ContentEventName = 'IMPRESSION' | 'CLICK' | 'DISMISSAL' | 'COMPLETION';
