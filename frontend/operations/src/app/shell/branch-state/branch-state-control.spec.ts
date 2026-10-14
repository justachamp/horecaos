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
import { ShortcutRegistry } from '../../shared/keyboard/shortcut-registry';
import { BranchStateControl } from './branch-state-control';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function state(overrides: Partial<LocationServiceStateView> = {}): LocationServiceStateView {
  return {
    locationId: 'l1',
    mode: 'FOLLOW_SCHEDULE',
    effectiveMode: 'FOLLOW_SCHEDULE',
    reasonCode: null,
    effectiveUntil: null,
    ...overrides,
  };
}

describe('BranchStateControl', () => {
  let fixture: ComponentFixture<BranchStateControl>;
  let mayChange: boolean;

  async function render(
    read: readonly LocationServiceStateView[] | 'refused' = [state()],
  ): Promise<void> {
    const api = {
      serviceStates:
        read === 'refused'
          ? vi.fn().mockRejectedValue(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null))
          : vi.fn().mockResolvedValue(read),
      list: vi.fn().mockResolvedValue([{ id: 'l1', timezone: 'Asia/Tashkent' }]),
      serviceSummary: vi.fn().mockResolvedValue({
        mode: 'FOLLOW_SCHEDULE',
        effectiveMode: 'FOLLOW_SCHEDULE',
        reasonCode: null,
        effectiveUntil: null,
        maxConcurrentOrders: null,
        openOrderCount: 0,
        bindings: [],
        preparationBands: [],
      }),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [BranchStateControl],
      providers: [
        { provide: LocationsApi, useValue: api },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            options: signal([{ id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' }]),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: SessionCapabilities, useValue: { has: () => mayChange } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BranchStateControl);
    fixture.detectChanges();
    await settle();
  }

  async function settle(): Promise<void> {
    for (let turn = 0; turn < 3; turn += 1) {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
    fixture.detectChanges();
  }

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const control = (): HTMLButtonElement | null =>
    host().querySelector('[data-testid="branch-state-control"]');
  const press = (key: string): boolean =>
    TestBed.inject(ShortcutRegistry).dispatch(
      Object.defineProperty(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
        'target',
        { value: document.body },
      ),
    );

  beforeEach(() => {
    mayChange = true;
  });

  it('is in the header, with the branch’s state, for someone who may change it', async () => {
    await render();

    expect(control()?.textContent?.trim()).toBe('Branch open');
    expect(control()?.getAttribute('aria-label')).toBe('Branch status: Branch open. Change it');
  });

  it('is omitted, not disabled, for someone who may not change a branch’s state', async () => {
    mayChange = false;
    await render();

    expect(control()).toBeNull();
    expect(press('c'), 'and its key does nothing').toBe(false);
  });

  it('is omitted when the branches cannot be read', async () => {
    await render('refused');

    expect(control()).toBeNull();
    expect(press('c')).toBe(false);
  });

  it('is omitted until the state of this branch has been read', async () => {
    await render([state({ locationId: 'l9' })]);

    expect(control()).toBeNull();
  });

  it('says closed until a time, closed with no end, or forced open, as the state is', async () => {
    await render([
      state({
        mode: 'FORCE_CLOSED',
        effectiveMode: 'FORCE_CLOSED',
        effectiveUntil: '2026-10-12T16:30:00Z',
      }),
    ]);
    expect(control()?.textContent?.trim()).toBe('Closed until 21:30');
    expect(control()?.classList.contains('branch-control--closed')).toBe(true);
  });

  it('says closed plainly when nothing will reopen it', async () => {
    await render([state({ mode: 'FORCE_CLOSED', effectiveMode: 'FORCE_CLOSED' })]);

    expect(control()?.textContent?.trim()).toBe('Branch closed');
  });

  it('says forced open when the branch is open outside its hours', async () => {
    await render([state({ mode: 'FORCE_OPEN', effectiveMode: 'FORCE_OPEN' })]);

    expect(control()?.textContent?.trim()).toBe('Open (forced)');
    expect(control()?.classList.contains('branch-control--forced')).toBe(true);
  });

  it('a click opens the status dialog for this branch', async () => {
    await render();

    control()!.click();
    fixture.detectChanges();

    expect(host().querySelector('[data-testid="branch-state-dialog"]')).not.toBeNull();
  });

  it('c opens the same dialog from anywhere, once, and is on the cheat-sheet', async () => {
    await render();
    const scope = TestBed.inject(ShortcutRegistry)
      .scopes()
      .find((candidate) => candidate.id === 'branch-state')!;
    expect(scope.shortcuts.map((shortcut) => shortcut.caps)).toEqual([['c']]);

    expect(press('c')).toBe(true);
    fixture.detectChanges();

    expect(host().querySelector('[data-testid="branch-state-dialog"]')).not.toBeNull();
    expect(press('c'), 'not while the dialog is open').toBe(false);
  });

  it('takes its key off the cheat-sheet when it goes', async () => {
    await render();

    fixture.destroy();

    expect(
      TestBed.inject(ShortcutRegistry)
        .scopes()
        .some((candidate) => candidate.id === 'branch-state'),
    ).toBe(false);
  });
});
