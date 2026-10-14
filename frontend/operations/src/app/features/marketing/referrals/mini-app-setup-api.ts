import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { referralPaths } from '../../../core/api/referral-paths';

/** The steps of the guided setup, in the order the platform lists them (`TelegramMiniAppSetupService`). */
export type MiniAppSetupStepCode =
  | 'CONNECT_BOT'
  | 'REGISTER_WEBHOOK'
  | 'TELEGRAM_CHANNEL'
  | 'STOREFRONT_ADDRESS'
  | 'BOTFATHER_APP'
  | 'MENU_BUTTON'
  | 'TEST_LINK';

/** `UNKNOWN`: a fact that lives at Telegram could not be read just now -- neither done nor known to be undone. */
export type MiniAppSetupState = 'DONE' | 'TODO' | 'UNKNOWN';

/** Mirrors `TelegramMiniAppSetupController.StepView`. */
export interface MiniAppSetupStep {
  readonly code: MiniAppSetupStepCode;
  readonly state: MiniAppSetupState;
  /** A stable reason code the step can branch on (`NOT_CLAIMED`, `NOT_APPLIED`, ...), never prose. */
  readonly detail: string | null;
}

/** Mirrors `TelegramMiniAppSetupController.StatusView`. Derived from facts on every read; nothing is stored. */
export interface MiniAppSetupView {
  readonly steps: readonly MiniAppSetupStep[];
  readonly botUsername: string | null;
  /** The brand's verified storefront address, the Web App URL BotFather is given. Never typed. */
  readonly miniAppUrl: string | null;
  readonly miniAppShortName: string | null;
  readonly channelId: string | null;
  readonly channelVersion: number | null;
  readonly menuButtonLabel: string | null;
  /** What Telegram says the bot's menu button opens right now. */
  readonly menuButtonUrl: string | null;
  readonly testLink: string | null;
}

/** The guided Mini App and BotFather setup (ADR 0183, row 6.6a). */
@Injectable({ providedIn: 'root' })
export class MiniAppSetupApi {
  private readonly api = inject(ApiClient);

  async status(scope: BrandScope): Promise<MiniAppSetupView> {
    const result = await firstValueFrom(
      this.api.get<MiniAppSetupView>(referralPaths.telegramMiniAppSetup(scope)),
    );
    return result.value;
  }

  /** Sets the bot's menu button and reads it back from Telegram; the answer is the status afterwards. */
  async applyMenuButton(scope: BrandScope): Promise<MiniAppSetupView> {
    return firstValueFrom(
      this.api.post<null, MiniAppSetupView>(referralPaths.telegramMenuButton(scope), command(null)),
    );
  }
}
