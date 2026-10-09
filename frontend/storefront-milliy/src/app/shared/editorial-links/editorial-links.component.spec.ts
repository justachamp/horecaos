import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EditorialService } from '../../services/editorial.service';
import { TranslateService } from '../../services/translate.service';
import { NO_EDITORIAL, type EditorialFeatures } from '../../types/editorial.types';
import { EditorialLinksComponent } from './editorial-links.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string) => key;
  current = () => ({});
}

function render(features: EditorialFeatures) {
  const state = signal(features);
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: EditorialService, useValue: { features: state.asReadonly() } },
    ],
  });
  const fixture = TestBed.createComponent(EditorialLinksComponent);
  fixture.detectChanges();
  return { fixture, state, host: fixture.nativeElement as HTMLElement };
}

const hrefs = (host: HTMLElement) =>
  [...host.querySelectorAll('a')].map((anchor) => anchor.getAttribute('href'));

describe('EditorialLinksComponent: the home screen’s way into the editorial pages', () => {
  it('draws nothing when no kind is on, which is also what a failed features read looks like', () => {
    const { host } = render({ ...NO_EDITORIAL });

    expect(host.querySelector('[data-testid="editorial-links"]')).toBeNull();
    expect(host.textContent?.trim()).toBe('');
  });

  it('links only the kinds that are on', () => {
    const { host } = render({ news: true, gallery: false, recipes: true, vacancies: false });

    expect(hrefs(host)).toEqual(['/news', '/recipes']);
    expect(host.textContent).toContain('editorial.news');
    expect(host.textContent).toContain('editorial.recipes');
    expect(host.textContent).not.toContain('editorial.gallery');
  });

  it('links vacancies to /careers', () => {
    const { host } = render({ news: false, gallery: false, recipes: false, vacancies: true });

    expect(hrefs(host)).toEqual(['/careers']);
    expect(host.textContent).toContain('editorial.careers');
  });

  it('lists all four, in a fixed order, when all are on', () => {
    const { host } = render({ news: true, gallery: true, recipes: true, vacancies: true });

    expect(hrefs(host)).toEqual(['/news', '/gallery', '/recipes', '/careers']);
  });

  it('is a labelled navigation', () => {
    const { host } = render({ news: true, gallery: false, recipes: false, vacancies: false });

    expect(
      host.querySelector('nav[data-testid="editorial-links"]')?.getAttribute('aria-label'),
    ).toBe('editorial.homeLabel');
  });

  it('follows the features as they change', () => {
    const { fixture, state, host } = render({ ...NO_EDITORIAL });

    state.set({ news: true, gallery: false, recipes: false, vacancies: false });
    fixture.detectChanges();
    expect(hrefs(host)).toEqual(['/news']);

    state.set({ ...NO_EDITORIAL });
    fixture.detectChanges();
    expect(hrefs(host)).toEqual([]);
  });
});
