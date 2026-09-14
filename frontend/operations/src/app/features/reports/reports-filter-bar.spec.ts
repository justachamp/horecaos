import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import {
  PaymentMethodView,
  PaymentMethodsApi,
} from '../settings/payment-methods/payment-methods-api';
import { ReportsFilterBar } from './reports-filter-bar';
import { ReportsFilterState } from './reports-filter-state';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const CASH: PaymentMethodView = {
  id: 'pm-cash',
  code: 'CASH',
  displayName: 'Cash',
  localizedNames: { ru: 'Наличные' },
  responsibility: 'OPERATOR',
  settlesFromBalance: false,
  status: 'ACTIVE',
  icon: 'cash',
  sortOrder: 0,
  providerInstallationId: null,
  contractReference: null,
  version: 1,
};

const CARD: PaymentMethodView = {
  ...CASH,
  id: 'pm-card',
  code: 'CARD',
  displayName: 'Card',
  localizedNames: {},
  sortOrder: 1,
  version: 1,
};

const DISABLED: PaymentMethodView = {
  ...CASH,
  id: 'pm-old',
  code: 'OLD',
  displayName: 'Retired method',
  status: 'DISABLED',
  sortOrder: 2,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ReportsFilterBar', () => {
  let fixture: ComponentFixture<ReportsFilterBar>;
  let state: ReportsFilterState;
  let list: ReturnType<typeof vi.fn>;

  async function render(
    methods: readonly PaymentMethodView[] = [CASH, CARD, DISABLED],
  ): Promise<void> {
    TestBed.resetTestingModule();
    list = vi.fn().mockResolvedValue(methods);
    await TestBed.configureTestingModule({
      imports: [ReportsFilterBar],
      providers: [
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PaymentMethodsApi, useValue: { list } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
    state = TestBed.inject(ReportsFilterState);
    fixture = TestBed.createComponent(ReportsFilterBar);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders only the active payment methods, sorted by their own sortOrder, never the locked notice', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).not.toContain('ADR 0013');
    expect(host.textContent).not.toContain('ADR 0046');
    // ru locale: CASH has a translation, CARD falls back to displayName.
    expect(host.textContent).toContain('Наличные');
    expect(host.textContent).toContain('Card');
    expect(host.textContent).not.toContain('Retired method');
  });

  it('toggles a payment method into ReportsFilterState.paymentMethodCodes on click, and back out on a second click', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(host.querySelectorAll('.segment')).filter((el) =>
      el.textContent?.includes('Наличные'),
    );
    expect(buttons).toHaveLength(1);
    const cashButton = buttons[0] as HTMLButtonElement;

    expect(state.paymentMethodCodes()).toEqual([]);
    cashButton.click();
    fixture.detectChanges();
    expect(state.paymentMethodCodes()).toEqual(['CASH']);
    expect(cashButton.getAttribute('aria-pressed')).toBe('true');

    cashButton.click();
    fixture.detectChanges();
    expect(state.paymentMethodCodes()).toEqual([]);
    expect(cashButton.getAttribute('aria-pressed')).toBe('false');
  });

  it('renders no payment-method control at all when the tenant has none, rather than an empty group', async () => {
    await render([]);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[aria-label="Тип оплаты"]')).toBeNull();
  });
});
