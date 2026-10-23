import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors uz.horecaos.platform.notifications.web.NotificationTemplateController.TemplateResponse. */
export interface TemplateResponse {
  readonly id: string;
  readonly brandId: string | null;
  readonly templateKey: string;
  readonly notificationClass: string;
  readonly channel: string;
  readonly consentPurpose: string | null;
  readonly status: string;
  readonly activeVersion: number | null;
  readonly version: number;
  /** Gap-map row 10.9a: null resolves for every fulfilment mode. */
  readonly fulfillmentMode: string | null;
  /** Gap-map row 10.9a: null resolves for every channel source. */
  readonly channelSource: string | null;
  /** ADR 0184: where this row sits in ADR 0020's order — a brand's own, or the tenant-wide default. */
  readonly origin: 'BRAND' | 'TENANT';
  /** The version the row summarises: the active one, or the newest draft; null when there is no version. */
  readonly shownVersion: number | null;
  /** The languages that version has a wording in. */
  readonly versionLocales: readonly string[];
  /** The languages this template's brand serves, which an activation needs. */
  readonly requiredLocales: readonly string[];
  /** ADR 0091: the worst provider state among the shown version's languages. */
  readonly providerReview: string;
}

/**
 * Mirrors `NotificationTemplateController.PlatformDefaultResponse` (ADR 0184): the wording the platform
 * sends when a brand and its tenant have written none. Read-only; a brand customises it by registering
 * its own template.
 */
export interface PlatformDefault {
  readonly templateKey: string;
  readonly notificationClass: string;
  readonly consentPurpose: string | null;
  /** The customer channels the wording is offered on. */
  readonly channels: readonly string[];
  /** The version a sent message freezes. */
  readonly version: number;
  /** Locale tag to the platform's sentence. */
  readonly bodies: Readonly<Record<string, string>>;
  readonly variables: readonly string[];
}

/** Mirrors `NotificationTemplateController.OtpWordingResponse` (ADR 0184, D19). */
export interface OtpWording {
  readonly sentences: readonly OtpSentence[];
  /** `GATEWAY_MODERATES` when this tenant's SMS gateway refuses wordings it has not approved. */
  readonly gatewayApproval: 'GATEWAY_MODERATES' | 'NOT_MODERATED';
}

export interface OtpSentence {
  readonly locale: string;
  /** The sentence with `{{code}}` and `{{minutes}}` still in it. */
  readonly text: string;
  readonly characters: number;
}

export interface CreateTemplateRequest {
  readonly templateKey: string;
  readonly notificationClass: string;
  readonly channel: string;
  readonly consentPurpose?: string;
  readonly fulfillmentMode?: string | null;
  readonly channelSource?: string | null;
}

/** `uz.horecaos.platform.tenancy.api.FulfillmentMode` (ADR 0036). */
export const FULFILLMENT_MODES: readonly string[] = ['DELIVERY', 'PICKUP', 'DINE_IN'];

/** `uz.horecaos.platform.tenancy.api.SalesChannelSystemType` (ADR 0036). */
export const CHANNEL_SOURCES: readonly string[] = [
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
  'KIOSK',
  'QR_TABLE',
  'CALL_CENTRE',
  'AGGREGATOR',
  'POS',
];

export interface Wording {
  readonly subject?: string;
  readonly body: string;
  /** A push wording's 3:1 cover, an ADR 0010 media asset the tenant owns (ADR 0182, D14). */
  readonly coverAssetId?: string | null;
  /** A push wording's storefront path: a single leading slash, never an absolute URL (ADR 0182, D14). */
  readonly targetPath?: string | null;
}

export interface AddVersionRequest {
  readonly wordings: Readonly<Record<string, Wording>>;
  readonly variablesSchema: Readonly<Record<string, string>>;
  /** ADR 0184: whether `{{productList}}` follows each item with its line total. Absent reads as off. */
  readonly showItemPrices?: boolean;
}

/** Mirrors `NotificationTemplateController.VersionResponse`. */
export interface VersionResult {
  readonly templateId: string;
  readonly versionNumber: number;
  /** ADR 0091: true when a locale of this version is PENDING/REJECTED with its SMS gateway. */
  readonly awaitsProviderReview: boolean;
}

/**
 * Mirrors `NotificationTemplateController.WordingResponse` (wave P36 widened
 * it past `subject`/`body`/`status` with the moderation state gap map row
 * `10.9c` asks for, and the stored `variablesSchema` row `X.27` asks for).
 */
export interface WordingResponse {
  readonly versionNumber: number;
  readonly locale: string;
  readonly subject: string | null;
  readonly body: string;
  readonly contentHash: string;
  readonly status: string;
  readonly approvedBy: string | null;
  readonly variablesSchema: Readonly<Record<string, string>>;
  /** ADR 0091: NOT_REQUIRED, PENDING, APPROVED or REJECTED. */
  readonly providerReview: string;
  readonly providerReviewReference: string | null;
  readonly providerReviewNote: string | null;
  readonly providerReviewUpdatedAt: string | null;
  /** ADR 0184: the version's «show item prices» setting, repeated on each of its languages. */
  readonly showItemPrices: boolean;
  /** ADR 0182, D14: a push wording's cover and target; null on every other channel. */
  readonly coverAssetId?: string | null;
  readonly targetPath?: string | null;
}

/** One version's locale rows, grouped client-side from the flat `WordingResponse` list. */
export interface VersionGroup {
  readonly versionNumber: number;
  readonly status: string;
  readonly approvedBy: string | null;
  readonly locales: readonly WordingResponse[];
}

/** Mirrors `NotificationTemplateController.VariableCatalogueEntry`. */
export interface VariableCatalogueEntry {
  readonly notificationClass: string;
  readonly variables: readonly { readonly name: string; readonly description: string }[];
}

export interface TestSendRequest {
  readonly locale: string;
  readonly destination: string;
}

/** Mirrors `NotificationTemplateController.TestSendResponse`. */
export interface TestSendResult {
  readonly status: string;
  readonly providerStatus: string | null;
  readonly errorCode: string | null;
}

/** Mirrors `TelegramRoutingController.EventClassResponse`. */
export interface EventClassOption {
  readonly eventClass: string;
  readonly description: string;
}

/** Mirrors `TelegramRoutingController.RoutingSubscriptionResponse`. */
export interface RoutingSubscription {
  readonly eventClass: string;
  readonly enabled: boolean;
}

/** Mirrors `TelegramRoutingController.BindingResponse`. */
export interface RoutingBinding {
  readonly bindingId: string;
  readonly brandId: string;
  readonly locationId: string | null;
  readonly status: string;
  readonly chatId: number;
  readonly topicId: number | null;
  readonly retiredAt: string | null;
  readonly retiredReason: string | null;
  readonly createdAt: string;
  readonly subscriptions: readonly RoutingSubscription[];
}

/** Every channel a template may declare. `SMS`, `TELEGRAM` and `EMAIL` are wired (EMAIL for campaigns only, ADR 0182) — see `field.channel.hint`. */
export const NOTIFICATION_CHANNELS: readonly string[] = ['SMS', 'TELEGRAM', 'PUSH', 'EMAIL'];

/** `NotificationChannel.isWired()`, restated client-side so the editor can warn before a save round-trip. */
export const WIRED_CHANNELS: ReadonlySet<string> = new Set(['SMS', 'TELEGRAM', 'EMAIL', 'PUSH']);

/**
 * 10.9 Notifications, Tab 1 (`NotificationTemplateController`, ADR 0020).
 * Already on the operations surface (`/api/v1/tenants/**`, pre-ADR-0031) —
 * no cross-surface call here, unlike most of this wave's other screens.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly TemplateResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TemplateResponse[]>(settingsPaths.notificationTemplates(scope)),
    );
    return result.value ?? [];
  }

  async create(scope: LocationScope, request: CreateTemplateRequest): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<CreateTemplateRequest, { id: string }>(
        settingsPaths.notificationTemplates(scope),
        command(request),
      ),
    );
    return response.id;
  }

  async addVersion(
    scope: LocationScope,
    templateId: string,
    request: AddVersionRequest,
  ): Promise<VersionResult> {
    return firstValueFrom(
      this.api.post<AddVersionRequest, VersionResult>(
        settingsPaths.notificationTemplateVersions(scope, templateId),
        command(request),
      ),
    );
  }

  async activate(scope: LocationScope, templateId: string, versionNumber: number): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        settingsPaths.notificationTemplateActivate(scope, templateId, versionNumber),
        command(null),
      ),
    );
  }

  /** Every version of a template, grouped by version number, newest first. */
  async versions(scope: LocationScope, templateId: string): Promise<readonly VersionGroup[]> {
    const result = await firstValueFrom(
      this.api.get<readonly WordingResponse[]>(
        settingsPaths.notificationTemplateVersions(scope, templateId),
      ),
    );
    return groupByVersion(result.value ?? []);
  }

  async version(
    scope: LocationScope,
    templateId: string,
    versionNumber: number,
  ): Promise<readonly WordingResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly WordingResponse[]>(
        settingsPaths.notificationTemplateVersion(scope, templateId, versionNumber),
      ),
    );
    return result.value ?? [];
  }

  /** `templateKey` lets the platform include the one variable that exists for one key only (ADR 0184, D21). */
  async variableCatalogue(
    scope: LocationScope,
    templateKey?: string,
  ): Promise<readonly VariableCatalogueEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly VariableCatalogueEntry[]>(
        settingsPaths.notificationVariableCatalogue(scope, templateKey),
      ),
    );
    return result.value ?? [];
  }

  /** The platform's own wording, shown read-only beneath the brand's and tenant's templates (ADR 0184). */
  async platformDefaults(scope: LocationScope): Promise<readonly PlatformDefault[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PlatformDefault[]>(settingsPaths.notificationPlatformDefaults(scope)),
    );
    return result.value ?? [];
  }

  /** The one-time-code sentence, read-only: the platform's, not the tenant's (ADR 0184, D19). */
  async otpWording(scope: LocationScope): Promise<OtpWording | null> {
    const result = await firstValueFrom(
      this.api.get<OtpWording>(settingsPaths.notificationOtpWording(scope)),
    );
    return result.value ?? null;
  }

  async testSend(
    scope: LocationScope,
    templateId: string,
    versionNumber: number,
    request: TestSendRequest,
  ): Promise<TestSendResult> {
    return firstValueFrom(
      this.api.post<TestSendRequest, TestSendResult>(
        settingsPaths.notificationTemplateTestSend(scope, templateId, versionNumber),
        command(request),
      ),
    );
  }

  // -------------------------------------------------------- Tab 2: routing

  async routingEventClasses(scope: LocationScope): Promise<readonly EventClassOption[]> {
    const result = await firstValueFrom(
      this.api.get<readonly EventClassOption[]>(
        settingsPaths.notificationRoutingEventClasses(scope),
      ),
    );
    return result.value ?? [];
  }

  async routingBindings(scope: LocationScope): Promise<readonly RoutingBinding[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RoutingBinding[]>(settingsPaths.notificationRoutingBindings(scope)),
    );
    return result.value ?? [];
  }

  async setRoutingSubscription(
    scope: LocationScope,
    bindingId: string,
    eventClass: string,
    enabled: boolean,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ eventClass: string; enabled: boolean }, void>(
        settingsPaths.notificationRoutingSubscription(scope, bindingId),
        command({ eventClass, enabled }),
      ),
    );
  }

  async changeRoutingTopic(
    scope: LocationScope,
    bindingId: string,
    topicId: number | null,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ topicId: number | null }, void>(
        settingsPaths.notificationRoutingTopic(scope, bindingId),
        command({ topicId }),
      ),
    );
  }

  async unbindRouting(scope: LocationScope, bindingId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        settingsPaths.notificationRoutingUnbind(scope, bindingId),
        command(null),
      ),
    );
  }
}

function groupByVersion(rows: readonly WordingResponse[]): readonly VersionGroup[] {
  const byVersion = new Map<number, WordingResponse[]>();
  for (const row of rows) {
    const existing = byVersion.get(row.versionNumber);
    if (existing) {
      existing.push(row);
    } else {
      byVersion.set(row.versionNumber, [row]);
    }
  }
  return [...byVersion.entries()]
    .sort(([a], [b]) => b - a)
    .map(([versionNumber, locales]) => ({
      versionNumber,
      status: locales[0]?.status ?? 'DRAFT',
      approvedBy: locales.find((locale) => locale.approvedBy !== null)?.approvedBy ?? null,
      locales,
    }));
}
