import type { MenuItemModifierGroup, MenuItemModifierOption } from '../types/home.types';
import {
  canBeSatisfied,
  chosenOptionIds,
  isMandatory,
  maximumSelections,
  minimumSelections,
  toggleOption,
  unsatisfiedGroups,
  unsatisfiedGroupsFor,
} from './modifier-selection';

function option(id: string): MenuItemModifierOption {
  return { id, label: id, amountMinor: null, maximumQuantity: 1 };
}

function group(overrides: Partial<MenuItemModifierGroup> = {}): MenuItemModifierGroup {
  return {
    id: 'g1',
    name: 'Sauce',
    required: false,
    minimumSelections: 0,
    maximumSelections: 2,
    allowSameOptionMultipleTimes: false,
    options: [option('a'), option('b'), option('c')],
    ...overrides,
  };
}

describe('modifier selection rules', () => {
  describe('bounds', () => {
    it('a required group needs at least one option, whatever its minimum says', () => {
      expect(minimumSelections(group({ required: true, minimumSelections: 0 }))).toBe(1);
      expect(minimumSelections(group({ required: true, minimumSelections: 2 }))).toBe(2);
    });

    it('an optional group needs exactly what its minimum says', () => {
      expect(minimumSelections(group({ required: false, minimumSelections: 0 }))).toBe(0);
      expect(minimumSelections(group({ required: false, minimumSelections: 1 }))).toBe(1);
    });

    it('a maximum of zero means the menu sets no ceiling, not that nothing may be chosen', () => {
      expect(maximumSelections(group({ maximumSelections: 0 }))).toBe(Number.POSITIVE_INFINITY);
      expect(maximumSelections(group({ maximumSelections: 3 }))).toBe(3);
    });

    it('a group is mandatory when it is required or has a minimum, and only then', () => {
      expect(isMandatory(group({ required: true }))).toBe(true);
      expect(isMandatory(group({ required: false, minimumSelections: 1 }))).toBe(true);
      expect(isMandatory(group())).toBe(false);
    });

    it('a mandatory group with fewer options than it demands can never be satisfied', () => {
      expect(canBeSatisfied(group({ required: true, options: [] }))).toBe(false);
      expect(
        canBeSatisfied(group({ required: false, minimumSelections: 2, options: [option('a')] })),
      ).toBe(false);
      expect(canBeSatisfied(group({ required: true }))).toBe(true);
      expect(canBeSatisfied(group({ options: [] }))).toBe(true);
    });
  });

  describe('toggleOption', () => {
    it('adds an option, and a second tap takes it back', () => {
      const g = group();
      const once = toggleOption({}, g, 'a');
      expect(once).toEqual({ g1: ['a'] });
      expect(toggleOption(once, g, 'a')).toEqual({ g1: [] });
    });

    it('refuses an option past the ceiling and returns the choices unchanged', () => {
      const g = group({ maximumSelections: 2 });
      const full = { g1: ['a', 'b'] };
      expect(toggleOption(full, g, 'c')).toBe(full);
    });

    it('a one-choice group replaces the choice instead of refusing the second tap', () => {
      const g = group({ required: true, maximumSelections: 1 });
      expect(toggleOption({ g1: ['a'] }, g, 'b')).toEqual({ g1: ['b'] });
    });

    it('a group with no ceiling keeps taking options', () => {
      const g = group({ maximumSelections: 0 });
      let choices = toggleOption({}, g, 'a');
      choices = toggleOption(choices, g, 'b');
      choices = toggleOption(choices, g, 'c');
      expect(choices).toEqual({ g1: ['a', 'b', 'c'] });
    });

    it('leaves the other groups alone', () => {
      const g = group({ id: 'g2' });
      expect(toggleOption({ g1: ['a'] }, g, 'b')).toEqual({ g1: ['a'], g2: ['b'] });
    });
  });

  describe('unsatisfiedGroups', () => {
    const required = group({ id: 'size', name: 'Size', required: true, maximumSelections: 1 });
    const optional = group({ id: 'extra', name: 'Extras' });

    it('names the required group that has nothing chosen', () => {
      expect(unsatisfiedGroups([required, optional], {}).map((entry) => entry.id)).toEqual([
        'size',
      ]);
    });

    it('is empty once every mandatory group is satisfied, and an optional one may stay empty', () => {
      expect(unsatisfiedGroups([required, optional], { size: ['a'] })).toEqual([]);
    });

    it('counts a group holding more than its ceiling as unsatisfied', () => {
      expect(
        unsatisfiedGroups([optional], { extra: ['a', 'b', 'c'] }).map((entry) => entry.id),
      ).toEqual(['extra']);
    });

    it('holds a group with a minimum of two to two', () => {
      const pair = group({ id: 'pair', required: true, minimumSelections: 2 });
      expect(unsatisfiedGroups([pair], { pair: ['a'] })).toHaveLength(1);
      expect(unsatisfiedGroups([pair], { pair: ['a', 'b'] })).toHaveLength(0);
    });

    it('does the same for a flat list of ids, counting each id against the group that offers it', () => {
      const other = group({ id: 'other', options: [option('x')] });
      expect(unsatisfiedGroupsFor([required, other], []).map((entry) => entry.id)).toEqual([
        'size',
      ]);
      expect(unsatisfiedGroupsFor([required, other], ['a'])).toEqual([]);
      // An id no group offers satisfies nothing.
      expect(unsatisfiedGroupsFor([required, other], ['nope']).map((entry) => entry.id)).toEqual([
        'size',
      ]);
    });
  });

  it("flattens the choices in the dish's group order", () => {
    const first = group({ id: 'first' });
    const second = group({ id: 'second' });
    expect(chosenOptionIds([first, second], { second: ['c'], first: ['b', 'a'] })).toEqual([
      'b',
      'a',
      'c',
    ]);
    expect(chosenOptionIds([first, second], {})).toEqual([]);
  });
});
