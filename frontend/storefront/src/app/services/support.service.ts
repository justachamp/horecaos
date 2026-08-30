import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { LangService } from './lang.service';

/** One question and its answer, in the resolved locale. */
export interface FaqItem {
  id: string;
  question: string;
  answer: string;
}

/** A heading and the questions under it. */
export interface FaqCategory {
  id: string;
  name: string;
  items: FaqItem[];
}

/** Somewhere a customer can reach this brand. */
export interface SocialMediaItem {
  id: string;
  /** The platform's own code -- TELEGRAM, PHONE -- for choosing an icon. */
  platform: string;
  /** What a customer reads. Never the code. */
  name: string;
  /** An operator's own artwork, or empty when the app uses its own for the platform. */
  image: string;
  url: string;
}

/**
 * A brand's help content.
 *
 * Replaces the legacy `/customers/support/faq` and `/customers/support/socials-media`
 * with the platform's own, which did not exist until now: there was no FAQ
 * table, no socials endpoint and nothing in between. Both reads are anonymous,
 * like the menu -- somebody hunting for delivery hours or the Telegram channel
 * very often has no account yet, and a token would answer the question only for
 * people who have stopped needing to ask.
 *
 * The locale travels with the FAQ request. The platform resolves it and falls
 * back to any other published translation rather than to an authoring code, so
 * a brand that has translated into Uzbek but not English shows an English
 * speaker the Uzbek answer instead of the string `DELIVERY_HOURS`.
 */
@Injectable({ providedIn: 'root' })
export class SupportService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly lang = inject(LangService);

  private get supportPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/support`;
  }

  /**
   * @returns an empty list when this brand has published no FAQ. Empty is a
   *          screen with no questions, not a failure to report: a brand with
   *          nothing to say yet is an ordinary state.
   */
  async faq(): Promise<FaqCategory[]> {
    const response = await this.api.get<FaqResponse[]>(`${this.supportPath}/faq`, {
      query: { locale: this.lang.langId() },
      anonymous: true,
    });
    return (response ?? []).map((category) => ({
      id: category.categoryId,
      name: category.name,
      items: (category.entries ?? []).map((entry) => ({
        id: entry.entryId,
        question: entry.question,
        answer: entry.answer,
      })),
    }));
  }

  async socialLinks(): Promise<SocialMediaItem[]> {
    const response = await this.api.get<SocialLinkResponse[]>(
      `${this.supportPath}/social-links`,
      { anonymous: true },
    );
    return (response ?? []).map((link) => ({
      id: link.linkId,
      platform: link.platform,
      // The platform code is a checked vocabulary, not a label. Rendering
      // "TELEGRAM" at a customer is showing them a database value, the same
      // mistake a SKU printed as a size label would be.
      name: PLATFORM_LABELS[link.platform] ?? link.platform,
      image: link.imageUrl ?? '',
      url: link.url,
    }));
  }
}

interface FaqResponse {
  readonly categoryId: string;
  readonly code: string | null;
  readonly name: string;
  readonly entries: readonly {
    readonly entryId: string;
    readonly code: string | null;
    readonly question: string;
    readonly answer: string;
  }[];
}

/**
 * Customer-facing names for the platform's codes.
 *
 * Proper nouns, so they are not translated: Telegram is Telegram in every
 * language this storefront serves. The two that are not proper nouns -- a phone
 * number and an email address -- read fine as they are and would need i18n keys
 * if that stops being true.
 */
const PLATFORM_LABELS: Readonly<Record<string, string>> = {
  TELEGRAM: 'Telegram',
  INSTAGRAM: 'Instagram',
  FACEBOOK: 'Facebook',
  YOUTUBE: 'YouTube',
  TIKTOK: 'TikTok',
  WEBSITE: 'Website',
  PHONE: 'Telefon',
  EMAIL: 'Email',
};

interface SocialLinkResponse {
  readonly linkId: string;
  readonly platform: string;
  readonly url: string;
  readonly imageUrl: string | null;
}
