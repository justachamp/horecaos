import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { LocaleSetView } from '../../core/i18n/locale-labels';
import { CommentPresetsApi } from '../settings/comment-presets/comment-presets-api';
import { ReferenceDataPage } from './reference-data-page';
import { VocabularyApi, VocabularyEntry } from './vocabulary-api';

const TENANT_ID = 'tenant-1';

const SPICY: VocabularyEntry = {
  id: 'tag-1',
  code: 'spicy',
  iconKey: 'FLAME',
  showAsBadge: true,
  showAsFilter: true,
  sortOrder: 0,
  status: 'ACTIVE',
  version: 2,
  names: { ru: 'Острое', 'uz-Latn': 'Achchiq', en: 'Spicy' },
};

const RICE: VocabularyEntry = {
  id: 'ing-1',
  code: 'rice',
  iconKey: null,
  showAsBadge: false,
  showAsFilter: false,
  sortOrder: 0,
  status: 'ACTIVE',
  version: 1,
  names: { ru: 'Рис', 'uz-Latn': 'Guruch' },
};

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

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ReferenceDataPage', () => {
  let fixture: ComponentFixture<ReferenceDataPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    archive: ReturnType<typeof vi.fn>;
  };

  async function setUp(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ReferenceDataPage],
      providers: [
        provideRouter([]),
        { provide: VocabularyApi, useValue: api },
        {
          provide: CommentPresetsApi,
          useValue: { localeSet: vi.fn().mockResolvedValue(PLATFORM_SET) },
        },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReferenceDataPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(testId: string): T {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as T;
  }

  function type(testId: string, value: string): void {
    const input = el<HTMLInputElement>(testId);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  beforeEach(() => {
    api = {
      list: vi.fn((tenantId: string, kind: string) =>
        Promise.resolve(kind === 'TAG' ? [SPICY] : [RICE]),
      ),
      create: vi.fn(),
      update: vi.fn(),
      archive: vi.fn(),
    };
  });

  it('reads both registries at tenant scope and lists the tags first', async () => {
    await setUp();

    expect(api.list).toHaveBeenCalledWith(TENANT_ID, 'TAG');
    expect(api.list).toHaveBeenCalledWith(TENANT_ID, 'INGREDIENT');
    expect(el('reference-data-table').textContent).toContain('spicy');
    expect(el('reference-data-table').textContent).toContain('Острое');
    expect(text()).not.toContain('rice');
  });

  it('switches to ingredients, which have no icon or switches, and says they are labels not allergen statements', async () => {
    await setUp();

    el<HTMLButtonElement>('rail-ingredients').click();
    fixture.detectChanges();

    expect(el('reference-data-table').textContent).toContain('rice');
    expect(el('form-icon')).toBeNull();
    expect(el('form-badge')).toBeNull();
    expect(el('ingredients-lead').textContent).toContain('allergen');
  });

  it('points to where the other vocabularies are authored instead of duplicating them', async () => {
    await setUp();

    expect(el<HTMLAnchorElement>('link-presets').getAttribute('href')).toBe(
      '/settings/comment-presets',
    );
    expect(el<HTMLAnchorElement>('link-brands').getAttribute('href')).toBe('/settings/brand');
    expect(el('link-kitchen')).not.toBeNull();
    expect(el('link-recommendations')).not.toBeNull();
  });

  it('needs a well-formed code, the default-language name and (for a tag) a badge or a filter before it will submit', async () => {
    await setUp();
    const submit = el<HTMLButtonElement>('form-submit');
    expect(submit.disabled).toBe(true);

    type('form-code', 'Not A Code');
    type('form-name-ru', 'Веган');
    expect(submit.disabled).toBe(true);

    type('form-code', 'vegan');
    expect(submit.disabled).toBe(false);

    el<HTMLInputElement>('form-badge').click();
    fixture.detectChanges();
    expect(submit.disabled).toBe(true);
    el<HTMLInputElement>('form-filter').click();
    fixture.detectChanges();
    expect(submit.disabled).toBe(false);
  });

  it('creates a tag with the wording of the languages that were filled in, and lists it', async () => {
    const created: VocabularyEntry = {
      ...SPICY,
      id: 'tag-2',
      code: 'vegan',
      iconKey: 'LEAF',
      names: { ru: 'Веган' },
      version: 1,
    };
    api.create.mockResolvedValue(created);
    await setUp();

    type('form-code', 'vegan');
    type('form-name-ru', 'Веган');
    const icon = el<HTMLSelectElement>('form-icon');
    icon.value = 'LEAF';
    icon.dispatchEvent(new Event('change'));
    el<HTMLFormElement>('form-submit')
      .closest('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await flush();
    fixture.detectChanges();

    expect(api.create).toHaveBeenCalledWith(TENANT_ID, 'TAG', {
      code: 'vegan',
      iconKey: 'LEAF',
      showAsBadge: true,
      showAsFilter: false,
      sortOrder: 0,
      names: { ru: 'Веган' },
    });
    expect(el('reference-data-table').textContent).toContain('vegan');
  });

  it('says why a code is refused in the operator’s language, not the server’s text', async () => {
    api.create.mockRejectedValue(
      new ApiError(
        ApiErrorCode.RESOURCE_CONFLICT,
        409,
        {
          status: 409,
          code: ApiErrorCode.RESOURCE_CONFLICT,
          detail: 'This tenant already has a tag with code spicy',
          findingCode: 'VOCABULARY_CODE_TAKEN',
        } as never,
        null,
      ),
    );
    await setUp();
    type('form-code', 'spicy');
    type('form-name-ru', 'Острое');

    el<HTMLFormElement>('form-submit')
      .closest('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await flush();
    fixture.detectChanges();

    expect(el('form-error').textContent).toContain('already has an entry with that code');
    expect(text()).not.toContain('This tenant already has a tag');
  });

  it('edits in place against the version it read and keeps a language the form does not show', async () => {
    api.update.mockResolvedValue({ ...SPICY, version: 3, sortOrder: 5 });
    await setUp();

    el<HTMLButtonElement>('entry-edit').click();
    fixture.detectChanges();
    expect(el<HTMLInputElement>('edit-name-ru').value).toBe('Острое');
    type('edit-sort', '5');
    el<HTMLButtonElement>('edit-save').click();
    await flush();
    fixture.detectChanges();

    expect(api.update).toHaveBeenCalledWith(TENANT_ID, 'TAG', SPICY, {
      iconKey: 'FLAME',
      showAsBadge: true,
      showAsFilter: true,
      sortOrder: 5,
      names: { ru: 'Острое', 'uz-Latn': 'Achchiq', en: 'Spicy' },
    });
    expect(el('entry-edit-row')).toBeNull();
  });

  it('archives an entry and offers nothing more to do with it', async () => {
    api.archive.mockResolvedValue({ ...SPICY, status: 'ARCHIVED', version: 3 });
    await setUp();

    el<HTMLButtonElement>('entry-archive').click();
    await flush();
    fixture.detectChanges();

    expect(api.archive).toHaveBeenCalledWith(TENANT_ID, 'TAG', SPICY);
    expect(el('entry-archive')).toBeNull();
    expect(el('entry-edit')).toBeNull();
    expect(text()).toContain('Archived');
  });

  it('shows the denied panel on 403 and offers no form', async () => {
    api.list = vi
      .fn()
      .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
    await setUp();

    expect(el('reference-data-denied')).not.toBeNull();
    expect(el('form-submit')).toBeNull();
  });
});
