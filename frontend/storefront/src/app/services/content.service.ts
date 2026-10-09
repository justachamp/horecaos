import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import type { ContentHome } from '../types/content.types';
import { normalizeHomeDocument } from '../utils/content-document';
import { LangService } from './lang.service';

/**
 * How long the platform lets a shared cache replay the document (`Cache-Control: max-age=60`). A
 * page that comes back to the foreground after longer than this asks again; the browser's own HTTP
 * cache then answers from its validator (`ETag`) or the network, so this stays cheap.
 */
export const CONTENT_REVALIDATE_AFTER_MS = 60_000;

const NONE: readonly never[] = [];

/**
 * The home screen's brand-authored content: banners, promo cards, groups, stories, a pop-up
 * (ADR 0157).
 *
 * <h2>A request of its own, and a failure that costs nothing</h2>
 *
 * The document is a single anonymous `GET` that the page issues beside the menu read and never
 * awaits on the menu's behalf. The contract is that its failure renders *no content* and leaves the
 * menu and the rest of the page exactly as they were, so nothing here can reject: a network error,
 * a 404, a 5xx and a body that is not a document all end in {@link home} being `null`, and the
 * request is flagged `quiet` so the error interceptor does not raise a toast over a menu that is
 * fine.
 *
 * <h2>When it reads</h2>
 *
 * Once, when the page starts it ({@link load}); then again only on revalidation: the language
 * changed (the texts are per language), or the page came back to the foreground after more than
 * the 60 s the platform lets the document be cached for. Re-entering home does not read again.
 *
 * A read that is overtaken (the language changed while it was on the wire) is dropped when it
 * lands, so a slow Uzbek answer can never replace the Russian one the visitor is looking at.
 */
@Injectable({ providedIn: 'root' })
export class ContentService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly lang = inject(LangService);
  private readonly doc = inject(DOCUMENT);

  /** The document, or `null` for "no content" (not read yet, or the read failed). */
  readonly home = signal<ContentHome | null>(null, { equal: sameContent });

  readonly carousel = computed(() => this.home()?.carousel ?? NONE);
  readonly promoGrid = computed(() => this.home()?.promoGrid ?? NONE);
  readonly groups = computed(() => this.home()?.groups ?? NONE);
  readonly stories = computed(() => this.home()?.stories ?? NONE);
  readonly popup = computed(() => this.home()?.popup ?? null);

  private started = false;
  private requestedLocale: string | null = null;
  private lastReadAt = 0;
  private generation = 0;
  private inflight: Promise<void> | null = null;

  constructor() {
    effect(() => {
      const locale = this.lang.langId();
      untracked(() => {
        if (this.started && locale !== this.requestedLocale) {
          void this.read(locale);
        }
      });
    });

    const onVisibility = (): void => {
      if (
        this.started &&
        this.doc.visibilityState === 'visible' &&
        Date.now() - this.lastReadAt > CONTENT_REVALIDATE_AFTER_MS
      ) {
        void this.read(this.lang.langId());
      }
    };
    this.doc.addEventListener('visibilitychange', onVisibility);
    inject(DestroyRef).onDestroy(() =>
      this.doc.removeEventListener('visibilitychange', onVisibility),
    );
  }

  /**
   * Starts the session's content: the first call reads, every later one waits for that read (or
   * returns at once when it is done). Never rejects.
   */
  load(): Promise<void> {
    if (this.started) {
      return this.inflight ?? Promise.resolve();
    }
    this.started = true;
    return this.read(this.lang.langId());
  }

  private read(locale: string): Promise<void> {
    const generation = ++this.generation;
    this.requestedLocale = locale;
    const promise = (async (): Promise<void> => {
      let next: ContentHome | null = null;
      try {
        const body = await this.api.get<unknown>(
          `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/content/home`,
          { query: { channel: this.config.channel, locale }, anonymous: true, quiet: true },
        );
        next = normalizeHomeDocument(body, this.config.apiBaseUrl);
      } catch {
        // No content. The menu and the rest of the page do not depend on this read.
        next = null;
      }
      if (generation !== this.generation) {
        return;
      }
      this.lastReadAt = Date.now();
      this.home.set(next);
    })();
    this.inflight = promise;
    void promise.finally(() => {
      if (this.inflight === promise) {
        this.inflight = null;
      }
    });
    return promise;
  }
}

/** A revalidation that brings back the same content must not redraw the page. */
function sameContent(a: ContentHome | null, b: ContentHome | null): boolean {
  return a === b || (a !== null && b !== null && JSON.stringify(a) === JSON.stringify(b));
}
