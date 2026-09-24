import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { LangService } from './lang.service';

/** Row 10.5's four static pages. Matches `tenancy.domain.channel.ChannelPageSlug` (Java) one to one. */
export type ChannelPageSlug = 'about' | 'contacts' | 'delivery-terms' | 'privacy-offer';

export const CHANNEL_PAGE_SLUGS: readonly ChannelPageSlug[] = [
  'about',
  'contacts',
  'delivery-terms',
  'privacy-offer',
];

export function isChannelPageSlug(value: string): value is ChannelPageSlug {
  return (CHANNEL_PAGE_SLUGS as readonly string[]).includes(value);
}

/** What the storefront renders for one static page. */
export interface ChannelPage {
  readonly slug: string;
  readonly locale: string;
  readonly version: number;
  readonly body: string;
}

/**
 * `StorefrontChannelSetupController.page`, `uz.horecaos.platform.tenancy.web`.
 *
 * Unauthenticated, like {@link TermsService}'s own `current()` — a customer
 * reads "Delivery terms" or "About" before they have an account, often from
 * the footer of a page they have not signed in from at all.
 *
 * **Locale is `ru` / `uz-Latn` / `en`, not this app's own `LangService` id**
 * — the same translation `TermsService`'s own {@link toDocumentLocale}
 * explains, repeated here rather than shared for the same reason that
 * function's own doc gives: this module declares its own copy of the
 * three-tag mapping rather than reaching into `terms.service.ts` for it.
 *
 * Not found is answered two ways by the server and folded into one here: a
 * channel that has never published this page, and a channel that published
 * it but not in the requested language. `page()` throws either way, and the
 * component shows one "not available" state rather than trying to explain
 * the difference to a customer who did not choose the language mismatch.
 */
@Injectable({ providedIn: 'root' })
export class ChannelPagesService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly lang = inject(LangService);

  async page(slug: ChannelPageSlug): Promise<ChannelPage> {
    const response = await this.api.get<ChannelPageResponse>(
      `/storefront/tenants/${this.config.tenantId}/channels/${this.config.channel}/pages/${slug}`,
      { query: { locale: toContentLocale(this.lang.langId()) }, anonymous: true },
    );
    return response;
  }
}

function toContentLocale(langId: string): string {
  switch (langId) {
    case 'uz':
      return 'uz-Latn';
    case 'ru':
      return 'ru';
    case 'en':
      return 'en';
    default:
      return 'en';
  }
}

interface ChannelPageResponse {
  readonly slug: string;
  readonly locale: string;
  readonly version: number;
  readonly body: string;
}
