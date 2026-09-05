import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { ApiError } from '../../core/api/problem-details';
import { CustomersApi } from '../customers/customers-api';
import { CallCentreApi, CallLogEntry, PresenceView, ScreenPopCard } from './call-centre-api';
import { CallCentrePage } from './call-centre-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function presence(overrides: Partial<PresenceView> = {}): PresenceView {
  return {
    operatorPrincipalId: 'op-1',
    state: 'OFFLINE',
    reason: null,
    changedAt: new Date().toISOString(),
    version: 1,
    ...overrides,
  };
}

function emptyCard(): ScreenPopCard {
  return {
    ringing: false,
    callEventId: null,
    lineDid: null,
    maskedCallerNumber: null,
    occurredAt: null,
    unknownCaller: false,
    customerAccountId: null,
    customerDisplayName: null,
    recentOrders: [],
    acknowledgedBy: null,
  };
}

function ringingCard(overrides: Partial<ScreenPopCard> = {}): ScreenPopCard {
  return {
    ...emptyCard(),
    ringing: true,
    callEventId: 'call-1',
    maskedCallerNumber: '•••••••••4567',
    occurredAt: new Date().toISOString(),
    unknownCaller: true,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CallCentrePage', () => {
  let fixture: ComponentFixture<CallCentrePage>;

  async function render(
    api: Partial<CallCentreApi>,
    locationOverrides: { scope?: LocationScope | null; denied?: boolean } = {},
    customersApi: Partial<CustomersApi> = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [CallCentrePage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(
              'scope' in locationOverrides ? (locationOverrides.scope ?? null) : SCOPE,
            ),
            denied: signal(locationOverrides.denied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CallCentreApi, useValue: api },
        { provide: CustomersApi, useValue: customersApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CallCentrePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function fakeApi(overrides: Partial<CallCentreApi> = {}): Partial<CallCentreApi> {
    return {
      myPresence: () => Promise.resolve(presence()),
      callLog: () => Promise.resolve([]),
      currentCall: () => Promise.resolve(emptyCard()),
      ...overrides,
    };
  }

  it('shows the denied state when the location grant is missing', async () => {
    await render(fakeApi(), { scope: null, denied: true });

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="call-centre-denied"]'),
    ).not.toBeNull();
  });

  it('shows the empty screen-pop state when nothing is ringing', async () => {
    await render(fakeApi());

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="call-centre-screen-pop-empty"]')).not.toBeNull();
  });

  it('renders the ringing card for an unknown caller with a create-customer action', async () => {
    await render(fakeApi({ currentCall: () => Promise.resolve(ringingCard()) }));

    const host = fixture.nativeElement as HTMLElement;
    const card = host.querySelector('[data-testid="call-centre-screen-pop-card"]');
    expect(card).not.toBeNull();
    expect(card?.textContent).toContain('•••••••••4567');
    expect(host.querySelector('[data-testid="call-centre-create-customer"]')).not.toBeNull();
  });

  it('renders a known caller with their recent orders and no create-customer action', async () => {
    await render(
      fakeApi({
        currentCall: () =>
          Promise.resolve(
            ringingCard({
              unknownCaller: false,
              customerAccountId: 'acc-1',
              customerDisplayName: 'Alisher',
              recentOrders: [
                {
                  orderId: 'order-1',
                  publicOrderNumber: 'A-1001',
                  locationId: 'l1',
                  status: 'COMPLETED',
                  currency: 'UZS',
                  totalMinor: 45_000,
                  placedAt: new Date().toISOString(),
                },
              ],
            }),
          ),
      }),
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="call-centre-screen-pop-card"]')?.textContent).toContain('Alisher');
    expect(host.textContent).toContain('A-1001');
    expect(host.querySelector('[data-testid="call-centre-create-customer"]')).toBeNull();
  });

  it('claims a ringing call and refreshes the card', async () => {
    const acknowledge = vi.fn().mockResolvedValue(undefined);
    let served = ringingCard();
    await render(
      fakeApi({
        currentCall: () => Promise.resolve(served),
        acknowledge: (...args) => {
          served = ringingCard({ acknowledgedBy: 'op-1' });
          return acknowledge(...args);
        },
      }),
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="call-centre-claim"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(acknowledge).toHaveBeenCalledWith(SCOPE, 'call-1');
    expect(host.querySelector('[data-testid="call-centre-claimed-by"]')?.textContent).toContain('op-1');
  });

  it('refuses to pause without a reason', async () => {
    const setPresence = vi.fn();
    await render(fakeApi({ setPresence }));

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="call-centre-presence-PAUSED"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(setPresence).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="call-centre-presence-error"]')).not.toBeNull();
  });

  it('pauses with a reason', async () => {
    const setPresence = vi.fn().mockResolvedValue(presence({ state: 'PAUSED', reason: 'Lunch break' }));
    await render(fakeApi({ setPresence }));

    const host = fixture.nativeElement as HTMLElement;
    const reasonField = host.querySelector('[data-testid="call-centre-presence-reason"]') as HTMLInputElement;
    reasonField.value = 'Lunch break';
    reasonField.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="call-centre-presence-PAUSED"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(setPresence).toHaveBeenCalledWith(SCOPE, 'PAUSED', 'Lunch break');
    expect(host.querySelector('[data-testid="call-centre-presence-current"]')?.textContent).toContain('Lunch break');
  });

  it('renders the call log', async () => {
    const entry: CallLogEntry = {
      callEventId: 'call-9',
      providerCallId: 'p-9',
      eventType: 'MISSED',
      direction: 'INBOUND',
      lineDid: '+998712001234',
      operatorPrincipalId: null,
      durationSeconds: null,
      occurredAt: new Date().toISOString(),
    };
    await render(fakeApi({ callLog: () => Promise.resolve([entry]) }));

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="call-centre-log"]')?.textContent).toContain('Missed');
  });

  it('reveals the real number and creates a customer from an unknown caller', async () => {
    const revealCallerNumber = vi.fn().mockResolvedValue('+998901234567');
    const create = vi.fn().mockResolvedValue('acc-new');
    await render(
      fakeApi({ currentCall: () => Promise.resolve(ringingCard()), revealCallerNumber }),
      {},
      { create },
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="call-centre-create-customer"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    // The dialog must be prefilled with the real number, never the masked
    // display value — a mask could never be typed into a real contact point.
    const dialog = host.querySelector('[data-testid="create-customer-dialog"]');
    expect(dialog).not.toBeNull();
    const phoneField = dialog?.querySelector('[data-testid="create-customer-phone"]') as HTMLInputElement | undefined;
    expect(phoneField?.value).toBe('+998901234567');

    (dialog?.querySelector('[data-testid="create-customer-confirm"]') as HTMLButtonElement)?.click();
    await flushMicrotasks();

    expect(create).toHaveBeenCalledWith(SCOPE, {
      brandId: 'b1',
      phone: '+998901234567',
      displayName: null,
    });
  });

  it('does not throw when the screen-pop poll itself is refused with a 403', async () => {
    await render(
      fakeApi({
        currentCall: () => Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null)),
      }),
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="call-centre-screen-pop-error"]')).toBeNull();
  });
});
