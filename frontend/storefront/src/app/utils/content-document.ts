import type {
  CarouselItemKind,
  ContentBrandSwitcherEntry,
  ContentCarouselItem,
  ContentGroup,
  ContentHome,
  ContentLink,
  ContentMedia,
  ContentPopup,
  ContentPopupDaily,
  ContentRendition,
  ContentSlide,
  ContentStoryGroup,
  GroupOrder,
  PopupCapMode,
} from '../types/content.types';
import { httpsUrlOrNull, isSiteRelativePath, resolveApiPath } from './safe-url';

/**
 * Turns the `content/home` body into the shapes the screen draws, or into nothing.
 *
 * The contract (ADR 0157, Storefront API) is that a client skips an unknown field, kind or block
 * rather than failing: the platform adds kinds release by release, and a storefront built last
 * month must keep drawing what it understands from a document written next month. So nothing here
 * throws. A value that is not what it should be drops *that* item (or that field) and the rest of
 * the document stands; a body that is not even an object is "no content".
 *
 * Two things are deliberately stricter than the wire:
 *
 *  - every URL is vetted. A media path must be a site-relative path (the server only ever sends
 *    its own files) and is put back on the API origin; an external link must be `https:`.
 *  - a pop-up whose frequency cap, window or dismissal rule this client cannot read is dropped
 *    rather than shown unrestricted, because a rule the device cannot enforce would turn a "once a
 *    day" pop-up into one on every visit.
 */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CLOCK = /^([01]\d|2[0-3]):([0-5]\d)$/;

const CAROUSEL_KINDS: readonly CarouselItemKind[] = ['BANNER', 'PROMO_CARD'];
const CAP_MODES: readonly PopupCapMode[] = ['ONCE_EVER', 'ONCE_PER_DAY', 'EVERY_N_DAYS'];
const ALL_DAYS: readonly number[] = [1, 2, 3, 4, 5, 6, 7];
const MAX_SLIDES = 10;

type Dict = Record<string, unknown>;

function isDict(value: unknown): value is Dict {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function nonBlank(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

function positiveInt(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 ? value : undefined;
}

function clamp(value: unknown, low: number, high: number, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return fallback;
  }
  return Math.min(high, Math.max(low, Math.round(value)));
}

function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

export function normalizeHomeDocument(raw: unknown, apiBaseUrl: string): ContentHome | null {
  if (!isDict(raw)) {
    return null;
  }
  const media = (value: unknown, fallbackAlt: string) => readMedia(value, fallbackAlt, apiBaseUrl);
  return {
    locale: str(raw['locale']) ?? '',
    carousel: collect(raw['carousel'], (item) => readCarouselItem(item, media, false)),
    groups: collect(raw['groups'], readGroup),
    stories: collect(raw['stories'], (item) => readStory(item, media)),
    promoGrid: collect(raw['promoGrid'], (item) => readCarouselItem(item, media, true)),
    popup: readPopup(raw['popup'], media),
    brandSwitcher: collect(raw['brandSwitcher'], (entry) => readSwitcherEntry(entry, media)),
    // Only the exact word turns the marketplace layout on: an unknown value from a later release
    // must leave today's storefront as it is.
    layout: raw['layout'] === 'MARKETPLACE' ? 'MARKETPLACE' : 'STANDARD',
  };
}

function readSwitcherEntry(raw: unknown, media: MediaReader): ContentBrandSwitcherEntry | null {
  if (!isDict(raw)) {
    return null;
  }
  const targetBrandId = nonBlank(raw['targetBrandId']);
  const name = nonBlank(raw['name']);
  // The second check on an address that leaves the storefront: `https:` only, no credentials.
  const url = httpsUrlOrNull(raw['url']);
  if (targetBrandId === undefined || name === undefined || url === null) {
    return null;
  }
  const logo = raw['logo'] === undefined ? null : media(raw['logo'], name);
  return {
    targetBrandId,
    name,
    ...optional('logo', logo !== null && logo.kind === 'IMAGE' ? logo : undefined),
    url,
  };
}

function collect<T>(value: unknown, read: (item: unknown) => T | null): T[] {
  const out: T[] = [];
  for (const item of list(value)) {
    const entry = read(item);
    if (entry !== null) {
      out.push(entry);
    }
  }
  return out;
}

type MediaReader = (value: unknown, fallbackAlt: string) => ContentMedia | null;

function readCarouselItem(
  raw: unknown,
  media: MediaReader,
  promoCardsOnly: boolean,
): ContentCarouselItem | null {
  if (!isDict(raw)) {
    return null;
  }
  const kind = CAROUSEL_KINDS.find((k) => k === raw['kind']);
  const id = nonBlank(raw['id']);
  const revisionNo = positiveInt(raw['revisionNo']);
  const title = str(raw['title']);
  if (!kind || (promoCardsOnly && kind !== 'PROMO_CARD')) {
    return null;
  }
  if (id === undefined || revisionNo === undefined || title === undefined) {
    return null;
  }
  const itemMedia = media(raw['media'], title);
  if (itemMedia === null) {
    return null;
  }
  return {
    id,
    kind,
    revisionNo,
    title,
    ...optional('subtitle', nonBlank(raw['subtitle'])),
    ...optional('ctaLabel', nonBlank(raw['ctaLabel'])),
    textLocale: str(raw['textLocale']) ?? '',
    media: itemMedia,
    link: readLink(raw['link']),
  };
}

function optional<K extends string, V>(key: K, value: V | undefined): { [P in K]?: V } {
  return value === undefined ? {} : ({ [key]: value } as { [P in K]?: V });
}

function readGroup(raw: unknown): ContentGroup | null {
  if (!isDict(raw)) {
    return null;
  }
  const id = nonBlank(raw['id']);
  const revisionNo = positiveInt(raw['revisionNo']);
  const title = nonBlank(raw['title']);
  if (id === undefined || revisionNo === undefined || title === undefined) {
    return null;
  }
  const base = { id, revisionNo, title, textLocale: str(raw['textLocale']) ?? '' };
  const categoryId = nonBlank(raw['categoryId']);
  if (categoryId !== undefined) {
    const order: GroupOrder = raw['order'] === 'NEWEST' ? 'NEWEST' : 'MENU_ORDER';
    const limit = positiveInt(raw['limit']);
    return {
      ...base,
      source: { kind: 'BY_CATEGORY', categoryId, order, ...optional('limit', limit) },
    };
  }
  const productIds = list(raw['productIds']).filter(
    (p): p is string => typeof p === 'string' && p !== '',
  );
  if (productIds.length === 0) {
    return null;
  }
  return { ...base, source: { kind: 'MANUAL', productIds } };
}

function readStory(raw: unknown, media: MediaReader): ContentStoryGroup | null {
  if (!isDict(raw)) {
    return null;
  }
  const id = nonBlank(raw['id']);
  const revisionNo = positiveInt(raw['revisionNo']);
  const title = str(raw['title']);
  if (id === undefined || revisionNo === undefined || title === undefined) {
    return null;
  }
  const slides = collect(raw['slides'], (slide) => readSlide(slide, media, title)).slice(
    0,
    MAX_SLIDES,
  );
  if (slides.length === 0) {
    return null;
  }
  const avatar = media(raw['avatar'], title);
  return {
    id,
    revisionNo,
    title,
    textLocale: str(raw['textLocale']) ?? '',
    ...optional('avatar', avatar ?? undefined),
    slides,
  };
}

function readSlide(raw: unknown, media: MediaReader, storyTitle: string): ContentSlide | null {
  if (!isDict(raw)) {
    return null;
  }
  const slideMedia = media(raw['media'], str(raw['caption']) ?? storyTitle);
  if (slideMedia === null) {
    return null;
  }
  return {
    media: slideMedia,
    durationSeconds: clamp(raw['durationSeconds'], 3, 15, 5),
    ...optional('caption', nonBlank(raw['caption'])),
    link: readLink(raw['link']),
  };
}

function readPopup(raw: unknown, media: MediaReader): ContentPopup | null {
  if (!isDict(raw)) {
    return null;
  }
  const id = nonBlank(raw['id']);
  const revisionNo = positiveInt(raw['revisionNo']);
  const title = nonBlank(raw['title']);
  if (id === undefined || revisionNo === undefined || title === undefined) {
    return null;
  }
  // The only dismissal rule there is. One this client has never heard of is one it cannot honour.
  if (raw['dismiss'] !== undefined && raw['dismiss'] !== 'DISMISSIBLE') {
    return null;
  }
  const cap = readCap(raw['cap']);
  if (cap === null) {
    return null;
  }
  let daily: ContentPopupDaily | undefined;
  if (raw['daily'] !== undefined && raw['daily'] !== null) {
    const window = readDaily(raw['daily']);
    if (window === null) {
      return null;
    }
    daily = window;
  }
  const popupMedia = raw['media'] === undefined ? null : media(raw['media'], title);
  return {
    id,
    revisionNo,
    title,
    ...optional('body', nonBlank(raw['body'])),
    ...optional('ctaLabel', nonBlank(raw['ctaLabel'])),
    textLocale: str(raw['textLocale']) ?? '',
    ...optional('media', popupMedia ?? undefined),
    link: readLink(raw['link']),
    showAfterSeconds: clamp(raw['showAfterSeconds'], 3, 60, 3),
    ...optional('daily', daily),
    cap,
  };
}

function readCap(raw: unknown): ContentPopup['cap'] | null {
  if (!isDict(raw)) {
    // The server always sends one; a document without it is not one this client can cap.
    return null;
  }
  const mode = CAP_MODES.find((m) => m === raw['mode']);
  if (!mode) {
    return null;
  }
  return { mode, n: clamp(raw['n'], 1, 30, 1) };
}

function readDaily(raw: unknown): ContentPopupDaily | null {
  if (!isDict(raw)) {
    return null;
  }
  const from = str(raw['from']);
  const until = str(raw['until']);
  if (!from || !until || !CLOCK.test(from) || !CLOCK.test(until)) {
    return null;
  }
  const given = list(raw['daysOfWeek']);
  const days = given.filter(
    (d): d is number => Number.isInteger(d) && (d as number) >= 1 && (d as number) <= 7,
  );
  if (days.length !== given.length) {
    return null;
  }
  return {
    from,
    until,
    daysOfWeek: days.length === 0 ? ALL_DAYS : [...new Set(days)].sort((a, b) => a - b),
  };
}

function readLink(raw: unknown): ContentLink {
  if (!isDict(raw)) {
    return { type: 'NONE' };
  }
  const targetId = str(raw['targetId']);
  switch (raw['type']) {
    case 'PRODUCT':
    case 'CATEGORY':
    case 'PROMOTION': {
      if (targetId === undefined || !UUID.test(targetId)) {
        return { type: 'NONE' };
      }
      return { type: raw['type'], targetId };
    }
    case 'CART':
      return { type: 'CART' };
    case 'EXTERNAL': {
      const url = httpsUrlOrNull(raw['url']);
      return url === null ? { type: 'NONE' } : { type: 'EXTERNAL', url };
    }
    default:
      // NONE, and any link kind a later release adds: the item stays, it just is not a link.
      return { type: 'NONE' };
  }
}

/**
 * One `Media` object of the document, or `null` when it cannot be shown: an image with no usable
 * rendition, a video with no mp4, a kind this client does not know. Shared with the editorial
 * documents, which carry the same shape.
 */
export function normalizeContentMedia(
  raw: unknown,
  fallbackAlt: string,
  apiBaseUrl: string,
): ContentMedia | null {
  return readMedia(raw, fallbackAlt, apiBaseUrl);
}

function readMedia(raw: unknown, fallbackAlt: string, apiBaseUrl: string): ContentMedia | null {
  if (!isDict(raw)) {
    return null;
  }
  const alt = str(raw['alt']) ?? fallbackAlt;
  const widthPx = positiveInt(raw['widthPx']);
  const heightPx = positiveInt(raw['heightPx']);
  const dimensions = { ...optional('widthPx', widthPx), ...optional('heightPx', heightPx) };
  if (raw['kind'] === 'IMAGE') {
    const renditions: ContentRendition[] = [];
    for (const entry of list(raw['renditions'])) {
      if (isDict(entry) && positiveInt(entry['widthPx']) && isSiteRelativePath(entry['url'])) {
        renditions.push({
          widthPx: entry['widthPx'] as number,
          url: resolveApiPath(apiBaseUrl, entry['url']),
        });
      }
    }
    if (renditions.length === 0) {
      return null;
    }
    renditions.sort((a, b) => a.widthPx - b.widthPx);
    return { kind: 'IMAGE', alt, ...dimensions, renditions };
  }
  if (raw['kind'] === 'VIDEO') {
    if (!isSiteRelativePath(raw['url'])) {
      return null;
    }
    const poster = isSiteRelativePath(raw['posterUrl'])
      ? resolveApiPath(apiBaseUrl, raw['posterUrl'])
      : undefined;
    return {
      kind: 'VIDEO',
      alt,
      ...dimensions,
      url: resolveApiPath(apiBaseUrl, raw['url']),
      ...optional('posterUrl', poster),
      ...optional('durationMs', positiveInt(raw['durationMs'])),
    };
  }
  return null;
}
