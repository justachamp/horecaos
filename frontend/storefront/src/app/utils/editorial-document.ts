import type { ContentImageMedia } from '../types/content.types';
import type {
  EditorialBlock,
  EditorialDetail,
  EditorialFeatures,
  EditorialImage,
  EditorialInline,
  EditorialListItem,
  EditorialPage,
  EmploymentType,
  Vacancy,
  VacancyApply,
  VacancyApplyType,
  VacancyBranch,
} from '../types/editorial.types';
import { normalizeContentMedia } from './content-document';

/**
 * Turns the editorial reads (ADR 0157, Decisions 6 and 7) into the shapes the screens draw, or
 * into nothing.
 *
 * Same contract as `normalizeHomeDocument`: nothing here throws, a block, inline or field this
 * client does not know is skipped rather than failing the page, and a body that is not a document
 * is `null`, which the service reports as "not available". The block types are a closed set the
 * server resolves for us (paragraph, heading, quote, list, image, divider); there is no block
 * that carries markup, and none is invented here.
 */

const EMPLOYMENT_TYPES: readonly EmploymentType[] = [
  'FULL_TIME',
  'PART_TIME',
  'SHIFT',
  'INTERNSHIP',
];
const APPLY_TYPES: readonly VacancyApplyType[] = ['PHONE', 'TELEGRAM', 'URL', 'EMAIL'];

/** A path segment: letters and digits of any script, then those plus `. _ ~ -`. No `/`, `?`, `#`. */
const SLUG = /^[\p{L}\p{N}][\p{L}\p{N}._~-]*$/u;

type Dict = Record<string, unknown>;

function isDict(value: unknown): value is Dict {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonBlank(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function optional<K extends string, V>(key: K, value: V | undefined): { [P in K]?: V } {
  return value === undefined ? {} : ({ [key]: value } as { [P in K]?: V });
}

/** The flags are on only when the server says exactly `true`; a vacancy listing is `vacancies`. */
export function normalizeFeatures(raw: unknown): EditorialFeatures {
  const flags = isDict(raw) ? raw : {};
  return {
    news: flags['news'] === true,
    gallery: flags['gallery'] === true,
    recipes: flags['recipes'] === true,
    vacancies: flags['vacancies'] === true,
  };
}

function readDate(value: unknown): string | undefined {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value)) ? value : undefined;
}

function readSlug(value: unknown): string | undefined {
  return typeof value === 'string' && SLUG.test(value) ? value : undefined;
}

function readEmploymentType(value: unknown): EmploymentType | undefined {
  return EMPLOYMENT_TYPES.find((type) => type === value);
}

export function normalizeEditorialPage(raw: unknown, apiBaseUrl: string): EditorialPage | null {
  if (!isDict(raw)) {
    return null;
  }
  const items: EditorialListItem[] = [];
  for (const entry of list(raw['items'])) {
    if (!isDict(entry)) {
      continue;
    }
    const slug = readSlug(entry['slug']);
    const title = nonBlank(entry['title']);
    const publishedAt = readDate(entry['publishedAt']);
    if (slug === undefined || title === undefined || publishedAt === undefined) {
      continue;
    }
    const cover =
      entry['cover'] === undefined
        ? null
        : normalizeContentMedia(entry['cover'], title, apiBaseUrl);
    items.push({
      slug,
      title,
      ...optional('summary', nonBlank(entry['summary'])),
      textLocale: typeof entry['textLocale'] === 'string' ? entry['textLocale'] : '',
      ...optional('cover', cover ?? undefined),
      publishedAt,
      ...optional('employmentType', readEmploymentType(entry['employmentType'])),
    });
  }
  return { items, ...optional('nextCursor', nonBlank(raw['nextCursor'])) };
}

function readInlines(raw: unknown): EditorialInline[] {
  const inlines: EditorialInline[] = [];
  for (const entry of list(raw)) {
    if (!isDict(entry) || typeof entry['text'] !== 'string') {
      continue;
    }
    inlines.push({
      text: entry['text'],
      ...(entry['bold'] === true ? { bold: true } : {}),
      ...(entry['italic'] === true ? { italic: true } : {}),
      ...optional('href', nonBlank(entry['href'])),
    });
  }
  return inlines;
}

function readBlock(raw: unknown, apiBaseUrl: string): EditorialBlock | null {
  if (!isDict(raw)) {
    return null;
  }
  switch (raw['type']) {
    case 'PARAGRAPH':
    case 'QUOTE': {
      if (!Array.isArray(raw['inlines'])) {
        return null;
      }
      return { type: raw['type'], inlines: readInlines(raw['inlines']) };
    }
    case 'HEADING': {
      if (!Array.isArray(raw['inlines'])) {
        return null;
      }
      return {
        type: 'HEADING',
        level: raw['level'] === 2 ? 2 : 3,
        inlines: readInlines(raw['inlines']),
      };
    }
    case 'LIST': {
      if (!Array.isArray(raw['items'])) {
        return null;
      }
      const items = raw['items']
        .filter((item): item is unknown[] => Array.isArray(item))
        .map(readInlines)
        .filter((inlines) => inlines.length > 0);
      return items.length === 0 ? null : { type: 'LIST', ordered: raw['ordered'] === true, items };
    }
    case 'IMAGE': {
      const media = normalizeContentMedia(raw['media'], '', apiBaseUrl);
      if (media === null || media.kind !== 'IMAGE') {
        return null;
      }
      const caption = readInlines(raw['caption']);
      return {
        type: 'IMAGE',
        media: media as ContentImageMedia,
        ...(caption.length > 0 ? { caption } : {}),
      };
    }
    case 'DIVIDER':
      return { type: 'DIVIDER' };
    default:
      // A block type a later release adds: skipped, the page still draws.
      return null;
  }
}

/** A gallery caption arrives as a string or as inlines; either way it is shown as plain text. */
function readCaption(raw: unknown): string | undefined {
  if (typeof raw === 'string') {
    return nonBlank(raw);
  }
  return nonBlank(
    readInlines(raw)
      .map((inline) => inline.text)
      .join(''),
  );
}

function readImages(raw: unknown, apiBaseUrl: string): EditorialImage[] {
  const images: EditorialImage[] = [];
  for (const entry of list(raw)) {
    if (!isDict(entry)) {
      continue;
    }
    const media = normalizeContentMedia(entry['media'], '', apiBaseUrl);
    if (media !== null) {
      images.push({ media, ...optional('caption', readCaption(entry['caption'])) });
    }
  }
  return images;
}

function readApply(raw: unknown): VacancyApply | undefined {
  if (!isDict(raw)) {
    return undefined;
  }
  const type = APPLY_TYPES.find((t) => t === raw['type']);
  const value = nonBlank(raw['value']);
  return type !== undefined && value !== undefined ? { type, value: value.trim() } : undefined;
}

function readBranch(raw: unknown): VacancyBranch | undefined {
  if (!isDict(raw)) {
    return undefined;
  }
  const name = nonBlank(raw['name']);
  if (name === undefined) {
    return undefined;
  }
  return {
    locationId: typeof raw['locationId'] === 'string' ? raw['locationId'] : '',
    name,
    ...optional('addressLine', nonBlank(raw['addressLine'])),
    ...optional('city', nonBlank(raw['city'])),
  };
}

function readVacancy(raw: unknown): Vacancy | undefined {
  if (!isDict(raw)) {
    return undefined;
  }
  return {
    ...optional('employmentType', readEmploymentType(raw['employmentType'])),
    ...optional('apply', readApply(raw['apply'])),
    ...optional('branch', readBranch(raw['branch'])),
  };
}

export function normalizeEditorialDetail(raw: unknown, apiBaseUrl: string): EditorialDetail | null {
  if (!isDict(raw)) {
    return null;
  }
  const slug = readSlug(raw['slug']);
  const title = nonBlank(raw['title']);
  const publishedAt = readDate(raw['publishedAt']);
  if (slug === undefined || title === undefined || publishedAt === undefined) {
    return null;
  }
  const cover =
    raw['cover'] === undefined ? null : normalizeContentMedia(raw['cover'], title, apiBaseUrl);
  const blocks: EditorialBlock[] = [];
  for (const entry of list(raw['blocks'])) {
    const block = readBlock(entry, apiBaseUrl);
    if (block !== null) {
      blocks.push(block);
    }
  }
  return {
    slug,
    title,
    ...optional('summary', nonBlank(raw['summary'])),
    textLocale: typeof raw['textLocale'] === 'string' ? raw['textLocale'] : '',
    ...optional('cover', cover ?? undefined),
    publishedAt,
    ...optional('bodyLocale', nonBlank(raw['bodyLocale'])),
    blocks,
    images: readImages(raw['images'], apiBaseUrl),
    ...optional('vacancy', readVacancy(raw['vacancy'])),
  };
}
