import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Why a Telegram channel has no Mini App address (`TelegramChannelSettingsService`). */
export type MiniAppUnavailable =
  'BRAND_NOT_CHOSEN' | 'HOSTNAME_NOT_CLAIMED' | 'HOSTNAME_NOT_VERIFIED' | 'HOSTNAME_AMBIGUOUS';

/** Mirrors `TelegramChannelSetupController.SettingsView` (ADR 0183 D27). */
export interface TelegramSettingsView {
  readonly brandId: string | null;
  readonly miniAppEnabled: boolean;
  readonly miniAppShortName: string | null;
  readonly publicOfferLink: boolean;
  /** Language tag to label (at most 64 characters). */
  readonly menuButtonLabels: Readonly<Record<string, string>>;
  /** Language tag to welcome text. */
  readonly welcomeTexts: Readonly<Record<string, string>>;
  /** Derived from the brand's verified storefront hostname -- never typed. */
  readonly miniAppUrl: string | null;
  readonly miniAppUnavailable: MiniAppUnavailable | null;
  readonly publicOfferUrl: string | null;
  /** The token a write must present as `expectedVersion`. */
  readonly channelVersion: number;
}

/** Mirrors `TelegramChannelSetupController.SettingsRequest`. A full replace. */
export interface TelegramSettingsRequest {
  readonly brandId: string | null;
  readonly miniAppEnabled: boolean;
  readonly miniAppShortName: string | null;
  readonly publicOfferLink: boolean;
  readonly menuButtonLabels: Readonly<Record<string, string>>;
  readonly welcomeTexts: Readonly<Record<string, string>>;
}

/** A TELEGRAM channel's own settings (ADR 0183 D27; row 10.5): control-plane, nested under the channel. */
@Injectable({ providedIn: 'root' })
export class TelegramSettingsApi {
  private readonly api = inject(ApiClient);

  async read(scope: LocationScope, channelId: string): Promise<TelegramSettingsView> {
    const result = await firstValueFrom(
      this.api.get<TelegramSettingsView>(settingsPaths.channelTelegram(scope, channelId)),
    );
    return result.value;
  }

  async save(
    scope: LocationScope,
    channelId: string,
    request: TelegramSettingsRequest,
    expectedVersion: number,
  ): Promise<TelegramSettingsView> {
    return firstValueFrom(
      this.api.put<TelegramSettingsRequest, TelegramSettingsView>(
        settingsPaths.channelTelegram(scope, channelId),
        command(request),
        { params: { expectedVersion } },
      ),
    );
  }
}
