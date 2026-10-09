import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { promptPolicy } from './feedback-fixtures.testing';
import { FeedbackSettingsApi, PromptPolicy } from './feedback-settings-api';
import { PromptPolicyPanel } from './prompt-policy-panel';

const SCOPE = { tenantId: 'tenant-1', brandId: 'brand-1' };

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('PromptPolicyPanel', () => {
  let fixture: ComponentFixture<PromptPolicyPanel>;
  let api: { promptPolicy: ReturnType<typeof vi.fn>; savePromptPolicy: ReturnType<typeof vi.fn> };

  async function render(
    policy: PromptPolicy | (() => Promise<PromptPolicy>) = promptPolicy(),
    options: { target?: 'TENANT' | 'BRAND'; canWrite?: boolean } = {},
  ): Promise<void> {
    api = {
      promptPolicy: vi.fn(typeof policy === 'function' ? policy : async () => policy),
      savePromptPolicy: vi.fn(async () => promptPolicy({ isDefault: false, versionAtScope: 1 })),
    };
    await TestBed.configureTestingModule({
      imports: [PromptPolicyPanel],
      providers: [{ provide: FeedbackSettingsApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PromptPolicyPanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('target', options.target ?? 'TENANT');
    fixture.componentRef.setInput('canWrite', options.canWrite ?? true);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function el<T extends HTMLElement>(testId: string): T {
    const found = host().querySelector(`[data-testid="${testId}"]`);
    expect(found, testId).not.toBeNull();
    return found as T;
  }

  function type(testId: string, value: string): void {
    const input = el<HTMLInputElement>(testId);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function save(): Promise<void> {
    el('prompt-save').click();
    await flush();
    fixture.detectChanges();
  }

  it('shows the built-in default as the default and says nothing has been saved', async () => {
    await render();

    expect(el('prompt-provenance').textContent).toContain('built-in default applies');
    expect(el<HTMLInputElement>('prompt-window-from').value).toBe('09:00');
    expect(el<HTMLInputElement>('prompt-window-to').value).toBe('21:00');
    expect(el<HTMLInputElement>('prompt-inline-days').value).toBe('7');
    expect(host().querySelectorAll('[data-testid="prompt-row"]')).toHaveLength(2);
    expect(api.promptPolicy).toHaveBeenCalledWith({ kind: 'TENANT', tenantId: 'tenant-1' });
  });

  it('shows each row with its own order type and channel, not the first option of each list', async () => {
    await render(
      promptPolicy({
        rows: [
          {
            fulfillmentMode: 'DINE_IN',
            channel: 'PUSH',
            enabled: true,
            delayMinutes: 5,
            giveUpAfterHours: 2,
          },
        ],
      }),
    );

    expect(el<HTMLSelectElement>('prompt-row-mode').value).toBe('DINE_IN');
    expect(el<HTMLSelectElement>('prompt-row-channel').value).toBe('PUSH');
    expect(el<HTMLInputElement>('prompt-row-enabled').checked).toBe(true);
  });

  it('will not save without a reason', async () => {
    await render();

    expect(el<HTMLButtonElement>('prompt-save').disabled).toBe(true);
    type('prompt-reason', 'ask later');
    expect(el<HTMLButtonElement>('prompt-save').disabled).toBe(false);
  });

  it('saves the whole document against the version authored at this level and says it saved', async () => {
    await render(promptPolicy({ versionAtScope: 2, isDefault: false, winningScope: 'TENANT' }));

    type('prompt-window-from', '08:00');
    type('prompt-window-to', '22:00');
    type('prompt-inline-days', '14');
    type('prompt-reason', 'start with delivery SMS');
    await save();

    expect(api.savePromptPolicy).toHaveBeenCalledWith(
      { kind: 'TENANT', tenantId: 'tenant-1' },
      {
        sendWindow: { start: '08:00', end: '22:00' },
        inlineWindowDays: 14,
        rows: promptPolicy().rows,
        reason: 'start with delivery SMS',
      },
      2,
    );
    expect(el('prompt-saved').textContent).toContain('Saved.');
  });

  it('sends no window when the prompt may go out at any hour', async () => {
    await render();

    el('prompt-window-any').click();
    fixture.detectChanges();
    type('prompt-reason', 'any hour');
    await save();

    expect(api.savePromptPolicy.mock.calls[0][1].sendWindow).toBeNull();
  });

  it('adds a row, edits it and removes another', async () => {
    await render();

    el('prompt-row-add').click();
    fixture.detectChanges();
    const rows = host().querySelectorAll('[data-testid="prompt-row"]');
    expect(rows).toHaveLength(3);

    const mode = rows[2].querySelector('[data-testid="prompt-row-mode"]') as HTMLSelectElement;
    mode.value = 'DELIVERY';
    mode.dispatchEvent(new Event('change'));
    const delay = rows[2].querySelector('[data-testid="prompt-row-delay"]') as HTMLInputElement;
    delay.value = '120';
    delay.dispatchEvent(new Event('input'));
    (rows[0].querySelector('[data-testid="prompt-row-remove"]') as HTMLElement).click();
    fixture.detectChanges();
    type('prompt-reason', 'delivery gets two hours');
    await save();

    const sent = api.savePromptPolicy.mock.calls[0][1].rows;
    expect(sent).toHaveLength(2);
    expect(sent[1]).toEqual({
      fulfillmentMode: 'DELIVERY',
      channel: 'SMS',
      enabled: true,
      delayMinutes: 120,
      giveUpAfterHours: 6,
    });
  });

  it('refuses a delay over a day, a give-up of zero and a window that ends before it starts', async () => {
    await render();
    type('prompt-reason', 'x');

    const delay = host().querySelector('[data-testid="prompt-row-delay"]') as HTMLInputElement;
    delay.value = '1441';
    delay.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el<HTMLButtonElement>('prompt-save').disabled).toBe(true);
    expect(el('prompt-invalid').textContent).toContain('Check the numbers');

    delay.value = '30';
    delay.dispatchEvent(new Event('input'));
    const giveUp = host().querySelector('[data-testid="prompt-row-give-up"]') as HTMLInputElement;
    giveUp.value = '0';
    giveUp.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el<HTMLButtonElement>('prompt-save').disabled).toBe(true);

    giveUp.value = '6';
    giveUp.dispatchEvent(new Event('input'));
    type('prompt-window-from', '21:00');
    type('prompt-window-to', '09:00');
    expect(el<HTMLButtonElement>('prompt-save').disabled).toBe(true);

    type('prompt-window-from', '09:00');
    type('prompt-window-to', '21:00');
    expect(el<HTMLButtonElement>('prompt-save').disabled).toBe(false);
  });

  it('says a brand follows the company until it saves its own, and saves at the brand when it does', async () => {
    await render(promptPolicy({ isDefault: false, winningScope: 'TENANT', versionAtScope: 0 }), {
      target: 'BRAND',
    });

    expect(api.promptPolicy).toHaveBeenCalledWith({ kind: 'BRAND', scope: SCOPE });
    expect(el('prompt-provenance').textContent).toContain('follows the company');

    type('prompt-reason', 'ask within three days');
    await save();

    expect(api.savePromptPolicy.mock.calls[0][0]).toEqual({ kind: 'BRAND', scope: SCOPE });
    expect(api.savePromptPolicy.mock.calls[0][2]).toBe(0);
  });

  it('reloads and says so when someone saved first', async () => {
    await render();
    type('prompt-reason', 'x');
    api.savePromptPolicy.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'c'),
    );
    api.promptPolicy.mockClear();

    await save();

    expect(el('prompt-error').textContent).toContain('Someone else saved first');
    expect(api.promptPolicy).toHaveBeenCalled();
  });

  it('is read-only for someone who may not write: fields disabled, no save, no reason', async () => {
    await render(promptPolicy(), { canWrite: false });

    expect(host().querySelector('[data-testid="prompt-save"]')).toBeNull();
    expect(host().querySelector('[data-testid="prompt-reason"]')).toBeNull();
    expect(el<HTMLInputElement>('prompt-inline-days').disabled).toBe(true);
    expect(host().querySelector('[data-testid="prompt-row-add"]')).toBeNull();
    expect(host().querySelector('[data-testid="prompt-row-remove"]')).toBeNull();
  });

  it('shows the denied state on a refusal', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });

    expect(host().textContent).toContain('You do not have access to these settings.');
  });
});

beforeEach(() => TestBed.resetTestingModule());
