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

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { labelDrafts, labelsToSend, localeDisplayName } from '../../../core/i18n/locale-labels';
import { MessageKey } from '../../../core/i18n/messages.en';
import { PlatformLocales } from '../../../core/i18n/platform-locales';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { TelegramSettingsApi, TelegramSettingsView } from './telegram-settings-api';

/** Telegram allows 64 characters on a menu button. */
export const MENU_BUTTON_LABEL_LIMIT = 64;
/** The platform's cap on a welcome message. */
export const WELCOME_TEXT_LIMIT = 1000;

/**
 * The TELEGRAM section of Channel setup (ADR 0183 D27; `settings.md` 10.5): the menu button's label and the welcome message
 * in each of the brand's languages, whether the menu button opens the Mini App, the short name BotFather gave it, and whether
 * the tenant's terms page is offered as the public offer.
 *
 * **No address is typed here.** The Mini App opens the working brand's storefront at its verified hostname, shown
 * read-only; when there is none the card says why (nothing claimed, claimed but not yet verified, or two verified ones),
 * which is the one thing a tenant can fix. Saving never calls Telegram: the guided setup in Marketing → Referrals applies
 * the menu button and reads it back.
 */
@Component({
  selector: 'q-telegram-card',
  imports: [TPipe, RouterLink],
  templateUrl: './telegram-card.html',
  styleUrl: './telegram-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TelegramCard implements OnInit {
  private readonly api = inject(TelegramSettingsApi);
  private readonly localeSet = inject(LocaleSet);
  private readonly registry = inject(PlatformLocales);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly channelId = input.required<string>();

  protected readonly state = signal<'loading' | 'ready' | 'denied' | 'error'>('loading');
  protected readonly view = signal<TelegramSettingsView | null>(null);

  protected readonly miniAppEnabled = signal(false);
  protected readonly shortName = signal('');
  protected readonly publicOffer = signal(false);
  protected readonly labels = signal<Readonly<Record<string, string>>>({});
  protected readonly welcomes = signal<Readonly<Record<string, string>>>({});

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly locales = computed(() => this.localeSet.locales());
  protected readonly labelLimit = MENU_BUTTON_LABEL_LIMIT;
  protected readonly welcomeLimit = WELCOME_TEXT_LIMIT;

  protected readonly unavailableKey = computed<MessageKey | null>(() => {
    const reason = this.view()?.miniAppUnavailable ?? null;
    return reason === null
      ? null
      : (`settings.telegram.miniApp.unavailable.${reason}` as MessageKey);
  });

  protected readonly dirty = computed(() => {
    const current = this.view();
    if (current === null) {
      return false;
    }
    return (
      this.miniAppEnabled() !== current.miniAppEnabled ||
      this.shortName().trim() !== (current.miniAppShortName ?? '') ||
      this.publicOffer() !== current.publicOfferLink ||
      JSON.stringify(labelsToSend(this.locales(), this.labels())) !==
        JSON.stringify(this.sent(current.menuButtonLabels)) ||
      JSON.stringify(labelsToSend(this.locales(), this.welcomes())) !==
        JSON.stringify(this.sent(current.welcomeTexts))
    );
  });

  async ngOnInit(): Promise<void> {
    await this.localeSet.ensureLoaded();
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.state.set('loading');
    try {
      this.adopt(await this.api.read(this.scope(), this.channelId()));
      this.state.set('ready');
    } catch (error) {
      this.state.set(error instanceof ApiError && error.status === 403 ? 'denied' : 'error');
    }
  }

  private adopt(view: TelegramSettingsView): void {
    this.view.set(view);
    this.miniAppEnabled.set(view.miniAppEnabled);
    this.shortName.set(view.miniAppShortName ?? '');
    this.publicOffer.set(view.publicOfferLink);
    this.labels.set(labelDrafts(this.locales(), view.menuButtonLabels));
    this.welcomes.set(labelDrafts(this.locales(), view.welcomeTexts));
  }

  /** The stored wording restricted to the offered languages, in the order {@link labelsToSend} writes it. */
  private sent(stored: Readonly<Record<string, string>>): Record<string, string> {
    return labelsToSend(this.locales(), labelDrafts(this.locales(), stored));
  }

  protected languageName(locale: string): string {
    return localeDisplayName(this.i18n, locale, this.registry);
  }

  protected setLabel(locale: string, value: string): void {
    this.labels.update((current) => ({ ...current, [locale]: value }));
    this.saved.set(false);
  }

  protected setWelcome(locale: string, value: string): void {
    this.welcomes.update((current) => ({ ...current, [locale]: value }));
    this.saved.set(false);
  }

  protected canSave(): boolean {
    return !this.saving() && this.dirty() && this.valid();
  }

  /** A Mini App needs a short name before a link can be built, but a menu button does not, so only the shape is checked. */
  protected valid(): boolean {
    const name = this.shortName().trim();
    const nameOk = name === '' || /^[A-Za-z0-9_]{3,64}$/.test(name);
    const labelsOk = this.locales().every(
      (locale) => (this.labels()[locale] ?? '').trim().length <= MENU_BUTTON_LABEL_LIMIT,
    );
    const welcomesOk = this.locales().every(
      (locale) => (this.welcomes()[locale] ?? '').trim().length <= WELCOME_TEXT_LIMIT,
    );
    return nameOk && labelsOk && welcomesOk;
  }

  protected async save(): Promise<void> {
    const current = this.view();
    if (current === null || !this.canSave()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.saved.set(false);
    try {
      const saved = await this.api.save(
        this.scope(),
        this.channelId(),
        {
          // The Mini App opens the working brand's storefront; the platform derives the address from it.
          brandId: this.scope().brandId,
          miniAppEnabled: this.miniAppEnabled(),
          miniAppShortName: this.shortName().trim() || null,
          publicOfferLink: this.publicOffer(),
          menuButtonLabels: labelsToSend(this.locales(), this.labels()),
          welcomeTexts: labelsToSend(this.locales(), this.welcomes()),
        },
        current.channelVersion,
      );
      this.adopt(saved);
      this.saved.set(true);
    } catch (error) {
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        await this.reload();
        this.saveError.set(this.i18n.t('settings.telegram.stale'));
      } else {
        this.saveError.set(
          error instanceof ApiError
            ? describeApiError(error, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
      }
    } finally {
      this.saving.set(false);
    }
  }
}
