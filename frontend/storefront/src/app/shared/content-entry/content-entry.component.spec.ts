import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EditorialService } from '../../services/editorial.service';
import { TranslateService } from '../../services/translate.service';
import type { EditorialFeatures } from '../../types/editorial.types';
import { ContentEntryComponent } from './content-entry.component';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

const OFF: EditorialFeatures = { news: false, gallery: false, recipes: false, vacancies: false };

function render(features: EditorialFeatures) {
  const flags = signal(features);
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: EditorialService, useValue: { features: flags } },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(ContentEntryComponent);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    host,
    flags,
    links: () =>
      Array.from(host.querySelectorAll('a')).map((a) => [
        a.getAttribute('href'),
        a.textContent?.trim(),
      ]),
  };
}

describe('ContentEntryComponent: entry points only for the kinds the brand turned on', () => {
  it('draws nothing at all when every kind is off', () => {
    const { host } = render(OFF);

    expect(host.querySelector('nav')).toBeNull();
    expect(host.querySelector('a')).toBeNull();
  });

  it('links to a kind that is on, with the label of that kind', () => {
    const { links } = render({ ...OFF, news: true });

    expect(links()).toEqual([['/news', 'content.editorial.news']]);
  });

  it('draws the links in a fixed order, and the vacancy listing is the careers page', () => {
    const { links } = render({ news: true, gallery: true, recipes: true, vacancies: true });

    expect(links()).toEqual([
      ['/news', 'content.editorial.news'],
      ['/gallery', 'content.editorial.gallery'],
      ['/recipes', 'content.editorial.recipes'],
      ['/careers', 'content.editorial.careers'],
    ]);
  });

  it('leaves out the kinds that are off', () => {
    const { links } = render({ news: false, gallery: true, recipes: false, vacancies: true });

    expect(links()).toEqual([
      ['/gallery', 'content.editorial.gallery'],
      ['/careers', 'content.editorial.careers'],
    ]);
  });

  it('is a labelled navigation', () => {
    const { host } = render({ ...OFF, recipes: true });

    expect(host.querySelector('nav')?.getAttribute('aria-label')).toBe('content.editorialSection');
  });

  it('follows the flags when they change', () => {
    const { fixture, flags, links } = render(OFF);

    flags.set({ ...OFF, gallery: true });
    fixture.detectChanges();

    expect(links()).toEqual([['/gallery', 'content.editorial.gallery']]);
  });
});
