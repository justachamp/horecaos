import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { CourierLifecycleCard } from './courier-lifecycle-card';
import { CouriersApi, CourierTypeResponse, RosterEntryResponse } from './couriers-api';

const COURIER: RosterEntryResponse = {
  courierId: 'c1',
  displayReference: 'K-014',
  status: 'ACTIVE',
  courierTypeId: 'type-1',
  courierTypeName: 'Scooter',
  vehicleClass: 'SCOOTER',
  activeAssignments: 0,
  concurrencyCeiling: 2,
  engagementId: 'e1',
  engagementStatus: 'ACTIVE',
  version: 4,
};

const TYPES: readonly CourierTypeResponse[] = [
  type('type-1', 'Scooter'),
  type('type-2', 'Bicycle'),
];

function type(courierTypeId: string, displayName: string): CourierTypeResponse {
  return {
    courierTypeId,
    code: displayName.toUpperCase(),
    displayName,
    vehicleClass: 'SCOOTER',
    minDistanceMeters: 0,
    maxConcurrentAssignments: 2,
    offerTtlSeconds: 60,
    startingMinuteOffset: 0,
    workMode: 'SHIFT',
    status: 'ACTIVE',
    version: 1,
  };
}

function problem(status: number, code: string, extra: Record<string, unknown> = {}): ApiError {
  return new ApiError(code, status, { type: 't', title: 'x', status, ...extra }, null);
}

describe('CourierLifecycleCard (ADR 0178)', () => {
  let fixture: ComponentFixture<CourierLifecycleCard>;

  async function render(
    api: Partial<CouriersApi>,
    courier: RosterEntryResponse = COURIER,
  ): Promise<{ host: HTMLElement; changed: ReturnType<typeof vi.fn> }> {
    await TestBed.configureTestingModule({
      imports: [CourierLifecycleCard],
      providers: [{ provide: CouriersApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierLifecycleCard);
    fixture.componentRef.setInput('tenantId', 't1');
    fixture.componentRef.setInput('courier', courier);
    fixture.componentRef.setInput('types', TYPES);
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    fixture.detectChanges();
    return { host: fixture.nativeElement as HTMLElement, changed };
  }

  function type_(host: HTMLElement, testId: string, value: string, event = 'input'): void {
    const element = host.querySelector<HTMLInputElement | HTMLSelectElement>(
      `[data-testid="${testId}"]`,
    )!;
    element.value = value;
    element.dispatchEvent(new Event(event));
    fixture.detectChanges();
  }

  function click(host: HTMLElement, testId: string): void {
    host.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)!.click();
    fixture.detectChanges();
  }

  it('sends the roster version with a correction and tells the page it changed', async () => {
    const correct = vi.fn().mockResolvedValue({});
    const { host, changed } = await render({ correct });

    click(host, 'courier-lifecycle-edit');
    type_(host, 'courier-lifecycle-reference', 'K-015');
    type_(host, 'courier-lifecycle-type', 'type-2', 'change');
    type_(host, 'courier-lifecycle-reason', 'Typo in the reference');
    click(host, 'courier-lifecycle-submit');

    await vi.waitFor(() => expect(changed).toHaveBeenCalledTimes(1));
    expect(correct).toHaveBeenCalledWith('t1', 'c1', {
      expectedVersion: 4,
      displayReference: 'K-015',
      courierTypeId: 'type-2',
      reason: 'Typo in the reference',
    });
  });

  it('will not save a correction that changes nothing or has no reason', async () => {
    const { host } = await render({ correct: vi.fn() });

    click(host, 'courier-lifecycle-edit');
    type_(host, 'courier-lifecycle-reason', 'Nothing really');
    expect(
      host.querySelector<HTMLButtonElement>('[data-testid="courier-lifecycle-submit"]')!.disabled,
    ).toBe(true);

    type_(host, 'courier-lifecycle-reference', 'K-099');
    type_(host, 'courier-lifecycle-reason', '');
    expect(
      host.querySelector<HTMLButtonElement>('[data-testid="courier-lifecycle-submit"]')!.disabled,
    ).toBe(true);
  });

  it('names the reference another courier holds', async () => {
    const correct = vi
      .fn()
      .mockRejectedValue(problem(409, 'RESOURCE_CONFLICT', { field: 'displayReference' }));
    const { host } = await render({ correct });

    click(host, 'courier-lifecycle-edit');
    type_(host, 'courier-lifecycle-reference', 'K-200');
    type_(host, 'courier-lifecycle-reason', 'Renumbering');
    click(host, 'courier-lifecycle-submit');

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-lifecycle-error"]')?.textContent).toContain(
        'Another courier already has that reference',
      );
    });
  });

  it('refuses to archive a courier with a balance and says to settle it, from the reason code', async () => {
    const archive = vi
      .fn()
      .mockRejectedValue(
        problem(422, 'UNPROCESSABLE_STATE', { reason: 'COURIER_BALANCE_NOT_ZERO' }),
      );
    const { host, changed } = await render({ archive });

    click(host, 'courier-lifecycle-archive');
    type_(host, 'courier-lifecycle-reason', 'Left the company');
    click(host, 'courier-lifecycle-submit');

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-lifecycle-error"]')?.textContent).toContain(
        'balance is not zero',
      );
    });
    expect(archive).toHaveBeenCalledWith('t1', 'c1', 'Left the company');
    expect(changed).not.toHaveBeenCalled();
  });

  it('explains a courier on a shift and one carrying a delivery', async () => {
    const endEngagement = vi
      .fn()
      .mockRejectedValueOnce(
        problem(422, 'UNPROCESSABLE_STATE', { reason: 'COURIER_HAS_OPEN_SHIFT' }),
      )
      .mockRejectedValueOnce(
        problem(422, 'UNPROCESSABLE_STATE', { reason: 'COURIER_HAS_ACTIVE_DELIVERIES' }),
      );
    const { host } = await render({ endEngagement });

    click(host, 'courier-lifecycle-end');
    type_(host, 'courier-lifecycle-reason', 'Left');
    click(host, 'courier-lifecycle-submit');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-lifecycle-error"]')?.textContent).toContain(
        'open shift',
      );
    });

    click(host, 'courier-lifecycle-submit');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-lifecycle-error"]')?.textContent).toContain(
        'carrying a delivery',
      );
    });
  });

  it('offers no «end engagement» once it has ended, and nothing at all for an archived courier', async () => {
    const ended = await render({}, { ...COURIER, engagementStatus: 'ENDED' });
    expect(ended.host.querySelector('[data-testid="courier-lifecycle-end"]')).toBeNull();
    expect(ended.host.querySelector('[data-testid="courier-lifecycle-archive"]')).not.toBeNull();

    TestBed.resetTestingModule();
    const archived = await render({}, { ...COURIER, status: 'ARCHIVED' });
    expect(
      archived.host.querySelector('[data-testid="courier-lifecycle-archived"]'),
    ).not.toBeNull();
    expect(archived.host.querySelector('[data-testid="courier-lifecycle-edit"]')).toBeNull();
  });
});
