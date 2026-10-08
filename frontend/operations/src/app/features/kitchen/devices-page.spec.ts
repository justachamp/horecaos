import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { DevicesPage } from './devices-page';
import { KitchenDeviceView, KitchenDevicesApi, PendingEnrolmentView } from './devices-api';
import { KitchenApi, StationResponse } from './kitchen-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const PENDING_KDS: PendingEnrolmentView = {
  requestedClass: 'KITCHEN_KDS',
  requestedLabel: null,
  expiresAt: '2026-09-14T08:10:00Z',
};
const PENDING_VDU: PendingEnrolmentView = { ...PENDING_KDS, requestedClass: 'KITCHEN_VDU' };

function device(overrides: Partial<KitchenDeviceView> = {}): KitchenDeviceView {
  return {
    deviceId: 'device-1',
    locationId: 'l1',
    deviceClass: 'KITCHEN_KDS',
    requestedClass: 'KITCHEN_KDS',
    displayName: 'Pass tablet',
    status: 'ACTIVE',
    enrolledBy: 'manager-subject',
    enrolledAt: '2026-09-14T08:00:00Z',
    revokedBy: null,
    revokedAt: null,
    revokedReason: null,
    ...overrides,
  };
}

function station(overrides: Partial<StationResponse> = {}): StationResponse {
  return {
    stationId: 'station-grill',
    code: 'GRILL',
    role: 'GRILL',
    displayNameRu: 'Гриль',
    displayNameUz: 'Gril',
    displayNameEn: 'Grill',
    sortOrder: 1,
    fallback: false,
    status: 'ACTIVE',
    version: 1,
    ...overrides,
  };
}

function wall(overrides: Partial<KitchenDeviceView> = {}): KitchenDeviceView {
  return device({
    deviceId: 'wall-1',
    deviceClass: 'KITCHEN_VDU',
    requestedClass: 'KITCHEN_VDU',
    displayName: 'Grill TV',
    display: {
      station: null,
      lastReadAt: '2026-09-14T08:05:00Z',
      version: 3,
      notSeen: false,
      notSeenAfterMinutes: 5,
    },
    ...overrides,
  });
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DevicesPage', () => {
  let fixture: ComponentFixture<DevicesPage>;

  async function render(
    devicesApi: Partial<KitchenDevicesApi>,
    locationOverrides: { scope?: LocationScope | null; denied?: boolean } = {},
    stations: readonly StationResponse[] = [],
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DevicesPage],
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
        { provide: KitchenDevicesApi, useValue: devicesApi },
        { provide: KitchenApi, useValue: { stations: vi.fn().mockResolvedValue(stations) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DevicesPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('shows the denied state when the manager holds no kitchen.station.manage grant here', async () => {
    await render({ list: vi.fn() }, { scope: null, denied: true });

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="devices-denied"]'),
    ).not.toBeNull();
  });

  it('lists active devices and an honest empty state when none are enrolled', async () => {
    await render({ list: () => Promise.resolve([]) });

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="devices-active-empty"]'),
    ).not.toBeNull();
  });

  it('approves a device by its typed user code, never a scan', async () => {
    const approve = vi.fn().mockReturnValue(of(device({ deviceId: 'device-new' })));
    const pending = vi.fn().mockResolvedValue(PENDING_KDS);
    await render({ list: () => Promise.resolve([]), approve, pending });

    const host = fixture.nativeElement as HTMLElement;
    const codeField = host.querySelector(
      '[data-testid="devices-form-usercode"]',
    ) as HTMLInputElement;
    codeField.value = 'ABCD-1234';
    codeField.dispatchEvent(new Event('input'));
    const nameField = host.querySelector(
      '[data-testid="devices-form-displayname"]',
    ) as HTMLInputElement;
    nameField.value = 'Pass tablet';
    nameField.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="devices-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(approve).toHaveBeenCalledTimes(1);
    expect(approve).toHaveBeenCalledWith(SCOPE, 'ABCD-1234', 'Pass tablet', 'KITCHEN_KDS');
    expect(host.querySelector('[data-testid="devices-form-error"]')).toBeNull();
    // The newly approved device is reflected without a page reload.
    expect(host.querySelector('[data-testid="devices-active-table"]')?.textContent).toContain(
      'Pass tablet',
    );
  });

  it('refuses to submit an approval with an empty user code', async () => {
    const approve = vi.fn();
    await render({ list: () => Promise.resolve([]), approve });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="devices-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(approve).not.toHaveBeenCalled();
  });

  it('surfaces a refused (already-approved) user code without crashing', async () => {
    const error = new ApiError('RESOURCE_CONFLICT', 409, null, 'corr-1');
    const approve = vi.fn().mockReturnValue(throwError(() => error));
    const pending = vi.fn().mockResolvedValue(PENDING_KDS);
    await render({ list: () => Promise.resolve([]), approve, pending });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="devices-form-usercode"]') as HTMLInputElement).value =
      'ABCD-1234';
    host.querySelector('[data-testid="devices-form-usercode"]')?.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="devices-form-displayname"]') as HTMLInputElement).value =
      'Pass tablet';
    host
      .querySelector('[data-testid="devices-form-displayname"]')
      ?.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="devices-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="devices-form-error"]')).not.toBeNull();
  });

  it('revokes a device only once a reason is typed, and reloads the list', async () => {
    const revoke = vi.fn().mockReturnValue(of({ changed: true }));
    const list = vi
      .fn()
      .mockResolvedValueOnce([device()])
      .mockResolvedValueOnce([
        device({
          status: 'REVOKED',
          revokedBy: 'manager-subject',
          revokedAt: '2026-09-14T09:00:00Z',
          revokedReason: 'Lost during move',
        }),
      ]);
    await render({ list, revoke });

    const host = fixture.nativeElement as HTMLElement;
    (
      host.querySelector('[data-testid="devices-revoke-start-device-1"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const confirmButton = host.querySelector(
      '[data-testid="devices-revoke-confirm-device-1"]',
    ) as HTMLButtonElement;
    // The reason field is empty — confirming must be disabled, not silently allowed.
    expect(confirmButton.disabled).toBe(true);

    const reasonField = host.querySelector(
      '[data-testid="devices-revoke-reason-device-1"]',
    ) as HTMLInputElement;
    reasonField.value = 'Lost during move';
    reasonField.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(confirmButton.disabled).toBe(false);
    confirmButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(revoke).toHaveBeenCalledTimes(1);
    expect(revoke).toHaveBeenCalledWith(SCOPE, 'device-1', 'Lost during move');
    expect(list).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-testid="devices-revoked-table"]')?.textContent).toContain(
      'Lost during move',
    );
  });

  it('cancels a revoke in progress without calling the API', async () => {
    const revoke = vi.fn();
    await render({ list: () => Promise.resolve([device()]), revoke });

    const host = fixture.nativeElement as HTMLElement;
    (
      host.querySelector('[data-testid="devices-revoke-start-device-1"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="devices-revoke-reason-device-1"]')).not.toBeNull();

    const cancelButton = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Cancel',
    ) as HTMLButtonElement;
    cancelButton.click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="devices-revoke-reason-device-1"]')).toBeNull();
    expect(revoke).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------------
  // ADR 0151: the class a device asked for, the class it is approved as, a wall's station and silence
  // ---------------------------------------------------------------------

  function type(testid: string, value: string): void {
    const field = (fixture.nativeElement as HTMLElement).querySelector(
      `[data-testid="${testid}"]`,
    ) as HTMLInputElement;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function click(testid: string): void {
    (
      (fixture.nativeElement as HTMLElement).querySelector(
        `[data-testid="${testid}"]`,
      ) as HTMLButtonElement
    ).click();
  }

  it('shows the approver what a typed code claims before they choose the class to approve it as', async () => {
    const pending = vi.fn().mockResolvedValue(PENDING_KDS);
    await render({ list: () => Promise.resolve([]), pending });

    type('devices-form-usercode', 'ABCD-1234');
    click('devices-form-lookup');
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(pending).toHaveBeenCalledWith(SCOPE, 'ABCD-1234');
    expect(host.querySelector('[data-testid="devices-form-claim"]')?.textContent).toContain(
      'Cook’s touch board',
    );
    const options = Array.from(
      host.querySelectorAll<HTMLOptionElement>('[data-testid="devices-form-class"] option'),
    ).map((option) => option.value);
    expect(options, 'a touch request may be approved as itself or as less').toEqual([
      'KITCHEN_KDS',
      'KITCHEN_VDU',
    ]);
  });

  it('offers a wall request only the wall: an approval narrows and never widens', async () => {
    const pending = vi.fn().mockResolvedValue(PENDING_VDU);
    await render({ list: () => Promise.resolve([]), pending });

    type('devices-form-usercode', 'WALL-0001');
    click('devices-form-lookup');
    await flushMicrotasks();
    fixture.detectChanges();

    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLOptionElement>(
        '[data-testid="devices-form-class"] option',
      ),
    ).map((option) => option.value);
    expect(options).toEqual(['KITCHEN_VDU']);
  });

  it('approves a tablet as a wall display when the approver chooses less than it asked for', async () => {
    const approve = vi.fn().mockReturnValue(of(wall({ requestedClass: 'KITCHEN_KDS' })));
    const pending = vi.fn().mockResolvedValue(PENDING_KDS);
    await render({ list: () => Promise.resolve([]), pending, approve });

    type('devices-form-usercode', 'ABCD-1234');
    click('devices-form-lookup');
    await flushMicrotasks();
    fixture.detectChanges();
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="devices-form-class"]',
    ) as HTMLSelectElement;
    select.value = 'KITCHEN_VDU';
    select.dispatchEvent(new Event('change'));
    type('devices-form-displayname', 'Grill TV');
    click('devices-form-submit');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(approve).toHaveBeenCalledWith(SCOPE, 'ABCD-1234', 'Grill TV', 'KITCHEN_VDU');
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="devices-class-wall-1"]')?.textContent).toContain(
      'Wall display',
    );
    expect(host.querySelector('[data-testid="devices-requested-as"]')?.textContent).toContain(
      'Cook’s touch board',
    );
  });

  it('does not approve a code nothing is waiting on, and says so', async () => {
    const approve = vi.fn();
    const pending = vi.fn().mockResolvedValue(null);
    await render({ list: () => Promise.resolve([]), pending, approve });

    type('devices-form-usercode', 'NOPE-0000');
    type('devices-form-displayname', 'Grill TV');
    click('devices-form-submit');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(approve).not.toHaveBeenCalled();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="devices-form-notfound"]'),
    ).not.toBeNull();
  });

  it('forgets a claim when the code changes: it described the old code', async () => {
    const pending = vi.fn().mockResolvedValue(PENDING_VDU);
    await render({ list: () => Promise.resolve([]), pending });
    type('devices-form-usercode', 'WALL-0001');
    click('devices-form-lookup');
    await flushMicrotasks();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="devices-form-claim"]'),
    ).not.toBeNull();

    type('devices-form-usercode', 'WALL-0002');

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="devices-form-claim"]'),
    ).toBeNull();
  });

  it('lets a manager point a wall at a station, sending the version the form was opened at as If-Match', async () => {
    const configureDisplay = vi.fn().mockReturnValue(
      of({
        station: {
          stationId: 'station-grill',
          code: 'GRILL',
          displayNameRu: 'Гриль',
          displayNameUz: 'Gril',
          displayNameEn: 'Grill',
        },
        lastReadAt: '2026-09-14T08:05:00Z',
        version: 4,
        notSeen: false,
        notSeenAfterMinutes: 5,
      }),
    );
    await render({ list: () => Promise.resolve([wall()]), configureDisplay }, {}, [station()]);

    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="devices-station-wall-1"]',
    ) as HTMLSelectElement;
    expect(select.value, 'the whole branch until a station is chosen').toBe('');
    select.value = 'station-grill';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    click('devices-station-save-wall-1');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(configureDisplay).toHaveBeenCalledWith(SCOPE, 'wall-1', 'station-grill', 3);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="devices-station-save-wall-1"]',
      ),
      'saved: nothing left to save',
    ).toBeNull();
  });

  it('offers a touch display no station, and no save until a station is chosen', async () => {
    await render({ list: () => Promise.resolve([device(), wall()]) }, {}, [station()]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="devices-station-device-1"]')).toBeNull();
    expect(host.querySelector('[data-testid="devices-station-wall-1"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="devices-station-save-wall-1"]')).toBeNull();
  });

  it('reloads and says so when another manager changed the wall first (STALE_VERSION)', async () => {
    const stale = new ApiError('STALE_VERSION', 409, null, 'corr-1');
    const configureDisplay = vi.fn().mockReturnValue(throwError(() => stale));
    const list = vi.fn().mockResolvedValue([wall()]);
    await render({ list, configureDisplay }, {}, [station()]);

    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="devices-station-wall-1"]',
    ) as HTMLSelectElement;
    select.value = 'station-grill';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    click('devices-station-save-wall-1');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(list).toHaveBeenCalledTimes(2);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="devices-station-error"]')
        ?.textContent,
    ).toContain('Someone else changed this display');
  });

  it('says when a wall was last seen, and warns when it has gone quiet', async () => {
    await render({
      list: () =>
        Promise.resolve([
          wall(),
          wall({
            deviceId: 'wall-2',
            displayName: 'Bar TV',
            display: {
              station: null,
              lastReadAt: null,
              version: 1,
              notSeen: true,
              notSeenAfterMinutes: 5,
            },
          }),
        ]),
    });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="devices-lastseen-wall-1"]')?.textContent).toContain(
      'Seen 14.09.2026 08:05',
    );
    const quiet = host.querySelector('[data-testid="devices-lastseen-wall-2"]') as HTMLElement;
    expect(quiet.textContent).toContain('Not seen');
    expect(quiet.classList.contains('devices__notseen')).toBe(true);
  });
});
