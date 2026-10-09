import type { MenuItem, MenuItemTag } from '../types/home.types';
import { badgesOf, filterOptionsOf, matchesAllTags } from './menu-tags';

function tag(id: string, over: Partial<MenuItemTag> = {}): MenuItemTag {
  return { id, code: id, icon: null, showAsBadge: true, showAsFilter: true, name: id, ...over };
}

function item(id: string, tags: MenuItemTag[] | undefined): MenuItem {
  return { id, name: id, tags } as unknown as MenuItem;
}

describe('menu tags (ADR 0174)', () => {
  const spicy = tag('spicy');
  const vegan = tag('vegan');
  const badgeOnly = tag('new', { showAsFilter: false });
  const filterOnly = tag('halal', { showAsBadge: false });

  describe('matchesAllTags', () => {
    it('shows everything when nothing is selected', () => {
      expect(matchesAllTags(item('a', [spicy]), new Set())).toBe(true);
      expect(matchesAllTags(item('b', undefined), new Set())).toBe(true);
    });

    it('combines selected chips as AND: a product must carry every one', () => {
      const both = item('both', [spicy, vegan]);
      const one = item('one', [spicy]);

      expect(matchesAllTags(both, new Set(['spicy', 'vegan']))).toBe(true);
      expect(matchesAllTags(one, new Set(['spicy', 'vegan']))).toBe(false);
      expect(matchesAllTags(one, new Set(['spicy']))).toBe(true);
    });

    it('hides a product that carries no tags when something is selected', () => {
      expect(matchesAllTags(item('bare', undefined), new Set(['spicy']))).toBe(false);
      expect(matchesAllTags(item('bare', []), new Set(['spicy']))).toBe(false);
    });
  });

  describe('filterOptionsOf', () => {
    it('offers each filter tag once, in the order first met, and never a badge-only tag', () => {
      const options = filterOptionsOf([
        item('a', [spicy, badgeOnly]),
        item('b', [vegan, spicy]),
        item('c', [filterOnly]),
      ]);

      expect(options.map((option) => option.id)).toEqual(['spicy', 'vegan', 'halal']);
    });

    it('offers nothing for a menu that carries no tags', () => {
      expect(filterOptionsOf([item('a', undefined), item('b', [])])).toEqual([]);
    });
  });

  describe('badgesOf', () => {
    it('draws the badge tags in the author’s order and skips filter-only ones', () => {
      expect(badgesOf(item('a', [filterOnly, spicy, badgeOnly])).map((t) => t.id)).toEqual([
        'spicy',
        'new',
      ]);
    });
  });
});
