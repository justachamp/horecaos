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
}

export interface AddVersionRequest {
  readonly wordings: Readonly<Record<string, Wording>>;
  readonly variablesSchema: Readonly<Record<string, string>>;
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

/** Every channel a template may declare. Only `SMS` and `TELEGRAM` are wired — see `field.channel.hint`. */
export const NOTIFICATION_CHANNELS: readonly string[] = ['SMS', 'TELEGRAM', 'PUSH', 'EMAIL'];

/** `NotificationChannel.isWired()`, restated client-side so the editor can warn before a save round-trip. */
export const WIRED_CHANNELS: ReadonlySet<string> = new Set(['SMS', 'TELEGRAM']);

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

  async variableCatalogue(scope: LocationScope): Promise<readonly VariableCatalogueEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly VariableCatalogueEntry[]>(
        settingsPaths.notificationVariableCatalogue(scope),
      ),
    );
    return result.value ?? [];
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
