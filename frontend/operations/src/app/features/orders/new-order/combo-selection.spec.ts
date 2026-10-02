import { describe, expect, it } from 'vitest';

import {
  canBeSatisfied,
  ceilingOf,
  picksOf,
  resolvePicks,
  selectedInGroup,
  setComponentQuantity,
  toggleComponent,
  unmetGroups,
} from './combo-selection';
import { MenuComboComponent, MenuComboGroup } from './new-order-api';

const component = (
  id: string,
  name: string,
  overrides: Partial<MenuComboComponent> = {},
): MenuComboComponent => ({
  componentId: id,
  variantId: `${id}-variant`,
  productId: null,
  name,
  variantName: null,
  defaultQuantity: 1,
  sortOrder: 0,
  orderable: true,
  amountMinor: 10_000,
  ...overrides,
});

const group = (overrides: Partial<MenuComboGroup> = {}): MenuComboGroup => ({
  comboGroupId: 'g-main',
  containerVariantId: 'lunch',
  code: 'MAIN',
  name: 'Main',
  minimumSelections: 1,
  maximumSelections: 1,
  allowSameComponentMultipleTimes: false,
  sortOrder: 0,
  components: [component('burger', 'Burger'), component('wrap', 'Wrap')],
  ...overrides,
});

describe('combo selection rules', () => {
  it('a one-pick group replaces its pick, and takes it back only where the minimum is zero', () => {
    const [burger, wrap] = group().components;
    const required = group();

    const first = toggleComponent(new Map(), required, burger);
    const second = toggleComponent(first, required, wrap);
    const again = toggleComponent(second, required, wrap);

    expect([...first.keys()]).toEqual(['burger']);
    expect([...second.keys()]).toEqual(['wrap']);
    expect([...again.keys()]).toEqual(['wrap']);

    const optional = group({ minimumSelections: 0 });
    const taken = toggleComponent(toggleComponent(new Map(), optional, burger), optional, burger);
    expect(taken.size).toBe(0);
  });

  it('a wider group takes picks up to its maximum and refuses the next', () => {
    const wide = group({
      minimumSelections: 0,
      maximumSelections: 2,
      components: [component('a', 'A'), component('b', 'B'), component('c', 'C')],
    });
    const [a, b, c] = wide.components;

    let picked = toggleComponent(new Map(), wide, a);
    picked = toggleComponent(picked, wide, b);
    const refused = toggleComponent(picked, wide, c);

    expect(selectedInGroup(wide, refused)).toBe(2);
    expect(refused.has('c')).toBe(false);
  });

  it('never picks a component that is not orderable', () => {
    const stopped = group({
      components: [component('burger', 'Burger', { orderable: false }), component('wrap', 'Wrap')],
    });

    const picked = toggleComponent(new Map(), stopped, stopped.components[0]);

    expect(picked.size).toBe(0);
    expect(setComponentQuantity(new Map(), stopped, stopped.components[0], 2).size).toBe(0);
  });

  it('a stepper is held to what the group still has room for, counting what the component already holds', () => {
    const repeats = group({
      minimumSelections: 1,
      maximumSelections: 3,
      allowSameComponentMultipleTimes: true,
      components: [component('cola', 'Cola'), component('juice', 'Juice')],
    });
    const [cola, juice] = repeats.components;

    let picked = setComponentQuantity(new Map(), repeats, cola, 2);
    expect(ceilingOf(repeats, juice, picked)).toBe(1);
    expect(ceilingOf(repeats, cola, picked)).toBe(3);

    picked = setComponentQuantity(picked, repeats, juice, 5);
    expect(picked.get('juice')).toBe(1);
    expect(selectedInGroup(repeats, picked)).toBe(3);

    picked = setComponentQuantity(picked, repeats, cola, 0);
    expect(picked.has('cola')).toBe(false);
  });

  it('names the groups still short of their minimum', () => {
    const groups = [
      group(),
      group({ comboGroupId: 'g-drink', minimumSelections: 0, maximumSelections: 1 }),
    ];

    expect(unmetGroups(groups, new Map()).map((g) => g.comboGroupId)).toEqual(['g-main']);
    expect(unmetGroups(groups, new Map([['burger', 1]]))).toEqual([]);
  });

  it('knows a group no orderable component can fill', () => {
    const none = group({
      components: [
        component('a', 'A', { orderable: false }),
        component('b', 'B', { orderable: false }),
      ],
    });
    const oneOfTwo = group({
      minimumSelections: 2,
      maximumSelections: 2,
      components: [component('a', 'A'), component('b', 'B', { orderable: false })],
    });
    const repeating = group({
      minimumSelections: 2,
      maximumSelections: 2,
      allowSameComponentMultipleTimes: true,
      components: [component('a', 'A'), component('b', 'B', { orderable: false })],
    });

    expect(canBeSatisfied(none)).toBe(false);
    expect(canBeSatisfied(oneOfTwo)).toBe(false);
    expect(canBeSatisfied(repeating)).toBe(true);
    expect(canBeSatisfied(group({ minimumSelections: 0, components: none.components }))).toBe(true);
  });

  it('turns picks into basket entries in the groups’ own order, each at its own price', () => {
    const groups = [
      group({ components: [component('burger', 'Burger', { amountMinor: 25_000 })] }),
      group({
        comboGroupId: 'g-drink',
        components: [
          component('cola', 'Cola', { amountMinor: 0, variantName: '0.5 L', defaultQuantity: 2 }),
        ],
      }),
    ];

    const picks = picksOf(
      groups,
      new Map([
        ['cola', 1],
        ['burger', 1],
      ]),
    );

    expect(picks).toEqual([
      {
        componentId: 'burger',
        name: 'Burger',
        pickQuantity: 1,
        unitQuantity: 1,
        amountMinor: 25_000,
      },
      { componentId: 'cola', name: 'Cola 0.5 L', pickQuantity: 1, unitQuantity: 2, amountMinor: 0 },
    ]);
  });

  it('rebuilds a repeat from today’s menu, and refuses to when a pick is no longer offered', () => {
    const groups = [group()];

    expect(
      resolvePicks(groups, [{ componentId: 'wrap', quantity: 1 }])?.map((p) => p.name),
    ).toEqual(['Wrap']);
    expect(resolvePicks(groups, [{ componentId: 'gone', quantity: 1 }])).toBeNull();
  });
});
