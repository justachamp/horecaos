import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { ChallengeState, HandoverVerification, OrderHandoverApi } from './order-handover-api';
import { OrderHandoverPanel } from './order-handover-panel';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function pending(overrides: Partial<ChallengeState> = {}): ChallengeState {
  return {
    id: 'c1',
    type: 'CODE',
    status: 'PENDING',
    attempts: 1,
    maxAttempts: 5,
    attemptsRemaining: 4,
    ...overrides,
  };
}

function configure(options: { handoverApi?: Partial<OrderHandoverApi> }): void {
  TestBed.configureTestingModule({
    providers: [{ provide: OrderHandoverApi, useValue: options.handoverApi ?? {} }],
  });
  TestBed.inject(I18n).setLocale('en');
}

async function render() {
  const fixture = TestBed.createComponent(OrderHandoverPanel);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('orderId', 'order-1');
  fixture.detectChanges();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return fixture;
}

describe('OrderHandoverPanel: reads the challenge state (row 1.2m)', () => {
  it('renders nothing to act on when the order was never issued a challenge', async () => {
    configure({ handoverApi: { challenge: () => of(null) } });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-handover-none"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="order-handover-status"]')).toBeNull();
  });

  it('renders the type, status and remaining attempts of a pending challenge', async () => {
    configure({ handoverApi: { challenge: () => of(pending()) } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-handover-type"]')?.textContent).toContain(
      'Code',
    );
    expect(host.querySelector('[data-testid="order-handover-status"]')?.textContent).toContain(
      'Awaiting verification',
    );
    expect(host.querySelector('[data-testid="order-handover-attempts"]')?.textContent).toContain(
      '4',
    );
  });

  it('shows an error state rather than crashing when the read fails', async () => {
    configure({
      handoverApi: {
        challenge: () =>
          throwError(() => new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null)),
      },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-handover-error"]'),
    ).not.toBeNull();
  });

  it('never shows a verify or bypass control once the challenge is settled', async () => {
    configure({ handoverApi: { challenge: () => of(pending({ status: 'VERIFIED' })) } });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-handover-code-input"]'),
    ).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-handover-bypass-toggle"]'),
    ).toBeNull();
  });
});

describe('OrderHandoverPanel: verifying a code consumes one attempt (ADR 0040)', () => {
  it('submits the entered code and shows the updated attempts remaining on a wrong guess', async () => {
    const verify = vi
      .fn()
      .mockReturnValue(
        of<HandoverVerification>({ verified: false, status: 'PENDING', attemptsRemaining: 3 }),
      );
    configure({ handoverApi: { challenge: () => of(pending()), verify } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    const input = host.querySelector(
      '[data-testid="order-handover-code-input"]',
    ) as HTMLInputElement;
    input.value = '1234';
    input.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="order-handover-verify"]') as HTMLButtonElement).click();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(verify).toHaveBeenCalledWith(SCOPE, 'order-1', '1234');
    expect(host.querySelector('[data-testid="order-handover-attempts"]')?.textContent).toContain(
      '3',
    );
    expect(host.querySelector('[data-testid="order-handover-notice"]')?.textContent).toContain(
      'Wrong code',
    );
  });

  it('never renders the expected value — the response never carries one, and neither does the panel', async () => {
    const verify = vi
      .fn()
      .mockReturnValue(
        of<HandoverVerification>({ verified: true, status: 'VERIFIED', attemptsRemaining: 4 }),
      );
    configure({ handoverApi: { challenge: () => of(pending()), verify } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    const input = host.querySelector(
      '[data-testid="order-handover-code-input"]',
    ) as HTMLInputElement;
    input.value = '4417';
    input.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="order-handover-verify"]') as HTMLButtonElement).click();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-handover-status"]')?.textContent).toContain(
      'Verified',
    );
  });
});

describe('OrderHandoverPanel: the supervisor override (ADR 0040 bypass)', () => {
  it('is offered while pending and refuses to submit without both fields', async () => {
    configure({ handoverApi: { challenge: () => of(pending()) } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-handover-bypass-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="order-handover-bypass-form"]')).not.toBeNull();
  });

  it('is still offered once attempts are exhausted', async () => {
    configure({
      handoverApi: { challenge: () => of(pending({ status: 'FAILED', attemptsRemaining: 0 })) },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-handover-bypass-toggle"]'),
    ).not.toBeNull();
  });

  it('submits the reason and supervisor name, then re-reads the challenge', async () => {
    const bypass = vi.fn().mockReturnValue(of(undefined));
    const challenge = vi
      .fn()
      .mockReturnValueOnce(of(pending({ status: 'FAILED', attemptsRemaining: 0 })))
      .mockReturnValueOnce(of(pending({ status: 'BYPASSED' })));
    configure({ handoverApi: { challenge, bypass } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-handover-bypass-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-handover-bypass-reason"]') as HTMLInputElement).value =
      'COURIER_APP_OFFLINE';
    host
      .querySelector('[data-testid="order-handover-bypass-reason"]')
      ?.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="order-handover-bypass-supervisor"]') as HTMLInputElement
    ).value = 'Aziza Karimova';
    host
      .querySelector('[data-testid="order-handover-bypass-supervisor"]')
      ?.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="order-handover-bypass-confirm"]') as HTMLButtonElement
    ).click();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(bypass).toHaveBeenCalledWith(SCOPE, 'order-1', 'COURIER_APP_OFFLINE', 'Aziza Karimova');
    expect(challenge).toHaveBeenCalledTimes(2);
  });
});
