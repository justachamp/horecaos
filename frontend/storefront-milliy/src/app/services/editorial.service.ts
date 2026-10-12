import { Injectable, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import {
  NO_EDITORIAL,
  type EditorialDetail,
  type EditorialFeatures,
  type EditorialKind,
  type EditorialPage,
} from '../types/editorial.types';
import { resolveMediaUrl } from '../utils/content-media';
import {
  parseEditorialDetail,
  parseEditorialPage,
  parseFeatures,
} from '../utils/editorial-document';
import { LangService } from './lang.service';

/** The same cache window the server grants (`Cache-Control: public, max-age=60`). */
const CACHE_WINDOW_MS = 60_000;

/** What a read of an editorial page gave: the page, or "not available" -- never an error. */
export type EditorialResult<T> =
  { readonly status: 'ok'; readonly value: T } | { readonly status: 'unavailable' };

/**
 * The editorial tier as the storefront reads it (ADR 0157, Decision 6 and 7; Release 5): which
 * kinds a brand runs, and the listings and entries of news, gallery albums, recipes and vacancies.
 *
 * Every read is anonymous and `silent` (no error toast), and none of them rejects. A kind a brand
 * has not turned on is a 404 -- "off", "never existed" and "unknown brand" are one answer so nothing
 * can be learned by probing -- and the pages show it as a plain "not available" page; so is any
 * other failure to read, because a section that cannot be reached is not an emergency. A failed
 * `features` read means no kind is on, so the home screen shows no link.
 */
@Injectable({ providedIn: 'root' })
export class EditorialService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly lang = inject(LangService);

  private readonly state = signal<EditorialFeatures>(NO_EDITORIAL);

  /** Which kinds the brand runs; every one off until a read says otherwise. */
  readonly features = this.state.asReadonly();

  private featuresAt: number | null = null;
  private featuresInFlight: Promise<void> | null = null;

  /** Makes sure `features` is no older than the cache window. Never rejects. */
  loadFeatures(options: { readonly force?: boolean } = {}): Promise<void> {
    if (!options.force) {
      if (this.featuresInFlight !== null) {
        return this.featuresInFlight;
      }
      if (this.featuresAt !== null && Date.now() - this.featuresAt < CACHE_WINDOW_MS) {
        return Promise.resolve();
      }
    }
    const read: Promise<void> = this.api
      .get<unknown>(`${this.base()}/features`, { anonymous: true, silent: true })
      .then(
        (body) => {
          this.state.set(parseFeatures(body));
          this.featuresAt = Date.now();
        },
        () => {
          this.state.set(NO_EDITORIAL);
          this.featuresAt = null;
        },
      )
      .finally(() => {
        if (this.featuresInFlight === read) {
          this.featuresInFlight = null;
        }
      });
    this.featuresInFlight = read;
    return read;
  }

  /** A page of a listing, newest first; `cursor` is the previous page's `nextCursor`. */
  async list(
    kind: EditorialKind,
    options: { readonly cursor?: string; readonly limit?: number } = {},
  ): Promise<EditorialResult<EditorialPage>> {
    try {
      const body = await this.api.get<unknown>(`${this.base()}/${kind}`, {
        query: {
          channel: this.config.channel,
          locale: this.lang.langId(),
          cursor: options.cursor,
          limit: options.limit,
        },
        anonymous: true,
        silent: true,
      });
      return { status: 'ok', value: parseEditorialPage(body, this.resolveMedia) };
    } catch {
      return { status: 'unavailable' };
    }
  }

  /** One entry by its slug. */
  async detail(kind: EditorialKind, slug: string): Promise<EditorialResult<EditorialDetail>> {
    if (!slug) {
      return { status: 'unavailable' };
    }
    try {
      const body = await this.api.get<unknown>(
        `${this.base()}/${kind}/${encodeURIComponent(slug)}`,
        {
          query: { channel: this.config.channel, locale: this.lang.langId() },
          anonymous: true,
          silent: true,
        },
      );
      return { status: 'ok', value: parseEditorialDetail(body, this.resolveMedia) };
    } catch {
      return { status: 'unavailable' };
    }
  }

  private base(): string {
    const { tenantId, brandId } = this.config;
    return `/storefront/tenants/${tenantId}/brands/${brandId}/content`;
  }

  private readonly resolveMedia = (url: unknown): string | null =>
    resolveMediaUrl(url, this.config.apiBaseUrl);
}
