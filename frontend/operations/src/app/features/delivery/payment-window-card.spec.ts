import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { DispatchRulesApi, DispatchScope, PaymentWindowView } from './dispatch-rules-api';
import { PaymentWindowCard } from './payment-window-card';

const SCOPE: DispatchScope = { tenantId: 't1' };

const DEFAULT: PaymentWindowView = {
  windowMinutes: 30,
  action: 'FLAG_ONLY',
  isDefault: true,
  winningScope: null,
  policyId: null,
  policyVersion: 0,
  versionAtScope: 0,
  levels: [],
};

async function render(
  view: PaymentWindowView = DEFAULT,
  canWrite = true,
  publish = vi.fn().mockResolvedValue({
    ...DEFAULT,
    isDefault: false,
    windowMinutes: 45,
    policyVersion: 1,
    versionAtScope: 1,
  }),
): Promise<{
  fixture: ComponentFixture<PaymentWindowCard>;
  host: HTMLElement;
  publish: ReturnType<typeof vi.fn>;
}> {
  const api = {
    paymentWindow: vi.fn().mockResolvedValue({ value: view, version: view.versionAtScope }),
    publishPaymentWindow: publish,
  };
  TestBed.configureTestingModule({ providers: [{ provide: DispatchRulesApi, useValue: api }] });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(PaymentWindowCard);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('canWrite', canWrite);
  fixture.detectChanges();
  await settle(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, publish };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  await fixture.whenStable();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function control<T extends HTMLElement>(host: HTMLElement, testId: string): T {
  const found = host.querySelector<T>(`[data-testid="${testId}"]`);
  if (!found) {
    throw new Error(`No control ${testId}`);
  }
  return found;
}

function type(host: HTMLElement, testId: string, value: string, event: string): void {
  const input = control<HTMLInputElement>(host, testId);
  input.value = value;
  input.dispatchEvent(new Event(event));
}

describe('PaymentWindowCard (ADR 0142 Decision 7: the unpaid-order window)', () => {
  it('shows the system’s own limit when nothing is published', async () => {
    const { host } = await render();

    expect(control<HTMLInputElement>(host, 'window-minutes').value).toBe('30');
    expect(control(host, 'window-default').textContent).toContain('system’s own limit');
  });

  it('offers only "flag the order": cancelling is shown and cannot be chosen, with the reason', async () => {
    const { host } = await render();

    const action = control<HTMLSelectElement>(host, 'window-action');
    expect(action.disabled).toBe(true);
    const cancel = [...action.options].find((option) => option.value === 'CANCEL');
    expect(cancel?.disabled).toBe(true);
    expect(control(host, 'window-cancel-locked').textContent).toContain(
      'not available until product decides',
    );
  });

  it('publishes the window as flag-only, with the version the read returned', async () => {
    const { fixture, host, publish } = await render({
      ...DEFAULT,
      isDefault: false,
      versionAtScope: 2,
      policyVersion: 2,
    });

    type(host, 'window-minutes', '45', 'change');
    type(host, 'window-reason', 'Bank transfers take longer', 'input');
    fixture.detectChanges();
    control<HTMLButtonElement>(host, 'window-publish').click();
    await settle(fixture);

    expect(publish).toHaveBeenCalledWith(
      SCOPE,
      { windowMinutes: 45, action: 'FLAG_ONLY' },
      'Bank transfers take longer',
      2,
    );
    expect(control(host, 'window-notice').textContent).toContain('Published as version 1');
  });

  it('cannot publish unchanged, without a reason, or with a window below a minute', async () => {
    const { fixture, host } = await render();
    const button = (): HTMLButtonElement => control<HTMLButtonElement>(host, 'window-publish');

    type(host, 'window-reason', 'Because', 'input');
    fixture.detectChanges();
    expect(button().disabled).toBe(true);

    type(host, 'window-minutes', '0', 'change');
    fixture.detectChanges();
    expect(button().disabled).toBe(true);

    type(host, 'window-minutes', '20', 'change');
    type(host, 'window-reason', '', 'input');
    fixture.detectChanges();
    expect(button().disabled).toBe(true);

    type(host, 'window-reason', 'Shorter wait', 'input');
    fixture.detectChanges();
    expect(button().disabled).toBe(false);
  });

  it('shows the server’s refusal and no success when the write fails', async () => {
    const refused = new ApiError(
      'VALIDATION_FAILED',
      400,
      { status: 400, detail: 'Cancelling an unpaid order is not available yet' },
      null,
    );
    const { fixture, host } = await render(DEFAULT, true, vi.fn().mockRejectedValue(refused));

    type(host, 'window-minutes', '20', 'change');
    type(host, 'window-reason', 'Shorter wait', 'input');
    fixture.detectChanges();
    control<HTMLButtonElement>(host, 'window-publish').click();
    await settle(fixture);

    expect(control(host, 'window-error').textContent).toContain('not available yet');
    expect(host.querySelector('[data-testid="window-notice"]')).toBeNull();
  });

  it('is read-only for someone without the grant to manage it', async () => {
    const { host } = await render(DEFAULT, false);

    expect(control<HTMLInputElement>(host, 'window-minutes').disabled).toBe(true);
    expect(host.querySelector('[data-testid="window-publish"]')).toBeNull();
  });
});
