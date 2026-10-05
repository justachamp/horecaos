import type {
  MenuItem,
  MenuItemModifierGroup,
  MenuItemModifierOption,
  MenuItemVariant,
} from '../types/home.types';
import {
  canBeSatisfied,
  chosenOptionIds,
  groupsForVariant,
  isMandatory,
  maximumSelections,
  minimumSelections,
  nestedKeyHash,
  nestedOnTheWire,
  openedGroups,
  pruneNested,
  toggleNested,
  toggleOption,
  unsatisfiedGroups,
  unsatisfiedGroupsFor,
  unsatisfiedNested,
  unsatisfiedNestedFor,
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

/**
 * ADR 0136: the second level. A first-level option can link a variant that carries choices of its
 * own ("Pizza halves" opens "Which pizza?"); taking the option asks them, and the answers travel
 * under it as `{ parentOptionId, optionId }`.
 */
describe('second-level choices', () => {
  const heat = group({
    id: 'heat',
    name: 'Heat',
    required: true,
    minimumSelections: 1,
    maximumSelections: 1,
    options: [option('mild'), option('hot')],
  });
  const extras = group({
    id: 'extras',
    name: 'Extras',
    required: false,
    minimumSelections: 0,
    maximumSelections: 2,
    options: [option('cheese'), option('bacon'), option('egg')],
  });
  const chili: MenuItemModifierOption = { ...option('chili'), nestedGroups: [heat, extras] };
  const garlic = option('garlic');
  const sauces = group({ id: 'sauces', name: 'Sauces', options: [chili, garlic] });

  describe('groupsForVariant', () => {
    function item(
      variants: Partial<MenuItemVariant>[],
    ): Pick<MenuItem, 'modifierGroups' | 'variants'> {
      return {
        modifierGroups: [sauces],
        variants: variants.map((v, i) => ({
          id: `v${i}`,
          name: `v${i}`,
          active: true,
          onSaleNow: true,
          preparation_time: 0,
          price: 0,
          price_without_discount: 0,
          remainingQuantity: null,
          ...v,
        })),
      };
    }

    it("falls back to the product's groups for a portion that published none of its own", () => {
      expect(groupsForVariant(item([{}, {}]), 'v1')).toEqual([sauces]);
    });

    it("uses the portion's own complete list when the menu published one", () => {
      const strict = { ...sauces, required: true, minimumSelections: 1 };
      const large = item([{}, { modifierGroups: [strict, extras] }]);
      expect(groupsForVariant(large, 'v1')).toEqual([strict, extras]);
      expect(groupsForVariant(large, 'v0')).toEqual([sauces]);
    });

    it('knows no portion by an unknown or missing id, and answers with the shared groups', () => {
      expect(groupsForVariant(item([{}]), 'nope')).toEqual([sauces]);
      expect(groupsForVariant(item([{}]), null)).toEqual([sauces]);
    });
  });

  describe('openedGroups', () => {
    it('lists only the chosen options that open choices, in the dish order', () => {
      expect(openedGroups([sauces], {})).toEqual([]);
      expect(openedGroups([sauces], { sauces: ['garlic'] })).toEqual([]);
      expect(openedGroups([sauces], { sauces: ['garlic', 'chili'] })).toEqual([
        { parent: chili, groups: [heat, extras] },
      ]);
    });
  });

  describe('toggleNested', () => {
    it('chooses inside the parent, replacing in a one-choice group and refusing past a ceiling', () => {
      let nested = toggleNested({}, 'chili', heat, 'mild');
      expect(nested).toEqual({ chili: { heat: ['mild'] } });
      nested = toggleNested(nested, 'chili', heat, 'hot');
      expect(nested).toEqual({ chili: { heat: ['hot'] } });
      nested = toggleNested(nested, 'chili', extras, 'cheese');
      nested = toggleNested(nested, 'chili', extras, 'bacon');
      const full = nested;
      expect(toggleNested(nested, 'chili', extras, 'egg')).toBe(full);
      expect(toggleNested(nested, 'chili', extras, 'cheese')).toEqual({
        chili: { heat: ['hot'], extras: ['bacon'] },
      });
    });
  });

  describe('pruneNested', () => {
    it('forgets the answers under an option that is no longer chosen', () => {
      const nested = { chili: { heat: ['hot'] } };
      expect(pruneNested([sauces], { sauces: ['chili'] }, nested)).toEqual(nested);
      expect(pruneNested([sauces], { sauces: ['garlic'] }, nested)).toEqual({});
      expect(pruneNested([sauces], {}, nested)).toEqual({});
    });
  });

  describe('unsatisfiedNested', () => {
    it('asks a chosen option for its required group and ignores an option nobody chose', () => {
      expect(unsatisfiedNested([sauces], { sauces: ['garlic'] }, {})).toEqual([]);
      const missing = unsatisfiedNested([sauces], { sauces: ['chili'] }, {});
      expect(missing.map((entry) => [entry.parent.id, entry.group.id])).toEqual([
        ['chili', 'heat'],
      ]);
      expect(
        unsatisfiedNested([sauces], { sauces: ['chili'] }, { chili: { heat: ['hot'] } }),
      ).toEqual([]);
    });

    it('also holds a group over its ceiling to account', () => {
      const over = unsatisfiedNested(
        [sauces],
        { sauces: ['chili'] },
        { chili: { heat: ['hot'], extras: ['cheese', 'bacon', 'egg'] } },
      );
      expect(over.map((entry) => entry.group.id)).toEqual(['extras']);
    });
  });

  describe('nestedOnTheWire', () => {
    it("lists the answers under their parent in the dish's own order", () => {
      expect(
        nestedOnTheWire(
          [sauces],
          { sauces: ['chili'] },
          { chili: { extras: ['bacon', 'cheese'], heat: ['hot'] }, gone: { x: ['y'] } },
        ),
      ).toEqual([
        { parentOptionId: 'chili', optionId: 'hot' },
        { parentOptionId: 'chili', optionId: 'bacon' },
        { parentOptionId: 'chili', optionId: 'cheese' },
      ]);
    });
  });

  describe('unsatisfiedNestedFor', () => {
    it('checks a cart line as it travels: option ids and parent/option pairs', () => {
      expect(unsatisfiedNestedFor([sauces], ['chili'], []).map((entry) => entry.group.id)).toEqual([
        'heat',
      ]);
      expect(
        unsatisfiedNestedFor([sauces], ['chili'], [{ parentOptionId: 'chili', optionId: 'hot' }]),
      ).toEqual([]);
      expect(unsatisfiedNestedFor([sauces], ['garlic'], [])).toEqual([]);
    });
  });

  describe('nestedKeyHash', () => {
    const pair = { parentOptionId: 'chili', optionId: 'hot' };

    it('is the same for the same choice whatever order it was made in', () => {
      const other = { parentOptionId: 'chili', optionId: 'cheese' };
      expect(nestedKeyHash(['b', 'a'], [pair, other])).toBe(
        nestedKeyHash(['a', 'b'], [other, pair]),
      );
    });

    it('differs when the answer, the parent or the first-level choice differs', () => {
      const base = nestedKeyHash(['chili'], [pair]);
      expect(nestedKeyHash(['chili'], [{ ...pair, optionId: 'mild' }])).not.toBe(base);
      expect(nestedKeyHash(['chili', 'garlic'], [pair])).not.toBe(base);
      expect(nestedKeyHash(['chili'], [{ ...pair, parentOptionId: 'other' }])).not.toBe(base);
    });
  });
});
