import type { ContentMedia } from './content.types';

/**
 * The editorial tier (ADR 0157, Decision 6 and 7; Release 5): news, gallery albums, recipes and a
 * vacancy listing, as the storefront reads them. Like `content.types.ts`, these are the shapes the
 * screens use *after* `editorial-document.ts` has read the wire body tolerantly.
 */

/** The four kinds, named as their routes are (`/news`, `/gallery`, `/recipes`, `/careers`). */
export type EditorialKind = 'news' | 'gallery' | 'recipes' | 'careers';

export const EDITORIAL_KINDS: readonly EditorialKind[] = ['news', 'gallery', 'recipes', 'careers'];

export function isEditorialKind(value: unknown): value is EditorialKind {
  return typeof value === 'string' && (EDITORIAL_KINDS as readonly string[]).includes(value);
}

/** Which kinds a brand runs: true only while the plan includes the tier AND the brand turned it on. */
export interface EditorialFeatures {
  readonly news: boolean;
  readonly gallery: boolean;
  readonly recipes: boolean;
  /** The vacancy listing, served under `/careers`. */
  readonly vacancies: boolean;
}

export const NO_EDITORIAL: EditorialFeatures = Object.freeze({
  news: false,
  gallery: false,
  recipes: false,
  vacancies: false,
});

export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'SHIFT' | 'INTERNSHIP';

/** A row of a listing: enough to draw a card. */
export interface EditorialSummary {
  readonly slug: string;
  readonly title: string;
  readonly summary?: string;
  readonly textLocale: string;
  readonly cover?: ContentMedia;
  /** ISO-8601 instant. */
  readonly publishedAt: string;
  readonly employmentType?: EmploymentType;
}

export interface EditorialPage {
  readonly items: readonly EditorialSummary[];
  readonly nextCursor?: string;
}

/** One run of text in a body: plain by default, optionally bold, italic and a link. */
export interface Inline {
  readonly text: string;
  readonly bold?: boolean;
  readonly italic?: boolean;
  /** As the server sent it. Never trusted: `safeLink` decides whether it may be an `href`. */
  readonly href?: string;
}

/** The READ shape of a body block, already resolved by the server. A block of any other type is skipped. */
export type EditorialBlock =
  | { readonly type: 'PARAGRAPH'; readonly inlines: readonly Inline[] }
  | { readonly type: 'HEADING'; readonly level: 2 | 3; readonly inlines: readonly Inline[] }
  | { readonly type: 'QUOTE'; readonly inlines: readonly Inline[] }
  | {
      readonly type: 'LIST';
      readonly ordered: boolean;
      readonly items: readonly (readonly Inline[])[];
    }
  | {
      readonly type: 'IMAGE';
      readonly media: ContentMedia;
      readonly caption?: readonly Inline[];
    }
  | { readonly type: 'DIVIDER' };

export interface AlbumImage {
  readonly media: ContentMedia;
  readonly caption?: string;
}

export type ApplyType = 'PHONE' | 'TELEGRAM' | 'URL' | 'EMAIL';

/** The one outbound contact a vacancy publishes. There is no application form and no applicant data. */
export interface VacancyApply {
  readonly type: ApplyType;
  readonly value: string;
}

export interface VacancyBranch {
  readonly locationId: string;
  readonly name: string;
  readonly addressLine?: string;
  readonly city?: string;
}

export interface Vacancy {
  readonly employmentType: EmploymentType;
  readonly apply?: VacancyApply;
  readonly branch?: VacancyBranch;
}

/** One entry in full. Only the fields that belong to its kind are set. */
export interface EditorialDetail {
  readonly slug: string;
  readonly kind: string;
  readonly title: string;
  readonly summary?: string;
  readonly textLocale: string;
  readonly cover?: ContentMedia;
  readonly publishedAt: string;
  /** The language the body is in, when it differs from the title's. */
  readonly bodyLocale?: string;
  readonly blocks: readonly EditorialBlock[];
  readonly images: readonly AlbumImage[];
  readonly vacancy?: Vacancy;
}
