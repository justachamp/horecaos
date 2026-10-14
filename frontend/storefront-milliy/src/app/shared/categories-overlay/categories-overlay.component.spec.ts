import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../services/translate.service';
import type { MenuCategory } from '../../types/home.types';
import { CategoriesOverlayComponent } from './categories-overlay.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
  current = (): Record<string, unknown> => ({});
}

const CATEGORIES = [
  { id: 'c-1', name: 'Soups' },
  { id: 'c-2', name: 'Mains' },
] as unknown as readonly MenuCategory[];

async function render(selectedId: string | null = null) {
  TestBed.configureTestingModule({
    imports: [CategoriesOverlayComponent],
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(CategoriesOverlayComponent);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('categories', CATEGORIES);
  fixture.componentRef.setInput('selectedId', selectedId);
  const chosen: (string | null)[] = [];
  let closed = 0;
  fixture.componentInstance.chosen.subscribe((id) => chosen.push(id));
  fixture.componentInstance.closed.subscribe(() => closed++);
  fixture.detectChanges();
  await fixture.whenStable();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, chosen, closed: () => closed };
}

describe('CategoriesOverlayComponent (ADR 0183 D17)', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('is a modal dialog named by its title, listing «All» and every category', async () => {
    const { host } = await render();

    const dialog = host.querySelector('[role="dialog"]');
    expect(dialog?.getAttribute('aria-modal')).toBe('true');
    expect(dialog?.getAttribute('aria-labelledby')).toBe('categories-overlay-title');
    expect(host.querySelector('#categories-overlay-title')?.textContent?.trim()).toBe(
      'home.showAllCategories',
    );
    expect(Array.from(host.querySelectorAll('li')).map((li) => li.textContent?.trim())).toEqual([
      'home.allCategories',
      'Soups',
      'Mains',
    ]);
  });

  it('puts focus on the close button when it opens', async () => {
    const { host } = await render();

    expect(document.activeElement).toBe(
      host.querySelector('[data-testid="categories-overlay-close"]'),
    );
  });

  it('reports the category chosen, and null for «All»', async () => {
    const { host, chosen } = await render();

    (host.querySelector('[data-testid="categories-overlay-c-2"]') as HTMLButtonElement).click();
    (host.querySelector('[data-testid="categories-overlay-all"]') as HTMLButtonElement).click();

    expect(chosen).toEqual(['c-2', null]);
  });

  it('marks the category in force as current for assistive technology', async () => {
    const { host } = await render('c-1');

    const current = (id: string) =>
      host.querySelector(`[data-testid="categories-overlay-${id}"]`)?.getAttribute('aria-current');
    expect(current('c-1')).toBe('true');
    expect(current('c-2')).toBeNull();
    expect(current('all')).toBeNull();
  });

  it('closes on Escape, on the close button and on the backdrop, but not on a click inside the sheet', async () => {
    const { host, closed } = await render();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(closed()).toBe(1);

    (host.querySelector('[data-testid="categories-overlay-close"]') as HTMLButtonElement).click();
    expect(closed()).toBe(2);

    (host.querySelector('[data-testid="categories-overlay-backdrop"]') as HTMLElement).click();
    expect(closed()).toBe(3);

    (host.querySelector('[role="dialog"]') as HTMLElement).click();
    expect(closed()).toBe(3);
  });
});
