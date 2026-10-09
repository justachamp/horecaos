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
