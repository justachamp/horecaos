import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import {
  LocationServiceStateView,
  LocationsApi,
} from '../../features/settings/locations/locations-api';
import { ClosedBranchStrip } from './closed-branch-strip';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function closed(
  locationId: string,
  effectiveUntil: string | null = null,
): LocationServiceStateView {
  return {
    locationId,
    mode: 'FORCE_CLOSED',
    effectiveMode: 'FORCE_CLOSED',
    reasonCode: 'EQUIPMENT_FAILURE',
    effectiveUntil,
  };
}

async function settle(fixture: ComponentFixture<ClosedBranchStrip>): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

describe('ClosedBranchStrip', () => {
  let api: {
    serviceStates: ReturnType<typeof vi.fn>;
    list: ReturnType<typeof vi.fn>;
    serviceSummary: ReturnType<typeof vi.fn>;
    profile: ReturnType<typeof vi.fn>;
    changeServiceState: ReturnType<typeof vi.fn>;
  };
  let mayChange: boolean;

  async function render(
    states: readonly LocationServiceStateView[],
    options: { refused?: boolean; branchOnly?: boolean } = {},
  ): Promise<ComponentFixture<ClosedBranchStrip>> {
    const refusal = (): Promise<never> =>
      Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null));
    const brandWideRefused = options.refused === true || options.branchOnly === true;
    // A branch manager (branchOnly): the brand's batched read and list are refused, their own branch is served.
    const own = states.find((candidate) => candidate.locationId === SCOPE.locationId);
    api = {
      serviceStates: brandWideRefused ? vi.fn(refusal) : vi.fn().mockResolvedValue(states),
      list: brandWideRefused
        ? vi.fn(refusal)
        : vi.fn().mockResolvedValue([
            { id: 'l1', timezone: 'Asia/Tashkent' },
            { id: 'l2', timezone: 'Asia/Tashkent' },
          ]),
      serviceSummary:
        options.branchOnly === true
          ? vi.fn().mockResolvedValue({
              mode: own?.mode ?? 'FOLLOW_SCHEDULE',
              effectiveMode: own?.effectiveMode ?? 'FOLLOW_SCHEDULE',
              reasonCode: own?.reasonCode ?? null,
              effectiveUntil: own?.effectiveUntil ?? null,
              maxConcurrentOrders: null,
              openOrderCount: 0,
              bindings: [],
              preparationBands: [],
            })
          : vi.fn(refusal),
      profile:
        options.branchOnly === true
          ? vi
              .fn()
              .mockResolvedValue({ id: 'l1', displayName: 'Chilonzor', timezone: 'Asia/Tashkent' })
          : vi.fn(refusal),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [ClosedBranchStrip],
      providers: [
        { provide: LocationsApi, useValue: api },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            options: signal(
              options.branchOnly === true
                ? []
                : [
                    { id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' },
                    { id: 'l2', displayName: 'Yunusobod', status: 'ACTIVE' },
                  ],
            ),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: SessionCapabilities, useValue: { has: () => mayChange } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
    const fixture = TestBed.createComponent(ClosedBranchStrip);
    fixture.detectChanges();
    await settle(fixture);
    return fixture;
  }

  const host = (fixture: ComponentFixture<ClosedBranchStrip>): HTMLElement =>
    fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    mayChange = true;
  });

  it('draws nothing while every branch is open', async () => {
    const fixture = await render([
      {
        locationId: 'l1',
        mode: 'FOLLOW_SCHEDULE',
        effectiveMode: 'FOLLOW_SCHEDULE',
        reasonCode: null,
        effectiveUntil: null,
      },
    ]);

    expect(host(fixture).querySelector('[data-testid="closed-branch-strip"]')).toBeNull();
  });

  it('names each branch shut by hand and when it reopens: «до отмены» for one nobody has to reopen on a timer', async () => {
    const fixture = await render([closed('l1'), closed('l2', '2026-10-12T16:30:00Z')]);

    const items = [...host(fixture).querySelectorAll('[data-testid="closed-branch-item"]')];
    expect(items.map((item) => item.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      'Chilonzor (до отмены) Открыть',
      'Yunusobod (до 21:30) Открыть',
    ]);
    expect(host(fixture).textContent).toContain('Закрыты вручную:');
    expect(host(fixture).querySelector('.closed-strip__until--open-ended')?.textContent).toContain(
      'до отмены',
    );
  });

  it('cannot be dismissed: no close control, and nothing in it but the reopen buttons to press', async () => {
    const fixture = await render([closed('l1')]);

    const buttons = [...host(fixture).querySelectorAll('button')];
    expect(buttons.map((button) => button.getAttribute('data-testid'))).toEqual([
      'closed-branch-reopen',
    ]);
    expect(
      host(fixture).querySelector('[aria-label*="акрыть"], [data-testid*="dismiss"]'),
    ).toBeNull();
    expect(host(fixture).querySelector('[role="status"]')).not.toBeNull();
  });

  it('reopens a branch with one click: the schedule mode for that branch, and the strip goes when the last one is open', async () => {
    const fixture = await render([closed('l1'), closed('l2')]);
    api.serviceStates.mockResolvedValue([closed('l2')]);

    (
      host(fixture).querySelector('[data-testid="closed-branch-reopen"]') as HTMLButtonElement
    ).click();
    await settle(fixture);

    expect(api.changeServiceState).toHaveBeenCalledWith(
      { tenantId: 't1', brandId: 'b1', locationId: 'l1' },
      { mode: 'FOLLOW_SCHEDULE' },
    );
    expect(host(fixture).querySelectorAll('[data-testid="closed-branch-item"]')).toHaveLength(1);

    api.serviceStates.mockResolvedValue([]);
    (
      host(fixture).querySelector('[data-testid="closed-branch-reopen"]') as HTMLButtonElement
    ).click();
    await settle(fixture);

    expect(host(fixture).querySelector('[data-testid="closed-branch-strip"]')).toBeNull();
  });

  it('says so, and stays, when the reopen is refused', async () => {
    const fixture = await render([closed('l1')]);
    api.changeServiceState.mockRejectedValue(
      new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null),
    );

    (
      host(fixture).querySelector('[data-testid="closed-branch-reopen"]') as HTMLButtonElement
    ).click();
    await settle(fixture);

    expect(
      host(fixture).querySelector('[data-testid="closed-branch-failed"]')?.textContent,
    ).toContain('Chilonzor');
    expect(host(fixture).querySelectorAll('[data-testid="closed-branch-item"]')).toHaveLength(1);
  });

  it('shows the strip without reopen buttons to someone who may read the branches but not change them', async () => {
    mayChange = false;
    const fixture = await render([closed('l1')]);

    expect(host(fixture).querySelector('[data-testid="closed-branch-strip"]')).not.toBeNull();
    expect(host(fixture).querySelector('[data-testid="closed-branch-reopen"]')).toBeNull();
  });

  it('shows a branch manager their own branch shut until cancelled: the brand-wide read is refused, the branch’s own is not', async () => {
    const fixture = await render([closed('l1')], { branchOnly: true });

    const items = [...host(fixture).querySelectorAll('[data-testid="closed-branch-item"]')];
    expect(items.map((item) => item.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      'Chilonzor (до отмены) Открыть',
    ]);
    expect(host(fixture).querySelector('.closed-strip__until--open-ended')).not.toBeNull();
  });

  it('lets a branch manager reopen their branch from the strip', async () => {
    const fixture = await render([closed('l1')], { branchOnly: true });
    api.serviceSummary.mockResolvedValue({
      mode: 'FOLLOW_SCHEDULE',
      effectiveMode: 'FOLLOW_SCHEDULE',
      reasonCode: null,
      effectiveUntil: null,
      maxConcurrentOrders: null,
      openOrderCount: 0,
      bindings: [],
      preparationBands: [],
    });

    (
      host(fixture).querySelector('[data-testid="closed-branch-reopen"]') as HTMLButtonElement
    ).click();
    await settle(fixture);

    expect(api.changeServiceState).toHaveBeenCalledWith(
      { tenantId: 't1', brandId: 'b1', locationId: 'l1' },
      { mode: 'FOLLOW_SCHEDULE' },
    );
    expect(host(fixture).querySelector('[data-testid="closed-branch-strip"]')).toBeNull();
  });

  it('draws nothing for someone the read is refused to', async () => {
    const fixture = await render([closed('l1')], { refused: true });

    expect(host(fixture).querySelector('[data-testid="closed-branch-strip"]')).toBeNull();
  });
});
