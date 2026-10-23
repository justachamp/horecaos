import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../services/translate.service';
import { CategoriesOverlayComponent, type CategoryChoice } from './categories-overlay.component';

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

const CATEGORIES: readonly CategoryChoice[] = [
  { id: 'c-1', label: 'Soups' },
  { id: 'c-2', label: 'Mains' },
];

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
      'home.allCategories',
    );
    expect(Array.from(host.querySelectorAll('li')).map((li) => li.textContent?.trim())).toEqual([
      'home.all',
      'Soups',
      'Mains',
    ]);
  });

  it('reports the category chosen, and null for «All»', async () => {
    const { host, chosen } = await render();

    (host.querySelector('[data-testid="categories-overlay-c-2"]') as HTMLButtonElement).click();
    (host.querySelector('[data-testid="categories-overlay-all"]') as HTMLButtonElement).click();

    expect(chosen).toEqual(['c-2', null]);
  });

  it('marks the category in force as current for assistive technology', async () => {
    const { host } = await render('c-1');

    expect(
      host.querySelector('[data-testid="categories-overlay-c-1"]')?.getAttribute('aria-current'),
    ).toBe('true');
    expect(
      host.querySelector('[data-testid="categories-overlay-c-2"]')?.getAttribute('aria-current'),
    ).toBeNull();
    expect(
      host.querySelector('[data-testid="categories-overlay-all"]')?.getAttribute('aria-current'),
    ).toBeNull();
  });

  it('closes on Escape, on the close button and on the backdrop, but not on a click inside the sheet', async () => {
    const { host, closed } = await render();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(closed()).toBe(1);

    (host.querySelector('[data-autofocus]') as HTMLButtonElement).click();
    expect(closed()).toBe(2);

    (host.querySelector('[data-testid="categories-overlay-backdrop"]') as HTMLElement).click();
    expect(closed()).toBe(3);

    (host.querySelector('[role="dialog"]') as HTMLElement).click();
    expect(closed()).toBe(3);
  });
});
