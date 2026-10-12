import type { ContentGroup } from '../types/content.types';
import type { CategoryItem, MenuItem } from '../types/home.types';
import { resolveGroupItems } from './content-groups';

function dish(id: string): MenuItem {
  return {
    id,
    name: `Dish ${id}`,
    description: '',
    active: true,
    has_discount: false,
    preparation_time: 0,
    price: 1000,
    price_without_discount: 1000,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 0,
    variants: [],
    modifierGroups: [],
  };
}

function section(id: string, ...dishIds: string[]): CategoryItem {
  return { id, name: id, items: dishIds.map(dish), items_count: dishIds.length };
}

function group(overrides: Partial<ContentGroup>): ContentGroup {
  return {
    id: 'g1',
    revisionNo: 1,
    title: 'Group',
    textLocale: 'uz-Latn',
    productIds: [],
    ...overrides,
  };
}

const MENU: readonly CategoryItem[] = [
  section('soups', 'p1', 'p2', 'p3'),
  section('mains', 'p4', 'p5'),
];

const ids = (items: readonly MenuItem[]) => items.map((item) => item.id);

describe('resolveGroupItems: a group is a view over the menu the page already holds', () => {
  it('lists the dishes of a manual group in the group order, not the menu order', () => {
    expect(ids(resolveGroupItems(group({ productIds: ['p5', 'p1', 'p4'] }), MENU))).toEqual([
      'p5',
      'p1',
      'p4',
    ]);
  });

  it('skips an id the menu lacks and keeps the rest in order', () => {
    expect(ids(resolveGroupItems(group({ productIds: ['p2', 'gone', 'p4'] }), MENU))).toEqual([
      'p2',
      'p4',
    ]);
  });

  it('lists a dish once even when the group names it twice', () => {
    expect(ids(resolveGroupItems(group({ productIds: ['p2', 'p2', 'p1'] }), MENU))).toEqual([
      'p2',
      'p1',
    ]);
  });

  it('is empty when the menu holds none of a manual group', () => {
    expect(resolveGroupItems(group({ productIds: ['x', 'y'] }), MENU)).toEqual([]);
  });

  it('is empty for a group that names neither dishes nor a category', () => {
    expect(resolveGroupItems(group({}), MENU)).toEqual([]);
  });

  it('takes the dishes of a by-category group from that category, in the menu order', () => {
    expect(
      ids(resolveGroupItems(group({ categoryId: 'soups', order: 'MENU_ORDER' }), MENU)),
    ).toEqual(['p1', 'p2', 'p3']);
  });

  it('keeps the menu order for NEWEST too: the menu carries no timestamp to sort by', () => {
    expect(ids(resolveGroupItems(group({ categoryId: 'mains', order: 'NEWEST' }), MENU))).toEqual([
      'p4',
      'p5',
    ]);
  });

  it('caps a by-category group at its limit', () => {
    expect(ids(resolveGroupItems(group({ categoryId: 'soups', limit: 2 }), MENU))).toEqual([
      'p1',
      'p2',
    ]);
  });

  it.each([0, -3, 1.5, Number.NaN])(
    'ignores a limit that is not a positive whole number (%s)',
    (limit) => {
      expect(ids(resolveGroupItems(group({ categoryId: 'soups', limit }), MENU))).toEqual([
        'p1',
        'p2',
        'p3',
      ]);
    },
  );

  it('is empty when the category is not on the menu', () => {
    expect(resolveGroupItems(group({ categoryId: 'desserts' }), MENU)).toEqual([]);
  });

  it('reads the category when a group names both a category and dishes', () => {
    expect(
      ids(resolveGroupItems(group({ categoryId: 'mains', productIds: ['p1'] }), MENU)),
    ).toEqual(['p4', 'p5']);
  });
});
