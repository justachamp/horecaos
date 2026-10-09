import type { ContentMedia } from '../types/content.types';
import {
  NO_EDITORIAL,
  type AlbumImage,
  type ApplyType,
  type EditorialBlock,
  type EditorialDetail,
  type EditorialFeatures,
  type EditorialPage,
  type EditorialSummary,
  type EmploymentType,
  type Inline,
  type Vacancy,
  type VacancyApply,
  type VacancyBranch,
} from '../types/editorial.types';
import { parseContentMedia } from './content-document';
import { isRecord, optionalText, text } from './wire';

type ResolveUrl = (url: unknown) => string | null;

/**
 * Reads the editorial reads (ADR 0157, Decision 6 and 7; Release 5) tolerantly, as
 * {@link parseContentHome} reads the home document: an unknown field is never copied, a block of a
 * type it does not know is skipped, an entry it cannot draw is left out, and every media URL goes
 * through `resolveUrl`. Text is copied verbatim and is only ever rendered by interpolation; an
 * `href` is copied as sent and checked again where it is used (`safeLink`).
 */

/** Which kinds are on. Anything that is not exactly `true` is off, and a body that is no object is all off. */
export function parseFeatures(raw: unknown): EditorialFeatures {
  if (!isRecord(raw)) {
    return { ...NO_EDITORIAL };
  }
  return {
    news: raw['news'] === true,
    gallery: raw['gallery'] === true,
    recipes: raw['recipes'] === true,
    vacancies: raw['vacancies'] === true,
  };
}

const EMPLOYMENT_TYPES: ReadonlySet<string> = new Set<EmploymentType>([
  'FULL_TIME',
  'PART_TIME',
  'SHIFT',
  'INTERNSHIP',
]);

const APPLY_TYPES: ReadonlySet<string> = new Set<ApplyType>(['PHONE', 'TELEGRAM', 'URL', 'EMAIL']);

function image(raw: unknown, resolveUrl: ResolveUrl): ContentMedia | undefined {
  const media = parseContentMedia(raw, resolveUrl);
  // A body or an album never plays a video (ADR 0157, Decision 6).
  return media !== null && media.kind === 'IMAGE' ? media : undefined;
}

function summaryOf(raw: unknown, resolveUrl: ResolveUrl): EditorialSummary | null {
  if (!isRecord(raw)) {
    return null;
  }
  const slug = optionalText(raw['slug']);
  const title = text(raw['title']);
  const publishedAt = optionalText(raw['publishedAt']);
  if (slug === undefined || title === undefined || publishedAt === undefined) {
    return null;
  }
  const employmentType = text(raw['employmentType']);
  return {
    slug,
    title,
    summary: optionalText(raw['summary']),
    textLocale: text(raw['textLocale']) ?? '',
    cover: image(raw['cover'], resolveUrl),
    publishedAt,
    employmentType:
      employmentType !== undefined && EMPLOYMENT_TYPES.has(employmentType)
        ? (employmentType as EmploymentType)
        : undefined,
  };
}

/** A page of a listing. Refuses a body that is not an object; the service turns that into "unavailable". */
export function parseEditorialPage(raw: unknown, resolveUrl: ResolveUrl): EditorialPage {
  if (!isRecord(raw)) {
    throw new Error('The editorial listing is not an object.');
  }
  const items: EditorialSummary[] = [];
  for (const entry of Array.isArray(raw['items']) ? raw['items'] : []) {
    const read = summaryOf(entry, resolveUrl);
    if (read !== null) {
      items.push(read);
    }
  }
  return { items, nextCursor: optionalText(raw['nextCursor']) };
}

function inlines(raw: unknown): Inline[] {
  const runs: Inline[] = [];
  for (const entry of Array.isArray(raw) ? raw : []) {
    if (!isRecord(entry) || typeof entry['text'] !== 'string') {
      continue;
    }
    const href = optionalText(entry['href']);
    runs.push({
      text: entry['text'],
      ...(entry['bold'] === true ? { bold: true } : {}),
      ...(entry['italic'] === true ? { italic: true } : {}),
      ...(href !== undefined ? { href } : {}),
    });
  }
  return runs;
}

function block(raw: unknown, resolveUrl: ResolveUrl): EditorialBlock | null {
  if (!isRecord(raw)) {
    return null;
  }
  switch (raw['type']) {
    case 'PARAGRAPH': {
      const runs = inlines(raw['inlines']);
      return runs.length > 0 ? { type: 'PARAGRAPH', inlines: runs } : null;
    }
    case 'HEADING': {
      const runs = inlines(raw['inlines']);
      return runs.length > 0
        ? { type: 'HEADING', level: raw['level'] === 3 ? 3 : 2, inlines: runs }
        : null;
    }
    case 'QUOTE': {
      const runs = inlines(raw['inlines']);
      return runs.length > 0 ? { type: 'QUOTE', inlines: runs } : null;
    }
    case 'LIST': {
      const items: Inline[][] = [];
      for (const item of Array.isArray(raw['items']) ? raw['items'] : []) {
        const runs = Array.isArray(item) ? inlines(item) : [];
        if (runs.length > 0) {
          items.push(runs);
        }
      }
      return items.length > 0 ? { type: 'LIST', ordered: raw['ordered'] === true, items } : null;
    }
    case 'IMAGE': {
      const media = image(raw['media'], resolveUrl);
      if (media === undefined) {
        return null;
      }
      const caption = inlines(raw['caption']);
      return caption.length > 0 ? { type: 'IMAGE', media, caption } : { type: 'IMAGE', media };
    }
    case 'DIVIDER':
      return { type: 'DIVIDER' };
    default:
      // A block type this reader does not know is skipped (ADR 0157).
      return null;
  }
}

function albumImage(raw: unknown, resolveUrl: ResolveUrl): AlbumImage | null {
  if (!isRecord(raw)) {
    return null;
  }
  const media = image(raw['media'], resolveUrl);
  if (media === undefined) {
    return null;
  }
  const caption = optionalText(raw['caption']);
  return caption !== undefined ? { media, caption } : { media };
}

function apply(raw: unknown): VacancyApply | undefined {
  if (!isRecord(raw)) {
    return undefined;
  }
  const type = text(raw['type']);
  const value = optionalText(raw['value']);
  return type !== undefined && APPLY_TYPES.has(type) && value !== undefined
    ? { type: type as ApplyType, value }
    : undefined;
}

function branch(raw: unknown): VacancyBranch | undefined {
  if (!isRecord(raw)) {
    return undefined;
  }
  const locationId = optionalText(raw['locationId']);
  const name = optionalText(raw['name']);
  if (locationId === undefined || name === undefined) {
    return undefined;
  }
  const addressLine = optionalText(raw['addressLine']);
  const city = optionalText(raw['city']);
  return {
    locationId,
    name,
    ...(addressLine !== undefined ? { addressLine } : {}),
    ...(city !== undefined ? { city } : {}),
  };
}

function vacancy(raw: unknown): Vacancy | undefined {
  if (!isRecord(raw)) {
    return undefined;
  }
  const employmentType = text(raw['employmentType']);
  const contact = apply(raw['apply']);
  const place = branch(raw['branch']);
  return {
    employmentType:
      employmentType !== undefined && EMPLOYMENT_TYPES.has(employmentType)
        ? (employmentType as EmploymentType)
        : 'FULL_TIME',
    ...(contact !== undefined ? { apply: contact } : {}),
    ...(place !== undefined ? { branch: place } : {}),
  };
}

/** One entry in full. Refuses a body that is not an entry; the service turns that into "unavailable". */
export function parseEditorialDetail(raw: unknown, resolveUrl: ResolveUrl): EditorialDetail {
  if (!isRecord(raw)) {
    throw new Error('The editorial entry is not an object.');
  }
  const slug = optionalText(raw['slug']);
  const title = text(raw['title']);
  const publishedAt = optionalText(raw['publishedAt']);
  if (slug === undefined || title === undefined || publishedAt === undefined) {
    throw new Error('The editorial entry has no slug, title or date.');
  }
  const blocks: EditorialBlock[] = [];
  for (const entry of Array.isArray(raw['blocks']) ? raw['blocks'] : []) {
    const read = block(entry, resolveUrl);
    if (read !== null) {
      blocks.push(read);
    }
  }
  const images: AlbumImage[] = [];
  for (const entry of Array.isArray(raw['images']) ? raw['images'] : []) {
    const read = albumImage(entry, resolveUrl);
    if (read !== null) {
      images.push(read);
    }
  }
  return {
    slug,
    kind: text(raw['kind']) ?? '',
    title,
    summary: optionalText(raw['summary']),
    textLocale: text(raw['textLocale']) ?? '',
    cover: image(raw['cover'], resolveUrl),
    publishedAt,
    bodyLocale: optionalText(raw['bodyLocale']),
    blocks,
    images,
    vacancy: vacancy(raw['vacancy']),
  };
}
