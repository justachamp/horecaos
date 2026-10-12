import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { RichText } from '../../../shared/ui/rich-text';
import { RuleList } from '../../../shared/ui/rule-list';
import { ContentDocument } from './content-api';
import {
  ContentEditorialFields,
  EditorialKind,
  MAX_ALBUM_IMAGES,
} from './content-editorial-fields';
import { emptyDocument, withAlbumImages } from './content-document';
import { ContentMedia } from './content-media';
import { ContentMediaField } from './content-media-field';
import { ContentReferences } from './content-references';

const SCOPE = { tenantId: 't', brandId: 'b' };

class FakeMedia {
  readonly thumbnails = signal<Readonly<Record<string, string>>>({});
  readonly ensured: string[] = [];
  readonly uploads: File[] = [];
  ensureThumbnail(_scope: unknown, id: string): void {
    this.ensured.push(id);
  }
  uploadImage(_scope: unknown, file: File): Promise<string> {
    this.uploads.push(file);
    this.thumbnails.update((t) => ({ ...t, 'uploaded-1': 'blob:u' }));
    return Promise.resolve('uploaded-1');
  }
}

function setup(
  kind: EditorialKind,
  document: ContentDocument = emptyDocument(kind),
  hostAllowed = true,
): {
  fixture: ReturnType<typeof TestBed.createComponent<ContentEditorialFields>>;
  emitted: ContentDocument[];
  media: FakeMedia;
  root: HTMLElement;
} {
  const media = new FakeMedia();
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentMedia, useValue: media },
      { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
      { provide: ContentReferences, useValue: { hostAllowed: () => hostAllowed } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentEditorialFields);
  fixture.componentRef.setInput('kind', kind);
  fixture.componentRef.setInput('document', document);
  fixture.componentRef.setInput('locale', 'ru');
  const emitted: ContentDocument[] = [];
  fixture.componentInstance.documentChange.subscribe((d) => {
    emitted.push(d);
    fixture.componentRef.setInput('document', d);
    fixture.detectChanges();
  });
  fixture.detectChanges();
  return { fixture, emitted, media, root: fixture.nativeElement as HTMLElement };
}

function type(root: HTMLElement, testid: string, value: string): void {
  const field = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `[data-testid="${testid}"]`,
  )!;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}

function choose(root: HTMLElement, testid: string, value: string): void {
  const select = root.querySelector<HTMLSelectElement>(`[data-testid="${testid}"]`)!;
  select.value = value;
  select.dispatchEvent(new Event('change'));
}

describe('ContentEditorialFields (ADR 0157, Decisions 6 and 7: the editorial tier)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  describe('a news post or a recipe', () => {
    it('writes a title and a summary in the language being edited, up to 120 and 300 characters', () => {
      const { root, emitted } = setup('NEWS');
      expect(root.querySelector('[data-testid="content-title"]')?.getAttribute('maxlength')).toBe(
        '120',
      );
      expect(root.querySelector('[data-testid="content-summary"]')?.getAttribute('maxlength')).toBe(
        '300',
      );

      type(root, 'content-title', 'Открытие');
      type(root, 'content-summary', 'Новая точка');

      expect(emitted.at(-1)?.['texts']).toEqual({
        ru: { title: 'Открытие', summary: 'Новая точка' },
      });
    });

    it('authors the body as a typed block document, in block mode, per language', () => {
      const { fixture, emitted } = setup('RECIPE');
      const richText = fixture.debugElement.query(By.directive(RichText))
        .componentInstance as RichText;
      expect(richText.mode()).toBe('blocks');
      expect(richText.blockLabels()?.types.PARAGRAPH).toBe('Paragraph');

      richText.documentChange.emit({
        schemaVersion: 1,
        blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'Шаг первый' }] }],
      });

      expect(emitted.at(-1)?.['body']).toEqual({
        ru: {
          schemaVersion: 1,
          blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'Шаг первый' }] }],
        },
      });
    });

    it('hands the body editor an upload that is public, the brand’s and verified', async () => {
      const { fixture, media } = setup('NEWS');
      const richText = fixture.debugElement.query(By.directive(RichText))
        .componentInstance as RichText;
      const file = new File(['x'], 'p.jpg', { type: 'image/jpeg' });

      const uploaded = await richText.imageUpload()!(file);

      expect(media.uploads).toEqual([file]);
      expect(uploaded).toEqual({ assetId: 'uploaded-1', previewUrl: 'blob:u' });
    });

    it('asks for a thumbnail of every picture the saved body names', () => {
      const { media } = setup('NEWS', {
        ...emptyDocument('NEWS'),
        body: {
          ru: {
            schemaVersion: 1,
            blocks: [{ type: 'IMAGE', assetId: 'in-body', caption: null }],
          },
        },
      });

      expect(media.ensured).toContain('in-body');
    });

    it('sets and clears the cover picture', () => {
      const { fixture, emitted } = setup('NEWS');
      const cover = fixture.debugElement.query(By.directive(ContentMediaField))
        .componentInstance as ContentMediaField;

      cover.changed.emit({ assetId: 'cover-1', posterAssetId: null });
      expect(emitted.at(-1)?.['coverAssetId']).toBe('cover-1');

      cover.changed.emit({ assetId: null, posterAssetId: null });
      expect('coverAssetId' in emitted.at(-1)!).toBe(false);
    });

    it('has neither the vacancy section nor the album', () => {
      const { root } = setup('NEWS');

      expect(root.querySelector('[data-testid="content-vacancy-apply-type"]')).toBeNull();
      expect(root.querySelector('[data-testid="content-album"]')).toBeNull();
    });
  });

  describe('a vacancy', () => {
    it('is a listing with one contact: it has a body and no cover', () => {
      const { fixture, root } = setup('VACANCY');

      expect(fixture.debugElement.query(By.directive(RichText))).toBeTruthy();
      expect(fixture.debugElement.query(By.directive(ContentMediaField))).toBeNull();
      expect(root.textContent).toContain('no application form');
    });

    it('chooses the type of work and the way to get in touch, and clears the contact when the way changes', () => {
      const { root, emitted } = setup('VACANCY');

      choose(root, 'content-vacancy-employment', 'SHIFT');
      choose(root, 'content-vacancy-apply-type', 'EMAIL');
      type(root, 'content-vacancy-apply-value', 'jobs@example.uz');
      expect(emitted.at(-1)?.['vacancy']).toEqual({
        employmentType: 'SHIFT',
        apply: { type: 'EMAIL', value: 'jobs@example.uz' },
      });

      choose(root, 'content-vacancy-apply-type', 'PHONE');
      expect(emitted.at(-1)?.['vacancy']).toEqual({
        employmentType: 'SHIFT',
        apply: { type: 'PHONE' },
      });
    });

    it('warns that a web or Telegram contact must be on the list of allowed links, and only then', () => {
      const outside = setup(
        'VACANCY',
        {
          ...emptyDocument('VACANCY'),
          vacancy: {
            employmentType: 'FULL_TIME',
            apply: { type: 'TELEGRAM', value: 'https://t.me/jobs' },
          },
        },
        false,
      );
      expect(outside.root.querySelector('[data-testid="content-vacancy-host"]')).toBeTruthy();

      TestBed.resetTestingModule();
      const inside = setup(
        'VACANCY',
        {
          ...emptyDocument('VACANCY'),
          vacancy: {
            employmentType: 'FULL_TIME',
            apply: { type: 'TELEGRAM', value: 'https://t.me/jobs' },
          },
        },
        true,
      );
      expect(inside.root.querySelector('[data-testid="content-vacancy-host"]')).toBeNull();

      TestBed.resetTestingModule();
      const phone = setup(
        'VACANCY',
        {
          ...emptyDocument('VACANCY'),
          vacancy: {
            employmentType: 'FULL_TIME',
            apply: { type: 'PHONE', value: '+998901234567' },
          },
        },
        false,
      );
      expect(phone.root.querySelector('[data-testid="content-vacancy-host"]')).toBeNull();
    });
  });

  describe('a photo album', () => {
    const album = (): ContentDocument =>
      withAlbumImages(emptyDocument('GALLERY_ALBUM'), [
        { assetId: 'a1', captions: { ru: 'Первая' } },
        { assetId: 'a2', captions: {} },
        { assetId: 'a3', captions: {} },
      ]);

    it('has no body, only photos', () => {
      const { fixture, root } = setup('GALLERY_ALBUM');

      expect(fixture.debugElement.query(By.directive(RichText))).toBeNull();
      expect(root.querySelector('[data-testid="content-album"]')).toBeTruthy();
    });

    it('adds a photo at the end and selects it', () => {
      const { fixture, emitted } = setup('GALLERY_ALBUM', album());
      const adder = fixture.debugElement.queryAll(By.directive(ContentMediaField))[1]
        .componentInstance as ContentMediaField;

      adder.changed.emit({ assetId: 'a4', posterAssetId: null });

      expect(
        (emitted.at(-1)?.['albumImages'] as { assetId: string }[]).map((i) => i.assetId),
      ).toEqual(['a1', 'a2', 'a3', 'a4']);
    });

    it('stops offering to add at 30 photos', () => {
      const images = Array.from({ length: MAX_ALBUM_IMAGES }, (_, i) => ({
        assetId: `a${i}`,
        captions: {},
      }));
      const { fixture } = setup(
        'GALLERY_ALBUM',
        withAlbumImages(emptyDocument('GALLERY_ALBUM'), images),
      );

      // the cover and the selected photo's read-only preview remain; the "add a photo" field does not
      const fields = fixture.debugElement.queryAll(By.directive(ContentMediaField));
      expect(fields).toHaveLength(2);
      expect(fields.map((f) => (f.componentInstance as ContentMediaField).label())).not.toContain(
        'Add a photo',
      );
    });

    it('orders the photos on the flat sortable list with no enable switch', () => {
      const { fixture, emitted } = setup('GALLERY_ALBUM', album());
      const list = fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList;

      expect(list.showToggle()).toBe(false);
      list.reorder.emit(['2', '0', '1']);

      expect(
        (emitted.at(-1)?.['albumImages'] as { assetId: string }[]).map((i) => i.assetId),
      ).toEqual(['a3', 'a1', 'a2']);
    });

    it('captions the chosen photo in this language only, and removes it', () => {
      const { fixture, root, emitted } = setup('GALLERY_ALBUM', album());
      const list = fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList;
      list.selected.emit('1');
      fixture.detectChanges();

      type(root, 'content-album-caption', 'Вторая');
      expect(
        (emitted.at(-1)?.['albumImages'] as { captions: Record<string, string> }[])[1].captions,
      ).toEqual({ ru: 'Вторая' });

      (root.querySelector('[data-testid="content-album-remove"]') as HTMLButtonElement).click();
      expect(
        (emitted.at(-1)?.['albumImages'] as { assetId: string }[]).map((i) => i.assetId),
      ).toEqual(['a1', 'a3']);
    });
  });
});
