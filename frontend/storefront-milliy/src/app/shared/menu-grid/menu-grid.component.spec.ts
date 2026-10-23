import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { MenuGridComponent } from './menu-grid.component';
import { TranslateService } from '../../services/translate.service';
import type { CategoryItem, MenuItem, MenuItemTag } from '../../types/home.types';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
  current = (): Record<string, unknown> => ({});
}

function tag(id: string, over: Partial<MenuItemTag> = {}): MenuItemTag {
  return { id, code: id, icon: null, showAsBadge: true, showAsFilter: true, name: id, ...over };
}

function dish(id: string, tags: MenuItemTag[]): MenuItem {
  return {
    id,
    name: `Dish ${id}`,
    description: '',
    active: true,
    has_discount: false,
    preparation_time: 0,
    price: 10_000,
    price_without_discount: 10_000,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 0,
    variants: [
      {
        id: `${id}-v`,
        name: '',
        active: true,
        onSaleNow: true,
        preparation_time: 0,
        price: 10_000,
        price_without_discount: 10_000,
        remainingQuantity: null,
      },
    ],
    modifierGroups: [],
    tags,
  };
}

function section(id: string, items: MenuItem[]): CategoryItem {
  return { id, name: `Section ${id}`, items, items_count: items.length };
}

describe('MenuGridComponent: tag filters (ADR 0174)', () => {
  const spicy = tag('spicy', { icon: 'FLAME' });
  const vegan = tag('vegan');
  const badgeOnly = tag('new', { showAsFilter: false });

  function render(sections: CategoryItem[]) {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: TranslateService, useClass: FakeTranslateService }],
    });
    const fixture = TestBed.createComponent(MenuGridComponent);
    fixture.componentRef.setInput('categories', []);
    fixture.componentRef.setInput('sections', sections);
    fixture.componentRef.setInput('currency', 'UZS');
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    return {
      host,
      names: () =>
        Array.from(host.querySelectorAll('.dish__name')).map((node) => node.textContent?.trim()),
      chip: (code: string) => host.querySelector<HTMLButtonElement>(`[data-tag="${code}"].chip`),
      press: (code: string) => {
        host.querySelector<HTMLButtonElement>(`[data-tag="${code}"].chip`)?.click();
        fixture.detectChanges();
      },
      clear: () => {
        host.querySelector<HTMLButtonElement>('[data-testid="tag-filter-clear"]')?.click();
        fixture.detectChanges();
      },
    };
  }

  const sections = [
    section('a', [dish('1', [spicy, vegan]), dish('2', [spicy]), dish('3', [badgeOnly])]),
    section('b', [dish('4', [vegan])]),
  ];

  it('offers a chip only for a tag its author made a filter option, once', () => {
    const { host, chip } = render(sections);

    expect(host.querySelector('[data-testid="tag-filter"]')).not.toBeNull();
    expect(chip('spicy')).not.toBeNull();
    expect(chip('vegan')).not.toBeNull();
    expect(chip('new')).toBeNull();
    expect(host.querySelectorAll('[data-tag="spicy"].chip')).toHaveLength(1);
  });

  it('draws no filter bar on a menu with no filter tags', () => {
    const { host } = render([section('a', [dish('1', [badgeOnly]), dish('2', [])])]);

    expect(host.querySelector('[data-testid="tag-filter"]')).toBeNull();
  });

  it('shows every dish with nothing selected, and only dishes carrying every selected tag otherwise', () => {
    const { names, press } = render(sections);
    expect(names()).toEqual(['Dish 1', 'Dish 2', 'Dish 3', 'Dish 4']);

    press('spicy');
    expect(names()).toEqual(['Dish 1', 'Dish 2']);

    press('vegan');
    expect(names()).toEqual(['Dish 1']);
  });

  it('says so when nothing carries the selected tags, and clearing brings the menu back', () => {
    const only = [section('a', [dish('1', [spicy]), dish('2', [vegan])])];
    const { host, names, press, clear } = render(only);

    press('spicy');
    press('vegan');
    expect(names()).toEqual([]);
    expect(host.querySelector('[data-testid="tag-filter-empty"]')).not.toBeNull();

    clear();
    expect(names()).toEqual(['Dish 1', 'Dish 2']);
    expect(host.querySelector('[data-testid="tag-filter-empty"]')).toBeNull();
  });
});

describe('MenuGridComponent: the compact rail of the marketplace layout (ADR 0183 D17)', () => {
  const categories = Array.from({ length: 8 }, (_, i) => ({
    id: `c-${i + 1}`,
    name: `Category ${i + 1}`,
  }));
  const sections = categories.map((category) =>
    section(category.id, [dish(`d-${category.id}`, [])]),
  );

  function render(layout: 'STANDARD' | 'MARKETPLACE') {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: TranslateService, useClass: FakeTranslateService }],
    });
    const fixture = TestBed.createComponent(MenuGridComponent);
    fixture.componentRef.setInput('categories', categories);
    fixture.componentRef.setInput('sections', sections);
    fixture.componentRef.setInput('currency', 'UZS');
    fixture.componentRef.setInput('layout', layout);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    return {
      fixture,
      host,
      rail: () =>
        Array.from(
          host.querySelectorAll<HTMLButtonElement>('nav[aria-label="home.categories"] .chip'),
        )
          .map((chip) => (chip.textContent ?? '').trim())
          .filter((name) => name.startsWith('Category ')),
      opener: () => host.querySelector<HTMLButtonElement>('[data-testid="all-categories-open"]'),
      dialog: () => host.querySelector('[role="dialog"]'),
    };
  }

  it('draws every category and no opener in the standard layout, as before', () => {
    const { rail, opener } = render('STANDARD');

    expect(rail()).toHaveLength(8);
    expect(opener()).toBeNull();
  });

  it('draws the first five and an «all categories» opener in the marketplace layout', () => {
    const { rail, opener, dialog } = render('MARKETPLACE');

    expect(rail()).toEqual(['Category 1', 'Category 2', 'Category 3', 'Category 4', 'Category 5']);
    expect(opener()).not.toBeNull();
    expect(dialog()).toBeNull();
  });

  it('lists every category in the overlay, applies a choice made there, closes, and keeps the choice on the rail', () => {
    const { fixture, host, rail, opener, dialog } = render('MARKETPLACE');

    opener()?.click();
    fixture.detectChanges();
    expect(dialog()).not.toBeNull();
    expect(dialog()?.querySelectorAll('li')).toHaveLength(9);

    host.querySelector<HTMLButtonElement>('[data-testid="categories-overlay-c-7"]')?.click();
    fixture.detectChanges();

    expect(dialog()).toBeNull();
    expect(rail()).toEqual(['Category 1', 'Category 2', 'Category 3', 'Category 4', 'Category 7']);
    expect(host.querySelectorAll('.section__title')).toHaveLength(1);
    expect(host.querySelector('.section__title')?.textContent?.trim()).toBe('Section c-7');
  });

  it('closes the overlay with Escape and chooses nothing', () => {
    const { fixture, host, opener, dialog } = render('MARKETPLACE');

    opener()?.click();
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    expect(dialog()).toBeNull();
    expect(host.querySelectorAll('.section__title')).toHaveLength(8);
  });

  it('shows a rail that is already short in full, with the opener still there', () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: TranslateService, useClass: FakeTranslateService }],
    });
    const fixture = TestBed.createComponent(MenuGridComponent);
    fixture.componentRef.setInput('categories', categories.slice(0, 3));
    fixture.componentRef.setInput('sections', sections.slice(0, 3));
    fixture.componentRef.setInput('layout', 'MARKETPLACE');
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(
      Array.from(host.querySelectorAll('nav[aria-label="home.categories"] .chip')).filter((chip) =>
        (chip.textContent ?? '').trim().startsWith('Category '),
      ),
    ).toHaveLength(3);
    expect(host.querySelector('[data-testid="all-categories-open"]')).not.toBeNull();
  });
});
