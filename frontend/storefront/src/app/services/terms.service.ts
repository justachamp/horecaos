import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { Session } from '../core/auth/session';
import { LangService } from './lang.service';

/** What the storefront actually renders for terms of service (ADR 0067). */
export interface TermsDocument {
  readonly locale: string;
  readonly isPlatformDefault: boolean;
  readonly version: number | null;
  readonly body: string;
}

export interface AcceptanceStatus {
  readonly accepted: boolean;
  readonly currentVersion: string;
  readonly lastAcceptedVersion: string | null;
  readonly lastAcceptedAt: string | null;
}

/**
 * `StorefrontTermsController`, `uz.horecaos.platform.legal.web`.
 *
 * The tenant's own published terms, or the platform's brand-neutral default
 * when the tenant has not written one — replacing the hardcoded, legacy-brand
 * text `pages/terms/content/*.ts` used to serve to every tenant regardless of
 * who they actually were.
 *
 * `current()` is anonymous, like the FAQ (`SupportService`) — the sign-in
 * screen links here before an account exists. `accept()` and
 * `acceptanceStatus()` require the signed-in customer's own session, because
 * there is nobody to accept on the customer's behalf without one.
 *
 * **Locale is `ru` / `uz-Latn` / `en` on this endpoint, not this app's own
 * two-letter `LangService` ids.** Unlike `SupportService`'s FAQ (which the
 * catalog/help-centre modules happen to key by the same two-letter codes
 * `LangService` uses), `legal.domain.TermsLocale` follows the platform's
 * other three-locale vocabulary (`notifications`, `marketing`, `ordering`),
 * whose Uzbek tag is `uz-Latn`. {@link toDocumentLocale} is the one place
 * that translation happens.
 */
@Injectable({ providedIn: 'root' })
export class TermsService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly lang = inject(LangService);
  private readonly session = inject(Session);

  private get termsPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/terms`;
  }

  /** The document a customer should read right now, in the app's current language. */
  async current(): Promise<TermsDocument> {
    const response = await this.api.get<TermsResponse>(this.termsPath, {
      query: { locale: toDocumentLocale(this.lang.langId()), brandName: this.config.brand.displayName },
      anonymous: true,
    });
    return {
      locale: response.locale,
      isPlatformDefault: response.isPlatformDefault,
      version: response.version,
      body: response.body,
    };
  }

  /**
   * Records that the signed-in customer accepts whatever {@link current}
   * would answer right now, in the app's current language.
   *
   * @returns the version label just accepted, e.g. `"v3:ru"` or `"default-v1:en"`
   */
  async accept(): Promise<string> {
    const response = await this.api.mutate<AcceptResponse, AcceptRequest>(
      'POST',
      `${this.termsPath}/accept`,
      { body: { locale: toDocumentLocale(this.lang.langId()), brandName: this.config.brand.displayName } },
    );
    return response.version;
  }

  /**
   * Whether the signed-in customer's last acceptance still covers the
   * version currently in force. Only meaningful for a signed-in customer;
   * called with nobody signed in this simply reports not-accepted rather
   * than throwing, since a guest has nothing on record by definition.
   */
  async status(): Promise<AcceptanceStatus> {
    if (!this.session.isAuthenticated()) {
      return { accepted: false, currentVersion: '', lastAcceptedVersion: null, lastAcceptedAt: null };
    }
    const response = await this.api.get<AcceptanceStatusResponse>(`${this.termsPath}/acceptance-status`, {
      query: { locale: toDocumentLocale(this.lang.langId()), brandName: this.config.brand.displayName },
    });
    return {
      accepted: response.accepted,
      currentVersion: response.currentVersion,
      lastAcceptedVersion: response.lastAcceptedVersion,
      lastAcceptedAt: response.lastAcceptedAt,
    };
  }
}

/**
 * Maps this app's own language selector (`uz`/`ru`/`en`, see `LangService`)
 * onto the platform's `ru`/`uz-Latn`/`en` legal-content locale tags.
 *
 * A plain lookup rather than a shared enum: the two vocabularies serve
 * different purposes (one is this app's own UI chrome language, the other is
 * a content locale several backend modules already key by), and the
 * `notifications`/`marketing`/`ordering` modules each declare their own copy
 * of the three-tag set rather than sharing one type, which this mirrors.
 */
function toDocumentLocale(langId: string): string {
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

interface TermsResponse {
  readonly locale: string;
  readonly isPlatformDefault: boolean;
  readonly version: number | null;
  readonly body: string;
}

interface AcceptRequest {
  readonly locale: string;
  readonly brandName: string;
}

interface AcceptResponse {
  readonly version: string;
  readonly acceptedAt: string;
}

interface AcceptanceStatusResponse {
  readonly accepted: boolean;
  readonly currentVersion: string;
  readonly lastAcceptedVersion: string | null;
  readonly lastAcceptedAt: string | null;
}
