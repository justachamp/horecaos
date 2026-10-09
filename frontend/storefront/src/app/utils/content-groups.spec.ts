import type { ContentGroup } from '../types/content.types';
import type { MenuItem } from '../types/home.types';
import { resolveGroupItems, type MenuSection } from './content-groups';

function dish(id: string): MenuItem {
  return { id, name: `Dish ${id}` } as MenuItem;
}

const MENU: MenuSection[] = [
  { id: 'c1', name: 'Mains', items: [dish('p1'), dish('p2'), dish('p3')] },
  { id: 'c2', name: 'Drinks', items: [dish('p4'), dish('p2')] },
];

function manual(...productIds: string[]): ContentGroup {
  return {
    id: 'g',
    revisionNo: 1,
    title: 'G',
    textLocale: 'en',
    source: { kind: 'MANUAL', productIds },
  };
}

function byCategory(
  categoryId: string,
  order: 'MENU_ORDER' | 'NEWEST',
  limit?: number,
): ContentGroup {
  return {
    id: 'g',
    revisionNo: 1,
    title: 'G',
    textLocale: 'en',
    source: { kind: 'BY_CATEGORY', categoryId, order, ...(limit === undefined ? {} : { limit }) },
  };
}

const ids = (items: readonly MenuItem[]) => items.map((i) => i.id);

describe('resolveGroupItems: a manual group joins its ids against the menu the page holds', () => {
  it('keeps the group order, not the menu order', () => {
    expect(ids(resolveGroupItems(manual('p4', 'p1', 'p3'), MENU))).toEqual(['p4', 'p1', 'p3']);
  });

  it('skips an id the menu lacks', () => {
    expect(ids(resolveGroupItems(manual('p1', 'gone', 'p3'), MENU))).toEqual(['p1', 'p3']);
  });

  it('finds a product whichever category holds it, and shows it once', () => {
    expect(ids(resolveGroupItems(manual('p2', 'p4', 'p2'), MENU))).toEqual(['p2', 'p4']);
  });

  it('is empty when none of the ids is on the menu', () => {
    expect(resolveGroupItems(manual('x', 'y'), MENU)).toEqual([]);
  });

  it('is empty against a menu that has not loaded', () => {
    expect(resolveGroupItems(manual('p1'), [])).toEqual([]);
  });
});

describe('resolveGroupItems: a by-category group takes that category from the same menu', () => {
  it('takes the category in the menu order', () => {
    expect(ids(resolveGroupItems(byCategory('c1', 'MENU_ORDER'), MENU))).toEqual([
      'p1',
      'p2',
      'p3',
    ]);
  });

  it('NEWEST has no timestamp in the menu, so it keeps the menu order too', () => {
    expect(ids(resolveGroupItems(byCategory('c1', 'NEWEST'), MENU))).toEqual(['p1', 'p2', 'p3']);
  });

  it('caps at the limit', () => {
    expect(ids(resolveGroupItems(byCategory('c1', 'MENU_ORDER', 2), MENU))).toEqual(['p1', 'p2']);
  });

  it('a limit above the size changes nothing', () => {
    expect(ids(resolveGroupItems(byCategory('c2', 'MENU_ORDER', 99), MENU))).toEqual(['p4', 'p2']);
  });

  it('is empty for a category the menu does not have', () => {
    expect(resolveGroupItems(byCategory('nope', 'MENU_ORDER'), MENU)).toEqual([]);
  });
});
