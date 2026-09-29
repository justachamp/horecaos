import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSetView } from '../../../core/i18n/locale-labels';
import { CommentPresetsApi, PresetResponse } from './comment-presets-api';
import { CommentPresetsPage } from './comment-presets-page';

const TENANT_ID = 'tenant-1';

const NO_ONIONS: PresetResponse = {
  presetId: 'preset-1',
  code: 'NO_ONIONS',
  labelRu: 'Без лука',
  labelUz: 'Piyozsiz',
  labelEn: 'No onions',
  labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions' },
  posModifierCode: 'MOD-NO-ONION',
  sortOrder: 0,
  status: 'ACTIVE',
  version: 3,
};

const WELL_DONE: PresetResponse = {
  presetId: 'preset-2',
  code: 'WELL_DONE',
  labelRu: 'Хорошо прожарить',
  labelUz: 'Yaxshi qovurish',
  labelEn: 'Well done',
  labels: { ru: 'Хорошо прожарить', 'uz-Latn': 'Yaxshi qovurish', en: 'Well done' },
  posModifierCode: null,
  sortOrder: 1,
  status: 'ACTIVE',
  version: 1,
};

/** The tenant whose brands have not chosen a set: the platform triple, ru first. */
const PLATFORM_SET: LocaleSetView = {
  locales: ['ru', 'uz-Latn', 'en'],
  defaultLocale: 'ru',
  configured: false,
};

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CommentPresetsPage', () => {
  let fixture: ComponentFixture<CommentPresetsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    localeSet: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
  };
  let tenant: FakeCurrentTenant;

  async function setUp(): Promise<void> {
    tenant = new FakeCurrentTenant();
    await TestBed.configureTestingModule({
      imports: [CommentPresetsPage],
      providers: [
        { provide: CommentPresetsApi, useValue: api },
        { provide: CurrentTenant, useValue: tenant },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CommentPresetsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function input(testId: string): HTMLInputElement {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
  }

  function type(testId: string, value: string): void {
    const el = input(testId);
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    api = {
      list: vi.fn().mockResolvedValue([WELL_DONE, NO_ONIONS]),
      localeSet: vi.fn().mockResolvedValue(PLATFORM_SET),
      create: vi.fn(),
      update: vi.fn(),
    };
  });

  it('loads the tenant-scoped vocabulary at TENANT scope and sorts it by sortOrder then code', async () => {
    await setUp();

    expect(api.list).toHaveBeenCalledWith(TENANT_ID);
    expect(api.localeSet).toHaveBeenCalledWith(TENANT_ID);
    const rows = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="comment-presets-table"] tbody tr'),
    ) as HTMLElement[];
    expect(rows[0].textContent).toContain('NO_ONIONS');
    expect(rows[1].textContent).toContain('WELL_DONE');
  });

  it('shows the denied panel rather than a table on 403, and never renders a create form', async () => {
    api.list = vi
      .fn()
      .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
    await setUp();

    expect(fixture.nativeElement.querySelector('.denied')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('[data-testid="comment-preset-form-submit"]'),
    ).toBeFalsy();
  });

  it('offers one column and one field per language of the tenant’s set, default first — and no others', async () => {
    // The union of the tenant's brands: Uzbek (its first brand's default) and
    // English. Russian is not offered, so it must not be a field or a column.
    api.localeSet = vi.fn().mockResolvedValue({
      locales: ['uz-Latn', 'en'],
      defaultLocale: 'uz-Latn',
      configured: true,
    } satisfies LocaleSetView);
    await setUp();

    const headers = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid^="comment-presets-col-"]'),
    ) as HTMLElement[];
    expect(headers.map((th) => th.getAttribute('data-testid'))).toEqual([
      'comment-presets-col-uz-Latn',
      'comment-presets-col-en',
    ]);
    expect(headers[0].textContent).toContain('default');
    expect(input('comment-preset-form-label-uz-Latn')).toBeTruthy();
    expect(input('comment-preset-form-label-en')).toBeTruthy();
    expect(input('comment-preset-form-label-ru')).toBeNull();
  });

  it('falls back to the platform triple when the tenant’s language set cannot be read', async () => {
    api.localeSet = vi.fn().mockRejectedValue(new Error('unreachable'));
    await setUp();

    expect(input('comment-preset-form-label-ru')).toBeTruthy();
    expect(input('comment-preset-form-label-uz-Latn')).toBeTruthy();
    expect(input('comment-preset-form-label-en')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('[data-testid="comment-presets-table"]'),
    ).toBeTruthy();
  });

  it('registers a new preset from the default language alone, sending only the wording it has', async () => {
    const created: PresetResponse = {
      presetId: 'preset-3',
      code: 'EXTRA_SPICY',
      labelRu: 'Поострее',
      labelUz: 'Поострее',
      labelEn: 'Extra spicy',
      labels: { ru: 'Поострее', en: 'Extra spicy' },
      posModifierCode: 'MOD-SPICY',
      sortOrder: 2,
      status: 'ACTIVE',
      version: 0,
    };
    api.create = vi.fn().mockReturnValue(of(created));
    await setUp();

    type('comment-preset-form-code', 'extra_spicy');
    type('comment-preset-form-label-ru', 'Поострее');
    // uz-Latn is offered but left blank: optional, so it must not be sent at all.
    type('comment-preset-form-label-en', 'Extra spicy');
    type('comment-preset-form-posModifierCode', 'MOD-SPICY');

    const submit = input('comment-preset-form-submit') as unknown as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.create).toHaveBeenCalledWith(TENANT_ID, {
      code: 'EXTRA_SPICY',
      // The contract keeps the platform triple required: uz-Latn was offered but
      // left blank, so its column takes the default language's wording.
      labelRu: 'Поострее',
      labelUz: 'Поострее',
      labelEn: 'Extra spicy',
      labels: { ru: 'Поострее', en: 'Extra spicy' },
      posModifierCode: 'MOD-SPICY',
      sortOrder: 0,
    });
    expect(fixture.nativeElement.textContent).toContain('EXTRA_SPICY');
  });

  it('refuses to register a preset that lacks the default language’s wording, and calls nothing', async () => {
    await setUp();

    type('comment-preset-form-code', 'EXTRA_SPICY');
    // English filled, the default (ru) not.
    type('comment-preset-form-label-en', 'Extra spicy');
    (input('comment-preset-form-submit') as unknown as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.create).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain(
      'A code and the label in the default language are required.',
    );
  });

  it('edits an existing preset with the version it was read at, never a stale one', async () => {
    const updated: PresetResponse = {
      ...NO_ONIONS,
      labelEn: 'No onions please',
      labels: { ...NO_ONIONS.labels, en: 'No onions please' },
      version: 4,
    };
    api.update = vi.fn().mockReturnValue(of(updated));
    await setUp();

    const editButtons = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="comment-preset-row-edit"]'),
    ) as HTMLButtonElement[];
    // NO_ONIONS sorts first (sortOrder 0).
    editButtons[0].click();
    fixture.detectChanges();

    type('comment-preset-edit-label-en', 'No onions please');
    (input('comment-preset-edit-save') as unknown as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.update).toHaveBeenCalledWith(
      TENANT_ID,
      'preset-1',
      expect.objectContaining({
        labelRu: 'Без лука',
        labelUz: 'Piyozsiz',
        labelEn: 'No onions please',
        labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions please' },
        expectedVersion: 3,
      }),
    );
    expect(
      fixture.nativeElement.querySelector('[data-testid="comment-preset-edit-row"]'),
    ).toBeFalsy();
    expect(fixture.nativeElement.textContent).toContain('No onions please');
  });

  it('never sends, blanks or deletes a language the tenant does not offer when it edits one it does', async () => {
    // The preset carries Uzbek and Karakalpak wording, but the tenant's brands
    // now offer only Russian and English. Editing English must leave the rest.
    const carrying: PresetResponse = {
      ...NO_ONIONS,
      labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions', kaa: 'Piyazsiz' },
    };
    api.list = vi.fn().mockResolvedValue([carrying]);
    api.localeSet = vi.fn().mockResolvedValue({
      locales: ['ru', 'en'],
      defaultLocale: 'ru',
      configured: true,
    } satisfies LocaleSetView);
    api.update = vi.fn().mockImplementation((_tenant: string, _id: string, body) =>
      // The server merges: what it answers still carries the languages the body never named.
      of({ ...carrying, labels: { ...carrying.labels, ...body.labels }, version: 4 }),
    );
    await setUp();

    (input('comment-preset-row-edit') as unknown as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(input('comment-preset-edit-label-uz-Latn')).toBeNull();
    expect(input('comment-preset-edit-label-kaa')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="comment-preset-hidden-kept"]'),
    ).toBeTruthy();

    // Clearing an offered, non-default field is "leave it", not "delete it".
    type('comment-preset-edit-label-en', '');
    type('comment-preset-edit-label-ru', 'Без лука!');
    (input('comment-preset-edit-save') as unknown as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const sent = api.update.mock.calls[0][2];
    // `labels` names only what was offered and filled in: not the hidden uz-Latn or
    // kaa, and not the cleared en.
    expect(sent.labels).toEqual({ ru: 'Без лука!' });
    expect(Object.keys(sent.labels)).not.toContain('uz-Latn');
    expect(Object.keys(sent.labels)).not.toContain('kaa');
    expect(Object.values(sent.labels)).not.toContain('');
    // The contract keeps the platform triple required, so the columns still go back:
    // the edited one changed, the hidden uz-Latn and the cleared en exactly as stored.
    expect(sent.labelRu).toBe('Без лука!');
    expect(sent.labelUz).toBe('Piyozsiz');
    expect(sent.labelEn).toBe('No onions');
  });

  it('words a platform language the tenant does not offer with the default language’s wording on a create', async () => {
    api.localeSet = vi.fn().mockResolvedValue({
      locales: ['uz-Latn', 'en'],
      defaultLocale: 'uz-Latn',
      configured: true,
    } satisfies LocaleSetView);
    api.create = vi.fn().mockReturnValue(
      of({
        ...NO_ONIONS,
        presetId: 'preset-9',
        code: 'EXTRA_SPICY',
        labelRu: 'Achchiqroq',
        labelUz: 'Achchiqroq',
        labelEn: 'Extra spicy',
      }),
    );
    await setUp();

    type('comment-preset-form-code', 'EXTRA_SPICY');
    type('comment-preset-form-label-uz-Latn', 'Achchiqroq');
    type('comment-preset-form-label-en', 'Extra spicy');
    (input('comment-preset-form-submit') as unknown as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(api.create.mock.calls[0][1]).toMatchObject({
      labelRu: 'Achchiqroq',
      labelUz: 'Achchiqroq',
      labelEn: 'Extra spicy',
      labels: { 'uz-Latn': 'Achchiqroq', en: 'Extra spicy' },
    });
  });
});
