import type { MenuItemComboComponent, MenuItemComboGroup } from '../types/home.types';
import {
  canBeSatisfied,
  ceilingOf,
  comboFromAmountMinor,
  comboKeyHash,
  comboUnitAmountMinor,
  comboValid,
  picksOnTheWire,
  samePicks,
  selectedInGroup,
  setComponentQuantity,
  toggleComponent,
  unmetGroups,
} from './combo-selection';

const component = (
  id: string,
  overrides: Partial<MenuItemComboComponent> = {},
): MenuItemComboComponent => ({
  id,
  name: id.toUpperCase(),
  variantName: null,
  defaultQuantity: 1,
  active: true,
  amountMinor: 10_000,
  ...overrides,
});

const group = (overrides: Partial<MenuItemComboGroup> = {}): MenuItemComboGroup => ({
  id: 'main',
  name: 'Main',
  minimumSelections: 1,
  maximumSelections: 1,
  allowSameComponentMultipleTimes: false,
  components: [component('burger'), component('wrap')],
  ...overrides,
});

describe('combo selection rules (ADR 0136)', () => {
  it('a one-pick group replaces its pick, and takes it back only where the minimum is zero', () => {
    const required = group();
    const [burger, wrap] = required.components;

    const first = toggleComponent({}, required, burger);
    const second = toggleComponent(first, required, wrap);
    const again = toggleComponent(second, required, wrap);

    expect(first).toEqual({ burger: 1 });
    expect(second).toEqual({ wrap: 1 });
    expect(again).toEqual({ wrap: 1 });

    const optional = group({ minimumSelections: 0 });
    expect(toggleComponent(toggleComponent({}, optional, burger), optional, burger)).toEqual({});
  });

  it('a wider group takes picks up to its maximum and refuses the next', () => {
    const wide = group({
      minimumSelections: 0,
      maximumSelections: 2,
      components: [component('a'), component('b'), component('c')],
    });
    const [a, b, c] = wide.components;

    let picks = toggleComponent({}, wide, a);
    picks = toggleComponent(picks, wide, b);

    expect(toggleComponent(picks, wide, c)).toEqual({ a: 1, b: 1 });
    expect(selectedInGroup(wide, picks)).toBe(2);
  });

  it('never picks a component that is sold out', () => {
    const stopped = group({
      components: [component('burger', { active: false }), component('wrap')],
    });

    expect(toggleComponent({}, stopped, stopped.components[0])).toEqual({});
    expect(setComponentQuantity({}, stopped, stopped.components[0], 2)).toEqual({});
  });

  it('holds a stepper to what the group still has room for, counting what the component already holds', () => {
    const repeats = group({
      minimumSelections: 1,
      maximumSelections: 3,
      allowSameComponentMultipleTimes: true,
      components: [component('cola'), component('juice')],
    });
    const [cola, juice] = repeats.components;

    let picks = setComponentQuantity({}, repeats, cola, 2);
    expect(ceilingOf(repeats, juice, picks)).toBe(1);
    expect(ceilingOf(repeats, cola, picks)).toBe(3);

    picks = setComponentQuantity(picks, repeats, juice, 5);
    expect(picks).toEqual({ cola: 2, juice: 1 });

    expect(setComponentQuantity(picks, repeats, cola, 0)).toEqual({ juice: 1 });
  });

  it('names the groups still short, and calls a combo valid only when every group is within its range', () => {
    const groups = [group(), group({ id: 'drink', minimumSelections: 0, maximumSelections: 1 })];

    expect(unmetGroups(groups, {}).map((g) => g.id)).toEqual(['main']);
    expect(comboValid(groups, {})).toBe(false);
    expect(comboValid(groups, { burger: 1 })).toBe(true);
    expect(comboValid([group({ maximumSelections: 1 })], { burger: 1, wrap: 1 })).toBe(false);
  });

  it('knows a group no orderable component can fill', () => {
    const none = group({
      components: [component('a', { active: false }), component('b', { active: false })],
    });
    const repeating = group({
      minimumSelections: 2,
      maximumSelections: 2,
      allowSameComponentMultipleTimes: true,
      components: [component('a'), component('b', { active: false })],
    });

    expect(canBeSatisfied(none)).toBe(false);
    expect(canBeSatisfied(repeating)).toBe(true);
    expect(
      canBeSatisfied(
        group({
          minimumSelections: 2,
          components: [component('a'), component('b', { active: false })],
        }),
      ),
    ).toBe(false);
    expect(canBeSatisfied(group({ minimumSelections: 0, components: none.components }))).toBe(true);
  });
});

describe('what a combo costs (ADR 0136)', () => {
  const groups = [
    group({
      components: [
        component('burger', { amountMinor: 25_000 }),
        component('wrap', { amountMinor: 22_000 }),
      ],
    }),
    group({
      id: 'drink',
      minimumSelections: 0,
      maximumSelections: 1,
      components: [component('cola', { amountMinor: 3_000, defaultQuantity: 2 })],
    }),
  ];

  it('is the sum of the picked components per unit, times units per pick, times picks', () => {
    expect(comboUnitAmountMinor(groups, { burger: 1, cola: 1 })).toBe(31_000);
    expect(comboUnitAmountMinor(groups, {})).toBe(0);
  });

  it('is unknown while a picked component has no price: never a smaller sum', () => {
    const unpriced = [group({ components: [component('burger', { amountMinor: null })] })];

    expect(comboUnitAmountMinor(unpriced, { burger: 1 })).toBeNull();
    expect(comboUnitAmountMinor(unpriced, {})).toBe(0);
  });

  it('shows the least a combo can cost: each choice’s cheapest way to its minimum', () => {
    expect(comboFromAmountMinor(groups)).toBe(22_000);
    expect(
      comboFromAmountMinor([
        group({
          minimumSelections: 2,
          maximumSelections: 3,
          components: [
            component('a', { amountMinor: 5_000 }),
            component('b', { amountMinor: 1_000 }),
            component('c', { amountMinor: 3_000 }),
          ],
        }),
      ]),
    ).toBe(4_000);
  });

  it('counts a repeating group at its cheapest component for every pick', () => {
    expect(
      comboFromAmountMinor([
        group({
          minimumSelections: 3,
          maximumSelections: 3,
          allowSameComponentMultipleTimes: true,
          components: [
            component('a', { amountMinor: 2_000 }),
            component('b', { amountMinor: 1_500 }),
          ],
        }),
      ]),
    ).toBe(4_500);
  });

  it('says nothing rather than something false when a needed component is unpriced or the minimum cannot be reached', () => {
    expect(
      comboFromAmountMinor([
        group({ components: [component('a', { amountMinor: null }), component('b')] }),
      ]),
    ).toBeNull();
    expect(
      comboFromAmountMinor([group({ minimumSelections: 3, maximumSelections: 3 })]),
    ).toBeNull();
    expect(comboFromAmountMinor([group({ minimumSelections: 0 })])).toBe(0);
  });

  it('says nothing when a choice that must be made has no orderable component at all', () => {
    expect(
      comboFromAmountMinor([
        group({ components: [component('a', { amountMinor: 1_000, active: false })] }),
      ]),
    ).toBeNull();
  });

  it('ignores a sold-out component when working out the least', () => {
    expect(
      comboFromAmountMinor([
        group({
          components: [
            component('a', { amountMinor: 1_000, active: false }),
            component('b', { amountMinor: 9_000 }),
          ],
        }),
      ]),
    ).toBe(9_000);
  });
});

describe('the wire form of a combo selection', () => {
  const groups = [group({ components: [component('b-two'), component('a-one')] })];

  it('lists the picks sorted by component, so the same choice is always the same list', () => {
    expect(picksOnTheWire(groups, { 'b-two': 1, 'a-one': 2 })).toEqual([
      { componentId: 'a-one', quantity: 2 },
      { componentId: 'b-two', quantity: 1 },
    ]);
  });

  it('recognises one combo whatever order its picks were made in, and tells different picks apart', () => {
    const left = [
      { componentId: 'a', quantity: 1 },
      { componentId: 'b', quantity: 2 },
    ];

    expect(
      samePicks(left, [
        { componentId: 'b', quantity: 2 },
        { componentId: 'a', quantity: 1 },
      ]),
    ).toBe(true);
    expect(
      samePicks(left, [
        { componentId: 'a', quantity: 1 },
        { componentId: 'b', quantity: 1 },
      ]),
    ).toBe(false);
    expect(samePicks(left, [{ componentId: 'a', quantity: 1 }])).toBe(false);
  });

  it('hashes a selection into a short stable key: the same choice twice, any order, one key; another choice, another key', () => {
    const a = comboKeyHash([
      { componentId: 'x', quantity: 1 },
      { componentId: 'y', quantity: 1 },
    ]);
    const reordered = comboKeyHash([
      { componentId: 'y', quantity: 1 },
      { componentId: 'x', quantity: 1 },
    ]);
    const other = comboKeyHash([
      { componentId: 'x', quantity: 2 },
      { componentId: 'y', quantity: 1 },
    ]);
    const withModifier = comboKeyHash(
      [
        { componentId: 'x', quantity: 1 },
        { componentId: 'y', quantity: 1 },
      ],
      ['m1'],
    );

    expect(a).toBe(reordered);
    expect(a).not.toBe(other);
    expect(a).not.toBe(withModifier);
    expect(a).toMatch(/^[0-9a-f]{14}$/);
  });
});
