import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { TelegramCard } from './telegram-card';
import {
  TelegramSettingsApi,
  TelegramSettingsRequest,
  TelegramSettingsView,
} from './telegram-settings-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function settings(overrides: Partial<TelegramSettingsView> = {}): TelegramSettingsView {
  return {
    brandId: 'b1',
    miniAppEnabled: true,
    miniAppShortName: 'order',
    publicOfferLink: true,
    menuButtonLabels: { ru: 'Заказать', en: 'Order' },
    welcomeTexts: { en: 'Welcome to Tandir House' },
    miniAppUrl: 'https://shop.example.uz/',
    miniAppUnavailable: null,
    publicOfferUrl: 'https://shop.example.uz/terms',
    channelVersion: 4,
    ...overrides,
  };
}

function staleVersion(): ApiError {
  return new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null);
}

describe('TelegramCard (ADR 0183 D27)', () => {
  let fixture: ComponentFixture<TelegramCard>;

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  function type(id: string, value: string): void {
    const field = byId(id) as HTMLInputElement | HTMLTextAreaElement;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function toggle(id: string, checked: boolean): void {
    const box = byId(id) as HTMLInputElement;
    box.checked = checked;
    box.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  async function render(api: Partial<TelegramSettingsApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [TelegramCard],
      providers: [
        provideRouter([]),
        { provide: TelegramSettingsApi, useValue: api },
        {
          provide: LocaleSet,
          useValue: {
            locales: signal(['ru', 'uz-Latn', 'en']),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TelegramCard);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('channelId', 'chan-1');
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId('telegram-card')?.textContent).not.toContain('Loading the Telegram settings');
    });
  }

  it('shows what is saved, one field per language, and the derived address as text rather than a field', async () => {
    await render({ read: vi.fn().mockResolvedValue(settings()) });

    expect((byId('telegram-label-ru') as HTMLInputElement).value).toBe('Заказать');
    expect((byId('telegram-label-uz-Latn') as HTMLInputElement).value).toBe('');
    expect((byId('telegram-welcome-en') as HTMLTextAreaElement).value).toBe(
      'Welcome to Tandir House',
    );
    expect((byId('telegram-short-name') as HTMLInputElement).value).toBe('order');
    expect(byId('telegram-mini-app-url')?.textContent).toContain('https://shop.example.uz/');
    expect(byId('telegram-offer-url')?.textContent).toContain('https://shop.example.uz/terms');
    // The address is derived from the verified hostname: there is no input a tenant could type one into.
    const inputs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        'input[type="text"], input:not([type])',
      ),
    ).map((input) => (input as HTMLInputElement).dataset['testid']);
    expect(inputs).toEqual([
      'telegram-short-name',
      'telegram-label-ru',
      'telegram-label-uz-Latn',
      'telegram-label-en',
    ]);
  });

  it.each([
    ['BRAND_NOT_CHOSEN', 'choose the working brand’s storefront'],
    ['HOSTNAME_NOT_CLAIMED', 'do not sell on any website channel with an address'],
    ['HOSTNAME_NOT_VERIFIED', 'is not verified yet'],
    ['HOSTNAME_AMBIGUOUS', 'will not guess which is the storefront'],
  ] as const)('says why there is no address when the reason is %s', async (reason, words) => {
    await render({
      read: vi
        .fn()
        .mockResolvedValue(
          settings({ miniAppUrl: null, miniAppUnavailable: reason, publicOfferUrl: null }),
        ),
    });

    expect(byId('telegram-mini-app-url')).toBeNull();
    expect(byId('telegram-mini-app-unavailable')?.textContent).toContain(words);
  });

  it('saves a full replace for the working brand against the channel version, and then shows it saved', async () => {
    const save = vi
      .fn<(...args: unknown[]) => Promise<TelegramSettingsView>>()
      .mockResolvedValue(settings({ channelVersion: 5 }));
    await render({ read: vi.fn().mockResolvedValue(settings()), save });

    type('telegram-short-name', ' order_app ');
    type('telegram-label-uz-Latn', 'Buyurtma');
    type('telegram-welcome-ru', 'Добро пожаловать');
    toggle('telegram-offer', false);
    (byId('telegram-save') as HTMLButtonElement).click();

    await vi.waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    const [scope, channelId, request, expectedVersion] = save.mock.calls[0] as [
      LocationScope,
      string,
      TelegramSettingsRequest,
      number,
    ];
    expect(scope).toEqual(SCOPE);
    expect(channelId).toBe('chan-1');
    expect(expectedVersion).toBe(4);
    expect(request).toEqual({
      brandId: 'b1',
      miniAppEnabled: true,
      miniAppShortName: 'order_app',
      publicOfferLink: false,
      menuButtonLabels: { ru: 'Заказать', 'uz-Latn': 'Buyurtma', en: 'Order' },
      welcomeTexts: { ru: 'Добро пожаловать', en: 'Welcome to Tandir House' },
    });
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId('telegram-saved')).not.toBeNull();
    });
  });

  it('offers no save until something has changed, and not for a label past Telegram’s 64 characters', async () => {
    await render({ read: vi.fn().mockResolvedValue(settings()), save: vi.fn() });
    expect((byId('telegram-save') as HTMLButtonElement).disabled).toBe(true);

    type('telegram-label-en', 'x'.repeat(65));
    expect((byId('telegram-save') as HTMLButtonElement).disabled).toBe(true);
    type('telegram-label-en', 'Order now');
    expect((byId('telegram-save') as HTMLButtonElement).disabled).toBe(false);
  });

  it('refuses a short name BotFather would not accept before asking the server', async () => {
    await render({ read: vi.fn().mockResolvedValue(settings()), save: vi.fn() });

    type('telegram-short-name', 'has space');

    expect((byId('telegram-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('reloads and says so when the channel changed in another window', async () => {
    const read = vi
      .fn<() => Promise<TelegramSettingsView>>()
      .mockResolvedValueOnce(settings())
      .mockResolvedValueOnce(settings({ channelVersion: 9, miniAppShortName: 'newer' }));
    const save = vi.fn().mockRejectedValue(staleVersion());
    await render({ read, save });

    type('telegram-short-name', 'mine');
    (byId('telegram-save') as HTMLButtonElement).click();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId('telegram-save-error')?.textContent).toContain('changed in another window');
    });
    expect(read).toHaveBeenCalledTimes(2);
    expect((byId('telegram-short-name') as HTMLInputElement).value).toBe('newer');
  });

  it('says the settings are not for this operator on a 403 and reads nothing else', async () => {
    const read = vi.fn().mockRejectedValue(new ApiError('FORBIDDEN', 403, null, null));
    await render({ read });

    expect(byId('telegram-denied')).not.toBeNull();
    expect(byId('telegram-save')).toBeNull();
  });

  it('links to the guided Mini App setup', async () => {
    await render({ read: vi.fn().mockResolvedValue(settings()) });

    expect(byId('telegram-guided-link')?.getAttribute('href')).toBe('/marketing/referrals');
  });
});
