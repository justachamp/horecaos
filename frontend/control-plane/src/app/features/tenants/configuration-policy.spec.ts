import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ApiError } from '../../core/api/problem';
import { ConfigurationPolicy } from './configuration-policy';
import {
  ConfigurationApi,
  ConfigurationKeyView,
  ConfigurationResolutionView,
  ConfigurationValueView,
  SetConfigurationValueRequest,
} from '../platform-config/configuration-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

// A writable key, per configuration-policy.ts's own WRITABLE_KEY_CODES.
const ENFORCEMENT_CEILING: ConfigurationKeyView = {
  code: 'commercial.enforcement_ceiling',
  valueType: 'String',
  defaultValue: 'METER_ONLY',
  settableScopes: ['PLATFORM', 'TENANT'],
  owningModule: 'commercial',
  tenantVisible: false,
  explicitNullTerminates: false,
  description: 'The strongest enforcement mode.',
};

// Registered but consumed nowhere -- must stay read-only.
const CART_EXPIRY: ConfigurationKeyView = {
  code: 'ordering.cart_expiry_minutes',
  valueType: 'Integer',
  defaultValue: 60,
  settableScopes: ['PLATFORM', 'TENANT', 'BRAND', 'LOCATION'],
  owningModule: 'ordering',
  tenantVisible: true,
  explicitNullTerminates: false,
  description: 'Minutes an untouched cart stays active before expiring.',
};

function resolutionFor(value: unknown, currentVersionAtScope: number | null): ConfigurationResolutionView {
  return {
    keyCode: 'ignored',
    value,
    cameFromDefault: currentVersionAtScope === null,
    source: currentVersionAtScope === null ? 'CODE_DEFAULT' : 'SCOPED_VALUE',
    winningScope: currentVersionAtScope === null ? null : 'TENANT',
    inspectedLevels: [],
    describe: '',
    currentVersionAtScope,
  };
}

class FakeConfigurationApi {
  readonly listKeys = vi.fn<() => Promise<ConfigurationKeyView[]>>();
  readonly resolve =
    vi.fn<
      (
        keyCode: string,
        scopeType: string,
        tenantId?: string,
        brandId?: string,
        locationId?: string,
      ) => Promise<ConfigurationResolutionView>
    >();
  readonly setValue =
    vi.fn<(keyCode: string, request: SetConfigurationValueRequest) => Promise<ConfigurationValueView>>();
}

async function fillPicker(
  fixture: ComponentFixture<ConfigurationPolicy>,
  code: string,
  scopeType: string,
  tenantId: string,
): Promise<void> {
  const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
  select.value = code;
  select.dispatchEvent(new Event('change'));
  fixture.detectChanges();

  const scopeSelect = fixture.nativeElement.querySelectorAll('select')[1] as HTMLSelectElement;
  scopeSelect.value = scopeType;
  scopeSelect.dispatchEvent(new Event('change'));
  fixture.detectChanges();

  const tenantInput = fixture.nativeElement.querySelector('input[type="text"]') as HTMLInputElement;
  tenantInput.value = tenantId;
  tenantInput.dispatchEvent(new Event('input'));
  fixture.detectChanges();

  fixture.nativeElement.querySelector('form.pickerForm').dispatchEvent(new Event('submit', { cancelable: true }));
  await fixture.whenStable();
  fixture.detectChanges();
}

describe('ConfigurationPolicy', () => {
  let fixture: ComponentFixture<ConfigurationPolicy>;
  let api: FakeConfigurationApi;

  beforeEach(async () => {
    api = new FakeConfigurationApi();
    api.listKeys.mockResolvedValue([ENFORCEMENT_CEILING, CART_EXPIRY]);

    await TestBed.configureTestingModule({
      imports: [ConfigurationPolicy],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: ConfigurationApi, useValue: api },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ConfigurationPolicy);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  it('offers a Save control for a key with a live consumer, at a scope it declares settable', async () => {
    api.resolve.mockResolvedValue(resolutionFor('METER_ONLY', null));

    await fillPicker(fixture, 'commercial.enforcement_ceiling', 'TENANT', 'tenant-1');

    expect(fixture.nativeElement.querySelector('.writeForm')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('ничего не читает');
  });

  it('shows a key with no consumer as read-only, with no Save control', async () => {
    api.resolve.mockResolvedValue(resolutionFor(60, null));

    await fillPicker(fixture, 'ordering.cart_expiry_minutes', 'TENANT', 'tenant-1');

    expect(fixture.nativeElement.querySelector('.writeForm')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('ничего не читает');
  });

  it('refuses to offer Save at a scope the key does not declare settable', async () => {
    // enforcement_ceiling is PLATFORM/TENANT only.
    api.resolve.mockResolvedValue(resolutionFor('METER_ONLY', null));

    await fillPicker(fixture, 'commercial.enforcement_ceiling', 'LOCATION', 'tenant-1');
    const brandInput = fixture.nativeElement.querySelectorAll('input[type="text"]')[1] as HTMLInputElement;
    brandInput.value = 'brand-1';
    brandInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const locationInput = fixture.nativeElement.querySelectorAll('input[type="text"]')[2] as HTMLInputElement;
    locationInput.value = 'location-1';
    locationInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('form.pickerForm').dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.writeForm')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('нельзя задать на этом уровне');
  });

  it('hides the write form the instant the scope picker no longer matches what was resolved', async () => {
    api.resolve.mockResolvedValue(resolutionFor('METER_ONLY', null));
    await fillPicker(fixture, 'commercial.enforcement_ceiling', 'TENANT', 'tenant-1');
    expect(fixture.nativeElement.querySelector('.writeForm')).not.toBeNull();

    const tenantInput = fixture.nativeElement.querySelector('input[type="text"]') as HTMLInputElement;
    tenantInput.value = 'tenant-2';
    tenantInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.writeForm')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Рассчитайте заново');
  });

  it('saves a typed value with the version last resolved, then refreshes the resolution', async () => {
    api.resolve.mockResolvedValueOnce(resolutionFor('METER_ONLY', 3));
    await fillPicker(fixture, 'commercial.enforcement_ceiling', 'TENANT', 'tenant-1');

    const stringInput = fixture.nativeElement.querySelector('.writeForm input[type="text"]') as HTMLInputElement;
    stringInput.value = 'METERED_LIMIT';
    stringInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const reasonInput = fixture.nativeElement.querySelectorAll('.writeForm input[type="text"]')[1] as HTMLInputElement;
    reasonInput.value = 'Raising the ceiling for a pilot tenant';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    api.setValue.mockResolvedValue({
      id: 'row-1',
      keyCode: 'commercial.enforcement_ceiling',
      scopeType: 'TENANT',
      value: 'METERED_LIMIT',
      explicitNull: false,
      version: 4,
    });
    api.resolve.mockResolvedValueOnce(resolutionFor('METERED_LIMIT', 4));

    fixture.nativeElement.querySelector('.writeForm').dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.setValue).toHaveBeenCalledWith(
      'commercial.enforcement_ceiling',
      expect.objectContaining({
        scopeType: 'TENANT',
        tenantId: 'tenant-1',
        explicitNull: false,
        stringValue: 'METERED_LIMIT',
        expectedVersion: 3,
        reason: 'Raising the ceiling for a pilot tenant',
      }),
    );
    // Point 5: after saving, the screen re-resolves rather than trusting its own echo.
    expect(api.resolve).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('METERED_LIMIT');
  });

  it('shows a translated error, not the raw code, when the server refuses a stale write', async () => {
    api.resolve.mockResolvedValue(resolutionFor('METER_ONLY', 3));
    await fillPicker(fixture, 'commercial.enforcement_ceiling', 'TENANT', 'tenant-1');

    const stringInput = fixture.nativeElement.querySelector('.writeForm input[type="text"]') as HTMLInputElement;
    stringInput.value = 'METERED_LIMIT';
    stringInput.dispatchEvent(new Event('input'));
    const reasonInput = fixture.nativeElement.querySelectorAll('.writeForm input[type="text"]')[1] as HTMLInputElement;
    reasonInput.value = 'Retry after a race';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    api.setValue.mockRejectedValue(new ApiError({ status: 409, code: 'STALE_VERSION' }));

    fixture.nativeElement.querySelector('.writeForm').dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('STALE_VERSION');
    expect(fixture.nativeElement.textContent).toContain('Кто-то изменил эту запись, пока вы её редактировали');
  });

  it('disables Save until a reason is entered', async () => {
    api.resolve.mockResolvedValue(resolutionFor('METER_ONLY', 3));
    await fillPicker(fixture, 'commercial.enforcement_ceiling', 'TENANT', 'tenant-1');

    const stringInput = fixture.nativeElement.querySelector('.writeForm input[type="text"]') as HTMLInputElement;
    stringInput.value = 'METERED_LIMIT';
    stringInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saveButton = fixture.nativeElement.querySelector('.writeForm button.primary') as HTMLButtonElement;
    expect(saveButton.disabled).toBe(true);
  });

  it('rejects a non-integer value for an Integer-typed key before it ever reaches the server', async () => {
    // ordering.cart_expiry_minutes has no consumer, so use a hypothetical
    // Integer key with a consumer instead: telemetry.track_retention_days.
    const trackRetention: ConfigurationKeyView = {
      code: 'telemetry.track_retention_days',
      valueType: 'Integer',
      defaultValue: 30,
      settableScopes: ['PLATFORM', 'TENANT'],
      owningModule: 'telemetry',
      tenantVisible: false,
      explicitNullTerminates: false,
      description: 'Days of track retention.',
    };
    api.listKeys.mockResolvedValue([ENFORCEMENT_CEILING, CART_EXPIRY, trackRetention]);
    fixture = TestBed.createComponent(ConfigurationPolicy);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    api.resolve.mockResolvedValue(resolutionFor(30, null));
    await fillPicker(fixture, 'telemetry.track_retention_days', 'TENANT', 'tenant-1');

    const numberInput = fixture.nativeElement.querySelector('.writeForm input[inputmode="numeric"]') as HTMLInputElement;
    numberInput.value = 'not-a-number';
    numberInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.writeForm .error')).not.toBeNull();
    const saveButton = fixture.nativeElement.querySelector('.writeForm button.primary') as HTMLButtonElement;
    expect(saveButton.disabled).toBe(true);
    expect(api.setValue).not.toHaveBeenCalled();
  });
});
