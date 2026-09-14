import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { LocationDetailPane } from './location-detail-pane';
import {
  LocationsApi,
  LocationView,
  ScheduleSummaryView,
  ServiceSummaryResponse,
} from './locations-api';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const LOCATION: LocationView = {
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
  landmark: 'Chilanzar metro yonida',
  contactPhone: '+998712000000',
  latitude: 41.3,
  longitude: 69.2,
  coordinateSource: 'MERCHANT_PIN',
};

const SUMMARY: ServiceSummaryResponse = {
  mode: 'FOLLOW_SCHEDULE',
  effectiveMode: 'FOLLOW_SCHEDULE',
  reasonCode: null,
  effectiveUntil: null,
  maxConcurrentOrders: 20,
  openOrderCount: 7,
  bindings: [
    {
      fulfillmentMode: 'DELIVERY',
      scheduleId: 'schedule-1',
      scheduleName: 'Standard hours',
      acceptsScheduledOrders: true,
      sharedWithLocationCount: 3,
      rules: [{ dayOfWeek: 1, opensAt: '09:00', closesAt: '23:00' }],
      exceptions: [],
    },
  ],
  preparationBands: [
    {
      fulfillmentMode: 'DELIVERY',
      dayOfWeek: null,
      startsAt: '18:00',
      endsAt: '21:00',
      durationMinutes: 25,
      priority: 1,
    },
  ],
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

const SCHEDULES: readonly ScheduleSummaryView[] = [
  { id: 'schedule-1', name: 'Standard hours', acceptsScheduledOrders: true, boundLocationCount: 3 },
  { id: 'schedule-2', name: 'Ramadan hours', acceptsScheduledOrders: true, boundLocationCount: 1 },
];

describe('LocationDetailPane', () => {
  let fixture: ComponentFixture<LocationDetailPane>;
  let api: {
    profile: ReturnType<typeof vi.fn>;
    serviceSummary: ReturnType<typeof vi.fn>;
    describePlace: ReturnType<typeof vi.fn>;
    changeServiceState: ReturnType<typeof vi.fn>;
    setCapacity: ReturnType<typeof vi.fn>;
    listSchedules: ReturnType<typeof vi.fn>;
    replaceScheduleRules: ReturnType<typeof vi.fn>;
    upsertScheduleException: ReturnType<typeof vi.fn>;
    bindSchedule: ReturnType<typeof vi.fn>;
    replacePreparationBands: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      profile: vi.fn().mockResolvedValue(LOCATION),
      serviceSummary: vi.fn().mockResolvedValue(SUMMARY),
      describePlace: vi.fn().mockResolvedValue({ ...LOCATION, addressLine: 'New address' }),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
      setCapacity: vi.fn().mockResolvedValue(undefined),
      listSchedules: vi.fn().mockResolvedValue(SCHEDULES),
      replaceScheduleRules: vi.fn().mockResolvedValue(undefined),
      upsertScheduleException: vi.fn().mockResolvedValue(undefined),
      bindSchedule: vi.fn().mockResolvedValue(undefined),
      replacePreparationBands: vi.fn().mockResolvedValue(undefined),
    };

    await TestBed.configureTestingModule({
      imports: [LocationDetailPane],
      providers: [
        { provide: LocationsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LocationDetailPane);
    fixture.componentRef.setInput('locationId', 'location-1');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('merges the route’s locationId onto the operator’s own tenant/brand scope', () => {
    expect(api.profile).toHaveBeenCalledWith(SCOPE);
    expect(api.serviceSummary).toHaveBeenCalledWith(SCOPE);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Chilanzar');
  });

  it('shows the bound schedule’s grid and the shared-with-others warning on the Hours tab', () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Standard hours');
    expect(text).toContain('3');
  });

  it('shows capacity and preparation bands on the Load tab', () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[2] as HTMLButtonElement).click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('7');
    expect(text).toContain('25');
  });

  it('saves the address/phone through the cross-surface place endpoint', async () => {
    const editButton = fixture.nativeElement.querySelector('.primary') as HTMLButtonElement;
    editButton.click();
    fixture.detectChanges();

    const addressInput = fixture.nativeElement.querySelector('#place-address') as HTMLInputElement;
    addressInput.value = 'New address';
    addressInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saveButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Save')) as HTMLButtonElement;
    saveButton.click();
    await flushMicrotasks();

    expect(api.describePlace).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ addressLine: 'New address' }),
    );
  });

  it('sends the existing landmark along with an untouched edit, and a new one when it is changed', async () => {
    const editButton = () => fixture.nativeElement.querySelector('.primary') as HTMLButtonElement;
    const saveButton = () =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
      ).find((button) => button.textContent?.includes('Save')) as HTMLButtonElement;

    editButton().click();
    fixture.detectChanges();

    // Untouched: the field the form opened with is what P32's fix relies on
    // being sent, since only what this form sends is carried through.
    saveButton().click();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(api.describePlace).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ landmark: 'Chilanzar metro yonida' }),
    );

    // Save swaps the edit form back out for the read-only view and a fresh
    // "Edit" button, so this has to be re-queried rather than reusing the
    // detached one above.
    editButton().click();
    fixture.detectChanges();
    const landmarkInput = fixture.nativeElement.querySelector(
      '#place-landmark',
    ) as HTMLInputElement;
    landmarkInput.value = 'Next to the blue mosque';
    landmarkInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    saveButton().click();
    await flushMicrotasks();
    expect(api.describePlace).toHaveBeenLastCalledWith(
      SCOPE,
      expect.objectContaining({ landmark: 'Next to the blue mosque' }),
    );
  });

  /**
   * P32 second-pass adversarial review: emptying the field must send the
   * explicit clear signal, not merely omit `landmark` -- an omission reads
   * to the backend as "this write never touched the landmark" and the stale
   * value would survive the save while the console showed it as gone.
   */
  it('sends the explicit clear signal when the landmark field is emptied and saved', async () => {
    const editButton = () => fixture.nativeElement.querySelector('.primary') as HTMLButtonElement;
    const saveButton = () =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
      ).find((button) => button.textContent?.includes('Save')) as HTMLButtonElement;

    editButton().click();
    fixture.detectChanges();
    const landmarkInput = fixture.nativeElement.querySelector(
      '#place-landmark',
    ) as HTMLInputElement;
    landmarkInput.value = '';
    landmarkInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    saveButton().click();
    await flushMicrotasks();

    expect(api.describePlace).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ landmark: undefined, clearLandmark: true }),
    );
  });

  it('warns with the shared-location count before saving a shared schedule’s hours, then saves', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="edit-hours-DELIVERY"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    // Add a Tuesday window -- SUMMARY's only rule is Monday -- so the whole
    // rules array genuinely differs from what was loaded.
    (
      fixture.nativeElement.querySelector(
        '[data-testid="q-schedule-grid-add-window-2"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="save-hours"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    // sharedWithLocationCount is 3 (including this location); the dialog
    // names the two *other* locations, not the raw stored count.
    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('2'));
    expect(api.replaceScheduleRules).toHaveBeenCalledWith(
      SCOPE,
      'schedule-1',
      expect.arrayContaining([
        expect.objectContaining({ dayOfWeek: 1 }),
        expect.objectContaining({ dayOfWeek: 2 }),
      ]),
    );

    confirmSpy.mockRestore();
  });

  it('does not save a shared schedule’s hours when the confirm is declined', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="edit-hours-DELIVERY"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-testid="q-schedule-grid-add-window-2"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="save-hours"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(api.replaceScheduleRules).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('requires a label and reason before saving a newly added dated exception', async () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-testid="edit-hours-DELIVERY"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const dateField = fixture.nativeElement.querySelector(
      '[data-testid="q-schedule-grid-new-exception-date"]',
    ) as HTMLInputElement;
    dateField.value = '2027-01-01';
    (
      fixture.nativeElement.querySelector(
        '[data-testid="q-schedule-grid-add-exception"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="save-hours"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.upsertScheduleException).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });

  it('rebinds a fulfilment mode to a different timetable from the picker', async () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="rebind-DELIVERY"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.listSchedules).toHaveBeenCalledWith(SCOPE);

    const select = fixture.nativeElement.querySelector(
      '[data-testid="rebind-select"]',
    ) as HTMLSelectElement;
    select.value = 'schedule-2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="confirm-rebind"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(api.bindSchedule).toHaveBeenCalledWith(SCOPE, {
      fulfillmentMode: 'DELIVERY',
      scheduleId: 'schedule-2',
    });
  });

  it('edits and saves the preparation bands as a whole set', async () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[2] as HTMLButtonElement).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="edit-bands"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="add-band"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="save-bands"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(api.replacePreparationBands).toHaveBeenCalledWith(
      SCOPE,
      expect.arrayContaining([
        expect.objectContaining({ startsAt: '18:00', endsAt: '21:00' }),
        expect.objectContaining({ startsAt: '09:00', endsAt: '17:00' }),
      ]),
    );
  });

  it('refuses to save a preparation band that wraps past midnight', async () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[2] as HTMLButtonElement).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector('[data-testid="edit-bands"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const startInput = fixture.nativeElement.querySelector(
      '[data-testid="band-row-0"] input[type="time"]',
    ) as HTMLInputElement;
    startInput.value = '23:00';
    startInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="save-bands"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(api.replacePreparationBands).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });

  it('requires a reason code before forcing the location closed', async () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('#state-mode') as HTMLSelectElement;
    select.value = 'FORCE_CLOSED';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const applyButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Apply')) as HTMLButtonElement;
    applyButton.click();
    await flushMicrotasks();

    expect(api.changeServiceState).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });
});
