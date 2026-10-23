import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import {
  TelegramSettingsApi,
  TelegramSettingsRequest,
  TelegramSettingsView,
} from '../../settings/telegram/telegram-settings-api';
import {
  MiniAppSetupApi,
  MiniAppSetupState,
  MiniAppSetupStep,
  MiniAppSetupStepCode,
  MiniAppSetupView,
} from './mini-app-setup-api';
import { MiniAppSetupCard } from './mini-app-setup-card';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const CODES: readonly MiniAppSetupStepCode[] = [
  'CONNECT_BOT',
  'REGISTER_WEBHOOK',
  'TELEGRAM_CHANNEL',
  'STOREFRONT_ADDRESS',
  'BOTFATHER_APP',
  'MENU_BUTTON',
  'TEST_LINK',
];

function view(
  states: Partial<Record<MiniAppSetupStepCode, [MiniAppSetupState, string | null]>> = {},
  overrides: Partial<MiniAppSetupView> = {},
): MiniAppSetupView {
  const steps: MiniAppSetupStep[] = CODES.map((code) => {
    const [state, detail] = states[code] ?? ['DONE', null];
    return { code, state, detail };
  });
  return {
    steps,
    botUsername: 'tandir_bot',
    miniAppUrl: 'https://shop.example.uz/',
    miniAppShortName: null,
    channelId: 'chan-1',
    channelVersion: 4,
    menuButtonLabel: 'Menu',
    menuButtonUrl: null,
    testLink: null,
    ...overrides,
  };
}

function settings(overrides: Partial<TelegramSettingsView> = {}): TelegramSettingsView {
  return {
    brandId: null,
    miniAppEnabled: false,
    miniAppShortName: null,
    publicOfferLink: true,
    menuButtonLabels: { en: 'Order' },
    welcomeTexts: { en: 'Hello' },
    miniAppUrl: null,
    miniAppUnavailable: 'BRAND_NOT_CHOSEN',
    publicOfferUrl: null,
    channelVersion: 7,
    ...overrides,
  };
}

describe('MiniAppSetupCard (ADR 0183, row 6.6a)', () => {
  let fixture: ComponentFixture<MiniAppSetupCard>;

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  async function render(
    api: Partial<MiniAppSetupApi>,
    telegram: Partial<TelegramSettingsApi> = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [MiniAppSetupCard],
      providers: [
        provideRouter([]),
        { provide: MiniAppSetupApi, useValue: api },
        { provide: TelegramSettingsApi, useValue: telegram },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MiniAppSetupCard);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId('mini-app-loading')).toBeNull();
    });
  }

  it('lists the seven steps in order, each in the state the platform derived', async () => {
    await render({
      status: vi.fn().mockResolvedValue(
        view({
          REGISTER_WEBHOOK: ['TODO', null],
          STOREFRONT_ADDRESS: ['TODO', 'NOT_VERIFIED'],
          TEST_LINK: ['TODO', 'NOT_READY'],
        }),
      ),
    });

    const listed = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid^="mini-app-step-"]'),
    ).map((row) => (row as HTMLElement).dataset['testid']);
    expect(listed).toEqual(CODES.map((code) => `mini-app-step-${code}`));
    expect(byId('mini-app-state-CONNECT_BOT')?.textContent?.trim()).toBe('Done');
    expect(byId('mini-app-state-REGISTER_WEBHOOK')?.textContent?.trim()).toBe('To do');
    expect(byId('mini-app-detail-STOREFRONT_ADDRESS')?.textContent).toContain(
      'claimed but not verified',
    );
  });

  it('says «Recorded», not «Done», for the step only a human in BotFather can do', async () => {
    await render({
      status: vi.fn().mockResolvedValue(view({}, { miniAppShortName: 'order' })),
    });

    expect(byId('mini-app-state-BOTFATHER_APP')?.textContent?.trim()).toBe('Recorded');
    expect(byId('mini-app-state-MENU_BUTTON')?.textContent?.trim()).toBe('Done');
  });

  it('says «Could not check» when Telegram will not answer, rather than done or to do', async () => {
    await render({
      status: vi.fn().mockResolvedValue(view({ MENU_BUTTON: ['UNKNOWN', null] })),
    });

    expect(byId('mini-app-state-MENU_BUTTON')?.textContent?.trim()).toBe('Could not check');
  });

  it('prints the exact BotFather words with the bot and the storefront address filled in', async () => {
    await render({
      status: vi
        .fn()
        .mockResolvedValue(
          view({ BOTFATHER_APP: ['TODO', null], MENU_BUTTON: ['TODO', 'NOT_READY'] }),
        ),
    });

    const steps = byId('mini-app-botfather')?.textContent ?? '';
    expect(steps).toContain('send /newapp');
    expect(steps).toContain('@tandir_bot');
    expect(byId('mini-app-botfather-url')?.textContent).toContain('https://shop.example.uz/');
  });

  it('asks for the address first, and shows no BotFather steps, while there is no verified storefront', async () => {
    await render({
      status: vi
        .fn()
        .mockResolvedValue(
          view(
            { STOREFRONT_ADDRESS: ['TODO', 'NOT_CLAIMED'], BOTFATHER_APP: ['TODO', null] },
            { miniAppUrl: null },
          ),
        ),
    });

    expect(byId('mini-app-botfather')).toBeNull();
    expect(byId('mini-app-needs-address')).not.toBeNull();
  });

  it('records the short name on the Telegram channel, changing only the short name and the Mini App switch', async () => {
    const status = vi
      .fn()
      .mockResolvedValueOnce(view({ BOTFATHER_APP: ['TODO', null] }))
      .mockResolvedValueOnce(view({}, { miniAppShortName: 'order' }));
    const read = vi.fn().mockResolvedValue(settings());
    const save = vi
      .fn<(...args: unknown[]) => Promise<TelegramSettingsView>>()
      .mockResolvedValue(settings({ channelVersion: 8 }));
    await render({ status }, { read, save });

    const input = byId('mini-app-short-name') as HTMLInputElement;
    input.value = ' order ';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (byId('mini-app-record-short-name') as HTMLButtonElement).click();

    await vi.waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    const [tenant, channelId, request, expectedVersion] = save.mock.calls[0] as [
      { tenantId: string },
      string,
      TelegramSettingsRequest,
      number,
    ];
    expect(tenant).toEqual({ tenantId: 't1' });
    expect(channelId).toBe('chan-1');
    expect(expectedVersion).toBe(7);
    expect(request).toEqual({
      brandId: 'b1',
      miniAppEnabled: true,
      miniAppShortName: 'order',
      publicOfferLink: true,
      menuButtonLabels: { en: 'Order' },
      welcomeTexts: { en: 'Hello' },
    });
    await vi.waitFor(() => expect(status).toHaveBeenCalledTimes(2));
  });

  it('will not record a short name BotFather would not have given, or one already recorded', async () => {
    await render(
      { status: vi.fn().mockResolvedValue(view({ BOTFATHER_APP: ['TODO', null] })) },
      { read: vi.fn(), save: vi.fn() },
    );

    const input = byId('mini-app-short-name') as HTMLInputElement;
    input.value = 'has space';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect((byId('mini-app-record-short-name') as HTMLButtonElement).disabled).toBe(true);
  });

  it('sets the menu button and reflects what Telegram reads back', async () => {
    const applyMenuButton = vi
      .fn()
      .mockResolvedValue(
        view({}, { miniAppShortName: 'order', menuButtonUrl: 'https://shop.example.uz/' }),
      );
    await render({
      status: vi
        .fn()
        .mockResolvedValue(
          view({ MENU_BUTTON: ['TODO', 'NOT_APPLIED'] }, { miniAppShortName: 'order' }),
        ),
      applyMenuButton,
    });

    (byId('mini-app-apply-menu-button') as HTMLButtonElement).click();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId('mini-app-state-MENU_BUTTON')?.textContent?.trim()).toBe('Done');
    });
    expect(applyMenuButton).toHaveBeenCalledWith(SCOPE);
    expect(byId('mini-app-not-shown')).toBeNull();
  });

  it('does not claim the button is set when Telegram accepted the call and still shows another', async () => {
    const applyMenuButton = vi
      .fn()
      .mockResolvedValue(
        view({ MENU_BUTTON: ['TODO', 'NOT_APPLIED'] }, { miniAppShortName: 'order' }),
      );
    await render({
      status: vi
        .fn()
        .mockResolvedValue(
          view({ MENU_BUTTON: ['TODO', 'NOT_APPLIED'] }, { miniAppShortName: 'order' }),
        ),
      applyMenuButton,
    });

    (byId('mini-app-apply-menu-button') as HTMLButtonElement).click();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId('mini-app-not-shown')).not.toBeNull();
    });
    expect(byId('mini-app-state-MENU_BUTTON')?.textContent?.trim()).toBe('To do');
  });

  it('offers to take the button off while Telegram still shows a Mini App the setup no longer stands behind', async () => {
    const applyMenuButton = vi
      .fn()
      .mockResolvedValue(
        view({ MENU_BUTTON: ['TODO', 'MINI_APP_OFF'] }, { miniAppShortName: 'order' }),
      );
    await render({
      status: vi
        .fn()
        .mockResolvedValue(
          view(
            { MENU_BUTTON: ['TODO', 'STALE_BUTTON'] },
            { miniAppShortName: 'order', menuButtonUrl: 'https://shop.example.uz/' },
          ),
        ),
      applyMenuButton,
    });

    const button = byId('mini-app-apply-menu-button') as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    expect(button.textContent?.trim()).toBe('Take the menu button off');
    expect(byId('mini-app-detail-MENU_BUTTON')?.textContent).toContain('Take it off');

    button.click();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(applyMenuButton).toHaveBeenCalledWith(SCOPE);
      expect(byId('mini-app-detail-MENU_BUTTON')?.textContent).toContain('switched off');
    });
    expect(byId('mini-app-not-shown')).toBeNull();
    expect((byId('mini-app-apply-menu-button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('says so when Telegram accepted taking the button off and still shows it', async () => {
    const stale = view(
      { MENU_BUTTON: ['TODO', 'STALE_BUTTON'] },
      { miniAppShortName: 'order', menuButtonUrl: 'https://shop.example.uz/' },
    );
    await render({
      status: vi.fn().mockResolvedValue(stale),
      applyMenuButton: vi.fn().mockResolvedValue(stale),
    });

    (byId('mini-app-apply-menu-button') as HTMLButtonElement).click();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId('mini-app-not-shown')).not.toBeNull();
    });
  });

  it('keeps the button off until the earlier steps are done', async () => {
    await render({
      status: vi.fn().mockResolvedValue(view({ MENU_BUTTON: ['TODO', 'NOT_READY'] })),
      applyMenuButton: vi.fn(),
    });

    expect((byId('mini-app-apply-menu-button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the test link once the bot and the short name are known', async () => {
    await render({
      status: vi
        .fn()
        .mockResolvedValue(
          view({ TEST_LINK: ['TODO', null] }, { testLink: 'https://t.me/tandir_bot/order' }),
        ),
    });

    expect(byId('mini-app-test-link')?.textContent).toContain('https://t.me/tandir_bot/order');
  });

  it('shows the operator without the right to manage integrations a plain sentence, not a broken card', async () => {
    await render({
      status: vi.fn().mockRejectedValue(new ApiError('FORBIDDEN', 403, null, null)),
    });

    expect(byId('mini-app-denied')).not.toBeNull();
    expect(byId('mini-app-step-CONNECT_BOT')).toBeNull();
  });
});
