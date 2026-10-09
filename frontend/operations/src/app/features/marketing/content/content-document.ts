import { BlockDocument, readBlockDocument } from '../../../shared/ui/block-document';
import { ContentDocument, ContentKind } from './content-api';

/**
 * The console edits a content document **in its wire shape** (ADR 0157, "The typed documents"):
 * `texts{locale: {...}}`, `media{...}`, `link{...}` and the rest of a kind's grammar, as the server
 * validates and stores them. These are the pure, immutable accessors the editors read and change it
 * with -- no second model to convert to and from, so what an author sees is what is sent, and the
 * grammar lives in one place (the server's). Everything here is tolerant of a document that lacks a
 * key (a draft the author has only started) and never invents content.
 */

export type LinkType = 'NONE' | 'PRODUCT' | 'CATEGORY' | 'CART' | 'PROMOTION' | 'EXTERNAL';

export const LINK_TYPES: readonly LinkType[] = [
  'NONE',
  'PRODUCT',
  'CATEGORY',
  'CART',
  'PROMOTION',
  'EXTERNAL',
];

/** Whether a link type names an id (a product, a category or a promotion). */
export function linkNeedsTarget(type: LinkType): boolean {
  return type === 'PRODUCT' || type === 'CATEGORY' || type === 'PROMOTION';
}

export interface LinkValue {
  readonly type: LinkType;
  readonly targetId: string | null;
  readonly url: string;
}

export const NO_LINK: LinkValue = { type: 'NONE', targetId: null, url: '' };

export type GroupMode = 'MANUAL' | 'BY_CATEGORY';
export type GroupOrder = 'MENU_ORDER' | 'NEWEST';

export interface GroupValue {
  readonly mode: GroupMode;
  readonly members: readonly string[];
  readonly categoryId: string | null;
  readonly order: GroupOrder;
  readonly limit: number;
}

export type CapMode = 'ONCE_EVER' | 'ONCE_PER_DAY' | 'EVERY_N_DAYS';

export interface PopupSettings {
  readonly showAfterSeconds: number;
  readonly daily: {
    readonly from: string;
    readonly until: string;
    readonly daysOfWeek: readonly number[];
  } | null;
  readonly capMode: CapMode;
  readonly capN: number;
}

export interface SlideValue {
  readonly kind: 'IMAGE' | 'VIDEO';
  readonly assetId: string | null;
  readonly posterAssetId: string | null;
  readonly durationSeconds: number;
  readonly captions: Readonly<Record<string, string>>;
  readonly link: LinkValue;
}

export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'SHIFT' | 'INTERNSHIP';
export type ApplyType = 'PHONE' | 'TELEGRAM' | 'URL' | 'EMAIL';

export interface VacancyValue {
  readonly employmentType: EmploymentType;
  readonly applyType: ApplyType;
  readonly applyValue: string;
}

export interface AlbumImageValue {
  readonly assetId: string;
  readonly captions: Readonly<Record<string, string>>;
}

type Bag = Readonly<Record<string, unknown>>;

function bag(value: unknown): Bag {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as Bag) : {};
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function idOrNull(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

function int(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : fallback;
}

function set(doc: ContentDocument, key: string, value: unknown): ContentDocument {
  return { ...doc, [key]: value };
}

/** A new, empty document of a kind, with the stated defaults (the server fills the same ones). */
export function emptyDocument(kind: ContentKind): ContentDocument {
  const base = { schemaVersion: 1, texts: {} };
  switch (kind) {
    case 'BANNER':
    case 'PROMO_CARD':
      return { ...base, media: {}, link: { type: 'NONE' } };
    case 'PRODUCT_GROUP':
      return { ...base, mode: 'MANUAL', members: [] };
    case 'STORY_GROUP':
      return { ...base, slides: [] };
    case 'POPUP':
      return {
        ...base,
        media: {},
        link: { type: 'NONE' },
        showAfterSeconds: 3,
        cap: { mode: 'ONCE_PER_DAY', n: 1 },
        dismiss: 'DISMISSIBLE',
      };
    case 'NEWS':
    case 'RECIPE':
      return { ...base, body: {} };
    case 'GALLERY_ALBUM':
      return { ...base, albumImages: [] };
    case 'VACANCY':
      return {
        ...base,
        body: {},
        vacancy: { employmentType: 'FULL_TIME', apply: { type: 'PHONE' } },
      };
  }
}

// ------------------------------------------------------------------------------------- texts

export function textOf(doc: ContentDocument, locale: string, field: string): string {
  return str(bag(bag(doc['texts'])[locale])[field]);
}

/** Sets one text field of one language; an entry left with nothing in it is removed, not kept empty. */
export function withText(
  doc: ContentDocument,
  locale: string,
  field: string,
  value: string,
): ContentDocument {
  const texts = bag(doc['texts']);
  const entry: Record<string, unknown> = { ...bag(texts[locale]) };
  if (value === '') {
    delete entry[field];
  } else {
    entry[field] = value;
  }
  const next: Record<string, unknown> = { ...texts };
  if (Object.keys(entry).length === 0) {
    delete next[locale];
  } else {
    next[locale] = entry;
  }
  return set(doc, 'texts', next);
}

/** The languages the document has any text in. */
export function textLocales(doc: ContentDocument): readonly string[] {
  return Object.keys(bag(doc['texts']));
}

/** A language is complete when its title is there: the one field every kind requires. */
export function isLocaleComplete(doc: ContentDocument, locale: string): boolean {
  return textOf(doc, locale, 'title').trim() !== '';
}

/**
 * Languages where something is typed but the title is not. The server refuses a text with no title
 * (400 on save), so the editor holds the save back and names the language instead of losing what was
 * typed.
 */
export function localesMissingTitle(doc: ContentDocument): readonly string[] {
  return textLocales(doc).filter((locale) => !isLocaleComplete(doc, locale));
}

// ------------------------------------------------------------------------------------- media

export function primaryAssetId(doc: ContentDocument): string | null {
  return idOrNull(bag(doc['media'])['primaryAssetId']);
}

export function posterAssetId(doc: ContentDocument): string | null {
  return idOrNull(bag(doc['media'])['posterAssetId']);
}

/** Sets (or clears) the primary asset and its poster. A poster belongs to a video: clearing the asset clears it. */
export function withPrimaryAsset(
  doc: ContentDocument,
  assetId: string | null,
  posterId: string | null = null,
): ContentDocument {
  const media: Record<string, unknown> = {};
  if (assetId !== null) {
    media['primaryAssetId'] = assetId;
    if (posterId !== null) {
      media['posterAssetId'] = posterId;
    }
  }
  return set(doc, 'media', media);
}

/** A language's own image, for a creative whose picture carries its own words. */
export function localeImageId(doc: ContentDocument, locale: string): string | null {
  return idOrNull(bag(bag(doc['texts'])[locale])['mediaAssetId']);
}

export function withLocaleImage(
  doc: ContentDocument,
  locale: string,
  assetId: string | null,
): ContentDocument {
  const texts = bag(doc['texts']);
  const entry: Record<string, unknown> = { ...bag(texts[locale]) };
  if (assetId === null) {
    delete entry['mediaAssetId'];
  } else {
    entry['mediaAssetId'] = assetId;
  }
  return set(doc, 'texts', { ...texts, [locale]: entry });
}

export function coverAssetId(doc: ContentDocument): string | null {
  return idOrNull(doc['coverAssetId']);
}

export function withCoverAsset(doc: ContentDocument, assetId: string | null): ContentDocument {
  const next: Record<string, unknown> = { ...doc };
  if (assetId === null) {
    delete next['coverAssetId'];
  } else {
    next['coverAssetId'] = assetId;
  }
  return next;
}

export function avatarAssetId(doc: ContentDocument): string | null {
  return idOrNull(doc['avatarAssetId']);
}

export function withAvatarAsset(doc: ContentDocument, assetId: string | null): ContentDocument {
  const next: Record<string, unknown> = { ...doc };
  if (assetId === null) {
    delete next['avatarAssetId'];
  } else {
    next['avatarAssetId'] = assetId;
  }
  return next;
}

// -------------------------------------------------------------------------------------- link

export function linkFrom(raw: unknown): LinkValue {
  const source = bag(raw);
  const type = LINK_TYPES.find((candidate) => candidate === source['type']) ?? 'NONE';
  return { type, targetId: idOrNull(source['targetId']), url: str(source['url']) };
}

export function linkOf(doc: ContentDocument): LinkValue {
  return linkFrom(doc['link']);
}

/** The wire form of a link: only the keys its type uses, so a stale target never rides along. */
export function linkToWire(link: LinkValue): Record<string, unknown> {
  const out: Record<string, unknown> = { type: link.type };
  if (linkNeedsTarget(link.type) && link.targetId !== null) {
    out['targetId'] = link.targetId;
  }
  if (link.type === 'EXTERNAL' && link.url.trim() !== '') {
    out['url'] = link.url.trim();
  }
  return out;
}

export function withLink(doc: ContentDocument, link: LinkValue): ContentDocument {
  return set(doc, 'link', linkToWire(link));
}

// ------------------------------------------------------------------------------------ groups

export function groupOf(doc: ContentDocument): GroupValue {
  const members = Array.isArray(doc['members']) ? doc['members'] : [];
  return {
    mode: doc['mode'] === 'BY_CATEGORY' ? 'BY_CATEGORY' : 'MANUAL',
    members: members
      .map((member) => idOrNull(bag(member)['id']))
      .filter((id): id is string => id !== null),
    categoryId: idOrNull(doc['categoryId']),
    order: doc['order'] === 'NEWEST' ? 'NEWEST' : 'MENU_ORDER',
    limit: Math.min(40, Math.max(1, int(doc['limit'], 8))),
  };
}

/** The wire form of a group: a manual group names members only, a by-category group its category, order and limit only. */
export function withGroup(doc: ContentDocument, group: GroupValue): ContentDocument {
  const next: Record<string, unknown> = { ...doc, mode: group.mode };
  delete next['members'];
  delete next['categoryId'];
  delete next['order'];
  delete next['limit'];
  if (group.mode === 'MANUAL') {
    next['members'] = [...new Set(group.members)].map((id) => ({ type: 'PRODUCT', id }));
  } else {
    next['members'] = [];
    if (group.categoryId !== null) {
      next['categoryId'] = group.categoryId;
    }
    next['order'] = group.order;
    next['limit'] = group.limit;
  }
  return next;
}

// ------------------------------------------------------------------------------------ popups

export function popupOf(doc: ContentDocument): PopupSettings {
  const daily = doc['daily'] === null || doc['daily'] === undefined ? null : bag(doc['daily']);
  const cap = bag(doc['cap']);
  const days = daily !== null && Array.isArray(daily['daysOfWeek']) ? daily['daysOfWeek'] : [];
  return {
    showAfterSeconds: Math.min(60, Math.max(3, int(doc['showAfterSeconds'], 3))),
    daily:
      daily === null
        ? null
        : {
            from: str(daily['from']),
            until: str(daily['until']),
            daysOfWeek: days.filter((d): d is number => typeof d === 'number'),
          },
    capMode:
      cap['mode'] === 'ONCE_EVER' || cap['mode'] === 'EVERY_N_DAYS' ? cap['mode'] : 'ONCE_PER_DAY',
    capN: Math.min(30, Math.max(1, int(cap['n'], 1))),
  };
}

export function withPopup(doc: ContentDocument, popup: PopupSettings): ContentDocument {
  const next: Record<string, unknown> = {
    ...doc,
    showAfterSeconds: popup.showAfterSeconds,
    cap: { mode: popup.capMode, n: popup.capMode === 'EVERY_N_DAYS' ? popup.capN : 1 },
    dismiss: 'DISMISSIBLE',
  };
  if (popup.daily === null) {
    delete next['daily'];
  } else {
    next['daily'] = {
      from: popup.daily.from,
      until: popup.daily.until,
      daysOfWeek: [...popup.daily.daysOfWeek],
    };
  }
  return next;
}

// ------------------------------------------------------------------------------------ stories

export function slidesOf(doc: ContentDocument): readonly SlideValue[] {
  const slides = Array.isArray(doc['slides']) ? doc['slides'] : [];
  return slides.map((raw) => {
    const slide = bag(raw);
    const media = bag(slide['media']);
    const captions: Record<string, string> = {};
    for (const [locale, entry] of Object.entries(bag(slide['texts']))) {
      const caption = str(bag(entry)['caption']);
      if (caption !== '') {
        captions[locale] = caption;
      }
    }
    return {
      kind: media['kind'] === 'VIDEO' ? 'VIDEO' : 'IMAGE',
      assetId: idOrNull(media['assetId']),
      posterAssetId: idOrNull(media['posterAssetId']),
      durationSeconds: Math.min(15, Math.max(3, int(slide['durationSeconds'], 5))),
      captions,
      link: linkFrom(slide['link']),
    };
  });
}

export function withSlides(doc: ContentDocument, slides: readonly SlideValue[]): ContentDocument {
  return set(
    doc,
    'slides',
    slides.map((slide, index) => {
      const media: Record<string, unknown> = { kind: slide.kind };
      if (slide.assetId !== null) {
        media['assetId'] = slide.assetId;
      }
      if (slide.kind === 'VIDEO' && slide.posterAssetId !== null) {
        media['posterAssetId'] = slide.posterAssetId;
      }
      const texts: Record<string, unknown> = {};
      for (const [locale, caption] of Object.entries(slide.captions)) {
        if (caption !== '') {
          texts[locale] = { caption };
        }
      }
      return {
        position: index + 1,
        media,
        durationSeconds: slide.durationSeconds,
        texts,
        link: linkToWire(slide.link),
      };
    }),
  );
}

export const NEW_SLIDE: SlideValue = {
  kind: 'IMAGE',
  assetId: null,
  posterAssetId: null,
  durationSeconds: 5,
  captions: {},
  link: NO_LINK,
};

// ----------------------------------------------------------------------------- editorial

/** One language's body, read tolerantly; an empty document when there is none. */
export function bodyOf(doc: ContentDocument, locale: string): BlockDocument {
  return readBlockDocument(bag(doc['body'])[locale]);
}

export function withBody(
  doc: ContentDocument,
  locale: string,
  body: BlockDocument,
): ContentDocument {
  const bodies: Record<string, unknown> = { ...bag(doc['body']) };
  if (body.blocks.length === 0) {
    delete bodies[locale];
  } else {
    bodies[locale] = body;
  }
  return set(doc, 'body', bodies);
}

export function vacancyOf(doc: ContentDocument): VacancyValue {
  const vacancy = bag(doc['vacancy']);
  const apply = bag(vacancy['apply']);
  const employment = vacancy['employmentType'];
  const applyType = apply['type'];
  return {
    employmentType:
      employment === 'PART_TIME' || employment === 'SHIFT' || employment === 'INTERNSHIP'
        ? employment
        : 'FULL_TIME',
    applyType:
      applyType === 'TELEGRAM' || applyType === 'URL' || applyType === 'EMAIL'
        ? applyType
        : 'PHONE',
    applyValue: str(apply['value']),
  };
}

export function withVacancy(doc: ContentDocument, vacancy: VacancyValue): ContentDocument {
  const apply: Record<string, unknown> = { type: vacancy.applyType };
  if (vacancy.applyValue.trim() !== '') {
    apply['value'] = vacancy.applyValue.trim();
  }
  return set(doc, 'vacancy', { employmentType: vacancy.employmentType, apply });
}

export function albumImagesOf(doc: ContentDocument): readonly AlbumImageValue[] {
  const images = Array.isArray(doc['albumImages']) ? doc['albumImages'] : [];
  return images
    .map((raw) => {
      const image = bag(raw);
      const captions: Record<string, string> = {};
      for (const [locale, caption] of Object.entries(bag(image['captions']))) {
        if (typeof caption === 'string' && caption !== '') {
          captions[locale] = caption;
        }
      }
      return { assetId: str(image['assetId']), captions };
    })
    .filter((image) => image.assetId !== '');
}

export function withAlbumImages(
  doc: ContentDocument,
  images: readonly AlbumImageValue[],
): ContentDocument {
  return set(
    doc,
    'albumImages',
    images.map((image) => ({ assetId: image.assetId, captions: { ...image.captions } })),
  );
}

// ------------------------------------------------------------------------------ plain text

/** Every text of a document as `path -> value`, for the diff between two revisions. */
export function flatten(doc: unknown, prefix = ''): Readonly<Record<string, unknown>> {
  const out: Record<string, unknown> = {};
  const walk = (value: unknown, path: string): void => {
    if (Array.isArray(value)) {
      if (value.length === 0 && path !== '') {
        out[path] = [];
      }
      value.forEach((entry, index) => walk(entry, `${path}[${index}]`));
    } else if (value !== null && typeof value === 'object') {
      const entries = Object.entries(value as Bag);
      if (entries.length === 0 && path !== '') {
        out[path] = {};
      }
      for (const [key, entry] of entries) {
        walk(entry, path === '' ? key : `${path}.${key}`);
      }
    } else {
      out[path] = value;
    }
  };
  walk(doc, prefix);
  return out;
}

/**
 * What changed between two documents, in the `{field: {before, after}}` shape `q-diff-viewer`
 * renders. A path present on one side only is a change from or to nothing (`null`).
 */
export function diffDocuments(
  before: ContentDocument | null,
  after: ContentDocument | null,
): Readonly<Record<string, { readonly before: unknown; readonly after: unknown }>> {
  const a = flatten(before ?? {});
  const b = flatten(after ?? {});
  const out: Record<string, { before: unknown; after: unknown }> = {};
  for (const path of new Set([...Object.keys(a), ...Object.keys(b)])) {
    const left = a[path] ?? null;
    const right = b[path] ?? null;
    if (JSON.stringify(left) !== JSON.stringify(right)) {
      out[path] = { before: left, after: right };
    }
  }
  return out;
}

/**
 * The document as it is sent: a language whose every field is blank is dropped, a blank optional text
 * is omitted rather than sent as an empty string, and nothing else is touched. (An empty required
 * title is not "fixed" here; {@link localesMissingTitle} keeps that from being saved at all.)
 */
export function documentForSave(doc: ContentDocument): ContentDocument {
  const texts: Record<string, unknown> = {};
  for (const [locale, entry] of Object.entries(bag(doc['texts']))) {
    const kept: Record<string, unknown> = {};
    for (const [field, value] of Object.entries(bag(entry))) {
      if (typeof value === 'string' ? value.trim() !== '' : value !== null && value !== undefined) {
        kept[field] = typeof value === 'string' ? value.trim() : value;
      }
    }
    if (Object.keys(kept).length > 0) {
      texts[locale] = kept;
    }
  }
  return { ...doc, texts };
}
