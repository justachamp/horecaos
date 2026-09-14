import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { DevicesPage } from './devices-page';
import { KitchenDeviceView, KitchenDevicesApi } from './devices-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function device(overrides: Partial<KitchenDeviceView> = {}): KitchenDeviceView {
  return {
    deviceId: 'device-1',
    locationId: 'l1',
    deviceClass: 'KITCHEN_KDS',
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

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DevicesPage', () => {
  let fixture: ComponentFixture<DevicesPage>;

  async function render(
    devicesApi: Partial<KitchenDevicesApi>,
    locationOverrides: { scope?: LocationScope | null; denied?: boolean } = {},
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
    await render({ list: () => Promise.resolve([]), approve });

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
    expect(approve).toHaveBeenCalledWith(SCOPE, 'ABCD-1234', 'Pass tablet');
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
    await render({ list: () => Promise.resolve([]), approve });

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
});
