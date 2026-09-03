import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { CouriersApi, ShiftView } from '../couriers/couriers-api';
import { I18n } from '../../core/i18n/i18n';
import { ShiftsPage } from './shifts-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function shift(overrides: Partial<ShiftView>): ShiftView {
  return {
    shiftId: 'shift-1',
    courierId: 'courier-1',
    status: 'OPEN',
    dutyState: 'AVAILABLE',
    openedAt: new Date().toISOString(),
    closedAt: null,
    paidSeconds: null,
    breakSeconds: 0,
    approvalRequestId: null,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ShiftsPage', () => {
  let fixture: ComponentFixture<ShiftsPage>;

  async function render(api: Partial<CouriersApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ShiftsPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ShiftsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists the branch’s shifts with a close action on an open one', async () => {
    await render({ shifts: () => Promise.resolve([shift({})]) });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="shift-row"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="shift-close"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="shift-approve"]')).toBeNull();
  });

  it('offers approve on a shift awaiting approval, and calls the API on click', async () => {
    const approveShift = vi.fn().mockResolvedValue(undefined);
    await render({
      shifts: () => Promise.resolve([shift({ status: 'AWAITING_APPROVAL' })]),
      approveShift,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="shift-approve"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(approveShift).toHaveBeenCalledWith('t1', 'shift-1', expect.any(String));
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [ShiftsPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: { shifts: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ShiftsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="shifts-denied"]'),
    ).not.toBeNull();
  });
});
