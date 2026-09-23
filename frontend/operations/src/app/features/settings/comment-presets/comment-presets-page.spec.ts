import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { CommentPresetsApi, PresetResponse } from './comment-presets-api';
import { CommentPresetsPage } from './comment-presets-page';

const TENANT_ID = 'tenant-1';

const NO_ONIONS: PresetResponse = {
  presetId: 'preset-1',
  code: 'NO_ONIONS',
  labelRu: 'Без лука',
  labelUz: 'Piyozsiz',
  labelEn: 'No onions',
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
  posModifierCode: null,
  sortOrder: 1,
  status: 'ACTIVE',
  version: 1,
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

  beforeEach(() => {
    api = {
      list: vi.fn().mockResolvedValue([WELL_DONE, NO_ONIONS]),
      create: vi.fn(),
      update: vi.fn(),
    };
  });

  it('loads the tenant-scoped vocabulary at TENANT scope and sorts it by sortOrder then code', async () => {
    await setUp();

    expect(api.list).toHaveBeenCalledWith(TENANT_ID);
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

  it('registers a new preset and appends it to the list without a reload', async () => {
    const created: PresetResponse = {
      presetId: 'preset-3',
      code: 'EXTRA_SPICY',
      labelRu: 'Поострее',
      labelUz: 'Achchiqroq',
      labelEn: 'Extra spicy',
      posModifierCode: 'MOD-SPICY',
      sortOrder: 2,
      status: 'ACTIVE',
      version: 0,
    };
    api.create = vi.fn().mockReturnValue(of(created));
    await setUp();

    const code = fixture.nativeElement.querySelector(
      '[data-testid="comment-preset-form-code"]',
    ) as HTMLInputElement;
    const ru = fixture.nativeElement.querySelector(
      '[data-testid="comment-preset-form-labelRu"]',
    ) as HTMLInputElement;
    const uz = fixture.nativeElement.querySelector(
      '[data-testid="comment-preset-form-labelUz"]',
    ) as HTMLInputElement;
    const en = fixture.nativeElement.querySelector(
      '[data-testid="comment-preset-form-labelEn"]',
    ) as HTMLInputElement;
    const pos = fixture.nativeElement.querySelector(
      '[data-testid="comment-preset-form-posModifierCode"]',
    ) as HTMLInputElement;

    code.value = 'extra_spicy';
    code.dispatchEvent(new Event('input'));
    ru.value = 'Поострее';
    ru.dispatchEvent(new Event('input'));
    uz.value = 'Achchiqroq';
    uz.dispatchEvent(new Event('input'));
    en.value = 'Extra spicy';
    en.dispatchEvent(new Event('input'));
    pos.value = 'MOD-SPICY';
    pos.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = fixture.nativeElement.querySelector(
      '[data-testid="comment-preset-form-submit"]',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.create).toHaveBeenCalledWith(TENANT_ID, {
      code: 'EXTRA_SPICY',
      labelRu: 'Поострее',
      labelUz: 'Achchiqroq',
      labelEn: 'Extra spicy',
      posModifierCode: 'MOD-SPICY',
      sortOrder: 0,
    });
    expect(fixture.nativeElement.textContent).toContain('EXTRA_SPICY');
  });

  it('edits an existing preset with the version it was read at, never a stale one', async () => {
    const updated: PresetResponse = { ...NO_ONIONS, labelEn: 'No onions please', version: 4 };
    api.update = vi.fn().mockReturnValue(of(updated));
    await setUp();

    const editButtons = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="comment-preset-row-edit"]'),
    ) as HTMLButtonElement[];
    // NO_ONIONS sorts first (sortOrder 0).
    editButtons[0].click();
    fixture.detectChanges();

    const labelEn = fixture.nativeElement.querySelector(
      '[data-testid="comment-preset-edit-labelEn"]',
    ) as HTMLInputElement;
    labelEn.value = 'No onions please';
    labelEn.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const save = fixture.nativeElement.querySelector(
      '[data-testid="comment-preset-edit-save"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.update).toHaveBeenCalledWith(
      TENANT_ID,
      'preset-1',
      expect.objectContaining({ labelEn: 'No onions please', expectedVersion: 3 }),
    );
    expect(
      fixture.nativeElement.querySelector('[data-testid="comment-preset-edit-row"]'),
    ).toBeFalsy();
    expect(fixture.nativeElement.textContent).toContain('No onions please');
  });
});
