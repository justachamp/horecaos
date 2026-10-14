import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import {
  BulkServiceStateResponse,
  LocationChannelsView,
  LocationLegalEntityView,
  LocationServiceStateView,
  LocationsApi,
  LocationView,
} from './locations-api';
import { ShortcutRegistry } from '../../../shared/keyboard/shortcut-registry';
import { LocationsPage } from './locations-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const OPEN_LOCATION: LocationView = {
  id: 'location-1',
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  code: 'CHILANZAR',
  slug: 'chilanzar',
  displayName: 'Chilanzar',
  timezone: 'Asia/Tashkent',
  status: 'ACTIVE',
  addressLine: 'Bunyodkor 12',
  district: 'Chilanzar',
  city: 'Tashkent',
  landmark: null,
  contactPhone: '+998712000000',
  latitude: 41.3,
  longitude: 69.2,
  coordinateSource: 'MERCHANT_PIN',
  sortOrder: 0,
  seats: null,
  averageChequeAmount: null,
  averageChequeCurrency: null,
  hasParking: false,
  hasPlayground: false,
  virtualTourUrl: null,
  locales: [],
};

const CLOSED_LOCATION: LocationView = {
  ...OPEN_LOCATION,
  id: 'location-2',
  code: 'YUNUSABAD',
  slug: 'yunusabad',
  displayName: 'Yunusabad',
};

const THIRD_LOCATION: LocationView = {
  ...OPEN_LOCATION,
  id: 'location-3',
  code: 'SERGELI',
  slug: 'sergeli',
  displayName: 'Sergeli',
};

const STATES: readonly LocationServiceStateView[] = [
  {
    locationId: 'location-1',
    mode: 'FOLLOW_SCHEDULE',
    effectiveMode: 'FOLLOW_SCHEDULE',
    reasonCode: null,
    effectiveUntil: null,
  },
  {
    locationId: 'location-2',
    mode: 'FORCE_CLOSED',
    effectiveMode: 'FORCE_CLOSED',
    reasonCode: 'fryer_broken',
    effectiveUntil: null,
  },
  {
    locationId: 'location-3',
    mode: 'FOLLOW_SCHEDULE',
    effectiveMode: 'FOLLOW_SCHEDULE',
    reasonCode: null,
    effectiveUntil: null,
  },
];

const LEGAL_ENTITIES: readonly LocationLegalEntityView[] = [
  { locationId: 'location-1', legalEntityCode: 'ACME', taxpayerNumber: '111111111' },
  { locationId: 'location-2', legalEntityCode: 'BETA', taxpayerNumber: '222222222' },
];

const CHANNELS: readonly LocationChannelsView[] = [
  { locationId: 'location-1', channelCodes: ['STOREFRONT'] },
  { locationId: 'location-2', channelCodes: ['STOREFRONT', 'YANDEX'] },
  { locationId: 'location-3', channelCodes: ['YANDEX'] },
];

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  readonly options = signal([
    { id: 'location-1', displayName: 'Chilanzar', status: 'ACTIVE' as const },
    { id: 'location-2', displayName: 'Yunusabad', status: 'ACTIVE' as const },
    { id: 'location-3', displayName: 'Sergeli', status: 'ACTIVE' as const },
  ]);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function rows(fixture: ComponentFixture<LocationsPage>): readonly HTMLElement[] {
  return Array.from(fixture.nativeElement.querySelectorAll('tbody tr'));
}

/** The row whose text names this branch — robust to the severity sort reordering rows. */
function rowFor(fixture: ComponentFixture<LocationsPage>, displayName: string): HTMLElement {
  const row = rows(fixture).find((candidate) => candidate.textContent?.includes(displayName));
  if (!row) {
    throw new Error(`No row found for ${displayName}`);
  }
  return row;
}

describe('LocationsPage', () => {
  let fixture: ComponentFixture<LocationsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    serviceStates: ReturnType<typeof vi.fn>;
    changeServiceState: ReturnType<typeof vi.fn>;
    legalEntities: ReturnType<typeof vi.fn>;
    channels: ReturnType<typeof vi.fn>;
    bulkChangeServiceState: ReturnType<typeof vi.fn>;
  };

  async function render(overrides: Partial<typeof api> = {}) {
    api = {
      list: vi.fn().mockResolvedValue([OPEN_LOCATION, CLOSED_LOCATION, THIRD_LOCATION]),
      serviceStates: vi.fn().mockResolvedValue(STATES),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
      legalEntities: vi.fn().mockResolvedValue(LEGAL_ENTITIES),
      channels: vi.fn().mockResolvedValue(CHANNELS),
      bulkChangeServiceState: vi.fn().mockResolvedValue({
        requestedCount: 0,
        appliedCount: 0,
        failedCount: 0,
        items: [],
      } satisfies BulkServiceStateResponse),
      ...overrides,
    };
    await TestBed.configureTestingModule({
      imports: [LocationsPage],
      providers: [
        provideRouter([]),
        { provide: LocationsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        { provide: SessionCapabilities, useValue: { has: () => true } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LocationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture;
  }

  it('lists the brand’s locations and each row’s own state, from the batched read', async () => {
    await render();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chilanzar');
    expect(text).toContain('Yunusabad');
    expect(text).toContain('fryer_broken');
    expect(api.serviceStates).toHaveBeenCalledWith(SCOPE);
  });

  it('filters the list to closed branches over the batched state read, without one call per row', async () => {
    await render();

    const select = fixture.nativeElement.querySelector('#state-filter') as HTMLSelectElement;
    select.value = 'FORCE_CLOSED';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Yunusabad');
    expect(text).not.toContain('Chilanzar');
    // One list read and one batched state read for the whole page — never one
    // request per row, which is the bug this wave's own comment named.
    expect(api.serviceStates).toHaveBeenCalledTimes(1);
  });

  it('closes an open branch from the row, with a required reason', async () => {
    await render();

    const openRow = rowFor(fixture, 'Chilanzar');
    (openRow.querySelector('.actions-cell button') as HTMLButtonElement).click();
    fixture.detectChanges();

    // No reason yet: refused without reaching the API.
    const confirmButton = Array.from(openRow.querySelectorAll('.close-form__actions button')).find(
      (button) => button.textContent?.includes('Close'),
    ) as HTMLButtonElement;
    confirmButton.click();
    await flushMicrotasks();
    expect(api.changeServiceState).not.toHaveBeenCalled();

    const reasonInput = openRow.querySelector('.close-form input') as HTMLInputElement;
    reasonInput.value = 'fryer_broken';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    confirmButton.click();
    await flushMicrotasks();

    expect(api.changeServiceState).toHaveBeenCalledWith(SCOPE, {
      mode: 'FORCE_CLOSED',
      reasonCode: 'fryer_broken',
    });
  });

  it('reopens a closed branch from the row with one click', async () => {
    await render();

    const closedRow = rowFor(fixture, 'Yunusabad');
    (closedRow.querySelector('.actions-cell button') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(api.changeServiceState).toHaveBeenCalledWith(
      { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-2' },
      { mode: 'FOLLOW_SCHEDULE' },
    );
  });

  describe('the closed-branch strip (brands-and-locations.md §6.2)', () => {
    const strip = (): HTMLElement | null =>
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="closed-branch-strip"]');

    it('names every branch shut by hand above the list, from the same read the list is drawn from', async () => {
      await render();

      expect(strip()?.textContent).toContain('Yunusabad');
      expect(strip()?.textContent).toContain('until cancelled');
      expect(strip()?.textContent).not.toContain('Chilanzar');
      expect(api.serviceStates).toHaveBeenCalledTimes(1);
    });

    it('has no strip while every branch is open', async () => {
      await render({
        serviceStates: vi.fn().mockResolvedValue(
          STATES.map((state) => ({
            ...state,
            mode: 'FOLLOW_SCHEDULE' as const,
            effectiveMode: 'FOLLOW_SCHEDULE' as const,
            reasonCode: null,
          })),
        ),
      });

      expect(strip()).toBeNull();
    });

    it('reopening from the strip puts the branch back on the list’s state column at once', async () => {
      await render();
      expect(rowFor(fixture, 'Yunusabad').textContent).toContain('fryer_broken');
      api.serviceStates.mockResolvedValue(
        STATES.map((state) => ({
          ...state,
          mode: 'FOLLOW_SCHEDULE' as const,
          effectiveMode: 'FOLLOW_SCHEDULE' as const,
          reasonCode: null,
        })),
      );

      (strip()!.querySelector('[data-testid="closed-branch-reopen"]') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.changeServiceState).toHaveBeenCalledWith(
        { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-2' },
        { mode: 'FOLLOW_SCHEDULE' },
      );
      expect(strip()).toBeNull();
      expect(rowFor(fixture, 'Yunusabad').textContent).not.toContain('fryer_broken');
    });

    it('reopening a row from the list takes the branch off the strip at once', async () => {
      await render();
      api.serviceStates.mockResolvedValue(
        STATES.map((state) => ({
          ...state,
          mode: 'FOLLOW_SCHEDULE' as const,
          effectiveMode: 'FOLLOW_SCHEDULE' as const,
          reasonCode: null,
        })),
      );

      (
        rowFor(fixture, 'Yunusabad').querySelector('.actions-cell button') as HTMLButtonElement
      ).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(strip()).toBeNull();
    });
  });

  it('sorts forced-closed branches first, regardless of load order (severity sort)', async () => {
    await render();

    const names = rows(fixture).map((row) => row.textContent ?? '');
    // Yunusabad (FORCE_CLOSED) loaded second but must render first; the two
    // FOLLOW_SCHEDULE branches keep their original relative order after it.
    expect(names[0]).toContain('Yunusabad');
    expect(names.findIndex((text) => text.includes('Chilanzar'))).toBeLessThan(
      names.findIndex((text) => text.includes('Sergeli')),
    );
  });

  it('filters the list by channel over the batched channel read, without one call per row', async () => {
    await render();

    const select = fixture.nativeElement.querySelector('#channel-filter') as HTMLSelectElement;
    select.value = 'YANDEX';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Yunusabad');
    expect(text).toContain('Sergeli');
    expect(text).not.toContain('Chilanzar');
    expect(api.channels).toHaveBeenCalledWith(SCOPE);
    expect(api.channels).toHaveBeenCalledTimes(1);
  });

  it('filters the list by legal entity (INN) over the batched read, without one call per row', async () => {
    await render();

    const select = fixture.nativeElement.querySelector('#legal-entity-filter') as HTMLSelectElement;
    select.value = 'ACME';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    // The rows only: the closed-branch strip above them names Yunusabad whatever the filter says.
    const text = rows(fixture)
      .map((row) => row.textContent ?? '')
      .join(' ');
    expect(text).toContain('Chilanzar');
    expect(text).not.toContain('Yunusabad');
    expect(text).not.toContain('Sergeli');
    expect(api.legalEntities).toHaveBeenCalledWith(SCOPE);
    expect(api.legalEntities).toHaveBeenCalledTimes(1);
  });

  it('bulk-reopens every selected branch in one capability-gated, idempotent call, reporting a per-branch outcome', async () => {
    await render({
      bulkChangeServiceState: vi.fn().mockResolvedValue({
        requestedCount: 2,
        appliedCount: 1,
        failedCount: 1,
        items: [
          { locationId: 'location-1', applied: true, problemCode: null },
          { locationId: 'location-3', applied: false, problemCode: 'VALIDATION_FAILED' },
        ],
      } satisfies BulkServiceStateResponse),
    });

    (
      rowFor(fixture, 'Chilanzar').querySelector('input[type=checkbox]') as HTMLInputElement
    ).click();
    (rowFor(fixture, 'Sergeli').querySelector('input[type=checkbox]') as HTMLInputElement).click();
    fixture.detectChanges();

    const reopenButton = Array.from(
      fixture.nativeElement.querySelectorAll('.bulk-bar button'),
    ).find((button) =>
      (button as HTMLButtonElement).textContent?.includes('Reopen selected'),
    ) as HTMLButtonElement;
    reopenButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.bulkChangeServiceState).toHaveBeenCalledWith(SCOPE, {
      mode: 'FOLLOW_SCHEDULE',
      locationIds: ['location-1', 'location-3'],
    });
    // One request for the whole selection, never one per branch.
    expect(api.bulkChangeServiceState).toHaveBeenCalledTimes(1);

    const outcomesText =
      (fixture.nativeElement.querySelector('.bulk-outcomes') as HTMLElement | null)?.textContent ??
      '';
    expect(outcomesText).toContain('Chilanzar');
    expect(outcomesText).toContain('done');
    expect(outcomesText).toContain('Sergeli');
    // Translated through settings.locations.list.bulk.outcome.problem.* --
    // the raw backend code must never reach the screen untranslated (it
    // breaks the ru/uz-latn key-parity requirement for user-facing text).
    expect(outcomesText).toContain('Rejected as invalid');
    expect(outcomesText).not.toContain('VALIDATION_FAILED');
  });

  it('bulk-closes every selected branch with a required reason', async () => {
    await render();

    (
      rowFor(fixture, 'Chilanzar').querySelector('input[type=checkbox]') as HTMLInputElement
    ).click();
    fixture.detectChanges();

    const closeButton = Array.from(fixture.nativeElement.querySelectorAll('.bulk-bar button')).find(
      (button) => (button as HTMLButtonElement).textContent?.includes('Close selected'),
    ) as HTMLButtonElement;
    closeButton.click();
    fixture.detectChanges();

    const confirmButton = Array.from(
      fixture.nativeElement.querySelectorAll('.bulk-bar .close-form__actions button'),
    ).find((button) =>
      (button as HTMLButtonElement).textContent?.includes('Close'),
    ) as HTMLButtonElement;
    confirmButton.click();
    await flushMicrotasks();
    expect(api.bulkChangeServiceState).not.toHaveBeenCalled();

    const reasonInput = fixture.nativeElement.querySelector(
      '.bulk-bar .close-form input',
    ) as HTMLInputElement;
    reasonInput.value = 'regional holiday';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    confirmButton.click();
    await flushMicrotasks();

    expect(api.bulkChangeServiceState).toHaveBeenCalledWith(SCOPE, {
      mode: 'FORCE_CLOSED',
      reasonCode: 'regional holiday',
      locationIds: ['location-1'],
    });
  });

  // ------------------------------------------------- the keys (brands-and-locations.md §1.8)

  describe('the keys', () => {
    function key(value: string): boolean {
      const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true });
      Object.defineProperty(event, 'target', { value: document.activeElement ?? document.body });
      return TestBed.inject(ShortcutRegistry).dispatch(event);
    }

    /** The cursor on the row naming `displayName`, reached by j from the top as an operator would. */
    function cursorOn(displayName: string): HTMLElement {
      (document.activeElement as HTMLElement | null)?.blur();
      const target = rowFor(fixture, displayName);
      for (let step = 0; step < rows(fixture).length; step++) {
        key('j');
        if (document.activeElement === target) {
          return target;
        }
      }
      throw new Error(`the cursor never reached ${displayName}`);
    }

    it('is on the cheat-sheet with the list keys', async () => {
      await render();
      const scope = TestBed.inject(ShortcutRegistry)
        .scopes()
        .find((candidate) => candidate.id === 'locations-list')!;

      expect(scope.title()).toBe('Branches');
      expect(scope.shortcuts.flatMap((shortcut) => shortcut.caps)).toEqual([
        'j',
        '↓',
        'k',
        '↑',
        'Enter',
        'Space',
        'x',
        'c',
        'o',
        'Esc',
      ]);
    });

    it('j and k walk the rows without wrapping, and make real focus of it', async () => {
      await render();
      const all = rows(fixture);

      key('j');
      expect(document.activeElement).toBe(all[0]);
      key('k');
      expect(document.activeElement, 'k at the top stays at the top').toBe(all[0]);
      for (let step = 0; step < all.length + 2; step++) {
        key('j');
      }
      expect(document.activeElement, 'stops at the last row').toBe(all[all.length - 1]);
    });

    it('Space and x select the branch under the cursor and deselect it again', async () => {
      await render();
      const row = cursorOn('Chilanzar');
      const checkbox = row.querySelector<HTMLInputElement>('input[type="checkbox"]')!;

      expect(key(' ')).toBe(true);
      fixture.detectChanges();
      expect(checkbox.checked).toBe(true);
      expect(fixture.nativeElement.querySelector('.bulk-bar')).not.toBeNull();

      key('x');
      fixture.detectChanges();
      expect(checkbox.checked).toBe(false);
    });

    it('Esc clears the selection, and does nothing without one', async () => {
      await render();
      cursorOn('Chilanzar');
      expect(key('Escape')).toBe(false);
      key('x');
      fixture.detectChanges();

      expect(key('Escape')).toBe(true);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.bulk-bar')).toBeNull();
    });

    it('c opens the close form on an open branch (and closes nothing by itself), and not on a closed one', async () => {
      await render();
      cursorOn('Yunusabad');
      expect(key('c'), 'a closed branch has no «close» to press').toBe(false);

      const row = cursorOn('Chilanzar');
      expect(key('c')).toBe(true);
      fixture.detectChanges();

      expect(row.querySelector('.close-form')).not.toBeNull();
      expect(api.changeServiceState).not.toHaveBeenCalled();
    });

    it('o reopens a closed branch, and does nothing on an open one', async () => {
      await render();
      cursorOn('Chilanzar');
      expect(key('o')).toBe(false);

      cursorOn('Yunusabad');
      expect(key('o')).toBe(true);
      await flushMicrotasks();

      expect(api.changeServiceState).toHaveBeenCalledWith(
        expect.objectContaining({ locationId: 'location-2' }),
        expect.objectContaining({ mode: 'FOLLOW_SCHEDULE' }),
      );
    });

    it('Enter opens the branch the cursor is on', async () => {
      await render();
      const row = cursorOn('Sergeli');
      vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      const opened = vi.fn();
      row.addEventListener('click', opened);

      expect(key('Enter')).toBe(true);

      expect(opened).toHaveBeenCalledOnce();
    });
  });
});
