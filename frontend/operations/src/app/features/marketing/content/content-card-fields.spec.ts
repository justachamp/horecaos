import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { DayOfWeekToggle } from '../../../shared/ui/day-of-week-toggle';
import { MediaUploader } from '../../../shared/ui/media-uploader';
import { TimeInput } from '../../../shared/ui/time-input';
import { ContentDocument } from './content-api';
import { CARD_MEDIA, CardKind, ContentCardFields } from './content-card-fields';
import { emptyDocument } from './content-document';
import { ContentLinkPicker } from './content-link-picker';
import { ContentMedia } from './content-media';
import { ContentMediaField } from './content-media-field';
import { ContentReferences } from './content-references';

function setup(
  kind: CardKind,
  document: ContentDocument = emptyDocument(kind),
  locale = 'ru',
): {
  fixture: ReturnType<typeof TestBed.createComponent<ContentCardFields>>;
  emitted: ContentDocument[];
  root: HTMLElement;
} {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: ContentReferences,
        useValue: {
          products: () => [],
          categories: () => [],
          promotions: () => [],
          productLabel: (id: string) => id,
          categoryLabel: (id: string) => id,
          promotionLabel: (id: string) => id,
          hostAllowed: () => true,
        },
      },
      {
        provide: ContentMedia,
        useValue: { thumbnails: signal({}), ensureThumbnail: () => undefined },
      },
      { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentCardFields);
  fixture.componentRef.setInput('kind', kind);
  fixture.componentRef.setInput('document', document);
  fixture.componentRef.setInput('locale', locale);
  const emitted: ContentDocument[] = [];
  fixture.componentInstance.documentChange.subscribe((d) => emitted.push(d));
  fixture.detectChanges();
  return { fixture, emitted, root: fixture.nativeElement as HTMLElement };
}

function type(root: HTMLElement, testid: string, value: string): void {
  const field = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `[data-testid="${testid}"]`,
  )!;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}

describe('ContentCardFields (ADR 0157: banners, promo cards and pop-ups)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('edits the texts of the language being edited and no other', () => {
    const { emitted, root } = setup('BANNER');

    type(root, 'content-title', 'Скидка 20%');

    expect(emitted.at(-1)?.['texts']).toEqual({ ru: { title: 'Скидка 20%' } });
  });

  it('reads the fields of the language it was given', () => {
    const { root } = setup(
      'BANNER',
      {
        schemaVersion: 1,
        texts: { ru: { title: 'Привет' }, en: { title: 'Hello', subtitle: 'World' } },
      },
      'en',
    );

    expect((root.querySelector('[data-testid="content-title"]') as HTMLInputElement).value).toBe(
      'Hello',
    );
    expect((root.querySelector('[data-testid="content-subtitle"]') as HTMLInputElement).value).toBe(
      'World',
    );
  });

  it('limits each field to the length the grammar accepts', () => {
    const { root } = setup('BANNER');
    const max = (id: string): string | null =>
      root.querySelector(`[data-testid="${id}"]`)?.getAttribute('maxlength') ?? null;

    expect(max('content-title')).toBe('80');
    expect(max('content-subtitle')).toBe('160');
    expect(max('content-cta')).toBe('24');
    expect(max('content-alt')).toBe('140');
  });

  it('holds each kind to its own crop: a 3:1 banner on 1600 px, a 3:2 card on 1200', () => {
    expect(CARD_MEDIA['BANNER']).toEqual({ ratio: '3:1', exportEdgePx: 1600, minWidthPx: 960 });
    expect(CARD_MEDIA['PROMO_CARD']).toEqual({ ratio: '3:2', exportEdgePx: 1200, minWidthPx: 600 });

    const { fixture } = setup('BANNER');
    const field = fixture.debugElement.query(By.directive(ContentMediaField))
      .componentInstance as ContentMediaField;
    expect(field.ratio()).toBe('3:1');
    expect(field.exportEdgePx()).toBe(1600);
    const uploader = fixture.debugElement.query(By.directive(MediaUploader))
      .componentInstance as MediaUploader;
    expect(uploader.exportEdgePx()).toBe(1600);
  });

  it('names the picture the uploader reports in the document', () => {
    const { fixture, emitted } = setup('PROMO_CARD');
    const field = fixture.debugElement.query(By.directive(ContentMediaField))
      .componentInstance as ContentMediaField;

    field.changed.emit({ assetId: 'asset-1', posterAssetId: null });

    expect(emitted.at(-1)?.['media']).toEqual({ primaryAssetId: 'asset-1' });
  });

  it('keeps a language’s own image apart from the shared one', () => {
    const { fixture, emitted } = setup('BANNER', {
      schemaVersion: 1,
      texts: { ru: { title: 'Т' } },
      media: { primaryAssetId: 'shared' },
    });
    const fields = fixture.debugElement.queryAll(By.directive(ContentMediaField));
    (fields[1].componentInstance as ContentMediaField).changed.emit({
      assetId: 'ru-image',
      posterAssetId: null,
    });

    expect(emitted.at(-1)?.['texts']).toEqual({ ru: { title: 'Т', mediaAssetId: 'ru-image' } });
    expect(emitted.at(-1)?.['media']).toEqual({ primaryAssetId: 'shared' });
  });

  it('writes the link the picker reports', () => {
    const { fixture, emitted } = setup('BANNER');
    const picker = fixture.debugElement.query(By.directive(ContentLinkPicker))
      .componentInstance as ContentLinkPicker;

    picker.linkChange.emit({ type: 'CATEGORY', targetId: 'c1', url: 'ignored' });

    expect(emitted.at(-1)?.['link']).toEqual({ type: 'CATEGORY', targetId: 'c1' });
  });

  it('offers a body and no subtitle on a pop-up, and the pop-up settings only there', () => {
    const popup = setup('POPUP');
    expect(popup.root.querySelector('[data-testid="content-body"]')).toBeTruthy();
    expect(popup.root.querySelector('[data-testid="content-subtitle"]')).toBeNull();
    expect(popup.root.querySelector('[data-testid="content-popup-cap"]')).toBeTruthy();

    TestBed.resetTestingModule();
    const banner = setup('BANNER');
    expect(banner.root.querySelector('[data-testid="content-popup-cap"]')).toBeNull();
    expect(banner.root.querySelector('[data-testid="content-body"]')).toBeNull();
  });

  describe('pop-up settings', () => {
    it('changes the delay', () => {
      const { fixture, emitted } = setup('POPUP');
      const stepper = fixture.debugElement.query(By.css('q-number-stepper'));

      (stepper.componentInstance as { valueChange: { emit(v: number): void } }).valueChange.emit(
        20,
      );

      expect(emitted.at(-1)?.['showAfterSeconds']).toBe(20);
    });

    it('opens a daily window with working hours on every day, and clears it again', () => {
      const { root, emitted, fixture } = setup('POPUP');
      const box = root.querySelector<HTMLInputElement>('[data-testid="content-popup-daily"]')!;

      box.checked = true;
      box.dispatchEvent(new Event('change'));
      expect(emitted.at(-1)?.['daily']).toEqual({
        from: '09:00',
        until: '21:00',
        daysOfWeek: [1, 2, 3, 4, 5, 6, 7],
      });

      fixture.componentRef.setInput('document', emitted.at(-1)!);
      fixture.detectChanges();
      box.checked = false;
      box.dispatchEvent(new Event('change'));
      expect('daily' in emitted.at(-1)!).toBe(false);
    });

    it('edits the hours and the days of an open window', () => {
      const { fixture, emitted } = setup('POPUP', {
        ...emptyDocument('POPUP'),
        daily: { from: '09:00', until: '21:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] },
      });

      const times = fixture.debugElement.queryAll(By.directive(TimeInput));
      (times[0].componentInstance as TimeInput).valueChange.emit('12:30');
      expect((emitted.at(-1)?.['daily'] as { from: string }).from).toBe('12:30');

      (times[1].componentInstance as TimeInput).valueChange.emit('23:00');
      expect((emitted.at(-1)?.['daily'] as { until: string }).until).toBe('23:00');

      const days = fixture.debugElement.query(By.directive(DayOfWeekToggle))
        .componentInstance as DayOfWeekToggle;
      days.selectedChange.emit(new Set([6, 7, 1]));
      expect((emitted.at(-1)?.['daily'] as { daysOfWeek: number[] }).daysOfWeek).toEqual([1, 6, 7]);
    });

    it('asks for the number of days only for an every-few-days cap', () => {
      const { fixture, root, emitted } = setup('POPUP');
      const select = root.querySelector<HTMLSelectElement>('[data-testid="content-popup-cap"]')!;
      expect(fixture.debugElement.queryAll(By.css('q-number-stepper'))).toHaveLength(1);

      select.value = 'EVERY_N_DAYS';
      select.dispatchEvent(new Event('change'));
      expect(emitted.at(-1)?.['cap']).toEqual({ mode: 'EVERY_N_DAYS', n: 7 });

      fixture.componentRef.setInput('document', emitted.at(-1)!);
      fixture.detectChanges();
      expect(fixture.debugElement.queryAll(By.css('q-number-stepper'))).toHaveLength(2);
    });
  });
});
