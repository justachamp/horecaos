import { Injectable, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import type {
  EditorialDetail,
  EditorialFeatures,
  EditorialKind,
  EditorialPage,
} from '../types/editorial.types';
import {
  normalizeEditorialDetail,
  normalizeEditorialPage,
  normalizeFeatures,
} from '../utils/editorial-document';
import { CONTENT_REVALIDATE_AFTER_MS } from './content.service';
import { LangService } from './lang.service';

/** What an editorial read gave: the document, or "not available" for every way it can fail. */
export type Loaded<T> =
  { readonly status: 'ok'; readonly data: T } | { readonly status: 'unavailable' };

const NO_FEATURES: EditorialFeatures = {
  news: false,
  gallery: false,
  recipes: false,
  vacancies: false,
};

/**
 * The editorial tier's reads (ADR 0157, Decisions 6 and 7; Release 5): which kinds are on, and the
 * lists and documents of news, gallery albums, recipes and vacancies.
 *
 * Every read is anonymous, public and quiet, and none of them throws. A kind that is off (the
 * server answers 404), a document that is gone, a server error and a body that is not a document
 * are all the same thing to a visitor: "this page is not available", never an error screen and
 * never a toast. {@link features} is the exception that proves it: when its read fails the
 * entry points are simply not drawn.
 */
@Injectable({ providedIn: 'root' })
export class EditorialService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly lang = inject(LangService);

  /** Which kinds are on; everything off until the read says otherwise. */
  readonly features = signal<EditorialFeatures>(NO_FEATURES);

  private lastFeaturesAt = 0;
  private featuresInflight: Promise<void> | null = null;

  /**
   * Reads the flags: once when the home page starts, and again only when the last read is older
   * than the 60 s the platform lets the answer be cached for. Never rejects.
   */
  loadFeatures(): Promise<void> {
    if (this.featuresInflight) {
      return this.featuresInflight;
    }
    if (
      this.lastFeaturesAt !== 0 &&
      Date.now() - this.lastFeaturesAt <= CONTENT_REVALIDATE_AFTER_MS
    ) {
      return Promise.resolve();
    }
    const promise = (async (): Promise<void> => {
      let next = NO_FEATURES;
      try {
        next = normalizeFeatures(
          await this.api.get<unknown>(`${this.base()}/features`, this.options()),
        );
      } catch {
        // No flags means no entry points; the home page is otherwise unchanged.
      }
      this.lastFeaturesAt = Date.now();
      this.features.set(next);
    })();
    this.featuresInflight = promise;
    void promise.finally(() => (this.featuresInflight = null));
    return promise;
  }

  async list(
    kind: EditorialKind,
    page: { cursor?: string; limit?: number } = {},
  ): Promise<Loaded<EditorialPage>> {
    try {
      const body = await this.api.get<unknown>(
        `${this.base()}/${kind}`,
        this.options({ cursor: page.cursor, limit: page.limit }),
      );
      const data = normalizeEditorialPage(body, this.config.apiBaseUrl);
      return data === null ? { status: 'unavailable' } : { status: 'ok', data };
    } catch {
      return { status: 'unavailable' };
    }
  }

  async detail(kind: EditorialKind, slug: string): Promise<Loaded<EditorialDetail>> {
    try {
      const body = await this.api.get<unknown>(
        `${this.base()}/${kind}/${encodeURIComponent(slug)}`,
        this.options(),
      );
      const data = normalizeEditorialDetail(body, this.config.apiBaseUrl);
      return data === null ? { status: 'unavailable' } : { status: 'ok', data };
    } catch {
      return { status: 'unavailable' };
    }
  }

  private base(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/content`;
  }

  private options(extra: Record<string, string | number | undefined> = {}) {
    return {
      query: { channel: this.config.channel, locale: this.lang.langId(), ...extra },
      anonymous: true,
      quiet: true,
    };
  }
}
