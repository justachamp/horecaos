import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { ConfigurationApi } from '../configuration-api';
import { SavedTarget } from '../settings-saved';
import { SettingsScope } from '../settings-scope';
import { AssistantApi, AssistantUsageResponse } from './assistant-api';
import { AssistantSettingsPage, DISCLOSURE_MAXIMUM_CHARACTERS } from './assistant-settings-page';

const TENANT_ID = 'tenant-1';
const BRAND_ID = 'brand-1';

const PLATFORM_RU = 'Я автоматический помощник. Ваш вопрос обрабатывает внешний ИИ-сервис.';

function usage(overrides: Partial<AssistantUsageResponse> = {}): AssistantUsageResponse {
  return {
    month: '2026-10',
    turns: 12,
    answered: 8,
    refused: 2,
    escalated: 1,
    declined: 1,
    servedFromCache: 3,
    costUsdMicros: 250_000,
    ceilingUsdCents: 2_500,
    ceilingReached: false,
    entitled: true,
    switchedOn: false,
    providerConfigured: true,
    publishedKnowledgeEntries: 4,
    defaultDisclosure: {
      ru: PLATFORM_RU,
      uz: 'Men avtomatik yordamchiman.',
      en: 'I’m an automated assistant.',
    },
    ...overrides,
  };
}

function resolution(
  code: string,
  value: unknown,
  overrides: Partial<ConfigurationResolutionView> = {},
): ConfigurationResolutionView {
  return {
    keyCode: code,
    value,
    cameFromDefault: true,
    source: 'CODE_DEFAULT',
    winningScope: null,
    inspectedLevels: [{ scopeType: 'BRAND', outcome: 'NOT_SET' }],
    describe: `${code} -> CODE_DEFAULT`,
    currentVersionAtScope: null,
    ...overrides,
  };
}

const DEFAULTS: Readonly<Record<string, unknown>> = {
  'assistant.enabled': false,
  'assistant.disclosure_text_ru': '',
  'assistant.disclosure_text_uz': '',
  'assistant.disclosure_text_en': '',
};

class FakeSettingsScope {
  readonly brandId = signal<string | null>(BRAND_ID);
  readonly locationId = signal<string | null>(null);
  readonly level = signal<'TENANT' | 'BRAND' | 'LOCATION'>('BRAND');
  readonly denied = signal(false);
  readonly target = signal<SavedTarget>({ level: 'BRAND', name: 'Rayhon' });
  readonly targetFor = vi.fn((level: 'TENANT' | 'BRAND' | 'LOCATION'): SavedTarget =>
    level === 'TENANT' ? { level, name: null } : { level, name: 'Rayhon' },
  );
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

interface Harness {
  readonly fixture: ComponentFixture<AssistantSettingsPage>;
  readonly config: { resolution: ReturnType<typeof vi.fn>; setValue: ReturnType<typeof vi.fn> };
  readonly assistant: { usage: ReturnType<typeof vi.fn> };
  readonly scope: FakeSettingsScope;
  readonly host: HTMLElement;
}

async function render(
  options: {
    usageResult?: AssistantUsageResponse | Error;
    resolutions?: Readonly<Record<string, ConfigurationResolutionView>>;
    resolutionError?: Error;
    level?: 'TENANT' | 'BRAND' | 'LOCATION';
    setValue?: ReturnType<typeof vi.fn>;
  } = {},
): Promise<Harness> {
  const scope = new FakeSettingsScope();
  scope.level.set(options.level ?? 'BRAND');
  const config = {
    resolution: vi.fn().mockImplementation((_tenantId: string, code: string) => {
      if (options.resolutionError) {
        return Promise.reject(options.resolutionError);
      }
      return Promise.resolve(options.resolutions?.[code] ?? resolution(code, DEFAULTS[code]));
    }),
    setValue:
      options.setValue ??
      vi.fn().mockResolvedValue({ id: 'v-1', keyCode: 'x', scopeType: 'BRAND' }),
  };
  const result = options.usageResult ?? usage();
  const assistant = {
    usage: vi
      .fn()
      .mockImplementation(() =>
        result instanceof Error ? Promise.reject(result) : Promise.resolve(result),
      ),
  };
  await TestBed.configureTestingModule({
    imports: [AssistantSettingsPage],
    providers: [
      provideRouter([]),
      { provide: ConfigurationApi, useValue: config },
      { provide: AssistantApi, useValue: assistant },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      { provide: SettingsScope, useValue: scope },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(AssistantSettingsPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, config, assistant, scope, host: fixture.nativeElement as HTMLElement };
}

function text(host: HTMLElement, testId: string): string {
  return (
    host.querySelector(`[data-testid="${testId}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
  );
}

function click(host: HTMLElement, selector: string, index = 0): void {
  (host.querySelectorAll(selector)[index] as HTMLElement).click();
}

function type(host: HTMLElement, selector: string, value: string, event = 'input'): void {
  const element = host.querySelector(selector) as HTMLInputElement | HTMLTextAreaElement;
  element.value = value;
  element.dispatchEvent(new Event(event));
}

function publishButton(host: HTMLElement): HTMLButtonElement {
  return host.querySelector('[data-testid="assistant-save"]') as HTMLButtonElement;
}

describe('AssistantSettingsPage: what it reads', () => {
  it('reads the switch and the three wordings at the bar’s brand, and never asks for the ceiling', async () => {
    const { config } = await render();

    for (const code of Object.keys(DEFAULTS)) {
      expect(config.resolution).toHaveBeenCalledWith(TENANT_ID, code, 'BRAND', BRAND_ID);
    }
    const asked = config.resolution.mock.calls.map((call: unknown[]) => call[1]);
    expect(asked).not.toContain('assistant.monthly_spend_ceiling_usd_cents');
  });

  it('reads the company level, with no brand, when the bar is at company level', async () => {
    const { config } = await render({ level: 'TENANT' });

    expect(config.resolution).toHaveBeenCalledWith(TENANT_ID, 'assistant.enabled', 'TENANT', null);
  });

  it('shows the brand’s setting for a branch, and says so', async () => {
    const { config, host } = await render({ level: 'LOCATION' });

    expect(config.resolution).toHaveBeenCalledWith(
      TENANT_ID,
      'assistant.enabled',
      'BRAND',
      BRAND_ID,
    );
    expect(host.querySelector('[data-testid="assistant-brand-level-note"]')).not.toBeNull();
  });

  it('does not show the brand note when the bar is on the brand', async () => {
    const { host } = await render();

    expect(host.querySelector('[data-testid="assistant-brand-level-note"]')).toBeNull();
  });

  it('says the assistant is off by default', async () => {
    const { host } = await render();

    expect(text(host, 'assistant-switch-card')).toContain('Off');
  });
});

describe('AssistantSettingsPage: the month and the ceiling', () => {
  it('shows the month’s questions, answers and hand-overs (refused and escalated together)', async () => {
    const { host } = await render();

    expect(text(host, 'assistant-usage-turns')).toBe('12');
    expect(text(host, 'assistant-usage-answered')).toBe('8');
    expect(text(host, 'assistant-usage-handed')).toBe('3');
  });

  it('shows spend against the ceiling in dollars, from integer cents, and says the ceiling is not the tenant’s to change', async () => {
    const { host } = await render();

    expect(text(host, 'assistant-usage-spend')).toContain('$0.25 of $25.00');
    expect(text(host, 'assistant-usage-card')).toContain('set by HorecaOS');
    expect(host.querySelector('[data-testid="assistant-ceiling-reached"]')).toBeNull();
  });

  it('rounds a fraction of a cent up, never down, so the room under the ceiling is not overstated', async () => {
    const { host } = await render({ usageResult: usage({ costUsdMicros: 10_001 }) });

    expect(text(host, 'assistant-usage-spend')).toContain('$0.02 of $25.00');
  });

  it('says so when the ceiling is reached', async () => {
    const { host } = await render({
      usageResult: usage({ costUsdMicros: 25_000_000, ceilingReached: true }),
    });

    expect(text(host, 'assistant-ceiling-reached')).toContain('handed to your team');
    expect(text(host, 'assistant-usage-spend')).toContain('$25.00 of $25.00');
  });

  it('links to the notes the assistant answers from, with how many are published', async () => {
    const { host } = await render();

    expect(text(host, 'assistant-usage-card')).toContain('Published notes: 4.');
    expect(host.querySelector('[data-testid="assistant-notes-link"]')).not.toBeNull();
  });

  it('says the role cannot see usage, and still shows the switch and the wording', async () => {
    const { host } = await render({
      usageResult: new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    });

    expect(text(host, 'assistant-usage-denied')).toContain('cannot see');
    expect(host.querySelector('[data-testid="assistant-switch-card"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="assistant-disclosure-card"]')).not.toBeNull();
  });

  it('says no AI service is connected, rather than letting a switched-on assistant look live', async () => {
    const { host } = await render({ usageResult: usage({ providerConfigured: false }) });

    expect(text(host, 'assistant-no-provider')).toContain('No AI service is connected');
  });

  it('says the plan lacks the assistant when it does', async () => {
    const { host } = await render({ usageResult: usage({ entitled: false }) });

    expect(host.querySelector('[data-testid="assistant-not-entitled"]')).not.toBeNull();
  });

  it('shows neither notice when the provider is connected and the plan includes it', async () => {
    const { host } = await render();

    expect(host.querySelector('[data-testid="assistant-no-provider"]')).toBeNull();
    expect(host.querySelector('[data-testid="assistant-not-entitled"]')).toBeNull();
  });
});

describe('AssistantSettingsPage: the switch', () => {
  async function turnOn(harness: Harness, reason = 'Pilot brand'): Promise<void> {
    click(harness.host, '[data-testid="assistant-switch-card"] .field__action');
    harness.fixture.detectChanges();
    const checkbox = harness.host.querySelector(
      '[data-testid="assistant-switch-input"]',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    type(harness.host, '#assistant-reason', reason);
    harness.fixture.detectChanges();
  }

  it('turns the assistant on for the brand with a required reason, at the brand’s own level', async () => {
    const harness = await render();
    await turnOn(harness, '');
    expect(publishButton(harness.host).disabled).toBe(true);

    type(harness.host, '#assistant-reason', 'Pilot brand');
    harness.fixture.detectChanges();
    expect(publishButton(harness.host).disabled).toBe(false);
    publishButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.config.setValue).toHaveBeenCalledWith(TENANT_ID, 'assistant.enabled', {
      scopeType: 'BRAND',
      brandId: BRAND_ID,
      locationId: null,
      explicitNull: false,
      expectedVersion: null,
      reason: 'Pilot brand',
      booleanValue: true,
    });
  });

  it('re-reads the month after a change of the switch, because the report says whether it is on', async () => {
    const harness = await render();
    await turnOn(harness);
    const readsBefore = harness.assistant.usage.mock.calls.length;

    publishButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.assistant.usage.mock.calls.length).toBe(readsBefore + 1);
  });

  it('turns it on for the whole company with no brand when the bar is at company level', async () => {
    const harness = await render({ level: 'TENANT' });
    await turnOn(harness);

    publishButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.config.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'assistant.enabled',
      expect.objectContaining({ scopeType: 'TENANT', brandId: null, booleanValue: true }),
    );
  });

  it('writes the brand, never a branch, when the bar is on a branch', async () => {
    const harness = await render({ level: 'LOCATION' });
    await turnOn(harness);

    publishButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.config.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'assistant.enabled',
      expect.objectContaining({ scopeType: 'BRAND', brandId: BRAND_ID, locationId: null }),
    );
  });

  it('says what leaves the platform before the switch is turned on', async () => {
    const harness = await render();
    click(harness.host, '[data-testid="assistant-switch-card"] .field__action');
    harness.fixture.detectChanges();

    expect(text(harness.host, 'assistant-switch-card')).toContain('outside AI service');
    expect(text(harness.host, 'assistant-switch-card')).toContain('never leave the platform');
  });

  it('passes the version it read, so a concurrent change is refused rather than overwritten', async () => {
    const harness = await render({
      resolutions: {
        'assistant.enabled': resolution('assistant.enabled', true, {
          cameFromDefault: false,
          source: 'SCOPED_VALUE',
          winningScope: 'BRAND',
          inspectedLevels: [{ scopeType: 'BRAND', outcome: 'VALUE', version: 4 }],
          currentVersionAtScope: 4,
        }),
      },
    });

    click(harness.host, '[data-testid="assistant-switch-card"] .field__action');
    harness.fixture.detectChanges();
    type(harness.host, '#assistant-reason', 'Switch it off for the weekend');
    harness.fixture.detectChanges();
    publishButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.config.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'assistant.enabled',
      expect.objectContaining({ expectedVersion: 4 }),
    );
  });

  it('reverts to the inherited value with an explicit null and no typed reason', async () => {
    const harness = await render({
      resolutions: {
        'assistant.enabled': resolution('assistant.enabled', true, {
          cameFromDefault: false,
          source: 'SCOPED_VALUE',
          winningScope: 'BRAND',
          inspectedLevels: [{ scopeType: 'BRAND', outcome: 'VALUE', version: 2 }],
          currentVersionAtScope: 2,
        }),
      },
    });

    click(harness.host, '[data-testid="assistant-switch-card"] .field__action', 1);
    await flushMicrotasks();

    expect(harness.config.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'assistant.enabled',
      expect.objectContaining({ explicitNull: true, expectedVersion: 2, scopeType: 'BRAND' }),
    );
  });

  it('keeps the editor open and says why when the save is refused', async () => {
    const harness = await render({
      setValue: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null)),
    });
    await turnOn(harness);

    publishButton(harness.host).click();
    await flushMicrotasks();
    harness.fixture.detectChanges();

    expect(harness.host.querySelector('[data-testid="assistant-save-error"]')).not.toBeNull();
    expect(harness.host.querySelector('[data-testid="assistant-switch-input"]')).not.toBeNull();
  });
});

describe('AssistantSettingsPage: what customers are told first', () => {
  it('lists the three languages, with the platform’s wording beside each so a blank is not a mystery', async () => {
    const { host } = await render();

    for (const locale of ['ru', 'uz', 'en']) {
      expect(host.querySelector(`[data-testid="assistant-disclosure-${locale}"]`)).not.toBeNull();
    }
    expect(text(host, 'assistant-disclosure-ru')).toContain(PLATFORM_RU);
    expect(text(host, 'assistant-disclosure-ru')).toContain('HorecaOS’s default wording');
  });

  it('shows the tenant’s own sentence once it has written one', async () => {
    const { host } = await render({
      resolutions: {
        'assistant.disclosure_text_ru': resolution(
          'assistant.disclosure_text_ru',
          'Вам отвечает робот сети.',
          { cameFromDefault: false, source: 'SCOPED_VALUE', winningScope: 'BRAND' },
        ),
      },
    });

    expect(text(host, 'assistant-disclosure-ru')).toContain('Вам отвечает робот сети.');
  });

  it('saves the sentence trimmed, as a string, with a reason, at the brand', async () => {
    const harness = await render();
    click(harness.host, '[data-testid="assistant-disclosure-ru"] .field__action');
    harness.fixture.detectChanges();

    type(
      harness.host,
      '[data-testid="assistant-disclosure-input"]',
      '  Вам отвечает робот сети.  ',
    );
    type(harness.host, '#assistant-reason', 'Our own wording');
    harness.fixture.detectChanges();
    publishButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.config.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'assistant.disclosure_text_ru',
      {
        scopeType: 'BRAND',
        brandId: BRAND_ID,
        locationId: null,
        explicitNull: false,
        expectedVersion: null,
        reason: 'Our own wording',
        stringValue: 'Вам отвечает робот сети.',
      },
    );
  });

  it('counts the characters and refuses to save past the limit the platform enforces', async () => {
    const harness = await render();
    click(harness.host, '[data-testid="assistant-disclosure-en"] .field__action');
    harness.fixture.detectChanges();

    type(
      harness.host,
      '[data-testid="assistant-disclosure-input"]',
      'x'.repeat(DISCLOSURE_MAXIMUM_CHARACTERS),
    );
    type(harness.host, '#assistant-reason', 'Long but allowed');
    harness.fixture.detectChanges();
    expect(publishButton(harness.host).disabled).toBe(false);
    expect(harness.host.textContent).toContain('500 of 500 characters');

    type(
      harness.host,
      '[data-testid="assistant-disclosure-input"]',
      'x'.repeat(DISCLOSURE_MAXIMUM_CHARACTERS + 1),
    );
    harness.fixture.detectChanges();
    expect(publishButton(harness.host).disabled).toBe(true);
    expect(harness.host.querySelector('.counter--over')).not.toBeNull();
  });

  it('shows the platform’s wording beside the box while the tenant writes its own', async () => {
    const harness = await render();
    click(harness.host, '[data-testid="assistant-disclosure-ru"] .field__action');
    harness.fixture.detectChanges();

    expect(text(harness.host, 'assistant-disclosure-ru')).toContain(
      `Default wording: ${PLATFORM_RU}`,
    );
  });

  it('saves a blank sentence as blank, which keeps the default and never switches the sentence off', async () => {
    const harness = await render({
      resolutions: {
        'assistant.disclosure_text_uz': resolution('assistant.disclosure_text_uz', 'Salom', {
          cameFromDefault: false,
          source: 'SCOPED_VALUE',
          winningScope: 'BRAND',
          inspectedLevels: [{ scopeType: 'BRAND', outcome: 'VALUE', version: 1 }],
          currentVersionAtScope: 1,
        }),
      },
    });
    click(harness.host, '[data-testid="assistant-disclosure-uz"] .field__action');
    harness.fixture.detectChanges();

    type(harness.host, '[data-testid="assistant-disclosure-input"]', '');
    type(harness.host, '#assistant-reason', 'Back to the default');
    harness.fixture.detectChanges();
    publishButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.config.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'assistant.disclosure_text_uz',
      expect.objectContaining({ stringValue: '', expectedVersion: 1 }),
    );
  });
});

describe('AssistantSettingsPage: refused reads', () => {
  it('says so, once, when the configuration cannot be read, and shows no switch', async () => {
    const { host } = await render({
      resolutionError: new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    });

    expect(host.querySelector('.denied')).not.toBeNull();
    expect(host.querySelector('[data-testid="assistant-switch-card"]')).toBeNull();
  });
});
