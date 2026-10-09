import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { RuleList } from '../../../shared/ui/rule-list';
import { ContentDocument } from './content-api';
import { ContentStoryFields, MAX_SLIDES } from './content-story-fields';
import { NEW_SLIDE, emptyDocument, withSlides } from './content-document';
import { ContentLinkPicker } from './content-link-picker';
import { ContentMedia } from './content-media';
import { ContentMediaField } from './content-media-field';
import { ContentReferences } from './content-references';

function setup(document: ContentDocument = emptyDocument('STORY_GROUP')): {
  fixture: ReturnType<typeof TestBed.createComponent<ContentStoryFields>>;
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
  const fixture = TestBed.createComponent(ContentStoryFields);
  fixture.componentRef.setInput('document', document);
  fixture.componentRef.setInput('locale', 'ru');
  const emitted: ContentDocument[] = [];
  fixture.componentInstance.documentChange.subscribe((d) => {
    emitted.push(d);
    fixture.componentRef.setInput('document', d);
    fixture.detectChanges();
  });
  fixture.detectChanges();
  return { fixture, emitted, root: fixture.nativeElement as HTMLElement };
}

const threeSlides = (): ContentDocument =>
  withSlides(emptyDocument('STORY_GROUP'), [
    { ...NEW_SLIDE, assetId: 'a1', captions: { ru: 'Первый' } },
    { ...NEW_SLIDE, assetId: 'a2', captions: { ru: 'Второй' } },
    { ...NEW_SLIDE, assetId: 'a3', captions: { ru: 'Третий' } },
  ]);

const slidesOfDoc = (doc: ContentDocument | undefined): { media: { assetId?: string } }[] =>
  (doc?.['slides'] ?? []) as { media: { assetId?: string } }[];

describe('ContentStoryFields (ADR 0157, Decision 10: stories)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('titles the story in the language being edited, up to 40 characters', () => {
    const { root, emitted } = setup();
    const input = root.querySelector<HTMLInputElement>('[data-testid="content-title"]')!;
    expect(input.getAttribute('maxlength')).toBe('40');

    input.value = 'Новинки';
    input.dispatchEvent(new Event('input'));

    expect(emitted.at(-1)?.['texts']).toEqual({ ru: { title: 'Новинки' } });
  });

  it('adds slides one at a time and selects the new one, up to ten', () => {
    const { root, emitted } = setup();
    const add = root.querySelector<HTMLButtonElement>('[data-testid="content-story-add"]')!;

    add.click();
    expect(slidesOfDoc(emitted.at(-1))).toHaveLength(1);
    expect(root.querySelector('[data-testid="content-story-slide"]')).toBeTruthy();

    for (let i = 1; i < MAX_SLIDES; i++) {
      add.click();
    }
    expect(slidesOfDoc(emitted.at(-1))).toHaveLength(MAX_SLIDES);
    expect(add.disabled).toBe(true);
  });

  it('numbers the slides by their place and shows each one’s caption in the list', () => {
    const { fixture } = setup(threeSlides());
    const list = fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList;

    expect(list.showToggle()).toBe(false);
    expect(list.items().map((i) => [i.label, i.description])).toEqual([
      ['Slide 1', 'Первый'],
      ['Slide 2', 'Второй'],
      ['Slide 3', 'Третий'],
    ]);
  });

  it('reorders the slides and keeps the same slide selected', () => {
    const { fixture, emitted } = setup(threeSlides());
    const list = fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList;

    list.selected.emit('0');
    fixture.detectChanges();
    list.reorder.emit(['2', '0', '1']);

    expect(slidesOfDoc(emitted.at(-1)).map((s) => s.media.assetId)).toEqual(['a3', 'a1', 'a2']);
    expect((emitted.at(-1)?.['slides'] as { position: number }[]).map((s) => s.position)).toEqual([
      1, 2, 3,
    ]);
    expect(
      (
        fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList
      ).selectedId(),
    ).toBe('1');
  });

  it('edits the chosen slide only: its picture, its length, its caption in this language and its link', () => {
    const { fixture, emitted } = setup(threeSlides());
    const list = fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList;
    list.selected.emit('1');
    fixture.detectChanges();

    const media = fixture.debugElement.queryAll(By.directive(ContentMediaField))[1]
      .componentInstance as ContentMediaField;
    media.changed.emit({ assetId: 'new-asset', posterAssetId: null });
    expect(slidesOfDoc(emitted.at(-1)).map((s) => s.media.assetId)).toEqual([
      'a1',
      'new-asset',
      'a3',
    ]);

    (
      fixture.debugElement.query(By.directive(NumberStepper)).componentInstance as NumberStepper
    ).valueChange.emit(9);
    expect((emitted.at(-1)?.['slides'] as { durationSeconds: number }[])[1].durationSeconds).toBe(
      9,
    );

    const caption = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="content-story-caption"]',
    )!;
    expect(caption.value).toBe('Второй');
    caption.value = 'Обновлено';
    caption.dispatchEvent(new Event('input'));
    expect(
      (emitted.at(-1)?.['slides'] as { texts: Record<string, { caption: string }> }[])[1].texts,
    ).toEqual({ ru: { caption: 'Обновлено' } });

    (
      fixture.debugElement.query(By.directive(ContentLinkPicker))
        .componentInstance as ContentLinkPicker
    ).linkChange.emit({ type: 'CART', targetId: null, url: '' });
    expect((emitted.at(-1)?.['slides'] as { link: unknown }[])[1].link).toEqual({ type: 'CART' });
    expect((emitted.at(-1)?.['slides'] as { link: unknown }[])[0].link).toEqual({ type: 'NONE' });
  });

  it('turns a slide into a video when the field reports a poster', () => {
    const { fixture, emitted } = setup(threeSlides());
    const media = fixture.debugElement.queryAll(By.directive(ContentMediaField))[1]
      .componentInstance as ContentMediaField;

    media.changed.emit({ assetId: 'v1', posterAssetId: 'p1' });

    expect((emitted.at(-1)?.['slides'] as { media: unknown }[])[0].media).toEqual({
      kind: 'VIDEO',
      assetId: 'v1',
      posterAssetId: 'p1',
    });
  });

  it('removes the chosen slide and leaves the selection on a slide that exists', () => {
    const { fixture, root, emitted } = setup(threeSlides());
    const list = fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList;
    list.selected.emit('2');
    fixture.detectChanges();

    (root.querySelector('[data-testid="content-story-remove"]') as HTMLButtonElement).click();

    expect(slidesOfDoc(emitted.at(-1)).map((s) => s.media.assetId)).toEqual(['a1', 'a2']);
    expect(root.querySelector('[data-testid="content-story-slide"]')).toBeTruthy();
  });

  it('offers a video only when the plan allows it', () => {
    const { fixture } = setup(threeSlides());
    const slideField = (): ContentMediaField =>
      fixture.debugElement.queryAll(By.directive(ContentMediaField))[1]
        .componentInstance as ContentMediaField;
    expect(slideField().allowVideo()).toBe(false);

    fixture.componentRef.setInput('allowVideo', true);
    fixture.detectChanges();
    expect(slideField().allowVideo()).toBe(true);
  });
});
