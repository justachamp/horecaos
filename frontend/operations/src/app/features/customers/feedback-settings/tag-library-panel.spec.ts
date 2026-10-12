import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MediaUploader } from '../../../shared/ui/media-uploader';
import { MediaApi } from '../../catalog/media-api';
import { library, tag } from './feedback-fixtures.testing';
import { FeedbackSettingsApi, TagLibrary } from './feedback-settings-api';
import { TagLibraryPanel } from './tag-library-panel';

const SCOPE = { tenantId: 'tenant-1', brandId: 'brand-1' };

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('TagLibraryPanel', () => {
  let fixture: ComponentFixture<TagLibraryPanel>;
  let api: {
    library: ReturnType<typeof vi.fn>;
    createTag: ReturnType<typeof vi.fn>;
    updateTag: ReturnType<typeof vi.fn>;
    importStarterPack: ReturnType<typeof vi.fn>;
  };
  let media: { upload: ReturnType<typeof vi.fn>; downloadUrl: ReturnType<typeof vi.fn> };

  async function render(
    initial: TagLibrary | (() => Promise<TagLibrary>) = library(),
    canWrite = true,
  ) {
    api = {
      library: vi.fn(typeof initial === 'function' ? initial : async () => initial),
      createTag: vi.fn().mockResolvedValue(tag()),
      updateTag: vi.fn().mockResolvedValue(tag()),
      importStarterPack: vi.fn().mockResolvedValue([]),
    };
    media = {
      upload: vi.fn(() => of({ assetId: 'asset-9', status: 'AVAILABLE' })),
      downloadUrl: vi.fn(() => of('https://cdn.test/icon.jpg')),
    };
    await TestBed.configureTestingModule({
      imports: [TagLibraryPanel],
      providers: [
        { provide: FeedbackSettingsApi, useValue: api },
        { provide: MediaApi, useValue: media },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TagLibraryPanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('canWrite', canWrite);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function el<T extends HTMLElement>(testId: string): T {
    const found = host().querySelector(`[data-testid="${testId}"]`);
    expect(found, testId).not.toBeNull();
    return found as T;
  }

  function type(testId: string, value: string): void {
    const input = el<HTMLInputElement>(testId);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function click(testId: string): Promise<void> {
    el(testId).click();
    await flush();
    fixture.detectChanges();
  }

  it('lists each tag in the operator’s language with what it is about, what kind it is and whether reviews carry it', async () => {
    await render(
      library([
        tag(),
        tag({
          id: 'tag-2',
          code: 'food_tasty',
          sentiment: 'POSITIVE',
          used: true,
          status: 'RETIRED',
        }),
      ]),
    );

    const rows = host().querySelectorAll('[data-testid="tag-row"]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Food arrived cold');
    expect(rows[0].textContent).toContain('food_cold');
    expect(rows[0].textContent).toContain('Food');
    expect(rows[0].textContent).toContain('Complaint');
    expect(rows[1].textContent).toContain('Praise');
    expect(rows[1].textContent).toContain('Retired');
    expect(rows[1].textContent).toContain('In use');
    expect(el('tag-active-count').textContent).toContain('1 of 40');
  });

  it('shows no editing at all to a reader who may not write', async () => {
    await render(library(), false);

    for (const id of ['tag-new', 'tag-edit', 'tag-retire', 'tag-import-starter']) {
      expect(host().querySelector(`[data-testid="${id}"]`), id).toBeNull();
    }
    expect(host().querySelectorAll('[data-testid="tag-row"]')).toHaveLength(1);
  });

  it('offers the starter pack only to an empty library, and imports it once', async () => {
    await render(library([]));

    expect(host().textContent).toContain('No tags yet.');
    api.library.mockResolvedValue(library([tag()]));
    await click('tag-import-starter');

    expect(api.importStarterPack).toHaveBeenCalledWith('tenant-1');
    expect(host().querySelector('[data-testid="tag-import-starter"]')).toBeNull();
    expect(host().querySelectorAll('[data-testid="tag-row"]')).toHaveLength(1);
  });

  describe('a new tag', () => {
    beforeEach(async () => {
      await render();
      await click('tag-new');
    });

    it('cannot be saved without a well-formed code and the wording in the default language', () => {
      expect(el<HTMLButtonElement>('tag-save').disabled).toBe(true);
      expect(el('tag-wording-required').textContent).toContain('Russian');

      type('tag-code', 'Food Greasy');
      type('tag-wording', 'Слишком жирно');
      expect(el<HTMLButtonElement>('tag-save').disabled).toBe(true);

      type('tag-code', 'food_greasy');
      expect(el<HTMLButtonElement>('tag-save').disabled).toBe(false);
    });

    it('sends the code, the kinds and only the wording that was written', async () => {
      type('tag-code', 'food_greasy');
      type('tag-wording', 'Слишком жирно');
      const category = el<HTMLSelectElement>('tag-category');
      category.value = 'SERVICE';
      category.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      await click('tag-save');

      expect(api.createTag).toHaveBeenCalledWith('tenant-1', {
        code: 'food_greasy',
        category: 'SERVICE',
        sentiment: 'NEGATIVE',
        iconAssetId: null,
        sortOrder: 30,
        labels: { ru: 'Слишком жирно' },
      });
      expect(host().querySelector('[data-testid="tag-editor"]')).toBeNull();
    });

    it('writes another language on its own tab without losing the first', async () => {
      type('tag-code', 'food_greasy');
      type('tag-wording', 'Слишком жирно');
      (
        host().querySelector('[data-testid="q-localized-field-group-tab-en"]') as HTMLElement
      ).click();
      fixture.detectChanges();
      expect(el<HTMLInputElement>('tag-wording').value).toBe('');
      type('tag-wording', 'Too greasy');

      await click('tag-save');

      expect(api.createTag.mock.calls[0][1].labels).toEqual({
        ru: 'Слишком жирно',
        en: 'Too greasy',
      });
    });

    it('sends an icon that was uploaded as a tenant-owned public image', async () => {
      type('tag-code', 'food_greasy');
      type('tag-wording', 'Слишком жирно');
      const uploader = fixture.debugElement.query(By.directive(MediaUploader));
      const file = new File(['x'], 'icon.jpg', { type: 'image/jpeg' });
      vi.stubGlobal('URL', {
        ...URL,
        createObjectURL: () => 'blob:icon',
        revokeObjectURL: () => undefined,
      });

      uploader.componentInstance.cropped.emit(file);
      await flush();
      fixture.detectChanges();
      await click('tag-save');

      expect(media.upload).toHaveBeenCalledWith('tenant-1', 'TENANT', 'tenant-1', 'PUBLIC', file);
      expect(api.createTag.mock.calls[0][1].iconAssetId).toBe('asset-9');
      vi.unstubAllGlobals();
    });

    it('says why a file was refused before any network call', async () => {
      const uploader = fixture.debugElement.query(By.directive(MediaUploader));

      uploader.componentInstance.rejected.emit('tooLarge');
      fixture.detectChanges();

      expect(el('tag-error').textContent?.length).toBeGreaterThan(0);
      expect(media.upload).not.toHaveBeenCalled();
    });
  });

  it('opens an existing tag with its own category and sentiment chosen, not the first of each list', async () => {
    await render(library([tag({ category: 'DELIVERY_TIME', sentiment: 'POSITIVE', used: false })]));

    await click('tag-edit');

    expect(el<HTMLSelectElement>('tag-category').value).toBe('DELIVERY_TIME');
    expect(el<HTMLSelectElement>('tag-sentiment').value).toBe('POSITIVE');
  });

  describe('a tag reviews already carry', () => {
    beforeEach(async () => {
      await render(library([tag({ used: true, version: 4 })]));
      await click('tag-edit');
    });

    it('freezes its code, category and sentiment and says why', () => {
      expect(el<HTMLInputElement>('tag-code').disabled).toBe(true);
      expect(el<HTMLSelectElement>('tag-category').disabled).toBe(true);
      expect(el<HTMLSelectElement>('tag-sentiment').disabled).toBe(true);
      expect(el('tag-frozen').textContent).toContain('reports group by it');
    });

    it('still lets the wording change, against the version the list returned', async () => {
      (
        host().querySelector('[data-testid="q-localized-field-group-tab-en"]') as HTMLElement
      ).click();
      fixture.detectChanges();
      type('tag-wording', 'Served cold');

      await click('tag-save');

      expect(api.updateTag).toHaveBeenCalledWith(
        'tenant-1',
        'tag-1',
        expect.objectContaining({
          category: 'FOOD',
          sentiment: 'NEGATIVE',
          status: 'ACTIVE',
          labels: { ru: 'Остыла еда', 'uz-Latn': 'Taom sovigan', en: 'Served cold' },
        }),
        4,
      );
    });
  });

  it('retires a tag without sending any wording, so no language is touched', async () => {
    await render(library([tag({ version: 3 })]));

    await click('tag-retire');

    expect(api.updateTag).toHaveBeenCalledWith(
      'tenant-1',
      'tag-1',
      expect.objectContaining({ status: 'RETIRED', labels: null }),
      3,
    );
  });

  it('offers a retired tag again with the same rule', async () => {
    await render(library([tag({ status: 'RETIRED', version: 5 })]));

    await click('tag-restore');

    expect(api.updateTag).toHaveBeenCalledWith(
      'tenant-1',
      'tag-1',
      expect.objectContaining({ status: 'ACTIVE', labels: null }),
      5,
    );
  });

  it('reloads the library and says so when the tag changed under the editor', async () => {
    await render(library([tag({ version: 3 })]));
    api.updateTag.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'corr'),
    );
    api.library.mockClear();

    await click('tag-retire');

    expect(el('tag-error').textContent).toContain('Someone else saved first');
    expect(api.library).toHaveBeenCalled();
  });

  it('shows a server refusal as its own sentence, never a raw code', async () => {
    await render();
    await click('tag-new');
    type('tag-code', 'food_greasy');
    type('tag-wording', 'Слишком жирно');
    api.createTag.mockRejectedValueOnce(
      new ApiError(
        ApiErrorCode.RESOURCE_CONFLICT,
        409,
        {
          status: 409,
          code: 'RESOURCE_CONFLICT',
          detail: "A feedback tag with the code 'food_greasy' already exists",
        },
        null,
      ),
    );

    await click('tag-save');

    expect(el('tag-error').textContent).toContain('already exists');
    expect(host().querySelector('[data-testid="tag-editor"]')).not.toBeNull();
  });

  it('shows the denied state when the library cannot be read', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });

    expect(host().textContent).toContain('You do not have access to these settings.');
  });
});

beforeEach(() => TestBed.resetTestingModule());
