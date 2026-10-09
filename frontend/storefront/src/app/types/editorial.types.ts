import type { ContentImageMedia, ContentMedia } from './content.types';

/**
 * The editorial tier of ADR 0157 (Decisions 6 and 7): news, gallery albums, recipes and a vacancy
 * listing, each a typed block document the brand wrote, never markup.
 *
 * As with `content.types.ts`, these are the shapes the screens work with after
 * `utils/editorial-document.ts` has been over the wire document: unknown blocks are gone, media
 * URLs are resolved, and nothing here is a string of HTML.
 */

/** The four kinds; each is a path under `.../content/` and a route of this storefront. */
export type EditorialKind = 'news' | 'gallery' | 'recipes' | 'careers';

/**
 * Which kinds are on for this brand (`GET .../content/features`). All false unless the tenant's
 * plan includes the editorial tier AND the brand turned the kind on. The vacancy listing is
 * `vacancies` on the wire and `careers` as a route.
 */
export interface EditorialFeatures {
  readonly news: boolean;
  readonly gallery: boolean;
  readonly recipes: boolean;
  readonly vacancies: boolean;
}

export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'SHIFT' | 'INTERNSHIP';

export interface EditorialListItem {
  readonly slug: string;
  readonly title: string;
  readonly summary?: string;
  readonly textLocale: string;
  readonly cover?: ContentMedia;
  /** ISO-8601 instant. */
  readonly publishedAt: string;
  readonly employmentType?: EmploymentType;
}

/** One page of a cursor-paged list, newest first. */
export interface EditorialPage {
  readonly items: readonly EditorialListItem[];
  readonly nextCursor?: string;
}

/** A run of text with its emphasis. `href` is the server's word, re-checked where it is drawn. */
export interface EditorialInline {
  readonly text: string;
  readonly bold?: boolean;
  readonly italic?: boolean;
  readonly href?: string;
}

/** The read shape of a body block, already resolved by the server. */
export type EditorialBlock =
  | { readonly type: 'PARAGRAPH'; readonly inlines: readonly EditorialInline[] }
  | {
      readonly type: 'HEADING';
      readonly level: 2 | 3;
      readonly inlines: readonly EditorialInline[];
    }
  | { readonly type: 'QUOTE'; readonly inlines: readonly EditorialInline[] }
  | {
      readonly type: 'LIST';
      readonly ordered: boolean;
      readonly items: readonly (readonly EditorialInline[])[];
    }
  | {
      readonly type: 'IMAGE';
      readonly media: ContentImageMedia;
      readonly caption?: readonly EditorialInline[];
    }
  | { readonly type: 'DIVIDER' };

export interface EditorialImage {
  readonly media: ContentMedia;
  readonly caption?: string;
}

export type VacancyApplyType = 'PHONE' | 'TELEGRAM' | 'URL' | 'EMAIL';

/** A vacancy's one outbound contact. The platform stores no candidate data and has no form. */
export interface VacancyApply {
  readonly type: VacancyApplyType;
  readonly value: string;
}

export interface VacancyBranch {
  readonly locationId: string;
  readonly name: string;
  readonly addressLine?: string;
  readonly city?: string;
}

export interface Vacancy {
  /** Absent when the document names one this client does not know. */
  readonly employmentType?: EmploymentType;
  readonly apply?: VacancyApply;
  readonly branch?: VacancyBranch;
}

export interface EditorialDetail {
  readonly slug: string;
  readonly title: string;
  readonly summary?: string;
  readonly textLocale: string;
  readonly cover?: ContentMedia;
  readonly publishedAt: string;
  /** The language the body is in, when it differs from the title's. */
  readonly bodyLocale?: string;
  readonly blocks: readonly EditorialBlock[];
  readonly images: readonly EditorialImage[];
  readonly vacancy?: Vacancy;
}
