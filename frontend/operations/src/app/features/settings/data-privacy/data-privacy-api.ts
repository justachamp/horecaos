import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { settingsPaths } from '../../../core/api/settings-paths';
import { LabelsByLocale, LocaleSetView } from '../../../core/i18n/locale-labels';

/** The three kinds of data-subject request the tenant's worklist lists together (ADR 0029). */
export type PrivacyRequestKind = 'ERASURE' | 'EXPORT' | 'CORRECTION';

export type PrivacyRequestStatus = 'PENDING' | 'COMPLETED' | 'CANCELLED';

/**
 * Mirrors `CustomerPrivacyController.PrivacyRequestResponse`: one line of the worklist. It names the kind, the customer
 * account, where the request stands and when; never a value. Acting on a line goes through the customer's own record using
 * `customerAccountId`.
 */
export interface PrivacyRequest {
  readonly kind: PrivacyRequestKind;
  readonly id: string;
  readonly customerAccountId: string;
  readonly status: PrivacyRequestStatus;
  readonly requestedVia: 'STOREFRONT' | 'OPERATIONS';
  readonly requestedAt: string;
  /** When it was produced, withdrawn, executed or corrected. */
  readonly settledAt: string | null;
  /** For an export: the day the tenant owes the customer her data. */
  readonly dueAt: string | null;
  /** For a correction: what was amended (`DISPLAY_NAME`, `DATE_OF_BIRTH`, `CONTACT_POINT`, `ADDRESS`). */
  readonly subject: string | null;
}

/**
 * Mirrors `ConsentTypeController.ConsentTypeResponse`.
 *
 * The purpose's wording is a map by locale, not three fixed fields: a tenant whose brands offer a fourth language
 * has a purpose worded in it, and a screen that switched on `labelRu`/`labelUz`/`labelEn` could not show it
 * (ADR 0149). The three legacy fields are still sent for one release for a client that has not been rebuilt, and this
 * console does not read them.
 */
export interface ConsentType {
  readonly id: string;
  readonly code: string;
  readonly labels: LabelsByLocale;
  readonly description: string | null;
  readonly channelSpecific: boolean;
  readonly policyVersion: string;
  readonly active: boolean;
  readonly updatedAt: string;
}

/**
 * Mirrors `RetentionWindowController.RetentionWindowResponse`: what one retention setting does for this tenant today.
 *
 * `effective` is computed by the job that does the deleting, so the card cannot disagree with it. `appliesTo` says
 * whether that window is this tenant's alone (`PER_TENANT`) or shared with every tenant (`ALL_TENANTS` — courier
 * tracks live in shared daily partitions, so a shorter value cannot shorten what the others keep).
 */
export interface RetentionWindow {
  readonly code: string;
  readonly unit: 'DAYS' | 'MONTHS';
  readonly platformDefault: number;
  readonly floor: number;
  readonly configured: number | null;
  readonly effective: number;
  readonly appliesTo: 'PER_TENANT' | 'ALL_TENANTS';
  readonly enforcedBy: string;
  readonly provisional: boolean;
}

/**
 * Settings 10.11's own reads (ADR 0109): the tenant-wide worklist of data-subject requests, the consent-type registry and what each
 * retention setting does. Retention periods are still written through `ConfigurationApi` — they are ADR 0030 keys,
 * not a bespoke table.
 */
@Injectable({ providedIn: 'root' })
export class DataPrivacyApi {
  private readonly api = inject(ApiClient);

  async privacyWorklist(
    tenantId: string,
    filter: { readonly kind?: PrivacyRequestKind; readonly status?: PrivacyRequestStatus } = {},
  ): Promise<readonly PrivacyRequest[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PrivacyRequest[]>(settingsPaths.privacyRequests(tenantId), {
        params: { kind: filter.kind, status: filter.status, limit: 200 },
      }),
    );
    return result.value ?? [];
  }

  async consentTypes(tenantId: string): Promise<readonly ConsentType[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ConsentType[]>(settingsPaths.consentTypes(tenantId)),
    );
    return result.value ?? [];
  }

  /** The languages the tenant's brands support, default first — the menu a purpose's wording is read from. */
  async consentLocaleSet(tenantId: string): Promise<LocaleSetView> {
    const result = await firstValueFrom(
      this.api.get<LocaleSetView>(settingsPaths.consentTypeLocaleSet(tenantId)),
    );
    return result.value;
  }

  async retentionWindows(tenantId: string): Promise<readonly RetentionWindow[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RetentionWindow[]>(settingsPaths.retentionWindows(tenantId)),
    );
    return result.value ?? [];
  }
}
