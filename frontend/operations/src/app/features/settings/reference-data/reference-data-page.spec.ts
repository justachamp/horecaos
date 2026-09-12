import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation, LocationOption } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { LocationView, LocationsApi } from '../locations/locations-api';
import { BusinessCalendarApi, TenantCalendar } from './business-calendar-api';
import { BranchTag, BranchTagAssignment, BranchTagsApi } from './branch-tags-api';
import { ReasonResponse, ReferenceDataApi, SlaBucketSetView } from './reference-data-api';
import { ReferenceDataPage } from './reference-data-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const CANCELLATION_REASON: ReasonResponse = {
  id: 'reason-1',
  kind: 'CANCELLATION',
  systemCategory: 'OUT_OF_STOCK',
  internalName: 'Не дозвонились',
  stockDisposition: 'RELEASE',
  liabilityParty: 'CUSTOMER',
  customerRefund: 'NONE',
  allowedFulfillmentModes: null,
  customerTexts: { ru: 'Не удалось связаться с вами', 'uz-Latn': '...', en: '...' },
  status: 'ACTIVE',
  version: 1,
  updatedAt: '2026-08-01T00:00:00Z',
};

const COMPLETION_REASON: ReasonResponse = {
  id: 'reason-2',
  kind: 'COMPLETION',
  systemCategory: 'DELIVERED',
  internalName: 'Доставлен',
  stockDisposition: null,
  liabilityParty: null,
  customerRefund: null,
  allowedFulfillmentModes: ['DELIVERY'],
  customerTexts: { ru: 'Доставлен', 'uz-Latn': '...', en: '...' },
  status: 'ACTIVE',
  version: 1,
  updatedAt: '2026-08-01T00:00:00Z',
};

const CALENDAR: TenantCalendar = {
  timezone: 'Asia/Tashkent',
  businessDayStart: '00:00',
  boundaryVersion: 1,
  recutCompletedThrough: null,
  weekendDays: [],
  holidays: [],
};

const SLA_BUCKET_SET: SlaBucketSetView = {
  version: 1,
  buckets: [
    { code: 'UNDER_30', fromMinutes: 0, toMinutesExclusive: 30 },
    { code: 'OVER_60', fromMinutes: 60, toMinutesExclusive: null },
  ],
};

const BRANCH_TAG: BranchTag = {
  tagId: 'tag-1',
  code: 'has-parking',
  displayName: 'Есть парковка',
  status: 'ACTIVE',
};
const ASSIGNMENT: BranchTagAssignment = {
  locationId: 'location-1',
  tagId: 'tag-1',
  assignedAt: '2026-08-01T00:00:00Z',
};
const LOCATION_OPTION: LocationOption = {
  id: 'location-1',
  displayName: 'Чиланзар',
  status: 'ACTIVE',
};
const LOCATION_VIEW: LocationView = {
  id: 'location-1',
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  code: 'CHI',
  slug: 'chilonzor',
  displayName: 'Чиланзар',
  timezone: 'Asia/Tashkent',
  status: 'ACTIVE',
  addressLine: null,
  district: null,
  city: null,
  landmark: null,
  contactPhone: null,
  latitude: null,
  longitude: null,
  coordinateSource: 'NOT_GEOCODED',
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  readonly options = signal<readonly LocationOption[]>([LOCATION_OPTION]);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ReferenceDataPage', () => {
  let fixture: ComponentFixture<ReferenceDataPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    categories: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    archive: ReturnType<typeof vi.fn>;
    slaBucketSet: ReturnType<typeof vi.fn>;
  };
  let calendarApi: { get: ReturnType<typeof vi.fn>; setWeekend: ReturnType<typeof vi.fn> };
  let tagsApi: {
    list: ReturnType<typeof vi.fn>;
    assignments: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
  };
  let locationsApi: { list: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = {
      list: vi
        .fn()
        .mockImplementation((_scope: LocationScope, kind: string) =>
          Promise.resolve(kind === 'CANCELLATION' ? [CANCELLATION_REASON] : [COMPLETION_REASON]),
        ),
      categories: vi.fn().mockResolvedValue(['OUT_OF_STOCK', 'CUSTOMER_UNREACHABLE']),
      create: vi.fn().mockResolvedValue('reason-3'),
      update: vi.fn().mockResolvedValue(2),
      archive: vi.fn().mockResolvedValue(undefined),
      slaBucketSet: vi.fn().mockResolvedValue(SLA_BUCKET_SET),
    };
    calendarApi = {
      get: vi.fn().mockResolvedValue(CALENDAR),
      setWeekend: vi.fn().mockResolvedValue(undefined),
    };
    tagsApi = {
      list: vi.fn().mockResolvedValue([BRANCH_TAG]),
      assignments: vi.fn().mockResolvedValue([ASSIGNMENT]),
      create: vi.fn().mockResolvedValue('tag-2'),
    };
    locationsApi = {
      list: vi.fn().mockResolvedValue([LOCATION_VIEW]),
    };

    await TestBed.configureTestingModule({
      imports: [ReferenceDataPage],
      providers: [
        { provide: ReferenceDataApi, useValue: api },
        { provide: BusinessCalendarApi, useValue: calendarApi },
        { provide: BranchTagsApi, useValue: tagsApi },
        { provide: LocationsApi, useValue: locationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReferenceDataPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists cancellation reasons with both the internal name and the customer text side by side', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Не дозвонились');
    expect(text).toContain('Не удалось связаться с вами');
  });

  it("renders a completion reason's allowed fulfilment modes, once typed on both sides and shown nowhere", () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Доставлен');
    expect(text).toContain('DELIVERY');
  });

  it('renders the business calendar, the read-only SLA version card, and the branch tag registry', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Business calendar');
    expect(text).toContain('Asia/Tashkent');
    expect(text).toContain('SLA boundaries');
    expect(text).toContain('Version 1');
    expect(text).toContain('Branch tags');
    expect(text).toContain('Есть парковка');
  });

  it('builds the branch-tag matrix from the brand-wide location list, not the shell scope-bar signal', () => {
    // CurrentLocation.options stays empty for an operator who resolved
    // through a direct LOCATION grant (see that signal's own doc comment) —
    // the matrix must still render for that persona, from LocationsApi.list
    // instead.
    expect(locationsApi.list).toHaveBeenCalledWith(SCOPE);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Чиланзар');
    const checkbox = fixture.nativeElement.querySelector(
      '.matrix__cell input[type="checkbox"]',
    ) as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
  });

  it('creates a cancellation reason with the stock/liability/refund posture set once, up front', async () => {
    const addButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.link'),
    ).filter((el) => el.textContent?.includes('Add')) as HTMLButtonElement[];
    addButtons[0].click();
    fixture.detectChanges();

    const setValue = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#reason-internal-name', 'Ресторан закрыт');
    setValue('#reason-text-ru', 'Ресторан временно не принимает заказы');
    setValue('#reason-text-uz', '...');
    setValue('#reason-text-en', '...');
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.dialog__actions button'),
    ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();

    expect(api.create).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({
        kind: 'CANCELLATION',
        internalName: 'Ресторан закрыт',
        stockDisposition: 'RELEASE',
        liabilityParty: 'TENANT',
      }),
    );
  });

  it('refuses to create a completion reason with no fulfilment mode selected', () => {
    const addButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.link'),
    ).filter((el) => el.textContent?.includes('Add')) as HTMLButtonElement[];
    addButtons[1].click();
    fixture.detectChanges();

    const setValue = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#reason-internal-name', 'Заказ забран');
    setValue('#reason-text-ru', 'Заказ забран курьером');
    setValue('#reason-text-uz', '...');
    setValue('#reason-text-en', '...');
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.dialog__actions button'),
    ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
  });

  it('never offers delete, only disable, and confirms before disabling', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const disableButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.link'),
    ).find((el) => el.textContent?.includes('Disable')) as HTMLButtonElement;
    disableButton.click();
    await flushMicrotasks();

    expect(api.archive).toHaveBeenCalledWith(SCOPE, 'reason-1', 1);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Delete');
  });

  it('warns that editing creates a new version, and saves through the versioned PUT', async () => {
    const editButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.link'),
    ).find((el) => el.textContent?.includes('Edit')) as HTMLButtonElement;
    editButton.click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Saving creates a new version');

    const saveButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Save') as HTMLButtonElement;
    saveButton.click();
    await flushMicrotasks();

    expect(api.update).toHaveBeenCalledWith(
      SCOPE,
      'reason-1',
      expect.objectContaining({ internalName: 'Не дозвонились' }),
      1,
    );
  });
});
