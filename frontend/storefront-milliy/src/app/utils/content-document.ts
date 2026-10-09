import {
  type CarouselItemKind,
  type ContentCarouselItem,
  type ContentGroup,
  type ContentGroupOrder,
  type ContentHome,
  type ContentLink,
  type ContentLinkType,
  type ContentMedia,
  type ContentPopup,
  type ContentRendition,
  type ContentStoryGroup,
  type ContentStorySlide,
  type PopupCap,
  type PopupCapMode,
  type PopupDaily,
} from '../types/content.types';
import { isRecord, optionalText, positiveInt, text } from './wire';

/**
 * Reads the wire body of `GET .../content/home` into {@link ContentHome}.
 *
 * ADR 0157 (Storefront API): "a client MUST skip an unknown field, kind or block instead of
 * failing". So this is tolerant by construction -- an unknown key is never copied, an item of a
 * kind it does not know or one it cannot draw is left out and the rest are kept in order, and a
 * missing list is an empty one. The only thing it refuses outright is a body that is not an object
 * at all; the caller turns that into "no content".
 *
 * Two things are decided here rather than in a template, because a wrong answer is not a cosmetic
 * one:
 *  - every media URL goes through `resolveUrl`, and media with no usable URL is dropped;
 *  - a pop-up whose cap or daily window cannot be read is dropped, because showing it anyway would
 *    be showing it under rules the client cannot keep.
 *
 * Text is copied verbatim and only ever rendered by interpolation. Link addresses are copied as they
 * arrive and checked again where they are used (`resolveContentLink`).
 */
export function parseContentHome(
  raw: unknown,
  resolveUrl: (url: unknown) => string | null,
): ContentHome {
  if (!isRecord(raw)) {
    throw new Error('The content document is not an object.');
  }
  return {
    locale: text(raw['locale']) ?? '',
    carousel: readList(raw['carousel'], (entry) => readCarouselItem(entry, resolveUrl)),
    groups: readList(raw['groups'], readGroup),
    stories: readList(raw['stories'], (entry) => readStoryGroup(entry, resolveUrl)),
    promoGrid: readList(raw['promoGrid'], (entry) => readCarouselItem(entry, resolveUrl)),
    popup: readPopup(raw['popup'], resolveUrl),
  };
}

/** A whole number of seconds inside `min..max`; the default when it is not a number at all. */
function clampedSeconds(value: unknown, min: number, max: number, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value)
    ? Math.min(max, Math.max(min, Math.trunc(value)))
    : fallback;
}

function readList<T>(value: unknown, read: (entry: unknown) => T | null): readonly T[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const items: T[] = [];
  for (const entry of value) {
    const item = read(entry);
    if (item !== null) {
      items.push(item);
    }
  }
  return items;
}

const LINK_TYPES: ReadonlySet<string> = new Set<ContentLinkType>([
  'NONE',
  'PRODUCT',
  'CATEGORY',
  'CART',
  'PROMOTION',
  'EXTERNAL',
]);

function readLink(value: unknown): ContentLink {
  if (!isRecord(value)) {
    return { type: 'NONE' };
  }
  const type = text(value['type']);
  if (type === undefined || !LINK_TYPES.has(type)) {
    return { type: 'NONE' };
  }
  const targetId = optionalText(value['targetId']);
  const url = optionalText(value['url']);
  return {
    type: type as ContentLinkType,
    ...(targetId !== undefined ? { targetId } : {}),
    ...(url !== undefined ? { url } : {}),
  };
}

export function parseContentMedia(
  value: unknown,
  resolveUrl: (url: unknown) => string | null,
): ContentMedia | null {
  if (!isRecord(value)) {
    return null;
  }
  const common = {
    alt: optionalText(value['alt']),
    widthPx: positiveInt(value['widthPx']),
    heightPx: positiveInt(value['heightPx']),
  };
  if (value['kind'] === 'IMAGE') {
    const renditions: ContentRendition[] = [];
    for (const entry of Array.isArray(value['renditions']) ? value['renditions'] : []) {
      if (!isRecord(entry)) {
        continue;
      }
      const widthPx = positiveInt(entry['widthPx']);
      const url = resolveUrl(entry['url']);
      // A space or a comma would end the candidate early inside `srcset="url 400w, url 800w"`.
      if (widthPx !== undefined && url !== null && !/[\s,]/.test(url)) {
        renditions.push({ widthPx, url });
      }
    }
    renditions.sort((a, b) => a.widthPx - b.widthPx);
    return renditions.length > 0 ? { kind: 'IMAGE', ...common, renditions } : null;
  }
  if (value['kind'] === 'VIDEO') {
    const url = resolveUrl(value['url']) ?? undefined;
    const posterUrl = resolveUrl(value['posterUrl']) ?? undefined;
    return url !== undefined || posterUrl !== undefined
      ? {
          kind: 'VIDEO',
          ...common,
          renditions: [],
          url,
          posterUrl,
          durationMs: positiveInt(value['durationMs']),
        }
      : null;
  }
  return null;
}

const CAROUSEL_KINDS: ReadonlySet<string> = new Set<CarouselItemKind>(['BANNER', 'PROMO_CARD']);

function readCarouselItem(
  value: unknown,
  resolveUrl: (url: unknown) => string | null,
): ContentCarouselItem | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = optionalText(value['id']);
  const kind = text(value['kind']);
  const revisionNo = positiveInt(value['revisionNo']);
  const title = text(value['title']);
  const media = parseContentMedia(value['media'], resolveUrl);
  if (
    id === undefined ||
    kind === undefined ||
    !CAROUSEL_KINDS.has(kind) ||
    revisionNo === undefined ||
    title === undefined ||
    media === null
  ) {
    return null;
  }
  return {
    id,
    kind: kind as CarouselItemKind,
    revisionNo,
    title,
    subtitle: optionalText(value['subtitle']),
    ctaLabel: optionalText(value['ctaLabel']),
    textLocale: text(value['textLocale']) ?? '',
    media,
    link: readLink(value['link']),
  };
}

const GROUP_ORDERS: ReadonlySet<string> = new Set<ContentGroupOrder>(['MENU_ORDER', 'NEWEST']);

function readGroup(value: unknown): ContentGroup | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = optionalText(value['id']);
  const revisionNo = positiveInt(value['revisionNo']);
  const title = text(value['title']);
  if (id === undefined || revisionNo === undefined || title === undefined) {
    return null;
  }
  const order = text(value['order']);
  return {
    id,
    revisionNo,
    title,
    textLocale: text(value['textLocale']) ?? '',
    productIds: Array.isArray(value['productIds'])
      ? value['productIds'].filter((entry): entry is string => typeof entry === 'string')
      : [],
    categoryId: optionalText(value['categoryId']),
    order:
      order !== undefined && GROUP_ORDERS.has(order) ? (order as ContentGroupOrder) : undefined,
    limit: positiveInt(value['limit']),
  };
}

function readSlide(
  value: unknown,
  resolveUrl: (url: unknown) => string | null,
): ContentStorySlide | null {
  if (!isRecord(value)) {
    return null;
  }
  const media = parseContentMedia(value['media'], resolveUrl);
  if (media === null) {
    return null;
  }
  return {
    media,
    durationSeconds: clampedSeconds(value['durationSeconds'], 3, 15, 5),
    caption: optionalText(value['caption']),
    link: readLink(value['link']),
  };
}

function readStoryGroup(
  value: unknown,
  resolveUrl: (url: unknown) => string | null,
): ContentStoryGroup | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = optionalText(value['id']);
  const revisionNo = positiveInt(value['revisionNo']);
  const title = text(value['title']);
  const slides = readList(value['slides'], (entry) => readSlide(entry, resolveUrl));
  if (id === undefined || revisionNo === undefined || title === undefined || slides.length === 0) {
    return null;
  }
  return {
    id,
    revisionNo,
    title,
    textLocale: text(value['textLocale']) ?? '',
    avatar: parseContentMedia(value['avatar'], resolveUrl) ?? undefined,
    slides,
  };
}

const CAP_MODES: ReadonlySet<string> = new Set<PopupCapMode>([
  'ONCE_EVER',
  'ONCE_PER_DAY',
  'EVERY_N_DAYS',
]);

const HH_MM = /^([01]\d|2[0-3]):([0-5]\d)$/;

function readCap(value: unknown): PopupCap | null {
  if (!isRecord(value)) {
    return null;
  }
  const mode = text(value['mode']);
  if (mode === undefined || !CAP_MODES.has(mode)) {
    return null;
  }
  return { mode: mode as PopupCapMode, n: positiveInt(value['n']) ?? 1 };
}

/** A daily window; null when it is absent, `false` when it is there and cannot be read. */
function readDaily(value: unknown): PopupDaily | null | false {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isRecord(value)) {
    return false;
  }
  const from = text(value['from']);
  const until = text(value['until']);
  if (from === undefined || until === undefined || !HH_MM.test(from) || !HH_MM.test(until)) {
    return false;
  }
  const days = new Set<number>();
  for (const day of Array.isArray(value['daysOfWeek']) ? value['daysOfWeek'] : []) {
    if (typeof day === 'number' && Number.isInteger(day) && day >= 1 && day <= 7) {
      days.add(day);
    }
  }
  return days.size > 0 ? { from, until, daysOfWeek: [...days].sort((a, b) => a - b) } : false;
}

function readPopup(
  value: unknown,
  resolveUrl: (url: unknown) => string | null,
): ContentPopup | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = optionalText(value['id']);
  const revisionNo = positiveInt(value['revisionNo']);
  const title = text(value['title']);
  const cap = readCap(value['cap']);
  const daily = readDaily(value['daily']);
  if (
    id === undefined ||
    revisionNo === undefined ||
    title === undefined ||
    cap === null ||
    daily === false
  ) {
    return null;
  }
  return {
    id,
    revisionNo,
    title,
    body: optionalText(value['body']),
    ctaLabel: optionalText(value['ctaLabel']),
    textLocale: text(value['textLocale']) ?? '',
    media: parseContentMedia(value['media'], resolveUrl) ?? undefined,
    link: readLink(value['link']),
    showAfterSeconds: clampedSeconds(value['showAfterSeconds'], 3, 60, 3),
    daily: daily ?? undefined,
    cap,
  };
}
