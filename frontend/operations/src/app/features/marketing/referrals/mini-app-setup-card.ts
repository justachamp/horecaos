import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { TelegramSettingsApi } from '../../settings/telegram/telegram-settings-api';
import {
  MiniAppSetupApi,
  MiniAppSetupStep,
  MiniAppSetupStepCode,
  MiniAppSetupView,
} from './mini-app-setup-api';

/** Where each step that sends the operator elsewhere sends them. */
const STEP_LINKS: Readonly<Partial<Record<MiniAppSetupStepCode, string>>> = {
  CONNECT_BOT: '/settings/integrations',
  REGISTER_WEBHOOK: '/settings/integrations',
  TELEGRAM_CHANNEL: '/settings/sales-channels',
  STOREFRONT_ADDRESS: '/settings/sales-channels',
};

/**
 * The guided Mini App and BotFather setup (ADR 0183, gap-map row `6.6a`; IA 6.6 «Mini-App / BotFather setup as a guided
 * resumable flow»), on the Referrals screen where the Telegram links are minted.
 *
 * **Resumable because the page remembers nothing.** Every step's state comes from `GET .../mini-app-setup`, which derives it from
 * facts the platform holds anyway (the bot, its webhook, the Telegram channel, the verified storefront address, the short name,
 * and the menu button as Telegram itself reports it). Coming back tomorrow, or fixing the address in another tab, shows the truth.
 *
 * **Two steps are only a human's.** `/newapp` in @BotFather has no API, so the card prints the exact words to send, with the
 * storefront address filled in, and takes the short name BotFather answers with. That step reads «recorded», never «verified».
 * The menu button is the step the platform does for you, and it says «done» only when Telegram reads back what was set.
 */
@Component({
  selector: 'q-mini-app-setup-card',
  imports: [TPipe, RouterLink],
  templateUrl: './mini-app-setup-card.html',
  styleUrl: './mini-app-setup-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MiniAppSetupCard implements OnInit {
  private readonly api = inject(MiniAppSetupApi);
  private readonly telegram = inject(TelegramSettingsApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();

  protected readonly state = signal<'loading' | 'ready' | 'denied' | 'error'>('loading');
  protected readonly view = signal<MiniAppSetupView | null>(null);

  protected readonly shortNameDraft = signal('');
  protected readonly recording = signal(false);
  protected readonly recordError = signal<string | null>(null);

  protected readonly applying = signal(false);
  protected readonly applyError = signal<string | null>(null);
  /** Set when the last apply was answered and Telegram still showed another button. */
  protected readonly appliedButNotShown = signal(false);

  protected readonly copied = signal<string | null>(null);

  protected readonly steps = computed(() => this.view()?.steps ?? []);
  protected readonly firstOpen = computed(
    () =>
      this.steps().find((step) => step.state !== 'DONE' && step.code !== 'TEST_LINK')?.code ?? null,
  );

  protected readonly botFatherLines = computed<readonly string[]>(() => {
    const current = this.view();
    return [
      this.i18n.t('marketing.miniApp.botFather.step1'),
      this.i18n.t('marketing.miniApp.botFather.step2', { bot: current?.botUsername ?? '…' }),
      this.i18n.t('marketing.miniApp.botFather.step3'),
      this.i18n.t('marketing.miniApp.botFather.step4'),
      this.i18n.t('marketing.miniApp.botFather.step5'),
      this.i18n.t('marketing.miniApp.botFather.step6'),
    ];
  });

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  private async reload(): Promise<void> {
    try {
      this.adopt(await this.api.status(this.scope()));
      this.state.set('ready');
    } catch (error) {
      this.state.set(error instanceof ApiError && error.status === 403 ? 'denied' : 'error');
    }
  }

  private adopt(view: MiniAppSetupView): void {
    this.view.set(view);
    this.shortNameDraft.set(view.miniAppShortName ?? '');
  }

  protected stepTitleKey(step: MiniAppSetupStep): MessageKey {
    return `marketing.miniApp.step.${step.code}.title` as MessageKey;
  }

  protected stepHintKey(step: MiniAppSetupStep): MessageKey {
    return `marketing.miniApp.step.${step.code}.hint` as MessageKey;
  }

  protected stateKey(step: MiniAppSetupStep): MessageKey {
    if (step.code === 'BOTFATHER_APP' && step.state === 'DONE') {
      return 'marketing.miniApp.state.RECORDED';
    }
    return `marketing.miniApp.state.${step.state}` as MessageKey;
  }

  /** The reason a step is not done, when the platform gave one and the card has words for it. */
  protected detailKey(step: MiniAppSetupStep): MessageKey | null {
    return step.detail === null || step.state === 'DONE'
      ? null
      : (`marketing.miniApp.detail.${step.code}.${step.detail}` as MessageKey);
  }

  protected link(step: MiniAppSetupStep): string | null {
    return STEP_LINKS[step.code] ?? null;
  }

  protected canRecordShortName(): boolean {
    const name = this.shortNameDraft().trim();
    return (
      !this.recording() &&
      /^[A-Za-z0-9_]{3,64}$/.test(name) &&
      name !== (this.view()?.miniAppShortName ?? '')
    );
  }

  /**
   * Records the short name BotFather answered with, on the Telegram channel's own settings and through the one endpoint
   * Channel setup uses, so there is a single place the value is written. The settings are replaced whole, so the card reads them
   * first and changes nothing but the short name and the Mini App switch.
   */
  protected async recordShortName(): Promise<void> {
    const current = this.view();
    if (current === null || current.channelId === null || !this.canRecordShortName()) {
      return;
    }
    this.recording.set(true);
    this.recordError.set(null);
    try {
      const tenant = { tenantId: this.scope().tenantId };
      const settings = await this.telegram.read(tenant, current.channelId);
      await this.telegram.save(
        tenant,
        current.channelId,
        {
          brandId: this.scope().brandId,
          miniAppEnabled: true,
          miniAppShortName: this.shortNameDraft().trim(),
          publicOfferLink: settings.publicOfferLink,
          menuButtonLabels: settings.menuButtonLabels,
          welcomeTexts: settings.welcomeTexts,
        },
        settings.channelVersion,
      );
      await this.reload();
    } catch (error) {
      this.recordError.set(this.describe(error));
    } finally {
      this.recording.set(false);
    }
  }

  protected async applyMenuButton(): Promise<void> {
    if (this.applying()) {
      return;
    }
    this.applying.set(true);
    this.applyError.set(null);
    this.appliedButNotShown.set(false);
    try {
      const after = await this.api.applyMenuButton(this.scope());
      this.adopt(after);
      const menu = after.steps.find((step) => step.code === 'MENU_BUTTON');
      // After taking a button off the step is TODO for another reason (the Mini App is off); only these two details mean
      // Telegram was asked and still shows the wrong button.
      this.appliedButNotShown.set(
        menu?.state === 'TODO' && (menu.detail === 'NOT_APPLIED' || menu.detail === 'STALE_BUTTON'),
      );
    } catch (error) {
      this.applyError.set(this.describe(error));
    } finally {
      this.applying.set(false);
    }
  }

  protected async copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.copied.set(text);
    } catch {
      // A browser that refuses the clipboard: the text is on screen to be selected.
      this.copied.set(null);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
