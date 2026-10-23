import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { NO_CONTENT, type ContentHome } from '../types/content.types';
import { parseContentHome } from '../utils/content-document';
import { resolveMediaUrl } from '../utils/content-media';

/**
 * How long the server lets a read be replayed (`Cache-Control: public, max-age=60`). Past it, a
 * withdrawn banner would be shown for longer than the stated trade-off, so the home screen asks again.
 */
const CACHE_WINDOW_MS = 60_000;

/**
 * What the brand has published for the home screen: banners, promo cards, product groups, stories,
 * a promo grid and an entry pop-up (ADR 0157, Decision 5).
 *
 * <h2>It never takes the home screen down</h2>
 *
 * The read is a request of its own, issued beside the menu's and never awaited by it, and a failed
 * one -- the network, a 404, a 5xx, a body that is not the document -- is "no content": the signals
 * go back to empty, {@link load} still resolves, and nothing is thrown or shown to the customer
 * (the call is marked `silent`, so the error toast does not fire either). The menu and everything
 * else on the page are untouched. A brand with nothing published looks identical to a failed read,
 * which is the point: the home screen then looks as it did before content existed.
 *
 * <h2>When it reads</h2>
 *
 * Once when the home screen first asks, and again when the visitor's language changes, when a read
 * is forced, and when the page becomes visible again after more than the 60-second cache window. A
 * second ask inside the window costs nothing. A failed read is not remembered as fresh, so the next
 * ask retries at once.
 */
@Injectable({ providedIn: 'root' })
export class ContentService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  private readonly state = signal<ContentHome>(NO_CONTENT);

  /** The whole document; {@link NO_CONTENT} until a read succeeds, and again after one fails. */
  readonly home = this.state.asReadonly();
  readonly carousel = computed(() => this.state().carousel);
  readonly groups = computed(() => this.state().groups);
  readonly stories = computed(() => this.state().stories);
  readonly promoGrid = computed(() => this.state().promoGrid);
  readonly popup = computed(() => this.state().popup);
  readonly brandSwitcher = computed(() => this.state().brandSwitcher);
  /** `STANDARD` while there is no document: a failed read never changes how categories are drawn. */
  readonly layout = computed(() => this.state().layout);

  private locale: string | null = null;
  private loadedAt: number | null = null;
  private inFlight: { readonly locale: string; readonly promise: Promise<void> } | null = null;
  /** Bumped by every read, so an answer that arrives after a newer read started is ignored. */
  private generation = 0;
  private watching = false;

  /**
   * Makes sure the content for `locale` is loaded and no older than the cache window. Never
   * rejects.
   */
  load(locale: string, options: { readonly force?: boolean } = {}): Promise<void> {
    this.watchVisibility();
    if (!options.force && this.locale === locale) {
      if (this.inFlight?.locale === locale) {
        return this.inFlight.promise;
      }
      if (this.loadedAt !== null && Date.now() - this.loadedAt < CACHE_WINDOW_MS) {
        return Promise.resolve();
      }
    }
    this.locale = locale;
    return this.read(locale);
  }

  private read(locale: string): Promise<void> {
    const generation = ++this.generation;
    const promise: Promise<void> = this.fetch(locale)
      .then(
        (content) => {
          if (generation === this.generation) {
            this.state.set(content);
            this.loadedAt = Date.now();
          }
        },
        () => {
          if (generation === this.generation) {
            this.state.set(NO_CONTENT);
            this.loadedAt = null;
          }
        },
      )
      .finally(() => {
        if (this.inFlight?.promise === promise) {
          this.inFlight = null;
        }
      });
    this.inFlight = { locale, promise };
    return promise;
  }

  private async fetch(locale: string): Promise<ContentHome> {
    const { tenantId, brandId, channel, apiBaseUrl } = this.config;
    const body = await this.api.get<unknown>(
      `/storefront/tenants/${tenantId}/brands/${brandId}/content/home`,
      { query: { channel, locale }, anonymous: true, silent: true },
    );
    return parseContentHome(body, (url) => resolveMediaUrl(url, apiBaseUrl));
  }

  /** Re-reads when the page is shown again and what it holds has gone stale. */
  private watchVisibility(): void {
    if (this.watching) {
      return;
    }
    this.watching = true;
    const listener = () => {
      if (
        this.document.visibilityState === 'visible' &&
        this.locale !== null &&
        this.inFlight === null &&
        (this.loadedAt === null || Date.now() - this.loadedAt >= CACHE_WINDOW_MS)
      ) {
        void this.read(this.locale);
      }
    };
    this.document.addEventListener('visibilitychange', listener);
    this.destroyRef.onDestroy(() =>
      this.document.removeEventListener('visibilitychange', listener),
    );
  }
}
