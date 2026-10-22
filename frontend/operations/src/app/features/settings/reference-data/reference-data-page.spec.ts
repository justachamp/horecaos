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
  customerTexts: {
    ru: 'Не удалось связаться с вами',
    'uz-Latn': 'Siz bilan bogʻlana olmadik',
    en: 'We could not reach you',
  },
  status: 'ACTIVE',
  version: 1,
  updatedAt: '2026-08-01T00:00:00Z',
};

const CANCELLATION_REASON_2: ReasonResponse = {
  id: 'reason-1b',
  kind: 'CANCELLATION',
  systemCategory: 'OUT_OF_STOCK',
  internalName: 'Дубликат заказа',
  stockDisposition: 'RELEASE',
  liabilityParty: 'TENANT',
  customerRefund: 'FULL',
  allowedFulfillmentModes: null,
  customerTexts: { ru: 'Дубликат', 'uz-Latn': '...', en: '...' },
  status: 'ACTIVE',
  version: 3,
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

const REMOVAL_REASON: ReasonResponse = {
  id: 'reason-removal-1',
  kind: 'LINE_REMOVAL',
  systemCategory: 'CUSTOMER_CHANGED_MIND',
  internalName: 'Клиент передумал',
  stockDisposition: 'RETURN_TO_STOCK',
  liabilityParty: 'CUSTOMER',
  customerRefund: null,
  allowedFulfillmentModes: null,
  customerTexts: { ru: 'Блюдо убрано по вашей просьбе', 'uz-Latn': '...', en: '...' },
  status: 'ACTIVE',
  version: 1,
  updatedAt: '2026-10-08T00:00:00Z',
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
  sortOrder: 0,
  seats: null,
  averageChequeAmount: null,
  averageChequeCurrency: null,
  hasParking: false,
  hasPlayground: false,
  virtualTourUrl: null,
  locales: [],
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
    localeSet: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    archive: ReturnType<typeof vi.fn>;
    reorder: ReturnType<typeof vi.fn>;
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
          Promise.resolve(
            kind === 'CANCELLATION'
              ? [CANCELLATION_REASON, CANCELLATION_REASON_2]
              : kind === 'LINE_REMOVAL'
                ? [REMOVAL_REASON]
                : [COMPLETION_REASON],
          ),
        ),
      categories: vi
        .fn()
        .mockImplementation((_scope: LocationScope, kind: string) =>
          Promise.resolve(
            kind === 'LINE_REMOVAL'
              ? ['CUSTOMER_CHANGED_MIND', 'ITEM_UNAVAILABLE']
              : ['OUT_OF_STOCK', 'CUSTOMER_UNREACHABLE'],
          ),
        ),
      localeSet: vi.fn().mockResolvedValue({
        locales: ['ru', 'uz-Latn', 'en'],
        defaultLocale: 'ru',
        configured: false,
      }),
      create: vi.fn().mockResolvedValue('reason-3'),
      update: vi.fn().mockResolvedValue(2),
      archive: vi.fn().mockResolvedValue(undefined),
      reorder: vi.fn().mockResolvedValue([
        { ...CANCELLATION_REASON_2, version: 4 },
        { ...CANCELLATION_REASON, version: 2 },
      ]),
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
    // The wording in the operator's own language (this spec reads the console in English), not a fixed ru.
    expect(text).toContain('We could not reach you');
    expect(text).not.toContain('Не удалось связаться с вами');
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

  it('pins ADR 0107 Decision 4: the SLA card is a version card, with no input and no button in it', () => {
    const card = (fixture.nativeElement as HTMLElement).querySelector('#sla-boundaries');
    expect(card, 'the SLA card is on the screen').not.toBeNull();
    // What it does carry: the version it was read at and one row per bucket the platform published.
    expect(card?.textContent).toContain('Version 1');
    expect(card?.querySelectorAll('tbody tr').length).toBe(SLA_BUCKET_SET.buckets.length);
    // What it must never carry: a control. The buckets are platform-fixed and versioned (ADR 0043), so a
    // field or a save button here would promise a tenant a boundary it cannot set.
    expect(
      card?.querySelectorAll('input, select, textarea, button, [contenteditable]').length,
    ).toBe(0);
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

  it('lists line-removal reasons (ADR 0169) in their own section, with a disposition and a liable party and no refund', () => {
    const section = (fixture.nativeElement as HTMLElement).querySelector('#removal-reasons');
    expect(section?.textContent).toContain('Клиент передумал');
    expect(section?.textContent).toContain('RETURN_TO_STOCK');
    expect(section?.textContent).toContain('CUSTOMER');
    expect(section?.textContent).not.toContain('Default refund');
    expect(section?.querySelectorAll('[data-testid="reason-row-LINE_REMOVAL"]')).toHaveLength(1);
  });

  async function openRemovalForm(): Promise<void> {
    const add = (fixture.nativeElement as HTMLElement).querySelector(
      '#removal-reasons .card__header .link',
    ) as HTMLButtonElement;
    add.click();
    fixture.detectChanges();
  }

  it('offers a removal reason the three dispositions that act on sold stock, and no refund posture', async () => {
    await openRemovalForm();
    const host = fixture.nativeElement as HTMLElement;

    const options = Array.from(host.querySelectorAll('#reason-stock option')).map((option) =>
      option.textContent?.trim(),
    );
    expect(options).toEqual(['RETURN_TO_STOCK', 'WRITE_OFF', 'NO_EFFECT']);
    expect(host.querySelector('#reason-refund')).toBeNull();
    expect(host.querySelector('#reason-liability')).not.toBeNull();
  });

  it('creates a removal reason with its disposition and liable party, and sends no refund', async () => {
    await openRemovalForm();
    const host = fixture.nativeElement as HTMLElement;
    const setValue = (id: string, value: string) => {
      const el = host.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#reason-internal-name', 'Ошибка кухни');
    setValue('#reason-text-ru', 'Блюдо убрано: ошибка на кухне');
    setValue('#reason-text-uz-Latn', '...');
    setValue('#reason-text-en', '...');
    const stock = host.querySelector('#reason-stock') as HTMLSelectElement;
    stock.value = 'WRITE_OFF';
    stock.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (
      Array.from(host.querySelectorAll('.dialog__actions button')).find((button) =>
        button.textContent?.includes('Create'),
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(api.create).toHaveBeenCalledTimes(1);
    const request = api.create.mock.calls[0][1];
    expect(request.kind).toBe('LINE_REMOVAL');
    expect(request.stockDisposition).toBe('WRITE_OFF');
    expect(request.liabilityParty).toBe('TENANT');
    expect(request.customerRefund).toBeUndefined();
    expect(request.allowedFulfillmentModes).toBeUndefined();
  });

  it('will not save «item unavailable» that returns to stock, and says why before the server would', async () => {
    await openRemovalForm();
    const host = fixture.nativeElement as HTMLElement;
    const setValue = (id: string, value: string) => {
      const el = host.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#reason-internal-name', 'Закончилось');
    setValue('#reason-text-ru', 'Блюдо закончилось');
    setValue('#reason-text-uz-Latn', '...');
    setValue('#reason-text-en', '...');
    const category = host.querySelector('#reason-category') as HTMLSelectElement;
    category.value = 'ITEM_UNAVAILABLE';
    category.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const submit = Array.from(host.querySelectorAll('.dialog__actions button')).find((button) =>
      button.textContent?.includes('Create'),
    ) as HTMLButtonElement;
    expect(host.querySelector('[data-testid="reason-unavailable-hint"]')).not.toBeNull();
    expect(submit.disabled).toBe(true);

    const stock = host.querySelector('#reason-stock') as HTMLSelectElement;
    stock.value = 'WRITE_OFF';
    stock.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="reason-unavailable-hint"]')).toBeNull();
    expect(submit.disabled).toBe(false);
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
    setValue('#reason-text-uz-Latn', '...');
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
    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '#completion-reasons .card__header .link',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const setValue = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#reason-internal-name', 'Заказ забран');
    setValue('#reason-text-ru', 'Заказ забран курьером');
    setValue('#reason-text-uz-Latn', '...');
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

  // Row 10.12 (ADR 0149, Decisions 4 and 5): the reason forms author in the tenant's languages.

  function fieldValues(host: HTMLElement, ids: readonly string[]): void {
    ids.forEach((id) => {
      const el = host.querySelector(id) as HTMLInputElement;
      el.value = 'text for ' + id;
      el.dispatchEvent(new Event('input'));
    });
  }

  async function reopenWithLocaleSet(
    locales: readonly string[],
    defaultLocale = locales[0],
  ): Promise<void> {
    api.localeSet.mockResolvedValue({ locales, defaultLocale, configured: true });
    fixture = TestBed.createComponent(ReferenceDataPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('asks the backend for the tenant’s languages and draws one customer-text field per language, in its order', async () => {
    await reopenWithLocaleSet(['uz-Latn', 'ru', 'kk']);
    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('#completion-reasons .card__header .link') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.localeSet).toHaveBeenCalledWith(SCOPE);
    const labels = Array.from(host.querySelectorAll('label[for^="reason-text-"]')).map((label) =>
      label.textContent?.trim(),
    );
    expect(labels).toEqual([
      'Customer text (Uzbek (Latin))',
      'Customer text (Russian)',
      'Customer text (Kazakh)',
    ]);
    expect(host.querySelector('#reason-text-en')).toBeNull();
  });

  it('will not create a reason until every language of the tenant is worded, and sends exactly those wordings', async () => {
    await reopenWithLocaleSet(['ru', 'uz-Latn', 'en', 'kk']);
    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('#removal-reasons .card__header .link') as HTMLButtonElement).click();
    fixture.detectChanges();
    fieldValues(host, [
      '#reason-internal-name',
      '#reason-text-ru',
      '#reason-text-uz-Latn',
      '#reason-text-en',
    ]);
    fixture.detectChanges();
    const submit = Array.from(host.querySelectorAll('.dialog__actions button')).find((button) =>
      button.textContent?.includes('Create'),
    ) as HTMLButtonElement;
    expect(
      submit.disabled,
      'three of four languages is the body the service answers with a 400',
    ).toBe(true);

    fieldValues(host, ['#reason-text-kk']);
    fixture.detectChanges();
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();

    expect(api.create.mock.calls[0][1].customerTexts).toEqual({
      ru: 'text for #reason-text-ru',
      'uz-Latn': 'text for #reason-text-uz-Latn',
      en: 'text for #reason-text-en',
      kk: 'text for #reason-text-kk',
    });
  });

  it('prefills an edit from the reason and sends back a wording in a language the form does not offer, unchanged', async () => {
    await reopenWithLocaleSet(['ru', 'uz-Latn', 'en']);
    api.list.mockImplementation((_scope: LocationScope, kind: string) =>
      Promise.resolve(
        kind === 'CANCELLATION'
          ? [
              {
                ...CANCELLATION_REASON,
                customerTexts: { ...CANCELLATION_REASON.customerTexts, kk: 'Жабылды' },
              },
            ]
          : [],
      ),
    );
    await reopenWithLocaleSet(['ru', 'uz-Latn', 'en']);
    const host = fixture.nativeElement as HTMLElement;
    (
      Array.from(host.querySelectorAll('.link')).find((el) =>
        el.textContent?.includes('Edit'),
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect((host.querySelector('#edit-text-ru') as HTMLInputElement).value).toBe(
      'Не удалось связаться с вами',
    );
    expect(host.querySelector('#edit-text-kk')).toBeNull();
    (
      Array.from(host.querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Save',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(api.update.mock.calls[0][2].customerTexts).toEqual({
      ru: 'Не удалось связаться с вами',
      'uz-Latn': 'Siz bilan bogʻlana olmadik',
      en: 'We could not reach you',
      kk: 'Жабылды',
    });
  });

  it('falls back to the registry’s content languages when the tenant’s set cannot be read', async () => {
    api.localeSet.mockRejectedValue(new Error('offline'));
    fixture = TestBed.createComponent(ReferenceDataPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('#removal-reasons .card__header .link') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(
      Array.from(host.querySelectorAll('input[id^="reason-text-"]')).map((input) => input.id),
    ).toEqual(['reason-text-ru', 'reason-text-uz-Latn', 'reason-text-en']);
  });

  // Row 10.10a: reasons can be reordered.

  it('moves a reason down and persists the whole new order, with the version sum as If-Match', async () => {
    (
      fixture.nativeElement.querySelector(
        '[data-testid="reason-move-down-CANCELLATION"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    // reason-1 is version 1, reason-1b is version 3 -- the sum (4), not the
    // max (3), is what catches a concurrent edit to either one.
    expect(api.reorder).toHaveBeenCalledWith(SCOPE, 'CANCELLATION', ['reason-1b', 'reason-1'], 4);
  });

  it('renders the server’s reordered list, versions already bumped, without a second read', async () => {
    (
      fixture.nativeElement.querySelector(
        '[data-testid="reason-move-down-CANCELLATION"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.list).toHaveBeenCalledTimes(3); // the initial load only (CANCELLATION + COMPLETION + LINE_REMOVAL)
    const rows = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="reason-row-CANCELLATION"]'),
    ) as HTMLElement[];
    expect(rows[0].textContent).toContain('Дубликат заказа');
    expect(rows[1].textContent).toContain('Не дозвонились');
  });

  it('disables move-up on the first row and move-down on the last row', () => {
    const rows = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="reason-row-CANCELLATION"]'),
    ) as HTMLElement[];
    const firstUp = rows[0].querySelector(
      '[data-testid="reason-move-up-CANCELLATION"]',
    ) as HTMLButtonElement;
    const lastDown = rows[1].querySelector(
      '[data-testid="reason-move-down-CANCELLATION"]',
    ) as HTMLButtonElement;
    expect(firstUp.disabled).toBe(true);
    expect(lastDown.disabled).toBe(true);
  });

  it('reloads the list and shows an error when a reorder is refused', async () => {
    api.reorder.mockRejectedValueOnce(new Error('STALE_VERSION'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="reason-move-down-CANCELLATION"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    // initial CANCELLATION+COMPLETION+LINE_REMOVAL, then reload() re-reads all three kinds.
    expect(api.list).toHaveBeenCalledTimes(6);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });
});
